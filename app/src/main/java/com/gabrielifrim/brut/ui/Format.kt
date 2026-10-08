package com.gabrielifrim.brut.ui

import com.gabrielifrim.brut.audio.LevelMeter
import java.util.Locale
import kotlin.math.abs

/** Formats d'affichage à la française : virgule décimale, vrai signe moins, espaces fines. */

private val FR = Locale.FRANCE

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

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(FR, "%.1f Go", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(FR, "%.0f Mo", bytes / (1L shl 20).toDouble())
    else -> String.format(FR, "%.0f Ko", bytes / 1024.0)
}

fun formatLongDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h >= 1 -> "$h h ${"%02d".format(m)}"
        else -> "$m min"
    }
}
