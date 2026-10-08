package com.gabrielifrim.brut.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class MeasurementTest {

    private fun sine(rate: Int, seconds: Double, freq: Double, amp: Double, left: Boolean = true, right: Boolean = true, phase: Double = 0.0): FloatArray {
        val n = (rate * seconds).toInt()
        return FloatArray(n * 2) { i ->
            val frame = i / 2
            val on = if (i % 2 == 0) left else right
            if (on) (amp * sin(2 * PI * freq * frame / rate + phase)).toFloat() else 0f
        }
    }

    @Test
    fun `sinus 1 kHz à −20 dBFS sur un canal donne −23 LUFS (BS 1770)`() {
        val meter = LoudnessMeter(48_000, 2)
        val s = sine(48_000, 10.0, 1000.0, 0.1, right = false)
        meter.process(s, s.size / 2)
        val r = meter.reading()
        assertThat(r.integrated).isWithin(0.2f).of(-23.0f)
        assertThat(r.momentary).isWithin(0.2f).of(-23.0f)
        assertThat(r.shortTerm).isWithin(0.2f).of(-23.0f)
    }

    @Test
    fun `même sinus sur les deux canaux donne −20 LUFS, à 44,1 comme à 96 kHz`() {
        for (rate in listOf(44_100, 96_000)) {
            val meter = LoudnessMeter(rate, 2)
            val s = sine(rate, 5.0, 1000.0, 0.1)
            meter.process(s, s.size / 2)
            assertThat(meter.reading().integrated).isWithin(0.2f).of(-20.0f)
        }
    }

    @Test
    fun `la porte absolue ignore le silence dans la sonie intégrée`() {
        val meter = LoudnessMeter(48_000, 2)
        val s = sine(48_000, 4.0, 1000.0, 0.1)
        meter.process(s, s.size / 2)
        val silence = FloatArray(48_000 * 2 * 10)
        meter.process(silence, silence.size / 2)
        assertThat(meter.reading().integrated).isWithin(0.3f).of(-20.0f)
    }

    @Test
    fun `la crête vraie révèle un pic entre deux échantillons`() {
        // Sinus à fs/4 décalé de 45° : tous les échantillons valent ±0,707, la vraie crête vaut 1.
        val s = sine(48_000, 1.0, 12_000.0, 1.0, phase = PI / 4)
        val samplePeak = s.maxOf { abs(it) }
        assertThat(LevelMeter.toDb(samplePeak)).isWithin(0.05f).of(-3.01f)
        val meter = LoudnessMeter(48_000, 2)
        meter.process(s, s.size / 2)
        assertThat(meter.reading().truePeakMax).isWithin(0.5f).of(0f)
    }

    @Test
    fun `le spectre place un sinus dans sa bande, au bon niveau`() {
        val analyzer = SpectrumAnalyzer(48_000, 2)
        val s = sine(48_000, 0.2, 1000.0, 0.5)
        analyzer.push(s, s.size / 2)
        val levels = analyzer.compute()
        val band1k = analyzer.centers.indices.minBy { abs(analyzer.centers[it] - 1000f) }
        val band8k = analyzer.centers.indices.minBy { abs(analyzer.centers[it] - 8000f) }
        assertThat(levels[band1k]).isWithin(1.5f).of(-6.02f)
        assertThat(levels[band8k]).isLessThan(-60f)
    }
}
