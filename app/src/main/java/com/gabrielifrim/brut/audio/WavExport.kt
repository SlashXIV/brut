package com.gabrielifrim.brut.audio

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong

/**
 * Voies gardées à l'export. Extraire une voie n'est pas un mélange : ses échantillons
 * sont recopiés tels quels, l'autre est simplement laissée de côté.
 */
enum class ChannelPick { ALL, LEFT, RIGHT }

/** Ce qu'on exporte d'une prise : l'intervalle [startFrame, endFrame[ et la forme du fichier. */
data class ExportSpec(
    val startFrame: Long,
    val endFrame: Long,
    /** null = résolution d'origine (copie à l'octet près). */
    val bitDepth: BitDepth? = null,
    val channels: ChannelPick = ChannelPick.ALL,
) {
    val frames: Long get() = endFrame - startFrame
}

/** [clippedSamples] : échantillons écrêtés par la conversion (flottant au-delà de 0 dBFS vers entier). */
data class ExportResult(val frames: Long, val clippedSamples: Long)

/**
 * Édition légère, toujours non destructive : on lit la prise d'origine et on écrit un
 * nouveau fichier. À résolution d'origine, les octets de l'intervalle sont recopiés sans
 * être décodés : l'extrait est identique bit à bit à la portion de l'original.
 *
 * Une conversion de résolution est un choix explicite. Elle se fait sans tramage, comme
 * l'enregistrement : vers une résolution plus haute elle est exacte, vers 16 bit elle
 * arrondit. Il n'y a volontairement pas de changement de fréquence : ce serait un
 * rééchantillonnage, donc un traitement du son.
 */
object WavExport {

    private const val BLOCK_FRAMES = 8192

    /** Résolution du fichier source, si Brut sait l'écrire telle quelle. */
    fun sourceDepth(info: WavInfo): BitDepth? = when {
        info.isFloat -> BitDepth.FLOAT_32
        info.bitsPerSample == 16 -> BitDepth.PCM_16
        info.bitsPerSample == 24 -> BitDepth.PCM_24
        else -> null // 32 bit entier : lu, mais pas écrit par Brut
    }

    fun targetFormat(info: WavInfo, spec: ExportSpec): AudioFormatSpec {
        val depth = spec.bitDepth ?: sourceDepth(info) ?: throw IOException("Résolution d'origine impossible à recopier")
        val channels = if (spec.channels == ChannelPick.ALL) info.channels else 1
        return AudioFormatSpec(info.sampleRate, depth, channels)
    }

    /** Vrai si l'export recopie les octets sans les décoder. */
    fun isBitExact(info: WavInfo, spec: ExportSpec): Boolean =
        spec.bitDepth == null || spec.bitDepth == sourceDepth(info)

    /**
     * Intervalles délimités par les repères compris dans [start, end[ : un repère ouvre
     * un nouveau morceau. Les morceaux vides (repère en tête, repères confondus) sont écartés.
     */
    fun segments(info: WavInfo, start: Long, end: Long): List<LongRange> {
        val cuts = (listOf(start) + info.markers.map { it.frame }.filter { it in (start + 1) until end } + end).distinct().sorted()
        return cuts.zipWithNext { a, b -> a until b }.filter { !it.isEmpty() }
    }

    /** Repères de l'intervalle, ramenés au début de l'extrait. */
    fun markersIn(info: WavInfo, start: Long, end: Long): List<Marker> =
        info.markers.filter { it.frame in start until end }.map { it.copy(frame = it.frame - start) }

    /**
     * Métadonnées bext de l'extrait : l'horodatage avance du décalage de l'extrait (la
     * synchronisation avec une caméra reste juste) et l'historique de codage garde la
     * chaîne d'origine, complétée de l'étape d'export.
     */
    fun derivedBext(source: Bext, info: WavInfo, spec: ExportSpec, target: AudioFormatSpec, name: String, tool: String, description: String): Bext {
        val offsetNanos = (spec.startFrame.toDouble() * 1_000_000_000.0 / info.sampleRate).roundToLong()
        val history = source.codingHistory.let { if (it.isEmpty() || it.endsWith("\r\n")) it else "$it\r\n" }
        return source.copy(
            description = description,
            originatorReference = name,
            date = source.date.plusNanos(offsetNanos).withNano(0),
            timeReference = source.timeReference + spec.startFrame,
            codingHistory = history + Bext.codingHistoryFor(target, tool),
        )
    }

    /**
     * Écrit l'extrait décrit par [spec] dans [output]. [isActive] est consulté entre deux
     * blocs : s'il devient faux, l'export s'interrompt (le fichier est alors à jeter).
     */
    fun export(
        input: SeekableByteChannel,
        info: WavInfo,
        spec: ExportSpec,
        output: FileChannel,
        extraChunks: List<Pair<String, ByteArray>> = emptyList(),
        markers: List<Marker> = emptyList(),
        onProgress: (Float) -> Unit = {},
        isActive: () -> Boolean = { true },
    ): ExportResult {
        require(spec.startFrame in 0..spec.endFrame && spec.endFrame <= info.frames) { "Intervalle hors de la prise" }
        require(spec.channels == ChannelPick.ALL || info.channels == 2) { "Choix de voie réservé à la stéréo" }
        val target = targetFormat(info, spec)
        val exact = isBitExact(info, spec)
        val pick = when (spec.channels) {
            ChannelPick.ALL -> -1
            ChannelPick.LEFT -> 0
            ChannelPick.RIGHT -> 1
        }
        val raw = ByteBuffer.allocate(BLOCK_FRAMES * info.blockAlign)
        val samples = FloatArray(BLOCK_FRAMES * info.channels)
        val picked = FloatArray(BLOCK_FRAMES * target.channels)
        val out = ByteArray(BLOCK_FRAMES * target.bytesPerFrame)
        var clipped = 0L
        var frame = spec.startFrame
        var blocks = 0
        WavWriter(output, target, extraChunks).use { writer ->
            markers.forEach { writer.addMarker(it.label, it.frame) }
            while (frame < spec.endFrame) {
                if (!isActive()) throw CancellationException("Export annulé")
                val n = minOf(BLOCK_FRAMES.toLong(), spec.endFrame - frame).toInt()
                if (exact) {
                    val read = readRaw(input, info, frame, n, raw)
                    if (read == 0) break
                    val src = raw.array()
                    val length = if (pick < 0) {
                        System.arraycopy(src, 0, out, 0, read * info.blockAlign)
                        read * info.blockAlign
                    } else {
                        val bps = info.bytesPerSample
                        for (f in 0 until read) System.arraycopy(src, f * info.blockAlign + pick * bps, out, f * bps, bps)
                        read * bps
                    }
                    writer.write(out, length)
                    frame += read
                } else {
                    val read = WavReader.readFrames(input, info, frame, n, samples, raw)
                    if (read == 0) break
                    val count = if (pick < 0) {
                        samples.copyInto(picked, 0, 0, read * info.channels)
                        read * info.channels
                    } else {
                        for (f in 0 until read) picked[f] = samples[f * 2 + pick]
                        read
                    }
                    clipped += countClipped(picked, count, target.bitDepth)
                    writer.write(out, SampleConverter.encode(picked, count, target.bitDepth, out))
                    frame += read
                }
                // Comme à l'enregistrement : un export interrompu reste lisible.
                if (++blocks % 64 == 0) writer.updateHeader()
                onProgress(((frame - spec.startFrame).toFloat() / spec.frames.coerceAtLeast(1)).coerceIn(0f, 1f))
            }
        }
        return ExportResult(frame - spec.startFrame, clipped)
    }

    /** Échantillons que le convertisseur ramènera à la pleine échelle entière. */
    fun countClipped(samples: FloatArray, count: Int, depth: BitDepth): Long {
        val scale = when (depth) {
            BitDepth.PCM_16 -> 32768f
            BitDepth.PCM_24 -> 8388608f
            BitDepth.FLOAT_32 -> return 0 // le flottant garde les dépassements
        }
        // Même arrondi que SampleConverter : au-delà de (max + 0,5) LSB, la valeur est écrêtée.
        val high = (scale - 0.5f) / scale
        val low = -(scale + 0.5f) / scale
        var n = 0L
        for (i in 0 until count) {
            val s = samples[i]
            if (s >= high || s < low) n++
        }
        return n
    }

    private fun readRaw(channel: SeekableByteChannel, info: WavInfo, startFrame: Long, frames: Int, buf: ByteBuffer): Int {
        val wanted = minOf(frames.toLong(), info.frames - startFrame).toInt()
        if (wanted <= 0) return 0
        buf.clear().limit(wanted * info.blockAlign)
        var p = info.dataOffset + startFrame * info.blockAlign
        while (buf.hasRemaining()) {
            val r = channel.position(p).read(buf)
            if (r <= 0) break
            p += r
        }
        return buf.position() / info.blockAlign
    }
}
