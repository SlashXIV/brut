package com.gabrielifrim.brut.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime

class WavReaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val bext = Bext(
        description = "Entrée : Zoom H1n ; gain G +3,0 dB / D −1,5 dB",
        originator = "Brut",
        originatorReference = "Brut_2026-10-08_20-31-05",
        date = LocalDateTime.of(2026, 10, 8, 20, 31, 5),
        timeReference = 73_865L * 48_000,
        codingHistory = Bext.codingHistoryFor(AudioFormatSpec(48_000, BitDepth.PCM_24, 2), "Brut 0.3.0"),
    )

    private fun write(format: AudioFormatSpec, samples: FloatArray, withBext: Boolean = true): File {
        val file = tmp.newFile()
        val extra = if (withBext) listOf(Bext.CHUNK_ID to bext.encode()) else emptyList()
        WavWriter(RandomAccessFile(file, "rw").channel, format, extra).use { w ->
            val bytes = ByteArray(samples.size * format.bitDepth.bytesPerSample)
            w.write(bytes, SampleConverter.encode(samples, samples.size, format.bitDepth, bytes))
        }
        return file
    }

    private fun readAll(file: File): Pair<WavInfo, FloatArray> =
        RandomAccessFile(file, "r").channel.use { ch ->
            val info = WavReader.readInfo(ch)
            val out = FloatArray((info.frames * info.channels).toInt())
            WavReader.readFrames(ch, info, 0, info.frames.toInt(), out)
            info to out
        }

    private val signal = floatArrayOf(0.5f, -0.25f, 0.125f, -1f, 0.75f, 0f)

    @Test
    fun `aller-retour 16 bit`() {
        val (info, out) = readAll(write(AudioFormatSpec(44_100, BitDepth.PCM_16, 2), signal))
        assertThat(info.sampleRate).isEqualTo(44_100)
        assertThat(info.bitsPerSample).isEqualTo(16)
        assertThat(info.frames).isEqualTo(3)
        assertThat(out).usingTolerance(1e-4).containsExactly(*signal.toTypedArray()).inOrder()
    }

    @Test
    fun `aller-retour 24 bit EXTENSIBLE`() {
        val (info, out) = readAll(write(AudioFormatSpec(96_000, BitDepth.PCM_24, 1), signal))
        assertThat(info.isFloat).isFalse()
        assertThat(info.channels).isEqualTo(1)
        assertThat(out).usingTolerance(1e-6).containsExactly(*signal.toTypedArray()).inOrder()
    }

    @Test
    fun `aller-retour 32 bit flottant, crêtes au-delà de 0 dBFS comprises`() {
        val hot = floatArrayOf(1.5f, -2f)
        val (info, out) = readAll(write(AudioFormatSpec(48_000, BitDepth.FLOAT_32, 2), hot))
        assertThat(info.isFloat).isTrue()
        assertThat(out).isEqualTo(hot)
    }

    @Test
    fun `le chunk bext est relu à l'identique, accents translittérés en ASCII`() {
        val (info, _) = readAll(write(AudioFormatSpec(48_000, BitDepth.PCM_24, 2), signal))
        val b = info.bext!!
        assertThat(b.description).isEqualTo("Entree : Zoom H1n ; gain G +3,0 dB / D -1,5 dB")
        assertThat(b.originator).isEqualTo("Brut")
        assertThat(b.date).isEqualTo(LocalDateTime.of(2026, 10, 8, 20, 31, 5))
        assertThat(b.timeReference).isEqualTo(73_865L * 48_000)
        assertThat(b.codingHistory).isEqualTo("A=PCM,F=48000,W=24,M=stereo,T=Brut 0.3.0\r\n")
    }

    @Test
    fun `un fichier interrompu dont l'en-tête est resté à zéro reste lisible`() {
        val file = tmp.newFile()
        val format = AudioFormatSpec(48_000, BitDepth.PCM_16, 1)
        val w = WavWriter(RandomAccessFile(file, "rw").channel, format)
        val bytes = ByteArray(200)
        w.write(bytes, 200) // jamais fermé ni mis à jour : tailles d'en-tête à 0
        val (info, _) = readAll(file)
        assertThat(info.frames).isEqualTo(100)
    }

    @Test
    fun `la note iXML garde ses accents et prime sur la description bext`() {
        val file = tmp.newFile()
        val format = AudioFormatSpec(48_000, BitDepth.PCM_24, 2)
        val note = "Entrée : Micro interne ; gain G +3,0 dB & D −1,5 dB <test>"
        WavWriter(
            RandomAccessFile(file, "rw").channel, format,
            listOf(
                Bext.CHUNK_ID to bext.encode(),
                Ixml.CHUNK_ID to Ixml.encode(note, "Brut", "Prise_1", listOf("Gauche", "Droite")),
            ),
        ).use { it.write(ByteArray(12), 12) }
        val (info, _) = readAll(file)
        assertThat(info.note).isEqualTo(note)
        assertThat(info.description).isEqualTo(note)
        assertThat(info.bext!!.originator).isEqualTo("Brut")
        assertThat(info.frames).isEqualTo(2)
    }
}
