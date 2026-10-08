package com.gabrielifrim.brut.audio

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Remet d'aplomb une prise interrompue (crash, batterie à plat, appli tuée) : toutes
 * les données présentes sur le disque sont comptées, même celles écrites après la
 * dernière mise à jour de l'en-tête. Une trame incomplète en fin de fichier est retirée.
 */
object WavRepair {

    /** Répare l'en-tête en place et retourne le nombre de trames récupérées. */
    fun repair(channel: FileChannel): Long = repair(channel, channel)

    /**
     * Variante à deux canaux sur le même fichier : sous Android, un descripteur obtenu
     * d'un fournisseur de contenu se lit par un FileInputStream et s'écrit par un
     * FileOutputStream, chacun n'ouvrant son canal que dans un sens.
     */
    fun repair(input: FileChannel, output: FileChannel): Long {
        val channel = input
        val size = channel.size()
        var pos = 12L
        var hasReserve = false
        var factBody = -1L
        var blockAlign = 0
        var dataBody = -1L
        while (pos + 8 <= size) {
            val h = read(channel, pos, 8)
            val id = String(h.array(), 0, 4, Charsets.US_ASCII)
            val chunkSize = h.getInt(4).toLong() and 0xFFFF_FFFFL
            val body = pos + 8
            when (id) {
                "JUNK", "ds64" -> if (pos == 12L && chunkSize == WavWriter.DS64_SIZE.toLong()) hasReserve = true
                "fmt " -> blockAlign = read(channel, body, 16).getShort(12).toInt()
                "fact" -> factBody = body
                "data" -> { dataBody = body; break }
            }
            pos = body + chunkSize + (chunkSize and 1)
        }
        if (dataBody < 0 || blockAlign <= 0) throw IOException("En-tête WAV irrécupérable")

        val dataBytes = (size - dataBody).let { it - it % blockAlign }
        val frames = dataBytes / blockAlign
        var end = dataBody + dataBytes
        output.truncate(end)
        if (dataBytes and 1L == 1L) {
            write(output, end, ByteBuffer.wrap(byteArrayOf(0)))
            end++
        }
        val riffSize = end - 8
        val le = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        if (riffSize > 0xFFFF_FFFFL && hasReserve) {
            write(output, 0, ByteBuffer.wrap("RF64".toByteArray(Charsets.US_ASCII)))
            write(output, 4, u32(le, 0xFFFF_FFFFL))
            write(output, 12, ByteBuffer.wrap("ds64".toByteArray(Charsets.US_ASCII)))
            write(output, 20, u64(le, riffSize))
            write(output, 28, u64(le, dataBytes))
            write(output, 36, u64(le, frames))
            write(output, dataBody - 4, u32(le, 0xFFFF_FFFFL))
            if (factBody >= 0) write(output, factBody, u32(le, 0xFFFF_FFFFL))
        } else {
            write(output, 4, u32(le, riffSize))
            write(output, dataBody - 4, u32(le, dataBytes))
            if (factBody >= 0) write(output, factBody, u32(le, frames))
        }
        output.force(true)
        return frames
    }

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

    private fun u32(b: ByteBuffer, v: Long): ByteBuffer {
        b.clear(); b.putInt((v and 0xFFFF_FFFFL).toInt()); b.flip(); return b
    }

    private fun u64(b: ByteBuffer, v: Long): ByteBuffer {
        b.clear(); b.putLong(v); b.flip(); return b
    }
}
