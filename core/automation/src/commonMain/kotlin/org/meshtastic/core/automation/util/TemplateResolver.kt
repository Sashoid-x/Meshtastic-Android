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

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.meshtastic.core.automation.engine.TriggerEvent
import kotlin.time.Clock

/**
 * Replaces placeholders like `{NODE_ID}`, `{LONG_NAME}`, `{SHORT_NAME}`, `{SNR}`, `{RSSI}`, `{HOPS}`, `{RABBIT_HOPS}`,
 * `{LAST_HOP}`, `{CHANNEL}`, `{TRANSPORT}`, `{VERSION}`, `{DURATION}`, `{FEATURES}`, `{NODECOUNT}`, `{DIRECTCOUNT}`,
 * `{TOTALNODES}`, `{ONLINENODES}`, `{DATE}`, `{TIME}`, etc. with actual metadata from [TriggerEvent].
 */
object TemplateResolver {
    private const val HEX_RADIX = 16
    private const val HEX_NODE_ID_LENGTH = 8
    private const val METERS_PER_KM = 1000.0
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
    private const val SECONDS_PER_DAY = 86400L
    private val placeholderRegex = Regex("""\{([a-zA-Z0-9_]+)\}""")

    @Suppress("CyclomaticComplexMethod", "MagicNumber", "LongMethod", "UseOrEmpty")
    fun resolve(template: String, event: TriggerEvent): String {
        if (!template.contains('{')) return template
        return placeholderRegex.replace(template) { matchResult ->
            when (matchResult.groupValues[1].uppercase()) {
                "NODE_ID",
                "NODE_HEX",
                ->
                    if (matchResult.groupValues[1] == "node_id") {
                        event.nodeId?.toString().orEmpty()
                    } else {
                        event.nodeId
                            ?.toUInt()
                            ?.toString(HEX_RADIX)
                            ?.padStart(HEX_NODE_ID_LENGTH, '0')
                            ?.let { "!$it" }
                            .orEmpty()
                    }

                "LONG_NAME",
                "NAME",
                "NODE_NAME",
                -> event.nodeName.orEmpty()

                "SHORT_NAME" -> event.shortName.orEmpty()

                "SNR" -> event.snr?.let { "${((it * 10).toInt()) / 10.0} dB" }.orEmpty()

                "RSSI" -> event.rssi?.let { "$it dBm" }.orEmpty()

                "HOPS",
                "NUMBER_HOPS",
                -> (event.hops ?: 0).toString()

                "RABBIT_HOPS" -> {
                    val h = event.hops ?: 0
                    if (h <= 0) "🎯" else "🐇".repeat(h)
                }

                "LAST_HOP" -> event.lastHop ?: "Direct"

                "CHANNEL" -> (event.channelIndex ?: 0).toString()

                "TRANSPORT" -> event.transport ?: "LoRa"

                "VERSION" -> event.appVersion ?: "2.8.3"

                "DURATION" -> {
                    val sec = event.uptimeSeconds ?: 0L
                    val days = sec / SECONDS_PER_DAY
                    val hours = (sec % SECONDS_PER_DAY) / SECONDS_PER_HOUR
                    val minutes = (sec % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
                    when {
                        days > 0 -> "${days}d ${hours}h"
                        hours > 0 -> "${hours}h ${minutes}m"
                        else -> "${minutes}m"
                    }
                }

                "FEATURES" -> event.features ?: "LoRa, MQTT, Automation"

                "NODECOUNT" -> (event.recentNodes ?: event.totalNodes ?: 0).toString()

                "DIRECTCOUNT" -> (event.directNodes ?: 0).toString()

                "TOTALNODES" -> (event.totalNodes ?: 0).toString()

                "ONLINENODES" -> (event.onlineNodes ?: 0).toString()

                "DATE" -> {
                    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                    val month = now.monthNumber.toString().padStart(2, '0')
                    val day = now.dayOfMonth.toString().padStart(2, '0')
                    "${now.year}-$month-$day"
                }

                "TIME" -> {
                    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                    val hour = now.hour.toString().padStart(2, '0')
                    val min = now.minute.toString().padStart(2, '0')
                    "$hour:$min"
                }

                "BATTERY",
                "BATTERY_LEVEL",
                -> event.batteryLevel?.toString().orEmpty()

                "VOLTAGE" -> event.voltage?.let { "${((it * 100).toInt()) / 100.0}V" }.orEmpty()

                "TEXT" -> event.messageText.orEmpty()

                "EMOJI" -> event.emoji.orEmpty()

                "LATITUDE" -> event.latitude?.let { "${((it * 10000).toInt()) / 10000.0}" }.orEmpty()

                "LONGITUDE" -> event.longitude?.let { "${((it * 10000).toInt()) / 10000.0}" }.orEmpty()

                "DISTANCE",
                "DISTANCE_KM",
                ->
                    event.distanceMeters?.let { "${((it / METERS_PER_KM * 10).toInt()) / 10.0} km" }.orEmpty()

                "DISTANCE_M" -> event.distanceMeters?.toInt()?.toString().orEmpty()

                "TEMPERATURE",
                "TEMP",
                -> event.temperature?.let { "${((it * 10).toInt()) / 10.0}°C" }.orEmpty()

                "HUMIDITY" -> event.humidity?.let { "${it.toInt()}%" }.orEmpty()

                "PRESSURE" -> event.pressure?.let { "${it.toInt()} hPa" }.orEmpty()

                "IAQ" -> event.iaq?.toInt()?.toString().orEmpty()

                "CO2" -> event.co2?.toInt()?.toString().orEmpty()

                "PM25" -> event.pm25?.let { "${((it * 10).toInt()) / 10.0}" }.orEmpty()

                "SOIL_MOISTURE" -> event.soilMoisture?.let { "${it.toInt()}%" }.orEmpty()

                else -> matchResult.value
            }
        }
    }
}
