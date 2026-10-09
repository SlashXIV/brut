package com.gabrielifrim.brut.audio

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Cadences de timecode SMPTE. [num]/[den] est la vraie cadence (29,97 = 30000/1001) ;
 * [nominal] est le nombre d'images comptées par seconde dans l'étiquette.
 */
enum class TimecodeRate(val num: Int, val den: Int, val nominal: Int, val dropFrame: Boolean, val label: String) {
    FPS_23_976(24_000, 1001, 24, false, "23,976"),
    FPS_24(24, 1, 24, false, "24"),
    FPS_25(25, 1, 25, false, "25"),
    FPS_29_97(30_000, 1001, 30, false, "29,97"),
    FPS_29_97_DF(30_000, 1001, 30, true, "29,97 DF"),
    FPS_30(30, 1, 30, false, "30");

    val fps: Double get() = num.toDouble() / den

    /** Forme iXML : « 25/1 », « 30000/1001 ». */
    val ixmlRate: String get() = "$num/$den"
    val ixmlFlag: String get() = if (dropFrame) "DF" else "NDF"

    /** Images dans 24 heures. */
    val framesPerDay: Long get() = if (dropFrame) 24 * 107_892L else 24L * 3600 * nominal

    companion object {
        val DEFAULT = FPS_25

        fun fromIxml(rate: String?, flag: String?): TimecodeRate? {
            val r = rate?.trim() ?: return null
            val df = flag?.trim().equals("DF", ignoreCase = true)
            return entries.firstOrNull { it.ixmlRate == r && it.dropFrame == df }
                ?: entries.firstOrNull { it.ixmlRate == r }
        }

        /**
         * Cadence d'un LTC d'après la durée mesurée d'une image : 24 et 23,976 ne diffèrent
         * que de 0,1 %, une moyenne sur quelques images suffit à les séparer.
         */
        fun detect(framePeriodSamples: Double, sampleRate: Int, dropFlag: Boolean): TimecodeRate {
            if (dropFlag) return FPS_29_97_DF
            val fps = sampleRate / framePeriodSamples
            return entries.filter { !it.dropFrame }.minBy { abs(it.fps - fps) }
        }
    }
}

/** Une étiquette de timecode hh:mm:ss:ff. */
data class Timecode(val hours: Int, val minutes: Int, val seconds: Int, val frames: Int) {

    fun isValidFor(rate: TimecodeRate): Boolean =
        hours in 0..23 && minutes in 0..59 && seconds in 0..59 && frames in 0 until rate.nominal &&
            !(rate.dropFrame && seconds == 0 && frames < 2 && minutes % 10 != 0)

    /** « 10:02:03:12 », ou « 10:02:03;12 » en drop-frame (usage SMPTE). */
    fun format(rate: TimecodeRate): String {
        val sep = if (rate.dropFrame) ';' else ':'
        return "%02d:%02d:%02d%c%02d".format(hours, minutes, seconds, sep, frames)
    }

    companion object {
        /** Numéro d'image depuis minuit (drop-frame : les numéros sautés ne comptent pas). */
        fun frameNumber(tc: Timecode, rate: TimecodeRate): Long {
            val ndf = ((tc.hours * 3600L + tc.minutes * 60L + tc.seconds) * rate.nominal) + tc.frames
            if (!rate.dropFrame) return ndf
            val totalMinutes = 60L * tc.hours + tc.minutes
            return ndf - 2 * (totalMinutes - totalMinutes / 10)
        }

        fun fromFrameNumber(number: Long, rate: TimecodeRate): Timecode {
            var n = Math.floorMod(number, rate.framesPerDay)
            if (rate.dropFrame) {
                // Réinsère les numéros 00 et 01 sautés chaque minute, sauf toutes les dix minutes.
                val tens = n / 17_982
                val rest = n % 17_982
                n += 18 * tens + if (rest < 2) 0 else 2 * ((rest - 2) / 1_798)
            }
            val fps = rate.nominal
            return Timecode(
                hours = (n / (3600L * fps)).toInt(),
                minutes = ((n / (60L * fps)) % 60).toInt(),
                seconds = ((n / fps) % 60).toInt(),
                frames = (n % fps).toInt(),
            )
        }

        /** Échantillons depuis minuit au début de l'image [tc] (unité de la référence BWF). */
        fun samplesSinceMidnight(tc: Timecode, rate: TimecodeRate, sampleRate: Int): Long =
            (frameNumber(tc, rate).toDouble() * sampleRate * rate.den / rate.num).roundToLong()

        /** Étiquette de l'image en cours à [samples] échantillons après minuit. */
        fun fromSamples(samples: Long, rate: TimecodeRate, sampleRate: Int): Timecode =
            fromFrameNumber(floor(samples.toDouble() * rate.num / (sampleRate.toDouble() * rate.den) + 1e-9).toLong(), rate)
    }
}
