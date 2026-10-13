package com.gabrielifrim.brut.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.gabrielifrim.brut.ui.motion.LocalMotionLevel
import com.gabrielifrim.brut.ui.motion.Motion
import com.gabrielifrim.brut.ui.theme.BrutColors
import com.gabrielifrim.brut.ui.theme.BrutShapes
import com.gabrielifrim.brut.ui.theme.BrutType

/** Où en est la prise : le chemin s'allume en ambre quand elle est armée, en rouge quand elle tourne. */
enum class PathState { IDLE, ARMED, RECORDING }

/** Ce qu'Android impose à cet endroit du chemin. [severe] : le son n'est plus celui qu'on croit. */
@Immutable
data class Imposed(val text: String, val severe: Boolean = false)

@Immutable
data class PathNode(
    /** Sérigraphie du nœud : ENTRÉE, FORMAT, GAIN. */
    val key: String,
    val value: String,
    val detail: String,
    /** Phrase lue par TalkBack : le nœud entier en une fois. */
    val spoken: String,
    val imposed: List<Imposed> = emptyList(),
    /** Bordure rouge : saturation, entrée perdue. */
    val alarm: Boolean = false,
    val clickLabel: String? = null,
    val onClick: (() -> Unit)? = null,
)

/**
 * Le chemin du signal, sérigraphié comme le synoptique d'une face de console : des blocs
 * reliés par des flèches, de l'entrée jusqu'au fichier. Ce qu'Android impose se branche
 * sous le nœud concerné, au lieu d'un message à part. Un chemin sans bloc est un chemin brut.
 */
@Composable
fun SignalPath(
    nodes: List<PathNode>,
    fileSpoken: String,
    fileState: String,
    state: PathState,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit = {},
) {
    val p = BrutColors.palette
    val motion = LocalMotionLevel.current
    val spec: AnimationSpec<Color> = if (motion.animates) tween(Motion.ENTER_MS, easing = Motion.Enter) else snap()
    val wire by animateColorAsState(
        when (state) {
            PathState.IDLE -> p.inkFaint
            PathState.ARMED -> p.lamp
            PathState.RECORDING -> p.clip
        },
        spec, label = "fil",
    )
    val border by animateColorAsState(
        when (state) {
            PathState.IDLE -> p.edge
            PathState.ARMED -> p.lamp
            PathState.RECORDING -> p.clip.copy(alpha = 0.7f)
        },
        spec, label = "bordure",
    )
    RackPlate(modifier.fillMaxWidth(), contentPadding = 8.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                nodes.forEach { node ->
                    Node(node, border, Modifier.weight(1f).fillMaxHeight())
                    Wire(wire, Modifier.width(14.dp).fillMaxHeight())
                }
                FileNode(state, wire, fileSpoken, fileState, Modifier.align(Alignment.CenterVertically))
            }
            nodes.forEachIndexed { i, node ->
                node.imposed.forEach { block ->
                    // Le bloc pend sous son nœud : décalé d'autant de nœuds qu'il y en a avant lui.
                    Row(Modifier.fillMaxWidth()) {
                        if (i > 0) Spacer(Modifier.weight(i.toFloat()))
                        ImposedBlock(block, Modifier.weight(nodes.size - i + 0.4f))
                    }
                }
            }
            footer()
        }
    }
}

@Composable
private fun Node(node: PathNode, border: Color, modifier: Modifier) {
    val p = BrutColors.palette
    val alert = when {
        node.alarm || node.imposed.any { it.severe } -> p.clip
        node.imposed.isNotEmpty() -> p.lamp
        else -> null
    }
    Column(
        modifier
            .heightIn(min = 52.dp)
            .clip(BrutShapes.InsetShape)
            .background(p.recess)
            .border(if (alert != null) 1.5.dp else 1.dp, alert ?: border, BrutShapes.InsetShape)
            .then(if (node.onClick != null) Modifier.clickable(onClickLabel = node.clickLabel, onClick = node.onClick) else Modifier)
            // Un nœud = une phrase. Ce qui est imposé est un état, annoncé quand il change.
            .clearAndSetSemantics {
                contentDescription = node.spoken
                if (node.imposed.isNotEmpty()) stateDescription = node.imposed.joinToString(". ") { it.text }
                liveRegion = LiveRegionMode.Polite
                if (node.onClick != null) {
                    role = Role.Button
                    onClick(node.clickLabel) { node.onClick(); true }
                }
            }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
    ) {
        Text(
            node.key.uppercase(), style = BrutType.Legend.copy(fontSize = 9.sp, letterSpacing = 1.2.sp),
            color = alert ?: p.inkDim, maxLines = 1, overflow = TextOverflow.Clip,
        )
        // La valeur se réduit plutôt que d'être coupée : un nom de micro long reste entier.
        // Deux lignes au plus, et la taille se réduit avant de couper : un nom de micro long reste entier.
        BasicText(
            node.value,
            style = BrutType.Node.copy(color = p.ink, lineHeight = 1.05.em),
            maxLines = 2,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = BrutType.Node.fontSize, stepSize = 0.5.sp),
        )
        BasicText(
            node.detail,
            style = BrutType.ReadoutSmall.copy(color = p.inkDim, fontSize = 10.sp, lineHeight = 1.1.em),
            maxLines = 2,
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 10.sp, stepSize = 0.5.sp),
        )
    }
}

/** Fil et flèche entre deux nœuds. */
@Composable
private fun Wire(color: Color, modifier: Modifier) {
    Canvas(modifier.clearAndSetSemantics {}) {
        val y = size.height / 2
        val head = 5.dp.toPx()
        drawLine(color, Offset(0f, y), Offset(size.width - head, y), 1.5.dp.toPx())
        val arrow = Path().apply {
            moveTo(size.width, y)
            lineTo(size.width - head, y - head * 0.8f)
            lineTo(size.width - head, y + head * 0.8f)
            close()
        }
        drawPath(arrow, color)
    }
}

/** Le fichier, au bout du chemin : un rond vide au repos, plein en prise, en pointillé armé. */
@Composable
private fun FileNode(state: PathState, color: Color, spoken: String, stateText: String, modifier: Modifier) {
    val p = BrutColors.palette
    Canvas(
        modifier
            .size(36.dp)
            .clearAndSetSemantics {
                contentDescription = spoken
                stateDescription = stateText
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        val r = size.minDimension / 2 - 1.dp.toPx()
        val stroke = Stroke(
            width = if (state == PathState.RECORDING) 2.5.dp.toPx() else 1.5.dp.toPx(),
            pathEffect = if (state == PathState.ARMED) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
        )
        drawCircle(color, r, style = stroke)
        drawCircle(if (state == PathState.IDLE) p.inkFaint else color, r * 0.32f)
    }
}

@Composable
private fun ImposedBlock(block: Imposed, modifier: Modifier) {
    val p = BrutColors.palette
    val color = if (block.severe) p.clip else p.lamp
    val text = if (block.severe) p.clip else p.accent
    Row(
        modifier
            .clearAndSetSemantics {}
            .drawBehind {
                // Raccord pointillé vers le nœud au-dessus, puis le liseré du bloc.
                val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))
                drawLine(color, Offset(1.dp.toPx(), -6.dp.toPx()), Offset(1.dp.toPx(), 0f), 1.5.dp.toPx(), pathEffect = dash)
                drawLine(color, Offset(1.dp.toPx(), 0f), Offset(1.dp.toPx(), size.height), 2.dp.toPx())
            }
            .padding(start = 9.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(block.text, style = BrutType.Body.copy(fontSize = 13.sp, lineHeight = 16.sp), color = if (block.severe) text else p.ink)
    }
}
