package com.gabrielifrim.brut.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import kotlin.coroutines.cancellation.CancellationException

class WavExportTest {

    @get:Rule val tmp = TemporaryFolder()

    private val bext = Bext(
        description = "Entrée : Zoom H1n",
        originator = "Brut",
        originatorReference = "Brut_2026-10-09_10-00-00",
        date = LocalDateTime.of(2026, 10, 9, 10, 0, 0),
        timeReference = 36_000L * 48_000,
        codingHistory = Bext.codingHistoryFor(AudioFormatSpec(48_000, BitDepth.PCM_24, 2), "Brut 0.5.0"),
    )

    /** Rampe stéréo : gauche et droite différentes, aucune trame identique à une autre. */
    private fun ramp(frames: Int, channels: Int) = FloatArray(frames * channels) { i ->
        val f = i / channels
        val c = i % channels
        ((f % 2000) - 1000) / 1024f * (if (c == 0) 1f else -0.5f)
    }

    private fun write(format: AudioFormatSpec, samples: FloatArray, markers: List<Marker> = emptyList()): File {
        val file = tmp.newFile()
        WavWriter(RandomAccessFile(file, "rw").channel, format, listOf(Bext.CHUNK_ID to bext.encode())).use { w ->
            markers.forEach { w.addMarker(it.label, it.frame) }
            val bytes = ByteArray(samples.size * format.bitDepth.bytesPerSample)
            w.write(bytes, SampleConverter.encode(samples, samples.size, format.bitDepth, bytes))
        }
        return file
    }

    private fun info(file: File) = RandomAccessFile(file, "r").channel.use { WavReader.readInfo(it) }

    private fun export(source: File, spec: ExportSpec, markers: List<Marker> = emptyList()): Pair<File, ExportResult> {
        val out = tmp.newFile()
        val result = RandomAccessFile(source, "r").channel.use { input ->
            WavExport.export(input, WavReader.readInfo(input), spec, RandomAccessFile(out, "rw").channel, markers = markers)
        }
        return out to result
    }

    private fun dataBytes(file: File): ByteArray {
        val i = info(file)
        return file.readBytes().copyOfRange(i.dataOffset.toInt(), (i.dataOffset + i.dataBytes).toInt())
    }

    private fun samples(file: File): FloatArray = RandomAccessFile(file, "r").channel.use { ch ->
        val i = WavReader.readInfo(ch)
        FloatArray((i.frames * i.channels).toInt()).also { WavReader.readFrames(ch, i, 0, i.frames.toInt(), it) }
    }

    @Test
    fun `extrait à résolution d'origine identique octet par octet`() {
        for (depth in BitDepth.entries) {
            val format = AudioFormatSpec(48_000, depth, 2)
            val source = write(format, ramp(20_000, 2))
            val (out, result) = export(source, ExportSpec(1_234, 17_000))
            val bpf = format.bytesPerFrame
            val expected = dataBytes(source).copyOfRange(1_234 * bpf, 17_000 * bpf)
            assertThat(result.frames).isEqualTo(17_000 - 1_234)
            assertThat(result.clippedSamples).isEqualTo(0)
            assertThat(dataBytes(out)).isEqualTo(expected)
            assertThat(info(out).bitsPerSample).isEqualTo(depth.bits)
        }
    }

    @Test
    fun `extraction d'une voie recopie ses échantillons tels quels`() {
        val source = write(AudioFormatSpec(48_000, BitDepth.PCM_24, 2), ramp(10_000, 2))
        val all = dataBytes(source)
        val (left, _) = export(source, ExportSpec(0, 10_000, channels = ChannelPick.LEFT))
        val (right, _) = export(source, ExportSpec(0, 10_000, channels = ChannelPick.RIGHT))
        assertThat(info(left).channels).isEqualTo(1)
        val l = dataBytes(left)
        val r = dataBytes(right)
        for (f in 0 until 10_000) {
            for (b in 0 until 3) {
                assertThat(l[f * 3 + b]).isEqualTo(all[f * 6 + b])
                assertThat(r[f * 3 + b]).isEqualTo(all[f * 6 + 3 + b])
            }
        }
    }

    @Test
    fun `conversion vers une résolution plus haute sans perte`() {
        val source = write(AudioFormatSpec(44_100, BitDepth.PCM_16, 2), ramp(5_000, 2))
        val original = samples(source)
        for (depth in listOf(BitDepth.PCM_24, BitDepth.FLOAT_32)) {
            val (out, result) = export(source, ExportSpec(0, 5_000, bitDepth = depth))
            assertThat(info(out).bitsPerSample).isEqualTo(depth.bits)
            assertThat(result.clippedSamples).isEqualTo(0)
            // Exact, pas seulement proche : 16 → 24 → 16 retrouve chaque échantillon.
            assertThat(samples(out)).isEqualTo(original)
        }
    }

    @Test
    fun `flottant au-delà de 0 dBFS vers entier compte les écrêtages`() {
        val signal = floatArrayOf(0.5f, 1.5f, -2f, 0.25f, 0.999f, -1f)
        val source = write(AudioFormatSpec(48_000, BitDepth.FLOAT_32, 1), signal)
        val (out, result) = export(source, ExportSpec(0, 6, bitDepth = BitDepth.PCM_24))
        assertThat(result.clippedSamples).isEqualTo(2)
        assertThat(samples(out)[1]).isEqualTo(8_388_607 / 8_388_608f)
        // −1,0 vaut exactement le minimum entier : pas un écrêtage.
        assertThat(samples(out)[5]).isEqualTo(-1f)
    }

    @Test
    fun `découpe aux repères`() {
        val markers = listOf(Marker(0, "Debut"), Marker(3_000, "Couplet"), Marker(3_000, "Double"), Marker(7_500, "Refrain"))
        val i = info(write(AudioFormatSpec(48_000, BitDepth.PCM_16, 1), ramp(10_000, 1), markers))
        assertThat(WavExport.segments(i, 0, 10_000)).containsExactly(0L until 3_000L, 3_000L until 7_500L, 7_500L until 10_000L).inOrder()
        // Dans une sélection rognée, seuls les repères intérieurs coupent.
        assertThat(WavExport.segments(i, 4_000, 9_000)).containsExactly(4_000L until 7_500L, 7_500L until 9_000L).inOrder()
        assertThat(WavExport.markersIn(i, 3_000, 8_000).map { it.frame }).containsExactly(0L, 0L, 4_500L).inOrder()
    }

    @Test
    fun `l'extrait garde ses repères et décale l'horodatage`() {
        val format = AudioFormatSpec(48_000, BitDepth.PCM_24, 2)
        val source = write(format, ramp(96_000, 2), listOf(Marker(60_000, "Applaudissements")))
        val i = info(source)
        val spec = ExportSpec(48_000, 96_000)
        val derived = WavExport.derivedBext(i.bext!!, i, spec, format, "Extrait", "Brut 0.6.0", "Extrait de test")
        assertThat(derived.timeReference).isEqualTo(bext.timeReference + 48_000)
        assertThat(derived.date).isEqualTo(bext.date.plusSeconds(1))
        assertThat(derived.codingHistory).startsWith(bext.codingHistory)
        assertThat(derived.codingHistory).endsWith("T=Brut 0.6.0\r\n")

        val out = tmp.newFile()
        RandomAccessFile(source, "r").channel.use { input ->
            WavExport.export(
                input, i, spec, RandomAccessFile(out, "rw").channel,
                extraChunks = listOf(Bext.CHUNK_ID to derived.encode()),
                markers = WavExport.markersIn(i, spec.startFrame, spec.endFrame),
            )
        }
        val o = info(out)
        assertThat(o.bext!!.timeReference).isEqualTo(bext.timeReference + 48_000)
        assertThat(o.markers).containsExactly(Marker(12_000, "Applaudissements"))
        assertThat(o.frames).isEqualTo(48_000)
    }

    @Test(expected = CancellationException::class)
    fun `export annulé`() {
        val source = write(AudioFormatSpec(48_000, BitDepth.PCM_16, 1), ramp(50_000, 1))
        RandomAccessFile(source, "r").channel.use { input ->
            WavExport.export(input, WavReader.readInfo(input), ExportSpec(0, 50_000), RandomAccessFile(tmp.newFile(), "rw").channel, isActive = { false })
        }
    }

    @Test
    fun `export à 44,1 kHz, longueur, repères et horodatage suivent la fréquence`() {
        val format = AudioFormatSpec(48_000, BitDepth.PCM_24, 2)
        val source = write(format, ramp(96_000, 2), listOf(Marker(72_000, "Refrain")))
        val i = info(source)
        val spec = ExportSpec(24_000, 96_000, sampleRate = 44_100)
        assertThat(WavExport.isBitExact(i, spec)).isFalse()
        val target = WavExport.targetFormat(i, spec)
        val derived = WavExport.derivedBext(i.bext!!, i, spec, target, "Extrait", "Brut 0.9.0", "test")
        assertThat(derived.timeReference).isEqualTo((bext.timeReference + 24_000) * 44_100 / 48_000)
        assertThat(derived.codingHistory).endsWith("F=44100,W=24,M=stereo,T=Brut 0.9.0; SRC sinc\r\n")

        val (out, result) = export(source, spec, WavExport.markersIn(i, spec.startFrame, spec.endFrame))
        val o = info(out)
        assertThat(o.sampleRate).isEqualTo(44_100)
        assertThat(result.frames).isEqualTo(66_150) // 1,5 s
        assertThat(o.frames).isEqualTo(66_150)
        assertThat(o.markers).containsExactly(Marker(44_100, "Refrain")) // 48 000 trames après le début = 1 s
    }

    @Test
    fun `bext translittère la typographie française plutôt que d'écrire des points d'interrogation`() {
        assertThat(Bext.ascii("Extrait de « Prise », 00:01 → 00:02 ; l’entrée −6 dB"))
            .isEqualTo("Extrait de \" Prise \", 00:01 -> 00:02 ; l'entree -6 dB")
    }
}
