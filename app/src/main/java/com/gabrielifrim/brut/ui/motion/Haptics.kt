package com.gabrielifrim.brut.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** Tout le vocabulaire haptique de Brut, au même endroit : chaque geste garde sa sensation. */
class Haptics(private val feedback: HapticFeedback) {
    /** Cran d'un commutateur, aimantation d'une poignée. */
    fun detent() = feedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    /** Cran franc : 0 dB d'un fader, butée. */
    fun notch() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
    /** Repère posé, prise lancée : on doit le sentir sans regarder. */
    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
}
