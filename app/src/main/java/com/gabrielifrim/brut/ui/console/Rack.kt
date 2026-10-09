package com.gabrielifrim.brut.ui.console

import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gabrielifrim.brut.ui.theme.BrutColors
import com.gabrielifrim.brut.ui.theme.BrutShapes
import com.gabrielifrim.brut.ui.theme.BrutType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Texte « gravé » dans la tôle : une ombre sombre au-dessus et un reflet clair dessous
 * suffisent à faire lire un creux. Réservé aux légendes, pas aux textes longs.
 */
fun engraved(style: TextStyle): TextStyle = style.copy(
    shadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, -1.2f), 0.5f),
)

/**
 * Façade du châssis : le fond de chaque écran. Un dégradé à peine perceptible et un
 * vignettage sur les bords suffisent à en faire une surface, sur laquelle les plaques
 * sont encastrées au lieu de flotter sur un aplat. Pas de texture : la façade reste plus
 * calme que les plaques.
 */
fun Modifier.chassis(): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF191612), Color(0xFF0F0D0B))))
    drawRect(
        Brush.radialGradient(
            0.55f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.35f),
            center = Offset(size.width / 2f, size.height * 0.4f),
            radius = maxOf(size.width, size.height) * 0.8f,
        ),
    )
}

/**
 * Creux dans la façade (pont de mesure, afficheurs en long) : même rayon que les plaques,
 * ombre portée vers l'intérieur par l'arête du haut, lèvre claire sous l'arête du bas.
 * À placer avant un `clip` au même rayon pour que la lèvre reste visible.
 */
fun Modifier.recess(fill: Color = BrutColors.Recess): Modifier = drawBehind {
    val radius = BrutShapes.Plate.toPx()
    val px = 1.dp.toPx()
    // Lèvre : la façade accroche la lumière juste sous le creux.
    drawRoundRect(
        Color.White.copy(alpha = 0.06f), Offset(-px, 0f), Size(size.width + 2 * px, size.height + px),
        CornerRadius(radius + px),
    )
    drawRoundRect(fill, cornerRadius = CornerRadius(radius))
    clipPath(Path().apply { addRoundRect(RoundRect(size.toRect(), CornerRadius(radius))) }) {
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), endY = 8.dp.toPx(),
            ),
            size = Size(size.width, 8.dp.toPx()),
        )
    }
    drawRoundRect(
        Color.Black.copy(alpha = 0.7f), cornerRadius = CornerRadius(radius), style = Stroke(px),
    )
}

/**
 * Plaque de rack : tôle brossée encastrée dans la façade, une vis à chaque coin. Le contenu
 * est posé entre les vis ; la plaque n'est pas une « carte » flottante mais un élément fixé.
 */
@Composable
fun RackPlate(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 14.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .drawBehind { drawPlate() }
            .padding(horizontal = contentPadding + 14.dp, vertical = contentPadding),
        content = content,
    )
}

private fun DrawScope.drawPlate() {
    val radius = BrutShapes.Plate.toPx()
    val r = CornerRadius(radius)
    val px = 1.dp.toPx()
    // Ombre douce sur la façade : trois couches de plus en plus larges et pâles, décalées
    // vers le bas (lumière d'au-dessus). Elles débordent du composant sans changer la mise en page.
    listOf(4f to 0.07f, 2.5f to 0.12f, 1f to 0.22f).forEach { (spreadDp, alpha) ->
        val spread = spreadDp * px
        drawRoundRect(
            Color.Black.copy(alpha = alpha),
            Offset(-spread, -spread + spread * 0.8f),
            Size(size.width + 2 * spread, size.height + 2 * spread),
            CornerRadius(radius + spread),
        )
    }
    // Joint : la fente sombre entre la plaque et la façade.
    drawRoundRect(Color.Black.copy(alpha = 0.6f), Offset(-px, -px), Size(size.width + 2 * px, size.height + 2 * px), CornerRadius(radius + px))
    // Tôle : dégradé vertical léger + stries de brossage horizontales, tenues dans l'arrondi.
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF2E2822), Color(0xFF221E19))), cornerRadius = r)
    clipPath(Path().apply { addRoundRect(RoundRect(size.toRect(), r)) }) {
        val step = 3.dp.toPx()
        var y = step
        var i = 0
        while (y < size.height) {
            drawLine(
                Color.White.copy(alpha = if (i % 3 == 0) 0.025f else 0.012f),
                Offset(0f, y), Offset(size.width, y), strokeWidth = 1f,
            )
            y += step
            i++
        }
    }
    // Arêtes : un liseré qui suit l'arrondi, clair en haut, sombre en bas, comme une plaque
    // éclairée d'au-dessus.
    val half = px / 2f
    drawRoundRect(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.10f),
            0.3f to Color.Transparent,
            0.7f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.5f),
        ),
        Offset(half, half), Size(size.width - px, size.height - px), CornerRadius(radius - half),
        style = Stroke(px),
    )
    val inset = 9.dp.toPx()
    listOf(
        Offset(inset, inset) to 20f,
        Offset(size.width - inset, inset) to 75f,
        Offset(inset, size.height - inset) to 130f,
        Offset(size.width - inset, size.height - inset) to 45f,
    ).forEach { (c, angle) -> drawScrew(c, 3.2.dp.toPx(), angle) }
}

/** Vis cruciforme vue de face, la fente orientée au hasard comme sur un vrai rack. */
private fun DrawScope.drawScrew(c: Offset, radius: Float, angle: Float) {
    drawCircle(Color.Black.copy(alpha = 0.55f), radius + 1.2f, c + Offset(0f, 0.8f))
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFF5C534A), Color(0xFF2A2520)), c - Offset(radius * 0.4f, radius * 0.4f), radius * 1.6f),
        radius, c,
    )
    rotate(angle, c) {
        val l = radius * 0.62f
        drawLine(Color(0xFF14110E), c - Offset(l, 0f), c + Offset(l, 0f), radius * 0.32f, StrokeCap.Round)
        drawLine(Color(0xFF14110E), c - Offset(0f, l), c + Offset(0f, l), radius * 0.32f, StrokeCap.Round)
    }
}

/**
 * Sélecteur rotatif à crans, comme un commutateur de gamme d'appareil de mesure.
 * Les positions sont sérigraphiées autour du bouton ; on choisit en touchant une
 * position, en touchant le bouton (cran suivant) ou en le faisant glisser.
 * Chaque cran franchi donne un retour haptique.
 */
@Composable
fun <T> RotarySelector(
    legend: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    /** Positions que l'entrée ne gère pas nativement : grisées, mais toujours sélectionnables. */
    isNative: (T) -> Boolean = { true },
    knobSize: Dp = 50.dp,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val index = options.indexOf(selected).coerceAtLeast(0)
    val currentIndex by rememberUpdatedState(index)
    val select by rememberUpdatedState(onSelect)
    // Course angulaire : 90° pour 2 positions, 120° jusqu'à 4, 170° au-delà (les repères restent lisibles).
    val span = when {
        options.size <= 2 -> 90f
        options.size <= 4 -> 120f
        else -> 170f
    }
    fun angleOf(i: Int) = if (options.size == 1) 0f else -span / 2 + span * i / (options.size - 1)
    val pointer by animateFloatAsState(angleOf(index), spring(dampingRatio = 0.55f, stiffness = 700f), label = "cran")
    val drag = remember { mutableFloatStateOf(0f) }

    fun choose(i: Int) {
        if (!enabled || i == currentIndex) return
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        select(options[i])
    }

    val labelRadius = knobSize * 0.92f
    val boxSize = knobSize + 56.dp
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(legend, style = engraved(BrutType.Legend), color = BrutColors.CreamDim)
        Box(Modifier.size(width = boxSize, height = knobSize + 46.dp)) {
            val pivot = Offset(with(density) { boxSize.toPx() } / 2, with(density) { (knobSize / 2 + 34.dp).toPx() })
            // Repères sérigraphiés autour du bouton.
            options.forEachIndexed { i, option ->
                val a = Math.toRadians((angleOf(i) - 90f).toDouble())
                val rPx = with(density) { labelRadius.toPx() }
                val x = pivot.x + rPx * cos(a).toFloat()
                val y = pivot.y + rPx * sin(a).toFloat()
                val active = i == index
                val w = 52.dp
                Text(
                    label(option),
                    style = engraved(BrutType.Legend).copy(textAlign = TextAlign.Center, letterSpacing = BrutType.Legend.letterSpacing * 0.5f),
                    color = when {
                        !enabled -> BrutColors.CreamFaint
                        active -> if (isNative(option)) BrutColors.Amber else BrutColors.Amber.copy(alpha = 0.55f)
                        isNative(option) -> BrutColors.CreamDim
                        else -> BrutColors.CreamFaint
                    },
                    modifier = Modifier
                        .width(w)
                        .offset { IntOffset((x - w.toPx() / 2).roundToInt(), (y - 9.dp.toPx()).roundToInt()) }
                        .semantics {
                            role = Role.RadioButton
                            this.selected = active
                            contentDescription = "$legend ${label(option)}"
                            onClick { choose(i); true }
                        }
                        .pointerInput(enabled) { detectTapGestures { choose(i) } },
                )
            }
            Canvas(
                Modifier
                    .size(knobSize)
                    .offset { IntOffset((pivot.x - knobSize.toPx() / 2).roundToInt(), (pivot.y - knobSize.toPx() / 2).roundToInt()) }
                    .semantics { stateDescription = label(selected) }
                    .pointerInput(enabled) {
                        detectTapGestures { choose((currentIndex + 1) % options.size) }
                    }
                    .pointerInput(enabled) {
                        detectHorizontalDragGestures(
                            onDragStart = { drag.floatValue = 0f },
                        ) { change, dx ->
                            change.consume()
                            drag.floatValue += dx
                            val stepPx = 36.dp.toPx()
                            if (abs(drag.floatValue) >= stepPx) {
                                val dir = if (drag.floatValue > 0) 1 else -1
                                drag.floatValue = 0f
                                choose((currentIndex + dir).coerceIn(0, options.lastIndex))
                            }
                        }
                    },
            ) {
                val r = size.minDimension / 2
                // Crans gravés sur la plaque.
                options.indices.forEach { i ->
                    rotate(angleOf(i), center) {
                        drawLine(
                            if (i == index) BrutColors.Amber else BrutColors.CreamFaint,
                            Offset(center.x, center.y - r * 1.02f),
                            Offset(center.x, center.y - r * 1.22f),
                            strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                        )
                    }
                }
                // Bouton à jupe moletée.
                drawCircle(Color.Black.copy(alpha = 0.5f), r * 0.98f, center + Offset(0f, 2.5.dp.toPx()))
                drawCircle(Brush.verticalGradient(listOf(Color(0xFF4A4238), Color(0xFF1C1814))), r * 0.94f)
                for (k in 0 until 36) {
                    rotate(k * 10f, center) {
                        drawLine(Color.Black.copy(alpha = 0.35f), Offset(center.x, center.y - r * 0.94f), Offset(center.x, center.y - r * 0.80f), 1.2f)
                    }
                }
                drawCircle(Brush.radialGradient(listOf(Color(0xFF3B342C), Color(0xFF26211C)), center, r * 0.7f), r * 0.72f)
                drawCircle(Color.White.copy(alpha = 0.06f), r * 0.72f, style = Stroke(1f))
                // Index : une flèche crème qui va du centre au bord.
                rotate(pointer, center) {
                    drawLine(
                        if (enabled) BrutColors.Cream else BrutColors.CreamFaint,
                        Offset(center.x, center.y - r * 0.68f), Offset(center.x, center.y - r * 0.18f),
                        strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

/** Touche d'action en relief, légende gravée : la même partout où l'on agit sur une plaque. */
@Composable
fun ActionButton(text: String, color: Color, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .drawBehind {
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF3B342C), Color(0xFF221E19))), cornerRadius = CornerRadius(4.dp.toPx()))
                drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, 0.5f), Offset(size.width, 0.5f))
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = engraved(BrutType.Legend), color = if (enabled) color else BrutColors.CreamFaint, maxLines = 1)
    }
}

/** Étiquette d'état gravée : un triangle et un mot, comme les repères d'une face avant. */
@Composable
fun StatusTag(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        "▸ $text",
        style = engraved(BrutType.Legend),
        color = color,
        maxLines = 1,
        // Le triangle est un ornement gravé : TalkBack ne lit que le mot.
        modifier = modifier.clearAndSetSemantics { contentDescription = text },
    )
}

/** Petit afficheur rétroéclairé encastré dans la plaque, pour une valeur courte. */
@Composable
fun Readout(text: String, modifier: Modifier = Modifier, color: Color = BrutColors.Amber) {
    Box(
        modifier
            .drawBehind {
                drawRoundRect(Color(0xFF0B0A08), cornerRadius = CornerRadius(3.dp.toPx()))
                drawRoundRect(
                    Color.Black.copy(alpha = 0.7f), size = Size(size.width, 2.dp.toPx()),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
                drawRoundRect(color.copy(alpha = 0.06f), cornerRadius = CornerRadius(3.dp.toPx()))
            }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        // Le texte se réduit plutôt que d'être coupé quand l'afficheur est étroit.
        BasicText(
            text,
            style = BrutType.Readout.copy(color = color, shadow = Shadow(color.copy(alpha = 0.6f), Offset.Zero, 8f)),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = BrutType.Readout.fontSize, stepSize = 0.5.sp),
        )
    }
}
