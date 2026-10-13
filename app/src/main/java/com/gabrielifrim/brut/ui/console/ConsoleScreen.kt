package com.gabrielifrim.brut.ui.console

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.gabrielifrim.brut.R
import com.gabrielifrim.brut.audio.BitDepth
import com.gabrielifrim.brut.audio.CaptureEncoding
import com.gabrielifrim.brut.audio.CaptureSource
import com.gabrielifrim.brut.audio.ChannelLevel
import com.gabrielifrim.brut.audio.EngineStats
import com.gabrielifrim.brut.audio.LoudnessReading
import com.gabrielifrim.brut.audio.RecorderState
import com.gabrielifrim.brut.audio.UserMessage
import com.gabrielifrim.brut.storage.Appearance
import com.gabrielifrim.brut.ui.component.Imposed
import com.gabrielifrim.brut.ui.component.PathNode
import com.gabrielifrim.brut.ui.component.PanelKey
import com.gabrielifrim.brut.ui.component.PathState
import com.gabrielifrim.brut.ui.component.Readout
import com.gabrielifrim.brut.ui.component.SignalPath
import com.gabrielifrim.brut.ui.component.StatusTag
import com.gabrielifrim.brut.ui.component.chassis
import com.gabrielifrim.brut.ui.component.engraved
import com.gabrielifrim.brut.ui.formatDb
import com.gabrielifrim.brut.ui.formatDuration
import com.gabrielifrim.brut.ui.formatLongDuration
import com.gabrielifrim.brut.ui.formatRate
import com.gabrielifrim.brut.ui.motion.LocalMotionLevel
import com.gabrielifrim.brut.ui.motion.Motion
import com.gabrielifrim.brut.ui.spokenDuration
import com.gabrielifrim.brut.ui.theme.BrutColors
import com.gabrielifrim.brut.ui.theme.BrutShapes
import com.gabrielifrim.brut.ui.theme.BrutType
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

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
    fun startChannelTest()
    fun stopChannelTest()
    fun closeChannelTest()
    fun applyRecipe(recipe: com.gabrielifrim.brut.audio.CaptureRecipe)
    fun forgetRecipe()
    fun setPalette(id: String)
    fun setSober(sober: Boolean)
}

/**
 * L'état sans ce qui change à chaque mesure (niveaux, sonie, spectre, chrono, LTC) : la console
 * ne se recompose qu'à un vrai changement. Les voyants de saturation, eux, restent.
 */
fun RecorderState.calm(): RecorderState = copy(
    levels = levels.map { ChannelLevel(clipped = it.clipped, inputClipped = it.inputClipped) },
    loudness = LoudnessReading(),
    spectrum = null,
    framesWritten = 0,
    ltcReadout = null,
)

/**
 * La console. [state] est l'état calme ([calm]) ; [live] est l'état complet, lu seulement par
 * les instruments (pont de mesure, chrono, LTC), seuls à se redessiner à chaque mesure.
 */
@Composable
fun ConsoleScreen(
    state: RecorderState,
    live: State<RecorderState>,
    appearance: Appearance,
    systemMotionOff: Boolean,
    actions: ConsoleActions,
) {
    var showFormat by rememberSaveable { mutableStateOf(false) }
    var showSource by rememberSaveable { mutableStateOf(false) }
    var showEngine by rememberSaveable { mutableStateOf(false) }
    var showTest by rememberSaveable { mutableStateOf(false) }
    var showDisplay by rememberSaveable { mutableStateOf(false) }
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
        val meters = @Composable { modifier: Modifier -> LiveMeters(live, labels, actions, modifier) }
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
        val top = @Composable {
            Header(onDisplay = { showDisplay = true }, onLibrary = actions::openLibrary)
            Spacer(Modifier.height(10.dp))
            ConsolePath(state, onSource = { showSource = true }, onFormat = { showFormat = true }, onEngine = { showEngine = true })
            Warnings(state)
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val landscape = maxWidth > maxHeight && maxWidth >= 560.dp
            // Sous ~680 dp de haut, ou quand le texte est agrandi, laisser le pont de mesure prendre
            // « le reste » l'écraserait : on lui donne une hauteur minimale et la console défile.
            val compact = !landscape && (maxHeight < 680.dp || LocalDensity.current.fontScale > 1.3f)
            val compactMeterHeight = maxOf(340.dp, maxHeight * 0.62f)
            when {
                // Paysage : les mesures sur toute la hauteur ; à droite, chemin et gain défilent
                // au-dessus du transport, fixé en bas (REC ne doit jamais sortir de la vue).
                landscape -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    meters(Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            top()
                            Spacer(Modifier.height(10.dp))
                            gainPanel()
                        }
                        Spacer(Modifier.height(8.dp))
                        Transport(state, live, actions)
                    }
                }
                compact -> Column(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        top()
                        Spacer(Modifier.height(10.dp))
                        meters(Modifier.fillMaxWidth().height(compactMeterHeight))
                        Spacer(Modifier.height(12.dp))
                        gainPanel()
                    }
                    Spacer(Modifier.height(8.dp))
                    Transport(state, live, actions)
                }
                else -> Column(Modifier.fillMaxSize()) {
                    top()
                    Spacer(Modifier.height(10.dp))
                    meters(Modifier.weight(1f).fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    gainPanel()
                    Spacer(Modifier.height(8.dp))
                    Transport(state, live, actions)
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
            recipe = state.selectedRecipe,
            canTest = state.selectedDevice?.isExternal == true,
            onTest = { showSource = false; showTest = true },
            onForgetRecipe = actions::forgetRecipe,
        )
    }
    if (showTest || state.channelTest != null) {
        ChannelTestSheet(
            deviceName = state.selectedDevice?.let { deviceName(it) }.orEmpty(),
            sampleRate = state.format.sampleRate,
            test = state.channelTest,
            activeRecipe = state.selectedRecipe.takeIf { state.captureMode == null },
            onStart = actions::startChannelTest,
            onStop = actions::stopChannelTest,
            onUse = actions::applyRecipe,
            onDismiss = { showTest = false; actions.closeChannelTest() },
        )
    }
    if (showEngine) {
        EngineSheet(state.stats, state.isRecording) { showEngine = false }
    }
    if (showDisplay) {
        DisplaySheet(appearance, systemMotionOff, actions::setPalette, actions::setSober) { showDisplay = false }
    }
}

/** Pont de mesure branché sur l'état vivant : seul ce morceau suit les mesures. */
@Composable
private fun LiveMeters(live: State<RecorderState>, labels: List<String>, actions: ConsoleActions, modifier: Modifier) {
    val s = live.value
    MeterBridge(
        levels = s.levels.take(s.format.channels),
        channelLabels = labels,
        mode = s.meterMode,
        onModeChange = actions::setMeterMode,
        onResetClip = actions::resetClip,
        modifier = modifier,
        loudness = s.loudness,
        spectrum = s.spectrum,
        spectrumCenters = s.spectrumCenters,
        onResetLoudness = actions::resetLoudness,
    )
}

/** Le logo, puis l'affichage et les prises, en aval comme le signal. */
@Composable
private fun Header(onDisplay: () -> Unit, onLibrary: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Le mot-symbole reprend l'icône : la lettre, puis le trait ambre.
        Column(Modifier.clearAndSetSemantics {}) {
            Text(stringResource(R.string.app_name).uppercase(), style = BrutType.Title, color = BrutColors.Ink)
            Box(Modifier.width(34.dp).height(2.dp).background(BrutColors.Lamp))
        }
        Spacer(Modifier.weight(1f))
        DisplayButton(onDisplay)
        Spacer(Modifier.width(8.dp))
        val libraryLabel = stringResource(R.string.a11y_open_library)
        PanelKey(
            stringResource(R.string.library_open).uppercase() + "  ›",
            Modifier
                .clickable(role = Role.Button, onClick = onLibrary)
                .clearAndSetSemantics {
                    contentDescription = libraryLabel
                    role = Role.Button
                    onClick { onLibrary(); true }
                },
        )
    }
}

/** Réglages d'affichage : un demi-disque, clair d'un côté et sombre de l'autre, comme les deux palettes. */
@Composable
private fun DisplayButton(onClick: () -> Unit) {
    val label = stringResource(R.string.a11y_display_settings)
    val ink = BrutColors.InkDim
    Box(
        Modifier
            .size(40.dp)
            .clip(BrutShapes.InsetShape)
            .background(BrutColors.Plate)
            .border(1.dp, BrutColors.Edge, BrutShapes.InsetShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val r = size.minDimension / 2
            drawCircle(ink, r, style = Stroke(1.5.dp.toPx()))
            drawArc(ink, 90f, 180f, useCenter = true, topLeft = Offset.Zero, size = size)
        }
    }
}

/**
 * Le chemin du signal de la prise en cours : l'entrée réellement ouverte, le format, le gain,
 * le fichier. Ce qu'Android impose se branche sous le nœud où cela se produit.
 */
@Composable
private fun ConsolePath(state: RecorderState, onSource: () -> Unit, onFormat: () -> Unit, onEngine: () -> Unit) {
    val device = state.selectedDevice
    val c = state.capture
    val f = state.format

    val sourceTag = when (c?.source) {
        CaptureSource.UNPROCESSED -> stringResource(R.string.tag_raw)
        CaptureSource.VOICE_RECOGNITION -> stringResource(R.string.tag_no_agc)
        CaptureSource.MIC -> stringResource(R.string.tag_standard)
        CaptureSource.CAMCORDER -> stringResource(R.string.tag_camcorder)
        null -> stringResource(R.string.capture_waiting)
    }
    val inputName = device?.let { deviceName(it) } ?: stringResource(R.string.source_none)
    val inputDetail = listOfNotNull(device?.let { kindLabel(it.kind) }, sourceTag).joinToString(" · ")
    val inputImposed = buildList {
        if (state.isRerouted) add(Imposed(stringResource(R.string.warning_rerouted, c?.routedDeviceName.orEmpty()), severe = true))
        if (c?.silenced == true) add(Imposed(stringResource(R.string.warning_silenced), severe = true))
        if (c?.channelsIdentical == true && device?.isExternal == true) add(Imposed(stringResource(R.string.warning_channels_identical), severe = true))
        // Seulement si le repli est subi : un mode Standard choisi à la main n'a pas à être signalé.
        if (c?.source == CaptureSource.MIC && state.captureMode != CaptureSource.MIC) add(Imposed(stringResource(R.string.warning_mic_source)))
        if (!c?.activeEffects.isNullOrEmpty()) add(Imposed(stringResource(R.string.warning_effects, c.activeEffects.joinToString())))
    }

    val depth = when (f.bitDepth) {
        BitDepth.PCM_16 -> "16"
        BitDepth.PCM_24 -> "24"
        BitDepth.FLOAT_32 -> "32F"
    }
    val formatValue = "${formatRate(f.sampleRate).replace(" kHz", "k")} · $depth"
    val formatDetail = listOfNotNull(
        channelsLabel(f.channels).lowercase(),
        c?.let { if (it.encoding == CaptureEncoding.FLOAT) "F32" else "I16" },
        stringResource(R.string.tag_index).lowercase().takeIf { c?.indexMask == true && f.channels <= 2 },
    ).joinToString(" · ")
    val formatImposed = buildList {
        val deviceRate = c?.deviceSampleRate
        if (deviceRate != null && deviceRate != f.sampleRate) add(Imposed(stringResource(R.string.warning_resampled, formatRate(deviceRate))))
        // Multipiste demandé à une entrée qui a moins de voies : Android remplit le reste.
        val deviceChannels = c?.deviceChannels
        if (f.channels > 2 && deviceChannels != null && deviceChannels < f.channels) {
            add(Imposed(pluralStringResource(R.plurals.warning_fewer_channels, deviceChannels, deviceChannels)))
        }
    }

    val gains = state.gainDb.take(f.channels)
    fun db(v: Float) = formatDb(v, signed = true) ?: "0,0"
    val gainValue = when {
        f.channels == 1 || state.gainLinked -> stringResource(R.string.db_value, db(gains[0]))
        f.channels == 2 -> "${db(gains[0])} · ${db(gains[1])}"
        else -> stringResource(R.string.path_gain_per_track)
    }
    val gainDetail = when {
        f.channels == 1 -> stringResource(R.string.format_mono).lowercase()
        f.channels == 2 -> stringResource(if (state.gainLinked) R.string.path_gain_linked else R.string.path_gain_split)
        state.gainLinked -> stringResource(R.string.path_gain_tracks_linked)
        else -> channelsLabel(f.channels).lowercase()
    }
    val clipped = state.levels.take(f.channels).any { it.clipped }

    val nodes = listOf(
        PathNode(
            key = stringResource(R.string.source_title), value = inputName, detail = inputDetail,
            spoken = stringResource(R.string.a11y_path_input, inputName, inputDetail),
            imposed = inputImposed, alarm = device == null,
            clickLabel = stringResource(R.string.path_change_input), onClick = onSource,
        ),
        PathNode(
            key = stringResource(R.string.path_format), value = formatValue, detail = formatDetail,
            // À l'oreille : « 48 kHz, 24 bit », pas « 48k point 24 ».
            spoken = stringResource(R.string.a11y_path_format, "${formatRate(f.sampleRate)}, ${depth.removeSuffix("F")} bit", formatDetail),
            imposed = formatImposed,
            clickLabel = stringResource(R.string.path_change_format), onClick = onFormat,
        ),
        PathNode(
            key = stringResource(R.string.gain), value = gainValue, detail = gainDetail,
            spoken = if (clipped) stringResource(R.string.a11y_path_gain_clip, gainValue) else stringResource(R.string.a11y_path_gain, gainValue, gainDetail),
            alarm = clipped,
        ),
    )
    val pathState = when {
        state.isRecording -> PathState.RECORDING
        state.isArmed -> PathState.ARMED
        else -> PathState.IDLE
    }
    SignalPath(
        nodes = nodes,
        // Sans le temps restant : il baisse pendant la prise et ferait parler TalkBack chaque minute.
        fileSpoken = stringResource(R.string.a11y_path_file),
        fileState = stringResource(
            when (pathState) {
                PathState.IDLE -> R.string.path_file_idle
                PathState.ARMED -> R.string.path_file_armed
                PathState.RECORDING -> R.string.path_file_recording
            },
        ),
        state = pathState,
    ) {
        state.stats?.let { EngineLine(it, onEngine) }
    }
}

/** Santé du moteur sous le chemin : on la touche pour le détail. */
@Composable
private fun EngineLine(stats: EngineStats.Snapshot, onClick: () -> Unit) {
    val color = engineColor(stats)
    val cpu = stats.cpuPercent?.let { percent(it / 100f) } ?: "—"
    // Sans horodatage matériel, on montre au moins la taille de la réserve.
    val buffer = stats.bufferFill?.let { percent(it) } ?: "${stats.bufferMs.roundToInt()} ms"
    val spoken = stringResource(R.string.a11y_engine_tags, cpu, buffer)
    val loss = stringResource(R.string.a11y_engine_loss)
    val open = stringResource(R.string.a11y_engine_open)
    FlowRow(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .clip(BrutShapes.InsetShape)
            .clickable(onClick = onClick)
            // Seule une perte est annoncée d'office ; les chiffres se lisent à la demande.
            .clearAndSetSemantics {
                contentDescription = spoken
                // Annonce d'office seulement pour une perte : les pourcentages changent chaque seconde.
                if (stats.lostFrames > 0) {
                    stateDescription = loss
                    liveRegion = LiveRegionMode.Polite
                }
                role = Role.Button
                onClick(open) { onClick(); true }
            }
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.engine_line).uppercase(), style = engraved(BrutType.Legend), color = BrutColors.InkDim)
        StatusTag(stringResource(R.string.tag_cpu, cpu), color)
        StatusTag(stringResource(R.string.tag_buffer, buffer), color)
        StatusTag(stringResource(R.string.tag_loss, if (stats.lostFrames == 0L) "0" else formatDuration(stats.lostMs / 1000.0)), if (stats.lostFrames > 0) BrutColors.Clip else BrutColors.InkDim)
    }
}

/**
 * Ce qui ne touche pas au chemin du signal mais menace la prise : batterie, espace, prise
 * armée, écoute sans casque. Un seul conteneur, toujours présent, porte l'annonce TalkBack.
 */
@Composable
private fun Warnings(state: RecorderState) {
    val warnings = buildList {
        if (state.lowBattery) add(stringResource(R.string.warning_low_battery, state.batteryPercent))
        if (state.isArmed) add(stringResource(R.string.warning_armed, "−${(-state.options.triggerDb).toInt()}"))
        if (state.options.monitor && !state.headphones) add(stringResource(R.string.warning_monitor_no_headphones))
        if (state.lowSpace) add(stringResource(R.string.warning_low_space, formatLongDuration(state.remainingSeconds)))
    }
    val spoken = warnings.joinToString(". ")
    val lamp = BrutColors.Lamp
    Column(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        warnings.forEach { text ->
            Row(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .clip(BrutShapes.PlateShape)
                    .background(lamp.copy(alpha = 0.12f))
                    .drawBehind { drawRect(lamp, Offset.Zero, size.copy(width = 3.dp.toPx())) }
                    .padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text, style = BrutType.Body, color = BrutColors.Ink)
            }
        }
    }
}

@Composable
private fun Transport(state: RecorderState, live: State<RecorderState>, actions: ConsoleActions) {
    LtcReadout(state, live)
    ToolsSummary(state)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            LiveClock(live, state.isRecording)
            val sub = when {
                state.isRecording && state.fileName != null -> stringResource(R.string.recording_to, state.fileName)
                state.remainingSeconds in 1..600 -> stringResource(R.string.remaining_low, formatLongDuration(state.remainingSeconds))
                else -> stringResource(R.string.remaining, formatLongDuration(state.remainingSeconds), state.folderLabel)
            }
            Text(sub, style = BrutType.ReadoutSmall, color = BrutColors.InkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun LtcReadout(state: RecorderState, live: State<RecorderState>) {
    val channel = state.options.ltcChannel ?: return
    val side = when {
        state.format.channels > 2 -> "${channel + 1}"
        state.format.channels == 1 -> ""
        else -> stringResource(if (channel == 0) R.string.channel_left else R.string.channel_right)
    }
    val tc = live.value.ltcReadout
    val spoken = if (tc != null) stringResource(R.string.a11y_ltc, tc) else stringResource(R.string.a11y_ltc_none)
    Row(
        Modifier
            .padding(bottom = 4.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Sans LTC au repos, ce n'est qu'un état ; le rouge est réservé à une prise qui le perd.
        val lost = tc == null && state.isRecording
        Lamp(if (tc != null) BrutColors.Level else if (lost) BrutColors.Clip else BrutColors.InkFaint)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.ltc_label, side), style = engraved(BrutType.Legend), color = BrutColors.InkDim)
        Spacer(Modifier.width(8.dp))
        Readout(tc ?: stringResource(R.string.ltc_none), color = if (tc != null) BrutColors.Accent else if (lost) BrutColors.Clip else BrutColors.InkDim)
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
        parts.joinToString("  ·  ") { "▸ $it" },
        style = engraved(BrutType.Legend),
        color = BrutColors.Accent,
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
 * Il lit l'état vivant : c'est le seul texte de la console qui change à chaque mesure.
 */
@Composable
private fun LiveClock(live: State<RecorderState>, recording: Boolean) {
    val seconds = live.value.elapsedSeconds
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
        style = BrutType.Clock.copy(color = if (recording) BrutColors.Ink else BrutColors.InkFaint),
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
    val motion = LocalMotionLevel.current
    val ms = if (motion.decorative) Motion.ENTER_MS else Motion.REDUCED_MS
    AnimatedVisibility(
        message != null, modifier,
        enter = if (motion.animates) fadeIn(tween(ms)) else EnterTransition.None,
        exit = if (motion.animates) fadeOut(tween(ms)) else ExitTransition.None,
    ) {
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
            is UserMessage.Saved -> if (m.ltcMissing) BrutColors.Lamp else BrutColors.Level
            is UserMessage.DeviceConnected, is UserMessage.Recovered -> BrutColors.Level
            UserMessage.CaptureResumed, is UserMessage.MarkerAdded, UserMessage.MonitorNeedsHeadphones -> BrutColors.Lamp
            else -> BrutColors.Clip
        }
        val edge = BrutColors.Edge
        Text(
            text,
            style = BrutType.Body,
            color = BrutColors.Ink,
            modifier = Modifier
                .padding(16.dp)
                .padding(bottom = 96.dp)
                .fillMaxWidth()
                .clip(BrutShapes.PlateShape)
                .background(BrutColors.PlateRaised)
                .border(1.dp, edge, BrutShapes.PlateShape)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .clickable(onClick = onDone)
                .drawBehind { drawRect(accent, Offset.Zero, size.copy(width = 4.dp.toPx())) }
                .padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}
