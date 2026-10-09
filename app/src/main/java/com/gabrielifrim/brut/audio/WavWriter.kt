package com.gabrielifrim.brut.audio

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Repère posé pendant la prise : une position (en trames) et une étiquette. */
data class Marker(val frame: Long, val label: String)

/**
 * Écrit un fichier WAV en flux sur un canal positionnable.
 *
 * L'en-tête est posé dès l'ouverture avec des tailles nulles, puis réécrit à chaque
 * [updateHeader] et à la [close] : un fichier interrompu brutalement (crash, batterie)
 * reste lisible jusqu'à la dernière mise à jour, et [WavRepair] récupère le reste.
 *
 * - 16 bit : PCM classique, lu partout.
 * - 24 bit : WAVE_FORMAT_EXTENSIBLE, la forme correcte au-delà de 16 bit.
 * - 32 bit : flottant IEEE (format 3) + chunk `fact`, obligatoire hors PCM.
 *
 * Au-delà de 4 Go, le fichier devient un **RF64** (EBU Tech 3306) : un chunk `JUNK`
 * réservé en tête est alors transformé en `ds64`, qui porte les tailles sur 64 bit.
 * Tant que la limite n'est pas atteinte, le fichier reste un WAV ordinaire.
 */
class WavWriter(
    private val channel: FileChannel,
    val format: AudioFormatSpec,
    /**
     * Chunks de métadonnées placés avant `data` (ex. `bext`, `iXML`). Les lecteurs
     * ignorent ceux qu'ils ne connaissent pas : le fichier reste un WAV standard.
     */
    private val extraChunks: List<Pair<String, ByteArray>> = emptyList(),
    /** Seuil de passage en RF64 ; abaissé dans les tests pour ne pas écrire 4 Go. */
    private val rf64Threshold: Long = 0xFFFF_FFFFL,
) : Closeable {

    /** Position et taille du corps de chaque chunk de métadonnées, pour les réécrire en place. */
    private val chunkBodies = HashMap<String, Pair<Long, Int>>()
    private val headerSize: Int
    private val factOffset: Int?
    private val markers = mutableListOf<Marker>()

    var dataBytes: Long = 0
        private set

    val framesWritten: Long get() = dataBytes / format.bytesPerFrame

    init {
        val header = buildHeader()
        headerSize = header.limit()
        // RIFF (12) + JUNK (8 + 28) + en-tête fmt (8) + corps fmt (18 en flottant) + en-tête fact (8)
        factOffset = if (format.bitDepth.isFloat) 12 + 36 + 8 + 18 + 8 else null
        channel.truncate(0)
        writeFully(header, 0)
    }

    fun write(bytes: ByteArray, length: Int) {
        if (length == 0) return
        writeFully(ByteBuffer.wrap(bytes, 0, length), headerSize.toLong() + dataBytes)
        dataBytes += length
    }

    /** Pose un repère à la position courante de la prise (écrit à la fermeture). */
    fun addMarker(label: String, frame: Long = framesWritten) {
        markers += Marker(frame, label)
    }

    /**
     * Remplace le corps d'un chunk de métadonnées par un corps de même taille (heure de
     * départ calée sur le LTC à la fin de la prise). Faux si le chunk est absent ou si la
     * taille diffère.
     */
    fun rewriteChunk(id: String, body: ByteArray): Boolean {
        val (offset, size) = chunkBodies[id] ?: return false
        if (body.size != size) return false
        writeFully(ByteBuffer.wrap(body), offset)
        return true
    }

    /** Toujours faux en pratique : le RF64 lève la limite des 4 Go. Garde-fou contre un débordement. */
    fun wouldOverflow(length: Int): Boolean = dataBytes + length > MAX_DATA_BYTES

    fun updateHeader(tailBytes: Long = 0) {
        val pad = dataBytes and 1L
        val riffSize = headerSize - 8 + dataBytes + pad + tailBytes
        val le = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        if (riffSize > rf64Threshold || dataBytes > rf64Threshold) {
            writeFully(ByteBuffer.wrap(ascii("RF64")), 0)
            putU32(le, 0xFFFF_FFFFL); writeFully(le, 4)
            writeFully(ByteBuffer.wrap(ascii("ds64")), 12)
            putU64(le, riffSize); writeFully(le, 20)
            putU64(le, dataBytes); writeFully(le, 28)
            putU64(le, framesWritten); writeFully(le, 36)
            putU32(le, 0xFFFF_FFFFL); writeFully(le, headerSize - 4L)
            factOffset?.let { putU32(le, 0xFFFF_FFFFL); writeFully(le, it.toLong()) }
        } else {
            putU32(le, riffSize); writeFully(le, 4)
            putU32(le, dataBytes); writeFully(le, headerSize - 4L)
            factOffset?.let { putU32(le, framesWritten); writeFully(le, it.toLong()) }
        }
    }

    override fun close() {
        var end = headerSize.toLong() + dataBytes
        if (dataBytes and 1L == 1L) {
            writeFully(ByteBuffer.wrap(byteArrayOf(0)), end)
            end++
        }
        // Les repères suivent les données : un lecteur qui ne les connaît pas s'arrête à `data`.
        val tail = encodeMarkers(markers)
        if (tail.isNotEmpty()) writeFully(ByteBuffer.wrap(tail), end)
        channel.truncate(end + tail.size)
        updateHeader(tail.size.toLong())
        channel.force(true)
        channel.close()
    }

    private fun putU32(buffer: ByteBuffer, value: Long) {
        buffer.clear()
        buffer.putInt((value and 0xFFFF_FFFFL).toInt())
        buffer.flip()
    }

    private fun putU64(buffer: ByteBuffer, value: Long) {
        buffer.clear()
        buffer.putLong(value)
        buffer.flip()
    }

    private fun writeFully(buffer: ByteBuffer, position: Long) {
        var pos = position
        while (buffer.hasRemaining()) pos += channel.write(buffer, pos)
    }

    private fun buildHeader(): ByteBuffer {
        val bits = format.bitDepth.bits
        val blockAlign = format.bytesPerFrame
        val extraSize = extraChunks.sumOf { 8 + it.second.size + (it.second.size and 1) }
        val b = ByteBuffer.allocate(120 + extraSize).order(ByteOrder.LITTLE_ENDIAN)
        b.put(ascii("RIFF")); b.putInt(0); b.put(ascii("WAVE"))
        // Place réservée au ds64 du RF64 (taille identique) : ignorée tant qu'elle s'appelle JUNK.
        b.put(ascii("JUNK")); b.putInt(DS64_SIZE); b.put(ByteArray(DS64_SIZE))
        b.put(ascii("fmt "))
        when (format.bitDepth) {
            BitDepth.PCM_16 -> {
                b.putInt(16)
                fmtCommon(b, FORMAT_PCM, blockAlign, bits)
            }
            BitDepth.PCM_24 -> {
                b.putInt(40)
                fmtCommon(b, FORMAT_EXTENSIBLE, blockAlign, bits)
                b.putShort(22)                                  // cbSize
                b.putShort(bits.toShort())                      // bits valides
                b.putInt(if (format.channels == 1) SPEAKER_MONO else SPEAKER_STEREO)
                b.put(SUBFORMAT_PCM_GUID)
            }
            BitDepth.FLOAT_32 -> {
                b.putInt(18)
                fmtCommon(b, FORMAT_IEEE_FLOAT, blockAlign, bits)
                b.putShort(0)                                   // cbSize
                b.put(ascii("fact")); b.putInt(4); b.putInt(0)
            }
        }
        for ((id, body) in extraChunks) {
            require(id.length == 4) { "Identifiant de chunk invalide : $id" }
            b.put(ascii(id)); b.putInt(body.size)
            chunkBodies[id] = b.position().toLong() to body.size
            b.put(body)
            if (body.size and 1 == 1) b.put(0)
        }
        b.put(ascii("data")); b.putInt(0)
        b.flip()
        return b
    }

    private fun fmtCommon(b: ByteBuffer, tag: Int, blockAlign: Int, bits: Int) {
        b.putShort(tag.toShort())
        b.putShort(format.channels.toShort())
        b.putInt(format.sampleRate)
        b.putInt(format.sampleRate * blockAlign)
        b.putShort(blockAlign.toShort())
        b.putShort(bits.toShort())
    }

    companion object {
        private const val FORMAT_PCM = 1
        private const val FORMAT_IEEE_FLOAT = 3
        private const val FORMAT_EXTENSIBLE = 0xFFFE
        private const val SPEAKER_MONO = 0x4          // FRONT_CENTER
        private const val SPEAKER_STEREO = 0x3        // FRONT_LEFT | FRONT_RIGHT
        const val DS64_SIZE = 28

        /** Limite de sûreté (RF64) : bien au-delà de toute carte mémoire. */
        const val MAX_DATA_BYTES = 1L shl 50

        // KSDATAFORMAT_SUBTYPE_PCM : 00000001-0000-0010-8000-00aa00389b71
        private val SUBFORMAT_PCM_GUID = byteArrayOf(
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
            0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71,
        )

        private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

        /**
         * Chunks `cue ` + `LIST/adtl` (étiquettes `labl`) : la forme standard des repères
         * dans un WAV, lue par Audacity, Reaper, Pro Tools… Position plafonnée à 2³²−1 trames.
         */
        fun encodeMarkers(markers: List<Marker>): ByteArray {
            if (markers.isEmpty()) return ByteArray(0)
            val labels = markers.mapIndexed { i, m ->
                val text = Bext.ascii(m.label).toByteArray(Charsets.US_ASCII) + 0
                Triple(i + 1, text, text.size and 1)
            }
            val cueSize = 4 + 24 * markers.size
            val adtlSize = 4 + labels.sumOf { 8 + 4 + it.second.size + it.third }
            val b = ByteBuffer.allocate(8 + cueSize + 8 + adtlSize).order(ByteOrder.LITTLE_ENDIAN)
            b.put(ascii("cue ")); b.putInt(cueSize); b.putInt(markers.size)
            markers.forEachIndexed { i, m ->
                val pos = m.frame.coerceIn(0, 0xFFFF_FFFFL).toInt()
                b.putInt(i + 1); b.putInt(pos); b.put(ascii("data")); b.putInt(0); b.putInt(0); b.putInt(pos)
            }
            b.put(ascii("LIST")); b.putInt(adtlSize); b.put(ascii("adtl"))
            for ((id, text, pad) in labels) {
                b.put(ascii("labl")); b.putInt(4 + text.size); b.putInt(id); b.put(text)
                if (pad == 1) b.put(0)
            }
            return b.array()
        }
    }
}
