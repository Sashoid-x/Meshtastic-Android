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

import io.kotest.matchers.shouldBe
import org.meshtastic.core.automation.engine.TriggerEvent
import kotlin.test.Test

class TemplateResolverTest {

    @Test
    fun `resolves all standard metadata variables`() {
        val event =
            TriggerEvent(
                nodeId = 12345678,
                nodeName = "Base Station",
                batteryLevel = 85,
                voltage = 4.12f,
                channelIndex = 0,
                messageText = "Hello mesh!",
                emoji = "🚨",
                latitude = 55.7558,
                longitude = 37.6173,
                distanceMeters = 2500.0,
                temperature = 22.5f,
                humidity = 45.0f,
                pressure = 1013.25f,
                co2 = 600f,
                pm25 = 12.3f,
                soilMoisture = 35.0f,
                hops = 2,
            )

        val template =
            "Node {node_name} ({node_id}) sent '{text}' with emoji {emoji}. Battery: {battery_level}%, Temp: {temperature}, Distance: {distance_km}, Hops: {hops}"
        val resolved = TemplateResolver.resolve(template, event)

        resolved shouldBe
            "Node Base Station (12345678) sent 'Hello mesh!' with emoji 🚨. Battery: 85%, Temp: 22.5°C, Distance: 2.5 km, Hops: 2"
    }

    @Test
    fun `resolves reply and meshmonitor variables`() {
        val event =
            TriggerEvent(
                nodeId = 0x12345678,
                nodeName = "Relay Alpha",
                shortName = "RALP",
                channelIndex = 1,
                hops = 3,
                snr = 7.5f,
                rssi = -68,
                lastHop = "Beta",
                transport = "LoRa",
                appVersion = "2.8.3-advanced-3",
                uptimeSeconds = 90000L, // 1d 1h
                features = "LoRa, MQTT, Automation",
                recentNodes = 15,
                directNodes = 4,
                totalNodes = 42,
                onlineNodes = 8,
            )

        val template =
            "From {NODE_ID} ({SHORT_NAME} / {LONG_NAME}) ch:{CHANNEL} via {TRANSPORT} SNR:{SNR} RSSI:{RSSI} hops:{HOPS}/{NUMBER_HOPS} {RABBIT_HOPS} via {LAST_HOP}. Ver:{VERSION} Up:{DURATION} [{FEATURES}] Nodes:{NODECOUNT} Dir:{DIRECTCOUNT} Tot:{TOTALNODES} On:{ONLINENODES}"
        val resolved = TemplateResolver.resolve(template, event)

        resolved shouldBe
            "From !12345678 (RALP / Relay Alpha) ch:1 via LoRa SNR:7.5 dB RSSI:-68 dBm hops:3/3 🐇🐇🐇 via Beta. Ver:2.8.3-advanced-3 Up:1d 1h [LoRa, MQTT, Automation] Nodes:15 Dir:4 Tot:42 On:8"
    }

    @Test
    fun `resolves zero hops with target emoji for rabbit hops`() {
        val event = TriggerEvent(hops = 0)
        val resolved = TemplateResolver.resolve("Hops: {HOPS} {RABBIT_HOPS}", event)
        resolved shouldBe "Hops: 0 🎯"
    }

    @Test
    fun `resolves current date and time tokens`() {
        val event = TriggerEvent()
        val resolved = TemplateResolver.resolve("{DATE} {TIME}", event)
        // Date matches YYYY-MM-DD and time matches HH:MM
        val regex = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""")
        regex.matches(resolved) shouldBe true
    }

    @Test
    fun `leaves unknown variables untouched`() {
        val event = TriggerEvent(nodeName = "TestNode")
        val template = "Hello {node_name}, unknown {foo_bar}"
        val resolved = TemplateResolver.resolve(template, event)

        resolved shouldBe "Hello TestNode, unknown {foo_bar}"
    }

    @Test
    fun `handles string without variables directly`() {
        val event = TriggerEvent(nodeName = "TestNode")
        val template = "Plain message without placeholders"
        val resolved = TemplateResolver.resolve(template, event)

        resolved shouldBe "Plain message without placeholders"
    }
}
