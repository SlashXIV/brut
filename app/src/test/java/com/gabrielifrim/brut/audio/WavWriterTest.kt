package com.gabrielifrim.brut.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavWriterTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun record(format: AudioFormatSpec, samples: FloatArray, threshold: Long = 0xFFFF_FFFFL, markers: List<String> = emptyList()): ByteArray {
        val file = tmp.newFile()
        val writer = WavWriter(RandomAccessFile(file, "rw").channel, format, rf64Threshold = threshold)
        val bytes = ByteArray(samples.size * format.bitDepth.bytesPerSample)
        val n = SampleConverter.encode(samples, samples.size, format.bitDepth, bytes)
        writer.write(bytes, n)
        markers.forEach { writer.addMarker(it) }
        writer.close()
        return file.readBytes()
    }

    private fun ByteArray.le() = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    private fun ByteArray.ascii(at: Int) = String(this, at, 4, Charsets.US_ASCII)

    /** Position du corps du premier chunk [id] (après son en-tête de 8 octets). */
    private fun ByteArray.chunk(id: String): Int {
        var p = 12
        while (p + 8 <= size) {
            val n = le().getInt(p + 4).toLong() and 0xFFFF_FFFFL
            if (ascii(p) == id) return p + 8
            p += 8 + n.toInt() + (n.toInt() and 1)
        }
        error("chunk $id absent")
    }

    @Test
    fun `16 bit stéréo, en-tête PCM, chunk JUNK réservé et tailles exactes`() {
        val out = record(AudioFormatSpec(48_000, BitDepth.PCM_16, 2), FloatArray(8) { 0.5f })
        val b = out.le()
        assertThat(out.ascii(0)).isEqualTo("RIFF")
        assertThat(out.ascii(8)).isEqualTo("WAVE")
        assertThat(out.ascii(12)).isEqualTo("JUNK")
        val fmt = out.chunk("fmt ")
        assertThat(b.getShort(fmt).toInt()).isEqualTo(1)           // PCM
        assertThat(b.getShort(fmt + 2).toInt()).isEqualTo(2)       // canaux
        assertThat(b.getInt(fmt + 4)).isEqualTo(48_000)
        assertThat(b.getInt(fmt + 8)).isEqualTo(48_000 * 4)        // octets / s
        assertThat(b.getShort(fmt + 12).toInt()).isEqualTo(4)      // alignement
        assertThat(b.getShort(fmt + 14).toInt()).isEqualTo(16)
        val data = out.chunk("data")
        assertThat(b.getInt(data - 4)).isEqualTo(16)
        assertThat(b.getInt(4)).isEqualTo(out.size - 8)
        assertThat(out.size).isEqualTo(data + 16)
        assertThat(b.getShort(data)).isEqualTo(16384.toShort())
    }

    @Test
    fun `24 bit utilise WAVE_FORMAT_EXTENSIBLE et empaquete sur 3 octets`() {
        val out = record(AudioFormatSpec(96_000, BitDepth.PCM_24, 1), floatArrayOf(-1f, 0.5f))
        val b = out.le()
        val fmt = out.chunk("fmt ")
        assertThat(b.getShort(fmt).toInt() and 0xFFFF).isEqualTo(0xFFFE)
        assertThat(b.getShort(fmt + 14).toInt()).isEqualTo(24)
        assertThat(b.getShort(fmt + 18).toInt()).isEqualTo(24)     // bits valides
        val data = out.chunk("data")
        assertThat(b.getInt(data - 4)).isEqualTo(6)
        // −1.0 -> 0x800000 ; 0.5 -> 0x400000, petit-boutiste.
        assertThat(out.copyOfRange(data, data + 6).toList()).containsExactly(
            0x00.toByte(), 0x00.toByte(), 0x80.toByte(), 0x00.toByte(), 0x00.toByte(), 0x40.toByte(),
        ).inOrder()
        assertThat(b.getInt(4)).isEqualTo(out.size - 8)
    }

    @Test
    fun `32 bit flottant écrit le format 3 et un chunk fact à jour`() {
        val out = record(AudioFormatSpec(44_100, BitDepth.FLOAT_32, 2), floatArrayOf(1.5f, -0.25f, 0f, 0.75f))
        val b = out.le()
        assertThat(b.getShort(out.chunk("fmt ")).toInt()).isEqualTo(3)
        assertThat(b.getInt(out.chunk("fact"))).isEqualTo(2)       // 2 trames stéréo
        val data = out.chunk("data")
        // Au-delà de 0 dBFS, le flottant n'est pas écrêté.
        assertThat(b.getFloat(data)).isEqualTo(1.5f)
        assertThat(b.getFloat(data + 4)).isEqualTo(-0.25f)
    }

    @Test
    fun `un nombre impair d'octets de données reçoit un octet de bourrage`() {
        val out = record(AudioFormatSpec(48_000, BitDepth.PCM_24, 1), floatArrayOf(0.1f))
        val b = out.le()
        val data = out.chunk("data")
        assertThat(b.getInt(data - 4)).isEqualTo(3)
        assertThat(out.size).isEqualTo(data + 4)
        assertThat(b.getInt(4)).isEqualTo(out.size - 8)
    }

    @Test
    fun `l'en-tête intermédiaire rend le fichier lisible avant la fermeture`() {
        val file: File = tmp.newFile()
        val writer = WavWriter(RandomAccessFile(file, "rw").channel, AudioFormatSpec(48_000, BitDepth.PCM_16, 1))
        writer.write(ByteArray(100), 100)
        writer.updateHeader()
        val snapshot = file.readBytes()
        val data = snapshot.chunk("data")
        assertThat(snapshot.le().getInt(data - 4)).isEqualTo(100)
        assertThat(snapshot.le().getInt(4)).isEqualTo(data - 8 + 100)
        writer.close()
    }

    @Test
    fun `au-delà du seuil, le fichier devient un RF64 relu à l'identique`() {
        val samples = FloatArray(64) { it / 64f }
        val out = record(AudioFormatSpec(48_000, BitDepth.FLOAT_32, 2), samples, threshold = 100)
        val b = out.le()
        assertThat(out.ascii(0)).isEqualTo("RF64")
        assertThat(b.getInt(4)).isEqualTo(-1)                      // 0xFFFFFFFF
        assertThat(out.ascii(12)).isEqualTo("ds64")
        assertThat(b.getLong(20)).isEqualTo(out.size - 8L)         // taille RIFF
        assertThat(b.getLong(28)).isEqualTo(256L)                  // taille des données
        assertThat(b.getLong(36)).isEqualTo(32L)                   // trames
        val file = tmp.newFile().also { it.writeBytes(out) }
        RandomAccessFile(file, "r").channel.use { ch ->
            val info = WavReader.readInfo(ch)
            assertThat(info.frames).isEqualTo(32)
            val back = FloatArray(64)
            WavReader.readFrames(ch, info, 0, 32, back)
            assertThat(back).isEqualTo(samples)
        }
    }

    @Test
    fun `les repères sont écrits après les données et relus avec leur étiquette`() {
        val format = AudioFormatSpec(48_000, BitDepth.PCM_16, 1)
        val file = tmp.newFile()
        WavWriter(RandomAccessFile(file, "rw").channel, format).use { w ->
            w.write(ByteArray(200), 200)
            w.addMarker("Micro coupé par le système")
            w.write(ByteArray(100), 100)
            w.addMarker("Micro rendu")
        }
        val out = file.readBytes()
        assertThat(out.le().getInt(4)).isEqualTo(out.size - 8)
        RandomAccessFile(file, "r").channel.use { ch ->
            val info = WavReader.readInfo(ch)
            assertThat(info.frames).isEqualTo(150)
            assertThat(info.markers).containsExactly(
                Marker(100, "Micro coupe par le systeme"),
                Marker(150, "Micro rendu"),
            ).inOrder()
        }
    }

    @Test
    fun `une prise interrompue est réparée, trame incomplète retirée`() {
        val file = tmp.newFile()
        val format = AudioFormatSpec(48_000, BitDepth.PCM_24, 2)
        val writer = WavWriter(RandomAccessFile(file, "rw").channel, format)
        writer.write(ByteArray(600), 600)
        writer.updateHeader()
        writer.write(ByteArray(604), 604) // 600 octets après la dernière mise à jour + 4 octets d'une trame incomplète
        // Pas de close() : l'appli a été tuée.
        val frames = RandomAccessFile(file, "rw").channel.use { WavRepair.repair(it) }
        assertThat(frames).isEqualTo(200)
        RandomAccessFile(file, "r").channel.use { ch ->
            val info = WavReader.readInfo(ch)
            assertThat(info.frames).isEqualTo(200)
        }
        val out = file.readBytes()
        assertThat(out.le().getInt(4)).isEqualTo(out.size - 8)
    }
}
