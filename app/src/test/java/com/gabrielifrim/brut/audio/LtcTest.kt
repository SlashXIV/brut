package com.gabrielifrim.brut.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/** Générateur LTC de référence (SMPTE 12M), pour les tests seulement. */
object LtcTestEncoder {

    fun frameBits(tc: Timecode, drop: Boolean): IntArray {
        val b = IntArray(80)
        fun put(from: Int, width: Int, v: Int) { for (k in 0 until width) b[from + k] = (v shr k) and 1 }
        put(0, 4, tc.frames % 10); put(8, 2, tc.frames / 10)
        b[10] = if (drop) 1 else 0
        put(16, 4, tc.seconds % 10); put(24, 3, tc.seconds / 10)
        put(32, 4, tc.minutes % 10); put(40, 3, tc.minutes / 10)
        put(48, 4, tc.hours % 10); put(56, 2, tc.hours / 10)
        intArrayOf(0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 1).copyInto(b, 64)
        return b
    }

    /**
     * [frames] images de LTC à partir de [start], dont la première commence à l'échantillon
     * [offset] (fractionnaire). Retourne le signal mono.
     */
    fun encode(start: Timecode, rate: TimecodeRate, sampleRate: Int, frames: Int, offset: Double = 100.0, amp: Float = 0.5f, totalSamples: Int? = null): FloatArray {
        val bit = sampleRate * rate.den.toDouble() / (rate.num * 80.0)
        val length = totalSamples ?: (offset + frames * 80 * bit + 200).toInt()
        val out = FloatArray(length)
        // Fronts de bit (et de demi-bit pour les 1), en temps continu.
        val edges = ArrayList<Double>()
        var n = Timecode.frameNumber(start, rate)
        for (f in 0 until frames) {
            val bits = frameBits(Timecode.fromFrameNumber(n, rate), rate.dropFrame)
            for (k in 0 until 80) {
                val t = offset + (f * 80 + k) * bit
                edges += t
                if (bits[k] == 1) edges += t + bit / 2
            }
            n++
        }
        var level = -amp
        var e = 0
        for (i in out.indices) {
            while (e < edges.size && edges[e] <= i) { level = -level; e++ }
            out[i] = if (i < offset - 1) 0f else level
        }
        return out
    }
}

class LtcTest {

    private fun decode(signal: FloatArray, sampleRate: Int, block: Int = 960, stride: Int = 1, channel: Int = 0): List<Pair<LtcFrame, Boolean>> {
        val d = LtcDecoder(sampleRate)
        val out = ArrayList<Pair<LtcFrame, Boolean>>()
        val frames = signal.size / stride
        var pos = 0
        while (pos < frames) {
            val n = minOf(block, frames - pos)
            d.process(signal.copyOfRange(pos * stride, (pos + n) * stride), n, stride, channel, pos.toLong()) { f, ok -> out += f to ok }
            pos += n
        }
        return out
    }

    @Test
    fun `calculs de timecode, drop-frame compris`() {
        val df = TimecodeRate.FPS_29_97_DF
        assertThat(Timecode.frameNumber(Timecode(1, 0, 0, 0), df)).isEqualTo(107_892)
        assertThat(Timecode.frameNumber(Timecode(0, 1, 0, 2), df)).isEqualTo(1_800)
        assertThat(Timecode.fromFrameNumber(1_800, df)).isEqualTo(Timecode(0, 1, 0, 2))
        assertThat(Timecode.fromFrameNumber(17_982, df)).isEqualTo(Timecode(0, 10, 0, 0))
        val rnd = Random(7)
        for (rate in TimecodeRate.entries) repeat(500) {
            val n = (rnd.nextDouble() * rate.framesPerDay).toLong()
            val tc = Timecode.fromFrameNumber(n, rate)
            assertThat(tc.isValidFor(rate)).isTrue()
            assertThat(Timecode.frameNumber(tc, rate)).isEqualTo(n)
        }
        assertThat(Timecode.samplesSinceMidnight(Timecode(10, 0, 0, 0), TimecodeRate.FPS_25, 48_000)).isEqualTo(36_000L * 48_000)
        assertThat(Timecode.fromSamples(36_000L * 48_000 + 1_920 * 7, TimecodeRate.FPS_25, 48_000)).isEqualTo(Timecode(10, 0, 0, 7))
        assertThat(Timecode(1, 2, 3, 4).format(df)).isEqualTo("01:02:03;04")
    }

    @Test
    fun `décode un LTC 25 i-s et s'ancre à l'échantillon près`() {
        val start = Timecode(10, 0, 0, 0)
        val signal = LtcTestEncoder.encode(start, TimecodeRate.FPS_25, 48_000, frames = 50, offset = 123.4)
        val frames = decode(signal, 48_000)
        val first = frames.first { it.second }.first
        val k = Timecode.frameNumber(first.timecode, TimecodeRate.FPS_25) - Timecode.frameNumber(start, TimecodeRate.FPS_25)
        assertThat(first.rateAt(48_000)).isEqualTo(TimecodeRate.FPS_25)
        assertThat(abs(first.startSample - (123.4 + k * 1_920))).isLessThan(2.0)
        // Après validation, chaque image suit la précédente.
        val validated = frames.filter { it.second }.map { it.first }
        assertThat(validated.size).isAtLeast(45)
        validated.zipWithNext().forEach { (a, b) ->
            assertThat(Timecode.frameNumber(b.timecode, TimecodeRate.FPS_25)).isEqualTo(Timecode.frameNumber(a.timecode, TimecodeRate.FPS_25) + 1)
        }
    }

    @Test
    fun `29,97 drop-frame au passage de la minute`() {
        val rate = TimecodeRate.FPS_29_97_DF
        val signal = LtcTestEncoder.encode(Timecode(0, 0, 59, 20), rate, 48_000, frames = 20)
        val validated = decode(signal, 48_000).filter { it.second }.map { it.first }
        assertThat(validated.first().rateAt(48_000)).isEqualTo(rate)
        assertThat(validated.map { it.timecode }).contains(Timecode(0, 1, 0, 2))
        assertThat(validated.map { it.timecode }).doesNotContain(Timecode(0, 1, 0, 0))
    }

    @Test
    fun `distingue 23,976 de 24, à toutes les fréquences`() {
        for (sr in listOf(44_100, 48_000, 96_000)) {
            for (rate in listOf(TimecodeRate.FPS_23_976, TimecodeRate.FPS_24, TimecodeRate.FPS_30)) {
                val signal = LtcTestEncoder.encode(Timecode(1, 0, 0, 0), rate, sr, frames = 60)
                // Moins d'une demi-seconde après la validation, la cadence est déjà juste.
                val validated = decode(signal, sr).filter { it.second }.map { it.first }
                assertThat(validated[10].rateAt(sr)).isEqualTo(rate)
                assertThat(validated.last().rateAt(sr)).isEqualTo(rate)
            }
        }
    }

    @Test
    fun `résiste à la polarité inversée, au niveau faible et au bruit`() {
        val clean = LtcTestEncoder.encode(Timecode(5, 6, 7, 8), TimecodeRate.FPS_25, 48_000, frames = 40, amp = 0.1f)
        val rnd = Random(3)
        // −20 dBFS, inversé, bruit à −60 dBFS, et un passe-bas d'ordre 1 pour adoucir les fronts.
        var lp = 0f
        val noisy = FloatArray(clean.size) { i ->
            lp += 0.5f * (-clean[i] - lp)
            lp + (rnd.nextGaussian() * 0.001).toFloat()
        }
        val validated = decode(noisy, 48_000).filter { it.second }.map { it.first }
        assertThat(validated.size).isAtLeast(30)
        assertThat(validated.first().timecode.hours).isEqualTo(5)
    }

    @Test
    fun `lit la voie choisie d'un signal stéréo`() {
        val ltc = LtcTestEncoder.encode(Timecode(12, 0, 0, 0), TimecodeRate.FPS_25, 48_000, frames = 20)
        val stereo = FloatArray(ltc.size * 2) { i -> if (i % 2 == 1) ltc[i / 2] else (0.3 * kotlin.math.sin(i * 0.01)).toFloat() }
        assertThat(decode(stereo, 48_000, stride = 2, channel = 1).count { it.second }).isAtLeast(15)
        assertThat(decode(stereo, 48_000, stride = 2, channel = 0).count { it.second }).isEqualTo(0)
    }
}
