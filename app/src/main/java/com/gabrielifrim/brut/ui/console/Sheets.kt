package com.gabrielifrim.brut.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gabrielifrim.brut.R
import com.gabrielifrim.brut.audio.AudioFormatSpec
import com.gabrielifrim.brut.audio.BitDepth
import com.gabrielifrim.brut.audio.CaptureSource
import com.gabrielifrim.brut.audio.TakeOptions
import com.gabrielifrim.brut.ui.formatDb
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.gabrielifrim.brut.device.InputDevice
import com.gabrielifrim.brut.device.InputKind
import com.gabrielifrim.brut.ui.formatBytes
import com.gabrielifrim.brut.ui.formatRate
import com.gabrielifrim.brut.ui.theme.BrutColors
import com.gabrielifrim.brut.ui.theme.BrutType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrutSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = BrutColors.Panel,
        contentColor = BrutColors.Cream,
        scrimColor = BrutColors.Graphite.copy(alpha = 0.7f),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) { content() }
    }
}

@Composable
fun FormatSheet(
    format: AudioFormatSpec,
    device: InputDevice?,
    locked: Boolean,
    options: TakeOptions,
    headphones: Boolean,
    onChange: (AudioFormatSpec) -> Unit,
    onOptions: (TakeOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    BrutSheet(onDismiss) {
        Text(stringResource(R.string.take_settings_title), style = BrutType.Title)
        Text(stringResource(R.string.format_subtitle), style = BrutType.Body, color = BrutColors.CreamDim)
        if (locked) {
            Text(stringResource(R.string.format_locked), style = BrutType.Body, color = BrutColors.Amber, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(Modifier.height(16.dp))
        // Trois commutateurs à crans sur une même plaque, comme la face avant d'un enregistreur.
        RackPlate(Modifier.fillMaxWidth(), contentPadding = 8.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RotarySelector(
                    legend = stringResource(R.string.format_rate_unit),
                    options = AudioFormatSpec.SUPPORTED_SAMPLE_RATES,
                    selected = format.sampleRate,
                    label = { formatRate(it).removeSuffix(" kHz") },
                    onSelect = { onChange(format.copy(sampleRate = it)) },
                    enabled = !locked,
                    isNative = { device?.supportsRate(it) ?: true },
                )
                RotarySelector(
                    legend = stringResource(R.string.format_depth_unit),
                    options = BitDepth.entries,
                    selected = format.bitDepth,
                    label = {
                        when (it) {
                            BitDepth.PCM_16 -> "16"
                            BitDepth.PCM_24 -> "24"
                            BitDepth.FLOAT_32 -> "32F"
                        }
                    },
                    onSelect = { onChange(format.copy(bitDepth = it)) },
                    enabled = !locked,
                )
                val mono = stringResource(R.string.format_short_mono)
                val stereo = stringResource(R.string.format_short_stereo)
                RotarySelector(
                    legend = stringResource(R.string.format_channels),
                    options = listOf(1, 2),
                    selected = format.channels,
                    label = { if (it == 1) mono else stereo },
                    onSelect = { onChange(format.copy(channels = it)) },
                    enabled = !locked,
                    isNative = { device?.supportsChannels(it) ?: true },
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(
                when (format.bitDepth) {
                    BitDepth.PCM_16 -> R.string.format_hint_16
                    BitDepth.PCM_24 -> R.string.format_hint_24
                    BitDepth.FLOAT_32 -> R.string.format_hint_32f
                },
            ),
            style = BrutType.Body, color = BrutColors.CreamDim,
        )
        val anyForeign = device != null && (
            AudioFormatSpec.SUPPORTED_SAMPLE_RATES.any { !device.supportsRate(it) } || !device.supportsChannels(2)
        )
        if (anyForeign) {
            Text(stringResource(R.string.format_not_native), style = BrutType.Body, color = BrutColors.CreamFaint, modifier = Modifier.padding(top = 8.dp))
        }
        if (format.channels == 2 && device != null && !device.supportsChannels(2)) {
            Text(stringResource(R.string.warning_mono_input), style = BrutType.Body, color = BrutColors.Amber, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.format_data_rate, formatBytes(format.bytesPerSecond * 60)),
            style = BrutType.Readout, color = BrutColors.CreamDim,
        )

        ToolsSection(options, headphones, locked, onOptions)
    }
}

@Composable
fun SourceSheet(
    devices: List<InputDevice>,
    selectedId: Int?,
    routedId: Int?,
    captureMode: CaptureSource?,
    activeSource: CaptureSource?,
    unprocessedSupported: Boolean,
    locked: Boolean,
    onSelect: (Int) -> Unit,
    onCaptureMode: (CaptureSource?) -> Unit,
    onDismiss: () -> Unit,
) {
    BrutSheet(onDismiss) {
        Text(stringResource(R.string.source_pick_title), style = BrutType.Title)
        Text(stringResource(R.string.source_pick_hint), style = BrutType.Body, color = BrutColors.CreamDim)
        if (locked) {
            Text(stringResource(R.string.format_locked), style = BrutType.Body, color = BrutColors.Amber, modifier = Modifier.padding(top = 8.dp))
        }

        SheetLegend(stringResource(R.string.source_section_inputs))
        RackPlate(Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            Column {
                if (devices.isEmpty()) {
                    Text(stringResource(R.string.source_none), style = BrutType.Body, color = BrutColors.CreamDim, modifier = Modifier.padding(8.dp))
                }
                devices.forEachIndexed { i, device ->
                    if (i > 0) EngravedRule()
                    DeviceRow(
                        device = device,
                        selected = device.id == selectedId,
                        routed = device.id == routedId,
                        enabled = !locked,
                        onClick = { onSelect(device.id) },
                    )
                }
            }
        }

        SheetLegend(stringResource(R.string.capture_section))
        RackPlate(Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            Column {
                val modes = listOf<Triple<CaptureSource?, Int, Int>>(
                    Triple(null, R.string.capture_mode_auto, R.string.capture_mode_auto_hint),
                    Triple(CaptureSource.UNPROCESSED, R.string.capture_mode_raw, R.string.capture_mode_raw_hint),
                    Triple(CaptureSource.VOICE_RECOGNITION, R.string.capture_mode_voice, R.string.capture_mode_voice_hint),
                    Triple(CaptureSource.MIC, R.string.capture_mode_standard, R.string.capture_mode_standard_hint),
                )
                modes.forEachIndexed { i, (mode, title, hint) ->
                    if (i > 0) EngravedRule()
                    val available = mode != CaptureSource.UNPROCESSED || unprocessedSupported
                    RackRow(
                        selected = captureMode == mode,
                        lampColor = BrutColors.Amber,
                        enabled = !locked && available,
                        onClick = { onCaptureMode(mode) },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(title).uppercase(), style = engraved(BrutType.Legend), color = if (available) BrutColors.Cream else BrutColors.CreamFaint)
                            if (mode != null && mode == activeSource) {
                                Text("  \u00B7 " + stringResource(R.string.source_active), style = engraved(BrutType.Legend), color = BrutColors.Green)
                            }
                        }
                        Text(
                            stringResource(if (available) hint else R.string.capture_mode_raw_missing),
                            style = BrutType.Body, color = BrutColors.CreamDim,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetLegend(text: String) {
    Text(
        text.uppercase(),
        style = engraved(BrutType.Legend),
        color = BrutColors.CreamDim,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

/** Filet gravé entre deux rangées d'une même plaque. */
@Composable
private fun EngravedRule() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .drawBehind {
                drawLine(Color.Black.copy(alpha = 0.6f), Offset(0f, 0f), Offset(size.width, 0f), 1f)
                drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, 1.5f), Offset(size.width, 1.5f), 1f)
            },
    )
}

/** Rangée sélectionnable d'une plaque : une lampe témoin puis le contenu gravé. */
@Composable
private fun RackRow(
    selected: Boolean,
    lampColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .background(if (selected) BrutColors.Amber.copy(alpha = 0.07f) else Color.Transparent)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lamp(lampColor, lit = selected)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), content = content)
    }
}

@Composable
private fun DeviceRow(device: InputDevice, selected: Boolean, routed: Boolean, enabled: Boolean, onClick: () -> Unit) {
    RackRow(selected || routed, if (routed) BrutColors.Green else BrutColors.Amber, enabled, onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kindLabel(device.kind).uppercase(), style = engraved(BrutType.Legend), color = BrutColors.Amber)
            if (routed) {
                Text("  \u00B7 " + stringResource(R.string.source_active), style = engraved(BrutType.Legend), color = BrutColors.Green)
            }
        }
        Text(deviceName(device), style = engraved(BrutType.BodyStrong), color = if (selected) BrutColors.Cream else BrutColors.CreamDim)
        Text(deviceDetails(device), style = BrutType.ReadoutSmall, color = BrutColors.CreamDim)
        if (device.kind == InputKind.BLUETOOTH) {
            Text(stringResource(R.string.device_bluetooth_warning), style = BrutType.Body, color = BrutColors.Amber)
        }
    }
}

@Composable
fun kindLabel(kind: InputKind): String = stringResource(
    when (kind) {
        InputKind.USB -> R.string.kind_usb
        InputKind.WIRED -> R.string.kind_wired
        InputKind.BUILTIN -> R.string.kind_builtin
        InputKind.BLUETOOTH -> R.string.kind_bluetooth
        InputKind.OTHER -> R.string.kind_other
    },
)

/** Le micro interne porte souvent le nom du téléphone : on le dit plutôt clairement. */
@Composable
fun deviceName(device: InputDevice): String = when {
    device.kind == InputKind.BUILTIN && device.address.isNotBlank() -> "${kindLabel(device.kind)} · ${device.address}"
    device.productName.isBlank() -> kindLabel(device.kind)
    else -> device.productName
}

@Composable
private fun deviceDetails(device: InputDevice): String {
    val channels = device.channelCounts.maxOrNull()
        ?.let { stringResource(R.string.device_channels, it.toString()) }
        ?: stringResource(R.string.device_channels_any)
    val rates = if (device.sampleRates.isEmpty()) {
        stringResource(R.string.device_rates_any)
    } else {
        // Une plage plutôt que la liste complète : certains pilotes en annoncent une dizaine.
        val lo = device.sampleRates.first()
        val hi = device.sampleRates.last()
        if (lo == hi) formatRate(lo) else "${formatRate(lo).removeSuffix(" kHz")} – ${formatRate(hi)}"
    }
    return "$channels · $rates"
}

/**
 * Outils de prise. Chaque commutateur a une position « OFF » : rien n'est actif par
 * défaut, l'enregistrement reste brut tant qu'on ne demande rien.
 */
@Composable
private fun ToolsSection(options: TakeOptions, headphones: Boolean, locked: Boolean, onOptions: (TakeOptions) -> Unit) {
    val off = stringResource(R.string.tools_off)
    Spacer(Modifier.height(22.dp))
    Text(stringResource(R.string.tools_title), style = BrutType.Title)
    Spacer(Modifier.height(12.dp))
    RackPlate(Modifier.fillMaxWidth(), contentPadding = 8.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RotarySelector(
                legend = stringResource(R.string.tools_preroll),
                options = TakeOptions.PREROLL_CHOICES,
                selected = options.prerollSeconds,
                label = { if (it == 0) off else "$it s" },
                onSelect = { onOptions(options.copy(prerollSeconds = it)) },
                enabled = !locked,
            )
            val safetyChoices = listOf<Float?>(null) + TakeOptions.SAFETY_CHOICES
            RotarySelector(
                legend = stringResource(R.string.tools_safety),
                options = safetyChoices,
                selected = if (options.safetyTrack) options.safetyDb else null,
                label = { it?.let { db -> "−${(-db).toInt()}" } ?: off },
                onSelect = { v -> onOptions(if (v == null) options.copy(safetyTrack = false) else options.copy(safetyTrack = true, safetyDb = v)) },
                enabled = !locked,
            )
            val triggerChoices = listOf<Float?>(null) + TakeOptions.TRIGGER_CHOICES
            RotarySelector(
                legend = stringResource(R.string.tools_trigger),
                options = triggerChoices,
                selected = if (options.trigger) options.triggerDb else null,
                label = { it?.let { db -> "−${(-db).toInt()}" } ?: off },
                onSelect = { v -> onOptions(if (v == null) options.copy(trigger = false) else options.copy(trigger = true, triggerDb = v)) },
                enabled = !locked,
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(BrutColors.Recess)
            .clickable(enabled = !locked, role = Role.Switch) { onOptions(options.copy(monitor = !options.monitor)) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lamp(BrutColors.Amber, lit = options.monitor)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.tools_monitor).uppercase(), style = engraved(BrutType.Legend), color = BrutColors.Cream)
            Text(
                stringResource(if (headphones) R.string.tools_monitor_hint else R.string.warning_monitor_no_headphones),
                style = BrutType.Body, color = if (headphones) BrutColors.CreamDim else BrutColors.Amber,
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    listOf(
        R.string.tools_preroll_hint,
        R.string.tools_safety_hint,
        R.string.tools_trigger_hint,
    ).forEach {
        Text(stringResource(it), style = BrutType.Body, color = BrutColors.CreamDim, modifier = Modifier.padding(top = 4.dp))
    }
}
