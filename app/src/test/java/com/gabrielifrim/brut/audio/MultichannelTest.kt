package com.gabrielifrim.brut.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MultichannelTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Signal où chaque voie a sa propre valeur : on voit tout de suite une voie mal rangée. */
    private fun distinct(frames: Int, channels: Int) = FloatArray(frames * channels) { i ->
        val f = i / channels
        val c = i % channels
        (c + 1) / 16f * (if (f % 2 == 0) 1f else -1f)
    }

    private fun write(format: AudioFormatSpec, samples: FloatArray, threshold: Long = 0xFFFF_FFFFL): File {
        val file = tmp.newFile()
        WavWriter(RandomAccessFile(file, "rw").channel, format, rf64Threshold = threshold).use { w ->
            val bytes = ByteArray(samples.size * format.bitDepth.bytesPerSample)
            w.write(bytes, SampleConverter.encode(samples, samples.size, format.bitDepth, bytes))
        }
        return file
    }

    private fun readAll(file: File): Pair<WavInfo, FloatArray> = RandomAccessFile(file, "r").channel.use { ch ->
        val i = WavReader.readInfo(ch)
        i to FloatArray((i.frames * i.channels).toInt()).also { WavReader.readFrames(ch, i, 0, i.frames.toInt(), it) }
    }

    private fun fmtBody(bytes: ByteArray): ByteBuffer {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        while (String(bytes, p, 4, Charsets.US_ASCII) != "fmt ") p += 8 + b.getInt(p + 4)
        return ByteBuffer.wrap(bytes, p + 8, 40).slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    @Test
    fun `4 et 8 voies, toutes résolutions, relues voie par voie`() {
        for (channels in listOf(4, 6, 8)) for (depth in BitDepth.entries) {
            val src = distinct(500, channels)
            val (info, out) = readAll(write(AudioFormatSpec(48_000, depth, channels), src))
            assertThat(info.channels).isEqualTo(channels)
            assertThat(info.isFloat).isEqualTo(depth.isFloat)
            assertThat(info.frames).isEqualTo(500)
            assertThat(out).usingTolerance(1e-4).containsExactly(*src.toTypedArray()).inOrder()
        }
    }

    @Test
    fun `plus de 2 voies impose EXTENSIBLE, sans affectation de haut-parleurs`() {
        val pcm = fmtBody(write(AudioFormatSpec(48_000, BitDepth.PCM_16, 4), distinct(10, 4)).readBytes())
        assertThat(pcm.getShort(0).toInt() and 0xFFFF).isEqualTo(0xFFFE)
        assertThat(pcm.getShort(2).toInt()).isEqualTo(4)
        assertThat(pcm.getInt(20)).isEqualTo(0) // masque de voies
        assertThat(pcm.getShort(24).toInt()).isEqualTo(1) // sous-format PCM
        val float = fmtBody(write(AudioFormatSpec(48_000, BitDepth.FLOAT_32, 4), distinct(10, 4)).readBytes())
        assertThat(float.getShort(24).toInt()).isEqualTo(3) // sous-format flottant
        // Mono et stéréo gardent leur en-tête historique.
        val stereo = fmtBody(write(AudioFormatSpec(48_000, BitDepth.PCM_16, 2), distinct(10, 2)).readBytes())
        assertThat(stereo.getShort(0).toInt()).isEqualTo(1)
    }

    @Test
    fun `8 voies en flottant passent en RF64 avec un chunk fact juste`() {
        val src = distinct(2_000, 8)
        val file = write(AudioFormatSpec(96_000, BitDepth.FLOAT_32, 8), src, threshold = 1_000)
        val bytes = file.readBytes()
        assertThat(String(bytes, 0, 4, Charsets.US_ASCII)).isEqualTo("RF64")
        val (info, out) = readAll(file)
        assertThat(info.frames).isEqualTo(2_000)
        assertThat(out).isEqualTo(src)
    }

    @Test
    fun `une prise 4 voies interrompue se répare`() {
        val file = write(AudioFormatSpec(48_000, BitDepth.PCM_24, 4), distinct(1_000, 4))
        // Simule une coupure : en-tête remis à zéro et une trame incomplète en fin de fichier.
        RandomAccessFile(file, "rw").use { raf ->
            val bytes = file.readBytes()
            var p = 12
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            while (String(bytes, p, 4, Charsets.US_ASCII) != "data") p += 8 + b.getInt(p + 4)
            raf.seek(p + 4L); raf.write(byteArrayOf(0, 0, 0, 0))
            raf.seek(raf.length()); raf.write(ByteArray(5))
        }
        RandomAccessFile(file, "rw").channel.use { assertThat(WavRepair.repair(it)).isEqualTo(1_000) }
        assertThat(readAll(file).first.frames).isEqualTo(1_000)
    }

    @Test
    fun `l'historique de codage note une prise multipiste`() {
        assertThat(Bext.codingHistoryFor(AudioFormatSpec(48_000, BitDepth.PCM_24, 4), "Brut")).contains("M=multitrack")
    }
}
