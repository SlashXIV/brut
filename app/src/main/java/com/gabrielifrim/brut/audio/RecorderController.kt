package com.gabrielifrim.brut.audio

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import com.gabrielifrim.brut.device.InputDevice
import com.gabrielifrim.brut.device.InputDeviceRepository
import com.gabrielifrim.brut.device.InputKind
import com.gabrielifrim.brut.storage.RecordingFile
import com.gabrielifrim.brut.storage.RecordingStorage
import com.gabrielifrim.brut.storage.SavedSettings
import com.gabrielifrim.brut.storage.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

enum class Phase { STOPPED, MONITORING, RECORDING }

/** Affichage du pont de mesure. */
enum class MeterMode { PEAK, VU }

/** Message destiné à l'utilisateur ; le texte est résolu par l'interface (strings.xml). */
sealed interface UserMessage {
    data class Saved(val name: String) : UserMessage
    data class DeviceConnected(val name: String) : UserMessage
    data class DeviceLost(val name: String) : UserMessage
    data class RecordingStoppedDeviceLost(val name: String, val file: String) : UserMessage
    data object SizeLimit : UserMessage
    data object WriteFailed : UserMessage
    data object CaptureFailed : UserMessage
}

data class RecorderState(
    val phase: Phase = Phase.STOPPED,
    val format: AudioFormatSpec = AudioFormatSpec(),
    val devices: List<InputDevice> = emptyList(),
    val selectedDeviceId: Int? = null,
    val capture: CaptureInfo? = null,
    val levels: List<ChannelLevel> = List(2) { ChannelLevel() },
    val gainDb: List<Float> = listOf(0f, 0f),
    val gainLinked: Boolean = true,
    val meterMode: MeterMode = MeterMode.PEAK,
    val framesWritten: Long = 0,
    val fileName: String? = null,
    val freeBytes: Long = 0,
    val message: UserMessage? = null,
) {
    val selectedDevice: InputDevice? get() = devices.firstOrNull { it.id == selectedDeviceId }
    val isRecording: Boolean get() = phase == Phase.RECORDING
    val elapsedSeconds: Double get() = framesWritten.toDouble() / format.sampleRate

    /** Durée encore enregistrable au format courant, d'après l'espace libre. */
    val remainingSeconds: Long get() = freeBytes / format.bytesPerSecond

    /** Android a routé la capture ailleurs que sur l'entrée choisie. */
    val isRerouted: Boolean
        get() = capture?.routedDeviceId != null && selectedDeviceId != null && capture.routedDeviceId != selectedDeviceId
}

/**
 * Chef d'orchestre : choix de l'entrée, format, gain, écoute des niveaux et prises.
 * Vit aussi longtemps que l'application, pour que l'enregistrement survive à l'écran.
 */
class RecorderController(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val deviceRepository = InputDeviceRepository(context)
    private val storage = RecordingStorage(context)
    private val settings = SettingsStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private var engine: RecordingEngine? = null
    private var gain = GainStage(2)
    private var currentFile: RecordingFile? = null
    private var lastFreeCheck = 0L
    private var consoleVisible = false
    private var saveJob: Job? = null

    init {
        // Lecture synchrone : quelques octets, et la console doit s'ouvrir avec les bons réglages.
        val saved = runBlocking { settings.load() }
        val devices = deviceRepository.devices.value
        val device = devices.firstOrNull { keyOf(it) == saved.deviceKey } ?: deviceRepository.preferredDefault(devices)
        _state.update {
            it.copy(
                format = saved.format,
                levels = List(saved.format.channels) { ChannelLevel() },
                gainDb = saved.gainDb,
                gainLinked = saved.gainLinked,
                meterMode = saved.meterMode,
                devices = devices,
                selectedDeviceId = device?.id,
            )
        }
        scope.launch { deviceRepository.devices.collect(::onDevicesChanged) }
    }

    private fun keyOf(d: InputDevice) = "${d.kind}|${d.productName}|${d.address}"

    /** Enregistre les réglages, regroupés : un fader qu'on glisse n'écrit qu'une fois. */
    private fun persist() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            val s = _state.value
            withContext(Dispatchers.IO) {
                settings.save(SavedSettings(s.format, s.gainDb, s.gainLinked, s.selectedDevice?.let(::keyOf), s.meterMode))
            }
        }
    }

    // --- Cycle de vie de la console -------------------------------------------------

    fun startMonitoring() {
        consoleVisible = true
        if (engine != null) return
        openEngine()
    }

    /** Libère le micro quand la console n'est plus visible, sauf pendant une prise. */
    fun stopMonitoring() {
        consoleVisible = false
        if (_state.value.isRecording) return
        closeEngine()
        _state.update { it.copy(phase = Phase.STOPPED) }
    }

    // --- Réglages --------------------------------------------------------------------

    fun selectDevice(id: Int) {
        if (_state.value.isRecording) return
        _state.update { it.copy(selectedDeviceId = id) }
        persist()
        restartEngineIfOpen()
    }

    fun setFormat(format: AudioFormatSpec) {
        if (_state.value.isRecording || format == _state.value.format) return
        _state.update { it.copy(format = format, levels = List(format.channels) { ChannelLevel() }) }
        persist()
        restartEngineIfOpen()
    }

    fun setGain(channel: Int, db: Float) {
        val s = _state.value
        val value = db.coerceIn(GainStage.MIN_DB, GainStage.MAX_DB)
        val gains = s.gainDb.toMutableList()
        if (s.gainLinked || s.format.channels == 1) gains.indices.forEach { gains[it] = value } else gains[channel] = value
        gains.forEachIndexed { c, g -> if (c < s.format.channels) gain.setGainDb(c, g) }
        _state.update { it.copy(gainDb = gains) }
        persist()
    }

    fun setGainLinked(linked: Boolean) {
        _state.update { it.copy(gainLinked = linked) }
        persist()
        if (linked) setGain(0, _state.value.gainDb[0])
    }

    fun setMeterMode(mode: MeterMode) {
        _state.update { it.copy(meterMode = mode) }
        persist()
    }

    fun resetClip() {
        engine?.meter?.resetClip()
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    // --- Prise -----------------------------------------------------------------------

    fun startRecording(): Boolean {
        if (_state.value.isRecording) return true
        val e = engine ?: openEngine() ?: return false
        val file = try {
            storage.create()
        } catch (_: Exception) {
            _state.update { it.copy(message = UserMessage.WriteFailed) }
            return false
        }
        val writer = try {
            WavWriter(file.channel, e.format)
        } catch (_: IOException) {
            file.discard()
            _state.update { it.copy(message = UserMessage.WriteFailed) }
            return false
        }
        currentFile = file
        e.meter.resetClip()
        e.attachWriter(writer)
        _state.update { it.copy(phase = Phase.RECORDING, fileName = file.displayName, framesWritten = 0) }
        return true
    }

    fun stopRecording(message: UserMessage? = null) {
        val e = engine ?: return
        val writer = e.detachWriter()
        val file = currentFile
        currentFile = null
        var finalMessage = message
        if (file != null) {
            try {
                // Sans writer (erreur d'écriture), on publie tout de même la partie déjà
                // écrite : son en-tête a été mis à jour au fil de la prise.
                if (writer != null) writer.close() else file.channel.close()
                file.publish()
                if (finalMessage == null) finalMessage = UserMessage.Saved(file.displayName)
            } catch (_: Exception) {
                finalMessage = UserMessage.WriteFailed
            }
        }
        _state.update { it.copy(phase = Phase.MONITORING, message = finalMessage ?: it.message) }
        // Prise arrêtée depuis la notification, console fermée : on rend le micro.
        if (!consoleVisible) {
            closeEngine()
            _state.update { it.copy(phase = Phase.STOPPED) }
        }
    }

    // --- Interne ---------------------------------------------------------------------

    private fun openEngine(): RecordingEngine? {
        val s = _state.value
        gain = GainStage(s.format.channels).also { g ->
            for (c in 0 until s.format.channels) g.setGainDb(c, s.gainDb[c])
        }
        val e = RecordingEngine(audioManager, s.format, s.selectedDevice?.info, gain, engineListener)
        return try {
            e.start()
            engine = e
            _state.update { it.copy(phase = Phase.MONITORING, freeBytes = storage.freeBytes()) }
            e
        } catch (_: EngineStartException) {
            _state.update { it.copy(phase = Phase.STOPPED, message = UserMessage.CaptureFailed) }
            null
        } catch (_: SecurityException) {
            _state.update { it.copy(phase = Phase.STOPPED) }
            null
        }
    }

    private fun closeEngine() {
        engine?.stop()
        engine = null
    }

    private fun restartEngineIfOpen() {
        if (engine == null) return
        closeEngine()
        openEngine()
    }

    private fun onDevicesChanged(devices: List<InputDevice>) {
        val s = _state.value
        val previous = s.devices
        val selected = s.selectedDevice
        val added = devices.filter { d -> previous.none { it.id == d.id } }
        _state.update { it.copy(devices = devices) }

        val selectedGone = selected != null && devices.none { it.id == selected.id }
        if (selectedGone && s.isRecording) {
            // Ne jamais basculer en silence sur le micro interne au milieu d'une prise :
            // on arrête et on sauvegarde ce qui a été capté par la bonne entrée.
            val file = s.fileName.orEmpty()
            stopRecording(UserMessage.RecordingStoppedDeviceLost(label(selected), file))
        }
        if (_state.value.isRecording) return

        val newExternal = added.firstOrNull { it.kind == InputKind.USB || it.kind == InputKind.WIRED }
        when {
            // Brancher un micro, c'est vouloir s'en servir.
            newExternal != null && previous.isNotEmpty() -> {
                _state.update { it.copy(selectedDeviceId = newExternal.id, message = UserMessage.DeviceConnected(label(newExternal))) }
                restartEngineIfOpen()
            }
            selectedGone -> {
                val fallback = deviceRepository.preferredDefault(devices)
                _state.update {
                    it.copy(
                        selectedDeviceId = fallback?.id,
                        message = it.message ?: UserMessage.DeviceLost(label(selected)),
                    )
                }
                restartEngineIfOpen()
            }
            s.selectedDeviceId == null -> {
                _state.update { it.copy(selectedDeviceId = deviceRepository.preferredDefault(devices)?.id) }
                restartEngineIfOpen()
            }
        }
    }

    private fun label(d: InputDevice) = d.productName.ifBlank { d.kind.name }

    private val engineListener = object : RecordingEngine.Listener {
        override fun onLevels(levels: List<ChannelLevel>, framesWritten: Long) {
            val now = SystemClock.elapsedRealtime()
            val refreshFree = now - lastFreeCheck > 5_000
            if (refreshFree) lastFreeCheck = now
            val free = if (refreshFree) storage.freeBytes() else null
            _state.update {
                it.copy(
                    levels = levels,
                    framesWritten = if (it.isRecording) framesWritten else it.framesWritten,
                    freeBytes = free ?: it.freeBytes,
                )
            }
        }

        override fun onCaptureInfo(info: CaptureInfo) {
            _state.update { it.copy(capture = info) }
        }

        override fun onWriteError(error: IOException) {
            scope.launch { stopRecording(UserMessage.WriteFailed) }
        }

        override fun onSizeLimitReached() {
            scope.launch { if (_state.value.isRecording) stopRecording(UserMessage.SizeLimit) }
        }

        override fun onReadError(code: Int) {
            scope.launch {
                if (_state.value.isRecording) stopRecording(UserMessage.CaptureFailed)
                closeEngine()
                _state.update { it.copy(phase = Phase.STOPPED, message = UserMessage.CaptureFailed) }
            }
        }
    }
}
