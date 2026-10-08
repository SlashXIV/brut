package com.gabrielifrim.brut.audio

/**
 * Tampon circulaire des dernières secondes captées, avant l'appui sur REC. Quand la
 * prise démarre, son contenu est écrit en tête du fichier : l'attaque d'un son qui a
 * surpris l'opérateur n'est pas perdue.
 *
 * Il contient le signal après le gain, exactement ce qui aurait été écrit.
 */
class PrerollBuffer(private val channels: Int, capacityFrames: Int) {

    private val data = FloatArray(maxOf(capacityFrames, 0) * channels)
    private var start = 0
    private var size = 0 // en échantillons

    val frames: Int get() = size / channels

    fun push(interleaved: FloatArray, frames: Int) {
        if (data.isEmpty()) return
        val n = frames * channels
        for (i in 0 until n) {
            val end = (start + size) % data.size
            data[end] = interleaved[i]
            if (size < data.size) size++ else start = (start + 1) % data.size
        }
    }

    /** Vide le tampon dans l'ordre chronologique, par morceaux d'au plus [chunk] échantillons. */
    fun drain(chunk: FloatArray, sink: (FloatArray, Int) -> Unit) {
        while (size > 0) {
            val n = minOf(size, chunk.size - chunk.size % channels)
            for (i in 0 until n) chunk[i] = data[(start + i) % data.size]
            start = (start + n) % data.size
            size -= n
            sink(chunk, n / channels)
        }
        start = 0
    }

    fun clear() {
        start = 0
        size = 0
    }
}
