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
private fun SectionLegend(text: String) {
    Text(text.uppercase(), style = BrutType.Legend, color = BrutColors.CreamDim, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
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

        SectionLegend(stringResource(R.string.format_rate))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AudioFormatSpec.SUPPORTED_SAMPLE_RATES.forEach { rate ->
                PadButton(
                    formatRate(rate), format.sampleRate == rate, !locked,
                    { onChange(format.copy(sampleRate = rate)) }, Modifier.weight(1f),
                )
            }
        }

        SectionLegend(stringResource(R.string.format_depth))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(BitDepth.PCM_16 to R.string.format_16, BitDepth.PCM_24 to R.string.format_24, BitDepth.FLOAT_32 to R.string.format_32f)
                .forEach { (depth, label) ->
                    PadButton(
                        stringResource(label), format.bitDepth == depth, !locked,
                        { onChange(format.copy(bitDepth = depth)) }, Modifier.weight(1f),
                    )
                }
        }
        Text(
            stringResource(
                when (format.bitDepth) {
                    BitDepth.PCM_16 -> R.string.format_hint_16
                    BitDepth.PCM_24 -> R.string.format_hint_24
                    BitDepth.FLOAT_32 -> R.string.format_hint_32f
                },
            ),
            style = BrutType.Body, color = BrutColors.CreamDim, modifier = Modifier.padding(top = 8.dp),
        )

        SectionLegend(stringResource(R.string.format_channels))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadButton(stringResource(R.string.format_mono), format.channels == 1, !locked, { onChange(format.copy(channels = 1)) }, Modifier.weight(1f))
            PadButton(stringResource(R.string.format_stereo), format.channels == 2, !locked, { onChange(format.copy(channels = 2)) }, Modifier.weight(1f))
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
