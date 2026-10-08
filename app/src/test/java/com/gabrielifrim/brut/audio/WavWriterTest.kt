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

    private fun record(format: AudioFormatSpec, samples: FloatArray): ByteArray {
        val file = tmp.newFile()
        val writer = WavWriter(RandomAccessFile(file, "rw").channel, format)
        val bytes = ByteArray(samples.size * format.bitDepth.bytesPerSample)
        val n = SampleConverter.encode(samples, samples.size, format.bitDepth, bytes)
        writer.write(bytes, n)
        writer.close()
        return file.readBytes()
    }

    private fun ByteArray.le() = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    private fun ByteArray.ascii(at: Int) = String(this, at, 4, Charsets.US_ASCII)

    @Test
    fun `16 bit stereo produit un en-tete PCM standard de 44 octets`() {
        val out = record(AudioFormatSpec(48_000, BitDepth.PCM_16, 2), FloatArray(8) { 0.5f })
        val b = out.le()
        assertThat(out.ascii(0)).isEqualTo("RIFF")
        assertThat(out.ascii(8)).isEqualTo("WAVE")
        assertThat(b.getShort(20).toInt()).isEqualTo(1)          // PCM
        assertThat(b.getShort(22).toInt()).isEqualTo(2)          // canaux
        assertThat(b.getInt(24)).isEqualTo(48_000)
        assertThat(b.getInt(28)).isEqualTo(48_000 * 4)           // octets / s
        assertThat(b.getShort(32).toInt()).isEqualTo(4)          // alignement
        assertThat(b.getShort(34).toInt()).isEqualTo(16)
        assertThat(out.ascii(36)).isEqualTo("data")
        assertThat(b.getInt(40)).isEqualTo(16)
        assertThat(b.getInt(4)).isEqualTo(out.size - 8)
        assertThat(out.size).isEqualTo(44 + 16)
        assertThat(b.getShort(44)).isEqualTo(16384.toShort())
    }

    @Test
    fun `24 bit utilise WAVE_FORMAT_EXTENSIBLE et empaquete sur 3 octets`() {
        val out = record(AudioFormatSpec(96_000, BitDepth.PCM_24, 1), floatArrayOf(-1f, 0.5f))
        val b = out.le()
        assertThat(b.getShort(20).toInt() and 0xFFFF).isEqualTo(0xFFFE)
        assertThat(b.getShort(34).toInt()).isEqualTo(24)
        assertThat(b.getShort(38).toInt()).isEqualTo(24)         // bits valides
        assertThat(out.ascii(60)).isEqualTo("data")
        assertThat(b.getInt(64)).isEqualTo(6)
        // −1.0 -> 0x800000 ; 0.5 -> 0x400000, petit-boutiste.
        assertThat(out.copyOfRange(68, 74).toList()).containsExactly(
            0x00.toByte(), 0x00.toByte(), 0x80.toByte(), 0x00.toByte(), 0x00.toByte(), 0x40.toByte(),
        ).inOrder()
        assertThat(b.getInt(4)).isEqualTo(out.size - 8)
    }

    @Test
    fun `32 bit flottant ecrit le format 3 et un chunk fact a jour`() {
        val samples = floatArrayOf(1.5f, -0.25f, 0f, 0.75f)
        val out = record(AudioFormatSpec(44_100, BitDepth.FLOAT_32, 2), samples)
        val b = out.le()
        assertThat(b.getShort(20).toInt()).isEqualTo(3)
        assertThat(out.ascii(38)).isEqualTo("fact")
        assertThat(b.getInt(46)).isEqualTo(2)                    // 2 trames stéréo
        assertThat(out.ascii(50)).isEqualTo("data")
        // Au-delà de 0 dBFS, le flottant n'est pas écrêté.
        assertThat(b.getFloat(58)).isEqualTo(1.5f)
        assertThat(b.getFloat(62)).isEqualTo(-0.25f)
    }

    @Test
    fun `un nombre impair d'octets de donnees recoit un octet de bourrage`() {
        val out = record(AudioFormatSpec(48_000, BitDepth.PCM_24, 1), floatArrayOf(0.1f))
        val b = out.le()
        assertThat(b.getInt(64)).isEqualTo(3)
        assertThat(out.size).isEqualTo(68 + 4)
        assertThat(b.getInt(4)).isEqualTo(out.size - 8)
    }

    @Test
    fun `l'en-tete intermediaire rend le fichier lisible avant la fermeture`() {
        val file: File = tmp.newFile()
        val channel = RandomAccessFile(file, "rw").channel
        val writer = WavWriter(channel, AudioFormatSpec(48_000, BitDepth.PCM_16, 1))
        writer.write(ByteArray(100), 100)
        writer.updateHeader()
        val snapshot = file.readBytes()
        assertThat(snapshot.le().getInt(40)).isEqualTo(100)
        assertThat(snapshot.le().getInt(4)).isEqualTo(36 + 100)
        writer.close()
    }
}
