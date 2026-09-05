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

/**
 * Adaptive timing profile for Meshtastic File Transfer (MFT).
 *
 * Timings are dynamically chosen based on the active LoRa [ModemPreset] so that:
 * 1. [interChunkDelayMs] matches the physical time-on-air (ToA) + CAD + radio firmware turnaround. This prevents the
 *    hardware transmit queue (ESP32/nRF52) from overflowing and keeps UI transmission speed measurements in sync with
 *    actual air speed.
 * 2. [startAckTimeoutMs], [passResponseTimeoutMs], and [idleCheckDelayMs] scale proportionally with the preset's symbol
 *    duration and packet airtimes.
 */
@Suppress("MagicNumber")
data class MftTimingProfile(
    val interChunkDelayMs: Long,
    val startAckTimeoutMs: Long,
    val passResponseTimeoutMs: Long,
    val idleCheckDelayMs: Long,
    val cancelBurstDelayMs: Long,
) {
    companion object {
        private const val SYMBOLS_PER_PACKET = 300.0
        private const val DEFAULT_AIRTIME_EXPAND_FACTOR = 1.8
        private const val MIN_INTER_CHUNK_DELAY = 250L

        /**
         * Calculates timings dynamically for custom LoRa configurations (when use_preset = false).
         *
         * @param spreadFactor LoRa spreading factor (typically 7..12)
         * @param bandwidthKhz LoRa bandwidth in kHz (typically 62, 125, 250, 500)
         */
        fun fromCustom(spreadFactor: Int, bandwidthKhz: Int): MftTimingProfile {
            val sf = spreadFactor.coerceIn(7, 12)
            val bw = bandwidthKhz.coerceAtLeast(15) * 1_000.0
            val symbolDurationMs = ((1 shl sf) / bw) * 1_000.0
            val approxAirtimeMs = (symbolDurationMs * SYMBOLS_PER_PACKET).toLong()

            val interChunkDelay =
                (approxAirtimeMs * DEFAULT_AIRTIME_EXPAND_FACTOR).toLong().coerceAtLeast(MIN_INTER_CHUNK_DELAY)
            val startAckTimeout = (approxAirtimeMs * 12).coerceIn(6_000L, 120_000L)
            val passResponseTimeout = (approxAirtimeMs * 8).coerceIn(3_500L, 90_000L)
            val idleCheckDelay = (interChunkDelay * 4).coerceIn(1_800L, 70_000L)
            val cancelBurstDelay = (interChunkDelay / 3).coerceIn(100L, 5_000L)

            return MftTimingProfile(
                interChunkDelayMs = interChunkDelay,
                startAckTimeoutMs = startAckTimeout,
                passResponseTimeoutMs = passResponseTimeout,
                idleCheckDelayMs = idleCheckDelay,
                cancelBurstDelayMs = cancelBurstDelay,
            )
        }

        @Suppress("CyclomaticComplexMethod", "LongMethod")
        fun forPreset(preset: ModemPreset?): MftTimingProfile = when (preset) {
            ModemPreset.SHORT_TURBO ->
                MftTimingProfile(
                    interChunkDelayMs = 250L,
                    startAckTimeoutMs = 6_000L,
                    passResponseTimeoutMs = 3_500L,
                    idleCheckDelayMs = 1_800L,
                    cancelBurstDelayMs = 100L,
                )

            ModemPreset.SHORT_FAST ->
                MftTimingProfile(
                    interChunkDelayMs = 450L,
                    startAckTimeoutMs = 8_000L,
                    passResponseTimeoutMs = 5_000L,
                    idleCheckDelayMs = 2_500L,
                    cancelBurstDelayMs = 150L,
                )

            ModemPreset.SHORT_SLOW ->
                MftTimingProfile(
                    interChunkDelayMs = 750L,
                    startAckTimeoutMs = 10_000L,
                    passResponseTimeoutMs = 6_500L,
                    idleCheckDelayMs = 3_500L,
                    cancelBurstDelayMs = 200L,
                )

            ModemPreset.MEDIUM_TURBO,
            ModemPreset.LONG_TURBO,
            ->
                MftTimingProfile(
                    interChunkDelayMs = 750L,
                    startAckTimeoutMs = 10_000L,
                    passResponseTimeoutMs = 6_500L,
                    idleCheckDelayMs = 3_500L,
                    cancelBurstDelayMs = 200L,
                )

            ModemPreset.MEDIUM_FAST ->
                MftTimingProfile(
                    interChunkDelayMs = 1_300L,
                    startAckTimeoutMs = 14_000L,
                    passResponseTimeoutMs = 9_000L,
                    idleCheckDelayMs = 5_500L,
                    cancelBurstDelayMs = 300L,
                )

            ModemPreset.MEDIUM_SLOW ->
                MftTimingProfile(
                    interChunkDelayMs = 2_200L,
                    startAckTimeoutMs = 20_000L,
                    passResponseTimeoutMs = 14_000L,
                    idleCheckDelayMs = 9_000L,
                    cancelBurstDelayMs = 500L,
                )

            ModemPreset.LONG_FAST ->
                MftTimingProfile(
                    interChunkDelayMs = 3_500L,
                    startAckTimeoutMs = 25_000L,
                    passResponseTimeoutMs = 18_000L,
                    idleCheckDelayMs = 14_000L,
                    cancelBurstDelayMs = 800L,
                )

            ModemPreset.LONG_MODERATE ->
                MftTimingProfile(
                    interChunkDelayMs = 7_000L,
                    startAckTimeoutMs = 40_000L,
                    passResponseTimeoutMs = 30_000L,
                    idleCheckDelayMs = 25_000L,
                    cancelBurstDelayMs = 1_500L,
                )

            ModemPreset.LONG_SLOW ->
                MftTimingProfile(
                    interChunkDelayMs = 11_000L,
                    startAckTimeoutMs = 60_000L,
                    passResponseTimeoutMs = 50_000L,
                    idleCheckDelayMs = 40_000L,
                    cancelBurstDelayMs = 2_500L,
                )

            ModemPreset.VERY_LONG_SLOW ->
                MftTimingProfile(
                    interChunkDelayMs = 22_000L,
                    startAckTimeoutMs = 120_000L,
                    passResponseTimeoutMs = 90_000L,
                    idleCheckDelayMs = 70_000L,
                    cancelBurstDelayMs = 5_000L,
                )

            ModemPreset.LITE_FAST ->
                MftTimingProfile(
                    interChunkDelayMs = 2_500L,
                    startAckTimeoutMs = 20_000L,
                    passResponseTimeoutMs = 14_000L,
                    idleCheckDelayMs = 9_000L,
                    cancelBurstDelayMs = 500L,
                )

            ModemPreset.LITE_SLOW ->
                MftTimingProfile(
                    interChunkDelayMs = 4_500L,
                    startAckTimeoutMs = 30_000L,
                    passResponseTimeoutMs = 20_000L,
                    idleCheckDelayMs = 14_000L,
                    cancelBurstDelayMs = 800L,
                )

            ModemPreset.NARROW_FAST ->
                MftTimingProfile(
                    interChunkDelayMs = 3_000L,
                    startAckTimeoutMs = 25_000L,
                    passResponseTimeoutMs = 18_000L,
                    idleCheckDelayMs = 12_000L,
                    cancelBurstDelayMs = 600L,
                )

            ModemPreset.NARROW_SLOW ->
                MftTimingProfile(
                    interChunkDelayMs = 5_000L,
                    startAckTimeoutMs = 35_000L,
                    passResponseTimeoutMs = 25_000L,
                    idleCheckDelayMs = 16_000L,
                    cancelBurstDelayMs = 1_000L,
                )

            ModemPreset.TINY_FAST ->
                MftTimingProfile(
                    interChunkDelayMs = 6_000L,
                    startAckTimeoutMs = 40_000L,
                    passResponseTimeoutMs = 30_000L,
                    idleCheckDelayMs = 20_000L,
                    cancelBurstDelayMs = 1_200L,
                )

            ModemPreset.TINY_SLOW ->
                MftTimingProfile(
                    interChunkDelayMs = 11_000L,
                    startAckTimeoutMs = 60_000L,
                    passResponseTimeoutMs = 50_000L,
                    idleCheckDelayMs = 40_000L,
                    cancelBurstDelayMs = 2_500L,
                )

            null ->
                MftTimingProfile(
                    interChunkDelayMs = 250L,
                    startAckTimeoutMs = 6_000L,
                    passResponseTimeoutMs = 3_500L,
                    idleCheckDelayMs = 1_800L,
                    cancelBurstDelayMs = 100L,
                )
        }
    }
}
