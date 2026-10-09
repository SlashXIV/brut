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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gabrielifrim.brut.R
import com.gabrielifrim.brut.audio.ChannelLevel
import com.gabrielifrim.brut.audio.LevelMeter
import com.gabrielifrim.brut.audio.LoudnessReading
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
    loudness: LoudnessReading = LoudnessReading(),
    spectrum: FloatArray? = null,
    spectrumCenters: FloatArray = FloatArray(0),
    onResetLoudness: () -> Unit = {},
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(BrutColors.Recess)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        // Au-delà de 2 voies : un voyant numéroté par voie, et pas de vu-mètres à aiguille
        // (huit cadrans sur un téléphone ne se liraient plus).
        val multi = levels.size > 2
        if (multi) {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                ModeSwitch(mode, onModeChange, allowVu = false)
            }
            Spacer(Modifier.height(8.dp))
            ClipStrip(levels, channelLabels, onResetClip)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                levels.forEachIndexed { c, level ->
                    ClipLamp(level, channelLabels[c], spokenChannel(c, levels.size), onResetClip)
                    Spacer(Modifier.width(8.dp))
                }
                Spacer(Modifier.weight(1f))
                ModeSwitch(mode, onModeChange)
            }
        }
        when (if (multi && mode == MeterMode.VU) MeterMode.PEAK else mode) {
            MeterMode.PEAK -> if (multi) {
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                ) {
                    Scale(Modifier.width(SCALE_WIDTH).fillMaxHeight())
                    levels.forEachIndexed { c, level ->
                        LedBar(level, spokenChannel(c, levels.size), Modifier.weight(1f).fillMaxHeight())
                    }
                }
            } else Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                levels.forEachIndexed { c, level ->
                    if (c == 1) Scale(Modifier.width(44.dp).fillMaxHeight())
                    LedBar(level, spokenChannel(c, levels.size), Modifier.weight(1f).fillMaxHeight())
                }
                if (levels.size == 1) Scale(Modifier.width(44.dp).fillMaxHeight())
            }
            MeterMode.LUFS -> LufsPanel(
                loudness, onResetLoudness,
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
            )
            MeterMode.SPECTRUM -> SpectrumView(
                spectrum, spectrumCenters,
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
            )
            MeterMode.VU -> Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                levels.forEachIndexed { c, level ->
                    VuMeter(level, channelLabels[c], Modifier.weight(1f).fillMaxWidth().vuSemantics(level, spokenChannel(c, levels.size)))
                }
            }
        }
        if (multi) {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(SCALE_WIDTH))
                levels.forEachIndexed { c, level ->
                    CompactReadout(channelLabels[c], spokenChannel(c, levels.size), level, Modifier.weight(1f))
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                levels.forEachIndexed { c, level -> Readouts(channelLabels[c], spokenChannel(c, levels.size), level) }
            }
        }
    }
}

/**
 * Commutateur d'affichage : un toucher passe à la position suivante, la position
 * active est en ambre, les autres restent lisibles pour savoir ce qui vient.
 */
@Composable
private fun ModeSwitch(mode: MeterMode, onChange: (MeterMode) -> Unit, allowVu: Boolean = true) {
    val labels = listOfNotNull(
        MeterMode.PEAK to stringResource(R.string.meter_mode_peak),
        (MeterMode.VU to stringResource(R.string.meter_mode_vu)).takeIf { allowVu },
        MeterMode.LUFS to stringResource(R.string.meter_mode_lufs),
        MeterMode.SPECTRUM to stringResource(R.string.meter_mode_spectrum),
    )
    val all = labels.map { it.first }
    val shown = if (mode in all) mode else MeterMode.PEAK
    val next = all[(all.indexOf(shown) + 1) % all.size]
    val name = stringResource(R.string.a11y_meter_mode)
    val current = labels.first { it.first == shown }.second
    val nextLabel = stringResource(R.string.a11y_switch_to, labels.first { it.first == next }.second)
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(BrutColors.Panel)
            .clickable(role = Role.Button, onClickLabel = nextLabel) { onChange(next) }
            .clearAndSetSemantics {
                contentDescription = name
                stateDescription = current
                onClick(nextLabel) { onChange(next); true }
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, (m, text) ->
            if (i > 0) Text(" \u00B7 ", style = BrutType.Legend, color = BrutColors.CreamFaint)
            Text(text, style = BrutType.Legend, color = if (m == shown) BrutColors.Amber else BrutColors.CreamDim)
        }
    }
}

/**
 * Voyant de saturation. Rouge : le fichier est écrêté. Ambre : c'est l'entrée
 * elle-même (le convertisseur) qui sature, avant le gain — baisser le gain n'y
 * changera rien, il faut baisser à la source.
 */
@Composable
private fun ClipLamp(level: ChannelLevel, label: String, spoken: String, onReset: () -> Unit) {
    val resetLabel = stringResource(R.string.clip_reset)
    val name = stringResource(R.string.a11y_clip, spoken)
    val state = stringResource(
        when {
            level.clipped -> R.string.a11y_clip_file
            level.inputClipped -> R.string.a11y_clip_input
            else -> R.string.a11y_clip_off
        },
    )
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
            // L'état est une région « vivante » : TalkBack annonce la saturation dès qu'elle survient.
            .clearAndSetSemantics {
                contentDescription = name
                stateDescription = state
                liveRegion = LiveRegionMode.Polite
                onClick(resetLabel) { onReset(); true }
            }
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

/** Voyants de saturation en bandeau : un par voie, numéroté. Toucher = effacer. */
@Composable
private fun ClipStrip(levels: List<ChannelLevel>, labels: List<String>, onReset: () -> Unit) {
    val resetLabel = stringResource(R.string.clip_reset)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.clip), style = BrutType.Legend, color = BrutColors.CreamDim, modifier = Modifier.width(SCALE_WIDTH - 4.dp))
        levels.forEachIndexed { c, level ->
            val name = stringResource(R.string.a11y_clip, spokenChannel(c, levels.size))
            val state = stringResource(
                when {
                    level.clipped -> R.string.a11y_clip_file
                    level.inputClipped -> R.string.a11y_clip_input
                    else -> R.string.a11y_clip_off
                },
            )
            val color = if (level.inputClipped && !level.clipped) BrutColors.Amber else BrutColors.Red
            val lit = level.clipped || level.inputClipped
            Box(
                Modifier
                    .weight(1f)
                    .height(22.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (lit) color else color.copy(alpha = 0.10f))
                    .clickable(onClick = onReset)
                    .clearAndSetSemantics {
                        contentDescription = name
                        stateDescription = state
                        liveRegion = LiveRegionMode.Polite
                        onClick(resetLabel) { onReset(); true }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(labels[c], style = BrutType.Legend, color = if (lit) BrutColors.Graphite else color.copy(alpha = 0.55f))
            }
        }
    }
}

/** Lecture d'une voie en multipiste : son numéro et sa crête, sous sa barre de LED. */
@Composable
private fun CompactReadout(label: String, spoken: String, level: ChannelLevel, modifier: Modifier) {
    val floor = stringResource(R.string.dbfs_floor)
    val summary = stringResource(R.string.a11y_levels, spoken, spokenDb(level.holdDb), spokenDb(level.rmsDb), spokenDb(level.maxDb))
    Column(modifier.clearAndSetSemantics { contentDescription = summary }, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = BrutType.Legend, color = BrutColors.Amber)
        Text(
            formatDb(level.holdDb)?.substringBefore(',')?.substringBefore('.') ?: floor,
            style = BrutType.ReadoutSmall,
            color = if (level.maxDb > -1f) BrutColors.Red else BrutColors.Cream,
            maxLines = 1,
        )
    }
}

private val SCALE_WIDTH = 34.dp

@Composable
private fun LedBar(level: ChannelLevel, spoken: String, modifier: Modifier) {
    val name = stringResource(R.string.a11y_peak_meter, spoken)
    val state = spokenDb(level.peakDb)
    Canvas(
        modifier
            .padding(horizontal = 6.dp)
            .clearAndSetSemantics {
                contentDescription = name
                stateDescription = state
            },
    ) {
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
    Canvas(modifier.clearAndSetSemantics {}) {
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
private fun Readouts(label: String, spoken: String, level: ChannelLevel) {
    val floor = stringResource(R.string.dbfs_floor)
    val summary = stringResource(R.string.a11y_levels, spoken, spokenDb(level.holdDb), spokenDb(level.rmsDb), spokenDb(level.maxDb))
    Column(
        Modifier.clearAndSetSemantics { contentDescription = summary },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = BrutType.Legend, color = BrutColors.Amber)
        ReadoutLine(stringResource(R.string.meter_peak), formatDb(level.holdDb, signed = true) ?: floor)
        ReadoutLine(stringResource(R.string.meter_rms), formatDb(level.rmsDb) ?: floor)
        ReadoutLine(
            stringResource(R.string.meter_max), formatDb(level.maxDb, signed = true) ?: floor,
            highlight = level.maxDb > -1f,
        )
    }
}

@Composable
private fun ReadoutLine(name: String, value: String, highlight: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = BrutType.ReadoutSmall, color = BrutColors.CreamDim, modifier = Modifier.width(44.dp))
        Text(
            value,
            style = BrutType.Readout,
            color = if (highlight) BrutColors.Red else BrutColors.Cream,
            textAlign = TextAlign.End,
            modifier = Modifier.width(52.dp),
        )
    }
}

/** Nom parlé d'une voie : « voie gauche », « voie droite », « mono ». */
@Composable
fun spokenChannel(index: Int, count: Int): String = stringResource(
    when {
        count == 1 -> R.string.a11y_channel_mono
        count > 2 -> R.string.a11y_channel_n
        index == 0 -> R.string.a11y_channel_left
        else -> R.string.a11y_channel_right
    },
    index + 1,
)

/** Niveau à lire à voix haute, arrondi au décibel : « −12 dBFS », ou « silence ». */
@Composable
fun spokenDb(db: Float, unit: String = "dBFS"): String {
    if (formatDb(db) == null) return stringResource(R.string.a11y_silence)
    val r = Math.round(db)
    return stringResource(R.string.a11y_db, if (r < 0) "−${-r}" else "$r", unit)
}

@Composable
private fun Modifier.vuSemantics(level: ChannelLevel, spoken: String): Modifier {
    val name = stringResource(R.string.a11y_vu_meter, spoken)
    val state = spokenDb(level.rmsDb - VU_REFERENCE_DBFS, "VU").takeIf { level.rmsDb > LevelMeter.FLOOR_DB } ?: stringResource(R.string.a11y_silence)
    return clearAndSetSemantics {
        contentDescription = name
        stateDescription = state
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

/** « MONO », « STÉRÉO » ou « 4 VOIES ». */
@Composable
fun channelsLabel(channels: Int): String = when (channels) {
    1 -> stringResource(R.string.format_short_mono)
    2 -> stringResource(R.string.format_short_stereo)
    else -> stringResource(R.string.format_short_channels, channels)
}
