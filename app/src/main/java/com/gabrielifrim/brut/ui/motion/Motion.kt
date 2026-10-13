package com.gabrielifrim.brut.ui.motion

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** Courbes et durées sèches : une console, ça claque. */
object Motion {
    val Enter = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)
    val Transition = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
    const val ENTER_MS = 320
    const val TRANSITION_MS = 420
    /** Durée unique en mouvement sobre : un changement d'état, pas un mouvement. */
    const val REDUCED_MS = 180
}

/**
 * Niveau de mouvement. Les composants ne testent que [decorative] et [animates] :
 * - FULL : tout, y compris le décoratif ;
 * - REDUCED (mouvement sobre) : changements d'état courts, aucun décor ;
 * - NONE (« Supprimer les animations » d'Android) : rien ne glisse.
 * Les mesures (aiguille, crête, niveau) restent vivantes dans tous les cas : là, l'animation est
 * l'information, et elles ne passent pas par ce niveau.
 */
enum class MotionLevel {
    FULL, REDUCED, NONE;

    val decorative: Boolean get() = this == FULL
    val animates: Boolean get() = this != NONE
}

/** Le réglage système l’emporte toujours sur le mouvement choisi dans Brut. */
fun motionLevelOf(animatorScale: Float, sober: Boolean): MotionLevel = when {
    animatorScale == 0f -> MotionLevel.NONE
    sober -> MotionLevel.REDUCED
    else -> MotionLevel.FULL
}

val LocalMotionLevel = staticCompositionLocalOf { MotionLevel.FULL }

/**
 * Échelle d'animation d'Android, suivie en direct : changer « Supprimer les animations »
 * pendant que Brut est ouvert prend effet tout de suite.
 */
@Composable
fun rememberAnimatorScale(): Float {
    val resolver = LocalContext.current.contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    var scale by remember { mutableFloatStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scale = read()
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return scale
}
