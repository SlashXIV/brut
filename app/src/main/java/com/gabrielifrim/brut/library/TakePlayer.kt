package com.gabrielifrim.brut.library

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.gabrielifrim.brut.audio.WavInfo
import com.gabrielifrim.brut.audio.WavReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

data class PlayerState(
    val takeKey: String? = null,
    val playing: Boolean = false,
    val positionFrames: Long = 0,
    val totalFrames: Long = 0,
    val sampleRate: Int = 48_000,
    val loop: Boolean = false,
    /** Plage jouée (sélection en cours d'édition) ; par défaut, toute la prise. */
    val regionStart: Long = 0,
    val regionEnd: Long = -1,
) {
    val end: Long get() = if (regionEnd < 0) totalFrames else regionEnd

    val positionSeconds: Double get() = positionFrames.toDouble() / sampleRate
    val totalSeconds: Double get() = totalFrames.toDouble() / sampleRate
    val fraction: Float get() = if (totalFrames == 0L) 0f else (positionFrames.toFloat() / totalFrames).coerceIn(0f, 1f)
}

/**
 * Lecture d'une prise, sans passer par le lecteur multimédia d'Android dont la prise
 * en charge du 24 bit et du flottant varie d'un appareil à l'autre : on décode
 * nous-mêmes et on joue en flottant. Le son est rendu tel quel, sans aucun traitement.
 */
class TakePlayer(private val context: Context) {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var take: Take? = null
    private var info: WavInfo? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var seekRequest: Long = -1

    /** Prépare une prise sans la jouer ; la position revient au début. */
    fun load(t: Take) {
        if (take?.key == t.key) return
        stop()
        take = t
        info = t.info
        val i = t.info ?: return
        _state.value = PlayerState(takeKey = t.key, totalFrames = i.frames, sampleRate = i.sampleRate, loop = _state.value.loop)
    }

    fun toggle() = if (_state.value.playing) pause() else play()

    fun play() {
        val t = take ?: return
        val i = info ?: return
        if (running) return
        val s = _state.value
        val start = s.positionFrames.takeIf { it >= s.regionStart && it < s.end } ?: s.regionStart
        running = true
        _state.update { it.copy(playing = true, positionFrames = start) }
        thread = Thread({ loop(t, i, start) }, "brut-lecture").apply { start() }
    }

    fun pause() {
        running = false
        thread?.join(500)
        thread = null
        _state.update { it.copy(playing = false) }
    }

    fun stop() {
        pause()
        take = null
        info = null
        _state.value = PlayerState(loop = _state.value.loop)
    }

    fun seekTo(fraction: Float) {
        val i = info ?: return
        val frame = (fraction.coerceIn(0f, 1f) * i.frames).toLong()
        if (running) seekRequest = frame
        _state.update { it.copy(positionFrames = frame) }
    }

    fun setLoop(loop: Boolean) = _state.update { it.copy(loop = loop) }

    /** Restreint la lecture à [start, end[ ; la tête de lecture y est ramenée si besoin. */
    fun setRegion(start: Long, end: Long) = _state.update { s ->
        val pos = if (s.positionFrames in start until end) s.positionFrames else start
        if (running && pos != s.positionFrames) seekRequest = pos
        s.copy(regionStart = start, regionEnd = end, positionFrames = pos)
    }

    fun clearRegion() = _state.update { it.copy(regionStart = 0, regionEnd = -1) }

    private fun loop(t: Take, i: WavInfo, startFrame: Long) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        // Brut n'enregistre qu'en mono ou stéréo ; un fichier multicanal étranger n'est pas lu.
        if (i.channels !in 1..2) { finish(); return }
        val channel: SeekableByteChannel = openChannel(context, t) ?: run { finish(); return }
        val mask = if (i.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(i.sampleRate, mask, AudioFormat.ENCODING_PCM_FLOAT)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(i.sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(mask)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 2, i.sampleRate / 10 * i.channels * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: run { channel.close(); finish(); return }

        val block = 2048
        val samples = FloatArray(block * i.channels)
        val scratch = ByteBuffer.allocate(block * i.blockAlign)
        var frame = startFrame
        var base = startFrame // trame correspondant à la tête de lecture 0 de l'AudioTrack
        track.play()
        try {
            while (running) {
                val seek = seekRequest
                if (seek >= 0) {
                    seekRequest = -1
                    track.pause(); track.flush(); track.play()
                    frame = seek
                    base = seek - track.playbackHeadPosition
                }
                val s = _state.value
                val wanted = minOf(block.toLong(), s.end - frame).toInt()
                val n = if (wanted > 0) WavReader.readFrames(channel, i, frame, wanted, samples, scratch) else 0
                if (n == 0) {
                    if (s.loop) {
                        // Laisser finir le tampon serait plus juste, mais l'écart est inaudible en boucle.
                        track.pause(); track.flush(); track.play()
                        frame = s.regionStart; base = s.regionStart - track.playbackHeadPosition.toLong()
                        continue
                    }
                    break
                }
                track.write(samples, 0, n * i.channels, AudioTrack.WRITE_BLOCKING)
                frame += n
                val heard = (base + track.playbackHeadPosition).coerceIn(0, i.frames)
                _state.update { it.copy(positionFrames = heard) }
            }
            if (running) {
                // Fin naturelle : laisser sortir ce qui reste dans le tampon.
                track.stop()
                _state.update { it.copy(positionFrames = it.regionStart) }
            }
        } finally {
            runCatching { track.pause(); track.flush() }
            track.release()
            channel.close()
            finish()
        }
    }

    private fun finish() {
        running = false
        _state.update { it.copy(playing = false) }
    }
}
