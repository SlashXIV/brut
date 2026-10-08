package com.gabrielifrim.brut.audio

/**
 * Chunk `iXML` : le standard des enregistreurs de terrain, lu par les logiciels de
 * montage et de synchronisation. Contrairement au `bext`, il est en UTF-8 : la note
 * de prise garde ses accents, et chaque piste porte un nom (Gauche / Droite).
 */
object Ixml {
    const val CHUNK_ID = "iXML"

    fun encode(note: String, project: String, take: String, trackNames: List<String>): ByteArray {
        val tracks = trackNames.mapIndexed { i, name ->
            "<TRACK><CHANNEL_INDEX>${i + 1}</CHANNEL_INDEX><INTERLEAVE_INDEX>${i + 1}</INTERLEAVE_INDEX>" +
                "<NAME>${escape(name)}</NAME></TRACK>"
        }.joinToString("")
        val xml = """<?xml version="1.0" encoding="UTF-8"?>""" +
            "<BWFXML><IXML_VERSION>2.10</IXML_VERSION>" +
            "<PROJECT>${escape(project)}</PROJECT>" +
            "<TAKE>${escape(take)}</TAKE>" +
            "<NOTE>${escape(note)}</NOTE>" +
            "<TRACK_LIST><TRACK_COUNT>${trackNames.size}</TRACK_COUNT>$tracks</TRACK_LIST>" +
            "</BWFXML>"
        return xml.toByteArray(Charsets.UTF_8)
    }

    /** Extrait la note d'un chunk iXML ; null si absente. */
    fun decodeNote(body: ByteArray): String? {
        val xml = String(body, Charsets.UTF_8)
        val match = Regex("<NOTE>(.*?)</NOTE>", RegexOption.DOT_MATCHES_ALL).find(xml) ?: return null
        return unescape(match.groupValues[1]).trim().ifBlank { null }
    }

    private fun escape(text: String) = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun unescape(text: String) = text
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
