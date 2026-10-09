package com.gabrielifrim.brut.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PresetTest {

    @Test
    fun `chaque préréglage se reconnaît lui-même et lui seul`() {
        for (p in Preset.entries) {
            assertThat(Preset.matching(p.format, p.options)).isEqualTo(p)
        }
        assertThat(Preset.entries.map { it.format to it.options }.distinct()).hasSize(Preset.entries.size)
    }

    @Test
    fun `l'écoute casque et les seuils inactifs n'empêchent pas la reconnaissance`() {
        val p = Preset.AMBIANCE
        val tweaked = p.options.copy(monitor = true, triggerDb = -50f, safetyDb = -18f)
        assertThat(Preset.matching(p.format, tweaked)).isEqualTo(p)
    }

    @Test
    fun `un réglage modifié à la main ne correspond plus à aucun préréglage`() {
        val p = Preset.CONCERT
        assertThat(Preset.matching(p.format.copy(sampleRate = 96_000), p.options)).isNull()
        assertThat(Preset.matching(p.format, p.options.copy(safetyDb = -6f))).isNull()
    }

    @Test
    fun `appliquer un préréglage garde l'écoute casque`() {
        assertThat(Preset.VOICE_OVER.optionsKeeping(TakeOptions(monitor = true)).monitor).isTrue()
        assertThat(Preset.CONCERT.optionsKeeping(TakeOptions(monitor = false)).monitor).isFalse()
    }

    @Test
    fun `aucun préréglage n'arme le déclenchement par défaut`() {
        // Un préréglage qui armerait REC surprendrait : le seuil reste un choix explicite.
        assertThat(Preset.entries.none { it.options.trigger }).isTrue()
    }
}
