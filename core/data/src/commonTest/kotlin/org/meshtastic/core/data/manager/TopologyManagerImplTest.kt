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

import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.meshtastic.core.common.di.asServiceScope
import org.meshtastic.core.database.dao.TopologyEdgeDao
import org.meshtastic.core.database.entity.TopologyEdge
import org.meshtastic.core.model.MqttConnectionState
import org.meshtastic.core.model.TopologySource
import org.meshtastic.core.repository.MqttManager
import org.meshtastic.core.repository.NodeManager
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.proto.Data
import org.meshtastic.proto.LocalModuleConfig
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.Neighbor
import org.meshtastic.proto.NeighborInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.RouteDiscovery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeTopologyEdgeDao : TopologyEdgeDao {
    val edges = mutableMapOf<Pair<Int, Int>, TopologyEdge>()
    val edgesFlow = MutableStateFlow<List<TopologyEdge>>(emptyList())

    override suspend fun upsertEdge(edge: TopologyEdge) {
        val key = edge.node1 to edge.node2
        edges[key] = edge
        edgesFlow.value = edges.values.toList()
    }

    override suspend fun upsertEdgeWithPriority(edge: TopologyEdge) {
        val key = edge.node1 to edge.node2
        val existing = edges[key]
        if (existing == null) {
            edges[key] = edge
        } else {
            val shouldUpdateSnr = edge.source == TopologySource.MQTT || edge.bestSnr > existing.bestSnr
            val updated =
                existing.copy(
                    bestSnr = if (shouldUpdateSnr) edge.bestSnr else existing.bestSnr,
                    bestRssi = if (shouldUpdateSnr) edge.bestRssi else maxOf(existing.bestRssi, edge.bestRssi),
                    lastSeen = edge.lastSeen,
                    source = if (edge.source == TopologySource.MQTT) TopologySource.MQTT else existing.source,
                )
            edges[key] = updated
        }
        edgesFlow.value = edges.values.toList()
    }

    override suspend fun deleteEdge(node1: Int, node2: Int) {
        edges.remove(node1 to node2)
        edgesFlow.value = edges.values.toList()
    }

    override suspend fun deleteInvalidEdges() {
        edges.entries.removeAll { (key, _) ->
            key.first == 0 || key.second == 0 || key.first == -1 || key.second == -1
        }
        edgesFlow.value = edges.values.toList()
    }

    override fun getAllEdgesFlow(): Flow<List<TopologyEdge>> = edgesFlow

    override fun getActiveMqttNodesCount(): Flow<Int> =
        MutableStateFlow(edges.values.count { it.source == TopologySource.MQTT })

    override suspend fun getEdge(node1: Int, node2: Int): TopologyEdge? = edges[node1 to node2]

    override suspend fun clearAllEdges() {
        edges.clear()
        edgesFlow.value = emptyList()
    }
}

class TopologyManagerImplTest {

    private fun createHarness(testScope: TestScope, myNodeNum: Int = 100): Harness {
        val dao = FakeTopologyEdgeDao()
        val nodeManager: NodeManager = mock()
        val nodeNumFlow = MutableStateFlow<Int?>(myNodeNum)
        every { nodeManager.myNodeNum } returns nodeNumFlow

        val mqttManager: MqttManager = mock()
        val proxyActiveFlow = MutableStateFlow(false)
        val isClientEnabledFlow = MutableStateFlow(false)
        val mqttStateFlow = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected.Idle)
        every { mqttManager.proxyActive } returns proxyActiveFlow
        every { mqttManager.isClientEnabled } returns isClientEnabledFlow
        every { mqttManager.mqttConnectionState } returns mqttStateFlow

        val radioConfigRepository: RadioConfigRepository = mock()
        val moduleConfigFlow = MutableStateFlow(LocalModuleConfig.Builder().build())
        every { radioConfigRepository.moduleConfigFlow } returns moduleConfigFlow

        val manager =
            TopologyManagerImpl(
                topologyEdgeDao = dao,
                nodeManager = nodeManager,
                mqttManager = lazy { mqttManager },
                radioConfigRepository = radioConfigRepository,
                scope = testScope.backgroundScope.asServiceScope(),
            )

        return Harness(
            manager = manager,
            dao = dao,
            mqttManager = mqttManager,
            nodeNumFlow = nodeNumFlow,
            proxyActiveFlow = proxyActiveFlow,
            isClientEnabledFlow = isClientEnabledFlow,
            mqttStateFlow = mqttStateFlow,
        )
    }

    private class Harness(
        val manager: TopologyManagerImpl,
        val dao: FakeTopologyEdgeDao,
        val mqttManager: MqttManager,
        val nodeNumFlow: MutableStateFlow<Int?>,
        val proxyActiveFlow: MutableStateFlow<Boolean>,
        val isClientEnabledFlow: MutableStateFlow<Boolean>,
        val mqttStateFlow: MutableStateFlow<MqttConnectionState>,
    )

    @Test
    fun `traceroute packet creates edges for all consecutive hops`() = runTest {
        val harness = createHarness(this, myNodeNum = 3)
        // Meshtastic RouteDiscovery payload stores intermediate nodes (here node 2 between 1 and 3)
        // Two hops: 3->2 and 2->1, both recorded SNR of 34 (8.5 dB)
        val routeDiscovery = RouteDiscovery.Builder().route(listOf(2)).snr_towards(listOf(34, 34)).build()
        val payload = RouteDiscovery.ADAPTER.encode(routeDiscovery).toByteString()

        val data = Data.Builder().portnum(PortNum.TRACEROUTE_APP).payload(payload).build()

        val packet = MeshPacket.Builder().from(1).to(3).decoded(data).rx_snr(8.5f).rx_rssi(-65).build()

        harness.manager.processPacket(packet, TopologySource.LOCAL_RADIO)
        runCurrent()

        val edges = harness.manager.allEdgesFlow.first()
        assertEquals(2, edges.size)

        val edge1 = harness.dao.getEdge(1, 2)
        assertNotNull(edge1)
        assertEquals(8.5f, edge1.bestSnr)
        assertEquals(0, edge1.bestRssi)
        assertEquals(TopologySource.LOCAL_RADIO, edge1.source)

        val edge2 = harness.dao.getEdge(2, 3)
        assertNotNull(edge2)
        assertEquals(8.5f, edge2.bestSnr)
        assertEquals(-65, edge2.bestRssi)
        assertEquals(TopologySource.LOCAL_RADIO, edge2.source)
    }

    @Test
    fun `neighbor info packet creates edges for each neighbor`() = runTest {
        val harness = createHarness(this)
        val neighborInfo =
            NeighborInfo.Builder()
                .node_id(10)
                .neighbors(
                    listOf(
                        Neighbor.Builder().node_id(20).snr(6.0f).build(),
                        Neighbor.Builder().node_id(30).snr(10.5f).build(),
                    ),
                )
                .build()
        val payload = NeighborInfo.ADAPTER.encode(neighborInfo).toByteString()

        val data = Data.Builder().portnum(PortNum.NEIGHBORINFO_APP).payload(payload).build()

        val packet =
            MeshPacket.Builder().from(10).to(0xFFFFFFFF.toInt()).decoded(data).rx_snr(5.0f).rx_rssi(-80).build()

        harness.manager.processPacket(packet, TopologySource.MQTT)
        runCurrent()

        val edges = harness.manager.allEdgesFlow.first()
        assertEquals(2, edges.size)

        val edge1 = harness.dao.getEdge(10, 20)
        assertNotNull(edge1)
        assertEquals(6.0f, edge1.bestSnr)
        assertEquals(TopologySource.MQTT, edge1.source)

        val edge2 = harness.dao.getEdge(10, 30)
        assertNotNull(edge2)
        assertEquals(10.5f, edge2.bestSnr)
        assertEquals(TopologySource.MQTT, edge2.source)
    }

    @Test
    fun `direct radio reception creates edge between sender and ourNodeNum`() = runTest {
        val harness = createHarness(this, myNodeNum = 100)

        val packet =
            MeshPacket.Builder()
                .from(200)
                .to(0xFFFFFFFF.toInt())
                .hop_start(3)
                .hop_limit(3)
                .rx_snr(9.2f)
                .rx_rssi(-55)
                .build()

        harness.manager.processPacket(packet, TopologySource.LOCAL_RADIO)
        runCurrent()

        val edge = harness.dao.getEdge(100, 200)
        assertNotNull(edge)
        assertEquals(9.2f, edge.bestSnr)
        assertEquals(-55, edge.bestRssi)
        assertEquals(TopologySource.LOCAL_RADIO, edge.source)
    }

    @Test
    fun `multihop packet does not create direct radio edge`() = runTest {
        val harness = createHarness(this, myNodeNum = 100)

        val packet =
            MeshPacket.Builder()
                .from(200)
                .to(0xFFFFFFFF.toInt())
                .hop_start(3)
                .hop_limit(2) // hop_limit < hop_start means it was routed through an intermediary
                .rx_snr(9.2f)
                .rx_rssi(-55)
                .build()

        harness.manager.processPacket(packet, TopologySource.LOCAL_RADIO)
        runCurrent()

        val edge = harness.dao.getEdge(100, 200)
        assertNull(edge)
    }

    @Test
    fun `mqtt packet updates edge even if SNR is lower prioritizing MQTT source`() = runTest {
        val harness = createHarness(this)

        val directPacket =
            MeshPacket.Builder().from(200).to(100).hop_start(3).hop_limit(3).rx_snr(12.0f).rx_rssi(-50).build()
        harness.manager.processPacket(directPacket, TopologySource.LOCAL_RADIO)
        runCurrent()

        val localEdge = harness.dao.getEdge(100, 200)
        assertNotNull(localEdge)
        assertEquals(12.0f, localEdge.bestSnr)
        assertEquals(TopologySource.LOCAL_RADIO, localEdge.source)

        // Now MQTT packet arrives with SNR 4.0 dB
        val neighborInfo =
            NeighborInfo.Builder()
                .node_id(100)
                .neighbors(listOf(Neighbor.Builder().node_id(200).snr(4.0f).build()))
                .build()
        val payload = NeighborInfo.ADAPTER.encode(neighborInfo).toByteString()
        val data = Data.Builder().portnum(PortNum.NEIGHBORINFO_APP).payload(payload).build()
        val mqttPacket =
            MeshPacket.Builder().from(100).to(0xFFFFFFFF.toInt()).decoded(data).rx_snr(4.0f).rx_rssi(-90).build()
        harness.manager.processPacket(mqttPacket, TopologySource.MQTT)
        runCurrent()

        val updatedEdge = harness.dao.getEdge(100, 200)
        assertNotNull(updatedEdge)
        assertEquals(4.0f, updatedEdge.bestSnr)
        assertEquals(TopologySource.MQTT, updatedEdge.source)
    }

    @Test
    fun `clearAllEdges clears all edges`() = runTest {
        val harness = createHarness(this)

        val packet = MeshPacket.Builder().from(10).to(20).hop_start(3).hop_limit(3).rx_snr(5.0f).rx_rssi(-70).build()
        harness.manager.processPacket(packet, TopologySource.LOCAL_RADIO)
        runCurrent()
        assertTrue(harness.manager.allEdgesFlow.first().isNotEmpty())

        harness.manager.clearAllEdges()
        runCurrent()
        assertTrue(harness.manager.allEdgesFlow.first().isEmpty())
    }

    @Test
    fun `mqtt packet with gateway creates direct edge to gateway for 0 hops even if encrypted`() = runTest {
        val harness = createHarness(this)

        val gatewayHex = "!29048a12"
        val gatewayNum = 0x29048a12
        val encryptedPacket =
            MeshPacket.Builder()
                .from(0x12345678)
                .to(0xFFFFFFFF.toInt())
                .hop_start(3)
                .hop_limit(3)
                .rx_snr(3.25f)
                .rx_rssi(-78)
                .encrypted("ciphertext".encodeToByteArray().toByteString())
                .build()

        harness.manager.processPacket(encryptedPacket, TopologySource.MQTT, gatewayId = gatewayHex)
        runCurrent()

        val edge = harness.dao.getEdge(0x12345678, gatewayNum)
        assertNotNull(edge)
        assertEquals(3.25f, edge.bestSnr)
        assertEquals(-78, edge.bestRssi)
        assertEquals(TopologySource.MQTT, edge.source)
    }

    @Test
    fun `traceroute converts quarter-dB integer SNRs properly`() = runTest {
        val harness = createHarness(this)

        // Intermediate node 2 between sender 1 and receiver 3
        val routeDiscovery = RouteDiscovery.Builder().route(listOf(2)).snr_towards(listOf(-16, 24)).build()
        val payload = RouteDiscovery.ADAPTER.encode(routeDiscovery).toByteString()
        val data = Data.Builder().portnum(PortNum.TRACEROUTE_APP).payload(payload).build()
        val packet = MeshPacket.Builder().from(1).to(3).decoded(data).rx_snr(10.0f).rx_rssi(-60).build()

        harness.manager.processPacket(packet, TopologySource.LOCAL_RADIO)
        runCurrent()

        // Hop 0: (3, 2) with SNR -16 / 4 = -4.0 dB
        val edge23 = harness.dao.getEdge(2, 3)
        assertNotNull(edge23)
        assertEquals(-4.0f, edge23.bestSnr)

        // Hop 1: (2, 1) with SNR 24 / 4 = 6.0 dB
        val edge12 = harness.dao.getEdge(1, 2)
        assertNotNull(edge12)
        assertEquals(6.0f, edge12.bestSnr)
    }

    @Test
    fun `packet via_mqtt=true from radio does not create direct radio edge to ourNodeNum`() = runTest {
        val harness = createHarness(this, myNodeNum = 100)

        val packet =
            MeshPacket.Builder()
                .from(200)
                .to(0xFFFFFFFF.toInt())
                .hop_start(3)
                .hop_limit(3)
                .via_mqtt(true)
                .rx_snr(9.2f)
                .rx_rssi(-55)
                .build()

        harness.manager.processPacket(packet, TopologySource.LOCAL_RADIO)
        runCurrent()

        val edge = harness.dao.getEdge(100, 200)
        assertNull(edge)
    }

    @Test
    fun `direct traceroute without repeaters creates edge between endpoints`() = runTest {
        val harness = createHarness(this)
        val routeDiscovery = RouteDiscovery.Builder().snr_towards(listOf(28)).build() // 28 / 4 = 7.0 dB
        val payload = RouteDiscovery.ADAPTER.encode(routeDiscovery).toByteString()

        val data = Data.Builder().portnum(PortNum.TRACEROUTE_APP).payload(payload).build()
        val packet = MeshPacket.Builder().from(10).to(20).decoded(data).rx_snr(7.0f).rx_rssi(-70).build()

        harness.manager.processPacket(packet, TopologySource.MQTT)
        runCurrent()

        val edge = harness.dao.getEdge(10, 20)
        assertNotNull(edge)
        assertEquals(7.0f, edge.bestSnr)
        assertEquals(TopologySource.MQTT, edge.source)
    }

    @Test
    fun `traceroute request from third party creates edge for repeaters traversed`() = runTest {
        val harness = createHarness(this)
        val routeDiscovery =
            RouteDiscovery.Builder().route(listOf(200)).snr_towards(listOf(36)).build() // 36 / 4 = 9.0 dB
        val payload = RouteDiscovery.ADAPTER.encode(routeDiscovery).toByteString()

        val data =
            Data.Builder()
                .portnum(PortNum.TRACEROUTE_APP)
                .want_response(true)
                .source(100)
                .dest(300)
                .payload(payload)
                .build()
        val packet = MeshPacket.Builder().from(100).to(300).decoded(data).rx_snr(9.0f).rx_rssi(-75).build()

        harness.manager.processPacket(packet, TopologySource.LOCAL_RADIO)
        runCurrent()

        val edge = harness.dao.getEdge(100, 200)
        assertNotNull(edge)
        assertEquals(9.0f, edge.bestSnr)
    }

    @Test
    fun `traceroute ignores broadcast and negative one ffffffff hops`() = runTest {
        val harness = createHarness(this)
        val broadcastNode = 0xFFFFFFFF.toInt() // -1
        val routeDiscovery =
            RouteDiscovery.Builder().route(listOf(broadcastNode, 200)).snr_towards(listOf(0, 36)).build()
        val payload = RouteDiscovery.ADAPTER.encode(routeDiscovery).toByteString()

        val data =
            Data.Builder()
                .portnum(PortNum.TRACEROUTE_APP)
                .want_response(false)
                .source(300)
                .dest(100)
                .payload(payload)
                .build()
        val packet = MeshPacket.Builder().from(300).to(100).decoded(data).rx_snr(5.0f).rx_rssi(-80).build()

        harness.manager.processPacket(packet, TopologySource.LOCAL_RADIO)
        runCurrent()

        // Edges connecting to -1 / broadcast should NOT exist
        assertNull(harness.dao.getEdge(minOf(100, broadcastNode), maxOf(100, broadcastNode)))
        assertNull(harness.dao.getEdge(minOf(200, broadcastNode), maxOf(200, broadcastNode)))
        // Valid hop 200 <-> 300 should exist
        assertNotNull(harness.dao.getEdge(200, 300))
    }

    @Test
    fun `traceroute intermediate hops do not inherit receiver SNR and RSSI`() = runTest {
        val harness = createHarness(this, myNodeNum = 999)
        // Hops: 100 -> 200 (intermediate), 200 -> 300 (intermediate). Neither is 999 (our node).
        val routeDiscovery = RouteDiscovery.Builder().route(listOf(200)).snr_towards(emptyList()).build()
        val payload = RouteDiscovery.ADAPTER.encode(routeDiscovery).toByteString()

        val data =
            Data.Builder()
                .portnum(PortNum.TRACEROUTE_APP)
                .want_response(false)
                .source(300)
                .dest(100)
                .payload(payload)
                .build()
        val packet = MeshPacket.Builder().from(300).to(100).decoded(data).rx_snr(12.0f).rx_rssi(-65).build()

        harness.manager.processPacket(packet, TopologySource.MQTT)
        runCurrent()

        val edge1 = harness.dao.getEdge(100, 200)
        assertNotNull(edge1)
        assertEquals(0.0f, edge1.bestSnr)
        assertEquals(0, edge1.bestRssi)

        val edge2 = harness.dao.getEdge(200, 300)
        assertNotNull(edge2)
        assertEquals(0.0f, edge2.bestSnr)
        assertEquals(0, edge2.bestRssi)
    }

    @Test
    fun `neighborInfo remote edges do not inherit packet receiver RSSI`() = runTest {
        val harness = createHarness(this, myNodeNum = 999)
        val neighbor = Neighbor.Builder().node_id(200).snr(8.5f).build()
        val neighborInfo = NeighborInfo.Builder().node_id(100).neighbors(listOf(neighbor)).build()
        val payload = NeighborInfo.ADAPTER.encode(neighborInfo).toByteString()
        val data = Data.Builder().portnum(PortNum.NEIGHBORINFO_APP).payload(payload).build()
        val packet =
            MeshPacket.Builder().from(100).to(0xFFFFFFFF.toInt()).decoded(data).rx_snr(10.0f).rx_rssi(-72).build()

        harness.manager.processPacket(packet, TopologySource.MQTT)
        runCurrent()

        val edge = harness.dao.getEdge(100, 200)
        assertNotNull(edge)
        assertEquals(8.5f, edge.bestSnr)
        // RSSI should be 0 because neither node 100 nor 200 is our node (999)
        assertEquals(0, edge.bestRssi)
    }

    @Test
    fun `isMqttActive is true when isClientEnabled is true`() = runTest {
        val harness = createHarness(this)
        assertFalse(harness.manager.isMqttActive.value)

        harness.isClientEnabledFlow.value = true
        runCurrent()
        assertTrue(harness.manager.isMqttActive.value)

        harness.isClientEnabledFlow.value = false
        runCurrent()
        assertFalse(harness.manager.isMqttActive.value)
    }
}
