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
package org.meshtastic.core.data.manager

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single
import org.meshtastic.core.common.di.ServiceScope
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.database.dao.TopologyEdgeDao
import org.meshtastic.core.database.entity.TopologyEdge
import org.meshtastic.core.model.MqttConnectionState
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.model.TopologySource
import org.meshtastic.core.model.neighborInfo
import org.meshtastic.core.repository.MqttManager
import org.meshtastic.core.repository.NodeManager
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.repository.TopologyManager
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.RouteDiscovery

private const val QUARTER_DB_SCALE = 4.0f
private const val UNKNOWN_SNR_SENTINEL = -128
private const val MIN_PLAUSIBLE_SNR = -100

@Single(binds = [TopologyManager::class])
class TopologyManagerImpl(
    private val topologyEdgeDao: TopologyEdgeDao,
    private val nodeManager: NodeManager,
    private val mqttManager: Lazy<MqttManager>,
    private val radioConfigRepository: RadioConfigRepository,
    private val scope: ServiceScope,
) : TopologyManager {

    private val logger = Logger.withTag("Topology")

    init {
        scope.launch {
            runCatching {
                topologyEdgeDao.deleteInvalidEdges()
            }
                .onFailure { e ->
                    logger.w(e) { "Failed to clean invalid topology edges on startup" }
                }
        }
    }

    override val allEdgesFlow: Flow<List<TopologyEdge>> = topologyEdgeDao.getAllEdgesFlow()

    override val activeMqttNodesCount: Flow<Int> = topologyEdgeDao.getActiveMqttNodesCount()

    override val isMqttActive: StateFlow<Boolean> =
        combine(
            mqttManager.value.proxyActive,
            mqttManager.value.isClientEnabled,
            mqttManager.value.mqttConnectionState,
            radioConfigRepository.moduleConfigFlow,
        ) { proxyActive, clientEnabled, connState, moduleConfig ->
            proxyActive ||
                clientEnabled ||
                connState is MqttConnectionState.Connected ||
                moduleConfig.mqtt?.enabled == true
        }
            .stateIn(scope, SharingStarted.Eagerly, false)

    @Suppress("TooGenericExceptionCaught")
    override fun processPacket(packet: MeshPacket, source: TopologySource, gatewayId: String?) {
        scope.launch {
            try {
                handlePacketInternal(packet, source, gatewayId)
            } catch (e: Exception) {
                logger.w(e) { "Error processing packet for topology from $source (gateway=$gatewayId)" }
            }
        }
    }

    override suspend fun clearAllEdges() {
        topologyEdgeDao.clearAllEdges()
    }

    private fun isValidTopologyNode(nodeNum: Int): Boolean =
        nodeNum != 0 && nodeNum != NodeAddress.NODENUM_BROADCAST && nodeNum != -1

    private suspend fun handlePacketInternal(packet: MeshPacket, source: TopologySource, gatewayId: String?) {
        val now = nowMillis

        if (!gatewayId.isNullOrBlank()) {
            handleGatewayEdge(packet, gatewayId, now)
        }

        handleTraceroute(packet, source, now)
        handleNeighborInfo(packet, source, now)

        if (source == TopologySource.LOCAL_RADIO && packet.via_mqtt != true) {
            handleDirectRadio(packet, now)
        }
    }

    private suspend fun handleGatewayEdge(packet: MeshPacket, gatewayId: String, now: Long) {
        val gatewayNum = NodeAddress.idToNum(gatewayId) ?: return
        val fromNode = packet.from
        if (!isValidTopologyNode(gatewayNum) || !isValidTopologyNode(fromNode) || gatewayNum == fromNode) return

        val isDirectToGateway = packet.hop_start > 0 && packet.hop_start == packet.hop_limit
        if (isDirectToGateway) {
            val edge =
                TopologyEdge.create(
                    from = fromNode,
                    to = gatewayNum,
                    bestSnr = packet.rx_snr,
                    bestRssi = packet.rx_rssi ?: 0,
                    lastSeen = now,
                    source = TopologySource.MQTT,
                )
            topologyEdgeDao.upsertEdgeWithPriority(edge)
            logger.d {
                "Gateway direct RF edge via MQTT: $fromNode <-> $gatewayNum (${packet.rx_snr} dB, gateway=$gatewayId)"
            }
        }
    }

    private suspend fun handleTraceroute(packet: MeshPacket, source: TopologySource, now: Long) {
        val decoded = packet.decoded ?: return
        val routeDiscovery = decodeTraceroutePayload(decoded) ?: return
        val isResponse = decoded.want_response == false

        if (isResponse) {
            val requester = if (decoded.dest != 0) decoded.dest else packet.to
            val target = if (decoded.source != 0) decoded.source else packet.from
            if (isValidTopologyNode(requester) && isValidTopologyNode(target) && requester != target) {
                val forwardNodes = listOf(requester) + routeDiscovery.route + target
                processRouteEdges(forwardNodes, routeDiscovery.snr_towards, packet, source, now, isReturn = false)

                val returnRepeaters = routeDiscovery.route_back
                val returnSnrs = routeDiscovery.snr_back
                if (returnRepeaters.isNotEmpty() || returnSnrs.isNotEmpty()) {
                    val returnNodes = listOf(target) + returnRepeaters + requester
                    processRouteEdges(returnNodes, returnSnrs, packet, source, now, isReturn = true)
                }
            }
        } else {
            val requester = if (decoded.source != 0) decoded.source else packet.from
            val repeaters = routeDiscovery.route
            if (isValidTopologyNode(requester) && repeaters.isNotEmpty()) {
                val forwardNodes = listOf(requester) + repeaters
                processRouteEdges(forwardNodes, routeDiscovery.snr_towards, packet, source, now, isReturn = false)
            }
        }
    }

    private fun decodeTraceroutePayload(decoded: Data): RouteDiscovery? {
        val isTraceroutePort = decoded.portnum == PortNum.TRACEROUTE_APP || decoded.portnum == PortNum.ROUTING_APP
        return if (isTraceroutePort) {
            runCatching { RouteDiscovery.ADAPTER.decode(decoded.payload) }.getOrNull()
        } else {
            null
        }
    }

    private suspend fun processRouteEdges(
        nodes: List<Int>,
        snrList: List<Int>,
        packet: MeshPacket,
        source: TopologySource,
        now: Long,
        isReturn: Boolean,
    ) {
        if (nodes.size < 2) return
        val myNodeNum = nodeManager.myNodeNum.value ?: 0
        for (i in 0 until nodes.size - 1) {
            val fromNode = nodes[i]
            val toNode = nodes[i + 1]
            if (!isValidTopologyNode(fromNode) || !isValidTopologyNode(toNode) || fromNode == toNode) {
                continue
            }
            val rawSnr = snrList.getOrNull(i)
            val isKnownSnr = rawSnr != null && rawSnr != UNKNOWN_SNR_SENTINEL && rawSnr > MIN_PLAUSIBLE_SNR
            val isDirectHopToUs = myNodeNum != 0 && (fromNode == myNodeNum || toNode == myNodeNum)

            val hopSnr =
                when {
                    isKnownSnr -> rawSnr / QUARTER_DB_SCALE
                    isDirectHopToUs -> packet.rx_snr
                    else -> 0.0f
                }
            val hopRssi = if (isDirectHopToUs) packet.rx_rssi ?: 0 else 0

            val edge =
                TopologyEdge.create(
                    from = fromNode,
                    to = toNode,
                    bestSnr = hopSnr,
                    bestRssi = hopRssi,
                    lastSeen = now,
                    source = source,
                )
            topologyEdgeDao.upsertEdgeWithPriority(edge)
            val label = if (isReturn) "return " else ""
            logger.d {
                "Traceroute ${label}edge recorded: $fromNode <-> $toNode ($hopSnr dB, $hopRssi dBm) from $source"
            }
        }
    }

    private suspend fun handleNeighborInfo(packet: MeshPacket, source: TopologySource, now: Long) {
        val neighborInfo = packet.neighborInfo ?: return
        val reporterNode = neighborInfo.node_id.takeIf { it != 0 } ?: packet.from
        if (!isValidTopologyNode(reporterNode) || neighborInfo.neighbors.isEmpty()) {
            return
        }
        val myNodeNum = nodeManager.myNodeNum.value ?: 0

        for (neighbor in neighborInfo.neighbors) {
            val neighborId = neighbor.node_id
            if (!isValidTopologyNode(neighborId) || neighborId == reporterNode) {
                continue
            }
            val isDirectToUs = myNodeNum != 0 && (reporterNode == myNodeNum || neighborId == myNodeNum)
            val edgeRssi = if (isDirectToUs) packet.rx_rssi ?: 0 else 0
            val edge =
                TopologyEdge.create(
                    from = reporterNode,
                    to = neighborId,
                    bestSnr = neighbor.snr,
                    bestRssi = edgeRssi,
                    lastSeen = now,
                    source = source,
                )
            topologyEdgeDao.upsertEdgeWithPriority(edge)
            logger.d {
                "NeighborInfo edge: $reporterNode <-> $neighborId (${neighbor.snr} dB, $edgeRssi dBm) from $source"
            }
        }
    }

    private suspend fun handleDirectRadio(packet: MeshPacket, now: Long) {
        val isDirect = packet.hop_start > 0 && packet.hop_start == packet.hop_limit
        val myNodeNum = nodeManager.myNodeNum.value ?: 0
        val fromNode = packet.from

        if (isDirect && isValidTopologyNode(fromNode) && isValidTopologyNode(myNodeNum) && fromNode != myNodeNum) {
            val edge =
                TopologyEdge.create(
                    from = fromNode,
                    to = myNodeNum,
                    bestSnr = packet.rx_snr,
                    bestRssi = packet.rx_rssi ?: 0,
                    lastSeen = now,
                    source = TopologySource.LOCAL_RADIO,
                )
            topologyEdgeDao.upsertEdgeWithPriority(edge)
            logger.d { "Direct radio edge: $fromNode <-> $myNodeNum (snr=${packet.rx_snr} dB)" }
        }
    }
}
