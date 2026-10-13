package com.gabrielifrim.brut.ui.console

import com.gabrielifrim.brut.ui.component.engraved
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gabrielifrim.brut.R
import com.gabrielifrim.brut.audio.LoudnessReading
import com.gabrielifrim.brut.ui.formatDb
import com.gabrielifrim.brut.ui.theme.BrutColors
import com.gabrielifrim.brut.ui.theme.BrutShapes
import com.gabrielifrim.brut.ui.theme.BrutType
import kotlin.math.ln
import kotlin.math.max

private fun lufsText(value: Float): String =
    if (value <= LoudnessReading.SILENCE) "—" else formatDb(value, signed = true) ?: "—"

/**
 * Sonie EBU R128 : instantanée (400 ms), court terme (3 s), intégrée depuis le début
 * de la prise (ou la remise à zéro), et crête vraie maximale. La barre situe le court
 * terme par rapport aux cibles usuelles : −23 (diffusion EBU), −16 et −14 (écoute en ligne).
 */
@Composable
fun LufsPanel(loudness: LoudnessReading, onReset: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.weight(1f))
        LufsRow(stringResource(R.string.lufs_momentary), loudness.momentary, "LUFS", BrutColors.Ink)
        LufsRow(stringResource(R.string.lufs_short), loudness.shortTerm, "LUFS", BrutColors.Ink)
        LufsRow(stringResource(R.string.lufs_integrated), loudness.integrated, "LUFS", BrutColors.Accent, big = true)
        LufsRow(
            stringResource(R.string.lufs_true_peak), loudness.truePeakMax, "dBTP",
            if (loudness.truePeakMax > -1f) BrutColors.Clip else BrutColors.Ink,
        )
        // Les cibles se lisent déjà dans les valeurs : la barre est un repère visuel.
        LoudnessBar(loudness.shortTerm, Modifier.fillMaxWidth().height(58.dp).clearAndSetSemantics {})
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.lufs_reset).uppercase(),
            style = engraved(BrutType.Legend),
            color = BrutColors.Accent,
            modifier = Modifier
                .align(Alignment.End)
                .heightIn(min = 32.dp)
                .clip(BrutShapes.InsetShape)
                .background(BrutColors.Plate)
                .clickable(role = Role.Button, onClick = onReset)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun LufsRow(label: String, reading: Float, unit: String, color: Color, big: Boolean = false) {
    val value = lufsText(reading)
    val spoken = if (reading <= LoudnessReading.SILENCE) {
        stringResource(R.string.a11y_no_reading)
    } else {
        stringResource(R.string.a11y_db, formatDb(reading, signed = true) ?: value, unit)
    }
    Row(Modifier.clearAndSetSemantics { contentDescription = "$label : $spoken" }, verticalAlignment = Alignment.Bottom) {
        Text(label.uppercase(), style = engraved(BrutType.Legend), color = BrutColors.InkDim, modifier = Modifier.weight(1f))
        Text(
            value,
            style = if (big) BrutType.Clock.copy(fontSize = 34.sp) else BrutType.Readout.copy(fontSize = 22.sp),
            color = color,
            textAlign = TextAlign.End,
        )
        Text(" $unit", style = BrutType.ReadoutSmall, color = BrutColors.InkDim)
    }
}

private val TARGETS = listOf(-23f, -16f, -14f)

@Composable
private fun LoudnessBar(shortTerm: Float, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val style = BrutType.ReadoutSmall.copy(color = BrutColors.InkDim)
    val p = BrutColors.palette
    val value = rememberUpdatedState(shortTerm)
    Canvas(
        modifier.drawWithCache {
            val lo = -40f
            val hi = 0f
            fun x(v: Float) = ((v - lo) / (hi - lo)).coerceIn(0f, 1f) * size.width
            val barH = 10.dp.toPx()
            val top = 16.dp.toPx()
            // Repères des cibles mesurés une fois ; −16 et −14 sont voisins : l'un au-dessus, l'autre dessous.
            val targets = TARGETS.map { t ->
                val layout = measurer.measure("−${(-t).toInt()}", style)
                val y = if (t == -14f) top - layout.size.height - 4.dp.toPx() else top + barH + 4.dp.toPx()
                Triple(x(t), layout, Offset(x(t) - layout.size.width / 2, y))
            }
            onDrawBehind {
                val v = value.value
                drawRect(p.display, Offset(0f, top), Size(size.width, barH))
                if (v > lo) {
                    val color = when {
                        v > -9f -> p.clip
                        v > -18f -> p.lamp
                        else -> p.level
                    }
                    drawRect(color, Offset(0f, top), Size(x(v), barH))
                }
                targets.forEach { (tx, layout, at) ->
                    drawLine(p.ink, Offset(tx, top - 2.dp.toPx()), Offset(tx, top + barH + 2.dp.toPx()), 1.5.dp.toPx())
                    drawText(layout, topLeft = at)
                }
            }
        },
    ) {}
}

private val FREQ_LABELS = listOf(50f to "50", 100f to "100", 200f to "200", 500f to "500", 1000f to "1k", 2000f to "2k", 5000f to "5k", 10_000f to "10k")

/**
 * Spectre par 1/6 d'octave, échelle de fréquences logarithmique de 20 Hz à 20 kHz.
 * Les barres retombent doucement : on lit un équilibre tonal, pas un scintillement.
 */
@Composable
fun SpectrumView(levels: FloatArray?, centers: FloatArray, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val held = remember(centers.size) { FloatArray(centers.size) { -100f } }
    // La retombée avance une fois par nouvelle mesure, pas à chaque recomposition.
    val bars = remember(levels) {
        if (levels != null && levels.size == held.size) {
            for (i in held.indices) held[i] = max(levels[i], held[i] - 2.5f)
        }
        held
    }
    val fresh = rememberUpdatedState(levels)
    val style = BrutType.ReadoutSmall.copy(color = BrutColors.InkDim)
    val idleStyle = BrutType.Legend.copy(color = BrutColors.InkFaint)
    val p = BrutColors.palette
    // Pour TalkBack, le spectre se résume à sa bande la plus forte.
    val name = stringResource(R.string.a11y_spectrum)
    val loudest = bars.indices.maxByOrNull { bars[it] }
    val silent = loudest == null || bars[loudest] <= -90f
    val idle = stringResource(R.string.spectrum_idle)
    val state = if (silent) {
        stringResource(R.string.a11y_silence)
    } else {
        val f = centers[loudest]
        val freq = if (f >= 1000f) stringResource(R.string.a11y_khz, formatDb(f / 1000f) ?: "") else stringResource(R.string.a11y_hz, f.toInt())
        stringResource(R.string.a11y_spectrum_peak, freq, spokenDb(bars[loudest]))
    }
    Canvas(
        modifier
            .fillMaxSize()
            .clearAndSetSemantics {
                contentDescription = name
                stateDescription = state
            }
            .drawWithCache {
                val labelH = 16.dp.toPx()
                val h = size.height - labelH
                val fMin = 20.0
                val fMax = 20_000.0
                fun x(f: Float) = (ln(f / fMin) / ln(fMax / fMin)).toFloat().coerceIn(0f, 1f) * size.width
                fun y(db: Float) = h * (1f - ((db + 90f) / 90f).coerceIn(0f, 1f))
                // Grille et libellés : mesurés une fois par taille.
                val grid = listOf(-20f, -40f, -60f).map { db -> y(db) to measurer.measure("−${(-db).toInt()}", style) }
                val freqs = FREQ_LABELS.map { (f, t) -> measurer.measure(t, style).let { it to Offset(x(f) - it.size.width / 2, size.height - it.size.height) } }
                val idleLayout = measurer.measure(idle, idleStyle)
                val xs = FloatArray(centers.size) { x(centers[it]) }
                val barW = if (centers.isEmpty()) 0f else size.width / centers.size * 0.72f
                val line = p.inkFaint.copy(alpha = 0.6f)
                val faintLine = p.inkFaint.copy(alpha = 0.35f)
                onDrawBehind {
                    fresh.value // abonne le dessin aux nouvelles mesures
                    // Grille : 0 (plafond), −20, −40, −60 dBFS, et le plancher sur lequel posent les barres.
                    drawLine(line, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f)
                    drawLine(line, Offset(0f, h), Offset(size.width, h), 1f)
                    if (bars.all { it <= -90f }) {
                        drawText(idleLayout, topLeft = Offset((size.width - idleLayout.size.width) / 2, h / 2 - idleLayout.size.height / 2))
                    }
                    grid.forEach { (gy, l) ->
                        drawLine(faintLine, Offset(0f, gy), Offset(size.width, gy), 1f)
                        drawText(l, topLeft = Offset(2.dp.toPx(), gy - l.size.height))
                    }
                    for (i in xs.indices) {
                        val db = bars.getOrElse(i) { -100f }
                        val top = y(db)
                        val color = when {
                            db > -6f -> p.clip
                            db > -18f -> p.lamp
                            else -> p.level
                        }
                        drawRect(color, Offset(xs[i] - barW / 2, top), Size(barW, h - top))
                    }
                    freqs.forEach { (l, at) -> drawText(l, topLeft = at) }
                }
            },
    ) {}
}
