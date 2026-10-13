package com.gabrielifrim.brut.ui.console

import com.gabrielifrim.brut.ui.component.engraved
import com.gabrielifrim.brut.ui.component.RackPlate
import com.gabrielifrim.brut.ui.component.Readout
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.gabrielifrim.brut.R
import com.gabrielifrim.brut.audio.GainStage
import com.gabrielifrim.brut.ui.formatDb
import com.gabrielifrim.brut.ui.component.Metal
import com.gabrielifrim.brut.ui.motion.rememberHaptics
import com.gabrielifrim.brut.ui.theme.BrutColors
import com.gabrielifrim.brut.ui.theme.BrutShapes
import com.gabrielifrim.brut.ui.theme.BrutType
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pas de réglage : assez fin pour doser, assez grossier pour retomber pile sur 0. */
private const val STEP_DB = 0.5f

/** Zone « aimantée » autour de 0 dB : on y retombe sans viser au pixel près. */
private const val DETENT_DB = 0.75f

private fun snap(db: Float): Float {
    val clamped = db.coerceIn(GainStage.MIN_DB, GainStage.MAX_DB)
    if (abs(clamped) < DETENT_DB) return 0f
    return (clamped / STEP_DB).roundToInt() * STEP_DB
}

/**
 * Fader de gain horizontal, sur toute la largeur : la course longue donne la
 * précision qu'un potard de 80 dp ne peut pas offrir sous un pouce.
 *
 * - Glisser déplace le curseur en relatif : poser le doigt ne fait jamais sauter le gain.
 * - Un cran aimanté et une vibration marquent 0 dB.
 * - − / + : pas de 0,5 dB. Double appui sur la glissière : retour à 0 dB.
 */
@Composable
fun GainFader(
    channelLabel: String,
    valueDb: Float,
    onValueChange: (Float) -> Unit,
    accessibilityLabel: String,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val p = BrutColors.palette
    val current by rememberUpdatedState(valueDb)
    val onChange by rememberUpdatedState(onValueChange)
    val raw = remember { mutableFloatStateOf(0f) }
    val measurer = rememberTextMeasurer()
    val range = GainStage.MAX_DB - GainStage.MIN_DB

    // Les touches − / + avancent d'un pas exact ; seul le glissé s'aimante sur 0 dB
    // (sinon +0,5 dB depuis 0 retombait aussitôt dans le cran).
    fun emit(next: Float, detent: Boolean = true) {
        val snapped = if (detent) snap(next) else ((next.coerceIn(GainStage.MIN_DB, GainStage.MAX_DB) / STEP_DB).roundToInt() * STEP_DB)
        if (snapped == current) return
        val crossedZero = snapped == 0f || (current < 0f) != (snapped < 0f)
        if (crossedZero || snapped == GainStage.MIN_DB || snapped == GainStage.MAX_DB) {
            haptics.notch()
        }
        onChange(snapped)
    }

    Column(modifier.fillMaxWidth()) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(channelLabel, style = engraved(BrutType.Title), color = BrutColors.Accent)
        Spacer(Modifier.weight(1f))
        StepButton("−", stringResource(R.string.gain_decrease)) { emit(current - STEP_DB, detent = false) }
        Readout(
            stringResource(R.string.db_value, formatDb(valueDb, signed = true) ?: "0,0"),
            Modifier.padding(horizontal = 8.dp).width(100.dp),
            color = if (valueDb == 0f) BrutColors.Ink else BrutColors.Accent,
        )
        StepButton("+", stringResource(R.string.gain_increase)) { emit(current + STEP_DB, detent = false) }
      }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .semantics {
                    contentDescription = accessibilityLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(current, GainStage.MIN_DB..GainStage.MAX_DB, steps = 95)
                    setProgress { v -> emit(v); true }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { emit(0f) }) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { raw.floatValue = current },
                    ) { change, dx ->
                        change.consume()
                        raw.floatValue = (raw.floatValue + dx / size.width * range)
                            .coerceIn(GainStage.MIN_DB, GainStage.MAX_DB)
                        emit(raw.floatValue)
                    }
                },
        ) {
            val capW = 18.dp.toPx()
            val usable = size.width - capW
            fun xOf(db: Float) = capW / 2 + usable * (db - GainStage.MIN_DB) / range
            val midY = size.height - 17.dp.toPx()

            // Graduations sérigraphiées : un trait tous les 3 dB, chiffré tous les 12 dB.
            var db = GainStage.MIN_DB
            while (db <= GainStage.MAX_DB) {
                val x = xOf(db)
                val major = db % 12f == 0f
                val isZero = db == 0f
                drawLine(
                    if (isZero) p.ink else if (major) p.inkDim else p.inkFaint,
                    Offset(x, midY - (if (major) 14.dp else 12.dp).toPx()),
                    Offset(x, midY - 8.dp.toPx()),
                    strokeWidth = if (isZero) 2.dp.toPx() else 1.dp.toPx(),
                )
                if (major) {
                    val text = when {
                        db > 0 -> "+${db.toInt()}"
                        db < 0 -> "−${(-db).toInt()}"
                        else -> "0"
                    }
                    val layout = measurer.measure(text, BrutType.ReadoutSmall.copy(color = if (isZero) p.ink else p.inkDim))
                    drawText(layout, topLeft = Offset(x - layout.size.width / 2f, 0f))
                }
                db += 3f
            }

            // Fente du fader, creusée dans la tôle : une ombre, quelle que soit la teinte de la tôle.
            val slotH = 4.dp.toPx()
            drawRoundRect(
                p.shadow.copy(alpha = if (p.isDark) 0.8f else 0.35f), Offset(capW / 2, midY - slotH / 2), Size(usable, slotH),
                CornerRadius(slotH / 2),
            )
            // Portion active, depuis 0 dB : amplification ou atténuation se lisent d'un coup d'œil.
            val x0 = xOf(0f)
            val xv = xOf(current)
            if (current != 0f) {
                drawRect(
                    p.accent.copy(alpha = 0.8f),
                    Offset(minOf(x0, xv), midY - 1.dp.toPx()), Size(abs(xv - x0), 2.dp.toPx()),
                )
            }

            // Bouton du fader : capuchon strié, ligne d'index au centre.
            val capH = 26.dp.toPx()
            val capTop = midY - capH / 2
            drawRoundRect(p.shadow.copy(alpha = if (p.isDark) 0.55f else 0.3f), Offset(xv - capW / 2 + 1.5.dp.toPx(), capTop + 2.dp.toPx()), Size(capW, capH), CornerRadius(3.dp.toPx()))
            drawRoundRect(
                Brush.horizontalGradient(listOf(Metal.KnobTop, Metal.CapDark), xv - capW / 2, xv + capW / 2),
                Offset(xv - capW / 2, capTop), Size(capW, capH), CornerRadius(3.dp.toPx()),
            )
            for (k in 1..4) {
                val y = capTop + capH * k / 5f
                drawLine(Color.Black.copy(alpha = 0.35f), Offset(xv - capW / 2 + 2.dp.toPx(), y), Offset(xv + capW / 2 - 2.dp.toPx(), y), 1f)
            }
            drawLine(
                // Le capuchon reste sombre dans toutes les palettes : index clair, ambre hors de 0 dB.
                if (current == 0f) Metal.Index else p.lamp,
                Offset(xv, capTop + 3.dp.toPx()), Offset(xv, capTop + capH - 3.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}

/**
 * Gains en multipiste : une touche par voie (son numéro et son gain), et un seul fader
 * pour la voie choisie, ou pour toutes quand elles sont liées. Huit faders ne tiendraient
 * pas sur un écran de téléphone à côté des mesures.
 */
@Composable
private fun MultiGain(channels: Int, labels: List<String>, gains: List<Float>, linked: Boolean, onGain: (Int, Float) -> Unit, gainWord: String) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val current = selected.coerceIn(0, channels - 1)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (c in 0 until channels) {
            val active = linked || c == current
            Column(
                Modifier
                    .weight(1f)
                    .clip(BrutShapes.InsetShape)
                    .background(if (active) BrutColors.PlateRaised else BrutColors.Recess)
                    .selectable(selected = c == current, enabled = !linked, role = Role.RadioButton) { selected = c }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(labels[c], style = engraved(BrutType.Legend), color = if (active) BrutColors.Accent else BrutColors.InkDim)
                Text(
                    formatDb(gains[c], signed = true) ?: "0,0",
                    style = BrutType.ReadoutSmall,
                    color = if (gains[c] == 0f) BrutColors.InkDim else BrutColors.Ink,
                    maxLines = 1,
                )
            }
        }
    }
    val all = stringResource(R.string.gain_all_channels)
    if (linked) {
        GainFader(all, gains[0], { onGain(0, it) }, "$gainWord $all")
    } else {
        GainFader(labels[current], gains[current], { onGain(current, it) }, "$gainWord " + stringResource(R.string.track_name, current + 1))
    }
}

/** Petit bouton poussoir gravé, pour les pas fins. */
@Composable
private fun StepButton(symbol: String, description: String, onClick: () -> Unit) {
    val p = BrutColors.palette
    Box(
        Modifier
            .size(40.dp)
            .clip(BrutShapes.InsetShape)
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(p.plateRaised, p.plate)))
                drawRoundRect(p.edge, cornerRadius = CornerRadius(BrutShapes.Inset.toPx()), style = Stroke(1.dp.toPx()))
                drawLine(p.highlight.copy(alpha = if (p.isDark) 0.08f else 0.9f), Offset(0f, 1f), Offset(size.width, 1f))
            }
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            // Le symbole « − » ou « + » ne se lit pas : seule l'action compte.
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(description) { onClick(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, style = BrutType.BodyStrong, color = BrutColors.Ink)
    }
}

/** En-tête du panneau de gain : légende gravée et interrupteur de liaison G/D. */
@Composable
fun GainPanel(
    channels: Int,
    labels: List<String>,
    gains: List<Float>,
    linked: Boolean,
    onGain: (Int, Float) -> Unit,
    onLinked: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stereo = channels == 2
    RackPlate(modifier.fillMaxWidth(), contentPadding = 8.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.gain).uppercase(), style = engraved(BrutType.Legend), color = BrutColors.InkDim)
                Spacer(Modifier.weight(1f))
                if (channels >= 2) {
                    Row(
                        Modifier
                            .clip(BrutShapes.InsetShape)
                            .toggleable(value = linked, role = Role.Switch, onValueChange = onLinked)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Lamp(BrutColors.Lamp, lit = linked)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (stereo) R.string.gain_link else R.string.gain_link_all).uppercase(), style = engraved(BrutType.Legend), color = if (linked) BrutColors.Ink else BrutColors.InkDim)
                    }
                }
            }
            val gainWord = stringResource(R.string.gain)
            if (channels > 2) {
                MultiGain(channels, labels, gains, linked, onGain, gainWord)
                return@Column
            }
            GainFader(
                labels[0], gains[0], { onGain(0, it) },
                "$gainWord " + stringResource(if (stereo) R.string.channel_left_long else R.string.format_mono),
            )
            if (stereo) {
                GainFader(labels[1], gains[1], { onGain(1, it) }, "$gainWord " + stringResource(R.string.channel_right_long))
            }
        }
    }
}
