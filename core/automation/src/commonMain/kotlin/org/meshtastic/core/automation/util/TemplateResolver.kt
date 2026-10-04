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
package org.meshtastic.core.automation.util

import org.meshtastic.core.automation.engine.TriggerEvent

/**
 * Replaces placeholders like `{node_name}`, `{node_id}`, `{battery_level}`, `{channel}`, `{text}` with actual metadata
 * from [TriggerEvent].
 */
object TemplateResolver {
    private const val HEX_RADIX = 16
    private const val HEX_NODE_ID_LENGTH = 8
    private const val METERS_PER_KM = 1000.0
    private val placeholderRegex = Regex("""\{([a-zA-Z0-9_]+)\}""")

    @Suppress("CyclomaticComplexMethod", "MagicNumber", "LongMethod")
    fun resolve(template: String, event: TriggerEvent): String {
        if (!template.contains('{')) return template
        return placeholderRegex.replace(template) { matchResult ->
            when (matchResult.groupValues[1]) {
                "name",
                "node_name",
                -> event.nodeName ?: ""

                "node_id" -> event.nodeId?.toString() ?: ""

                "node_hex" ->
                    event.nodeId?.toUInt()?.toString(HEX_RADIX)?.padStart(HEX_NODE_ID_LENGTH, '0')?.let { "!$it" } ?: ""

                "battery",
                "battery_level",
                -> event.batteryLevel?.toString() ?: ""

                "voltage" -> event.voltage?.let { "${((it * 100).toInt()) / 100.0}V" } ?: ""

                "channel" -> event.channelIndex?.toString() ?: ""

                "text" -> event.messageText ?: ""

                "emoji" -> event.emoji ?: ""

                "latitude" -> event.latitude?.let { "${((it * 10000).toInt()) / 10000.0}" } ?: ""

                "longitude" -> event.longitude?.let { "${((it * 10000).toInt()) / 10000.0}" } ?: ""

                "distance",
                "distance_km",
                -> event.distanceMeters?.let { "${((it / METERS_PER_KM * 10).toInt()) / 10.0} km" } ?: ""

                "distance_m" -> event.distanceMeters?.toInt()?.toString() ?: ""

                "temperature" -> event.temperature?.let { "${((it * 10).toInt()) / 10.0}°C" } ?: ""

                "humidity" -> event.humidity?.let { "${it.toInt()}%" } ?: ""

                "pressure" -> event.pressure?.let { "${it.toInt()} hPa" } ?: ""

                "iaq" -> event.iaq?.toInt()?.toString() ?: ""

                "co2" -> event.co2?.toInt()?.toString() ?: ""

                "pm25" -> event.pm25?.let { "${((it * 10).toInt()) / 10.0}" } ?: ""

                "soil_moisture" -> event.soilMoisture?.let { "${it.toInt()}%" } ?: ""

                "hops" -> event.hops?.toString() ?: ""

                else -> matchResult.value
            }
        }
    }
}
