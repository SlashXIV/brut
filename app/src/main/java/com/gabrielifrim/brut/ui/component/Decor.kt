package com.gabrielifrim.brut.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gabrielifrim.brut.ui.theme.BrutColors
import com.gabrielifrim.brut.ui.theme.BrutShapes
import com.gabrielifrim.brut.ui.theme.BrutType

/**
 * Titre de plaque ou de feuille : capitales gravées suivies d'un filet jusqu'au bord, comme
 * l'intitulé d'une section de console. Un seul nœud TalkBack, lu en casse normale.
 */
@Composable
fun PlateTitle(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = text
                heading()
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text.uppercase(), style = engraved(BrutType.Title), color = BrutColors.Ink)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(BrutColors.Edge))
    }
}

/**
 * Sélecteur à voyants : des touches côte à côte dans un creux, la position choisie relevée
 * et son voyant allumé. Pour les choix courts (palette, mouvement, tri).
 */
@Composable
fun <T> LampSelector(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    extra: @Composable (T) -> Unit = {},
) {
    val p = BrutColors.palette
    Row(
        modifier
            .fillMaxWidth()
            .recess()
            .clip(BrutShapes.PlateShape)
            .padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .then(if (on) Modifier.shadow(1.dp, BrutShapes.InsetShape) else Modifier)
                    .clip(BrutShapes.InsetShape)
                    .background(if (on) p.plateRaised else p.recess)
                    .selectable(selected = on, enabled = enabled, role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = 4.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(BrutShapes.Lens)
                        .background(if (on && enabled) p.lamp else p.inkFaint.copy(alpha = 0.35f)),
                )
                Text(
                    label(option).uppercase(),
                    style = BrutType.Action,
                    color = when {
                        !enabled -> p.inkFaint
                        on -> p.ink
                        else -> p.inkDim
                    },
                    textAlign = TextAlign.Center,
                )
                extra(option)
            }
        }
    }
}

/**
 * Touche d'en-tête : un petit bloc de tôle relevé, au même rayon que le reste. [modifier]
 * porte le clic et la sémantique, posés entre le fond et la marge intérieure.
 */
@Composable
fun PanelKey(text: String, modifier: Modifier) {
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clip(BrutShapes.InsetShape)
            .background(BrutColors.Plate)
            .border(1.dp, BrutColors.Edge, BrutShapes.InsetShape)
            .then(modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = engraved(BrutType.Action), color = BrutColors.Ink, maxLines = 1)
    }
}
