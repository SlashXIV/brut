package com.gabrielifrim.brut.audio

import kotlin.math.abs

/** Une image LTC décodée et l'échantillon (absolu) où elle commence. */
data class LtcFrame(
    val timecode: Timecode,
    val dropFlag: Boolean,
    val startSample: Long,
    /** Durée moyenne mesurée d'une image, en échantillons. */
    val periodSamples: Double,
) {
    /** Cadence détectée d'après la période et le drapeau drop-frame. */
    fun rateAt(sampleRate: Int): TimecodeRate = TimecodeRate.detect(periodSamples, sampleRate, dropFlag)
}

/**
 * Décodeur de timecode longitudinal (SMPTE 12M) sur une voie audio, en flux.
 *
 * Le LTC est un code biphase : une transition à chaque frontière de bit, et une de plus
 * au milieu d'un bit à 1. On repère les passages par zéro (avec hystérésis, pour ignorer
 * le bruit), on classe chaque intervalle en bit entier ou demi-bit, puis on cherche le mot
 * de synchronisation qui termine chaque image de 80 bits. La polarité n'importe pas.
 *
 * Une image n'est annoncée « validée » qu'après [CONFIRM] images consécutives cohérentes :
 * une étiquette isolée, abîmée par une coupure, ne sert jamais d'ancrage.
 */
class LtcDecoder(private val sampleRate: Int) {

    private val minBit = sampleRate / 2_700.0 // au-delà de 30 i/s × 80 bits, avec marge
    private val maxBit = sampleRate / 1_700.0 // en deçà de 23,976 i/s × 80 bits

    private var bitPeriod = sampleRate / 2_000.0
    private var state = 0 // signe courant : +1, −1, 0 = inconnu
    private var peak = 0.0
    private var prev = 0.0
    private var prevPos = -1L
    private var zeroAt = Double.NaN // passage par zéro candidat, en attente de franchir l'hystérésis
    private var lastCross = Double.NaN
    private var halfPending = false
    private var halfStart = 0.0

    private val bits = IntArray(80)
    private val starts = DoubleArray(80)
    private var count = 0L

    private var lastFrame: LtcFrame? = null
    private var runNominal = 0
    private var period = 0.0
    private var runStart = 0.0
    private var runNumber = 0L
    private var consecutive = 0

    fun reset() {
        bitPeriod = sampleRate / 2_000.0
        state = 0; peak = 0.0; prev = 0.0; prevPos = -1L
        zeroAt = Double.NaN; lastCross = Double.NaN
        halfPending = false; count = 0
        lastFrame = null; runNominal = 0; period = 0.0; runStart = 0.0; runNumber = 0; consecutive = 0
    }

    /**
     * Lit [frames] trames d'un tampon entrelacé ([stride] voies, voie [channel]) dont la
     * première est l'échantillon absolu [absStart]. [onFrame] reçoit chaque image décodée
     * et dit si elle est validée.
     */
    fun process(samples: FloatArray, frames: Int, stride: Int, channel: Int, absStart: Long, onFrame: (LtcFrame, Boolean) -> Unit) {
        for (i in 0 until frames) {
            val x = samples[i * stride + channel].toDouble()
            val pos = absStart + i
            val a = abs(x)
            peak = if (a > peak) a else peak * 0.9995
            val h = peak * 0.15
            val sign = if (x >= 0) 1 else -1
            if (state != 0 && sign != state && zeroAt.isNaN() && prevPos == pos - 1) {
                // Passage par zéro interpolé entre les deux échantillons : précision sous l'échantillon.
                zeroAt = (pos - 1) + prev / (prev - x)
            }
            if (sign == state) zeroAt = Double.NaN
            if (a > h && h > 1e-4 && sign != state) {
                val t = if (zeroAt.isNaN()) pos.toDouble() else zeroAt
                if (state != 0) transition(t, onFrame)
                state = sign
                zeroAt = Double.NaN
            }
            prev = x
            prevPos = pos
        }
    }

    private fun transition(t: Double, onFrame: (LtcFrame, Boolean) -> Unit) {
        val last = lastCross
        lastCross = t
        if (last.isNaN()) return
        val dt = t - last
        when {
            dt > bitPeriod * 0.75 && dt < maxBit * 1.3 -> {
                // Bit entier : un 0. Un demi-bit resté seul signale une désynchronisation.
                halfPending = false
                adapt(dt)
                push(0, last, onFrame)
            }
            dt <= bitPeriod * 0.75 && dt > minBit * 0.3 -> {
                if (halfPending) {
                    halfPending = false
                    adapt(2 * dt)
                    push(1, halfStart, onFrame)
                } else {
                    halfPending = true
                    halfStart = last
                }
            }
            else -> {
                // Silence ou parasite : on repart de zéro.
                halfPending = false
                count = 0
                consecutive = 0
            }
        }
    }

    private fun adapt(measured: Double) {
        bitPeriod = (bitPeriod + 0.1 * (measured - bitPeriod)).coerceIn(minBit, maxBit)
    }

    private fun push(bit: Int, start: Double, onFrame: (LtcFrame, Boolean) -> Unit) {
        val idx = (count % 80).toInt()
        bits[idx] = bit
        starts[idx] = start
        count++
        if (count >= 80 && syncAtEnd()) decode(onFrame)
    }

    /** Bit n de l'image qui vient de se terminer (0 = le plus ancien). */
    private fun bit(n: Int): Int = bits[((count - 80 + n) % 80).toInt()]

    private fun syncAtEnd(): Boolean {
        for (k in 0 until 16) if (bit(64 + k) != SYNC[k]) return false
        return true
    }

    private fun decode(onFrame: (LtcFrame, Boolean) -> Unit) {
        fun bcd(from: Int, width: Int): Int {
            var v = 0
            for (k in 0 until width) v = v or (bit(from + k) shl k)
            return v
        }
        val tc = Timecode(
            hours = bcd(48, 4) + 10 * bcd(56, 2),
            minutes = bcd(32, 4) + 10 * bcd(40, 3),
            seconds = bcd(16, 4) + 10 * bcd(24, 3),
            frames = bcd(0, 4) + 10 * bcd(8, 2),
        )
        val drop = bit(10) == 1
        val start = starts[((count - 80) % 80).toInt()]
        val previous = lastFrame
        val gap = previous?.let { start - it.startSample }
        // La cadence nominale (24, 25 ou 30 : 4 % d'écart au moins) se lit sur l'intervalle
        // entre deux images, précis à l'échantillon ; la durée d'un bit n'a pas encore convergé.
        val measured = gap?.takeIf { abs(it - 80 * bitPeriod) < 8 * bitPeriod } ?: (bitPeriod * 80)
        val rough = TimecodeRate.detect(measured, sampleRate, drop)
        val number = Timecode.frameNumber(tc, rough)
        val plausible = tc.isValidFor(rough)
        val follows = previous != null && plausible && gap != null && abs(gap - 80 * bitPeriod) < 8 * bitPeriod &&
            number == Math.floorMod(Timecode.frameNumber(previous.timecode, rough) + 1, rough.framesPerDay)
        // 23,976 et 24 ne diffèrent que de 0,1 % : la période se mesure sur toute la série
        // d'images consécutives, d'autant plus précise que la série est longue.
        if (follows && rough.nominal == runNominal && number > runNumber) {
            period = (start - runStart) / (number - runNumber)
        } else if (!follows || rough.nominal != runNominal) {
            runStart = start
            runNumber = number
            runNominal = rough.nominal
            period = measured
        }
        consecutive = if (follows) consecutive + 1 else if (plausible) 1 else 0
        val frame = LtcFrame(tc, drop, Math.round(start), period)
        lastFrame = frame
        if (plausible) onFrame(frame, consecutive >= CONFIRM)
    }

    companion object {
        /** Images consécutives cohérentes exigées avant de faire confiance au LTC. */
        const val CONFIRM = 3

        /** Mot de synchronisation, bits 64 à 79 dans l'ordre d'émission. */
        private val SYNC = intArrayOf(0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 1)
    }
}
