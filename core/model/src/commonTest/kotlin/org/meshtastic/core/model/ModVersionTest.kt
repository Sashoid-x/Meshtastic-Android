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

        val v5 = ModVersion.parse("2.8.4-advanced-r3")
        assertNotNull(v5)
        assertEquals(2, v5.major)
        assertEquals(8, v5.minor)
        assertEquals(4, v5.patch)
        assertEquals(3, v5.revision)

        val v6 = ModVersion.parse("v2.8.4-advanced-2")
        assertNotNull(v6)
        assertEquals(2, v6.major)
        assertEquals(8, v6.minor)
        assertEquals(4, v6.patch)
        assertEquals(2, v6.revision)

        val v7 = ModVersion.parse("2.8.4-release")
        assertNotNull(v7)
        assertEquals(2, v7.major)
        assertEquals(8, v7.minor)
        assertEquals(4, v7.patch)
        assertEquals(0, v7.revision)

        val v8 = ModVersion.parse("2.8.4-r3")
        assertNotNull(v8)
        assertEquals(3, v8.revision)

        assertNull(ModVersion.parse("invalid-version"))
    }

    @Test
    fun `isNewer compares advanced revisions accurately`() {
        assertTrue(ModVersion.isNewer(latest = "v2.8.2-advanced-6", current = "v2.8.2-advanced-5"))
        assertTrue(ModVersion.isNewer(latest = "v2.8.2-advanced-5", current = "2.8.2-adv"))
        assertTrue(ModVersion.isNewer(latest = "v2.8.3", current = "v2.8.2-advanced-5"))
        assertTrue(ModVersion.isNewer(latest = "v2.9.0", current = "v2.8.2-advanced-5"))
        assertTrue(ModVersion.isNewer(latest = "v3.0.0", current = "v2.8.2-advanced-5"))
        assertTrue(ModVersion.isNewer(latest = "v2.8.4-advanced-r4", current = "2.8.4-advanced-r3"))

        assertFalse(ModVersion.isNewer(latest = "v2.8.4-advanced-2", current = "2.8.4-advanced-r3"))
        assertFalse(ModVersion.isNewer(latest = "v2.8.4-release", current = "2.8.4-advanced-r3"))
        assertFalse(ModVersion.isNewer(latest = "v2.8.2-advanced-5", current = "v2.8.2-advanced-5"))
        assertFalse(ModVersion.isNewer(latest = "v2.8.2-advanced-4", current = "v2.8.2-advanced-5"))
        assertFalse(ModVersion.isNewer(latest = "v2.8.1", current = "v2.8.2-advanced-5"))
        assertFalse(ModVersion.isNewer(latest = "v2.8.2-advanced-5", current = "v2.8.2-advanced-6"))
    }
}
