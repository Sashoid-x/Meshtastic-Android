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
package org.meshtastic.core.common.util

import kotlin.test.Test
import kotlin.test.assertEquals

class HopReactionFormatterTest {

    @Test
    fun formatsKeycapEmojisForHopsOneToSeven() {
        assertEquals("1️⃣", HopReactionFormatter.format(hops = 1, snr = 10f, rssi = -60))
        assertEquals("2️⃣", HopReactionFormatter.format(hops = 2, snr = 5f, rssi = -80))
        assertEquals("3️⃣", HopReactionFormatter.format(hops = 3, snr = null, rssi = null))
        assertEquals("4️⃣", HopReactionFormatter.format(hops = 4, snr = null, rssi = null))
        assertEquals("5️⃣", HopReactionFormatter.format(hops = 5, snr = null, rssi = null))
        assertEquals("6️⃣", HopReactionFormatter.format(hops = 6, snr = null, rssi = null))
        assertEquals("7️⃣", HopReactionFormatter.format(hops = 7, snr = null, rssi = null))
    }

    @Test
    fun clampsAboveSevenHopsToSeven() {
        assertEquals("7️⃣", HopReactionFormatter.format(hops = 8, snr = null, rssi = null))
        assertEquals("7️⃣", HopReactionFormatter.format(hops = 12, snr = null, rssi = null))
    }

    @Test
    fun formatsTargetWithSnrAndRssiWhenDirect() {
        assertEquals("🎯 8.5dB/-75dBm", HopReactionFormatter.format(hops = 0, snr = 8.5f, rssi = -75))
        assertEquals("🎯 12.3dB/-60dBm", HopReactionFormatter.format(hops = null, snr = 12.34f, rssi = -60))
    }

    @Test
    fun formatsTargetWithOnlySnrOrOnlyRssi() {
        assertEquals("🎯 9.5dB", HopReactionFormatter.format(hops = 0, snr = 9.5f, rssi = null))
        assertEquals("🎯 -80dBm", HopReactionFormatter.format(hops = 0, snr = null, rssi = -80))
    }

    @Test
    fun formatsTargetAloneWhenNoSignalMetrics() {
        assertEquals("🎯", HopReactionFormatter.format(hops = 0, snr = null, rssi = null))
        assertEquals("🎯", HopReactionFormatter.format(hops = null, snr = null, rssi = null))
    }
}
