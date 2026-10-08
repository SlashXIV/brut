package com.gabrielifrim.brut.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Métadonnées Broadcast Wave (chunk `bext`, EBU Tech 3285 v2), lues par les logiciels
 * de montage : date et heure de la prise, origine, et une description de la chaîne
 * d'enregistrement (entrée, gain, mode de capture).
 */
data class Bext(
    val description: String,
    val originator: String,
    val originatorReference: String,
    val date: LocalDateTime,
    /** Position du premier échantillon, en échantillons depuis minuit. */
    val timeReference: Long,
    val codingHistory: String,
) {
    fun encode(): ByteArray {
        val history = ascii(codingHistory).toByteArray(Charsets.US_ASCII)
        val b = ByteBuffer.allocate(FIXED_SIZE + history.size).order(ByteOrder.LITTLE_ENDIAN)
        putField(b, description, 256)
        putField(b, originator, 32)
        putField(b, originatorReference, 32)
        putField(b, date.format(DATE), 10)
        putField(b, date.format(TIME), 8)
        b.putInt((timeReference and 0xFFFF_FFFFL).toInt())
        b.putInt((timeReference ushr 32).toInt())
        b.putShort(2)                 // version 2 : champs de sonie présents (laissés à 0)
        b.position(b.position() + 64) // UMID absent
        b.position(b.position() + 10) // sonie : non mesurée à l'enregistrement
        b.position(b.position() + 180) // réservé
        b.put(history)
        return b.array()
    }

    companion object {
        const val CHUNK_ID = "bext"
        const val FIXED_SIZE = 602
        private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

        /** Le bext est en ASCII : on retire les accents plutôt que d'écrire des « ? ». */
        fun ascii(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace('−', '-')
                .map { if (it.code in 0x20..0x7E || it == '\r' || it == '\n') it else '?' }
                .joinToString("")

        private fun putField(b: ByteBuffer, text: String, size: Int) {
            val bytes = ascii(text).toByteArray(Charsets.US_ASCII).copyOf(size)
            b.put(bytes)
        }

        /** Lit les champs utiles d'un chunk bext (corps seul, sans l'en-tête de chunk). */
        fun decode(body: ByteArray): Bext? {
            if (body.size < FIXED_SIZE) return null
            fun field(offset: Int, size: Int) =
                String(body, offset, size, Charsets.US_ASCII).substringBefore('\u0000').trim()
            val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
            val low = b.getInt(338).toLong() and 0xFFFF_FFFFL
            val high = b.getInt(342).toLong() and 0xFFFF_FFFFL
            val date = runCatching {
                LocalDateTime.parse("${field(320, 10)}T${field(330, 8).replace('-', ':')}")
            }.getOrNull() ?: return null
            return Bext(
                description = field(0, 256),
                originator = field(256, 32),
                originatorReference = field(288, 32),
                date = date,
                timeReference = (high shl 32) or low,
                codingHistory = String(body, FIXED_SIZE, body.size - FIXED_SIZE, Charsets.US_ASCII).trimEnd('\u0000'),
            )
        }

        /** Ligne d'historique de codage normalisée (EBU R98) : « A=PCM,F=48000,W=24,M=stereo,T=… ». */
        fun codingHistoryFor(format: AudioFormatSpec, tool: String): String {
            val algorithm = if (format.bitDepth.isFloat) "PCM-FLOAT" else "PCM"
            val mode = if (format.channels == 1) "mono" else "stereo"
            return "A=$algorithm,F=${format.sampleRate},W=${format.bitDepth.bits},M=$mode,T=$tool\r\n"
        }
    }
}
