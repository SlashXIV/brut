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
import com.gabrielifrim.brut.R
import java.time.LocalDateTime

/** ARMED : REC pressé avec le déclenchement sur seuil, la prise attend le signal. */
enum class Phase { STOPPED, MONITORING, ARMED, RECORDING }

/** Affichage du pont de mesure. */
enum class MeterMode { PEAK, VU, LUFS, SPECTRUM }

/** Message destiné à l'utilisateur ; le texte est résolu par l'interface (strings.xml). */
sealed interface UserMessage {
    data class Saved(val name: String) : UserMessage
    data class DeviceConnected(val name: String) : UserMessage
    data class DeviceLost(val name: String) : UserMessage
    data class RecordingStoppedDeviceLost(val name: String, val file: String) : UserMessage
    data object SizeLimit : UserMessage
    data object WriteFailed : UserMessage
    data object CaptureFailed : UserMessage
    data class Recovered(val name: String, val seconds: Double) : UserMessage
    data object CaptureResumed : UserMessage
    data object StoppedLowBattery : UserMessage
    data object StoppedNoSpace : UserMessage
    data class MarkerAdded(val number: Int) : UserMessage
    data object MonitorNeedsHeadphones : UserMessage
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
    /** Mode de capture imposé ; null = automatique. */
    val captureMode: CaptureSource? = null,
    val unprocessedSupported: Boolean = false,
    val framesWritten: Long = 0,
    val fileName: String? = null,
    val freeBytes: Long = 0,
    /** Dossier de destination, tel qu'affiché (« Musique/Brut », « Carte SD/Concerts »…). */
    val folderLabel: String = "",
    val batteryPercent: Int = 100,
    val charging: Boolean = true,
    val options: TakeOptions = TakeOptions(),
    val loudness: LoudnessReading = LoudnessReading(),
    /** Niveaux du spectre par bande (null tant que l'affichage spectre n'est pas visible). */
    val spectrum: FloatArray? = null,
    val spectrumCenters: FloatArray = FloatArray(0),
    /** Un casque (filaire, USB ou Bluetooth) est branché : l'écoute de contrôle est possible. */
    val headphones: Boolean = false,
    val markerCount: Int = 0,
    val message: UserMessage? = null,
) {
    val isArmed: Boolean get() = phase == Phase.ARMED

    /** Prise en cours ou armée : le micro doit rester ouvert, même écran éteint. */
    val isBusy: Boolean get() = phase == Phase.RECORDING || phase == Phase.ARMED

    /** Batterie à surveiller pendant une prise. */
    val lowBattery: Boolean get() = isRecording && !charging && batteryPercent <= LOW_BATTERY

    /** Moins de deux minutes d'espace au format courant. */
    val lowSpace: Boolean get() = isRecording && remainingSeconds < LOW_SPACE_SECONDS

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
const val LOW_BATTERY = 15
const val LOW_SPACE_SECONDS = 120L

/** En dessous : on arrête proprement plutôt que de laisser le téléphone couper en pleine écriture. */
private const val CRITICAL_BATTERY = 3
private const val CRITICAL_SPACE_SECONDS = 5L

class RecorderController(private val context: Context) {

    private val appVersion: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

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
    private var safetyFile: RecordingFile? = null
    private var lastFreeCheck = 0L
    private var consoleVisible = false
    private var saveJob: Job? = null

    /** Suit le branchement d'un casque, pour l'écoute de contrôle. */
    private val outputCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) = refreshHeadphones()
        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>) = refreshHeadphones()
    }

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
                captureMode = saved.captureMode,
                options = saved.options,
                unprocessedSupported = supportsUnprocessed(audioManager),
                devices = devices,
                selectedDeviceId = device?.id,
            )
        }
        scope.launch { deviceRepository.devices.collect(::onDevicesChanged) }
        refreshBattery()
        _state.update { it.copy(folderLabel = storage.folderLabel(), freeBytes = storage.freeBytes()) }
        // Une prise interrompue par un arrêt brutal est réparée et publiée dès le démarrage.
        scope.launch {
            val recovered = withContext(Dispatchers.IO) { storage.recoverInterrupted() }
            recovered.firstOrNull()?.let { r -> _state.update { it.copy(message = UserMessage.Recovered(r.name, r.seconds)) } }
        }
        audioManager.registerAudioDeviceCallback(outputCallback, android.os.Handler(android.os.Looper.getMainLooper()))
        refreshHeadphones()
    }

    /** Sorties sur lesquelles l'écoute de contrôle ne risque pas de repartir dans le micro. */
    private fun refreshHeadphones() {
        val types = setOf(
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
        val present = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
        _state.update { it.copy(headphones = present) }
        applyMonitor()
    }

    /** L'écoute ne passe jamais par le haut-parleur : elle reviendrait dans le micro (larsen). */
    private fun applyMonitor() {
        val s = _state.value
        engine?.monitorEnabled = s.options.monitor && s.headphones
    }

    /** Change le dossier de destination (null = dossier par défaut). Sans effet pendant une prise. */
    fun setCustomFolder(tree: android.net.Uri?) {
        if (_state.value.isRecording) return
        runCatching { storage.setCustomFolder(tree) }
        _state.update { it.copy(folderLabel = storage.folderLabel(), freeBytes = storage.freeBytes()) }
    }

    val customFolder: android.net.Uri? get() = storage.customFolder

    private fun refreshBattery() {
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
        val plugged = intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level >= 0 && scale > 0) _state.update { it.copy(batteryPercent = level * 100 / scale, charging = plugged) }
    }

    /** Arrête et sauvegarde avant que la batterie ou le stockage ne lâchent. */
    private fun guardResources() {
        val s = _state.value
        if (!s.isRecording) return
        when {
            !s.charging && s.batteryPercent <= CRITICAL_BATTERY -> stopRecording(UserMessage.StoppedLowBattery)
            s.freeBytes > 0 && s.remainingSeconds < CRITICAL_SPACE_SECONDS -> stopRecording(UserMessage.StoppedNoSpace)
        }
    }

    private fun keyOf(d: InputDevice) = "${d.kind}|${d.productName}|${d.address}"

    /** Enregistre les réglages, regroupés : un fader qu'on glisse n'écrit qu'une fois. */
    private fun persist() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            val s = _state.value
            withContext(Dispatchers.IO) {
                settings.save(SavedSettings(s.format, s.gainDb, s.gainLinked, s.selectedDevice?.let(::keyOf), s.meterMode, s.captureMode, s.options))
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
        if (_state.value.isBusy) return
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

    fun setCaptureMode(mode: CaptureSource?) {
        if (_state.value.isRecording || mode == _state.value.captureMode) return
        _state.update { it.copy(captureMode = mode) }
        persist()
        restartEngineIfOpen()
    }

    fun setMeterMode(mode: MeterMode) {
        _state.update { it.copy(meterMode = mode) }
        engine?.spectrumEnabled = mode == MeterMode.SPECTRUM
        persist()
    }

    fun setOptions(options: TakeOptions) {
        val s = _state.value
        if (s.isBusy || options == s.options) return
        if (options.monitor && !s.options.monitor && !s.headphones) {
            _state.update { it.copy(message = UserMessage.MonitorNeedsHeadphones) }
        }
        _state.update { it.copy(options = options) }
        persist()
        applyMonitor()
        // La taille du tampon de pré-enregistrement est fixée à l'ouverture de la capture.
        if (options.effectivePrerollSeconds != s.options.effectivePrerollSeconds) restartEngineIfOpen()
    }

    /** Format et outils d'un préréglage, en une seule réouverture de la capture. */
    fun applyPreset(preset: Preset) {
        val s = _state.value
        if (s.isBusy) return
        val options = preset.optionsKeeping(s.options)
        if (preset.format == s.format && options == s.options) return
        _state.update { it.copy(format = preset.format, levels = List(preset.format.channels) { ChannelLevel() }, options = options) }
        persist()
        applyMonitor()
        restartEngineIfOpen()
    }

    /** Remet à zéro la sonie intégrée et la crête vraie maximale. */
    fun resetLoudness() {
        engine?.loudness?.reset()
    }

    /** Pose un repère numéroté à l'instant présent de la prise. */
    fun addMarker() {
        if (!_state.value.isRecording) return
        val n = _state.value.markerCount + 1
        engine?.addMarker(context.getString(R.string.marker_manual, n))
        _state.update { it.copy(markerCount = n, message = UserMessage.MarkerAdded(n)) }
    }

    fun resetClip() {
        engine?.meter?.resetClip()
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    // --- Prise -----------------------------------------------------------------------

    /**
     * REC. Avec le déclenchement sur seuil, la prise est seulement armée : elle
     * commencera d'elle-même quand le signal dépassera le seuil.
     */
    fun startRecording(): Boolean {
        val s = _state.value
        if (s.isBusy) return true
        if (engine == null && openEngine() == null) return false
        if (s.options.trigger) {
            _state.update { it.copy(phase = Phase.ARMED, framesWritten = 0) }
            return true
        }
        return beginTake()
    }

    private fun beginTake(): Boolean {
        val e = engine ?: return false
        val s = _state.value
        // Le fichier commence au début du pré-enregistrement : l'horodatage en tient compte.
        val prerollFrames = e.prerollFrames
        val start = LocalDateTime.now().minusNanos(prerollFrames * 1_000_000_000L / e.format.sampleRate)
        val main = openTakeFile(start, "", e.format, null) ?: return false
        val safety = if (s.options.safetyTrack) {
            openTakeFile(start, "_securite", e.format, s.options.safetyDb) ?: run {
                runCatching { main.second.close() }
                main.first.discard()
                return false
            }
        } else {
            null
        }
        currentFile = main.first
        safetyFile = safety?.first
        e.meter.resetClip()
        e.loudness.reset()
        e.attachWriter(main.second, safety?.second, s.options.safetyDb, withPreroll = true)
        _state.update { it.copy(phase = Phase.RECORDING, fileName = main.first.displayName, framesWritten = 0, markerCount = 0) }
        return true
    }

    /** Crée un fichier de la prise et son en-tête (bext + iXML). [safetyDb] non nul = piste de sécurité. */
    private fun openTakeFile(start: LocalDateTime, suffix: String, format: AudioFormatSpec, safetyDb: Float?): Pair<RecordingFile, WavWriter>? {
        val file = try {
            storage.create(start, suffix)
        } catch (_: Exception) {
            _state.update { it.copy(message = UserMessage.WriteFailed) }
            return null
        }
        return try {
            val bext = bextFor(file.displayName, start, format, safetyDb)
            val tracks = if (format.channels == 2) {
                listOf(context.getString(R.string.channel_left_name), context.getString(R.string.channel_right_name))
            } else {
                listOf(context.getString(R.string.format_mono))
            }
            file to WavWriter(
                file.channel, format,
                listOf(
                    Bext.CHUNK_ID to bext.encode(),
                    Ixml.CHUNK_ID to Ixml.encode(bext.description, "Brut", bext.originatorReference, tracks),
                ),
            )
        } catch (_: IOException) {
            file.discard()
            _state.update { it.copy(message = UserMessage.WriteFailed) }
            null
        }
    }

    /** Décrit la chaîne d'enregistrement dans le fichier lui-même (lisible par les logiciels de montage). */
    private fun bextFor(fileName: String, start: LocalDateTime, format: AudioFormatSpec, safetyDb: Float? = null): Bext {
        val s = _state.value
        // Le micro interne porte le nom technique du téléphone : on écrit plutôt ce qu'il est.
        val device = s.selectedDevice?.let { d ->
            when {
                d.kind == InputKind.BUILTIN -> context.getString(R.string.kind_builtin) + (d.address.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
                d.productName.isBlank() -> d.kind.name
                else -> d.productName
            }
        } ?: "?"
        val gains = if (format.channels == 2) {
            context.getString(R.string.bext_gains_stereo, signedDb(s.gainDb[0]), signedDb(s.gainDb[1]))
        } else {
            context.getString(R.string.bext_gain_mono, signedDb(s.gainDb[0]))
        }
        val source = s.capture?.source?.name ?: "?"
        val safety = safetyDb?.let { context.getString(R.string.bext_safety, signedDb(it)) }.orEmpty()
        return Bext(
            description = context.getString(R.string.bext_description, device, gains, safety, source),
            originator = "Brut $appVersion",
            originatorReference = fileName.removeSuffix(".wav"),
            date = start,
            timeReference = start.toLocalTime().toNanoOfDay() / 1_000_000_000L * format.sampleRate,
            codingHistory = Bext.codingHistoryFor(format, "Brut $appVersion"),
        )
    }

    private fun signedDb(db: Float) = String.format(java.util.Locale.getDefault(), "%+.1f", db)

    fun stopRecording(message: UserMessage? = null) {
        val e = engine ?: return
        if (_state.value.isArmed) {
            // Armée mais jamais déclenchée : aucun fichier n'a été créé.
            _state.update { it.copy(phase = Phase.MONITORING) }
            if (!consoleVisible) stopMonitoring()
            return
        }
        val writers = e.detachWriter()
        val file = currentFile
        val safety = safetyFile
        currentFile = null
        safetyFile = null
        var finalMessage = message
        if (file != null) {
            try {
                // Sans writer (erreur d'écriture), on publie tout de même la partie déjà
                // écrite : son en-tête a été mis à jour au fil de la prise.
                if (writers != null) writers.main.close() else file.channel.close()
                file.publish()
                if (finalMessage == null) finalMessage = UserMessage.Saved(file.displayName)
            } catch (_: Exception) {
                finalMessage = UserMessage.WriteFailed
            }
        }
        if (safety != null) {
            runCatching {
                writers?.safety?.close() ?: safety.channel.close()
                safety.publish()
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
        val e = RecordingEngine(
            audioManager, s.format, s.selectedDevice?.info, s.captureMode, gain,
            s.options.effectivePrerollSeconds, engineListener,
        )
        return try {
            e.start()
            engine = e
            e.spectrumEnabled = s.meterMode == MeterMode.SPECTRUM
            applyMonitor()
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

    /**
     * La capture a lâché en pleine prise (serveur audio redémarré, pilote USB…) : on
     * rouvre l'entrée et on continue dans le MÊME fichier, avec un repère à l'endroit du trou.
     */
    private fun resumeCapture(): Boolean {
        val old = engine ?: return false
        val writers = old.detachWriter() ?: return false
        closeEngine()
        // Le format est verrouillé pendant une prise : la nouvelle capture est compatible.
        val fresh = openEngine()
        if (fresh == null) {
            // L'entrée ne revient pas : on sauvegarde proprement ce qui a été capté.
            runCatching { writers.main.close() }
            runCatching { writers.safety?.close() }
            runCatching { currentFile?.publish() }
            runCatching { safetyFile?.publish() }
            val name = currentFile?.displayName.orEmpty()
            currentFile = null
            safetyFile = null
            _state.update { it.copy(phase = Phase.STOPPED, message = UserMessage.Saved(name)) }
            return true
        }
        val label = context.getString(R.string.marker_resumed)
        writers.main.addMarker(label)
        writers.safety?.addMarker(label)
        fresh.attachWriter(writers.main, writers.safety, _state.value.options.safetyDb, withPreroll = false)
        _state.update { it.copy(phase = Phase.RECORDING, message = UserMessage.CaptureResumed) }
        return true
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
        override fun onLevels(levels: List<ChannelLevel>, framesWritten: Long, loudness: LoudnessReading, spectrum: FloatArray?) {
            val now = SystemClock.elapsedRealtime()
            val refreshFree = now - lastFreeCheck > 5_000
            if (refreshFree) lastFreeCheck = now
            val free = if (refreshFree) storage.freeBytes() else null
            _state.update {
                it.copy(
                    levels = levels,
                    framesWritten = if (it.isRecording) framesWritten else it.framesWritten,
                    freeBytes = free ?: it.freeBytes,
                    loudness = loudness,
                    spectrum = spectrum,
                    spectrumCenters = if (spectrum != null && it.spectrumCenters.isEmpty()) engine?.spectrum?.centers ?: it.spectrumCenters else it.spectrumCenters,
                )
            }
            // Déclenchement sur seuil : la première crête au-dessus du seuil lance la prise,
            // le pré-enregistrement en conserve l'attaque.
            val s = _state.value
            if (s.isArmed && levels.any { it.peakDb >= s.options.triggerDb }) {
                scope.launch { if (_state.value.isArmed && !beginTake()) _state.update { it.copy(phase = Phase.MONITORING) } }
            }
            if (refreshFree) {
                scope.launch {
                    refreshBattery()
                    guardResources()
                }
            }
        }

        override fun onCaptureInfo(info: CaptureInfo) {
            val before = _state.value.capture
            _state.update { it.copy(capture = info) }
            // Un appel ou une autre appli peut couper le micro : la prise continue (en silence),
            // et un repère marque l'endroit exact dans le fichier.
            if (_state.value.isRecording && before != null && before.silenced != info.silenced) {
                engine?.addMarker(context.getString(if (info.silenced) R.string.marker_silenced else R.string.marker_unsilenced))
            }
        }

        override fun onWriteError(error: IOException) {
            scope.launch { stopRecording(UserMessage.WriteFailed) }
        }

        override fun onSizeLimitReached() {
            scope.launch { if (_state.value.isRecording) stopRecording(UserMessage.SizeLimit) }
        }

        override fun onReadError(code: Int) {
            scope.launch {
                if (_state.value.isRecording && resumeCapture()) return@launch
                if (_state.value.isRecording) stopRecording(UserMessage.CaptureFailed)
                closeEngine()
                _state.update { it.copy(phase = Phase.STOPPED, message = UserMessage.CaptureFailed) }
            }
        }
    }
}
