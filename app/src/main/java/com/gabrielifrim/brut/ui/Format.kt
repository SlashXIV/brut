package com.gabrielifrim.brut.ui

import com.gabrielifrim.brut.audio.LevelMeter
import java.util.Locale
import kotlin.math.abs

/**
 * Formats d'affichage : séparateur décimal de la langue du téléphone (virgule en français),
 * et toujours le vrai signe moins.
 */

private val FR: Locale get() = Locale.getDefault()

/** Unités de taille : « Go, Mo, Ko » en français, « GB, MB, KB » ailleurs. */
private fun sizeUnits(): Triple<String, String, String> =
    if (Locale.getDefault().language == "fr") Triple("Go", "Mo", "Ko") else Triple("GB", "MB", "KB")

fun formatDuration(seconds: Double, withHundredths: Boolean = true): String {
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val base = String.format(FR, "%02d:%02d:%02d", h, m, s)
    if (!withHundredths) return base
    val cs = ((seconds - total) * 100).toInt().coerceIn(0, 99)
    return base + String.format(FR, ".%02d", cs)
}

/** « −12,4 », « +3,0 », « 0,0 ». Retourne null sous le plancher de mesure (affiché −∞). */
fun formatDb(db: Float, signed: Boolean = false): String? {
    if (db <= LevelMeter.FLOOR_DB) return null
    val rounded = Math.round(db * 10) / 10f
    val body = String.format(FR, "%.1f", abs(rounded))
    return when {
        rounded < 0f -> "−$body"
        signed && rounded > 0f -> "+$body"
        else -> body
    }
}

fun formatRate(hz: Int): String =
    if (hz % 1000 == 0) "${hz / 1000} kHz" else String.format(FR, "%.1f kHz", hz / 1000.0)

fun formatBytes(bytes: Long): String {
    val (gb, mb, kb) = sizeUnits()
    return when {
        bytes >= 1L shl 30 -> String.format(FR, "%.1f $gb", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(FR, "%.0f $mb", bytes / (1L shl 20).toDouble())
        else -> String.format(FR, "%.0f $kb", bytes / 1024.0)
    }
}

/** Durée lue par TalkBack : « 1 heure 2 minutes 5 secondes » plutôt que « 01:02:05 ». */
@androidx.compose.runtime.Composable
fun spokenDuration(seconds: Double): String {
    val total = seconds.toLong()
    val h = (total / 3600).toInt()
    val m = ((total % 3600) / 60).toInt()
    val s = (total % 60).toInt()
    return buildList {
        if (h > 0) add(androidx.compose.ui.res.pluralStringResource(com.gabrielifrim.brut.R.plurals.a11y_hours, h, h))
        if (m > 0 || h > 0) add(androidx.compose.ui.res.pluralStringResource(com.gabrielifrim.brut.R.plurals.a11y_minutes, m, m))
        add(androidx.compose.ui.res.pluralStringResource(com.gabrielifrim.brut.R.plurals.a11y_seconds, s, s))
    }.joinToString(" ")
}

fun formatLongDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h >= 1 -> "$h h ${"%02d".format(m)}"
        else -> "$m min"
    }
}
