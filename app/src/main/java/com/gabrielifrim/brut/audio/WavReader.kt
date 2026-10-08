package com.gabrielifrim.brut.audio

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel

/** Ce qu'un en-tête WAV dit du fichier. */
data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val isFloat: Boolean,
    val dataOffset: Long,
    val dataBytes: Long,
    val bext: Bext?,
    /** Note de prise du chunk iXML (UTF-8, accents compris). */
    val note: String? = null,
    /** Repères (`cue` + `labl`) posés pendant la prise. */
    val markers: List<Marker> = emptyList(),
) {
    /** Ce qu'il faut afficher de la prise : la note iXML, sinon la description bext. */
    val description: String? get() = note ?: bext?.description?.takeIf { it.isNotBlank() }

    val bytesPerSample: Int get() = bitsPerSample / 8
    val blockAlign: Int get() = bytesPerSample * channels
    val frames: Long get() = dataBytes / blockAlign
    val durationSeconds: Double get() = frames.toDouble() / sampleRate
}

/**
 * Lit les WAV de Brut et ceux des autres : PCM 16/24/32 bit, flottant 32 bit, format
 * classique ou EXTENSIBLE. Tolère un fichier interrompu dont l'en-tête n'a pas été
 * finalisé (taille de données à 0 ou absurde) : on se fie alors à la taille réelle.
 */
object WavReader {

    fun readInfo(channel: SeekableByteChannel): WavInfo {
        val size = channel.size()
        val head = read(channel, 0, 12)
        val riff = String(head, 0, 4, Charsets.US_ASCII)
        if ((riff != "RIFF" && riff != "RF64") || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") {
            throw IOException("Ce fichier n'est pas un WAV")
        }
        var pos = 12L
        var rate = 0
        var channels = 0
        var bits = 0
        var isFloat = false
        var bext: Bext? = null
        var note: String? = null
        var ds64DataSize = -1L
        var dataOffset = -1L
        var dataBytes = 0L
        val cues = HashMap<Int, Long>()
        val labels = HashMap<Int, String>()
        while (pos + 8 <= size) {
            val h = ByteBuffer.wrap(read(channel, pos, 8)).order(ByteOrder.LITTLE_ENDIAN)
            val id = String(h.array(), 0, 4, Charsets.US_ASCII)
            var chunkSize = h.getInt(4).toLong() and 0xFFFF_FFFFL
            val body = pos + 8
            when (id) {
                "ds64" -> {
                    val d = ByteBuffer.wrap(read(channel, body, 24)).order(ByteOrder.LITTLE_ENDIAN)
                    ds64DataSize = d.getLong(8)
                }
                "fmt " -> {
                    val f = ByteBuffer.wrap(read(channel, body, minOf(chunkSize, 40L).toInt())).order(ByteOrder.LITTLE_ENDIAN)
                    var tag = f.getShort(0).toInt() and 0xFFFF
                    channels = f.getShort(2).toInt()
                    rate = f.getInt(4)
                    bits = f.getShort(14).toInt()
                    if (tag == 0xFFFE && chunkSize >= 40) tag = f.getShort(24).toInt() and 0xFFFF // sous-format
                    isFloat = when (tag) {
                        1 -> false
                        3 -> true
                        else -> throw IOException("Encodage WAV non pris en charge ($tag)")
                    }
                }
                Bext.CHUNK_ID -> if (chunkSize in Bext.FIXED_SIZE..65_536) {
                    bext = Bext.decode(read(channel, body, chunkSize.toInt()))
                }
                Ixml.CHUNK_ID -> if (chunkSize in 1..262_144) {
                    note = Ixml.decodeNote(read(channel, body, chunkSize.toInt()))
                }
                "data" -> {
                    if (channels == 0) throw IOException("Chunk fmt absent")
                    val available = size - body
                    if (chunkSize == 0xFFFF_FFFFL && ds64DataSize >= 0) chunkSize = ds64DataSize
                    // Taille nulle ou incohérente : fichier interrompu, on se fie à la taille réelle.
                    chunkSize = if (chunkSize == 0L || chunkSize > available) available else chunkSize
                    dataOffset = body
                    dataBytes = chunkSize
                }
                "cue " -> if (chunkSize in 4..1_000_000) {
                    val c = ByteBuffer.wrap(read(channel, body, chunkSize.toInt())).order(ByteOrder.LITTLE_ENDIAN)
                    val count = c.getInt(0)
                    for (k in 0 until count) {
                        val o = 4 + k * 24
                        if (o + 24 > chunkSize) break
                        cues[c.getInt(o)] = c.getInt(o + 20).toLong() and 0xFFFF_FFFFL
                    }
                }
                "LIST" -> if (chunkSize in 4..1_000_000) {
                    val l = read(channel, body, chunkSize.toInt())
                    if (String(l, 0, 4, Charsets.US_ASCII) == "adtl") {
                        var p = 4
                        val lb = ByteBuffer.wrap(l).order(ByteOrder.LITTLE_ENDIAN)
                        while (p + 8 <= l.size) {
                            val sub = String(l, p, 4, Charsets.US_ASCII)
                            val n = lb.getInt(p + 4)
                            if (sub == "labl" && n >= 4 && p + 8 + n <= l.size) {
                                labels[lb.getInt(p + 8)] = String(l, p + 12, n - 4, Charsets.US_ASCII).trimEnd('\u0000')
                            }
                            p += 8 + n + (n and 1)
                        }
                    }
                }
            }
            pos = body + chunkSize + (chunkSize and 1)
        }
        if (dataOffset < 0) throw IOException("Chunk data absent")
        if (bits !in setOf(16, 24, 32) || (isFloat && bits != 32)) {
            throw IOException("Résolution non prise en charge ($bits bit)")
        }
        val markers = cues.entries.sortedBy { it.value }.map { (id, frame) -> Marker(frame, labels[id].orEmpty()) }
        val info = WavInfo(rate, channels, bits, isFloat, dataOffset, dataBytes, bext, note, markers)
        return info.copy(dataBytes = dataBytes - dataBytes % info.blockAlign)
    }

    /**
     * Décode jusqu'à [frames] trames à partir de [startFrame] dans [out] (entrelacé, flottant).
     * Retourne le nombre de trames lues (0 en fin de fichier).
     */
    fun readFrames(channel: SeekableByteChannel, info: WavInfo, startFrame: Long, frames: Int, out: FloatArray, scratch: ByteBuffer? = null): Int {
        val remaining = info.frames - startFrame
        if (remaining <= 0) return 0
        val n = minOf(frames.toLong(), remaining).toInt()
        val bytes = n * info.blockAlign
        val buf = (scratch?.takeIf { it.capacity() >= bytes } ?: ByteBuffer.allocate(bytes)).order(ByteOrder.LITTLE_ENDIAN)
        buf.clear().limit(bytes)
        var p = info.dataOffset + startFrame * info.blockAlign
        while (buf.hasRemaining()) {
            val r = channel.position(p).read(buf)
            if (r <= 0) break
            p += r
        }
        buf.flip()
        val samples = (buf.limit() / info.bytesPerSample)
        for (i in 0 until samples) {
            out[i] = when {
                info.isFloat -> buf.getFloat(i * 4)
                info.bitsPerSample == 16 -> buf.getShort(i * 2) / 32768f
                info.bitsPerSample == 24 -> {
                    val o = i * 3
                    val v = (buf.get(o).toInt() and 0xFF) or
                        ((buf.get(o + 1).toInt() and 0xFF) shl 8) or
                        (buf.get(o + 2).toInt() shl 16)
                    v / 8388608f
                }
                else -> buf.getInt(i * 4) / 2147483648f
            }
        }
        return samples / info.channels
    }

    private fun read(channel: SeekableByteChannel, position: Long, length: Int): ByteArray {
        val b = ByteBuffer.allocate(length)
        channel.position(position)
        while (b.hasRemaining()) {
            if (channel.read(b) <= 0) break
        }
        return b.array()
    }
}
