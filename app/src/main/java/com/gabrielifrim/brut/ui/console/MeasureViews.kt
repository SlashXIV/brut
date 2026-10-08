package com.gabrielifrim.brut.ui.console

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gabrielifrim.brut.R
import com.gabrielifrim.brut.audio.LoudnessReading
import com.gabrielifrim.brut.ui.formatDb
import com.gabrielifrim.brut.ui.theme.BrutColors
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
        LufsRow(stringResource(R.string.lufs_momentary), lufsText(loudness.momentary), "LUFS", BrutColors.Cream)
        LufsRow(stringResource(R.string.lufs_short), lufsText(loudness.shortTerm), "LUFS", BrutColors.Cream)
        LufsRow(stringResource(R.string.lufs_integrated), lufsText(loudness.integrated), "LUFS", BrutColors.Amber, big = true)
        LufsRow(
            stringResource(R.string.lufs_true_peak), lufsText(loudness.truePeakMax), "dBTP",
            if (loudness.truePeakMax > -1f) BrutColors.Red else BrutColors.Cream,
        )
        LoudnessBar(loudness.shortTerm, Modifier.fillMaxWidth().height(58.dp))
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.lufs_reset).uppercase(),
            style = engraved(BrutType.Legend),
            color = BrutColors.Amber,
            modifier = Modifier
                .align(Alignment.End)
                .clip(RoundedCornerShape(4.dp))
                .background(BrutColors.Panel)
                .clickable(role = Role.Button, onClick = onReset)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun LufsRow(label: String, value: String, unit: String, color: Color, big: Boolean = false) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(label.uppercase(), style = engraved(BrutType.Legend), color = BrutColors.CreamDim, modifier = Modifier.weight(1f))
        Text(
            value,
            style = if (big) BrutType.Clock.copy(fontSize = 34.sp) else BrutType.Readout.copy(fontSize = 22.sp),
            color = color,
            textAlign = TextAlign.End,
        )
        Text(" $unit", style = BrutType.ReadoutSmall, color = BrutColors.CreamDim)
    }
}

private val TARGETS = listOf(-23f, -16f, -14f)

@Composable
private fun LoudnessBar(shortTerm: Float, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val style = BrutType.ReadoutSmall.copy(color = BrutColors.CreamDim)
    Canvas(modifier) {
        val lo = -40f
        val hi = 0f
        fun x(v: Float) = ((v - lo) / (hi - lo)).coerceIn(0f, 1f) * size.width
        val barH = 10.dp.toPx()
        translate(top = 16.dp.toPx()) {
        drawRect(Color(0xFF0B0A08), Offset(0f, 0f), Size(size.width, barH))
        if (shortTerm > lo) {
            val color = when {
                shortTerm > -9f -> BrutColors.Red
                shortTerm > -18f -> BrutColors.Amber
                else -> BrutColors.Green
            }
            drawRect(color, Offset(0f, 0f), Size(x(shortTerm), barH))
        }
        TARGETS.forEach { t ->
            drawLine(BrutColors.Cream, Offset(x(t), -2.dp.toPx()), Offset(x(t), barH + 2.dp.toPx()), 1.5.dp.toPx())
            val layout = measurer.measure("−${(-t).toInt()}", style)
            // −16 et −14 sont voisins : l'un au-dessus de la barre, l'autre dessous.
            val y = if (t == -14f) -layout.size.height - 4.dp.toPx() else barH + 4.dp.toPx()
            drawText(layout, topLeft = Offset(x(t) - layout.size.width / 2, y))
        }
        }
    }
}

/**
 * Spectre par 1/6 d'octave, échelle de fréquences logarithmique de 20 Hz à 20 kHz.
 * Les barres retombent doucement : on lit un équilibre tonal, pas un scintillement.
 */
@Composable
fun SpectrumView(levels: FloatArray?, centers: FloatArray, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val held = remember(centers.size) { FloatArray(centers.size) { -100f } }
    if (levels != null && levels.size == held.size) {
        for (i in held.indices) held[i] = max(levels[i], held[i] - 2.5f)
    }
    val style = BrutType.ReadoutSmall.copy(color = BrutColors.CreamDim)
    Canvas(modifier.fillMaxSize()) {
        val labelH = 16.dp.toPx()
        val h = size.height - labelH
        val fMin = 20.0
        val fMax = 20_000.0
        fun x(f: Float) = (ln(f / fMin) / ln(fMax / fMin)).toFloat().coerceIn(0f, 1f) * size.width
        fun y(db: Float) = h * (1f - ((db + 90f) / 90f).coerceIn(0f, 1f))
        // Grille : −20, −40, −60 dBFS.
        for (db in listOf(-20f, -40f, -60f)) {
            drawLine(BrutColors.CreamFaint.copy(alpha = 0.35f), Offset(0f, y(db)), Offset(size.width, y(db)), 1f)
            val l = measurer.measure("−${(-db).toInt()}", style)
            drawText(l, topLeft = Offset(2.dp.toPx(), y(db) - l.size.height))
        }
        if (centers.isNotEmpty()) {
            val barW = size.width / centers.size * 0.72f
            for (i in centers.indices) {
                val db = held.getOrElse(i) { -100f }
                val cx = x(centers[i])
                val top = y(db)
                val color = when {
                    db > -6f -> BrutColors.Red
                    db > -18f -> BrutColors.Amber
                    else -> BrutColors.Green
                }
                drawRect(color, Offset(cx - barW / 2, top), Size(barW, h - top))
            }
        }
        listOf(50f to "50", 100f to "100", 200f to "200", 500f to "500", 1000f to "1k", 2000f to "2k", 5000f to "5k", 10_000f to "10k").forEach { (f, t) ->
            val l = measurer.measure(t, style)
            drawText(l, topLeft = Offset(x(f) - l.size.width / 2, size.height - l.size.height))
        }
    }
}
