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
package org.meshtastic.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdvThemeColorsTest {

    @Test
    fun defaultValues_areAllNullAndLightBase() {
        val default = AdvThemeColors.DEFAULT
        assertNull(default.primaryArgb)
        assertNull(default.secondaryArgb)
        assertNull(default.tertiaryArgb)
        assertNull(default.baseArgb)
        assertFalse(default.darkBase)
    }

    @Test
    fun fromJson_emptyOrBlank_returnsDefault() {
        assertEquals(AdvThemeColors.DEFAULT, AdvThemeColors.fromJson(""))
        assertEquals(AdvThemeColors.DEFAULT, AdvThemeColors.fromJson("   "))
    }

    @Test
    fun fromJson_invalidJson_returnsDefault() {
        assertEquals(AdvThemeColors.DEFAULT, AdvThemeColors.fromJson("{invalid json"))
    }

    @Test
    fun serialization_roundTrip_preservesValues() {
        val original =
            AdvThemeColors(
                primaryArgb = 0xFF2D8F52.toInt(),
                secondaryArgb = 0xFF555668.toInt(),
                tertiaryArgb = null,
                baseArgb = 0xFF121A24.toInt(),
                darkBase = true,
            )

        val json = original.toJson()
        val restored = AdvThemeColors.fromJson(json)

        assertEquals(original, restored)
        assertEquals(0xFF2D8F52.toInt(), restored.primaryArgb)
        assertEquals(0xFF555668.toInt(), restored.secondaryArgb)
        assertNull(restored.tertiaryArgb)
        assertEquals(0xFF121A24.toInt(), restored.baseArgb)
        assertTrue(restored.darkBase)
    }
}
