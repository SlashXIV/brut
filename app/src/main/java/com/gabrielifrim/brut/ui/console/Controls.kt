package com.gabrielifrim.brut.ui.console

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gabrielifrim.brut.R
import com.gabrielifrim.brut.audio.GainStage
import com.gabrielifrim.brut.ui.formatDb
import com.gabrielifrim.brut.ui.theme.BrutColors
import com.gabrielifrim.brut.ui.theme.BrutType
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val SWEEP = 270f
private const val START_ANGLE = 135f

/**
 * Potard rotatif. On le tourne en glissant verticalement (le geste le plus précis
 * sur un écran tactile, et celui des applis de studio) ; double appui = 0 dB.
 * Un retour haptique marque le passage par 0 dB, comme le cran d'un vrai potard.
 */
@Composable
fun GainKnob(
    valueDb: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    accessibilityLabel: String,
    modifier: Modifier = Modifier,
    diameter: Dp = 84.dp,
) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(valueDb)
    val onChange by rememberUpdatedState(onValueChange)
    val dragAccumulator = remember { mutableFloatStateOf(0f) }
    val range = GainStage.MAX_DB - GainStage.MIN_DB

    fun emit(next: Float) {
        // Pas de 0,5 dB : assez fin pour régler, assez grossier pour retomber pile sur 0.
        val snapped = ((next * 2).roundToInt() / 2f).coerceIn(GainStage.MIN_DB, GainStage.MAX_DB)
        if (snapped != current) {
            if (snapped == 0f || snapped == GainStage.MIN_DB || snapped == GainStage.MAX_DB) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            onChange(snapped)
        }
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(diameter)
                .semantics {
                    contentDescription = accessibilityLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(current, GainStage.MIN_DB..GainStage.MAX_DB, steps = 95)
                    setProgress { v -> emit(v); true }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { emit(0f) })
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { dragAccumulator.floatValue = current },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            // 240 px de course pour toute la plage.
                            dragAccumulator.floatValue -= dy / 240.dp.toPx() * range
                            emit(dragAccumulator.floatValue)
                        },
                    )
                },
        ) {
            val r = size.minDimension / 2
            val c = center
            val fraction = (current - GainStage.MIN_DB) / range
            val zeroFraction = -GainStage.MIN_DB / range

            // Graduations sérigraphiées autour du bouton.
            for (i in 0..24) {
                val a = Math.toRadians((START_ANGLE + SWEEP * i / 24f).toDouble())
                val major = i % 6 == 0
                val inner = r * (if (major) 0.80f else 0.84f)
                drawLine(
                    if (major) BrutColors.CreamDim else BrutColors.CreamFaint,
                    Offset(c.x + inner * cos(a).toFloat(), c.y + inner * sin(a).toFloat()),
                    Offset(c.x + r * 0.92f * cos(a).toFloat(), c.y + r * 0.92f * sin(a).toFloat()),
                    strokeWidth = if (major) 2f else 1.2f,
                )
            }
            // Arc de valeur, depuis 0 dB : on voit d'un coup d'œil si l'on amplifie ou atténue.
            val arcInset = r * 0.04f
            drawArc(
                BrutColors.Amber,
                startAngle = START_ANGLE + SWEEP * zeroFraction,
                sweepAngle = SWEEP * (fraction - zeroFraction),
                useCenter = false,
                topLeft = Offset(arcInset, arcInset),
                size = androidx.compose.ui.geometry.Size(size.width - 2 * arcInset, size.height - 2 * arcInset),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
            )
            // Corps du bouton : métal sombre éclairé par le haut.
            val bodyR = r * 0.68f
            drawCircle(Color.Black.copy(alpha = 0.5f), bodyR + 3.dp.toPx(), c + Offset(0f, 3.dp.toPx()))
            drawCircle(
                Brush.verticalGradient(listOf(Color(0xFF4A4238), Color(0xFF1E1A16)), c.y - bodyR, c.y + bodyR),
                bodyR, c,
            )
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFF3B342C), Color(0xFF26211C)), c, bodyR * 0.8f),
                bodyR * 0.82f, c,
            )
            // Index du bouton.
            rotate(START_ANGLE + SWEEP * fraction + 90f, c) {
                drawLine(
                    BrutColors.Cream,
                    Offset(c.x, c.y - bodyR * 0.78f),
                    Offset(c.x, c.y - bodyR * 0.35f),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        Text(label, style = BrutType.Legend, color = BrutColors.CreamDim)
        Text(
            stringResource(R.string.db_value, formatDb(valueDb, signed = true) ?: "0,0"),
            style = BrutType.Readout,
            color = if (valueDb == 0f) BrutColors.Cream else BrutColors.Amber,
        )
    }
}

/** Interrupteur à lampe pour lier les gains gauche et droit. */
@Composable
fun LinkSwitch(linked: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(BrutColors.PanelRaised)
            .border(1.dp, BrutColors.Edge, RoundedCornerShape(10.dp))
            .clickable(role = Role.Switch) { onToggle(!linked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Lamp(BrutColors.Amber, lit = linked)
        Text(stringResource(R.string.gain_link), style = BrutType.Legend, color = if (linked) BrutColors.Cream else BrutColors.CreamDim)
    }
}

/**
 * Gros bouton d'enregistrement : un rond rouge au repos, un carré quand la prise
 * tourne, avec une lueur qui respire pour qu'on le voie du coin de l'œil.
 */
@Composable
fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "rec")
    val glow by transition.animateFloat(
        initialValue = 0.25f, targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glow",
    )
    val ring by animateColorAsState(if (recording) BrutColors.Red else BrutColors.Edge, label = "ring")
    val label = stringResource(if (recording) R.string.stop else R.string.record)
    Box(
        modifier
            .size(92.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(92.dp)) {
            val r = size.minDimension / 2
            if (recording) drawCircle(BrutColors.Red.copy(alpha = glow * 0.35f), r)
            drawCircle(
                Brush.verticalGradient(listOf(Color(0xFF3E372F), Color(0xFF171410))),
                r * 0.86f,
            )
            drawCircle(ring, r * 0.86f, style = Stroke(2.dp.toPx()))
            val red = if (enabled) BrutColors.Red else BrutColors.Red.copy(alpha = 0.3f)
            if (recording) {
                val s = r * 0.62f
                drawRoundRect(
                    red,
                    topLeft = center - Offset(s / 2, s / 2),
                    size = androidx.compose.ui.geometry.Size(s, s),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                )
            } else {
                drawCircle(red, r * 0.40f)
                drawCircle(Color.White.copy(alpha = 0.18f), r * 0.40f, center - Offset(0f, r * 0.06f), style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}
