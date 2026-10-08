package com.gabrielifrim.brut.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

/** Source de capture Android effectivement obtenue, de la plus brute à la moins brute. */
enum class CaptureSource(val androidSource: Int) {
    /** Aucun traitement : ni gain automatique, ni réduction de bruit, ni égalisation. */
    UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED),
    /** Sans contrôle automatique de gain d'après le CDD Android ; repli si UNPROCESSED est absent. */
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION),
    /** Dernier recours : le constructeur peut y appliquer des traitements. Signalé à l'utilisateur. */
    MIC(MediaRecorder.AudioSource.MIC),
}

/** Encodage demandé à Android pour la capture (indépendant du format du fichier). */
enum class CaptureEncoding(val androidEncoding: Int, val bytesPerSample: Int) {
    FLOAT(AudioFormat.ENCODING_PCM_FLOAT, 4),
    PCM_16(AudioFormat.ENCODING_PCM_16BIT, 2),
}

/** Ce qu'Android a réellement mis en place : affiché tel quel, sans embellir. */
data class CaptureInfo(
    val source: CaptureSource,
    val encoding: CaptureEncoding,
    val routedDeviceId: Int?,
    val routedDeviceName: String?,
    /** Format côté matériel (API 29+) : révèle un rééchantillonnage éventuel. */
    val deviceSampleRate: Int?,
    val deviceChannels: Int?,
    /** Effets encore actifs sur la capture, d'après Android (API 29+). */
    val activeEffects: List<String>,
    /** Vrai si Android a coupé le micro (autre application prioritaire, appel...). */
    val silenced: Boolean,
)

class EngineStartException(message: String) : Exception(message)

/** L'appareil déclare-t-il une capture sans aucun traitement ? */
fun supportsUnprocessed(audioManager: AudioManager): Boolean =
    audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

/**
 * Lit l'entrée audio sur un thread dédié, applique le gain utilisateur, mesure les
 * niveaux et, lorsqu'un [WavWriter] est attaché, écrit dans le fichier.
 *
 * Tourne en permanence pendant que la console est visible (pour régler les niveaux
 * AVANT d'enregistrer) ; l'enregistrement consiste seulement à attacher un fichier.
 */
class RecordingEngine(
    private val audioManager: AudioManager,
    val format: AudioFormatSpec,
    private val preferredDevice: AudioDeviceInfo?,
    /** Source choisie à la main ; null = automatique (la plus brute disponible). */
    private val forcedSource: CaptureSource?,
    val gain: GainStage,
    private val listener: Listener,
) {
    interface Listener {
        fun onLevels(levels: List<ChannelLevel>, framesWritten: Long)
        fun onCaptureInfo(info: CaptureInfo)
        fun onWriteError(error: IOException)
        fun onSizeLimitReached()
        fun onReadError(code: Int)
    }

    private lateinit var record: AudioRecord
    private lateinit var encoding: CaptureEncoding
    private lateinit var source: CaptureSource
    private val effects = mutableListOf<AudioEffect>()
    val meter = LevelMeter(format.sampleRate, format.channels)

    private val writerLock = ReentrantLock()
    private var writer: WavWriter? = null

    @Volatile private var running = false
    private var thread: Thread? = null
    private var lastInfo: CaptureInfo? = null

    /** Ouvre la capture en essayant les combinaisons de la plus brute à la plus tolérante. */
    @SuppressLint("MissingPermission")
    fun start() {
        val automatic = buildList {
            if (supportsUnprocessed(audioManager)) add(CaptureSource.UNPROCESSED)
            add(CaptureSource.VOICE_RECOGNITION)
            add(CaptureSource.MIC)
        }
        // Une source imposée passe en tête ; si l'appareil la refuse, la chaîne
        // automatique prend le relais plutôt que de laisser l'utilisateur sans son.
        val sources = forcedSource?.let { listOf(it) + (automatic - it) } ?: automatic
        // Fichier 16 bit : capture 16 bit d'abord, pour un chemin strictement bit-exact.
        val encodings = if (format.bitDepth == BitDepth.PCM_16) {
            listOf(CaptureEncoding.PCM_16, CaptureEncoding.FLOAT)
        } else {
            listOf(CaptureEncoding.FLOAT, CaptureEncoding.PCM_16)
        }
        val mask = if (format.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO

        for (s in sources) for (e in encodings) {
            val candidate = tryOpen(s, e, mask) ?: continue
            record = candidate
            source = s
            encoding = e
            disableEffects(candidate.audioSessionId)
            running = true
            thread = Thread(::loop, "brut-audio").apply { start() }
            return
        }
        throw EngineStartException("Aucune configuration de capture acceptée par cet appareil")
    }

    @SuppressLint("MissingPermission")
    private fun tryOpen(s: CaptureSource, e: CaptureEncoding, mask: Int): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(format.sampleRate, mask, e.androidEncoding)
        if (minBuffer <= 0) return null
        val wanted = format.sampleRate / 5 * format.channels * e.bytesPerSample // 200 ms
        val r = try {
            AudioRecord.Builder()
                .setAudioSource(s.androidSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(format.sampleRate)
                        .setEncoding(e.androidEncoding)
                        .setChannelMask(mask)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBuffer * 4, wanted))
                .build()
        } catch (_: Exception) {
            return null
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release(); return null
        }
        preferredDevice?.let { r.setPreferredDevice(it) }
        try {
            r.startRecording()
        } catch (_: IllegalStateException) {
            r.release(); return null
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            r.release(); return null
        }
        return r
    }

    /**
     * Certains constructeurs greffent des effets sur la session même sans qu'on les
     * demande : on les désactive explicitement quand Android les expose.
     */
    private fun disableEffects(session: Int) {
        if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session)?.let { it.enabled = false; effects += it }
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session)?.let { it.enabled = false; effects += it }
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session)?.let { it.enabled = false; effects += it }
    }

    fun attachWriter(w: WavWriter) = writerLock.withLock { writer = w }

    /** Détache le fichier et le finalise (en-tête définitif). */
    fun detachWriter(): WavWriter? = writerLock.withLock {
        val w = writer
        writer = null
        w
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
        effects.forEach { it.release() }
        effects.clear()
        runCatching { record.stop() }
        record.release()
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frames = format.sampleRate / 50 // blocs de 20 ms
        val samples = frames * format.channels
        val floats = FloatArray(samples)
        val shorts = if (encoding == CaptureEncoding.PCM_16) ShortArray(samples) else null
        val bytes = ByteArray(samples * format.bitDepth.bytesPerSample)
        val publishEvery = format.sampleRate / 30L
        val infoEvery = format.sampleRate.toLong()
        val headerEvery = format.sampleRate * 2L
        var sincePublish = 0L
        var sinceInfo = infoEvery
        var sinceHeader = 0L

        while (running) {
            val read = if (shorts != null) {
                record.read(shorts, 0, samples, AudioRecord.READ_BLOCKING).also { n ->
                    for (i in 0 until max(n, 0)) floats[i] = SampleConverter.pcm16ToFloat(shorts[i])
                }
            } else {
                record.read(floats, 0, samples, AudioRecord.READ_BLOCKING)
            }
            if (read < 0) {
                listener.onReadError(read)
                break
            }
            val readFrames = read / format.channels
            if (readFrames == 0) continue

            meter.inspectInput(floats, readFrames)
            val modified = gain.apply(floats, readFrames)
            meter.process(floats, readFrames)

            var written = 0L
            writerLock.withLock {
                val w = writer ?: return@withLock
                val n = readFrames * format.channels
                val length = if (shorts != null && !modified && format.bitDepth == BitDepth.PCM_16) {
                    SampleConverter.encodePcm16(shorts, n, bytes)
                } else {
                    SampleConverter.encode(floats, n, format.bitDepth, bytes)
                }
                if (w.wouldOverflow(length)) {
                    listener.onSizeLimitReached()
                    return@withLock
                }
                try {
                    w.write(bytes, length)
                    sinceHeader += readFrames
                    if (sinceHeader >= headerEvery) {
                        w.updateHeader()
                        sinceHeader = 0
                    }
                } catch (e: IOException) {
                    writer = null
                    listener.onWriteError(e)
                }
                written = w.framesWritten
            }

            sincePublish += readFrames
            if (sincePublish >= publishEvery) {
                sincePublish = 0
                listener.onLevels(meter.snapshot(), written)
            }
            sinceInfo += readFrames
            if (sinceInfo >= infoEvery) {
                sinceInfo = 0
                val info = captureInfo()
                if (info != lastInfo) {
                    lastInfo = info
                    listener.onCaptureInfo(info)
                }
            }
        }
    }

    private fun captureInfo(): CaptureInfo {
        val routed = record.routedDevice
        var rate: Int? = null
        var channels: Int? = null
        var activeEffects = emptyList<String>()
        var silenced = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            record.activeRecordingConfiguration?.let { conf ->
                rate = conf.format.sampleRate.takeIf { it > 0 }
                channels = conf.format.channelCount.takeIf { it > 0 }
                silenced = conf.isClientSilenced
                activeEffects = (conf.effects + conf.clientEffects).map { it.name }.distinct()
            }
        }
        return CaptureInfo(
            source = source,
            encoding = encoding,
            routedDeviceId = routed?.id,
            routedDeviceName = routed?.productName?.toString(),
            deviceSampleRate = rate,
            deviceChannels = channels,
            activeEffects = activeEffects,
            silenced = silenced,
        )
    }
}
