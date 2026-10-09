package com.gabrielifrim.brut.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Réécrit en place l'heure de départ d'une prise (calage sur LTC) : la référence horaire
 * du `bext` (8 octets à position fixe) et le bloc SPEED de l'iXML (à taille constante).
 * Aucun octet audio n'est touché, la taille du fichier ne change pas.
 *
 * Deux canaux sur le même fichier, comme [WavRepair] : sous Android, un descripteur de
 * fournisseur de contenu se lit et s'écrit par deux flux distincts.
 */
object WavMetadata {

    /** Retourne vrai si la référence horaire du bext a été réécrite. */
    fun patchTimestamp(input: FileChannel, output: FileChannel, speed: IxmlSpeed): Boolean {
        var pos = 12L
        var bextDone = false
        val size = input.size()
        while (pos + 8 <= size) {
            val h = read(input, pos, 8)
            val id = String(h.array(), 0, 4, Charsets.US_ASCII)
            val chunkSize = h.getInt(4).toLong() and 0xFFFF_FFFFL
            val body = pos + 8
            when (id) {
                Bext.CHUNK_ID -> if (chunkSize >= Bext.FIXED_SIZE) {
                    val le = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(speed.timeReference)
                    le.flip()
                    write(output, body + TIME_REFERENCE_OFFSET, le)
                    bextDone = true
                }
                Ixml.CHUNK_ID -> if (chunkSize in 1..262_144) {
                    Ixml.withTimestamp(read(input, body, chunkSize.toInt()).array(), speed)?.let { write(output, body, ByteBuffer.wrap(it)) }
                }
                // Les métadonnées de Brut précèdent toujours les données.
                "data" -> break
            }
            pos = body + chunkSize + (chunkSize and 1)
        }
        output.force(true)
        return bextDone
    }

    /** Position de la référence horaire dans le corps du bext (EBU Tech 3285). */
    const val TIME_REFERENCE_OFFSET = 338

    private fun read(channel: FileChannel, position: Long, length: Int): ByteBuffer {
        val b = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        var p = position
        while (b.hasRemaining()) {
            val r = channel.read(b, p)
            if (r <= 0) break
            p += r
        }
        return b
    }

    private fun write(channel: FileChannel, position: Long, buffer: ByteBuffer) {
        var p = position
        while (buffer.hasRemaining()) p += channel.write(buffer, p)
    }
}
