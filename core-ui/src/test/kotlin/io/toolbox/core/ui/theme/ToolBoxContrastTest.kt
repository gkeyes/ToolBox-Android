package io.toolbox.core.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolBoxContrastTest {
    @Test
    fun standardThemesKeepSmallTextAndActionLabelsReadable() {
        for (colors in listOf(LightToolBoxColors, DarkToolBoxColors)) {
            assertTrue(contrastRatio(colors.primary, colors.onPrimary) >= 4.5f)
            assertTrue(contrastRatio(colors.danger, colors.onDanger) >= 4.5f)
            for (surface in listOf(colors.background, colors.surface, colors.surfaceMuted)) {
                assertTrue(contrastRatio(colors.textSecondary, surface) >= 4.5f)
            }
        }
    }

    @Test
    fun dynamicButtonColorsPreserveReadableChoicesAndRepairLowContrast() {
        assertEquals(Color.White, readableForeground(Color.White, listOf(Color.Black)))
        for (r in 0..255 step 51) for (g in 0..255 step 51) for (b in 0..255 step 51) {
            val background = Color(r, g, b)
            assertTrue(contrastRatio(readableForeground(background, listOf(background)), background) >= 4.5f)
        }
    }

    @Test
    fun multipleSurfacesPreserveReadableForegroundOrUseSaferNeutral() {
        val colors = LightToolBoxColors
        val surfaces = listOf(colors.background, colors.surface, colors.surfaceMuted)
        assertEquals(colors.textSecondary, readableForeground(colors.textSecondary, surfaces))
        assertEquals(Color.Black, readableForeground(Color.White, surfaces))
    }

    @Test
    fun liquidGlassKeepsNeutralSurfacesReadableAcrossRepresentativeSystemAccents() {
        val accents = listOf(Color(0xFF0A84FF), Color(0xFF30B05A), Color(0xFFAF52DE))
        for (dark in listOf(false, true)) for (accent in accents) {
            val colors = liquidGlassColors(dark, accent, accentForeground = accent)
            assertEquals(if (dark) Color.Black else Color(0xFFF2F2F7), colors.background)
            for (surface in listOf(colors.background, colors.surface, colors.surfaceMuted)) {
                assertTrue(contrastRatio(colors.textPrimary, surface) >= 4.5f)
                assertTrue(contrastRatio(colors.textSecondary, surface) >= 4.5f)
            }
            assertTrue(contrastRatio(colors.primary, colors.onPrimary) >= 4.5f)
        }
    }

    @Test
    fun navigationSelectionRemainsReadableWithoutDependingOnBlur() {
        for (dark in listOf(false, true)) {
            for (accent in listOf(Color(0xFF1264CC), Color(0xFF30B05A), Color(0xFFAF52DE))) {
                val colors = liquidGlassColors(dark, accent, accent)
                for (reduced in listOf(false, true)) {
                    val material = liquidGlassMaterials(colors, dark, true, reduced)
                    // An opaque selected lens stays distinct even over a white blur fallback.
                    assertEquals(1f, material.navigationSelectionTint.alpha)
                    assertTrue(contrastRatio(material.navigationSelectionTint, colors.surface) > 1.1f)
                    val foreground = readableForeground(colors.primary, listOf(material.navigationSelectionTint, colors.surface))
                    assertTrue(contrastRatio(foreground, material.navigationSelectionTint) >= 4.5f)
                }
            }
        }
    }

    @Test
    fun reduceTransparencyUsesTheSameOpaqueMaterialFallback() {
        val colors = liquidGlassColors(false, Color(0xFF0A84FF), Color.White)
        val glass = liquidGlassMaterials(colors, dark = false, enabled = true, reduceTransparency = false)
        val solid = liquidGlassMaterials(colors, dark = false, enabled = true, reduceTransparency = true)
        assertTrue(glass.realBlurEnabled)
        assertTrue(!solid.realBlurEnabled)
        assertTrue(glass.topEdgeFadeEnabled)
        assertTrue(!solid.topEdgeFadeEnabled)
        assertTrue(glass.navigationLensEnabled)
        assertTrue(solid.navigationLensEnabled)
        assertTrue(glass.pressFeedbackEnabled)
        assertTrue(solid.pressFeedbackEnabled)
        assertEquals(glass.glassFallback, solid.glassFallback)
        assertEquals(1f, solid.glassFallback.alpha)
        assertTrue(glass.glassTint.alpha < 1f)
        assertEquals(solid.glassFallback, solid.glassTint)
    }
}
