package com.gabrielifrim.brut.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
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
    /**
     * Source « vidéo » : jamais choisie d'office. Seul le test des voies peut la retenir, quand
     * c'est la seule qui garde séparées les voies d'une entrée sur un appareil donné.
     */
    CAMCORDER(MediaRecorder.AudioSource.CAMCORDER),
}

/** Encodage demandé à Android pour la capture (indépendant du format du fichier). */
enum class CaptureEncoding(val androidEncoding: Int, val bytesPerSample: Int) {
    FLOAT(AudioFormat.ENCODING_PCM_FLOAT, 4),
    PCM_16(AudioFormat.ENCODING_PCM_16BIT, 2),
}

/**
 * Façon d'ouvrir une entrée retenue par le test des voies : certains téléphones mélangent les
 * voies d'une entrée USB selon la source, l'encodage ou le type de masque demandés.
 */
data class CaptureRecipe(val source: CaptureSource, val encoding: CaptureEncoding, val indexMask: Boolean) {
    fun encode(): String = "${source.name},${encoding.name},${if (indexMask) "index" else "position"}"

    companion object {
        fun decode(text: String): CaptureRecipe? {
            val parts = text.split(',')
            if (parts.size != 3) return null
            val source = CaptureSource.entries.firstOrNull { it.name == parts[0] } ?: return null
            val encoding = CaptureEncoding.entries.firstOrNull { it.name == parts[1] } ?: return null
            return CaptureRecipe(source, encoding, parts[2] == "index")
        }

        /** Tout ce que le test essaie, du plus brut au plus traité. */
        val CANDIDATES: List<CaptureRecipe> = buildList {
            for (s in listOf(CaptureSource.UNPROCESSED, CaptureSource.VOICE_RECOGNITION, CaptureSource.CAMCORDER, CaptureSource.MIC)) {
                for (e in listOf(CaptureEncoding.FLOAT, CaptureEncoding.PCM_16)) {
                    for (index in listOf(false, true)) add(CaptureRecipe(s, e, index))
                }
            }
        }
    }
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
    /** Voies demandées par index (1, 2…) plutôt que par position (gauche, droite). */
    val indexMask: Boolean = false,
    /** Les voies 1 et 2 portent le même signal : Android a mélangé l'entrée. */
    val channelsIdentical: Boolean = false,
)

class EngineStartException(message: String) : Exception(message)

/** Première image LTC validée pendant la prise, et la trame du fichier où elle commence. */
data class LtcAnchor(val frame: LtcFrame, val fileFrame: Long)

/** Fichiers d'une prise : le principal et, si demandée, la piste de sécurité. */
data class TakeWriters(val main: WavWriter, val safety: WavWriter?)

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
    /** Secondes conservées avant l'appui sur REC (0 = pas de pré-enregistrement). */
    prerollSeconds: Int,
    private val listener: Listener,
    /** Combinaison retenue par le test des voies pour cette entrée ; essayée en premier. */
    private val recipe: CaptureRecipe? = null,
) {
    interface Listener {
        fun onLevels(levels: List<ChannelLevel>, framesWritten: Long, loudness: LoudnessReading, spectrum: FloatArray?)
        fun onCaptureInfo(info: CaptureInfo)
        fun onWriteError(error: IOException)
        fun onSizeLimitReached()
        fun onReadError(code: Int)
        /** Santé du moteur, une fois par seconde. */
        fun onStats(stats: EngineStats.Snapshot)
    }

    private lateinit var record: AudioRecord
    private lateinit var encoding: CaptureEncoding
    private lateinit var source: CaptureSource
    private var indexMask = false
    private val twins = ChannelTwinWatch(format.channels)
    private val effects = mutableListOf<AudioEffect>()
    val meter = LevelMeter(format.sampleRate, format.channels)
    /** Sonie mesurée sur les voies 1 et 2 : la somme de pistes indépendantes ne voudrait rien dire. */
    val loudness = LoudnessMeter(format.sampleRate, minOf(format.channels, 2))
    private var pairScratch = FloatArray(0)
    val spectrum = SpectrumAnalyzer(format.sampleRate, format.channels)
    private val preroll = PrerollBuffer(format.channels, prerollSeconds * format.sampleRate)

    private val writerLock = ReentrantLock()
    private var writer: WavWriter? = null
    /** Piste de sécurité : même signal, gain abaissé, dans un second fichier. */
    private var safety: WavWriter? = null
    private var safetyGain = 1f
    private var prerollPending = false

    /** Le spectre coûte une FFT par affichage : calculé seulement quand il est visible. */
    @Volatile var spectrumEnabled = false

    /** Écoute de contrôle au casque (latence de quelques dizaines de ms). */
    @Volatile var monitorEnabled = false
    private var monitor: AudioTrack? = null

    /** Voie qui porte un LTC (null = aucune) ; lu avant le gain, tel qu'il arrive. */
    @Volatile var ltcChannel: Int? = null
    private val ltc = LtcDecoder(format.sampleRate)
    private var ltcDecoding: Int? = null

    /** Échantillons captés depuis l'ouverture : l'horloge commune au LTC et au fichier. */
    @Volatile var position = 0L
        private set

    /** Dernière image LTC validée (affichage, et cadence mesurée sur la plus longue série). */
    @Volatile var latestLtc: LtcFrame? = null
        private set

    /** Ancrage LTC de la prise en cours ; conservé après [detachWriter] pour le calage. */
    @Volatile var takeAnchor: LtcAnchor? = null
        private set
    @Volatile private var takeActive = false
    // Échantillon absolu correspondant à la trame 0 du fichier (pré-enregistrement compris).
    private var fileBase = Long.MIN_VALUE

    private lateinit var stats: EngineStats
    private val stamp = AudioTimestamp()
    /** Posé par [attachWriter] (autre thread), consommé par la boucle : les pertes repartent de zéro. */
    @Volatile private var statsMarkPending = false

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
        val attempts = buildList {
            if (recipe != null && format.channels <= 2) add(recipe)
            for (s in sources) for (e in encodings) add(CaptureRecipe(s, e, indexMask = false))
        }
        for (a in attempts) {
            val candidate = tryOpen(a.source, a.encoding, a.indexMask) ?: continue
            record = candidate
            source = a.source
            encoding = a.encoding
            indexMask = a.indexMask
            disableEffects(candidate.audioSessionId)
            stats = EngineStats(format.sampleRate, candidate.bufferSizeInFrames)
            running = true
            thread = Thread(::loop, "brut-audio").apply { start() }
            return
        }
        throw EngineStartException("Aucune configuration de capture acceptée par cet appareil")
    }

    @SuppressLint("MissingPermission")
    private fun tryOpen(s: CaptureSource, e: CaptureEncoding, index: Boolean = false): AudioRecord? {
        // Au-delà de 2 voies, pas de positions (gauche, droite…) mais des index : voie 1 à N
        // telles que l'interface USB les présente, sans aucun mélange.
        val multi = format.channels > 2 || index
        val positionMask = if (format.channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val stereoMin = AudioRecord.getMinBufferSize(format.sampleRate, positionMask, e.androidEncoding)
        if (stereoMin <= 0) return null
        val minBuffer = if (format.channels > 2) stereoMin / 2 * format.channels else stereoMin
        val wanted = format.sampleRate / 5 * format.channels * e.bytesPerSample // 200 ms
        val r = try {
            AudioRecord.Builder()
                .setAudioSource(s.androidSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(format.sampleRate)
                        .setEncoding(e.androidEncoding)
                        .apply { if (multi) setChannelIndexMask((1 shl format.channels) - 1) else setChannelMask(positionMask) }
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

    /**
     * Branche les fichiers de la prise. Avec [withPreroll], le contenu du tampon de
     * pré-enregistrement est écrit en tête au prochain bloc.
     */
    fun attachWriter(w: WavWriter, safetyWriter: WavWriter? = null, safetyDb: Float = 0f, withPreroll: Boolean = false) =
        writerLock.withLock {
            writer = w
            safety = safetyWriter
            safetyGain = LevelMeter.dbToLinear(safetyDb)
            prerollPending = withPreroll
            if (!withPreroll) preroll.clear()
            takeAnchor = null
            fileBase = Long.MIN_VALUE
            takeActive = true
            statsMarkPending = true
        }

    /** Trames actuellement disponibles dans le tampon de pré-enregistrement. */
    val prerollFrames: Int get() = writerLock.withLock { preroll.frames }

    /** Pose un repère dans les fichiers en cours, à la position actuelle de la prise. */
    fun addMarker(label: String) = writerLock.withLock {
        writer?.addMarker(label)
        safety?.addMarker(label)
    }

    /** Détache les fichiers de la prise (à finaliser par l'appelant). */
    fun detachWriter(): TakeWriters? = writerLock.withLock {
        val w = writer ?: return@withLock null
        val pair = TakeWriters(w, safety)
        writer = null
        safety = null
        takeActive = false
        pair
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
        effects.forEach { it.release() }
        effects.clear()
        monitor?.let { runCatching { it.stop() }; it.release() }
        monitor = null
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
        val scratch = FloatArray(samples)
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
            val started = System.nanoTime()

            decodeLtc(floats, readFrames)
            meter.inspectInput(floats, readFrames)
            val modified = gain.apply(floats, readFrames)
            meter.process(floats, readFrames)
            loudness.process(firstPair(floats, readFrames), readFrames)
            if (spectrumEnabled) spectrum.push(floats, readFrames)
            twins.add(floats, readFrames)
            feedMonitor(floats, readFrames)

            var written = 0L
            var writeNanos = -1L
            writerLock.withLock {
                val w = writer
                if (w == null) {
                    preroll.push(floats, readFrames)
                    return@withLock
                }
                val writeStarted = System.nanoTime()
                if (prerollPending) {
                    prerollPending = false
                    preroll.drain(scratch) { chunk, n -> writeBlock(w, chunk, n, null, false, bytes) }
                }
                // La trame du fichier où commence ce bloc relie l'horloge de capture au fichier.
                if (fileBase == Long.MIN_VALUE) fileBase = position - w.framesWritten
                writeBlock(w, floats, readFrames, shorts, modified, bytes)
                sinceHeader += readFrames
                if (sinceHeader >= headerEvery) {
                    runCatching { w.updateHeader(); safety?.updateHeader() }
                    sinceHeader = 0
                }
                written = w.framesWritten
                writeNanos = System.nanoTime() - writeStarted
            }

            position += readFrames
            sincePublish += readFrames
            if (sincePublish >= publishEvery) {
                sincePublish = 0
                listener.onLevels(meter.snapshot(), written, loudness.reading(), if (spectrumEnabled) spectrum.compute() else null)
            }
            sinceInfo += readFrames
            if (sinceInfo >= infoEvery) {
                sinceInfo = 0
                twins.conclude()
                val info = captureInfo()
                if (info != lastInfo) {
                    lastInfo = info
                    listener.onCaptureInfo(info)
                }
                if (statsMarkPending) {
                    statsMarkPending = false
                    stats.markTake()
                }
                stats.timestamp(hardwareFrames(), position)
                stats.cpu(Process.getElapsedCpuTime(), System.nanoTime(), Runtime.getRuntime().availableProcessors())
                listener.onStats(stats.snapshot())
            }
            stats.block(readFrames, System.nanoTime() - started, writeNanos)
        }
    }

    /** Trames captées par le matériel à l'instant présent, d'après l'horodatage d'Android. */
    private fun hardwareFrames(): Long? {
        if (record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) return null
        // La position horodatée, prolongée jusqu'à maintenant au rythme de l'échantillonnage.
        return stamp.framePosition + (System.nanoTime() - stamp.nanoTime) * format.sampleRate / 1_000_000_000L
    }

    private var safetyScratch = FloatArray(0)

    private fun decodeLtc(floats: FloatArray, frames: Int) {
        val ch = ltcChannel?.coerceIn(0, format.channels - 1)
        if (ch != ltcDecoding) {
            ltc.reset()
            ltcDecoding = ch
            latestLtc = null
        }
        if (ch == null) return
        ltc.process(floats, frames, format.channels, ch, position) { frame, validated ->
            if (!validated) return@process
            latestLtc = frame
            val base = fileBase
            // La première image validée de la prise sert d'ancrage : plus elle est proche du
            // début, moins la dérive entre l'horloge du téléphone et celle du LTC compte.
            if (takeActive && takeAnchor == null && base != Long.MIN_VALUE && frame.startSample >= base) {
                takeAnchor = LtcAnchor(frame, frame.startSample - base)
            }
        }
    }

    /** Écrit un bloc dans le fichier principal et, le cas échéant, dans la piste de sécurité. */
    private fun writeBlock(w: WavWriter, floats: FloatArray, frames: Int, shorts: ShortArray?, modified: Boolean, bytes: ByteArray) {
        val n = frames * format.channels
        val length = if (shorts != null && !modified && format.bitDepth == BitDepth.PCM_16) {
            SampleConverter.encodePcm16(shorts, n, bytes)
        } else {
            SampleConverter.encode(floats, n, format.bitDepth, bytes)
        }
        if (w.wouldOverflow(length)) {
            listener.onSizeLimitReached()
            return
        }
        try {
            w.write(bytes, length)
            safety?.let { s ->
                if (safetyScratch.size < n) safetyScratch = FloatArray(n)
                for (i in 0 until n) safetyScratch[i] = floats[i] * safetyGain
                s.write(bytes, SampleConverter.encode(safetyScratch, n, format.bitDepth, bytes))
            }
        } catch (e: IOException) {
            writer = null
            safety = null
            listener.onWriteError(e)
        }
    }

    /**
     * Renvoie le signal (après gain) vers le casque. Écriture non bloquante : si la
     * sortie prend du retard, des échantillons d'écoute sont perdus, jamais ceux du fichier.
     */
    private fun feedMonitor(floats: FloatArray, frames: Int) {
        if (!monitorEnabled) {
            monitor?.let { runCatching { it.pause(); it.flush() } }
            return
        }
        val track = monitor ?: openMonitor()?.also { monitor = it } ?: return
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
        // En multipiste, le casque reçoit les voies 1 et 2 ; le fichier, lui, garde tout.
        val out = firstPair(floats, frames)
        track.write(out, 0, frames * minOf(format.channels, 2), AudioTrack.WRITE_NON_BLOCKING)
    }

    /** Les voies 1 et 2 d'un bloc multipiste (le bloc lui-même en mono ou stéréo). */
    private fun firstPair(floats: FloatArray, frames: Int): FloatArray {
        if (format.channels <= 2) return floats
        if (pairScratch.size < frames * 2) pairScratch = FloatArray(frames * 2)
        for (f in 0 until frames) {
            pairScratch[f * 2] = floats[f * format.channels]
            pairScratch[f * 2 + 1] = floats[f * format.channels + 1]
        }
        return pairScratch
    }

    private fun openMonitor(): AudioTrack? = runCatching {
        val mask = if (format.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(format.sampleRate, mask, AudioFormat.ENCODING_PCM_FLOAT)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(format.sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(mask)
                    .build(),
            )
            .setBufferSizeInBytes(min * 2)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }.getOrNull()

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
            indexMask = indexMask,
            channelsIdentical = twins.identical,
        )
    }
}
