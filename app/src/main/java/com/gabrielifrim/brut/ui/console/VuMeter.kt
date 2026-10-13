package com.gabrielifrim.brut.ui.console

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gabrielifrim.brut.R
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

/** Le cadran est un objet : le même papier crème et la même aiguille dans toutes les palettes. */
private object Dial {
    val Housing = Color(0xFF0B0A08)
    val FaceLight = Color(0xFFF4E3B5)
    val FaceMid = Color(0xFFE2C27F)
    val FaceEdge = Color(0xFFB08A4C)
    val Ink = Color(0xFF231C14)
    val Red = Color(0xFFB4291D)
    val Needle = Color(0xFF15100B)
    val LedOff = Color(0xFF5A2620)
    val CoverTop = Color(0xFF2A241E)
    val CoverBottom = Color(0xFF14110E)
}

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
 * Le ressort reste actif quel que soit le niveau de mouvement : c'est une mesure.
 */
@Composable
fun VuMeter(level: ChannelLevel, label: String, modifier: Modifier = Modifier) {
    val needle = animateFloatAsState(
        vuFraction(level.rmsDb - VU_REFERENCE_DBFS), spring(dampingRatio = 0.78f, stiffness = 120f), label = "aiguille",
    )
    val peakLit = rememberUpdatedState(level.holdDb > -3f)
    val measurer = rememberTextMeasurer()
    val vuText = stringResource(R.string.meter_mode_vu)
    val peakText = stringResource(R.string.vu_peak)
    val clip = BrutColors.Clip

    // Tout le cadran est préparé une fois par taille ; chaque image ne redessine que l'aiguille et la LED.
    Canvas(
        modifier.drawWithCache {
            val inset = 5.dp.toPx()
            val faceSize = Size(size.width - 2 * inset, size.height - 2 * inset)
            val face = Brush.radialGradient(
                listOf(Dial.FaceLight, Dial.FaceMid, Dial.FaceEdge),
                center = Offset(size.width / 2, size.height * 0.85f),
                radius = size.width * 0.75f,
            )
            val cover = Brush.verticalGradient(listOf(Dial.CoverTop, Dial.CoverBottom))

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

            val zero = vuFraction(0f)
            val arcTopLeft = Offset(pivot.x - radius, pivot.y - radius)
            val arcSize = Size(radius * 2, radius * 2)
            val ticks = VU_MARKS.map { mark ->
                val f = vuFraction(mark.toFloat())
                Triple(pointAt(f, radius), pointAt(f, radius + 8.dp.toPx()), if (mark > 0) Dial.Red else Dial.Ink)
            }
            val numbers = VU_MARKS.filter { it in labelled }.map { mark ->
                val text = if (mark > 0) "+$mark" else "${-mark}"
                val layout = measurer.measure(text, scaleStyle.copy(color = if (mark > 0) Dial.Red else Dial.Ink))
                val p = pointAt(vuFraction(mark.toFloat()), radius + 17.dp.toPx())
                layout to Offset(p.x - layout.size.width / 2, p.y - layout.size.height / 2)
            }
            // Sur un tout petit cadran, l'inscription chevaucherait l'échelle : on s'en passe.
            val vu = if (radius >= 90.dp.toPx()) measurer.measure(vuText, BrutType.Title.copy(color = Dial.Ink, fontSize = 20.sp * k)) else null
            val vuY = vu?.let { minOf(pivot.y - radius * 0.55f, size.height - inset - 8.dp.toPx() - it.size.height / 2) - it.size.height / 2 } ?: 0f
            val ch = measurer.measure(label, BrutType.Legend.copy(color = Dial.Ink))
            val led = Offset(size.width - inset - 14.dp.toPx(), size.height - inset - 12.dp.toPx())
            val peak = measurer.measure(peakText, BrutType.Legend.copy(color = Dial.Ink, fontSize = 9.sp))
            val shadowShift = Offset(2.dp.toPx(), 2.dp.toPx())

            onDrawBehind {
                // Boîtier encastré puis cadran rétroéclairé : chaud au centre, assombri aux bords.
                drawRoundRect(Dial.Housing, cornerRadius = CornerRadius(4.dp.toPx()))
                drawRoundRect(face, Offset(inset, inset), faceSize, CornerRadius(2.dp.toPx()))
                clipRect(inset, inset, size.width - inset, size.height - inset) {
                    // Arc de l'échelle, rouge au-delà de 0 VU.
                    drawArc(Dial.Ink, -90f - SWEEP_DEG / 2, SWEEP_DEG * zero, false, arcTopLeft, arcSize, style = Stroke(1.5.dp.toPx()))
                    drawArc(Dial.Red, -90f - SWEEP_DEG / 2 + SWEEP_DEG * zero, SWEEP_DEG * (1 - zero), false, arcTopLeft, arcSize, style = Stroke(4.dp.toPx()))
                    ticks.forEach { (a, b, color) -> drawLine(color, a, b, strokeWidth = 1.5.dp.toPx()) }
                    numbers.forEach { (layout, at) -> drawText(layout, topLeft = at) }
                    vu?.let { drawText(it, topLeft = Offset(size.width / 2 - it.size.width / 2, vuY)) }
                    drawText(ch, topLeft = Offset(inset + 8.dp.toPx(), size.height - inset - ch.size.height - 4.dp.toPx()))

                    // LED PEAK en bas à droite du cadran.
                    val lit = peakLit.value
                    if (lit) drawCircle(clip.copy(alpha = 0.35f), 9.dp.toPx(), led)
                    drawCircle(if (lit) clip else Dial.LedOff, 4.dp.toPx(), led)
                    drawText(peak, topLeft = Offset(led.x - peak.size.width - 8.dp.toPx(), led.y - peak.size.height / 2))

                    // Aiguille : fine, noire, avec son ombre portée sur le cadran.
                    val f = needle.value
                    val tip = pointAt(f, radius + 6.dp.toPx())
                    val base = pointAt(f, radius * 0.18f)
                    drawLine(Color.Black.copy(alpha = 0.18f), base + shadowShift, tip + shadowShift, 2.dp.toPx(), StrokeCap.Round)
                    drawLine(Dial.Needle, base, tip, 1.6.dp.toPx(), StrokeCap.Round)
                }
                // Cache-pivot sombre en bas du cadran.
                drawRect(cover, Offset(inset, size.height - inset - 2.dp.toPx()), Size(faceSize.width, 2.dp.toPx()))
            }
        },
    ) {}
}
