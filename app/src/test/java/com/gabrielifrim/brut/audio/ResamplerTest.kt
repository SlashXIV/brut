package com.gabrielifrim.brut.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {

    private fun sine(rate: Int, freq: Double, seconds: Double, amp: Double = 0.5, channels: Int = 1): FloatArray {
        val n = (rate * seconds).toInt()
        return FloatArray(n * channels) { i -> (amp * sin(2 * PI * freq * (i / channels) / rate)).toFloat() }
    }

    /** Rééchantillonne en blocs irréguliers, pour exercer le fonctionnement en flux. */
    private fun run(r: Resampler, input: FloatArray): FloatArray {
        val frames = input.size / r.channels
        val out = ArrayList<Float>()
        var pos = 0
        var step = 1000
        while (pos < frames) {
            val n = minOf(step, frames - pos)
            r.process(input.copyOfRange(pos * r.channels, (pos + n) * r.channels), n).forEach(out::add)
            pos += n
            step = if (step == 1000) 777 else 1000
        }
        r.flush().forEach(out::add)
        return out.toFloatArray()
    }

    /** Écart au sinus idéal à la fréquence de sortie, hors bords, en dB sous le signal. */
    private fun residualDb(out: FloatArray, rate: Int, freq: Double, amp: Double = 0.5): Double {
        val skip = rate / 10
        var err = 0.0
        var sig = 0.0
        for (j in skip until out.size - skip) {
            val ideal = amp * sin(2 * PI * freq * j / rate)
            err += (out[j] - ideal) * (out[j] - ideal)
            sig += ideal * ideal
        }
        return 10 * log10(err / sig)
    }

    private fun rmsDb(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return 20 * log10(sqrt(s / (to - from)) + 1e-30)
    }

    @Test
    fun `longueur exacte et gain continu de 1`() {
        val r = Resampler(48_000, 44_100, 2)
        val out = run(r, FloatArray(48_000 * 2) { 0.25f })
        assertThat(out.size / 2).isEqualTo(44_100)
        for (j in 4_000 until 40_000) assertThat(out[j * 2].toDouble()).isWithin(1e-6).of(0.25)
    }

    @Test
    fun `48 vers 44,1 kHz garde un sinus intact`() {
        val out = run(Resampler(48_000, 44_100, 1), sine(48_000, 1_000.0, 1.0))
        assertThat(residualDb(out, 44_100, 1_000.0)).isLessThan(-110.0)
    }

    @Test
    fun `44,1 vers 48 kHz sans image`() {
        val out = run(Resampler(44_100, 48_000, 1), sine(44_100, 1_000.0, 1.0))
        assertThat(residualDb(out, 48_000, 1_000.0)).isLessThan(-110.0)
    }

    @Test
    fun `aucun repliement d'un son au-dessus du nouveau Nyquist`() {
        // 23 kHz existe à 48 kHz mais pas à 44,1 kHz : il doit disparaître, pas revenir à 21,1 kHz.
        val out = run(Resampler(48_000, 44_100, 1), sine(48_000, 23_000.0, 1.0))
        assertThat(rmsDb(out, 4_410, out.size - 4_410)).isLessThan(-110.0)
    }

    @Test
    fun `l'aigu audible passe à 20 kHz`() {
        val out = run(Resampler(96_000, 44_100, 1), sine(96_000, 19_000.0, 1.0))
        assertThat(residualDb(out, 44_100, 19_000.0)).isLessThan(-90.0)
    }

    @Test
    fun `aller-retour 48 - 96 - 48 kHz`() {
        val src = sine(48_000, 3_000.0, 1.0, channels = 2)
        val back = run(Resampler(96_000, 48_000, 2), run(Resampler(48_000, 96_000, 2), src))
        assertThat(back.size).isEqualTo(src.size)
        var err = 0.0
        var sig = 0.0
        for (i in 9_600 until src.size - 9_600) {
            err += (back[i] - src[i]).toDouble().let { it * it }
            sig += src[i].toDouble() * src[i]
        }
        assertThat(10 * log10(err / sig)).isLessThan(-100.0)
    }
}
