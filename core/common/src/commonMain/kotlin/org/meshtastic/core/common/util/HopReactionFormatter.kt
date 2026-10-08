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

object HopReactionFormatter {
    private const val SNR_DECIMAL_PLACES = 10.0
    private val HOP_KEYCAPS = listOf("1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣")

    /**
     * Formats a reaction string based on hops and signal metrics:
     * - Multi-hop (hops in 1..7+): Blue keycap digit emojis 1️⃣ to 7️⃣ (clamped at 7️⃣).
     * - Direct (hops <= 0): Target emoji 🎯 + SNR/RSSI metrics (e.g. "🎯 8.5dB/-75dBm" or "🎯").
     */
    fun format(hops: Int?, snr: Float?, rssi: Int?): String {
        val h = hops ?: 0
        return if (h > 0) {
            val index = (h - 1).coerceIn(0, HOP_KEYCAPS.size - 1)
            HOP_KEYCAPS[index]
        } else {
            val snrPart = snr?.let { "${((it * SNR_DECIMAL_PLACES).toInt()) / SNR_DECIMAL_PLACES}dB" }
            val rssiPart = rssi?.let { "${it}dBm" }
            val metrics = listOfNotNull(snrPart, rssiPart).joinToString("/")
            if (metrics.isNotEmpty()) "🎯 $metrics" else "🎯"
        }
    }
}
