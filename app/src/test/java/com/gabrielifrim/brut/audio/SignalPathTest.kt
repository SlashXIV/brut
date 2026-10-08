package com.gabrielifrim.brut.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SignalPathTest {

    @Test
    fun `aller-retour 16 bit vers flottant est exact sur toute la plage`() {
        for (v in Short.MIN_VALUE..Short.MAX_VALUE) {
            val s = v.toShort()
            assertThat(SampleConverter.floatToPcm16(SampleConverter.pcm16ToFloat(s))).isEqualTo(s)
        }
    }

    @Test
    fun `les entiers sont ecretes a pleine echelle sans debordement`() {
        assertThat(SampleConverter.floatToPcm16(2f)).isEqualTo(Short.MAX_VALUE)
        assertThat(SampleConverter.floatToPcm16(-2f)).isEqualTo(Short.MIN_VALUE)
        assertThat(SampleConverter.floatToPcm24(1f)).isEqualTo(8_388_607)
        assertThat(SampleConverter.floatToPcm24(-1f)).isEqualTo(-8_388_608)
    }

    @Test
    fun `gain a 0 dB ne touche a aucun echantillon`() {
        val gain = GainStage(2)
        val buffer = floatArrayOf(0.123456f, -0.5f, 0.999f, -1f)
        val copy = buffer.copyOf()
        assertThat(gain.apply(buffer, 2)).isFalse()
        assertThat(buffer).isEqualTo(copy)
    }

    @Test
    fun `gain par canal, independant a gauche et a droite`() {
        val gain = GainStage(2)
        gain.setGainDb(0, 6f)
        val warmup = FloatArray(200) { 0f }
        gain.apply(warmup, 100) // la rampe atteint la cible sur ce bloc
        val buffer = floatArrayOf(0.25f, 0.25f)
        gain.apply(buffer, 1)
        assertThat(buffer[0]).isWithin(1e-3f).of(0.25f * LevelMeter.dbToLinear(6f))
        assertThat(buffer[1]).isEqualTo(0.25f)
    }

    @Test
    fun `retour a 0 dB retrouve le chemin bit-exact`() {
        val gain = GainStage(1)
        gain.setGainDb(0, -6f)
        gain.apply(FloatArray(10), 10)
        gain.setGainDb(0, 0f)
        gain.apply(FloatArray(10), 10)
        val buffer = floatArrayOf(0.3f)
        assertThat(gain.apply(buffer, 1)).isFalse()
        assertThat(buffer[0]).isEqualTo(0.3f)
    }

    @Test
    fun `crete-metre mesure un sinus a -6 dBFS et son RMS a -9 dBFS`() {
        val rate = 48_000
        val meter = LevelMeter(rate, 1)
        val signal = FloatArray(rate) { i -> kotlin.math.sin(2 * Math.PI * 1000 * i / rate).toFloat() * 0.5f }
        meter.process(signal, rate)
        val level = meter.snapshot().single()
        assertThat(level.peakDb).isWithin(0.05f).of(-6.02f)
        assertThat(level.rmsDb).isWithin(0.2f).of(-9.03f)
        assertThat(level.clipped).isFalse()
    }

    @Test
    fun `la saturation reste verrouillee jusqu'a la remise a zero`() {
        val meter = LevelMeter(48_000, 2)
        meter.process(floatArrayOf(0.1f, 1f), 1)
        meter.process(FloatArray(96_000), 48_000)
        val after = meter.snapshot()
        assertThat(after[0].clipped).isFalse()
        assertThat(after[1].clipped).isTrue()
        assertThat(after[1].clipCount).isEqualTo(1)
        meter.resetClip()
        meter.process(FloatArray(2), 1)
        assertThat(meter.snapshot()[1].clipped).isFalse()
    }

    @Test
    fun `la crete maintenue tient 1,5 s puis retombe`() {
        val rate = 1000
        val meter = LevelMeter(rate, 1)
        meter.process(floatArrayOf(0.5f), 1)
        meter.process(FloatArray(1400), 1400)
        assertThat(meter.snapshot().single().holdDb).isWithin(0.01f).of(-6.02f)
        meter.process(FloatArray(200), 200)
        assertThat(meter.snapshot().single().holdDb).isLessThan(-30f)
    }

    @Test
    fun `la saturation d'entree est vue meme quand le gain la masque dans le fichier`() {
        val meter = LevelMeter(48_000, 1)
        val gain = GainStage(1)
        gain.setGainDb(0, -12f)
        val block = FloatArray(480) { 1f }
        meter.inspectInput(block, 480)
        gain.apply(block, 480)
        gain.apply(block.also { it.fill(1f) }, 480)
        meter.process(block, 480)
        val level = meter.snapshot().single()
        assertThat(level.inputClipped).isTrue()
        assertThat(level.clipped).isFalse()
    }
}
