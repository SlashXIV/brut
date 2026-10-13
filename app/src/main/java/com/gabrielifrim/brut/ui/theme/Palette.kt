package com.gabrielifrim.brut.ui.theme

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.gabrielifrim.brut.R

/**
 * Une palette donne un rôle à chaque couleur de la façade, hors Material : c'est elle qui fait
 * l'identité, Material n'en reçoit qu'un schéma dérivé. Ajouter une palette = une entrée dans
 * [Palettes.all] ; `PaletteContrastTest` la vérifie aussitôt (contrastes et daltonisme).
 */
@Immutable
data class BrutPalette(
    /** Identifiant persisté : ne jamais le renommer. */
    val id: String,
    @StringRes val nameRes: Int,
    val isDark: Boolean,
    /** Façade du châssis (haut du dégradé) et son bas. */
    val facade: Color,
    val facadeDeep: Color,
    /** Tôle des plaques (bas du dégradé) et son reflet haut. */
    val plate: Color,
    val plateRaised: Color,
    /** Creux de la façade : pont de mesure, fentes, champs de saisie. */
    val recess: Color,
    /** Fond des afficheurs (chiffres, valeurs). */
    val display: Color,
    val edge: Color,
    /** Ombres portées et joints ; reflets et lèvres. */
    val shadow: Color,
    val highlight: Color,
    /** Sérigraphie : texte principal, secondaire, et repères (gros chiffres, graduations, désactivé). */
    val ink: Color,
    val inkDim: Color,
    val inkFaint: Color,
    /** Ambre lisible en texte (valeurs, choix actifs). */
    val accent: Color,
    /** Ambre des voyants et des LED « attention » : un éclairage, pas un texte. */
    val lamp: Color,
    /** Bon niveau (LED vertes, verdicts favorables). */
    val level: Color,
    /** Saturation, enregistrement, perte : le rouge qui ne se discute pas. */
    val clip: Color,
)

object Palettes {
    /** Choix enregistré « suivre le thème du téléphone ». */
    const val AUTO = "auto"

    val Scene = BrutPalette(
        id = "scene", nameRes = R.string.palette_scene, isDark = true,
        facade = Color(0xFF16130F), facadeDeep = Color(0xFF0E0C0A),
        plate = Color(0xFF221E19), plateRaised = Color(0xFF2E2822),
        recess = Color(0xFF0D0C0A), display = Color(0xFF0B0A08), edge = Color(0xFF3A332B),
        shadow = Color.Black, highlight = Color.White,
        ink = Color(0xFFEFE6D6), inkDim = Color(0xFFA89E90), inkFaint = Color(0xFF7E766A),
        accent = Color(0xFFFFB238), lamp = Color(0xFFFFB238),
        // Vert phosphore plutôt que vert pomme : l'ancien #9BC53D se confondait avec l'ambre
        // pour un protanope (ΔE 0,7).
        level = Color(0xFF2FC7A3), clip = Color(0xFFFF5A4A),
    )

    val PleinJour = BrutPalette(
        id = "plein_jour", nameRes = R.string.palette_plein_jour, isDark = false,
        facade = Color(0xFFE8E3D8), facadeDeep = Color(0xFFDCD6C9),
        plate = Color(0xFFF2EEE5), plateRaised = Color(0xFFFAF7F1),
        recess = Color(0xFFEBE6DC), display = Color(0xFFE5DFD3), edge = Color(0xFFB5AC9C),
        shadow = Color(0xFF3C2D14), highlight = Color.White,
        ink = Color(0xFF17140F), inkDim = Color(0xFF544C42), inkFaint = Color(0xFF746B5E),
        accent = Color(0xFF865600), lamp = Color(0xFFAF7300),
        level = Color(0xFF066C59), clip = Color(0xFFC0182E),
    )

    val all = listOf(Scene, PleinJour)

    /** Palette d'un choix enregistré ; un identifiant inconnu (ou « auto ») suit le thème du système. */
    fun resolve(choice: String?, systemDark: Boolean): BrutPalette =
        all.firstOrNull { it.id == choice } ?: if (systemDark) Scene else PleinJour
}

/** Mélange de deux palettes, pour passer de l'une à l'autre en fondu. */
fun lerp(a: BrutPalette, b: BrutPalette, t: Float): BrutPalette = if (t >= 1f) b else b.copy(
    facade = lerp(a.facade, b.facade, t), facadeDeep = lerp(a.facadeDeep, b.facadeDeep, t),
    plate = lerp(a.plate, b.plate, t), plateRaised = lerp(a.plateRaised, b.plateRaised, t),
    recess = lerp(a.recess, b.recess, t), display = lerp(a.display, b.display, t), edge = lerp(a.edge, b.edge, t),
    shadow = lerp(a.shadow, b.shadow, t), highlight = lerp(a.highlight, b.highlight, t),
    ink = lerp(a.ink, b.ink, t), inkDim = lerp(a.inkDim, b.inkDim, t), inkFaint = lerp(a.inkFaint, b.inkFaint, t),
    accent = lerp(a.accent, b.accent, t), lamp = lerp(a.lamp, b.lamp, t),
    level = lerp(a.level, b.level, t), clip = lerp(a.clip, b.clip, t),
)

/** Encre lisible sur un voyant allumé (rouge, ambre, vert) : la plus contrastée des deux. */
fun legibleOn(lit: Color): Color {
    val dark = Color(0xFF14110E)
    val l = lit.luminance()
    return if ((l + 0.05f) / (dark.luminance() + 0.05f) >= 1.05f / (l + 0.05f)) dark else Color.White
}
