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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModVersionTest {

    @Test
    fun `parse versions with advanced revisions correctly`() {
        val v1 = ModVersion.parse("v2.8.2-advanced-5")
        assertNotNull(v1)
        assertEquals(2, v1.major)
        assertEquals(8, v1.minor)
        assertEquals(2, v1.patch)
        assertEquals(5, v1.revision)

        val v2 = ModVersion.parse("2.8.2-adv-3")
        assertNotNull(v2)
        assertEquals(2, v2.major)
        assertEquals(8, v2.minor)
        assertEquals(2, v2.patch)
        assertEquals(3, v2.revision)

        val v3 = ModVersion.parse("2.8.2-adv")
        assertNotNull(v3)
        assertEquals(0, v3.revision)

        val v4 = ModVersion.parse("2.8.0")
        assertNotNull(v4)
        assertEquals(2, v4.major)
        assertEquals(8, v4.minor)
        assertEquals(0, v4.patch)
        assertEquals(0, v4.revision)

        assertNull(ModVersion.parse("invalid-version"))
    }

    @Test
    fun `isNewer compares advanced revisions accurately`() {
        assertTrue(ModVersion.isNewer(latest = "v2.8.2-advanced-6", current = "v2.8.2-advanced-5"))
        assertTrue(ModVersion.isNewer(latest = "v2.8.2-advanced-5", current = "2.8.2-adv"))
        assertTrue(ModVersion.isNewer(latest = "v2.8.3", current = "v2.8.2-advanced-5"))
        assertTrue(ModVersion.isNewer(latest = "v2.9.0", current = "v2.8.2-advanced-5"))
        assertTrue(ModVersion.isNewer(latest = "v3.0.0", current = "v2.8.2-advanced-5"))

        assertFalse(ModVersion.isNewer(latest = "v2.8.2-advanced-5", current = "v2.8.2-advanced-5"))
        assertFalse(ModVersion.isNewer(latest = "v2.8.2-advanced-4", current = "v2.8.2-advanced-5"))
        assertFalse(ModVersion.isNewer(latest = "v2.8.1", current = "v2.8.2-advanced-5"))
        assertFalse(ModVersion.isNewer(latest = "v2.8.2-advanced-5", current = "v2.8.2-advanced-6"))
    }
}
