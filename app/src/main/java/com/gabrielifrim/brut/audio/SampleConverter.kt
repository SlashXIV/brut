package com.gabrielifrim.brut.audio

import kotlin.math.roundToInt

/**
 * Conversions d'échantillons. Convention : un flottant de 1.0 vaut 2^(n-1) en entier,
 * ce qui rend l'aller-retour entier -> flottant -> entier exact (aucune perte).
 * Aucun tramage (dither) n'est appliqué : ce serait un traitement.
 */
object SampleConverter {
    private const val SCALE_16 = 32768f
    private const val SCALE_24 = 8388608f

    fun pcm16ToFloat(sample: Short): Float = sample / SCALE_16

    fun floatToPcm16(sample: Float): Short =
        (sample * SCALE_16).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    fun floatToPcm24(sample: Float): Int =
        (sample * SCALE_24).roundToInt().coerceIn(-8_388_608, 8_388_607)

    /**
     * Écrit [count] échantillons flottants au format [depth], petit-boutiste, dans [out]
     * à partir de [offset]. Retourne le nombre d'octets écrits.
     */
    fun encode(samples: FloatArray, count: Int, depth: BitDepth, out: ByteArray, offset: Int = 0): Int {
        var p = offset
        for (i in 0 until count) {
            val s = samples[i]
            when (depth) {
                BitDepth.PCM_16 -> {
                    val v = floatToPcm16(s).toInt()
                    out[p++] = v.toByte()
                    out[p++] = (v shr 8).toByte()
                }
                BitDepth.PCM_24 -> {
                    val v = floatToPcm24(s)
                    out[p++] = v.toByte()
                    out[p++] = (v shr 8).toByte()
                    out[p++] = (v shr 16).toByte()
                }
                BitDepth.FLOAT_32 -> {
                    // Le flottant n'est PAS écrêté : un fichier 32 bit flottant conserve
                    // les dépassements au-delà de 0 dBFS, c'est tout son intérêt.
                    val v = java.lang.Float.floatToRawIntBits(s)
                    out[p++] = v.toByte()
                    out[p++] = (v shr 8).toByte()
                    out[p++] = (v shr 16).toByte()
                    out[p++] = (v shr 24).toByte()
                }
            }
        }
        return p - offset
    }

    /** Copie directe d'échantillons 16 bit : chemin bit-exact quand rien n'est modifié. */
    fun encodePcm16(samples: ShortArray, count: Int, out: ByteArray, offset: Int = 0): Int {
        var p = offset
        for (i in 0 until count) {
            val v = samples[i].toInt()
            out[p++] = v.toByte()
            out[p++] = (v shr 8).toByte()
        }
        return p - offset
    }
}
