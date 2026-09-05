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
package org.meshtastic.feature.messaging.filetransfer

import org.meshtastic.proto.Config.LoRaConfig.ModemPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Suppress("MagicNumber")
class MftTimingProfileTest {

    @Test
    fun shortTurbo_hasFastestTimings() {
        val profile = MftTimingProfile.forPreset(ModemPreset.SHORT_TURBO)
        assertEquals(250L, profile.interChunkDelayMs)
        assertEquals(6_000L, profile.startAckTimeoutMs)
        assertEquals(3_500L, profile.passResponseTimeoutMs)
        assertEquals(1_800L, profile.idleCheckDelayMs)
        assertEquals(100L, profile.cancelBurstDelayMs)
    }

    @Test
    fun longFast_scalesUpTimingsForLoRaPhysics() {
        val profile = MftTimingProfile.forPreset(ModemPreset.LONG_FAST)
        assertEquals(3_500L, profile.interChunkDelayMs)
        assertEquals(25_000L, profile.startAckTimeoutMs)
        assertEquals(18_000L, profile.passResponseTimeoutMs)
        assertEquals(14_000L, profile.idleCheckDelayMs)
        assertEquals(800L, profile.cancelBurstDelayMs)
    }

    @Test
    fun veryLongSlow_handlesExtremeAirtime() {
        val profile = MftTimingProfile.forPreset(ModemPreset.VERY_LONG_SLOW)
        assertEquals(22_000L, profile.interChunkDelayMs)
        assertEquals(120_000L, profile.startAckTimeoutMs)
        assertEquals(90_000L, profile.passResponseTimeoutMs)
        assertEquals(70_000L, profile.idleCheckDelayMs)
        assertEquals(5_000L, profile.cancelBurstDelayMs)
    }

    @Test
    fun nullPreset_defaultsToShortTurboSafe() {
        val profile = MftTimingProfile.forPreset(null)
        assertEquals(250L, profile.interChunkDelayMs)
        assertEquals(6_000L, profile.startAckTimeoutMs)
    }

    @Test
    fun fromCustom_calculatesRealisticTimings() {
        // SF7, BW 500kHz custom
        val fastCustom = MftTimingProfile.fromCustom(spreadFactor = 7, bandwidthKhz = 500)
        assertTrue(fastCustom.interChunkDelayMs in 250L..500L)
        assertTrue(fastCustom.startAckTimeoutMs >= 6_000L)

        // SF12, BW 125kHz custom
        val slowCustom = MftTimingProfile.fromCustom(spreadFactor = 12, bandwidthKhz = 125)
        assertTrue(slowCustom.interChunkDelayMs > fastCustom.interChunkDelayMs)
        assertTrue(slowCustom.startAckTimeoutMs > fastCustom.startAckTimeoutMs)
    }

    @Test
    fun allProfiles_haveLogicalOrdering() {
        val presets = ModemPreset.entries + listOf(null)
        for (preset in presets) {
            val profile = MftTimingProfile.forPreset(preset)
            assertTrue(profile.interChunkDelayMs > 0L)
            assertTrue(profile.cancelBurstDelayMs > 0L)
            assertTrue(profile.passResponseTimeoutMs > profile.interChunkDelayMs)
            assertTrue(profile.startAckTimeoutMs > profile.passResponseTimeoutMs)
            assertTrue(profile.idleCheckDelayMs > profile.interChunkDelayMs)
        }
    }
}
