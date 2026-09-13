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
@file:Suppress("MagicNumber", "LongMethod")

package org.meshtastic.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.meshtastic.core.model.AdvThemeColors
import kotlin.math.abs

data class Hsl(val hue: Float, val saturation: Float, val lightness: Float)

fun Color.toHsl(): Hsl {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min

    val l = (max + min) / 2f
    val s = if (delta == 0f) 0f else delta / (1f - abs(2f * l - 1f))

    val h =
        when {
            delta == 0f -> 0f
            max == r -> (((g - b) / delta) % 6f) * 60f
            max == g -> (((b - r) / delta) + 2f) * 60f
            else -> (((r - g) / delta) + 4f) * 60f
        }.let { if (it < 0f) it + 360f else it }

    return Hsl(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
}

@Suppress("MagicNumber")
fun hslToColor(hue: Float, saturation: Float, lightness: Float, alpha: Float = 1f): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)

    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f

    val (r1, g1, b1) =
        when ((h / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0f, 1f),
    )
}

/**
 * Guarantees maximum contrast against [background] between White and Black, ensuring compliance with WCAG AA (>=
 * 4.5:1).
 */
fun safeOnColor(background: Color): Color {
    val whiteRatio = contrastRatio(Color.White, background)
    val blackRatio = contrastRatio(Color.Black, background)
    return if (whiteRatio >= blackRatio) Color.White else Color.Black
}

@Suppress("MagicNumber")
object AdvColorSchemeBuilder {
    fun build(colors: AdvThemeColors, darkTheme: Boolean = colors.darkBase): ColorScheme {
        val primarySeed = colors.primaryArgb?.let { Color(it) } ?: primaryLight
        val primaryHsl = primarySeed.toHsl()

        val secondarySeed =
            colors.secondaryArgb?.let { Color(it) }
                ?: hslToColor(
                    hue = (primaryHsl.hue + 40f) % 360f,
                    saturation = (primaryHsl.saturation * 0.7f).coerceIn(0.15f, 0.6f),
                    lightness = primaryHsl.lightness,
                )
        val secondaryHsl = secondarySeed.toHsl()

        val tertiarySeed =
            colors.tertiaryArgb?.let { Color(it) }
                ?: hslToColor(
                    hue = (primaryHsl.hue + 80f) % 360f,
                    saturation = (primaryHsl.saturation * 0.8f).coerceIn(0.2f, 0.7f),
                    lightness = primaryHsl.lightness,
                )
        val tertiaryHsl = tertiarySeed.toHsl()

        val baseHsl = colors.baseArgb?.let { Color(it).toHsl() }

        return if (darkTheme) {
            buildDark(primaryHsl, secondaryHsl, tertiaryHsl, baseHsl)
        } else {
            buildLight(primaryHsl, secondaryHsl, tertiaryHsl, baseHsl)
        }
    }

    private fun buildLight(primaryHsl: Hsl, secondaryHsl: Hsl, tertiaryHsl: Hsl, baseHsl: Hsl?): ColorScheme {
        val primary = hslToColor(primaryHsl.hue, primaryHsl.saturation.coerceAtLeast(0.3f), 0.38f)
        val onPrimary = safeOnColor(primary)
        val primaryContainer = hslToColor(primaryHsl.hue, (primaryHsl.saturation * 0.5f).coerceIn(0.15f, 0.5f), 0.88f)
        val onPrimaryContainer = safeOnColor(primaryContainer)

        val secondary = hslToColor(secondaryHsl.hue, secondaryHsl.saturation.coerceAtLeast(0.2f), 0.42f)
        val onSecondary = safeOnColor(secondary)
        val secondaryContainer =
            hslToColor(secondaryHsl.hue, (secondaryHsl.saturation * 0.4f).coerceIn(0.1f, 0.4f), 0.88f)
        val onSecondaryContainer = safeOnColor(secondaryContainer)

        val tertiary = hslToColor(tertiaryHsl.hue, tertiaryHsl.saturation.coerceAtLeast(0.25f), 0.40f)
        val onTertiary = safeOnColor(tertiary)
        val tertiaryContainer = hslToColor(tertiaryHsl.hue, (tertiaryHsl.saturation * 0.4f).coerceIn(0.1f, 0.4f), 0.90f)
        val onTertiaryContainer = safeOnColor(tertiaryContainer)

        val baseHue = baseHsl?.hue ?: primaryHsl.hue
        val normSat = (baseHsl?.saturation ?: 0.04f).coerceIn(0.02f, 0.15f)
        val baseLight = (baseHsl?.lightness ?: 0.97f).coerceIn(0.95f, 0.98f)

        val background = hslToColor(baseHue, normSat, baseLight)
        val surface = background
        val onSurface = safeOnColor(surface)
        val onBackground = onSurface
        val surfaceVariant = hslToColor(baseHue, (normSat + 0.04f).coerceAtMost(0.18f), 0.88f)
        val onSurfaceVariant = hslToColor(baseHue, (normSat * 0.8f).coerceIn(0.04f, 0.12f), 0.35f)
        val outline = hslToColor(baseHue, (normSat * 0.8f).coerceIn(0.04f, 0.12f), 0.50f)
        val outlineVariant = hslToColor(baseHue, normSat, 0.78f)

        return lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            error = errorLight,
            onError = onErrorLight,
            errorContainer = errorContainerLight,
            onErrorContainer = onErrorContainerLight,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = scrimLight,
            inverseSurface = hslToColor(baseHue, normSat, 0.20f),
            inverseOnSurface = hslToColor(baseHue, normSat, 0.95f),
            inversePrimary = hslToColor(primaryHsl.hue, primaryHsl.saturation, 0.75f),
            surfaceDim = hslToColor(baseHue, normSat, (baseLight - 0.11f).coerceIn(0.85f, 0.88f)),
            surfaceBright = Color.White,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = hslToColor(baseHue, normSat, (baseLight - 0.02f).coerceIn(0.94f, 0.96f)),
            surfaceContainer = hslToColor(baseHue, normSat, (baseLight - 0.04f).coerceIn(0.92f, 0.94f)),
            surfaceContainerHigh =
            hslToColor(baseHue, (normSat + 0.02f).coerceAtMost(0.18f), (baseLight - 0.07f).coerceIn(0.89f, 0.91f)),
            surfaceContainerHighest =
            hslToColor(baseHue, (normSat + 0.02f).coerceAtMost(0.18f), (baseLight - 0.10f).coerceIn(0.86f, 0.88f)),
        )
    }

    private fun buildDark(primaryHsl: Hsl, secondaryHsl: Hsl, tertiaryHsl: Hsl, baseHsl: Hsl?): ColorScheme {
        val primary = hslToColor(primaryHsl.hue, primaryHsl.saturation.coerceAtLeast(0.3f), 0.75f)
        val onPrimary = safeOnColor(primary)
        val primaryContainer = hslToColor(primaryHsl.hue, (primaryHsl.saturation * 0.7f).coerceIn(0.2f, 0.6f), 0.24f)
        val onPrimaryContainer = safeOnColor(primaryContainer)

        val secondary = hslToColor(secondaryHsl.hue, secondaryHsl.saturation.coerceAtLeast(0.2f), 0.75f)
        val onSecondary = safeOnColor(secondary)
        val secondaryContainer =
            hslToColor(secondaryHsl.hue, (secondaryHsl.saturation * 0.5f).coerceIn(0.15f, 0.5f), 0.24f)
        val onSecondaryContainer = safeOnColor(secondaryContainer)

        val tertiary = hslToColor(tertiaryHsl.hue, tertiaryHsl.saturation.coerceAtLeast(0.25f), 0.75f)
        val onTertiary = safeOnColor(tertiary)
        val tertiaryContainer =
            hslToColor(tertiaryHsl.hue, (tertiaryHsl.saturation * 0.5f).coerceIn(0.15f, 0.5f), 0.24f)
        val onTertiaryContainer = safeOnColor(tertiaryContainer)

        val isAmoled = baseHsl != null && baseHsl.lightness <= 0.02f
        val baseHue = baseHsl?.hue ?: primaryHsl.hue
        val normSat = (baseHsl?.saturation ?: 0.05f).coerceIn(0.02f, 0.20f)
        val baseLight = (baseHsl?.lightness ?: 0.08f).coerceIn(0.04f, 0.09f)

        val background: Color
        val surface: Color
        val surfaceDim: Color
        val surfaceBright: Color
        val surfaceContainerLowest: Color
        val surfaceContainerLow: Color
        val surfaceContainer: Color
        val surfaceContainerHigh: Color
        val surfaceContainerHighest: Color
        val surfaceVariant: Color
        val outline: Color
        val outlineVariant: Color

        if (isAmoled) {
            background = Color.Black
            surface = Color.Black
            surfaceDim = Color.Black
            surfaceBright = Color(0xFF383838)
            surfaceContainerLowest = Color.Black
            surfaceContainerLow = Color(0xFF0E0E0E)
            surfaceContainer = Color(0xFF161616)
            surfaceContainerHigh = Color(0xFF222222)
            surfaceContainerHighest = Color(0xFF2C2C2C)
            surfaceVariant = Color(0xFF222222)
            outline = Color(0xFF757575)
            outlineVariant = Color(0xFF424242)
        } else {
            background = hslToColor(baseHue, normSat, baseLight)
            surface = background
            surfaceDim = hslToColor(baseHue, normSat, (baseLight - 0.02f).coerceAtLeast(0.03f))
            surfaceBright = hslToColor(baseHue, normSat + 0.04f, baseLight + 0.15f)
            surfaceContainerLowest = hslToColor(baseHue, normSat, (baseLight - 0.03f).coerceAtLeast(0.02f))
            surfaceContainerLow = hslToColor(baseHue, normSat, baseLight + 0.02f)
            surfaceContainer = hslToColor(baseHue, (normSat + 0.02f).coerceAtMost(0.24f), baseLight + 0.05f)
            surfaceContainerHigh = hslToColor(baseHue, (normSat + 0.03f).coerceAtMost(0.24f), baseLight + 0.08f)
            surfaceContainerHighest = hslToColor(baseHue, (normSat + 0.04f).coerceAtMost(0.24f), baseLight + 0.12f)
            surfaceVariant = hslToColor(baseHue, (normSat + 0.03f).coerceAtMost(0.24f), 0.22f)
            outline = hslToColor(baseHue, (normSat * 0.6f).coerceIn(0.04f, 0.12f), 0.55f)
            outlineVariant = hslToColor(baseHue, (normSat * 0.6f).coerceIn(0.04f, 0.12f), 0.26f)
        }

        val onSurface = safeOnColor(surface)
        val onBackground = onSurface
        val onSurfaceVariant = hslToColor(baseHue, (normSat * 0.5f).coerceIn(0.02f, 0.10f), 0.75f)

        return darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            error = errorDark,
            onError = onErrorDark,
            errorContainer = errorContainerDark,
            onErrorContainer = onErrorContainerDark,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = scrimDark,
            inverseSurface = hslToColor(baseHue, normSat, 0.90f),
            inverseOnSurface = hslToColor(baseHue, normSat, 0.15f),
            inversePrimary = hslToColor(primaryHsl.hue, primaryHsl.saturation, 0.40f),
            surfaceDim = surfaceDim,
            surfaceBright = surfaceBright,
            surfaceContainerLowest = surfaceContainerLowest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
        )
    }
}
