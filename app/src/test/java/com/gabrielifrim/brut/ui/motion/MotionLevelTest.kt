package com.gabrielifrim.brut.ui.motion

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MotionLevelTest {

    @Test
    fun `supprimer les animations d'Android l'emporte sur le mouvement choisi`() {
        assertThat(motionLevelOf(0f, sober = false)).isEqualTo(MotionLevel.NONE)
        assertThat(motionLevelOf(0f, sober = true)).isEqualTo(MotionLevel.NONE)
    }

    @Test
    fun `Mouvement sobre, retire le décoratif sans tout figer`() {
        val level = motionLevelOf(1f, sober = true)
        assertThat(level).isEqualTo(MotionLevel.REDUCED)
        assertThat(level.decorative).isFalse()
        assertThat(level.animates).isTrue()
    }

    @Test
    fun `Mouvement expressif, quelle que soit l'échelle non nulle`() {
        assertThat(motionLevelOf(0.5f, sober = false)).isEqualTo(MotionLevel.FULL)
        assertThat(motionLevelOf(10f, sober = false).decorative).isTrue()
    }
}
