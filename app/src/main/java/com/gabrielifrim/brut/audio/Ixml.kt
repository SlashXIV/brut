package com.gabrielifrim.brut.audio

/** Horodatage écrit dans le bloc `<SPEED>` de l'iXML. */
data class IxmlSpeed(
    val rate: TimecodeRate,
    val sampleRate: Int,
    val bitDepth: Int,
    /** Échantillons depuis minuit (la même valeur que la référence horaire du bext). */
    val timeReference: Long,
)

/**
 * Chunk `iXML` : le standard des enregistreurs de terrain, lu par les logiciels de
 * montage et de synchronisation. Contrairement au `bext`, il est en UTF-8 : la note
 * de prise garde ses accents, et chaque piste porte un nom (Gauche / Droite).
 *
 * Le bloc `<SPEED>` porte la cadence du timecode et l'heure de départ. Il est suivi d'une
 * réserve d'espaces (autorisés après l'élément racine) : l'heure peut ainsi être réécrite
 * en place, après un calage sur LTC, sans déplacer les données audio qui suivent.
 */
object Ixml {
    const val CHUNK_ID = "iXML"
    private const val SLACK = 96

    fun encode(note: String, project: String, take: String, trackNames: List<String>, speed: IxmlSpeed? = null): ByteArray {
        val tracks = trackNames.mapIndexed { i, name ->
            "<TRACK><CHANNEL_INDEX>${i + 1}</CHANNEL_INDEX><INTERLEAVE_INDEX>${i + 1}</INTERLEAVE_INDEX>" +
                "<NAME>${escape(name)}</NAME></TRACK>"
        }.joinToString("")
        val xml = """<?xml version="1.0" encoding="UTF-8"?>""" +
            "<BWFXML><IXML_VERSION>2.10</IXML_VERSION>" +
            "<PROJECT>${escape(project)}</PROJECT>" +
            "<TAKE>${escape(take)}</TAKE>" +
            "<NOTE>${escape(note)}</NOTE>" +
            (speed?.let(::speedBlock) ?: "") +
            "<TRACK_LIST><TRACK_COUNT>${trackNames.size}</TRACK_COUNT>$tracks</TRACK_LIST>" +
            "</BWFXML>" + " ".repeat(SLACK)
        return xml.toByteArray(Charsets.UTF_8)
    }

    private fun speedBlock(s: IxmlSpeed): String {
        val hi = s.timeReference ushr 32
        val lo = s.timeReference and 0xFFFF_FFFFL
        return "<SPEED>" +
            "<MASTER_SPEED>${s.rate.ixmlRate}</MASTER_SPEED>" +
            "<CURRENT_SPEED>${s.rate.ixmlRate}</CURRENT_SPEED>" +
            "<TIMECODE_RATE>${s.rate.ixmlRate}</TIMECODE_RATE>" +
            "<TIMECODE_FLAG>${s.rate.ixmlFlag}</TIMECODE_FLAG>" +
            "<FILE_SAMPLE_RATE>${s.sampleRate}</FILE_SAMPLE_RATE>" +
            "<AUDIO_BIT_DEPTH>${s.bitDepth}</AUDIO_BIT_DEPTH>" +
            "<DIGITIZER_SAMPLE_RATE>${s.sampleRate}</DIGITIZER_SAMPLE_RATE>" +
            "<TIMESTAMP_SAMPLES_SINCE_MIDNIGHT_HI>$hi</TIMESTAMP_SAMPLES_SINCE_MIDNIGHT_HI>" +
            "<TIMESTAMP_SAMPLES_SINCE_MIDNIGHT_LO>$lo</TIMESTAMP_SAMPLES_SINCE_MIDNIGHT_LO>" +
            "<TIMESTAMP_SAMPLE_RATE>${s.sampleRate}</TIMESTAMP_SAMPLE_RATE>" +
            "</SPEED>"
    }

    /**
     * Même iXML avec une nouvelle heure de départ et une nouvelle cadence, **de même taille
     * en octets** (la réserve d'espaces absorbe l'écart) ; null si la place manque.
     */
    fun withTimestamp(body: ByteArray, speed: IxmlSpeed): ByteArray? {
        val xml = String(body, Charsets.UTF_8)
        val end = xml.indexOf("</BWFXML>").takeIf { it >= 0 } ?: return null
        val block = speedBlock(speed)
        val existing = Regex("<SPEED>.*?</SPEED>", RegexOption.DOT_MATCHES_ALL)
        val head = xml.substring(0, end)
        val withBlock = when {
            existing.containsMatchIn(head) -> existing.replace(head, Regex.escapeReplacement(block))
            "<TRACK_LIST>" in head -> head.replace("<TRACK_LIST>", block + "<TRACK_LIST>")
            else -> head + block
        }
        return fit(withBlock + "</BWFXML>", body.size)
            // iXML d'un autre enregistreur, sans réserve : on ne change que les valeurs déjà présentes.
            ?: replaceValues(head, speed).takeIf { it != head }?.let { fit("$it</BWFXML>", body.size) }
    }

    private fun replaceValues(xml: String, s: IxmlSpeed): String {
        val values = mapOf(
            "TIMECODE_RATE" to s.rate.ixmlRate,
            "TIMECODE_FLAG" to s.rate.ixmlFlag,
            "TIMESTAMP_SAMPLES_SINCE_MIDNIGHT_HI" to (s.timeReference ushr 32).toString(),
            "TIMESTAMP_SAMPLES_SINCE_MIDNIGHT_LO" to (s.timeReference and 0xFFFF_FFFFL).toString(),
            "TIMESTAMP_SAMPLE_RATE" to s.sampleRate.toString(),
        )
        return values.entries.fold(xml) { acc, (tag, value) ->
            acc.replace(Regex("<$tag>.*?</$tag>"), Regex.escapeReplacement("<$tag>$value</$tag>"))
        }
    }

    private fun fit(xml: String, size: Int): ByteArray? {
        val bytes = xml.toByteArray(Charsets.UTF_8)
        if (bytes.size > size) return null
        return bytes + ByteArray(size - bytes.size) { ' '.code.toByte() }
    }

    /** Extrait la note d'un chunk iXML ; null si absente. */
    fun decodeNote(body: ByteArray): String? {
        val xml = String(body, Charsets.UTF_8)
        val match = Regex("<NOTE>(.*?)</NOTE>", RegexOption.DOT_MATCHES_ALL).find(xml) ?: return null
        return unescape(match.groupValues[1]).trim().ifBlank { null }
    }

    /** Cadence du timecode déclarée dans le bloc SPEED ; null si absente. */
    fun decodeRate(body: ByteArray): TimecodeRate? {
        val xml = String(body, Charsets.UTF_8)
        fun tag(name: String) = Regex("<$name>(.*?)</$name>").find(xml)?.groupValues?.get(1)
        return TimecodeRate.fromIxml(tag("TIMECODE_RATE"), tag("TIMECODE_FLAG"))
    }

    private fun escape(text: String) = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun unescape(text: String) = text
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
