package com.gabrielifrim.brut.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.gabrielifrim.brut.R

/**
 * Palette « Console analogique » : le graphite chaud d'une face avant de console,
 * la sérigraphie crème, l'ambre des lampes témoins. Le vert, l'ambre et le rouge
 * des LED gardent leur sens de toujours (bon niveau, attention, saturation).
 */
object BrutColors {
    val Graphite = Color(0xFF14120F)
    val Panel = Color(0xFF221E19)
    val PanelRaised = Color(0xFF2C2620)
    val Recess = Color(0xFF0D0C0A)
    val Edge = Color(0xFF3A332B)
    val Cream = Color(0xFFEFE6D6)
    val CreamDim = Color(0xFF9C9284)
    val CreamFaint = Color(0xFF5E574E)
    val Amber = Color(0xFFFFB238)
    val Green = Color(0xFF9BC53D)
    val Red = Color(0xFFFF4B3E)
}

object BrutFonts {
    val Mono = FontFamily(
        Font(R.font.plex_mono_medium, FontWeight.Medium),
        Font(R.font.plex_mono_semibold, FontWeight.SemiBold),
    )
    val Condensed = FontFamily(
        Font(R.font.plex_condensed_regular, FontWeight.Normal),
        Font(R.font.plex_condensed_medium, FontWeight.Medium),
        Font(R.font.plex_condensed_semibold, FontWeight.SemiBold),
    )
}

/** Styles nommés par usage, pas par taille : on sait où chacun va. */
object BrutType {
    /** Sérigraphie : petites capitales espacées, comme sous un potard. */
    val Legend = TextStyle(fontFamily = BrutFonts.Condensed, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.6.sp)
    val Body = TextStyle(fontFamily = BrutFonts.Condensed, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp)
    val BodyStrong = TextStyle(fontFamily = BrutFonts.Condensed, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    val Title = TextStyle(fontFamily = BrutFonts.Condensed, fontWeight = FontWeight.SemiBold, fontSize = 24.sp)
    val Readout = TextStyle(fontFamily = BrutFonts.Mono, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    val ReadoutSmall = TextStyle(fontFamily = BrutFonts.Mono, fontWeight = FontWeight.Medium, fontSize = 10.sp)
    val Clock = TextStyle(fontFamily = BrutFonts.Mono, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, letterSpacing = (-1).sp)
}

@Composable
fun BrutTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = BrutColors.Amber,
            onPrimary = BrutColors.Graphite,
            background = BrutColors.Graphite,
            onBackground = BrutColors.Cream,
            surface = BrutColors.Panel,
            onSurface = BrutColors.Cream,
            surfaceContainerLow = BrutColors.Panel,
            error = BrutColors.Red,
        ),
    ) {
        // Aucun Surface Material à la racine : la couleur de texte par défaut est posée ici.
        CompositionLocalProvider(LocalContentColor provides BrutColors.Cream, content = content)
    }
}
