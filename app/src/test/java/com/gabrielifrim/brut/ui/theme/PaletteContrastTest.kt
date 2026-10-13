package com.gabrielifrim.brut.ui.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Chaque palette du registre est vérifiée ici : en ajouter une ne demande aucun test de plus.
 * Seuils : texte principal 7:1, texte secondaire et texte en couleur 4,5:1, repères et
 * mesures 3:1 (WCAG). Les trois couleurs de mesure doivent rester distinctes pour un
 * protanope ou un deutéranope (Machado 2009, sévérité 1, ΔE OKLab × 100 ≥ 8 : le seuil du
 * validateur de palettes utilisé pour les choisir).
 */
class PaletteContrastTest {

    @Test
    fun `le texte principal se lit partout`() = each { p ->
        listOf(p.facade, p.facadeDeep, p.plate, p.plateRaised, p.recess, p.display).forEach { bg ->
            p.check("encre", p.ink, bg, 7.0)
        }
    }

    @Test
    fun `le texte secondaire et le texte en couleur se lisent sur les plaques et les afficheurs`() = each { p ->
        listOf(p.facade, p.plate, p.plateRaised, p.recess).forEach { bg -> p.check("encre secondaire", p.inkDim, bg, 4.5) }
        listOf(p.facade, p.plate, p.plateRaised, p.display).forEach { bg ->
            p.check("ambre (texte)", p.accent, bg, 4.5)
            p.check("saturation (texte)", p.clip, bg, 4.5)
            p.check("niveau (texte)", p.level, bg, 4.5)
        }
    }

    @Test
    fun `repères et mesures se voient sur la façade et dans le pont de mesure`() = each { p ->
        listOf(p.facade, p.plate, p.recess).forEach { bg ->
            p.check("repère", p.inkFaint, bg, 3.0)
            p.check("voyant ambre", p.lamp, bg, 3.0)
            p.check("niveau", p.level, bg, 3.0)
            p.check("saturation", p.clip, bg, 3.0)
        }
    }

    @Test
    fun `niveau, attention et saturation restent distincts pour les daltoniens`() = each { p ->
        val pairs = listOf("niveau/ambre" to (p.level to p.lamp), "ambre/saturation" to (p.lamp to p.clip), "niveau/saturation" to (p.level to p.clip))
        for ((name, pair) in pairs) for ((cvd, m) in listOf("protan" to PROTAN, "deutan" to DEUTAN)) {
            val d = deltaE(simulate(pair.first, m), simulate(pair.second, m))
            assertWithMessage("${p.id} : $name en $cvd, ΔE = %.1f, minimum 8".format(d)).that(d).isAtLeast(8.0)
        }
    }

    @Test
    fun `identifiants uniques et repli sur le thème du système`() {
        assertThat(Palettes.all.map { it.id }).containsNoDuplicates()
        assertThat(Palettes.resolve(Palettes.AUTO, systemDark = true)).isEqualTo(Palettes.Scene)
        assertThat(Palettes.resolve(Palettes.AUTO, systemDark = false)).isEqualTo(Palettes.PleinJour)
        assertThat(Palettes.resolve("inconnue", systemDark = false)).isEqualTo(Palettes.PleinJour)
        assertThat(Palettes.resolve(Palettes.Scene.id, systemDark = false)).isEqualTo(Palettes.Scene)
        Palettes.all.forEach { assertThat(it.isDark).isEqualTo(it.ink.luminance() > it.facade.luminance()) }
    }

    private fun each(block: (BrutPalette) -> Unit) = Palettes.all.forEach(block)

    private fun BrutPalette.check(role: String, fg: Color, bg: Color, min: Double) {
        val r = contrast(fg, bg)
        assertWithMessage("$id : $role sur ${bg.hex()} = %.2f:1, minimum $min:1".format(r)).that(r).isAtLeast(min)
    }

    private companion object {
        val PROTAN = arrayOf(
            doubleArrayOf(0.152286, 1.052583, -0.204868),
            doubleArrayOf(0.114503, 0.786281, 0.099216),
            doubleArrayOf(-0.003882, -0.048116, 1.051998),
        )
        val DEUTAN = arrayOf(
            doubleArrayOf(0.367322, 0.860646, -0.227968),
            doubleArrayOf(0.280085, 0.672501, 0.047413),
            doubleArrayOf(-0.011820, 0.042940, 0.968881),
        )

        fun lin(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        fun Color.linear() = doubleArrayOf(lin(red), lin(green), lin(blue))
        fun Color.luminance() = linear().let { 0.2126 * it[0] + 0.7152 * it[1] + 0.0722 * it[2] }
        fun Color.hex() = "#%06X".format(0xFFFFFF and (((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()))

        fun contrast(a: Color, b: Color): Double {
            val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
            return (hi + 0.05) / (lo + 0.05)
        }

        /** Couleur vue par un daltonien : matrice appliquée en sRGB linéaire, ramenée dans 0..1. */
        fun simulate(c: Color, m: Array<DoubleArray>): DoubleArray {
            val v = c.linear()
            return DoubleArray(3) { i -> (m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2]).coerceIn(0.0, 1.0) }
        }

        fun oklab(rgb: DoubleArray): DoubleArray {
            val (r, g, b) = Triple(rgb[0], rgb[1], rgb[2])
            val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
            val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
            val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
            return doubleArrayOf(
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
            )
        }

        fun deltaE(a: DoubleArray, b: DoubleArray): Double {
            val (x, y) = oklab(a) to oklab(b)
            return 100 * sqrt((x[0] - y[0]).pow(2) + (x[1] - y[1]).pow(2) + (x[2] - y[2]).pow(2))
        }
    }
}
