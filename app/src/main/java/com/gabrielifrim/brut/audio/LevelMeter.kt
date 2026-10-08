package com.gabrielifrim.brut.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max

/** Mesure instantanée d'un canal, en dBFS. */
data class ChannelLevel(
    /** Crête affichée : suit les crêtes instantanément, redescend à [LevelMeter.PEAK_FALL_DB_PER_S]. */
    val peakDb: Float = LevelMeter.FLOOR_DB,
    /** Valeur efficace intégrée sur ~300 ms (balistique proche d'un VU). */
    val rmsDb: Float = LevelMeter.FLOOR_DB,
    /** Crête maintenue pendant [LevelMeter.HOLD_SECONDS]. */
    val holdDb: Float = LevelMeter.FLOOR_DB,
    /** Crête maximale depuis la dernière remise à zéro. */
    val maxDb: Float = LevelMeter.FLOOR_DB,
    /** Saturation verrouillée : reste vraie jusqu'à [LevelMeter.resetClip]. */
    val clipped: Boolean = false,
    val clipCount: Long = 0,
)

/**
 * Crête-mètre et VU-mètre par canal. Le temps est compté en échantillons, pas en
 * horloge murale : le comportement est déterministe et testable.
 *
 * Les mesures portent sur le signal écrit dans le fichier (après le gain).
 */
class LevelMeter(private val sampleRate: Int, private val channels: Int) {

    private val peakDb = FloatArray(channels) { FLOOR_DB }
    private val holdDb = FloatArray(channels) { FLOOR_DB }
    private val holdAge = LongArray(channels)
    private val maxDb = FloatArray(channels) { FLOOR_DB }
    private val meanSquare = DoubleArray(channels)
    private val clipped = BooleanArray(channels)
    private val clipCount = LongArray(channels)
    private val blockPeak = FloatArray(channels)

    private val rmsAlpha = 1.0 - exp(-1.0 / (RMS_TAU_SECONDS * sampleRate))
    private val holdFrames = (HOLD_SECONDS * sampleRate).toLong()

    @Volatile private var resetRequested = false

    /** Demande la remise à zéro des voyants de saturation et des crêtes max (thread UI). */
    fun resetClip() {
        resetRequested = true
    }

    fun process(interleaved: FloatArray, frames: Int) {
        if (resetRequested) {
            resetRequested = false
            clipped.fill(false)
            clipCount.fill(0)
            maxDb.fill(FLOOR_DB)
        }
        blockPeak.fill(0f)
        var i = 0
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val x = interleaved[i++]
                val a = abs(x)
                if (a > blockPeak[c]) blockPeak[c] = a
                if (a >= CLIP_LINEAR) {
                    clipped[c] = true
                    clipCount[c]++
                }
                meanSquare[c] += (x.toDouble() * x - meanSquare[c]) * rmsAlpha
            }
        }
        val fall = PEAK_FALL_DB_PER_S * frames / sampleRate
        for (c in 0 until channels) {
            val db = toDb(blockPeak[c])
            peakDb[c] = max(db, max(FLOOR_DB, peakDb[c] - fall))
            if (db > maxDb[c]) maxDb[c] = db
            if (db >= holdDb[c]) {
                holdDb[c] = db
                holdAge[c] = 0
            } else {
                holdAge[c] += frames
                if (holdAge[c] > holdFrames) holdDb[c] = peakDb[c]
            }
        }
    }

    fun snapshot(): List<ChannelLevel> = List(channels) { c ->
        ChannelLevel(
            peakDb = peakDb[c],
            rmsDb = toDb(kotlin.math.sqrt(meanSquare[c]).toFloat()),
            holdDb = holdDb[c],
            maxDb = maxDb[c],
            clipped = clipped[c],
            clipCount = clipCount[c],
        )
    }

    companion object {
        const val FLOOR_DB = -96f
        const val HOLD_SECONDS = 1.5
        const val RMS_TAU_SECONDS = 0.3
        const val PEAK_FALL_DB_PER_S = 20f

        /** Seuil de saturation : -0,1 dBFS. Au-delà, le convertisseur est à pleine échelle. */
        const val CLIP_DB = -0.1f
        val CLIP_LINEAR: Float = dbToLinear(CLIP_DB)

        fun toDb(linear: Float): Float =
            if (linear <= 0f) FLOOR_DB else max(FLOOR_DB, 20f * log10(linear))

        fun dbToLinear(db: Float): Float = Math.pow(10.0, db / 20.0).toFloat()
    }
}
