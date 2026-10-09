package com.gabrielifrim.brut.ui.library

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gabrielifrim.brut.R
import com.gabrielifrim.brut.audio.AudioFormatSpec
import com.gabrielifrim.brut.audio.BitDepth
import com.gabrielifrim.brut.audio.ChannelPick
import com.gabrielifrim.brut.audio.ExportSpec
import com.gabrielifrim.brut.audio.LevelMeter
import com.gabrielifrim.brut.audio.WavExport
import com.gabrielifrim.brut.audio.WavInfo
import com.gabrielifrim.brut.library.PlayerState
import com.gabrielifrim.brut.library.Take
import com.gabrielifrim.brut.library.TrimState
import com.gabrielifrim.brut.library.Waveform
import com.gabrielifrim.brut.library.depthName
import com.gabrielifrim.brut.ui.console.ActionButton
import com.gabrielifrim.brut.ui.console.RackPlate
import com.gabrielifrim.brut.ui.console.Readout
import com.gabrielifrim.brut.ui.console.RotarySelector
import com.gabrielifrim.brut.ui.console.engraved
import com.gabrielifrim.brut.ui.console.meterPosition
import com.gabrielifrim.brut.ui.formatDuration
import com.gabrielifrim.brut.ui.formatRate
import com.gabrielifrim.brut.ui.theme.BrutColors
import com.gabrielifrim.brut.ui.theme.BrutType
import kotlin.math.abs

/**
 * Édition d'une prise : on choisit une sélection entre deux poignées IN et OUT, on
 * l'écoute, puis on l'exporte (ou on la découpe aux repères) dans de nouveaux fichiers.
 */
@Composable
fun TrimPanel(
    take: Take,
    info: WavInfo,
    trim: TrimState,
    waveform: Waveform?,
    player: PlayerState,
    exportProgress: Float?,
    recording: Boolean,
    actions: LibraryActions,
) {
    val busy = exportProgress != null
    val rate = info.sampleRate.toDouble()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TrimWaveform(info, trim, waveform, player.fraction, enabled = !busy, onSeek = actions::seek, onTrim = actions::setTrim)
        Row(verticalAlignment = Alignment.Bottom) {
            Bound(stringResource(R.string.trim_in), formatDuration(trim.start / rate))
            Spacer(Modifier.weight(1f))
            Bound(stringResource(R.string.trim_length), formatDuration(trim.frames / rate), Alignment.CenterHorizontally, BrutColors.Cream)
            Spacer(Modifier.weight(1f))
            Bound(stringResource(R.string.trim_out), formatDuration(trim.end / rate), Alignment.End)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(stringResource(R.string.trim_in_here), BrutColors.Amber, Modifier.weight(1f), enabled = !busy) {
                actions.setTrim(player.positionFrames, trim.end)
            }
            ActionButton(stringResource(R.string.trim_all), BrutColors.Cream, Modifier.weight(1f), enabled = !busy) {
                actions.setTrim(0, info.frames)
            }
            ActionButton(stringResource(R.string.trim_out_here), BrutColors.Amber, Modifier.weight(1f), enabled = !busy) {
                actions.setTrim(trim.start, player.positionFrames)
            }
        }
        PlaybackRow(take, player, enabled = !recording && !busy, actions)
        Text(stringResource(R.string.trim_hint), style = BrutType.Body, color = BrutColors.CreamDim)
        ExportControls(take, info, trim, exportProgress, recording, actions)
    }
}

@Composable
private fun Bound(legend: String, value: String, align: Alignment.Horizontal = Alignment.Start, color: Color = BrutColors.Amber) {
    Column(horizontalAlignment = align) {
        Text(legend.uppercase(), style = engraved(BrutType.Legend), color = BrutColors.CreamDim)
        Readout(value, color = color)
    }
}

/**
 * Forme d'onde de la prise entière, la sélection en clair et le reste assombri. On saisit
 * la poignée la plus proche du doigt ; loin des poignées, glisser ou toucher = se placer.
 * Une poignée lâchée près d'un repère s'y aimante : découper au repère près est immédiat.
 */
@Composable
private fun TrimWaveform(
    info: WavInfo,
    trim: TrimState,
    waveform: Waveform?,
    fraction: Float,
    enabled: Boolean,
    onSeek: (Float) -> Unit,
    onTrim: (Long, Long) -> Unit,
) {
    val description = stringResource(R.string.trim_waveform)
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(trim)
    val total = info.frames.toFloat()
    val markers = info.markers.map { it.frame }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xFF0B0A08))
            .semantics { contentDescription = description }
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { onSeek(it.x / size.width) }
            }
            .pointerInput(enabled, info) {
                if (!enabled) return@pointerInput
                val grab = 28.dp.toPx()
                val snap = 12.dp.toPx()
                // 0 = rien, 1 = IN, 2 = OUT, 3 = tête de lecture
                var handle = 0
                fun frameAt(x: Float): Long {
                    val f = (x / size.width).coerceIn(0f, 1f) * total
                    val nearest = markers.minByOrNull { abs(it - f) }
                    return if (nearest != null && abs(nearest - f) / total * size.width < snap) nearest else f.toLong()
                }
                detectHorizontalDragGestures(
                    onDragStart = { pos ->
                        val inX = current.start / total * size.width
                        val outX = current.end / total * size.width
                        val dIn = abs(pos.x - inX)
                        val dOut = abs(pos.x - outX)
                        handle = when {
                            dIn <= grab && dIn <= dOut -> 1
                            dOut <= grab -> 2
                            else -> 3
                        }
                        if (handle != 3) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    },
                ) { change, _ ->
                    change.consume()
                    val x = change.position.x
                    when (handle) {
                        1 -> onTrim(frameAt(x), current.end)
                        2 -> onTrim(current.start, frameAt(x))
                        else -> onSeek(x / size.width)
                    }
                }
            },
    ) {
        val mid = size.height / 2
        val inX = trim.start / total * size.width
        val outX = trim.end / total * size.width
        drawLine(BrutColors.CreamFaint.copy(alpha = 0.4f), Offset(0f, mid), Offset(size.width, mid), 1f)
        waveform?.peaks?.let { peaks ->
            val step = size.width / peaks.size
            peaks.forEachIndexed { i, p ->
                val h = meterPosition(LevelMeter.toDb(p)) * (mid - 2.dp.toPx())
                val x = i * step
                val inside = x + step >= inX && x <= outX
                val color = when {
                    p >= LevelMeter.CLIP_LINEAR -> BrutColors.Red
                    inside -> BrutColors.Amber
                    else -> BrutColors.CreamFaint
                }
                drawRect(color, Offset(x, mid - h), Size(maxOf(step - 1f, 1f), h * 2))
            }
        }
        // Hors sélection : voilé, comme un fader à fond.
        drawRect(Color.Black.copy(alpha = 0.55f), Offset.Zero, Size(inX, size.height))
        drawRect(Color.Black.copy(alpha = 0.55f), Offset(outX, 0f), Size(size.width - outX, size.height))
        markers.forEach { m ->
            val mx = m / total * size.width
            drawLine(BrutColors.Cream.copy(alpha = 0.5f), Offset(mx, 0f), Offset(mx, size.height), 1.dp.toPx())
        }
        // Poignées : un trait et une languette tournée vers l'intérieur de la sélection.
        val tab = Size(10.dp.toPx(), 18.dp.toPx())
        drawLine(BrutColors.Amber, Offset(inX, 0f), Offset(inX, size.height), 2.dp.toPx())
        drawRect(BrutColors.Amber, Offset(inX, size.height - tab.height), tab)
        drawLine(BrutColors.Amber, Offset(outX, 0f), Offset(outX, size.height), 2.dp.toPx())
        drawRect(BrutColors.Amber, Offset(outX - tab.width, size.height - tab.height), tab)
        val x = fraction * size.width
        drawLine(BrutColors.Cream, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
    }
}

/** Format de sortie, puis exporter la sélection ou la découper aux repères. */
@Composable
private fun ExportControls(take: Take, info: WavInfo, trim: TrimState, exportProgress: Float?, recording: Boolean, actions: LibraryActions) {
    val context = LocalContext.current
    val source = WavExport.sourceDepth(info)
    // Brut ne sait pas réécrire un 32 bit entier tel quel : « origine » n'est alors pas proposée.
    val depthChoices: List<BitDepth?> = (if (source != null) listOf(null) else emptyList()) + BitDepth.entries
    var depthIndex by rememberSaveable(take.key) { mutableIntStateOf(0) }
    var channelIndex by rememberSaveable(take.key) { mutableIntStateOf(0) }
    var rateIndex by rememberSaveable(take.key) { mutableIntStateOf(0) }
    // La fréquence de la prise n'est pas un choix : c'est « ORIG. ».
    val rateChoices: List<Int?> = listOf<Int?>(null) + AudioFormatSpec.SUPPORTED_SAMPLE_RATES.filter { it != info.sampleRate }
    val rate = rateChoices[rateIndex.coerceIn(0, rateChoices.lastIndex)]
    val depth = depthChoices[depthIndex.coerceIn(0, depthChoices.lastIndex)]
    val channelChoices: List<ChannelPick> = if (info.channels == 1) {
        listOf(ChannelPick.All)
    } else {
        listOf(ChannelPick.All) + (0 until info.channels).map { ChannelPick.One(it) } + ChannelPick.Separate
    }
    val channels = channelChoices[channelIndex.coerceIn(0, channelChoices.lastIndex)]
    val single = (channels as? ChannelPick.One)?.index
    // Le format s'apprécie sur un des fichiers : en « séparées », chacun est une voie seule.
    val spec = ExportSpec(trim.start, trim.end, depth, single ?: if (channels == ChannelPick.Separate) 0 else null, rate)
    val segments = remember(info, trim.start, trim.end) { WavExport.segments(info, trim.start, trim.end).size }
    val busy = exportProgress != null
    val enabled = !busy && !recording

    Text(stringResource(R.string.export_title).uppercase(), style = engraved(BrutType.Legend), color = BrutColors.CreamDim)
    RackPlate(Modifier.fillMaxWidth(), contentPadding = 6.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            val original = stringResource(R.string.export_original)
            RotarySelector(
                legend = stringResource(R.string.export_depth),
                options = depthChoices,
                selected = depth,
                label = { d ->
                    when {
                        d == null -> original
                        d.isFloat -> "32F"
                        else -> "${d.bits}"
                    }
                },
                onSelect = { depthIndex = depthChoices.indexOf(it) },
                enabled = enabled,
                knobSize = 40.dp,
            )
            RotarySelector(
                legend = stringResource(R.string.export_rate),
                options = rateChoices,
                selected = rate,
                label = { r -> r?.let { formatRate(it).removeSuffix(" kHz") } ?: original },
                onSelect = { rateIndex = rateChoices.indexOf(it) },
                enabled = enabled,
                knobSize = 40.dp,
            )
            if (info.channels >= 2) {
                val both = stringResource(if (info.channels == 2) R.string.export_both else R.string.export_all)
                val left = stringResource(R.string.export_left)
                val right = stringResource(R.string.export_right)
                val separate = stringResource(R.string.export_separate)
                RotarySelector(
                    legend = stringResource(R.string.export_channels),
                    options = channelChoices,
                    selected = channels,
                    label = { pick ->
                        when (pick) {
                            ChannelPick.All -> both
                            ChannelPick.Separate -> separate
                            is ChannelPick.One -> when {
                                info.channels > 2 -> "${pick.index + 1}"
                                pick.index == 0 -> left
                                else -> right
                            }
                        }
                    },
                    onSelect = { channelIndex = channelChoices.indexOf(it) },
                    enabled = enabled,
                    knobSize = 40.dp,
                )
            }
        }
    }
    val hints = buildList {
        val target = WavExport.targetFormat(info, spec)
        if (WavExport.isBitExact(info, spec)) {
            add(stringResource(R.string.export_hint_exact))
        } else {
            if (WavExport.resamples(info, spec)) add(stringResource(R.string.export_hint_resample, formatRate(target.sampleRate)))
            if (target.bitDepth != WavExport.sourceDepth(info)) {
                add(stringResource(R.string.export_hint_convert, depthName(context, target.bitDepth)))
            }
            if (!target.bitDepth.isFloat && (info.isFloat || WavExport.resamples(info, spec))) add(stringResource(R.string.export_hint_clip))
        }
        when {
            channels == ChannelPick.Separate -> add(stringResource(R.string.export_hint_separate, info.channels))
            single != null && info.channels == 2 -> {
                val name = stringResource(if (single == 0) R.string.channel_left_name else R.string.channel_right_name)
                add(stringResource(R.string.export_hint_channel, name.lowercase()))
            }
            single != null -> add(stringResource(R.string.export_hint_channel_n, single + 1))
        }
        add(stringResource(R.string.export_hint_original))
    }
    hints.forEach { Text(it, style = BrutType.Body, color = BrutColors.CreamDim) }
    if (recording) Text(stringResource(R.string.export_busy), style = BrutType.Body, color = BrutColors.Amber)

    if (exportProgress != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Readout(stringResource(R.string.export_running, (exportProgress * 100).toInt()))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(BrutColors.Recess)) {
                    Box(Modifier.fillMaxWidth(exportProgress.coerceIn(0f, 1f)).height(4.dp).background(BrutColors.Amber))
                }
            }
            Spacer(Modifier.width(12.dp))
            ActionButton(stringResource(R.string.export_cancel), BrutColors.Red, Modifier.width(110.dp), onClick = actions::cancelExport)
        }
    } else {
        // L'un sous l'autre : les libellés restent entiers sur les écrans étroits.
        ActionButton(stringResource(R.string.export_selection), BrutColors.Amber, Modifier.fillMaxWidth(), enabled = enabled) {
            actions.export(take, depth, channels, rate, split = false)
        }
        if (segments > 1) {
            ActionButton(stringResource(R.string.export_split, segments), BrutColors.Amber, Modifier.fillMaxWidth(), enabled = enabled) {
                actions.export(take, depth, channels, rate, split = true)
            }
        }
        ActionButton(stringResource(R.string.export_close), BrutColors.CreamDim, Modifier.fillMaxWidth(), onClick = actions::endTrim)
    }
}

/** Lecture, boucle et position : partagés entre la plaque normale et l'édition. */
@Composable
fun PlaybackRow(take: Take, player: PlayerState, enabled: Boolean, actions: LibraryActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlayButton(player.playing, enabled = take.info != null && enabled, onClick = actions::togglePlay)
        Spacer(Modifier.width(12.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .toggleable(value = player.loop, role = Role.Switch, onValueChange = actions::setLoop)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.gabrielifrim.brut.ui.console.Lamp(BrutColors.Amber, lit = player.loop)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.library_loop).uppercase(), style = engraved(BrutType.Legend), color = if (player.loop) BrutColors.Cream else BrutColors.CreamDim)
        }
        Spacer(Modifier.weight(1f))
        Readout(
            formatDuration(player.positionSeconds, withHundredths = false) + " / " +
                formatDuration(take.info?.durationSeconds ?: 0.0, withHundredths = false),
            color = BrutColors.Cream,
        )
    }
}
