package com.gabrielifrim.brut.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gabrielifrim.brut.R
import com.gabrielifrim.brut.ui.motion.LocalMotionLevel
import com.gabrielifrim.brut.ui.motion.Motion
import com.gabrielifrim.brut.ui.motion.MotionLevel

private val LocalPalette = staticCompositionLocalOf<BrutPalette> { error("Envelopper l'écran dans BrutTheme") }

/**
 * Couleurs de la palette active, nommées par rôle. Lues dans une fonction composable ; dans un
 * bloc de dessin, les lire avant (`val ink = BrutColors.Ink`).
 */
object BrutColors {
    val palette: BrutPalette @Composable @ReadOnlyComposable get() = LocalPalette.current
    val Facade: Color @Composable @ReadOnlyComposable get() = palette.facade
    val FacadeDeep: Color @Composable @ReadOnlyComposable get() = palette.facadeDeep
    val Plate: Color @Composable @ReadOnlyComposable get() = palette.plate
    val PlateRaised: Color @Composable @ReadOnlyComposable get() = palette.plateRaised
    val Recess: Color @Composable @ReadOnlyComposable get() = palette.recess
    val Display: Color @Composable @ReadOnlyComposable get() = palette.display
    val Edge: Color @Composable @ReadOnlyComposable get() = palette.edge
    val Shadow: Color @Composable @ReadOnlyComposable get() = palette.shadow
    val Highlight: Color @Composable @ReadOnlyComposable get() = palette.highlight
    val Ink: Color @Composable @ReadOnlyComposable get() = palette.ink
    val InkDim: Color @Composable @ReadOnlyComposable get() = palette.inkDim
    val InkFaint: Color @Composable @ReadOnlyComposable get() = palette.inkFaint
    val Accent: Color @Composable @ReadOnlyComposable get() = palette.accent
    val Lamp: Color @Composable @ReadOnlyComposable get() = palette.lamp
    val Level: Color @Composable @ReadOnlyComposable get() = palette.level
    val Clip: Color @Composable @ReadOnlyComposable get() = palette.clip
}

object BrutFonts {
    /** Chiffres d'afficheur : une mono à fût droit, comme sur un écran d'appareil de mesure. */
    val Display = FontFamily(Font(R.font.share_tech_mono, FontWeight.Normal))

    /** Sérigraphie de face avant : grotesque condensée, lisible en tout petit et en capitales. */
    val Engraving = FontFamily(
        Font(R.font.barlow_condensed_medium, FontWeight.Medium),
        Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
        Font(R.font.barlow_condensed_bold, FontWeight.Bold),
    )

    /** Texte courant (explications, messages) : la même famille, moins serrée. */
    val Text = FontFamily(
        Font(R.font.barlow_semicondensed_regular, FontWeight.Normal),
        Font(R.font.barlow_semicondensed_medium, FontWeight.Medium),
    )
}

/** Styles nommés par usage, pas par taille : on sait où chacun va. */
object BrutType {
    /** Sérigraphie : petites capitales espacées, comme sous un potard. */
    val Legend = TextStyle(fontFamily = BrutFonts.Engraving, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 1.8.sp)
    /** Valeur d'un nœud du chemin du signal (entrée, format, gain). */
    val Node = TextStyle(fontFamily = BrutFonts.Engraving, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 0.4.sp)
    val Body = TextStyle(fontFamily = BrutFonts.Text, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp)
    val BodyStrong = TextStyle(fontFamily = BrutFonts.Engraving, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, letterSpacing = 0.3.sp)
    val Title = TextStyle(fontFamily = BrutFonts.Engraving, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 1.5.sp)
    /** Touche d'action : légende plus grasse que la sérigraphie. */
    val Action = TextStyle(fontFamily = BrutFonts.Engraving, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.8.sp)
    val Readout = TextStyle(fontFamily = BrutFonts.Display, fontSize = 15.sp)
    val ReadoutSmall = TextStyle(fontFamily = BrutFonts.Display, fontSize = 12.sp)
    val Clock = TextStyle(fontFamily = BrutFonts.Display, fontSize = 52.sp, letterSpacing = (-0.5).sp)
}

/**
 * Un seul rayon pour tout ce qui est fixé sur la façade (plaques, pont de mesure, bandeaux,
 * touches) : c'est ce qui fait lire l'ensemble comme une seule pièce de tôle.
 */
object BrutShapes {
    val Plate = 6.dp
    /** Petites pièces dans une plaque : afficheurs, fentes, nœuds du chemin. */
    val Inset = 3.dp
    val PlateShape = RoundedCornerShape(Plate)
    val InsetShape = RoundedCornerShape(Inset)
    val Lens = CircleShape
}

/** La typographie Material passe dans les polices de Brut : ses rares composants ne détonnent pas. */
private val MaterialType = Typography().let { t ->
    fun TextStyle.engraved() = copy(fontFamily = BrutFonts.Engraving, fontWeight = FontWeight.Bold)
    fun TextStyle.text() = copy(fontFamily = BrutFonts.Text)
    t.copy(
        displayLarge = t.displayLarge.engraved(), displayMedium = t.displayMedium.engraved(), displaySmall = t.displaySmall.engraved(),
        headlineLarge = t.headlineLarge.engraved(), headlineMedium = t.headlineMedium.engraved(), headlineSmall = t.headlineSmall.engraved(),
        titleLarge = t.titleLarge.engraved(), titleMedium = t.titleMedium.engraved(), titleSmall = t.titleSmall.engraved(),
        bodyLarge = t.bodyLarge.text(), bodyMedium = t.bodyMedium.text(), bodySmall = t.bodySmall.text(),
        labelLarge = t.labelLarge.engraved(), labelMedium = t.labelMedium.engraved(), labelSmall = t.labelSmall.engraved(),
    )
}

private fun BrutPalette.materialScheme() = if (isDark) {
    darkColorScheme(
        primary = accent, onPrimary = facadeDeep, secondary = lamp, onSecondary = facadeDeep,
        background = facade, onBackground = ink, surface = plate, onSurface = ink,
        surfaceVariant = recess, onSurfaceVariant = inkDim, surfaceContainerLow = plate, surfaceContainer = plate,
        surfaceContainerHigh = plateRaised, outline = edge, outlineVariant = edge, error = clip, onError = ink,
    )
} else {
    lightColorScheme(
        primary = accent, onPrimary = plateRaised, secondary = lamp, onSecondary = plateRaised,
        background = facade, onBackground = ink, surface = plate, onSurface = ink,
        surfaceVariant = recess, onSurfaceVariant = inkDim, surfaceContainerLow = plate, surfaceContainer = plate,
        surfaceContainerHigh = plateRaised, outline = edge, outlineVariant = edge, error = clip, onError = plateRaised,
    )
}

@Composable
fun BrutTheme(
    palette: BrutPalette = Palettes.Scene,
    motion: MotionLevel = MotionLevel.FULL,
    content: @Composable () -> Unit,
) {
    // Changement de palette en fondu (pas de révélation : ni capture d'écran ni mémoire en plus).
    var from by remember { mutableStateOf(palette) }
    var to by remember { mutableStateOf(palette) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(palette) {
        if (palette == to) return@LaunchedEffect
        from = lerp(from, to, progress.value)
        to = palette
        if (motion.animates) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(Motion.TRANSITION_MS, easing = Motion.Transition))
        } else {
            progress.snapTo(1f)
        }
    }
    val shown = lerp(from, to, progress.value)
    MaterialTheme(colorScheme = shown.materialScheme(), typography = MaterialType) {
        // Aucun Surface Material à la racine : la couleur de texte par défaut est posée ici.
        CompositionLocalProvider(
            LocalPalette provides shown,
            LocalMotionLevel provides motion,
            LocalContentColor provides shown.ink,
            content = content,
        )
    }
}
