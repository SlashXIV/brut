package com.gabrielifrim.brut.ui.console

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gabrielifrim.brut.R
import com.gabrielifrim.brut.ui.component.Metal
import com.gabrielifrim.brut.ui.motion.LocalMotionLevel
import com.gabrielifrim.brut.ui.motion.Motion
import com.gabrielifrim.brut.ui.motion.rememberHaptics
import com.gabrielifrim.brut.ui.theme.BrutColors
import com.gabrielifrim.brut.ui.theme.BrutType

/**
 * Gros bouton d'enregistrement : un rond rouge au repos, un carré quand la prise tourne,
 * avec un halo pour qu'on le voie du coin de l'œil. Le halo est fixe : une lueur qui
 * respire redessinerait le bouton à chaque image pendant toute la prise.
 */
@Composable
fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, armed: Boolean = false) {
    val motion = LocalMotionLevel.current
    val p = BrutColors.palette
    val ring by animateColorAsState(
        when {
            recording -> p.clip
            armed -> p.lamp
            else -> p.edge
        },
        if (motion.animates) tween(Motion.ENTER_MS, easing = Motion.Enter) else snap(),
        label = "anneau",
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
            when {
                recording -> drawCircle(p.clip.copy(alpha = 0.22f), r)
                // Armée : un halo ambre, la prise attend le signal.
                armed -> drawCircle(p.lamp.copy(alpha = 0.20f), r)
            }
            drawCircle(p.shadow.copy(alpha = if (p.isDark) 0.5f else 0.25f), r * 0.86f, center + Offset(0f, 2.dp.toPx()))
            drawCircle(Brush.verticalGradient(listOf(Metal.CapLight, Metal.KnobBottom)), r * 0.86f)
            drawCircle(ring, r * 0.86f, style = Stroke(2.dp.toPx()))
            val red = if (enabled) p.clip else p.clip.copy(alpha = 0.3f)
            if (recording) {
                val s = r * 0.62f
                drawRoundRect(red, topLeft = center - Offset(s / 2, s / 2), size = Size(s, s), cornerRadius = CornerRadius(6.dp.toPx()))
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
    val haptics = rememberHaptics()
    val label = stringResource(R.string.marker_button)
    val lamp = BrutColors.Lamp
    Box(
        modifier
            .size(76.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button) {
                haptics.confirm()
                onClick()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .size(76.dp)
                .drawWithCache {
                    val r = size.minDimension / 2
                    val body = Brush.verticalGradient(listOf(Metal.CapLight, Metal.KnobBottom))
                    // Triangle de repère, comme sur la forme d'onde.
                    val t = r * 0.32f
                    val c = size.center
                    val mark = Path().apply {
                        moveTo(c.x - t, c.y - t * 0.9f)
                        lineTo(c.x + t, c.y - t * 0.9f)
                        lineTo(c.x, c.y + t * 0.9f)
                        close()
                    }
                    onDrawBehind {
                        drawCircle(body, r * 0.92f)
                        drawCircle(lamp, r * 0.92f, style = Stroke(2.dp.toPx()))
                        drawPath(mark, lamp)
                    }
                },
        ) {}
        if (count > 0) {
            Text(
                count.toString(),
                style = BrutType.ReadoutSmall,
                color = Metal.Index,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            )
        }
    }
}
