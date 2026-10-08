package com.gabrielifrim.brut.audio

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Écrit un fichier WAV en flux sur un canal positionnable.
 *
 * L'en-tête est posé dès l'ouverture avec des tailles nulles, puis réécrit à chaque
 * [updateHeader] et à la [close] : un fichier interrompu brutalement (crash, batterie)
 * reste lisible jusqu'à la dernière mise à jour.
 *
 * - 16 bit : PCM classique (fmt de 16 octets), lu partout.
 * - 24 bit : WAVE_FORMAT_EXTENSIBLE, la forme correcte au-delà de 16 bit.
 * - 32 bit : flottant IEEE (format 3) + chunk `fact`, obligatoire hors PCM.
 */
class WavWriter(
    private val channel: FileChannel,
    val format: AudioFormatSpec,
    /**
     * Chunks de métadonnées placés avant `data` (ex. `bext` du Broadcast Wave). Les
     * lecteurs ignorent ceux qu'ils ne connaissent pas : le fichier reste un WAV standard.
     */
    private val extraChunks: List<Pair<String, ByteArray>> = emptyList(),
) : Closeable {

    private val headerSize: Int
    private val dataSizeOffset: Int
    private val factOffset: Int? = if (format.bitDepth.isFloat) FLOAT_FACT_OFFSET else null

    var dataBytes: Long = 0
        private set

    val framesWritten: Long get() = dataBytes / format.bytesPerFrame

    init {
        val header = buildHeader()
        headerSize = header.limit()
        dataSizeOffset = headerSize - 4
        channel.truncate(0)
        writeFully(header, 0)
    }

    fun write(bytes: ByteArray, length: Int) {
        if (length == 0) return
        check(!wouldOverflow(length)) { "Limite de 4 Go du format WAV atteinte" }
        writeFully(ByteBuffer.wrap(bytes, 0, length), headerSize.toLong() + dataBytes)
        dataBytes += length
    }

    /** Vrai si [length] octets supplémentaires dépasseraient la limite du format. */
    fun wouldOverflow(length: Int): Boolean = dataBytes + length > MAX_DATA_BYTES

    fun updateHeader() {
        val le = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        // Un octet de bourrage est dû si les données sont impaires : la taille RIFF en tient compte.
        val pad = dataBytes and 1L
        putU32(le, headerSize - 8 + dataBytes + pad); writeFully(le, 4)
        putU32(le, dataBytes); writeFully(le, dataSizeOffset.toLong())
        factOffset?.let { putU32(le, framesWritten); writeFully(le, it.toLong()) }
    }

    override fun close() {
        if (dataBytes and 1L == 1L) {
            writeFully(ByteBuffer.wrap(byteArrayOf(0)), headerSize.toLong() + dataBytes)
        }
        updateHeader()
        channel.force(true)
        channel.close()
    }

    private fun putU32(buffer: ByteBuffer, value: Long) {
        buffer.clear()
        buffer.putInt((value and 0xFFFF_FFFFL).toInt())
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
        val b = ByteBuffer.allocate(80 + extraSize).order(ByteOrder.LITTLE_ENDIAN)
        b.put(ascii("RIFF")); b.putInt(0); b.put(ascii("WAVE"))
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
            b.put(ascii(id)); b.putInt(body.size); b.put(body)
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

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    companion object {
        private const val FORMAT_PCM = 1
        private const val FORMAT_IEEE_FLOAT = 3
        private const val FORMAT_EXTENSIBLE = 0xFFFE
        private const val SPEAKER_MONO = 0x4          // FRONT_CENTER
        private const val SPEAKER_STEREO = 0x3        // FRONT_LEFT | FRONT_RIGHT

        // 12 (RIFF) + 8 (en-tête fmt) + 18 (corps fmt) + 8 (en-tête fact)
        private const val FLOAT_FACT_OFFSET = 46

        /** Taille maximale des données : le champ RIFF est un entier 32 bit non signé. */
        const val MAX_DATA_BYTES = 0xFFFF_FFFFL - 256

        // KSDATAFORMAT_SUBTYPE_PCM : 00000001-0000-0010-8000-00aa00389b71
        private val SUBFORMAT_PCM_GUID = byteArrayOf(
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
            0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71,
        )
    }
}
