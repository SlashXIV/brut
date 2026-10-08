package com.gabrielifrim.brut.ui.console

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gabrielifrim.brut.R
import com.gabrielifrim.brut.audio.ChannelLevel
import com.gabrielifrim.brut.audio.MeterMode
import com.gabrielifrim.brut.ui.formatDb
import com.gabrielifrim.brut.ui.theme.BrutColors
import com.gabrielifrim.brut.ui.theme.BrutType

/**
 * Échelle IEC 60268-18 : linéaire par morceaux, de plus en plus dilatée vers le
 * haut. C'est là que se règle un niveau ; −60 et −50 n'ont pas besoin de place.
 */
fun meterPosition(db: Float): Float = when {
    db < -70f -> 0f
    db < -60f -> (db + 70f) * 0.25f
    db < -50f -> (db + 60f) * 0.5f + 2.5f
    db < -40f -> (db + 50f) * 0.75f + 7.5f
    db < -30f -> (db + 40f) * 1.5f + 15f
    db < -20f -> (db + 30f) * 2f + 30f
    db < 0f -> (db + 20f) * 2.5f + 50f
    else -> 100f
} / 100f

private val SCALE_MARKS = listOf(0, -3, -6, -9, -12, -18, -24, -30, -40, -50, -60)
private const val SEGMENTS = 48

private fun segmentColor(db: Float): Color = when {
    db >= -0.5f -> BrutColors.Red
    db >= -9f -> BrutColors.Amber
    else -> BrutColors.Green
}

/** Seuil de la n-ième LED, retrouvé en inversant l'échelle par dichotomie. */
private val SEGMENT_DB: FloatArray = FloatArray(SEGMENTS) { i ->
    val target = (i + 1f) / SEGMENTS
    var lo = -70f
    var hi = 0f
    repeat(30) {
        val mid = (lo + hi) / 2
        if (meterPosition(mid) < target) lo = mid else hi = mid
    }
    hi
}

/**
 * Pont de mesure : une barre de LED par canal, l'échelle au milieu, les voyants
 * CLIP au-dessus. Les LED suivent la crête ; le trait crème suit le RMS.
 */
@Composable
fun MeterBridge(
    levels: List<ChannelLevel>,
    channelLabels: List<String>,
    mode: MeterMode,
    onModeChange: (MeterMode) -> Unit,
    onResetClip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(BrutColors.Recess)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            levels.forEachIndexed { c, level ->
                ClipLamp(level, channelLabels[c], onResetClip)
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.weight(1f))
            ModeSwitch(mode, onModeChange)
        }
        when (mode) {
            MeterMode.PEAK -> Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                levels.forEachIndexed { c, level ->
                    if (c == 1) Scale(Modifier.width(44.dp).fillMaxHeight())
                    LedBar(level, Modifier.weight(1f).fillMaxHeight())
                }
                if (levels.size == 1) Scale(Modifier.width(44.dp).fillMaxHeight())
            }
            MeterMode.VU -> Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                levels.forEachIndexed { c, level ->
                    VuMeter(level, channelLabels[c], Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            levels.forEachIndexed { c, level -> Readouts(channelLabels[c], level) }
        }
    }
}

/** Commutateur à deux positions gravées : barres de crête ou aiguilles VU. */
@Composable
private fun ModeSwitch(mode: MeterMode, onChange: (MeterMode) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(BrutColors.Panel)
            .clickable(role = Role.Switch) { onChange(if (mode == MeterMode.PEAK) MeterMode.VU else MeterMode.PEAK) }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.meter_mode_peak), style = BrutType.Legend, color = if (mode == MeterMode.PEAK) BrutColors.Amber else BrutColors.CreamFaint)
        Text("  /  ", style = BrutType.Legend, color = BrutColors.CreamFaint)
        Text(stringResource(R.string.meter_mode_vu), style = BrutType.Legend, color = if (mode == MeterMode.VU) BrutColors.Amber else BrutColors.CreamFaint)
    }
}

/**
 * Voyant de saturation. Rouge : le fichier est écrêté. Ambre : c'est l'entrée
 * elle-même (le convertisseur) qui sature, avant le gain — baisser le gain n'y
 * changera rien, il faut baisser à la source.
 */
@Composable
private fun ClipLamp(level: ChannelLevel, label: String, onReset: () -> Unit) {
    val description = stringResource(R.string.clip_reset)
    val (lit, color, text) = when {
        level.clipped -> Triple(true, BrutColors.Red, stringResource(R.string.clip))
        level.inputClipped -> Triple(true, BrutColors.Amber, stringResource(R.string.clip_input))
        else -> Triple(false, BrutColors.Red, stringResource(R.string.clip))
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (lit) color else color.copy(alpha = 0.10f))
            .clickable(onClick = onReset)
            .semantics { contentDescription = description }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$text $label",
            style = BrutType.Legend,
            color = if (lit) BrutColors.Graphite else color.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun LedBar(level: ChannelLevel, modifier: Modifier) {
    Canvas(modifier.padding(horizontal = 6.dp)) {
        val gap = 2.dp.toPx()
        val segH = (size.height - gap * (SEGMENTS - 1)) / SEGMENTS
        val radius = CornerRadius(1.5.dp.toPx())
        val holdIndex = SEGMENT_DB.indexOfFirst { it > level.holdDb } - 1
        for (i in 0 until SEGMENTS) {
            val db = SEGMENT_DB[i]
            val top = size.height - (i + 1) * segH - i * gap
            val lit = level.peakDb >= db - 0.01f
            val base = segmentColor(db)
            val color = when {
                lit || i == holdIndex -> base
                else -> base.copy(alpha = 0.09f)
            }
            drawRoundRect(color, Offset(0f, top), Size(size.width, segH), radius)
            if (lit) {
                // Halo discret : une LED allumée éclaire un peu autour d'elle.
                drawRoundRect(
                    Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Transparent)),
                    Offset(0f, top), Size(size.width, segH), radius,
                )
            }
        }
        val rmsY = size.height * (1f - meterPosition(level.rmsDb))
        if (level.rmsDb > -70f) {
            drawRect(BrutColors.Cream, Offset(-3.dp.toPx(), rmsY - 1.dp.toPx()), Size(size.width + 6.dp.toPx(), 2.dp.toPx()))
        }
    }
}

@Composable
private fun Scale(modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val style = BrutType.ReadoutSmall.copy(color = BrutColors.CreamDim, textAlign = TextAlign.Center)
    Canvas(modifier) {
        SCALE_MARKS.forEach { mark ->
            val y = size.height * (1f - meterPosition(mark.toFloat()))
            val text = if (mark == 0) "0" else "−${-mark}"
            val layout = measurer.measure(text, style)
            drawText(layout, topLeft = Offset((size.width - layout.size.width) / 2, y - layout.size.height / 2))
            drawLine(BrutColors.CreamFaint, Offset(0f, y), Offset(4.dp.toPx(), y))
            drawLine(BrutColors.CreamFaint, Offset(size.width - 4.dp.toPx(), y), Offset(size.width, y))
        }
    }
}

@Composable
private fun Readouts(label: String, level: ChannelLevel) {
    val floor = stringResource(R.string.dbfs_floor)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = BrutType.Legend, color = BrutColors.Amber)
        ReadoutLine(stringResource(R.string.meter_peak), formatDb(level.holdDb) ?: floor)
        ReadoutLine(stringResource(R.string.meter_rms), formatDb(level.rmsDb) ?: floor)
        ReadoutLine(
            stringResource(R.string.meter_max), formatDb(level.maxDb) ?: floor,
            highlight = level.maxDb > -1f,
        )
    }
}

@Composable
private fun ReadoutLine(name: String, value: String, highlight: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = BrutType.ReadoutSmall, color = BrutColors.CreamFaint, modifier = Modifier.width(44.dp))
        Text(
            value,
            style = BrutType.Readout,
            color = if (highlight) BrutColors.Red else BrutColors.Cream,
            textAlign = TextAlign.End,
            modifier = Modifier.width(52.dp),
        )
    }
}

/** Témoin rond, façon lampe de console. */
@Composable
fun Lamp(color: Color, modifier: Modifier = Modifier, lit: Boolean = true) {
    Box(
        modifier
            .width(8.dp)
            .height(8.dp)
            .clip(RoundedCornerShape(50))
            .background(if (lit) color else color.copy(alpha = 0.2f)),
    )
}
