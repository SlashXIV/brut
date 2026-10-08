package com.gabrielifrim.brut.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PrerollBufferTest {

    private fun drained(buffer: PrerollBuffer, chunkSamples: Int): List<Float> {
        val out = mutableListOf<Float>()
        buffer.drain(FloatArray(chunkSamples)) { chunk, frames -> repeat(frames * 2) { out += chunk[it] } }
        return out
    }

    @Test
    fun `garde les dernières trames, dans l'ordre, et oublie les plus anciennes`() {
        val buffer = PrerollBuffer(channels = 2, capacityFrames = 3)
        // 5 trames stéréo : (1,-1) (2,-2) ... (5,-5)
        val signal = FloatArray(10) { i -> (i / 2 + 1f) * if (i % 2 == 0) 1 else -1 }
        buffer.push(signal, 5)
        assertThat(buffer.frames).isEqualTo(3)
        assertThat(drained(buffer, 4)).containsExactly(3f, -3f, 4f, -4f, 5f, -5f).inOrder()
        assertThat(buffer.frames).isEqualTo(0)
    }

    @Test
    fun `un tampon de capacité nulle ne retient rien`() {
        val buffer = PrerollBuffer(channels = 1, capacityFrames = 0)
        buffer.push(floatArrayOf(1f, 2f), 2)
        assertThat(buffer.frames).isEqualTo(0)
        assertThat(drained(buffer, 8)).isEmpty()
    }

    @Test
    fun `reste utilisable après un vidage`() {
        val buffer = PrerollBuffer(channels = 2, capacityFrames = 4)
        buffer.push(FloatArray(6) { it.toFloat() }, 3)
        drained(buffer, 2)
        buffer.push(floatArrayOf(9f, -9f), 1)
        assertThat(drained(buffer, 8)).containsExactly(9f, -9f).inOrder()
    }
}
