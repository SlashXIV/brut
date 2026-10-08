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

/**
 * Gros bouton d'enregistrement : un rond rouge au repos, un carré quand la prise
 * tourne, avec une lueur qui respire pour qu'on le voie du coin de l'œil.
 */
@Composable
fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, armed: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "rec")
    val glow by transition.animateFloat(
        initialValue = 0.25f, targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glow",
    )
    val ring by animateColorAsState(
        when {
            recording -> BrutColors.Red
            armed -> BrutColors.Amber
            else -> BrutColors.Edge
        },
        label = "ring",
    )
    val label = stringResource(
        when {
            recording -> R.string.stop
            armed -> R.string.disarm
            else -> R.string.record
        },
    )
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
            // Armée : l'anneau ambre respire, la prise attend le signal.
            if (armed) drawCircle(BrutColors.Amber.copy(alpha = glow * 0.30f), r)
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

/**
 * Bouton de repère : large, rond, ambre, posé à côté de REC pendant la prise. On le
 * trouve au toucher sans regarder l'écran ; une vibration confirme chaque repère.
 */
@Composable
fun MarkerButton(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val label = stringResource(R.string.marker_button)
    Box(
        modifier
            .size(76.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(76.dp)) {
            val r = size.minDimension / 2
            drawCircle(Brush.verticalGradient(listOf(Color(0xFF3E372F), Color(0xFF171410))), r * 0.92f)
            drawCircle(BrutColors.Amber, r * 0.92f, style = Stroke(2.dp.toPx()))
            // Triangle de repère, comme sur la forme d'onde.
            val t = r * 0.32f
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(center.x - t, center.y - t * 0.9f)
                lineTo(center.x + t, center.y - t * 0.9f)
                lineTo(center.x, center.y + t * 0.9f)
                close()
            }
            drawPath(path, BrutColors.Amber)
        }
        if (count > 0) {
            Text(
                count.toString(),
                style = BrutType.ReadoutSmall,
                color = BrutColors.Cream,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            )
        }
    }
}
