package com.gabrielifrim.brut.library

import android.content.Context
import android.os.ParcelFileDescriptor
import com.gabrielifrim.brut.audio.WavInfo
import com.gabrielifrim.brut.audio.WavReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import kotlin.math.abs
import kotlin.math.max

/**
 * Enveloppe d'une prise : la crête de chaque tranche, tous canaux confondus.
 * Valeurs linéaires, 1.0 = pleine échelle (un fichier flottant peut dépasser).
 */
class Waveform(val peaks: FloatArray)

object WaveformMath {
    const val BUCKETS = 480

    /** Calcule l'enveloppe en lisant tout le fichier par blocs ; annulable entre deux blocs. */
    suspend fun compute(channel: SeekableByteChannel, info: WavInfo, buckets: Int = BUCKETS): Waveform {
        val peaks = FloatArray(buckets)
        val total = info.frames
        if (total == 0L) return Waveform(peaks)
        val block = 16_384
        val samples = FloatArray(block * info.channels)
        val scratch = ByteBuffer.allocate(block * info.blockAlign)
        var frame = 0L
        val ctx = kotlin.coroutines.coroutineContext
        while (frame < total) {
            ctx.ensureActive()
            val n = WavReader.readFrames(channel, info, frame, block, samples, scratch)
            if (n == 0) break
            for (f in 0 until n) {
                val bucket = ((frame + f) * buckets / total).toInt().coerceAtMost(buckets - 1)
                var m = peaks[bucket]
                val base = f * info.channels
                for (c in 0 until info.channels) m = max(m, abs(samples[base + c]))
                peaks[bucket] = m
            }
            frame += n
        }
        return Waveform(peaks)
    }
}

/**
 * Les enveloppes sont mises en cache sur disque : une prise d'une heure en 96 kHz se
 * lit en plusieurs secondes, on ne le fait qu'une fois.
 */
class WaveformCache(private val context: Context) {

    private val memory = HashMap<String, Waveform>()

    suspend fun get(take: Take): Waveform? = withContext(Dispatchers.IO) {
        val info = take.info ?: return@withContext null
        val key = "${take.uri.hashCode()}_${take.sizeBytes}"
        memory[key]?.let { return@withContext it }
        val file = File(context.cacheDir, "formes/$key.bin")
        val cached = runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                Waveform(FloatArray(input.readInt()) { input.readFloat() })
            }
        }.getOrNull()
        val wave = cached ?: open(take)?.use { WaveformMath.compute(it, info) }?.also { w ->
            file.parentFile?.mkdirs()
            runCatching {
                DataOutputStream(file.outputStream().buffered()).use { out ->
                    out.writeInt(w.peaks.size)
                    w.peaks.forEach(out::writeFloat)
                }
            }
        }
        wave?.also { memory[key] = it }
    }

    private fun open(take: Take): SeekableByteChannel? = openChannel(context, take)
}

/** Canal de lecture positionnable sur une prise ; le fermer libère aussi le descripteur. */
fun openChannel(context: Context, take: Take): SeekableByteChannel? = runCatching {
    take.file?.let { FileInputStream(it).channel }
        ?: ParcelFileDescriptor.AutoCloseInputStream(context.contentResolver.openFileDescriptor(take.uri, "r")!!).channel
}.getOrNull()
