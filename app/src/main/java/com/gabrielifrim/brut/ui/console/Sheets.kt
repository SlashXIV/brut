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
    onChange: (AudioFormatSpec) -> Unit,
    onDismiss: () -> Unit,
) {
    BrutSheet(onDismiss) {
        Text(stringResource(R.string.format_title), style = BrutType.Title)
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
    }
}

@Composable
fun SourceSheet(
    devices: List<InputDevice>,
    selectedId: Int?,
    routedId: Int?,
    locked: Boolean,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    BrutSheet(onDismiss) {
        Text(stringResource(R.string.source_pick_title), style = BrutType.Title)
        Text(stringResource(R.string.source_pick_hint), style = BrutType.Body, color = BrutColors.CreamDim)
        if (locked) {
            Text(stringResource(R.string.format_locked), style = BrutType.Body, color = BrutColors.Amber, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(12.dp))
        if (devices.isEmpty()) {
            Text(stringResource(R.string.source_none), style = BrutType.Body, color = BrutColors.CreamDim)
        }
        devices.forEach { device ->
            DeviceRow(
                device = device,
                selected = device.id == selectedId,
                routed = device.id == routedId,
                enabled = !locked,
                onClick = { onSelect(device.id) },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DeviceRow(device: InputDevice, selected: Boolean, routed: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) BrutColors.PanelRaised else BrutColors.Recess)
            .border(1.dp, if (selected) BrutColors.Amber.copy(alpha = 0.6f) else BrutColors.Edge, shape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lamp(if (routed) BrutColors.Green else BrutColors.Amber, lit = selected || routed)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(kindLabel(device.kind).uppercase(), style = BrutType.Legend, color = BrutColors.Amber)
                if (routed) {
                    Text("  · " + stringResource(R.string.source_active), style = BrutType.Legend, color = BrutColors.Green)
                }
            }
            Text(deviceName(device), style = BrutType.BodyStrong)
            Text(deviceDetails(device), style = BrutType.ReadoutSmall, color = BrutColors.CreamDim)
            if (device.kind == InputKind.BLUETOOTH) {
                Text(stringResource(R.string.device_bluetooth_warning), style = BrutType.Body, color = BrutColors.Amber)
            }
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
        device.sampleRates.joinToString(" / ") { formatRate(it) }
    }
    return "$channels · $rates"
}
