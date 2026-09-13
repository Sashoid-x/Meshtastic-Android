/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.core.ui.theme

import androidx.compose.ui.graphics.Color
import org.meshtastic.core.model.AdvThemeColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdvColorSchemeBuilderTest {

    @Test
    fun safeOnColor_alwaysSatisfiesWcagTextContrast() {
        val testColors =
            listOf(
                Color.White,
                Color.Black,
                Color.Red,
                Color.Green,
                Color.Blue,
                Color.Yellow,
                Color.Cyan,
                Color.Magenta,
                Color(0xFF2D8F52),
                Color(0xFF555668),
                Color(0xFF1E1E2E),
                Color(0xFFFAFAFA),
            )

        for (bg in testColors) {
            val onColor = safeOnColor(bg)
            val ratio = contrastRatio(onColor, bg)
            assertTrue(
                ratio >= MIN_TEXT_CONTRAST,
                "safeOnColor failed WCAG text contrast for background $bg (ratio: $ratio)",
            )
        }
    }

    @Test
    fun buildLightScheme_guaranteesKeyContrastRatios() {
        val colors =
            AdvThemeColors(
                primaryArgb = 0xFF2D8F52.toInt(),
                secondaryArgb = null,
                tertiaryArgb = null,
                darkBase = false,
            )

        val scheme = AdvColorSchemeBuilder.build(colors)

        assertTrue(contrastRatio(scheme.onPrimary, scheme.primary) >= MIN_TEXT_CONTRAST)
        assertTrue(contrastRatio(scheme.onPrimaryContainer, scheme.primaryContainer) >= MIN_TEXT_CONTRAST)
        assertTrue(contrastRatio(scheme.onSecondary, scheme.secondary) >= MIN_TEXT_CONTRAST)
        assertTrue(contrastRatio(scheme.onSecondaryContainer, scheme.secondaryContainer) >= MIN_TEXT_CONTRAST)
        assertTrue(contrastRatio(scheme.onSurface, scheme.surface) >= MIN_TEXT_CONTRAST)
    }

    @Test
    fun buildDarkScheme_guaranteesKeyContrastRatios() {
        val colors =
            AdvThemeColors(
                primaryArgb = 0xFF8A2BE2.toInt(), // Purple
                secondaryArgb = 0xFFFF69B4.toInt(), // Pink
                tertiaryArgb = null,
                darkBase = true,
            )

        val scheme = AdvColorSchemeBuilder.build(colors)

        assertTrue(contrastRatio(scheme.onPrimary, scheme.primary) >= MIN_TEXT_CONTRAST)
        assertTrue(contrastRatio(scheme.onPrimaryContainer, scheme.primaryContainer) >= MIN_TEXT_CONTRAST)
        assertTrue(contrastRatio(scheme.onSecondary, scheme.secondary) >= MIN_TEXT_CONTRAST)
        assertTrue(contrastRatio(scheme.onSecondaryContainer, scheme.secondaryContainer) >= MIN_TEXT_CONTRAST)
        assertTrue(contrastRatio(scheme.onSurface, scheme.surface) >= MIN_TEXT_CONTRAST)
    }

    @Test
    fun hslConversion_roundTrip() {
        val original = Color(0xFF2D8F52)
        val hsl = original.toHsl()
        val reconstructed = hslToColor(hsl.hue, hsl.saturation, hsl.lightness)

        // R, G, B should be within ~0.02 precision
        assertTrue(kotlin.math.abs(original.red - reconstructed.red) < 0.02f)
        assertTrue(kotlin.math.abs(original.green - reconstructed.green) < 0.02f)
        assertTrue(kotlin.math.abs(original.blue - reconstructed.blue) < 0.02f)
    }

    @Test
    fun buildDarkScheme_customBase_normalizesAndGuaranteesContrast() {
        val colors =
            AdvThemeColors(
                primaryArgb = 0xFF2D8F52.toInt(),
                baseArgb = 0xFF0D253A.toInt(), // Deep navy seed
                darkBase = true,
            )

        val scheme = AdvColorSchemeBuilder.build(colors)

        assertTrue(contrastRatio(scheme.onSurface, scheme.surface) >= MIN_TEXT_CONTRAST)
        assertTrue(contrastRatio(scheme.onBackground, scheme.background) >= MIN_TEXT_CONTRAST)
        // Surface should not be pure black for navy seed, but stay in dark range
        assertTrue(scheme.surface != Color.Black)
        assertTrue(scheme.surface.toHsl().lightness < 0.15f)
    }

    @Test
    fun buildDarkScheme_pureBlack_enablesAmoledBlack() {
        val colors =
            AdvThemeColors(
                primaryArgb = 0xFF2D8F52.toInt(),
                baseArgb = 0xFF000000.toInt(), // Pure black seed
                darkBase = true,
            )

        val scheme = AdvColorSchemeBuilder.build(colors)

        assertEquals(Color.Black, scheme.background)
        assertEquals(Color.Black, scheme.surface)
        assertEquals(Color.Black, scheme.surfaceDim)
        assertEquals(Color.Black, scheme.surfaceContainerLowest)
        assertTrue(contrastRatio(scheme.onSurface, scheme.surface) >= MIN_TEXT_CONTRAST)
    }

    @Test
    fun buildLightScheme_customBase_normalizesAndGuaranteesContrast() {
        val colors =
            AdvThemeColors(
                primaryArgb = 0xFF2D8F52.toInt(),
                baseArgb = 0xFFFFF8E7.toInt(), // Warm cream/cosmic latte seed
                darkBase = false,
            )

        val scheme = AdvColorSchemeBuilder.build(colors)

        assertTrue(contrastRatio(scheme.onSurface, scheme.surface) >= MIN_TEXT_CONTRAST)
        assertTrue(contrastRatio(scheme.onBackground, scheme.background) >= MIN_TEXT_CONTRAST)
        assertTrue(scheme.surface.toHsl().lightness >= 0.94f)
    }
}
