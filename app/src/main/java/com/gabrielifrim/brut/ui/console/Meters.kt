package com.gabrielifrim.brut.ui.console

import com.gabrielifrim.brut.ui.component.recess
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
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
import com.gabrielifrim.brut.ui.theme.BrutPalette
import com.gabrielifrim.brut.ui.theme.legibleOn
import com.gabrielifrim.brut.ui.theme.BrutShapes
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

private fun BrutPalette.segmentColor(db: Float): Color = when {
    db >= -0.5f -> clip
    db >= -9f -> lamp
    else -> level
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
) = BoxWithConstraints(modifier) {
    // Pont de mesure bas (téléphone en portrait) : en mode VU, les chiffres laissent leur place aux
    // cadrans, sinon deux aiguilles de 40 dp ne se liraient plus. Ils restent en mode Crête.
    val roomy = maxHeight >= 330.dp
    Column(
        Modifier
            .fillMaxSize()
            .recess()
            .clip(BrutShapes.PlateShape)
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
            // Une ligne d'ordinaire ; à grande police, le commutateur passe dessous au lieu d'être écrasé.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                levels.forEachIndexed { c, level ->
                    ClipLamp(level, channelLabels[c], spokenChannel(c, levels.size), onResetClip)
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
        } else if (mode != MeterMode.VU || roomy) {
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
            .heightIn(min = 32.dp)
            .clip(BrutShapes.InsetShape)
            .background(BrutColors.Plate)
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
            if (i > 0) Text(" \u00B7 ", style = BrutType.Legend, color = BrutColors.InkFaint, maxLines = 1, softWrap = false)
            Text(text, style = BrutType.Legend, color = if (m == shown) BrutColors.Accent else BrutColors.InkDim, maxLines = 1, softWrap = false)
        }
    }
}

/** Verre d'un voyant éteint : neutre, pour qu'un CLIP au repos ne se lise pas comme une alarme. */
private val unlitLens: Color
    @Composable get() = BrutColors.palette.let { if (it.isDark) it.highlight.copy(alpha = 0.045f) else it.shadow.copy(alpha = 0.06f) }

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
        level.clipped -> Triple(true, BrutColors.Clip, stringResource(R.string.clip))
        level.inputClipped -> Triple(true, BrutColors.Lamp, stringResource(R.string.clip_input))
        else -> Triple(false, BrutColors.Clip, stringResource(R.string.clip))
    }
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .clip(BrutShapes.InsetShape)
            .background(if (lit) color else unlitLens)
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
            // Éteint, le voyant est neutre : un CLIP au repos ne doit pas se lire comme une alarme.
            color = if (lit) legibleOn(color) else BrutColors.InkFaint,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Voyants de saturation en bandeau : un par voie, numéroté. Toucher = effacer. */
@Composable
private fun ClipStrip(levels: List<ChannelLevel>, labels: List<String>, onReset: () -> Unit) {
    val resetLabel = stringResource(R.string.clip_reset)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.clip), style = BrutType.Legend, color = BrutColors.InkDim, modifier = Modifier.width(SCALE_WIDTH - 4.dp))
        levels.forEachIndexed { c, level ->
            val name = stringResource(R.string.a11y_clip, spokenChannel(c, levels.size))
            val state = stringResource(
                when {
                    level.clipped -> R.string.a11y_clip_file
                    level.inputClipped -> R.string.a11y_clip_input
                    else -> R.string.a11y_clip_off
                },
            )
            val color = if (level.inputClipped && !level.clipped) BrutColors.Lamp else BrutColors.Clip
            val lit = level.clipped || level.inputClipped
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 24.dp)
                    .clip(BrutShapes.InsetShape)
                    .background(if (lit) color else unlitLens)
                    .clickable(onClick = onReset)
                    .clearAndSetSemantics {
                        contentDescription = name
                        stateDescription = state
                        liveRegion = LiveRegionMode.Polite
                        onClick(resetLabel) { onReset(); true }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(labels[c], style = BrutType.Legend, color = if (lit) legibleOn(color) else BrutColors.InkFaint, maxLines = 1)
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
        Text(label, style = BrutType.Legend, color = BrutColors.Accent)
        Text(
            formatDb(level.holdDb)?.substringBefore(',')?.substringBefore('.') ?: floor,
            style = BrutType.ReadoutSmall,
            color = if (level.maxDb > -1f) BrutColors.Clip else BrutColors.Ink,
            maxLines = 1,
        )
    }
}

private val SCALE_WIDTH = 34.dp

@Composable
private fun LedBar(level: ChannelLevel, spoken: String, modifier: Modifier) {
    val name = stringResource(R.string.a11y_peak_meter, spoken)
    val state = spokenDb(level.peakDb)
    val p = BrutColors.palette
    // Lu au dessin seulement : le cache ci-dessous survit d'une image à l'autre.
    val current = rememberUpdatedState(level)
    Canvas(
        modifier
            .padding(horizontal = 6.dp)
            .clearAndSetSemantics {
                contentDescription = name
                stateDescription = state
            }
            .drawWithCache {
                // Ce qui ne dépend que de la taille et de la palette est préparé une fois :
                // le dessin de chaque image ne crée plus aucun objet.
                val gap = 2.dp.toPx()
                val segH = (size.height - gap * (SEGMENTS - 1)) / SEGMENTS
                val segSize = Size(size.width, segH)
                val radius = CornerRadius(1.5.dp.toPx())
                val halo = Brush.horizontalGradient(listOf(p.highlight.copy(alpha = if (p.isDark) 0.18f else 0.35f), Color.Transparent), 0f, size.width)
                val colors = Array(SEGMENTS) { p.segmentColor(SEGMENT_DB[it]) }
                val unlitAlpha = if (p.isDark) 0.07f else 0.09f
                val rmsOffset = 3.dp.toPx()
                val rmsSize = Size(size.width + 2 * rmsOffset, 2.dp.toPx())
                onDrawBehind {
                    val level = current.value
                    val holdIndex = SEGMENT_DB.indexOfFirst { it > level.holdDb } - 1
                    for (i in 0 until SEGMENTS) {
                        val top = size.height - (i + 1) * segH - i * gap
                        val lit = level.peakDb >= SEGMENT_DB[i] - 0.01f
                        val base = colors[i]
                        drawRoundRect(if (lit || i == holdIndex) base else base.copy(alpha = unlitAlpha), Offset(0f, top), segSize, radius)
                        // Halo discret : une LED allumée éclaire un peu autour d'elle.
                        if (lit) drawRoundRect(halo, Offset(0f, top), segSize, radius)
                    }
                    if (level.rmsDb > -70f) {
                        val rmsY = size.height * (1f - meterPosition(level.rmsDb))
                        drawRect(p.ink, Offset(-rmsOffset, rmsY - rmsSize.height / 2), rmsSize)
                    }
                }
            },
    ) {}
}

@Composable
private fun Scale(modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val style = BrutType.ReadoutSmall.copy(color = BrutColors.InkDim, textAlign = TextAlign.Center)
    val tick = BrutColors.InkFaint
    Canvas(
        modifier
            .clearAndSetSemantics {}
            // Graduations mesurées une fois par taille, pas à chaque image.
            .drawWithCache {
                // Sur un pont de mesure bas, les chiffres voisins se chevaucheraient (0 / −3 / −6,
                // −40 / −50 / −60) : un repère trop proche du précédent garde son trait, pas son chiffre.
                var lastY = Float.NEGATIVE_INFINITY
                val marks = SCALE_MARKS.map { mark ->
                    val y = size.height * (1f - meterPosition(mark.toFloat()))
                    val layout = measurer.measure(if (mark == 0) "0" else "−${-mark}", style)
                    val fits = y - lastY >= layout.size.height * 0.9f
                    if (fits) lastY = y
                    (layout.takeIf { fits }) to y
                }
                val tickLen = 4.dp.toPx()
                onDrawBehind {
                    marks.forEach { (layout, y) ->
                        layout?.let { drawText(it, topLeft = Offset((size.width - it.size.width) / 2, y - it.size.height / 2)) }
                        drawLine(tick, Offset(0f, y), Offset(tickLen, y))
                        drawLine(tick, Offset(size.width - tickLen, y), Offset(size.width, y))
                    }
                }
            },
    ) {}
}

@Composable
private fun Readouts(label: String, spoken: String, level: ChannelLevel) {
    val floor = stringResource(R.string.dbfs_floor)
    val summary = stringResource(R.string.a11y_levels, spoken, spokenDb(level.holdDb), spokenDb(level.rmsDb), spokenDb(level.maxDb))
    Column(
        Modifier.clearAndSetSemantics { contentDescription = summary },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = BrutType.Legend, color = BrutColors.Accent)
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
        // Largeurs minimales, pas fixes : à grande police, la ligne s'élargit au lieu de couper les mots.
        Text(name, style = BrutType.ReadoutSmall, color = BrutColors.InkDim, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 44.dp))
        Text(
            value,
            style = BrutType.Readout,
            color = if (highlight) BrutColors.Clip else BrutColors.Ink,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.widthIn(min = 52.dp),
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
            .clip(BrutShapes.Lens)
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
