package com.gabrielifrim.brut.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Changement de fréquence d'échantillonnage, réservé à l'export : c'est un traitement,
 * jamais appliqué à l'enregistrement.
 *
 * Polyphase à sinc fenêtré (Kaiser), rapport rationnel L/M exact (48 → 44,1 kHz = 147/160).
 * La bande passante va jusqu'à 20 kHz (ou 0,907 × Nyquist), l'atténuation au-delà du
 * Nyquist le plus bas dépasse [attenuationDb] : ni repliement ni images audibles.
 * Le retard du filtre est compensé : la trame de sortie 0 tombe sur l'entrée t = 0, et
 * une entrée de n trames donne round(n × sortie / entrée) trames.
 *
 * Fonctionne en flux ([process] puis [flush]) : une prise de plusieurs heures ne tient pas
 * en mémoire.
 */
class Resampler(
    val inRate: Int,
    val outRate: Int,
    val channels: Int,
    attenuationDb: Double = 130.0,
) {
    /** Facteur de suréchantillonnage. */
    val l: Int
    /** Facteur de décimation. */
    val m: Int
    private val taps: Int
    private val delay: Long
    private val h: DoubleArray

    // Entrées encore utiles, entrelacées, à partir de l'indice absolu [bufStart].
    private var buf = FloatArray(0)
    private var bufFrames = 0
    private var bufStart = 0L
    private var inTotal = 0L
    private var outNext = 0L

    init {
        require(inRate > 0 && outRate > 0 && channels > 0)
        val g = gcd(inRate, outRate)
        l = outRate / g
        m = inRate / g
        val nyquist = min(inRate, outRate) / 2.0
        val pass = nyquist * PASS_FRACTION
        val transition = nyquist - pass
        val cutoff = (pass + nyquist) / 2
        val beta = 0.1102 * (attenuationDb - 8.7)
        // Formule de Kaiser, exprimée en coefficients par phase (à la fréquence d'entrée).
        val perPhase = ceil((attenuationDb - 8) / (2.285 * 2 * PI * transition / inRate)).toInt().let { it + (it and 1) }
        taps = perPhase * l + 1
        delay = ((taps - 1) / 2).toLong()
        val wc = 2 * cutoff / (inRate.toDouble() * l)
        val center = (taps - 1) / 2.0
        val i0Beta = besselI0(beta)
        h = DoubleArray(taps) { i ->
            val x = i - center
            val sinc = if (x == 0.0) wc else sin(PI * wc * x) / (PI * x)
            val r = 2 * i / (taps - 1.0) - 1
            sinc * besselI0(beta * sqrt(1 - r * r)) / i0Beta * l
        }
        // Gain continu exactement 1 : chaque phase somme à l, on corrige l'écart résiduel.
        val dc = h.sum() / l
        for (i in h.indices) h[i] /= dc
    }

    /** Trames de sortie correspondant à [inputFrames] trames d'entrée. */
    fun outputFrames(inputFrames: Long): Long = (inputFrames.toDouble() * l / m).roundToLong()

    /** Ajoute [frames] trames entrelacées ; retourne les trames de sortie déjà calculables. */
    fun process(input: FloatArray, frames: Int): FloatArray {
        append(input, frames)
        inTotal += frames
        // Une sortie ne part que lorsque toutes ses entrées sont arrivées.
        return produce { j -> lastInput(j) < inTotal }
    }

    /** Fin du signal : les entrées manquantes valent zéro, la sortie s'arrête à la bonne longueur. */
    fun flush(): FloatArray {
        val total = outputFrames(inTotal)
        return produce { j -> j < total }
    }

    private fun produce(ready: (Long) -> Boolean): FloatArray {
        var out = FloatArray(256 * channels)
        var n = 0
        val acc = DoubleArray(channels)
        while (ready(outNext)) {
            val t = outNext * m + delay
            val kMin = maxOf(Math.floorDiv(t - (taps - 1) + l - 1, l.toLong()), 0L)
            val kMax = lastInput(outNext)
            acc.fill(0.0)
            var k = kMin
            while (k <= kMax) {
                val local = k - bufStart
                if (local in 0 until bufFrames) {
                    val c = h[(t - k * l).toInt()]
                    val base = (local * channels).toInt()
                    for (ch in 0 until channels) acc[ch] += c * buf[base + ch]
                }
                k++
            }
            if ((n + 1) * channels > out.size) out = out.copyOf(out.size * 2)
            for (ch in 0 until channels) out[n * channels + ch] = acc[ch].toFloat()
            n++
            outNext++
        }
        discardBefore(maxOf(Math.floorDiv(outNext * m + delay - (taps - 1) + l - 1, l.toLong()), 0L))
        return out.copyOf(n * channels)
    }

    private fun lastInput(j: Long): Long = Math.floorDiv(j * m + delay, l.toLong())

    private fun append(input: FloatArray, frames: Int) {
        val needed = (bufFrames + frames) * channels
        if (needed > buf.size) buf = buf.copyOf(maxOf(needed, buf.size * 2))
        System.arraycopy(input, 0, buf, bufFrames * channels, frames * channels)
        bufFrames += frames
    }

    private fun discardBefore(frame: Long) {
        val drop = (frame - bufStart).coerceIn(0, bufFrames.toLong()).toInt()
        if (drop == 0) return
        System.arraycopy(buf, drop * channels, buf, 0, (bufFrames - drop) * channels)
        bufFrames -= drop
        bufStart += drop
    }

    companion object {
        /** 20 kHz pour un Nyquist de 22,05 kHz : toute la bande audible passe. */
        const val PASS_FRACTION = 20_000.0 / 22_050.0

        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val q = x * x / 4
            var k = 1
            while (term > 1e-17 * sum) {
                term *= q / (k.toDouble() * k)
                sum += term
                k++
            }
            return sum
        }
    }
}
