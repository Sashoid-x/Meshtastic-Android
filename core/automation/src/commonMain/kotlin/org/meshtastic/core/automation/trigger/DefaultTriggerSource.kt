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
package org.meshtastic.core.automation.trigger

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.datetime.TimeZone
import org.koin.core.annotation.Single
import org.meshtastic.core.automation.engine.TriggerEvent
import org.meshtastic.core.automation.model.AirQualityMetricType
import org.meshtastic.core.automation.model.AutomationTrigger
import org.meshtastic.core.automation.model.EnvironmentMetricType
import org.meshtastic.core.automation.model.GeofenceTransition
import org.meshtastic.core.automation.model.NodeStatusType
import org.meshtastic.core.automation.util.CronExpression
import org.meshtastic.core.automation.util.calculateDistanceMeters
import org.meshtastic.core.automation.util.matchesOperator
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.ServiceRepository
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.Telemetry
import kotlin.time.Clock

private const val SCHEDULE_INTERVAL_MS = 60_000L
private const val NODE_LOST_CHECK_INTERVAL_MS = 30_000L
private const val LAT_LON_SCALE = 1e-7
private const val METERS_PER_KM = 1000.0
private const val HEX_RADIX = 16
private const val HEX_NODE_ID_LENGTH = 8
private const val DEFAULT_SHORT_NAME_LENGTH = 4
private const val MS_PER_MINUTE = 60_000L
private const val MS_PER_SECOND = 1000L

@Single(binds = [TriggerSource::class])
@Suppress("TooManyFunctions")
class DefaultTriggerSource(
    private val nodeRepository: NodeRepository,
    private val serviceRepository: ServiceRepository,
) : TriggerSource {

    override fun flowFor(trigger: AutomationTrigger): Flow<TriggerEvent> = when (trigger) {
        is AutomationTrigger.MessageReceived -> createMessageReceivedFlow(trigger)
        is AutomationTrigger.ReactionReceived -> createReactionReceivedFlow(trigger)
        is AutomationTrigger.NodeBatteryLow -> createNodeBatteryLowFlow(trigger)
        is AutomationTrigger.EnvironmentThreshold -> createEnvironmentThresholdFlow(trigger)
        is AutomationTrigger.AirQualityThreshold -> createAirQualityThresholdFlow(trigger)
        is AutomationTrigger.SoilMoistureThreshold -> createSoilMoistureThresholdFlow(trigger)
        is AutomationTrigger.NodeGeofence -> createNodeGeofenceFlow(trigger)
        is AutomationTrigger.NodeProximity -> createNodeProximityFlow(trigger)
        is AutomationTrigger.NodeMoved -> createNodeMovedFlow(trigger)
        is AutomationTrigger.NodeAppeared -> createNodeAppearedFlow(trigger)
        is AutomationTrigger.NodeDisappeared -> createNodeLostFlow(trigger)
        is AutomationTrigger.HopLimitChanged -> createHopLimitChangedFlow(trigger)
        is AutomationTrigger.Schedule -> createScheduleFlow(trigger)
        is AutomationTrigger.DeviceBatteryLow -> emptyFlow()
        is AutomationTrigger.RadioConnected -> createRadioConnectedFlow()
        is AutomationTrigger.RadioDisconnected -> createRadioDisconnectedFlow()
        is AutomationTrigger.TelemetryReceived -> createTelemetryReceivedFlow(trigger)
        is AutomationTrigger.PositionUpdated -> createPositionUpdatedFlow(trigger)
        is AutomationTrigger.NodeStatusChanged -> createNodeStatusChangedFlow(trigger)
        is AutomationTrigger.RadioConnectionChanged -> createRadioConnectionChangedFlow(trigger)
        is AutomationTrigger.DeviceBatteryChanged -> createDeviceBatteryChangedFlow(trigger)
        is AutomationTrigger.ScheduleTick -> createScheduleTickFlow(trigger)
    }

    private fun createMessageReceivedFlow(trigger: AutomationTrigger.MessageReceived): Flow<TriggerEvent> {
        val pattern = trigger.pattern
        return serviceRepository.meshPacketFlow
            .filter { packet ->
                val decoded = packet.decoded
                if (decoded?.portnum != PortNum.TEXT_MESSAGE_APP || decoded.emoji != 0) return@filter false
                if (trigger.channelIndex != null && packet.channel != trigger.channelIndex) return@filter false
                if (trigger.fromNodeId != null && packet.from != trigger.fromNodeId) return@filter false
                if (trigger.isDirectMessage && packet.to == NodeAddress.NODENUM_BROADCAST) return@filter false
                if (pattern.isNotBlank()) {
                    val text = decoded.payload.utf8()
                    val matches =
                        if (trigger.isRegex) {
                            runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text) }
                                .getOrDefault(false)
                        } else {
                            text.contains(pattern, ignoreCase = true)
                        }
                    if (!matches) return@filter false
                }
                true
            }
            .mapNotNull { packet ->
                val text = packet.decoded?.payload?.utf8().orEmpty()
                extractMessageTriggerEvent(packet = packet, text = text)
            }
    }

    private fun createReactionReceivedFlow(trigger: AutomationTrigger.ReactionReceived): Flow<TriggerEvent> =
        serviceRepository.meshPacketFlow
            .filter { packet ->
                val decoded = packet.decoded
                if (decoded?.portnum != PortNum.TEXT_MESSAGE_APP || decoded.emoji == 0) return@filter false
                if (trigger.channelIndex != null && packet.channel != trigger.channelIndex) return@filter false
                if (trigger.fromNodeId != null && packet.from != trigger.fromNodeId) return@filter false
                val emoji = decoded.payload.utf8()
                if (trigger.emoji.isNotBlank() && emoji != trigger.emoji) return@filter false
                true
            }
            .mapNotNull { packet ->
                val emoji = packet.decoded?.payload?.utf8().orEmpty()
                extractMessageTriggerEvent(packet = packet, emoji = emoji)
            }

    private fun extractMessageTriggerEvent(
        packet: MeshPacket,
        text: String? = null,
        emoji: String? = null,
    ): TriggerEvent {
        val user = nodeRepository.getUser(packet.from)
        val isDm = packet.to != NodeAddress.NODENUM_BROADCAST
        val fromHex = packet.from.toUInt().toString(HEX_RADIX).padStart(HEX_NODE_ID_LENGTH, '0')
        val contactKey = if (isDm) "0!$fromHex" else "${packet.channel}^all"
        val hopCount =
            if (packet.hop_start == 0 || packet.hop_limit > packet.hop_start) {
                0
            } else {
                packet.hop_start - packet.hop_limit
            }
        val relayNode = packet.relay_node
        val lastHopStr =
            if (relayNode != 0) {
                val nodeList = nodeRepository.nodeDBbyNum.value.values.toList()
                val myNodeNum = nodeRepository.ourNodeInfo.value?.num
                val relay = Node.getRelayNode(relayNode, nodeList, myNodeNum)
                relay?.user?.short_name?.ifBlank { relay.user.long_name }
                    ?: "0x${relayNode.toUInt().toString(HEX_RADIX).uppercase()}"
            } else if (hopCount == 0) {
                "Direct"
            } else {
                "unknown"
            }
        val transportStr = if (packet.via_mqtt == true) "MQTT" else "LoRa"

        return TriggerEvent(
            nodeId = packet.from,
            nodeName = user.long_name.ifBlank { "Node ${packet.from}" },
            shortName = user.short_name.ifBlank { fromHex.takeLast(DEFAULT_SHORT_NAME_LENGTH) },
            channelIndex = packet.channel,
            messageText = text,
            emoji = emoji,
            packetId = packet.id,
            hops = hopCount,
            snr = packet.rx_snr,
            rssi = packet.rx_rssi,
            lastHop = lastHopStr,
            transport = transportStr,
            contactKey = contactKey,
        )
    }

    private fun createNodeBatteryLowFlow(trigger: AutomationTrigger.NodeBatteryLow): Flow<TriggerEvent> = flow {
        val previousBatteryMap = mutableMapOf<Int, Int>()
        val previousVoltageMap = mutableMapOf<Int, Float>()

        nodeRepository.nodeDBbyNum.collect { nodesMap ->
            for ((num, node) in nodesMap) {
                if (trigger.nodeId != null && num != trigger.nodeId) continue

                val currentBattery = node.batteryLevel
                val voltage = node.voltage

                var percentFired = false
                if (currentBattery != null && currentBattery > 0) {
                    val prev = previousBatteryMap[num]
                    previousBatteryMap[num] = currentBattery
                    if (prev != null && prev > trigger.thresholdPercent && currentBattery <= trigger.thresholdPercent) {
                        percentFired = true
                    }
                }

                var voltageFired = false
                if (trigger.thresholdVoltage != null && voltage != null && voltage > 0f) {
                    val prevV = previousVoltageMap[num]
                    previousVoltageMap[num] = voltage
                    if (prevV != null && prevV > trigger.thresholdVoltage && voltage <= trigger.thresholdVoltage) {
                        voltageFired = true
                    }
                }

                if (percentFired || voltageFired) {
                    emit(
                        TriggerEvent(
                            nodeId = num,
                            nodeName = node.user.long_name,
                            batteryLevel = currentBattery,
                            voltage = voltage,
                        ),
                    )
                }
            }
        }
    }

    private fun createEnvironmentThresholdFlow(trigger: AutomationTrigger.EnvironmentThreshold): Flow<TriggerEvent> =
        serviceRepository.meshPacketFlow
            .filter { packet ->
                if (packet.decoded?.portnum != PortNum.TELEMETRY_APP) return@filter false
                if (trigger.nodeId != null && packet.from != trigger.nodeId) return@filter false
                true
            }
            .mapNotNull { packet ->
                val payload = packet.decoded?.payload ?: return@mapNotNull null
                val telemetry = runCatching { Telemetry.ADAPTER.decode(payload) }.getOrNull()
                val env = telemetry?.environment_metrics ?: return@mapNotNull null
                val value =
                    when (trigger.metricType) {
                        EnvironmentMetricType.TEMPERATURE -> env.temperature
                        EnvironmentMetricType.HUMIDITY -> env.relative_humidity
                        EnvironmentMetricType.BAROMETRIC_PRESSURE -> env.barometric_pressure
                        EnvironmentMetricType.VOLTAGE -> env.voltage
                        EnvironmentMetricType.CURRENT -> env.current
                    } ?: return@mapNotNull null

                if (matchesOperator(value, trigger.operator, trigger.thresholdValue)) {
                    val user = nodeRepository.getUser(packet.from)
                    TriggerEvent(
                        nodeId = packet.from,
                        nodeName = user.long_name.ifBlank { "Node ${packet.from}" },
                        temperature = env.temperature,
                        humidity = env.relative_humidity,
                        pressure = env.barometric_pressure,
                        voltage = env.voltage,
                    )
                } else {
                    null
                }
            }

    private fun createAirQualityThresholdFlow(trigger: AutomationTrigger.AirQualityThreshold): Flow<TriggerEvent> =
        serviceRepository.meshPacketFlow
            .filter { packet ->
                if (packet.decoded?.portnum != PortNum.TELEMETRY_APP) return@filter false
                if (trigger.nodeId != null && packet.from != trigger.nodeId) return@filter false
                true
            }
            .mapNotNull { packet ->
                val payload = packet.decoded?.payload ?: return@mapNotNull null
                val telemetry = runCatching { Telemetry.ADAPTER.decode(payload) }.getOrNull()
                val aq = telemetry?.air_quality_metrics
                val env = telemetry?.environment_metrics
                val value =
                    when (trigger.metricType) {
                        AirQualityMetricType.PM25 -> aq?.pm25_standard?.toFloat()
                        AirQualityMetricType.CO2 -> aq?.co2?.toFloat()
                        AirQualityMetricType.IAQ -> env?.iaq?.toFloat()
                    } ?: return@mapNotNull null

                if (matchesOperator(value, trigger.operator, trigger.thresholdValue)) {
                    val user = nodeRepository.getUser(packet.from)
                    TriggerEvent(
                        nodeId = packet.from,
                        nodeName = user.long_name.ifBlank { "Node ${packet.from}" },
                        pm25 = aq?.pm25_standard?.toFloat(),
                        co2 = aq?.co2?.toFloat(),
                        iaq = env?.iaq?.toFloat(),
                    )
                } else {
                    null
                }
            }

    private fun createSoilMoistureThresholdFlow(trigger: AutomationTrigger.SoilMoistureThreshold): Flow<TriggerEvent> =
        serviceRepository.meshPacketFlow
            .filter { packet ->
                if (packet.decoded?.portnum != PortNum.TELEMETRY_APP) return@filter false
                if (trigger.nodeId != null && packet.from != trigger.nodeId) return@filter false
                true
            }
            .mapNotNull { packet ->
                val payload = packet.decoded?.payload ?: return@mapNotNull null
                val telemetry = runCatching { Telemetry.ADAPTER.decode(payload) }.getOrNull()
                val moisture = telemetry?.environment_metrics?.soil_moisture ?: return@mapNotNull null
                if (matchesOperator(moisture.toFloat(), trigger.operator, trigger.thresholdPercent)) {
                    val user = nodeRepository.getUser(packet.from)
                    TriggerEvent(
                        nodeId = packet.from,
                        nodeName = user.long_name.ifBlank { "Node ${packet.from}" },
                        soilMoisture = moisture.toFloat(),
                    )
                } else {
                    null
                }
            }

    private fun createNodeGeofenceFlow(trigger: AutomationTrigger.NodeGeofence): Flow<TriggerEvent> {
        val previousInsideMap = mutableMapOf<Int, Boolean>()
        return serviceRepository.meshPacketFlow
            .filter { packet ->
                if (packet.decoded?.portnum != PortNum.POSITION_APP) return@filter false
                if (trigger.nodeId != null && packet.from != trigger.nodeId) return@filter false
                true
            }
            .mapNotNull { packet ->
                val payload = packet.decoded?.payload ?: return@mapNotNull null
                val pos = runCatching { Position.ADAPTER.decode(payload) }.getOrNull()
                val lat = pos?.latitude_i?.times(LAT_LON_SCALE) ?: return@mapNotNull null
                val lon = pos.longitude_i?.times(LAT_LON_SCALE) ?: return@mapNotNull null
                val centerLat = trigger.centerLatitude ?: return@mapNotNull null
                val centerLon = trigger.centerLongitude ?: return@mapNotNull null
                val dist = calculateDistanceMeters(lat, lon, centerLat, centerLon)
                val isInside = dist <= trigger.radiusMeters
                val prev = previousInsideMap[packet.from]
                previousInsideMap[packet.from] = isInside

                val transitionMatch =
                    when (trigger.transition) {
                        GeofenceTransition.ENTER -> prev == false && isInside
                        GeofenceTransition.EXIT -> prev == true && !isInside
                    }

                if (transitionMatch) {
                    val user = nodeRepository.getUser(packet.from)
                    TriggerEvent(
                        nodeId = packet.from,
                        nodeName = user.long_name.ifBlank { "Node ${packet.from}" },
                        latitude = lat,
                        longitude = lon,
                        distanceMeters = dist,
                    )
                } else {
                    null
                }
            }
    }

    private fun createNodeProximityFlow(trigger: AutomationTrigger.NodeProximity): Flow<TriggerEvent> =
        serviceRepository.meshPacketFlow
            .filter { packet ->
                if (packet.decoded?.portnum != PortNum.POSITION_APP) return@filter false
                if (trigger.nodeId != null && packet.from != trigger.nodeId) return@filter false
                true
            }
            .mapNotNull { packet ->
                val payload = packet.decoded?.payload ?: return@mapNotNull null
                val pos = runCatching { Position.ADAPTER.decode(payload) }.getOrNull()
                val lat = pos?.latitude_i?.times(LAT_LON_SCALE) ?: return@mapNotNull null
                val lon = pos.longitude_i?.times(LAT_LON_SCALE) ?: return@mapNotNull null

                val ourPos = nodeRepository.ourNodeInfo.value?.position
                val ourLat = ourPos?.latitude_i?.times(LAT_LON_SCALE) ?: return@mapNotNull null
                val ourLon = ourPos.longitude_i?.times(LAT_LON_SCALE) ?: return@mapNotNull null

                val distMeters = calculateDistanceMeters(lat, lon, ourLat, ourLon)
                val targetMeters = trigger.distanceKilometers * METERS_PER_KM

                if (matchesOperator(distMeters, trigger.operator, targetMeters)) {
                    val user = nodeRepository.getUser(packet.from)
                    TriggerEvent(
                        nodeId = packet.from,
                        nodeName = user.long_name.ifBlank { "Node ${packet.from}" },
                        latitude = lat,
                        longitude = lon,
                        distanceMeters = distMeters,
                    )
                } else {
                    null
                }
            }

    private fun createNodeMovedFlow(trigger: AutomationTrigger.NodeMoved): Flow<TriggerEvent> {
        val lastPosMap = mutableMapOf<Int, Pair<Double, Double>>()
        return serviceRepository.meshPacketFlow
            .filter { packet ->
                if (packet.decoded?.portnum != PortNum.POSITION_APP) return@filter false
                if (trigger.nodeId != null && packet.from != trigger.nodeId) return@filter false
                true
            }
            .mapNotNull { packet ->
                val payload = packet.decoded?.payload ?: return@mapNotNull null
                val pos = runCatching { Position.ADAPTER.decode(payload) }.getOrNull()
                val lat = pos?.latitude_i?.times(LAT_LON_SCALE) ?: return@mapNotNull null
                val lon = pos.longitude_i?.times(LAT_LON_SCALE) ?: return@mapNotNull null

                val last = lastPosMap[packet.from]
                lastPosMap[packet.from] = lat to lon

                if (last != null) {
                    val delta = calculateDistanceMeters(lat, lon, last.first, last.second)
                    if (delta >= trigger.minDistanceMeters) {
                        val user = nodeRepository.getUser(packet.from)
                        TriggerEvent(
                            nodeId = packet.from,
                            nodeName = user.long_name.ifBlank { "Node ${packet.from}" },
                            latitude = lat,
                            longitude = lon,
                            distanceMeters = delta,
                        )
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
    }

    private fun createNodeAppearedFlow(trigger: AutomationTrigger.NodeAppeared): Flow<TriggerEvent> {
        var knownNodeNums = emptySet<Int>()
        return nodeRepository.nodeDBbyNum.mapNotNull { nodesMap ->
            val currentNums = nodesMap.keys
            if (knownNodeNums.isNotEmpty()) {
                val newNums = currentNums - knownNodeNums
                knownNodeNums = currentNums
                val targetNum =
                    if (trigger.nodeId != null) {
                        if (trigger.nodeId in newNums) trigger.nodeId else null
                    } else {
                        newNums.firstOrNull()
                    }
                if (targetNum != null) {
                    val node = nodesMap[targetNum]
                    TriggerEvent(
                        nodeId = targetNum,
                        nodeName = node?.user?.long_name ?: "Node $targetNum",
                    )
                } else {
                    null
                }
            } else {
                knownNodeNums = currentNums
                null
            }
        }
    }

    private fun createNodeLostFlow(trigger: AutomationTrigger.NodeDisappeared): Flow<TriggerEvent> {
        val reportedLost = mutableSetOf<Int>()
        return flow {
            while (currentCoroutineContext().isActive) {
                delay(NODE_LOST_CHECK_INTERVAL_MS)
                val now = Clock.System.now().toEpochMilliseconds()
                val timeoutMs = trigger.timeoutMinutes * MS_PER_MINUTE
                val nodesMap = nodeRepository.nodeDBbyNum.value
                for ((num, node) in nodesMap) {
                    if (trigger.nodeId != null && num != trigger.nodeId) continue
                    val lastHeard = node.lastHeard
                    if (lastHeard > 0) {
                        val isLost = (now - lastHeard.toLong() * MS_PER_SECOND) > timeoutMs
                        if (isLost && num !in reportedLost) {
                            reportedLost.add(num)
                            emit(
                                TriggerEvent(
                                    nodeId = num,
                                    nodeName = node.user.long_name.ifBlank { "Node $num" },
                                ),
                            )
                        } else if (!isLost) {
                            reportedLost.remove(num)
                        }
                    }
                }
            }
        }
    }

    private fun createHopLimitChangedFlow(trigger: AutomationTrigger.HopLimitChanged): Flow<TriggerEvent> {
        val lastHopsMap = mutableMapOf<Int, Int>()
        return serviceRepository.meshPacketFlow
            .filter { packet ->
                if (trigger.nodeId != null && packet.from != trigger.nodeId) return@filter false
                val hopStart = packet.hop_start
                val hopLimit = packet.hop_limit
                val hops = hopStart - hopLimit
                if (trigger.minHops != null && hops < trigger.minHops) return@filter false
                if (trigger.maxHops != null && hops > trigger.maxHops) return@filter false
                val prev = lastHopsMap[packet.from]
                lastHopsMap[packet.from] = hops
                prev != null && prev != hops
            }
            .mapNotNull { packet ->
                val hops = packet.hop_start - packet.hop_limit
                val user = nodeRepository.getUser(packet.from)
                TriggerEvent(
                    nodeId = packet.from,
                    nodeName = user.long_name.ifBlank { "Node ${packet.from}" },
                    hops = hops,
                )
            }
    }

    private fun createScheduleFlow(trigger: AutomationTrigger.Schedule): Flow<TriggerEvent> = flow {
        val cron = CronExpression.parse(trigger.cronExpression.trim())
        if (cron == null) {
            while (currentCoroutineContext().isActive) {
                delay(SCHEDULE_INTERVAL_MS)
                emit(TriggerEvent())
            }
            return@flow
        }
        val tz = TimeZone.currentSystemDefault()
        while (currentCoroutineContext().isActive) {
            val now = Clock.System.now()
            val next = cron.nextExecution(now, tz)
            if (next != null) {
                val delayMs = (next.toEpochMilliseconds() - now.toEpochMilliseconds()).coerceAtLeast(MS_PER_SECOND)
                delay(delayMs)
                emit(TriggerEvent())
            } else {
                delay(SCHEDULE_INTERVAL_MS)
            }
        }
    }

    private fun createRadioConnectedFlow(): Flow<TriggerEvent> {
        var prev: ConnectionState? = null
        return serviceRepository.connectionState.mapNotNull { state ->
            val shouldEmit = prev != null && prev !is ConnectionState.Connected && state is ConnectionState.Connected
            prev = state
            if (shouldEmit) TriggerEvent() else null
        }
    }

    private fun createRadioDisconnectedFlow(): Flow<TriggerEvent> {
        var prev: ConnectionState? = null
        return serviceRepository.connectionState.mapNotNull { state ->
            val shouldEmit = prev is ConnectionState.Connected && state is ConnectionState.Disconnected
            prev = state
            if (shouldEmit) TriggerEvent() else null
        }
    }

    private fun createTelemetryReceivedFlow(trigger: AutomationTrigger.TelemetryReceived): Flow<TriggerEvent> =
        serviceRepository.meshPacketFlow
            .filter { packet ->
                if (packet.decoded?.portnum != PortNum.TELEMETRY_APP) return@filter false
                if (trigger.fromNodeId != null && packet.from != trigger.fromNodeId) return@filter false
                true
            }
            .mapNotNull { packet ->
                val payload = packet.decoded?.payload ?: return@mapNotNull null
                val telemetry = runCatching { Telemetry.ADAPTER.decode(payload) }.getOrNull()
                val dev = telemetry?.device_metrics
                val env = telemetry?.environment_metrics
                val aq = telemetry?.air_quality_metrics
                val user = nodeRepository.getUser(packet.from)
                TriggerEvent(
                    nodeId = packet.from,
                    nodeName = user.long_name.ifBlank { "Node ${packet.from}" },
                    batteryLevel = dev?.battery_level,
                    voltage = dev?.voltage,
                    temperature = env?.temperature,
                    humidity = env?.relative_humidity,
                    pressure = env?.barometric_pressure,
                    iaq = env?.iaq?.toFloat(),
                    co2 = aq?.co2?.toFloat(),
                    pm25 = aq?.pm25_standard?.toFloat(),
                    soilMoisture = env?.soil_moisture?.toFloat(),
                )
            }

    private fun createPositionUpdatedFlow(trigger: AutomationTrigger.PositionUpdated): Flow<TriggerEvent> =
        serviceRepository.meshPacketFlow
            .filter { packet ->
                if (packet.decoded?.portnum != PortNum.POSITION_APP) return@filter false
                if (trigger.fromNodeId != null && packet.from != trigger.fromNodeId) return@filter false
                true
            }
            .mapNotNull { packet ->
                val payload = packet.decoded?.payload ?: return@mapNotNull null
                val pos = runCatching { Position.ADAPTER.decode(payload) }.getOrNull()
                val lat = pos?.latitude_i?.times(LAT_LON_SCALE) ?: return@mapNotNull null
                val lon = pos.longitude_i?.times(LAT_LON_SCALE) ?: return@mapNotNull null

                val ourPos = nodeRepository.ourNodeInfo.value?.position
                val ourLat = ourPos?.latitude_i?.times(LAT_LON_SCALE)
                val ourLon = ourPos?.longitude_i?.times(LAT_LON_SCALE)
                val distMeters =
                    if (ourLat != null && ourLon != null) {
                        calculateDistanceMeters(lat, lon, ourLat, ourLon)
                    } else {
                        null
                    }

                val user = nodeRepository.getUser(packet.from)
                TriggerEvent(
                    nodeId = packet.from,
                    nodeName = user.long_name.ifBlank { "Node ${packet.from}" },
                    latitude = lat,
                    longitude = lon,
                    distanceMeters = distMeters,
                )
            }

    private fun createNodeStatusChangedFlow(trigger: AutomationTrigger.NodeStatusChanged): Flow<TriggerEvent> =
        when (trigger.statusType) {
            NodeStatusType.APPEARED -> createNodeAppearedFlow(AutomationTrigger.NodeAppeared(trigger.fromNodeId))

            NodeStatusType.DISAPPEARED ->
                createNodeLostFlow(AutomationTrigger.NodeDisappeared(trigger.fromNodeId, trigger.timeoutMinutes))
        }

    private fun createRadioConnectionChangedFlow(
        trigger: AutomationTrigger.RadioConnectionChanged,
    ): Flow<TriggerEvent> = if (trigger.requiredConnected) createRadioConnectedFlow() else createRadioDisconnectedFlow()

    @Suppress("UnusedParameter")
    private fun createDeviceBatteryChangedFlow(trigger: AutomationTrigger.DeviceBatteryChanged): Flow<TriggerEvent> =
        emptyFlow()

    private fun createScheduleTickFlow(trigger: AutomationTrigger.ScheduleTick): Flow<TriggerEvent> = flow {
        val intervalMs = (trigger.intervalMinutes.coerceAtLeast(1)) * MS_PER_MINUTE
        while (currentCoroutineContext().isActive) {
            delay(intervalMs)
            emit(TriggerEvent())
        }
    }
}
