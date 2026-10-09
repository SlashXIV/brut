package com.gabrielifrim.brut.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Ce que les voies 1 et 2 ont en commun sur une fenêtre de mesure. */
enum class Pairing {
    /** Trop peu de signal pour conclure. */
    SILENT,
    /** Même signal sur les deux voies : quelque chose les a mélangées. */
    IDENTICAL,
    /** Deux signaux différents (dont une voie muette) : les voies sont séparées. */
    DISTINCT,
}

/** Niveaux RMS des voies 1 et 2 (dBFS) et corrélation entre elles (−1..1). */
data class PairReading(val leftDb: Float, val rightDb: Float, val correlation: Float) {
    val verdict: Pairing
        get() = when {
            max(leftDb, rightDb) < SILENCE_DB -> Pairing.SILENT
            // Deux micros différents, même proches, ne donnent jamais un signal à ce point
            // identique : au-delà, les voies sont la copie l'une de l'autre, au gain près.
            min(leftDb, rightDb) >= SILENCE_DB && correlation > TWIN_CORRELATION -> Pairing.IDENTICAL
            else -> Pairing.DISTINCT
        }

    companion object {
        const val SILENCE_DB = -60f
        const val TWIN_CORRELATION = 0.99f
    }
}

/**
 * Accumule la ressemblance des voies 1 et 2 d'un flux entrelacé, sans allocation : de quoi
 * savoir si Android a mélangé une entrée stéréo avant de la livrer.
 */
class ChannelPair {
    private var ll = 0.0
    private var rr = 0.0
    private var lr = 0.0
    private var n = 0L

    fun add(samples: FloatArray, frames: Int, channels: Int) {
        if (channels < 2) return
        var i = 0
        repeat(frames) {
            val l = samples[i].toDouble()
            val r = samples[i + 1].toDouble()
            ll += l * l
            rr += r * r
            lr += l * r
            i += channels
        }
        n += frames
    }

    fun reading(): PairReading {
        fun db(sum: Double) = if (n == 0L || sum <= 0.0) -120f else (10 * log10(sum / n)).toFloat()
        val corr = if (ll > 0.0 && rr > 0.0) (lr / sqrt(ll * rr)).toFloat() else 0f
        return PairReading(db(ll), db(rr), corr)
    }

    fun reset() {
        ll = 0.0
        rr = 0.0
        lr = 0.0
        n = 0
    }
}

/**
 * Surveillance continue pendant la capture : les voies ne sont déclarées identiques qu'après
 * plusieurs fenêtres consécutives qui le montrent, et redeviennent distinctes de même. Les
 * fenêtres sans signal ne changent rien : un silence ne prouve ni l'un ni l'autre.
 */
class ChannelTwinWatch(private val channels: Int, private val confirm: Int = 3) {
    private val pair = ChannelPair()
    private var streak = 0
    private var lastVerdict: Pairing? = null

    /** Vrai tant que les voies sont jugées identiques. */
    var identical = false
        private set

    fun add(samples: FloatArray, frames: Int) = pair.add(samples, frames, channels)

    /** Clôt une fenêtre (environ une seconde) et met l'état à jour. */
    fun conclude(): Boolean {
        if (channels < 2) return false
        val verdict = pair.reading().verdict
        pair.reset()
        if (verdict == Pairing.SILENT) return identical
        streak = if (verdict == lastVerdict) streak + 1 else 1
        lastVerdict = verdict
        if (streak >= confirm) identical = verdict == Pairing.IDENTICAL
        return identical
    }
}
