package com.gabrielifrim.brut.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Le widget, la notification et le fond de démarrage lisent des couleurs de ressources :
 * elles doivent rester celles des palettes Kotlin (values = Plein jour, values-night = Scène).
 */
class PaletteResourcesTest {

    @Test
    fun `les couleurs de ressources suivent les palettes`() {
        check("values", Palettes.PleinJour)
        check("values-night", Palettes.Scene)
    }

    private fun check(folder: String, p: BrutPalette) {
        val xml = File("src/main/res/$folder/colors.xml").readText()
        val colors = Regex("""<color name="(\w+)">#([0-9A-Fa-f]{8})</color>""").findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2].toLong(16).toInt() }
        mapOf(
            "brut_facade" to p.facade, "brut_plate" to p.plate, "brut_recess" to p.recess, "brut_edge" to p.edge,
            "brut_ink" to p.ink, "brut_ink_dim" to p.inkDim, "brut_ink_faint" to p.inkFaint,
            "brut_accent" to p.accent, "brut_lamp" to p.lamp, "brut_clip" to p.clip,
        ).forEach { (name, color: Color) ->
            assertWithMessage("$folder/$name").that(colors[name]).isEqualTo(color.toArgb())
        }
    }
}
