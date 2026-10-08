package com.gabrielifrim.brut.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Analyseur de spectre : FFT de 4096 points sur la somme des canaux, fenêtre de Hann,
 * puis regroupement en bandes de 1/6 d'octave de 20 Hz à 20 kHz (ou Nyquist).
 * Niveaux en dBFS, calibrés pour qu'un sinus pleine échelle donne 0 dBFS dans sa bande.
 */
class SpectrumAnalyzer(private val sampleRate: Int, private val channels: Int) {

    private val ring = FloatArray(SIZE)
    private var write = 0
    private var filled = 0
    private val re = DoubleArray(SIZE)
    private val im = DoubleArray(SIZE)
    private val window = DoubleArray(SIZE) { 0.5 - 0.5 * cos(2 * PI * it / (SIZE - 1)) }
    private val windowGain = window.sum() / SIZE // ≈ 0,5 : corrige l'atténuation de la fenêtre

    /** Bornes des bandes, en indices de FFT. */
    private val bands: List<IntRange>
    val centers: FloatArray

    init {
        val top = minOf(20_000.0, sampleRate / 2.0 * 0.95)
        val edges = generateSequence(20.0) { it * 2.0.pow(1.0 / 6) }.takeWhile { it <= top }.toList()
        val binHz = sampleRate.toDouble() / SIZE
        bands = edges.zipWithNext { lo, hi ->
            val a = (lo / binHz).toInt().coerceAtLeast(1)
            val b = (hi / binHz).toInt().coerceAtLeast(a)
            a..b
        }
        centers = edges.zipWithNext { lo, hi -> sqrt(lo * hi).toFloat() }.toFloatArray()
    }

    fun push(interleaved: FloatArray, frames: Int) {
        var i = 0
        for (f in 0 until frames) {
            var s = 0f
            for (c in 0 until channels) s += interleaved[i++]
            ring[write] = s / channels
            write = (write + 1) % SIZE
        }
        filled = minOf(SIZE, filled + frames)
    }

    /** Niveaux par bande, en dBFS (plancher à [FLOOR_DB]). */
    fun compute(): FloatArray {
        if (filled < SIZE) return FloatArray(bands.size) { FLOOR_DB }
        for (k in 0 until SIZE) {
            re[k] = ring[(write + k) % SIZE] * window[k]
            im[k] = 0.0
        }
        fft(re, im)
        val scale = 2.0 / (SIZE * windowGain)
        return FloatArray(bands.size) { b ->
            var peak = 0.0
            for (k in bands[b]) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k]) * scale
                peak = max(peak, mag)
            }
            if (peak <= 0) FLOOR_DB else max(FLOOR_DB, (20 * log10(peak)).toFloat())
        }
    }

    companion object {
        const val SIZE = 4096
        const val FLOOR_DB = -100f

        /** FFT complexe en place, radix 2 (Cooley-Tukey itératif). */
        fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    re[i] = re[j].also { re[j] = re[i] }
                    im[i] = im[j].also { im[j] = im[i] }
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang); val wi = sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0; var ci = 0.0
                    for (k in 0 until len / 2) {
                        val ur = re[i + k]; val ui = im[i + k]
                        val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                        val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                        re[i + k] = ur + vr; im[i + k] = ui + vi
                        re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                        val nr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr; cr = nr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
