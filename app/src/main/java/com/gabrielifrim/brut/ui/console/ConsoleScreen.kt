package com.gabrielifrim.brut.ui.console

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import com.gabrielifrim.brut.audio.EngineStats
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.gabrielifrim.brut.R
import com.gabrielifrim.brut.audio.BitDepth
import com.gabrielifrim.brut.audio.CaptureEncoding
import com.gabrielifrim.brut.audio.CaptureSource
import com.gabrielifrim.brut.audio.RecorderState
import com.gabrielifrim.brut.audio.UserMessage
import com.gabrielifrim.brut.ui.formatDb
import com.gabrielifrim.brut.ui.formatDuration
import com.gabrielifrim.brut.ui.formatLongDuration
import com.gabrielifrim.brut.ui.formatRate
import com.gabrielifrim.brut.ui.spokenDuration
import com.gabrielifrim.brut.ui.theme.BrutColors
import com.gabrielifrim.brut.ui.theme.BrutShapes
import com.gabrielifrim.brut.ui.theme.BrutType
import kotlinx.coroutines.delay

/** Actions de la console, regroupées pour garder l'écran indépendant du contrôleur. */
interface ConsoleActions {
    fun toggleRecording()
    fun selectDevice(id: Int)
    fun setFormat(format: com.gabrielifrim.brut.audio.AudioFormatSpec)
    fun setGain(channel: Int, db: Float)
    fun setGainLinked(linked: Boolean)
    fun setMeterMode(mode: com.gabrielifrim.brut.audio.MeterMode)
    fun setCaptureMode(mode: com.gabrielifrim.brut.audio.CaptureSource?)
    fun openLibrary()
    fun addMarker()
    fun setOptions(options: com.gabrielifrim.brut.audio.TakeOptions)
    fun applyPreset(preset: com.gabrielifrim.brut.audio.Preset)
    fun resetLoudness()
    fun resetClip()
    fun consumeMessage()
}

@Composable
fun ConsoleScreen(state: RecorderState, actions: ConsoleActions) {
    var showFormat by rememberSaveable { mutableStateOf(false) }
    var showSource by rememberSaveable { mutableStateOf(false) }
    var showEngine by rememberSaveable { mutableStateOf(false) }
    val labels = when (state.format.channels) {
        1 -> listOf(stringResource(R.string.channel_mono))
        2 -> listOf(stringResource(R.string.channel_left), stringResource(R.string.channel_right))
        else -> List(state.format.channels) { "${it + 1}" }
    }

    Box(
        Modifier
            .fillMaxSize()
            .chassis()
            .safeDrawingPadding(),
    ) {
        val meters = @Composable { modifier: Modifier ->
            MeterBridge(
                levels = state.levels.take(state.format.channels),
                channelLabels = labels,
                mode = state.meterMode,
                onModeChange = actions::setMeterMode,
                onResetClip = actions::resetClip,
                modifier = modifier,
                loudness = state.loudness,
                spectrum = state.spectrum,
                spectrumCenters = state.spectrumCenters,
                onResetLoudness = actions::resetLoudness,
            )
        }
        val gainPanel = @Composable {
            GainPanel(
                channels = state.format.channels,
                labels = labels,
                gains = state.gainDb,
                linked = state.gainLinked,
                onGain = actions::setGain,
                onLinked = actions::setGainLinked,
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val landscape = maxWidth > maxHeight && maxWidth >= 560.dp
            // Sous ~680 dp de haut, laisser le pont de mesure prendre « le reste » l'écraserait :
            // on lui donne une hauteur minimale et la console défile.
            val compact = !landscape && maxHeight < 680.dp
            val compactMeterHeight = maxOf(340.dp, maxHeight * 0.62f)
            when {
                landscape -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    meters(Modifier.weight(1f).fillMaxHeight())
                    // Le transport reste fixé en bas : le bouton REC ne doit jamais défiler hors de vue.
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            Header(state, onFormat = { showFormat = true }, onLibrary = actions::openLibrary)
                            Spacer(Modifier.height(10.dp))
                            SourceStrip(state, onClick = { showSource = true }, onEngine = { showEngine = true })
                            Warnings(state)
                            Spacer(Modifier.height(10.dp))
                            gainPanel()
                        }
                        Spacer(Modifier.height(8.dp))
                        Transport(state, actions)
                    }
                }
                compact -> Column(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Header(state, onFormat = { showFormat = true }, onLibrary = actions::openLibrary)
                        Spacer(Modifier.height(10.dp))
                        SourceStrip(state, onClick = { showSource = true }, onEngine = { showEngine = true })
                        Warnings(state)
                        Spacer(Modifier.height(10.dp))
                        meters(Modifier.fillMaxWidth().height(compactMeterHeight))
                        Spacer(Modifier.height(12.dp))
                        gainPanel()
                    }
                    Spacer(Modifier.height(8.dp))
                    Transport(state, actions)
                }
                else -> Column(Modifier.fillMaxSize()) {
                    Header(state, onFormat = { showFormat = true }, onLibrary = actions::openLibrary)
                    Spacer(Modifier.height(10.dp))
                    SourceStrip(state, onClick = { showSource = true }, onEngine = { showEngine = true })
                    Warnings(state)
                    Spacer(Modifier.height(10.dp))
                    meters(Modifier.weight(1f).fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    gainPanel()
                    Spacer(Modifier.height(8.dp))
                    Transport(state, actions)
                }
            }
        }

        MessageBar(state.message, actions::consumeMessage, Modifier.align(Alignment.BottomCenter))
    }

    if (showFormat) {
        FormatSheet(
            format = state.format,
            device = state.selectedDevice,
            locked = state.isBusy,
            options = state.options,
            headphones = state.headphones,
            onChange = actions::setFormat,
            onOptions = actions::setOptions,
            onPreset = actions::applyPreset,
        ) { showFormat = false }
    }
    if (showSource) {
        SourceSheet(
            devices = state.devices,
            selectedId = state.selectedDeviceId,
            routedId = state.capture?.routedDeviceId,
            captureMode = state.captureMode,
            activeSource = state.capture?.source,
            unprocessedSupported = state.unprocessedSupported,
            locked = state.isRecording,
            onSelect = { actions.selectDevice(it); showSource = false },
            onCaptureMode = actions::setCaptureMode,
            onDismiss = { showSource = false },
        )
    }
    if (showEngine) {
        EngineSheet(state.stats, state.isRecording) { showEngine = false }
    }
}

@Composable
private fun Header(state: RecorderState, onFormat: () -> Unit, onLibrary: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Le mot-symbole reprend l'icône : la lettre, puis le trait ambre.
        Column {
            Text(stringResource(R.string.app_name).uppercase(), style = BrutType.Title, color = BrutColors.Cream)
            Box(Modifier.width(34.dp).height(2.dp).background(BrutColors.Amber))
        }
        Spacer(Modifier.width(10.dp))
        val libraryLabel = stringResource(R.string.a11y_open_library)
        Text(
            stringResource(R.string.library_open).uppercase() + " \u203A",
            style = engraved(BrutType.Legend),
            color = BrutColors.Cream,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(BrutColors.Panel)
                .clickable(role = Role.Button, onClick = onLibrary)
                .semantics { contentDescription = libraryLabel }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        val f = state.format
        val depth = when (f.bitDepth) {
            BitDepth.PCM_16 -> "16"
            BitDepth.PCM_24 -> "24"
            BitDepth.FLOAT_32 -> "32F"
        }
        val channels = channelsLabel(f.channels)
        Readout(
            "${formatRate(f.sampleRate)} · $depth BIT · $channels",
            Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.take_settings_title), onClick = onFormat),
        )
    }
}

/**
 * La source, sur une plaque de rack vissée : l'entrée gravée en tête, puis les
 * repères d'état de la capture tels qu'Android les a réellement mis en place.
 */
@Composable
private fun SourceStrip(state: RecorderState, onClick: () -> Unit, onEngine: () -> Unit) {
    val device = state.selectedDevice
    val capture = state.capture
    val lampColor = when {
        device == null -> BrutColors.CreamFaint
        state.isRerouted || capture?.silenced == true -> BrutColors.Red
        capture?.source == CaptureSource.MIC -> BrutColors.Amber
        capture != null -> BrutColors.Green
        else -> BrutColors.Amber
    }
    RackPlate(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        contentPadding = 10.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.source_title).uppercase(), style = engraved(BrutType.Legend), color = BrutColors.CreamDim)
                    Text("  →  ", style = BrutType.Legend, color = BrutColors.CreamFaint, modifier = Modifier.clearAndSetSemantics {})
                    Text(
                        (device?.let { kindLabel(it.kind) } ?: stringResource(R.string.source_none)).uppercase(),
                        style = engraved(BrutType.Legend), color = BrutColors.Amber,
                    )
                }
                Text(
                    if (device != null) deviceName(device) else stringResource(R.string.source_none),
                    style = engraved(BrutType.BodyStrong), color = BrutColors.Cream,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                CaptureTags(state, onEngine)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Lamp(lampColor)
                Text("›", style = BrutType.Title, color = BrutColors.CreamDim, modifier = Modifier.clearAndSetSemantics {})
            }
        }
    }
}

/**
 * Repères d'état : mode de capture, encodage, fréquence matérielle, effets, puis la santé
 * du moteur (processeur, tampon), qui ouvre son détail au toucher.
 */
@Composable
private fun CaptureTags(state: RecorderState, onEngine: () -> Unit) {
    val c = state.capture
    if (c == null) {
        Text(stringResource(R.string.capture_waiting), style = BrutType.ReadoutSmall, color = BrutColors.CreamDim)
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val (sourceText, sourceColor) = when (c.source) {
            CaptureSource.UNPROCESSED -> stringResource(R.string.tag_raw) to BrutColors.Green
            CaptureSource.VOICE_RECOGNITION -> stringResource(R.string.tag_no_agc) to BrutColors.Cream
            CaptureSource.MIC -> stringResource(R.string.tag_standard) to BrutColors.Amber
        }
        StatusTag(sourceText, sourceColor)
        StatusTag(if (c.encoding == CaptureEncoding.FLOAT) "F32" else "I16", BrutColors.CreamDim)
        c.deviceSampleRate?.let { StatusTag(formatRate(it).replace(" kHz", "k"), BrutColors.CreamDim) }
        if (c.activeEffects.isEmpty()) {
            StatusTag(stringResource(R.string.tag_no_fx), BrutColors.CreamDim)
        } else {
            StatusTag(stringResource(R.string.tag_fx), BrutColors.Amber)
        }
        state.stats?.let { EngineTags(it, onEngine) }
    }
}

@Composable
private fun EngineTags(stats: EngineStats.Snapshot, onClick: () -> Unit) {
    val color = engineColor(stats)
    val cpu = stats.cpuPercent?.let { percent(it / 100f) } ?: "—"
    // Sans horodatage matériel, on montre au moins la taille de la réserve.
    val buffer = stats.bufferFill?.let { percent(it) } ?: "${stats.bufferMs.roundToInt()} ms"
    val spoken = stringResource(R.string.a11y_engine_tags, cpu, buffer)
    val loss = stringResource(R.string.a11y_engine_loss)
    val open = stringResource(R.string.a11y_engine_open)
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            // Seule une perte est annoncée d'office ; les chiffres se lisent à la demande.
            .clearAndSetSemantics {
                contentDescription = spoken
                if (stats.lostFrames > 0) {
                    stateDescription = loss
                    liveRegion = LiveRegionMode.Polite
                }
                role = Role.Button
                onClick(open) { onClick(); true }
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusTag(stringResource(R.string.tag_cpu, cpu), color)
        StatusTag(stringResource(R.string.tag_buffer, buffer), color)
    }
}

@Composable
private fun Warnings(state: RecorderState) {
    val c = state.capture
    val warnings = buildList {
        if (state.isRerouted) add(stringResource(R.string.warning_rerouted, c?.routedDeviceName.orEmpty()))
        if (c?.silenced == true) add(stringResource(R.string.warning_silenced))
        if (state.lowBattery) add(stringResource(R.string.warning_low_battery, state.batteryPercent))
        if (state.isArmed) add(stringResource(R.string.warning_armed, "−${(-state.options.triggerDb).toInt()}"))
        if (state.options.monitor && !state.headphones) add(stringResource(R.string.warning_monitor_no_headphones))
        if (state.lowSpace) add(stringResource(R.string.warning_low_space, formatLongDuration(state.remainingSeconds)))
        // Seulement si le repli est subi : un mode Standard choisi à la main n'a pas à être signalé.
        if (c?.source == CaptureSource.MIC && state.captureMode != CaptureSource.MIC) add(stringResource(R.string.warning_mic_source))
        if (!c?.activeEffects.isNullOrEmpty()) add(stringResource(R.string.warning_effects, c.activeEffects.joinToString()))
        // Multipiste demandé à une entrée qui a moins de voies : Android remplit le reste.
        val deviceChannels = c?.deviceChannels
        if (state.format.channels > 2 && deviceChannels != null && deviceChannels < state.format.channels) {
            add(androidx.compose.ui.res.pluralStringResource(R.plurals.warning_fewer_channels, deviceChannels, deviceChannels))
        }
        val deviceRate = c?.deviceSampleRate
        if (deviceRate != null && deviceRate != state.format.sampleRate) {
            add(stringResource(R.string.warning_resampled, formatRate(deviceRate)))
        }
    }
    warnings.forEach { text ->
        Row(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(BrutShapes.Plate))
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .background(BrutColors.Amber.copy(alpha = 0.10f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Lamp(BrutColors.Amber)
            Spacer(Modifier.width(10.dp))
            Text(text, style = BrutType.Body, color = BrutColors.Cream)
        }
    }
}

@Composable
private fun Transport(state: RecorderState, actions: ConsoleActions) {
    LtcReadout(state)
    ToolsSummary(state)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Clock(state.elapsedSeconds, state.isRecording)
            val sub = when {
                state.isRecording && state.fileName != null -> stringResource(R.string.recording_to, state.fileName)
                state.remainingSeconds in 1..600 -> stringResource(R.string.remaining_low, formatLongDuration(state.remainingSeconds))
                else -> stringResource(R.string.remaining, formatLongDuration(state.remainingSeconds), state.folderLabel)
            }
            Text(sub, style = BrutType.ReadoutSmall, color = BrutColors.CreamDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (state.isRecording) {
            MarkerButton(state.markerCount, actions::addMarker)
            Spacer(Modifier.width(10.dp))
        }
        RecordButton(
            recording = state.isRecording,
            enabled = state.selectedDevice != null && state.capture != null,
            onClick = actions::toggleRecording,
            armed = state.isArmed,
        )
    }
}

/** Timecode reçu sur la voie LTC : à vérifier avant REC, comme sur un enregistreur de plateau. */
@Composable
private fun LtcReadout(state: RecorderState) {
    val channel = state.options.ltcChannel ?: return
    val side = when {
        state.format.channels > 2 -> "${channel + 1}"
        state.format.channels == 1 -> ""
        else -> stringResource(if (channel == 0) R.string.channel_left else R.string.channel_right)
    }
    val tc = state.ltcReadout
    val spoken = if (tc != null) stringResource(R.string.a11y_ltc, tc) else stringResource(R.string.a11y_ltc_none)
    Row(
        Modifier
            .padding(bottom = 4.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Sans LTC au repos, ce n'est qu'un état ; le rouge est réservé à une prise qui le perd.
        val lost = tc == null && state.isRecording
        Lamp(if (tc != null) BrutColors.Green else if (lost) BrutColors.Red else BrutColors.CreamFaint)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.ltc_label, side), style = engraved(BrutType.Legend), color = BrutColors.CreamDim)
        Spacer(Modifier.width(8.dp))
        Readout(tc ?: stringResource(R.string.ltc_none), color = if (tc != null) BrutColors.Amber else if (lost) BrutColors.Red else BrutColors.CreamDim)
    }
}

/** Rappel discret des outils de prise actifs, au-dessus du transport. */
@Composable
private fun ToolsSummary(state: RecorderState) {
    val o = state.options
    val parts = buildList {
        if (o.prerollSeconds > 0) add(stringResource(R.string.tools_short_preroll, o.prerollSeconds))
        if (o.safetyTrack) add(stringResource(R.string.tools_short_safety, "−${(-o.safetyDb).toInt()}"))
        if (o.trigger) add(stringResource(R.string.tools_short_trigger, "−${(-o.triggerDb).toInt()}"))
        if (o.monitor) add(stringResource(R.string.tools_short_monitor))
    }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString("  \u00B7  ") { "\u25B8 $it" },
        style = engraved(BrutType.Legend),
        color = BrutColors.Amber,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(bottom = 4.dp)
            .clearAndSetSemantics { contentDescription = parts.joinToString(", ") },
    )
}

/**
 * Chrono sur une seule ligne quoi qu'il arrive : les centièmes sont plus petits,
 * comme sur un enregistreur de terrain, et la taille se réduit si l'écran est étroit.
 */
@Composable
private fun Clock(seconds: Double, recording: Boolean) {
    val full = formatDuration(seconds)
    val cut = full.lastIndexOf('.')
    val text = buildAnnotatedString {
        append(full.substring(0, cut))
        withStyle(SpanStyle(fontSize = 0.55.em)) { append(full.substring(cut)) }
    }
    val spoken = if (recording) stringResource(R.string.a11y_clock, spokenDuration(seconds)) else stringResource(R.string.a11y_clock_idle)
    BasicText(
        text,
        modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
        style = BrutType.Clock.copy(color = if (recording) BrutColors.Cream else BrutColors.CreamFaint),
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = 22.sp, maxFontSize = BrutType.Clock.fontSize, stepSize = 1.sp),
    )
}

@Composable
private fun MessageBar(message: UserMessage?, onDone: () -> Unit, modifier: Modifier) {
    var shown by remember { mutableStateOf<UserMessage?>(null) }
    LaunchedEffect(message) {
        if (message != null) {
            shown = message
            delay(
                when (message) {
                    is UserMessage.MarkerAdded -> 1500
                    is UserMessage.Saved, is UserMessage.DeviceConnected -> 3500
                    else -> 7000
                },
            )
            onDone()
        }
    }
    AnimatedVisibility(message != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        val m = shown ?: return@AnimatedVisibility
        val text = when (m) {
            is UserMessage.Saved -> when {
                m.ltc != null -> stringResource(R.string.msg_saved_ltc, m.name, m.ltc)
                m.ltcMissing -> stringResource(R.string.msg_saved_no_ltc, m.name)
                else -> stringResource(R.string.msg_saved, m.name)
            }
            is UserMessage.DeviceConnected -> stringResource(R.string.msg_device_connected, m.name)
            is UserMessage.DeviceLost -> stringResource(R.string.msg_device_lost, m.name)
            is UserMessage.RecordingStoppedDeviceLost -> stringResource(R.string.msg_stopped_device_lost, m.name, m.file)
            UserMessage.SizeLimit -> stringResource(R.string.msg_size_limit)
            UserMessage.WriteFailed -> stringResource(R.string.msg_write_failed)
            UserMessage.CaptureFailed -> stringResource(R.string.msg_capture_failed)
            is UserMessage.Recovered -> stringResource(R.string.msg_recovered, m.name, formatDuration(m.seconds, withHundredths = false))
            UserMessage.CaptureResumed -> stringResource(R.string.msg_capture_resumed)
            UserMessage.StoppedLowBattery -> stringResource(R.string.msg_stopped_battery)
            UserMessage.StoppedNoSpace -> stringResource(R.string.msg_stopped_space)
            is UserMessage.MarkerAdded -> stringResource(R.string.msg_marker, m.number)
            UserMessage.MonitorNeedsHeadphones -> stringResource(R.string.warning_monitor_no_headphones)
        }
        val accent = when (m) {
            is UserMessage.Saved -> if (m.ltcMissing) BrutColors.Amber else BrutColors.Green
            is UserMessage.DeviceConnected, is UserMessage.Recovered -> BrutColors.Green
            UserMessage.CaptureResumed, is UserMessage.MarkerAdded, UserMessage.MonitorNeedsHeadphones -> BrutColors.Amber
            else -> BrutColors.Red
        }
        Text(
            text,
            style = BrutType.Body,
            color = BrutColors.Cream,
            modifier = Modifier
                .padding(16.dp)
                .padding(bottom = 96.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(BrutShapes.Plate))
                .background(BrutColors.PanelRaised)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .clickable(onClick = onDone)
                .drawBehind { drawRect(accent, Offset.Zero, size.copy(width = 4.dp.toPx())) }
                .padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}
