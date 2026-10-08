package com.gabrielifrim.brut.ui.console

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gabrielifrim.brut.audio.ChannelLevel
import com.gabrielifrim.brut.ui.theme.BrutColors
import com.gabrielifrim.brut.ui.theme.BrutFonts
import com.gabrielifrim.brut.ui.theme.BrutType
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Alignement EBU R68 : 0 VU correspond à −18 dBFS. */
const val VU_REFERENCE_DBFS = -18f

private const val VU_MIN = -20f
private const val VU_MAX = 3f
private const val SWEEP_DEG = 96f
private val VU_MARKS = listOf(-20, -10, -7, -5, -3, -2, -1, 0, 1, 2, 3)

/**
 * Un vrai cadran VU suit la tension, pas les décibels : la graduation est linéaire
 * en amplitude, d'où des repères serrés à gauche et espacés près de 0 VU.
 */
private fun vuFraction(vu: Float): Float {
    val lin = { v: Float -> 10f.pow(v / 20f) }
    val v = vu.coerceIn(VU_MIN - 2f, VU_MAX + 0.5f)
    return (lin(v) - lin(VU_MIN)) / (lin(VU_MAX) - lin(VU_MIN))
}

/**
 * Vu-mètre à aiguille, cadran rétroéclairé façon console. L'aiguille suit le RMS
 * (300 ms) avec un ressort légèrement amorti : elle monte franchement et dépasse
 * à peine, comme un galvanomètre. La LED PEAK s'allume au-delà de −3 dBFS en crête.
 */
@Composable
fun VuMeter(level: ChannelLevel, label: String, modifier: Modifier = Modifier) {
    val target = vuFraction(level.rmsDb - VU_REFERENCE_DBFS)
    val needle by animateFloatAsState(target, spring(dampingRatio = 0.78f, stiffness = 120f), label = "aiguille")
    val measurer = rememberTextMeasurer()
    val peakLit = level.holdDb > -3f

    Canvas(modifier) {
        val r = CornerRadius(4.dp.toPx())
        // Boîtier encastré puis cadran rétroéclairé : chaud au centre, assombri aux bords.
        drawRoundRect(Color(0xFF0B0A08), cornerRadius = r)
        val inset = 5.dp.toPx()
        val faceSize = Size(size.width - 2 * inset, size.height - 2 * inset)
        drawRoundRect(
            Brush.radialGradient(
                listOf(Color(0xFFF4E3B5), Color(0xFFE2C27F), Color(0xFFB08A4C)),
                center = Offset(size.width / 2, size.height * 0.85f),
                radius = size.width * 0.75f,
            ),
            Offset(inset, inset), faceSize, CornerRadius(2.dp.toPx()),
        )

        // Géométrie calculée sur la place réelle : l'arc ET ses chiffres doivent tenir
        // en hauteur comme en largeur, quel que soit le format du cadran.
        val labelSpace = 26.dp.toPx()
        val halfSweep = Math.toRadians(SWEEP_DEG / 2.0)
        // Comme sur un vrai VU, le pivot peut se trouver SOUS le cadran : l'arc s'étale
        // alors sur toute la largeur au lieu de rester un petit demi-cercle.
        val top = inset + 6.dp.toPx() + labelSpace
        val bottomRoom = size.height - inset - 8.dp.toPx()
        // Marge latérale : les coins bas portent le canal (G/D) et la LED PEAK.
        val byWidth = (size.width / 2 - inset - 64.dp.toPx()) / sin(halfSweep).toFloat() - labelSpace
        val byHeight = (bottomRoom - top) / (1f - cos(halfSweep).toFloat())
        val radius = minOf(byWidth, byHeight).coerceAtLeast(24.dp.toPx())
        val pivot = Offset(size.width / 2, top + radius)
        // Les textes suivent la taille du cadran, dans des bornes lisibles.
        val k = (radius / 120.dp.toPx()).coerceIn(0.72f, 1.15f)
        val scaleStyle = BrutType.ReadoutSmall.copy(fontFamily = BrutFonts.Engraving, fontSize = 11.sp * k)
        // Petit cadran : on ne chiffre que les repères principaux pour qu'ils ne se chevauchent pas.
        val labelled = if (radius < 110.dp.toPx()) setOf(-20, -10, -5, -3, 0, 3) else VU_MARKS.toSet()
        fun angleOf(f: Float) = Math.toRadians((-90f - SWEEP_DEG / 2 + SWEEP_DEG * f).toDouble())
        fun pointAt(f: Float, rr: Float) = angleOf(f).let { Offset(pivot.x + rr * cos(it).toFloat(), pivot.y + rr * sin(it).toFloat()) }

        clipRect(inset, inset, size.width - inset, size.height - inset) {
        val ink = Color(0xFF231C14)
        val red = Color(0xFFB4291D)
        // Arc de l'échelle, rouge au-delà de 0 VU.
        val arcTopLeft = Offset(pivot.x - radius, pivot.y - radius)
        val zero = vuFraction(0f)
        drawArc(ink, -90f - SWEEP_DEG / 2, SWEEP_DEG * zero, false, arcTopLeft, Size(radius * 2, radius * 2), style = Stroke(1.5.dp.toPx()))
        drawArc(red, -90f - SWEEP_DEG / 2 + SWEEP_DEG * zero, SWEEP_DEG * (1 - zero), false, arcTopLeft, Size(radius * 2, radius * 2), style = Stroke(4.dp.toPx()))

        VU_MARKS.forEach { mark ->
            val f = vuFraction(mark.toFloat())
            val color = if (mark > 0) red else ink
            drawLine(color, pointAt(f, radius), pointAt(f, radius + 8.dp.toPx()), strokeWidth = 1.5.dp.toPx())
            val text = when {
                mark > 0 -> "+$mark"
                mark < 0 -> "${-mark}"
                else -> "0"
            }
            if (mark !in labelled) return@forEach
            val layout = measurer.measure(text, scaleStyle.copy(color = color))
            val p = pointAt(f, radius + 17.dp.toPx())
            drawText(layout, topLeft = Offset(p.x - layout.size.width / 2, p.y - layout.size.height / 2))
        }

        // Légendes du cadran.
        // Sur un tout petit cadran, l'inscription chevaucherait l'échelle : on s'en passe.
        if (radius >= 90.dp.toPx()) {
            val vu = measurer.measure("VU", BrutType.Title.copy(color = ink, fontSize = 20.sp * k))
            val vuY = minOf(pivot.y - radius * 0.55f, size.height - inset - 8.dp.toPx() - vu.size.height / 2)
            drawText(vu, topLeft = Offset(size.width / 2 - vu.size.width / 2, vuY - vu.size.height / 2))
        }
        val ch = measurer.measure(label, BrutType.Legend.copy(color = ink))
        drawText(ch, topLeft = Offset(inset + 8.dp.toPx(), size.height - inset - ch.size.height - 4.dp.toPx()))

        // LED PEAK en bas à droite du cadran.
        val led = Offset(size.width - inset - 14.dp.toPx(), size.height - inset - 12.dp.toPx())
        if (peakLit) drawCircle(BrutColors.Red.copy(alpha = 0.35f), 9.dp.toPx(), led)
        drawCircle(if (peakLit) BrutColors.Red else Color(0xFF5A2620), 4.dp.toPx(), led)
        val peak = measurer.measure("PEAK", BrutType.Legend.copy(color = ink, fontSize = 9.sp))
        drawText(peak, topLeft = Offset(led.x - peak.size.width - 8.dp.toPx(), led.y - peak.size.height / 2))

        // Aiguille : fine, noire, avec un contrepoids sous le cadran.
        val tip = pointAt(needle, radius + 6.dp.toPx())
        val base = pointAt(needle, radius * 0.18f)
        drawLine(Color.Black.copy(alpha = 0.18f), base + Offset(2.dp.toPx(), 2.dp.toPx()), tip + Offset(2.dp.toPx(), 2.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
        drawLine(Color(0xFF15100B), base, tip, 1.6.dp.toPx(), StrokeCap.Round)
        }
        // Cache-pivot sombre en bas du cadran.
        drawRect(
            Brush.verticalGradient(listOf(Color(0xFF2A241E), Color(0xFF14110E))),
            Offset(inset, size.height - inset - 2.dp.toPx()), Size(faceSize.width, 2.dp.toPx()),
        )
    }
}
