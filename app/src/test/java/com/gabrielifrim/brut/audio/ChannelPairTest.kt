package com.gabrielifrim.brut.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class ChannelPairTest {

    private val rate = 48_000

    /** Stéréo entrelacée de [frames] trames : la voie 1 vaut [left], la voie 2 vaut [right]. */
    private fun stereo(frames: Int, left: (Int) -> Float, right: (Int) -> Float) =
        FloatArray(frames * 2) { i -> if (i % 2 == 0) left(i / 2) else right(i / 2) }

    private fun voice(i: Int) = (0.3 * sin(2 * PI * 220 * i / rate) + 0.1 * sin(2 * PI * 1370 * i / rate)).toFloat()

    private fun reading(samples: FloatArray, channels: Int = 2) =
        ChannelPair().apply { add(samples, samples.size / channels, channels) }.reading()

    @Test
    fun `deux voies copiees l'une sur l'autre sont jugees identiques, meme a gain different`() {
        val r = reading(stereo(rate, ::voice) { voice(it) * 0.5f })
        assertThat(r.correlation).isGreaterThan(0.999f)
        assertThat(r.verdict).isEqualTo(Pairing.IDENTICAL)
    }

    @Test
    fun `une voie qui parle et une voie muette sont separees`() {
        val r = reading(stereo(rate, ::voice) { 0f })
        assertThat(r.rightDb).isLessThan(PairReading.SILENCE_DB)
        assertThat(r.verdict).isEqualTo(Pairing.DISTINCT)
    }

    @Test
    fun `une voie qui parle et un bruit de fond independant sont separes`() {
        val noise = Random(7)
        val r = reading(stereo(rate, ::voice) { (noise.nextFloat() - 0.5f) * 0.02f })
        assertThat(r.verdict).isEqualTo(Pairing.DISTINCT)
    }

    @Test
    fun `deux micros differents qui entendent la meme voix restent distincts`() {
        // Le second micro capte la voix plus loin : retard de 1,5 ms et autre timbre.
        val delay = rate * 3 / 2000
        val noise = Random(3)
        val r = reading(stereo(rate, ::voice) { i -> 0.4f * voice(i - delay) + (noise.nextFloat() - 0.5f) * 0.05f })
        assertThat(r.verdict).isEqualTo(Pairing.DISTINCT)
    }

    @Test
    fun `trop peu de signal ne permet pas de conclure`() {
        val r = reading(stereo(rate, { voice(it) * 0.0001f }, { voice(it) * 0.0001f }))
        assertThat(r.verdict).isEqualTo(Pairing.SILENT)
    }

    @Test
    fun `seules les voies 1 et 2 d'un flux a 4 voies sont comparees`() {
        val frames = rate
        val samples = FloatArray(frames * 4) { i ->
            when (i % 4) {
                0, 1 -> voice(i / 4)
                else -> 0f
            }
        }
        assertThat(reading(samples, channels = 4).verdict).isEqualTo(Pairing.IDENTICAL)
    }

    @Test
    fun `un flux mono n'est jamais juge`() {
        val pair = ChannelPair()
        pair.add(FloatArray(rate) { voice(it) }, rate, 1)
        assertThat(pair.reading().verdict).isEqualTo(Pairing.SILENT)
    }

    @Test
    fun `la surveillance attend trois fenetres concordantes, et le silence ne change rien`() {
        val watch = ChannelTwinWatch(channels = 2)
        val twins = stereo(rate, ::voice) { voice(it) }
        val apart = stereo(rate, ::voice) { 0f }
        val quiet = FloatArray(rate * 2)
        repeat(2) { watch.add(twins, rate); assertThat(watch.conclude()).isFalse() }
        watch.add(twins, rate)
        assertThat(watch.conclude()).isTrue()
        watch.add(quiet, rate)
        assertThat(watch.conclude()).isTrue()
        repeat(2) { watch.add(apart, rate); assertThat(watch.conclude()).isTrue() }
        watch.add(apart, rate)
        assertThat(watch.conclude()).isFalse()
    }

    @Test
    fun `une recette s'ecrit et se relit, une ligne abimee est ignoree`() {
        CaptureRecipe.CANDIDATES.forEach { assertThat(CaptureRecipe.decode(it.encode())).isEqualTo(it) }
        assertThat(CaptureRecipe.decode("INCONNUE,FLOAT,index")).isNull()
        assertThat(CaptureRecipe.decode("UNPROCESSED,FLOAT")).isNull()
    }

    @Test
    fun `le test essaie 16 combinaisons, la plus brute d'abord`() {
        val all = CaptureRecipe.CANDIDATES
        assertThat(all).hasSize(16)
        assertThat(all.toSet()).hasSize(16)
        assertThat(all.first()).isEqualTo(CaptureRecipe(CaptureSource.UNPROCESSED, CaptureEncoding.FLOAT, indexMask = false))
        assertThat(all.last().source).isEqualTo(CaptureSource.MIC)
    }
}
