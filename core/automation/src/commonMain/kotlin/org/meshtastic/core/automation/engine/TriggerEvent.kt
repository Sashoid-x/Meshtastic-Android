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
package org.meshtastic.core.automation.engine

/**
 * Contextual data carried with a trigger event when it fires.
 *
 * Not all fields are populated for every trigger type; consumers should handle nulls gracefully.
 */
data class TriggerEvent(
    val nodeId: Int? = null,
    val nodeName: String? = null,
    val batteryLevel: Int? = null,
    val voltage: Float? = null,
    val channelIndex: Int? = null,
    val messageText: String? = null,
    val emoji: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val distanceMeters: Double? = null,
    val temperature: Float? = null,
    val humidity: Float? = null,
    val pressure: Float? = null,
    val iaq: Float? = null,
    val co2: Float? = null,
    val pm25: Float? = null,
    val soilMoisture: Float? = null,
    val hops: Int? = null,
    val packetId: Int? = null,
    val shortName: String? = null,
    val snr: Float? = null,
    val rssi: Int? = null,
    val lastHop: String? = null,
    val transport: String? = null,
    val contactKey: String? = null,
    val appVersion: String? = null,
    val uptimeSeconds: Long? = null,
    val features: String? = null,
    val totalNodes: Int? = null,
    val onlineNodes: Int? = null,
    val directNodes: Int? = null,
    val recentNodes: Int? = null,
)
