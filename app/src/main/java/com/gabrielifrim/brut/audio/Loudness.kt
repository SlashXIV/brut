package com.gabrielifrim.brut.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/** Mesures de sonie, en LUFS / LU, et crête vraie en dBTP. */
data class LoudnessReading(
    /** Sonie instantanée (fenêtre de 400 ms). */
    val momentary: Float = SILENCE,
    /** Sonie court terme (fenêtre de 3 s). */
    val shortTerm: Float = SILENCE,
    /** Sonie intégrée depuis la remise à zéro, avec portes absolue et relative. */
    val integrated: Float = SILENCE,
    /** Crête vraie maximale (suréchantillonnée ×4) depuis la remise à zéro. */
    val truePeakMax: Float = SILENCE,
) {
    companion object {
        const val SILENCE = -99f
    }
}

/** Filtre biquadratique (forme directe I), un état par canal. */
private class Biquad(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    companion object {
        /**
         * Les deux étages de la pondération K (BS.1770), recalculés pour toute fréquence
         * d'échantillonnage à partir du prototype analogique (méthode de libebur128) :
         * à 48 kHz, on retrouve au chiffre près les coefficients publiés par l'UIT.
         */
        fun kWeighting(sampleRate: Int): Pair<Biquad, Biquad> {
            // Étage 1 : plateau haut (effet acoustique de la tête).
            var f0 = 1681.974450955533
            val g = 3.999843853973347
            var q = 0.7071752369554196
            var k = tan(PI * f0 / sampleRate)
            val vh = 10.0.pow(g / 20)
            val vb = vh.pow(0.4996667741545416)
            var a0 = 1 + k / q + k * k
            val shelf = Biquad(
                (vh + vb * k / q + k * k) / a0,
                2 * (k * k - vh) / a0,
                (vh - vb * k / q + k * k) / a0,
                2 * (k * k - 1) / a0,
                (1 - k / q + k * k) / a0,
            )
            // Étage 2 : passe-haut (RLB).
            f0 = 38.13547087602444
            q = 0.5003270373238773
            k = tan(PI * f0 / sampleRate)
            a0 = 1 + k / q + k * k
            val highPass = Biquad(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
            return shelf to highPass
        }
    }
}

/**
 * Sonie EBU R128 / ITU-R BS.1770-4 et crête vraie. Le temps est compté en
 * échantillons : déterministe et testable sans horloge.
 *
 * Les énergies sont cumulées par tranches de 100 ms ; la fenêtre instantanée en
 * réunit 4, la court terme 30, et les blocs de l'intégrée (400 ms, recouvrement de
 * 75 %) sont conservés pour appliquer les portes à −70 LUFS puis à −10 LU.
 */
class LoudnessMeter(private val sampleRate: Int, private val channels: Int) {

    private val filters = List(channels) { Biquad.kWeighting(sampleRate) }
    private val subBlockFrames = sampleRate / 10
    private var subFrames = 0
    private var subEnergy = 0.0
    private val recent = ArrayDeque<Double>()          // énergies moyennes des 30 dernières tranches
    private val blocks = ArrayList<Double>()            // énergies des blocs de 400 ms (intégrée)
    private val truePeak = List(channels) { TruePeakDetector() }
    private var truePeakMax = 0.0

    @Volatile private var resetRequested = false

    fun reset() { resetRequested = true }

    fun process(interleaved: FloatArray, frames: Int) {
        if (resetRequested) {
            resetRequested = false
            blocks.clear()
            truePeakMax = 0.0
        }
        var i = 0
        for (f in 0 until frames) {
            var sum = 0.0
            for (c in 0 until channels) {
                val x = interleaved[i++]
                val (shelf, hp) = filters[c]
                val y = hp.process(shelf.process(x.toDouble()))
                sum += y * y
                val tp = truePeak[c].process(x)
                if (tp > truePeakMax) truePeakMax = tp
            }
            subEnergy += sum
            if (++subFrames == subBlockFrames) closeSubBlock()
        }
    }

    private fun closeSubBlock() {
        recent.addLast(subEnergy / subBlockFrames)
        if (recent.size > 30) recent.removeFirst()
        subEnergy = 0.0
        subFrames = 0
        if (recent.size >= 4) blocks += mean(recent.size - 4)
    }

    private fun mean(from: Int): Double {
        var s = 0.0
        for (k in from until recent.size) s += recent[k]
        return s / (recent.size - from)
    }

    fun reading(): LoudnessReading = LoudnessReading(
        momentary = if (recent.size >= 4) lufs(mean(recent.size - 4)) else LoudnessReading.SILENCE,
        shortTerm = if (recent.size >= 30) lufs(mean(0)) else LoudnessReading.SILENCE,
        integrated = integrated(),
        truePeakMax = if (truePeakMax > 0) (20 * log10(truePeakMax)).toFloat() else LoudnessReading.SILENCE,
    )

    private fun integrated(): Float {
        val absolute = blocks.filter { lufsD(it) > -70.0 }
        if (absolute.isEmpty()) return LoudnessReading.SILENCE
        val gate = lufsD(absolute.average()) - 10.0
        val relative = absolute.filter { lufsD(it) > gate }
        if (relative.isEmpty()) return LoudnessReading.SILENCE
        return lufs(relative.average())
    }

    private fun lufsD(energy: Double): Double = if (energy <= 0) -200.0 else -0.691 + 10 * log10(energy)
    private fun lufs(energy: Double): Float = max(LoudnessReading.SILENCE.toDouble(), lufsD(energy)).toFloat()
}

/**
 * Crête vraie (BS.1770, annexe 2) : suréchantillonnage ×4 par un filtre polyphasé
 * (sinus cardinal fenêtré, 12 coefficients par phase), puis valeur absolue maximale.
 * Révèle les crêtes inter-échantillons qu'un crête-mètre classique ne voit pas.
 */
class TruePeakDetector {
    private val history = DoubleArray(TAPS_PER_PHASE)
    private var pos = 0

    fun process(x: Float): Double {
        history[pos] = x.toDouble()
        var peak = abs(x.toDouble())
        for (phase in 1 until FACTOR) {
            var acc = 0.0
            val coeffs = PHASES[phase]
            for (k in 0 until TAPS_PER_PHASE) {
                acc += coeffs[k] * history[(pos - k + TAPS_PER_PHASE) % TAPS_PER_PHASE]
            }
            peak = max(peak, abs(acc))
        }
        pos = (pos + 1) % TAPS_PER_PHASE
        return peak
    }

    companion object {
        private const val FACTOR = 4
        private const val TAPS_PER_PHASE = 12

        /**
         * Coefficients de chaque phase : la phase p donne l'échantillon situé p/4 avant
         * l'échantillon courant, retardé de la moitié du filtre (latence sans importance ici).
         */
        private val PHASES: Array<DoubleArray> = Array(FACTOR) { phase ->
            val half = TAPS_PER_PHASE / 2.0
            DoubleArray(TAPS_PER_PHASE) { k ->
                val t = k - half + 1 - phase.toDouble() / FACTOR
                val sinc = if (abs(t) < 1e-9) 1.0 else sin(PI * t) / (PI * t)
                val window = 0.5 + 0.5 * cos(PI * t / (half + 0.5))
                sinc * window
            }.let { c ->
                // Normalisation : gain unitaire en continu pour chaque phase.
                val s = c.sum()
                DoubleArray(c.size) { c[it] / s }
            }
        }
    }
}
