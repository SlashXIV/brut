package com.gabrielifrim.brut.ui.console

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.Role
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
import com.gabrielifrim.brut.ui.formatDuration
import com.gabrielifrim.brut.ui.formatLongDuration
import com.gabrielifrim.brut.ui.formatRate
import com.gabrielifrim.brut.ui.theme.BrutColors
import com.gabrielifrim.brut.ui.theme.BrutType
import kotlinx.coroutines.delay

/** Actions de la console, regroupées pour garder l'écran indépendant du contrôleur. */
interface ConsoleActions {
    fun toggleRecording()
    fun selectDevice(id: Int)
    fun setFormat(format: com.gabrielifrim.brut.audio.AudioFormatSpec)
    fun setGain(channel: Int, db: Float)
    fun setGainLinked(linked: Boolean)
    fun resetClip()
    fun consumeMessage()
}

@Composable
fun ConsoleScreen(state: RecorderState, actions: ConsoleActions) {
    var showFormat by rememberSaveable { mutableStateOf(false) }
    var showSource by rememberSaveable { mutableStateOf(false) }
    val stereo = state.format.channels == 2
    val labels = if (stereo) {
        listOf(stringResource(R.string.channel_left), stringResource(R.string.channel_right))
    } else {
        listOf(stringResource(R.string.channel_mono))
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(BrutColors.Graphite)
            .safeDrawingPadding(),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Header(state, onFormat = { showFormat = true })
            Spacer(Modifier.height(10.dp))
            SourceStrip(state, onClick = { showSource = true })
            Warnings(state)
            Spacer(Modifier.height(10.dp))
            MeterBridge(
                levels = state.levels.take(state.format.channels),
                channelLabels = labels,
                onResetClip = actions::resetClip,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            GainRow(state, labels, actions)
            Spacer(Modifier.height(8.dp))
            Transport(state, actions)
        }

        MessageBar(state.message, actions::consumeMessage, Modifier.align(Alignment.BottomCenter))
    }

    if (showFormat) {
        FormatSheet(state.format, state.selectedDevice, state.isRecording, actions::setFormat) { showFormat = false }
    }
    if (showSource) {
        SourceSheet(
            state.devices, state.selectedDeviceId, state.capture?.routedDeviceId, state.isRecording,
            onSelect = { actions.selectDevice(it); showSource = false },
            onDismiss = { showSource = false },
        )
    }
}

@Composable
private fun Header(state: RecorderState, onFormat: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Le mot-symbole reprend l'icône : la lettre, puis le trait ambre.
        Column {
            Text(stringResource(R.string.app_name).uppercase(), style = BrutType.Title, color = BrutColors.Cream)
            Box(Modifier.width(34.dp).height(2.dp).background(BrutColors.Amber))
        }
        Spacer(Modifier.weight(1f))
        val f = state.format
        val depth = when (f.bitDepth) {
            BitDepth.PCM_16 -> "16"
            BitDepth.PCM_24 -> "24"
            BitDepth.FLOAT_32 -> "32F"
        }
        val channels = stringResource(if (f.channels == 2) R.string.format_short_stereo else R.string.format_short_mono)
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(BrutColors.Recess)
                .border(1.dp, BrutColors.Edge, RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onFormat)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${formatRate(f.sampleRate)} · $depth BIT · $channels", style = BrutType.Readout, color = BrutColors.Amber)
        }
    }
}

@Composable
private fun SourceStrip(state: RecorderState, onClick: () -> Unit) {
    val device = state.selectedDevice
    val capture = state.capture
    val lampColor = when {
        device == null -> BrutColors.CreamFaint
        state.isRerouted || capture?.silenced == true -> BrutColors.Red
        capture?.source == CaptureSource.MIC -> BrutColors.Amber
        capture != null -> BrutColors.Green
        else -> BrutColors.Amber
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BrutColors.Panel)
            .border(1.dp, BrutColors.Edge, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lamp(lampColor)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.source_title).uppercase(), style = BrutType.Legend, color = BrutColors.CreamDim)
                if (device != null) {
                    Text("  " + kindLabel(device.kind).uppercase(), style = BrutType.Legend, color = BrutColors.Amber)
                }
            }
            Text(
                if (device != null) deviceName(device) else stringResource(R.string.source_none),
                style = BrutType.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(captureLine(state), style = BrutType.ReadoutSmall, color = BrutColors.CreamDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", style = BrutType.Title, color = BrutColors.CreamDim)
    }
}

/** Ce qu'Android fait réellement de l'entrée, en une ligne. */
@Composable
private fun captureLine(state: RecorderState): String {
    val c = state.capture ?: return stringResource(R.string.capture_waiting)
    val parts = mutableListOf(
        stringResource(
            when (c.source) {
                CaptureSource.UNPROCESSED -> R.string.capture_unprocessed
                CaptureSource.VOICE_RECOGNITION -> R.string.capture_voice
                CaptureSource.MIC -> R.string.capture_mic
            },
        ),
        stringResource(if (c.encoding == CaptureEncoding.FLOAT) R.string.capture_float else R.string.capture_pcm16),
    )
    c.deviceSampleRate?.let { parts += stringResource(R.string.capture_device_rate, formatRate(it)) }
    if (c.activeEffects.isEmpty()) parts += stringResource(R.string.capture_no_effect)
    return parts.joinToString(" · ")
}

@Composable
private fun Warnings(state: RecorderState) {
    val c = state.capture
    val warnings = buildList {
        if (state.isRerouted) add(stringResource(R.string.warning_rerouted, c?.routedDeviceName.orEmpty()))
        if (c?.silenced == true) add(stringResource(R.string.warning_silenced))
        if (c?.source == CaptureSource.MIC) add(stringResource(R.string.warning_mic_source))
        if (!c?.activeEffects.isNullOrEmpty()) add(stringResource(R.string.warning_effects, c.activeEffects.joinToString()))
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
                .clip(RoundedCornerShape(10.dp))
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
private fun GainRow(state: RecorderState, labels: List<String>, actions: ConsoleActions) {
    val stereo = state.format.channels == 2
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BrutColors.Panel)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val gainLabel = stringResource(R.string.gain).uppercase()
        GainKnob(
            state.gainDb[0], { actions.setGain(0, it) },
            label = "$gainLabel ${labels[0]}",
            accessibilityLabel = "$gainLabel " + stringResource(if (stereo) R.string.channel_left_long else R.string.format_mono),
        )
        if (stereo) {
            LinkSwitch(state.gainLinked, actions::setGainLinked)
            GainKnob(
                state.gainDb[1], { actions.setGain(1, it) },
                label = "$gainLabel ${labels[1]}",
                accessibilityLabel = "$gainLabel " + stringResource(R.string.channel_right_long),
            )
        }
    }
}

@Composable
private fun Transport(state: RecorderState, actions: ConsoleActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Clock(state.elapsedSeconds, state.isRecording)
            val sub = when {
                state.isRecording && state.fileName != null -> stringResource(R.string.recording_to, state.fileName)
                state.remainingSeconds in 1..600 -> stringResource(R.string.remaining_low, formatLongDuration(state.remainingSeconds))
                else -> stringResource(R.string.remaining, formatLongDuration(state.remainingSeconds))
            }
            Text(sub, style = BrutType.ReadoutSmall, color = BrutColors.CreamDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RecordButton(
            recording = state.isRecording,
            enabled = state.selectedDevice != null && state.capture != null,
            onClick = actions::toggleRecording,
        )
    }
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
    BasicText(
        text,
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
            delay(if (message is UserMessage.RecordingStoppedDeviceLost) 7000 else 3500)
            onDone()
        }
    }
    AnimatedVisibility(message != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        val m = shown ?: return@AnimatedVisibility
        val text = when (m) {
            is UserMessage.Saved -> stringResource(R.string.msg_saved, m.name)
            is UserMessage.DeviceConnected -> stringResource(R.string.msg_device_connected, m.name)
            is UserMessage.DeviceLost -> stringResource(R.string.msg_device_lost, m.name)
            is UserMessage.RecordingStoppedDeviceLost -> stringResource(R.string.msg_stopped_device_lost, m.name, m.file)
            UserMessage.SizeLimit -> stringResource(R.string.msg_size_limit)
            UserMessage.WriteFailed -> stringResource(R.string.msg_write_failed)
            UserMessage.CaptureFailed -> stringResource(R.string.msg_capture_failed)
        }
        val accent = when (m) {
            is UserMessage.Saved, is UserMessage.DeviceConnected -> BrutColors.Green
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
                .clip(RoundedCornerShape(12.dp))
                .background(BrutColors.PanelRaised)
                .clickable(onClick = onDone)
                .drawBehind { drawRect(accent, Offset.Zero, size.copy(width = 4.dp.toPx())) }
                .padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}
