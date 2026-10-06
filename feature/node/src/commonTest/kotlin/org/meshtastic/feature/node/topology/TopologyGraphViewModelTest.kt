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
package org.meshtastic.feature.node.topology

import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.database.entity.TopologyEdge
import org.meshtastic.core.model.MqttConnectionState
import org.meshtastic.core.model.MqttProbeStatus
import org.meshtastic.core.model.TopologySource
import org.meshtastic.core.repository.MqttManager
import org.meshtastic.core.repository.TopologyManager
import org.meshtastic.core.testing.FakeNodeRepository
import org.meshtastic.core.testing.TestDataFactory
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MqttClientProxyMessage
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeTopologyManager : TopologyManager {
    val edgesFlow = MutableStateFlow<List<TopologyEdge>>(emptyList())
    val mqttNodesCountFlow = MutableStateFlow(0)
    val mqttActiveFlow = MutableStateFlow(false)

    override val allEdgesFlow: Flow<List<TopologyEdge>> = edgesFlow
    override val activeMqttNodesCount: Flow<Int> = mqttNodesCountFlow

    override fun getActiveMqttNodesCount(periodStart: Long): Flow<Int> = mqttNodesCountFlow

    override val isMqttActive: StateFlow<Boolean> = mqttActiveFlow

    override fun processPacket(packet: MeshPacket, source: TopologySource, gatewayId: String?) {}

    override suspend fun clearAllEdges() {
        edgesFlow.value = emptyList()
    }
}

private class FakeMqttManager : MqttManager {
    val clientEnabledFlow = MutableStateFlow(false)
    val connectionStateFlow = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected.Idle)
    val messageRateFlow = MutableStateFlow(0f)
    val proxyActiveFlow = MutableStateFlow(false)

    override val isClientEnabled: StateFlow<Boolean> = clientEnabledFlow
    override val mqttConnectionState: StateFlow<MqttConnectionState> = connectionStateFlow
    override val messageRate: StateFlow<Float> = messageRateFlow
    override val proxyActive: StateFlow<Boolean> = proxyActiveFlow

    override fun setClientEnabled(enabled: Boolean) {
        clientEnabledFlow.value = enabled
    }

    override fun restoreClientState() {}

    override fun startProxy(enabled: Boolean, proxyToClientEnabled: Boolean) {}

    override fun stop() {}

    override fun handleMqttProxyMessage(message: MqttClientProxyMessage) {}

    override suspend fun probe(
        address: String,
        tlsEnabled: Boolean,
        username: String?,
        password: String?,
    ): MqttProbeStatus = MqttProbeStatus.Success(serverInfo = null)
}

class TopologyGraphViewModelTest {

    private lateinit var topologyManager: FakeTopologyManager
    private lateinit var nodeRepository: FakeNodeRepository
    private lateinit var mqttManager: FakeMqttManager
    private lateinit var viewModel: TopologyGraphViewModel

    @BeforeTest
    fun setUp() {
        topologyManager = FakeTopologyManager()
        nodeRepository = FakeNodeRepository()
        mqttManager = FakeMqttManager()
        viewModel = TopologyGraphViewModel(topologyManager, nodeRepository, mqttManager)
    }

    @Test
    fun `default state shows all edges including MQTT`() = runTest {
        val now = nowMillis
        val edgeLocal = TopologyEdge.create(1, 2, 5f, -80, now, TopologySource.LOCAL_RADIO)
        val edgeMqtt = TopologyEdge.create(2, 3, 10f, -70, now, TopologySource.MQTT)
        topologyManager.edgesFlow.value = listOf(edgeLocal, edgeMqtt)

        viewModel.uiState.test {
            var state = awaitItem()
            if (state.edges.isEmpty()) {
                state = awaitItem()
            }
            assertTrue(state.showMqttData)
            assertEquals(TopologyTimePeriod.ALL, state.timePeriod)
            assertEquals(2, state.edges.size)
            assertEquals(2, state.totalEdgesCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hiding MQTT data filters out MQTT edges but keeps local edges`() = runTest {
        val now = nowMillis
        val edgeLocal = TopologyEdge.create(1, 2, 5f, -80, now, TopologySource.LOCAL_RADIO)
        val edgeMqtt = TopologyEdge.create(2, 3, 10f, -70, now, TopologySource.MQTT)
        topologyManager.edgesFlow.value = listOf(edgeLocal, edgeMqtt)

        viewModel.uiState.test {
            var state = awaitItem()
            if (state.edges.isEmpty()) {
                state = awaitItem()
            }
            assertEquals(2, state.edges.size)

            viewModel.setShowMqttData(false)
            val updated = awaitItem()
            assertFalse(updated.showMqttData)
            assertEquals(1, updated.edges.size)
            assertEquals(edgeLocal, updated.edges.first())
            assertEquals(2, updated.totalEdgesCount)

            viewModel.setShowMqttData(true)
            val restored = awaitItem()
            assertTrue(restored.showMqttData)
            assertEquals(2, restored.edges.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `time period filter filters out edges older than duration`() = runTest {
        val now = nowMillis
        val freshEdge =
            TopologyEdge.create(1, 2, 5f, -80, now - 30 * 60 * 1000L, TopologySource.LOCAL_RADIO) // 30 min ago
        val oldEdge =
            TopologyEdge.create(2, 3, 5f, -80, now - 2 * 60 * 60 * 1000L, TopologySource.LOCAL_RADIO) // 2 hours ago
        val ancientEdge =
            TopologyEdge.create(3, 4, 5f, -80, now - 25 * 60 * 60 * 1000L, TopologySource.LOCAL_RADIO) // 25 hours ago

        topologyManager.edgesFlow.value = listOf(freshEdge, oldEdge, ancientEdge)

        viewModel.uiState.test {
            var state = awaitItem()
            if (state.edges.isEmpty()) {
                state = awaitItem()
            }
            assertEquals(3, state.edges.size)

            viewModel.setTimePeriod(TopologyTimePeriod.ONE_HOUR)
            val oneHourState = awaitItem()
            assertEquals(1, oneHourState.edges.size)
            assertEquals(freshEdge, oneHourState.edges.first())

            viewModel.setTimePeriod(TopologyTimePeriod.TWENTY_FOUR_HOURS)
            val oneDayState = awaitItem()
            assertEquals(2, oneDayState.edges.size)
            assertTrue(oneDayState.edges.contains(freshEdge))
            assertTrue(oneDayState.edges.contains(oldEdge))

            viewModel.setTimePeriod(TopologyTimePeriod.ALL)
            val allState = awaitItem()
            assertEquals(3, allState.edges.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clearTopology delegates to manager and resets selection`() = runTest {
        val edge = TopologyEdge.create(1, 2, 5f, -80, nowMillis, TopologySource.LOCAL_RADIO)
        topologyManager.edgesFlow.value = listOf(edge)

        viewModel.selectEdge(edge)
        viewModel.uiState.test {
            var state = awaitItem()
            if (state.edges.isEmpty()) {
                state = awaitItem()
            }
            assertEquals(1, state.edges.size)
            assertEquals(edge, state.selectedEdge)

            viewModel.clearTopology()
            while (state.edges.isNotEmpty() || state.selectedEdge != null) {
                state = awaitItem()
            }
            assertEquals(0, state.edges.size)
            assertEquals(null, state.selectedEdge)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setMqttClientEnabled updates mqttManager and uiState`() = runTest {
        viewModel.uiState.test {
            var state = awaitItem()
            assertFalse(state.isMqttClientEnabled)

            viewModel.setMqttClientEnabled(true)
            while (!state.isMqttClientEnabled) {
                state = awaitItem()
            }
            assertTrue(state.isMqttClientEnabled)
            assertTrue(state.isMqttMode)
            assertTrue(mqttManager.clientEnabledFlow.value)

            viewModel.setMqttClientEnabled(false)
            while (state.isMqttClientEnabled) {
                state = awaitItem()
            }
            assertFalse(state.isMqttClientEnabled)
            assertFalse(mqttManager.clientEnabledFlow.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `mqtt connection state and message rate are exposed in uiState`() = runTest {
        viewModel.uiState.test {
            var state = awaitItem()
            assertEquals(MqttConnectionState.Disconnected.Idle, state.mqttConnectionState)
            assertEquals(0f, state.mqttMessageRate)

            mqttManager.connectionStateFlow.value = MqttConnectionState.Connected
            mqttManager.messageRateFlow.value = 5.5f
            while (state.mqttConnectionState !is MqttConnectionState.Connected || state.mqttMessageRate != 5.5f) {
                state = awaitItem()
            }
            assertEquals(MqttConnectionState.Connected, state.mqttConnectionState)
            assertEquals(5.5f, state.mqttMessageRate)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uniqueNodeNums includes isolated ourNode even without edges`() = runTest {
        nodeRepository.setOurNode(TestDataFactory.createTestNode(num = 42))
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.ourNode?.num != 42) {
                state = awaitItem()
            }
            assertEquals(setOf(42), state.uniqueNodeNums)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selection mutual exclusion selectNode clears edge and selectEdge clears node`() = runTest {
        val edge = TopologyEdge.create(1, 2, 5f, -80, nowMillis, TopologySource.LOCAL_RADIO)
        viewModel.uiState.test {
            var state = awaitItem()
            viewModel.selectNode(1)
            while (state.selectedNodeNum != 1 || state.selectedEdge != null) {
                state = awaitItem()
            }
            assertEquals(1, state.selectedNodeNum)
            assertEquals(null, state.selectedEdge)

            viewModel.selectEdge(edge)
            while (state.selectedEdge != edge || state.selectedNodeNum != null) {
                state = awaitItem()
            }
            assertEquals(edge, state.selectedEdge)
            assertEquals(null, state.selectedNodeNum)

            viewModel.selectNode(2)
            while (state.selectedNodeNum != 2 || state.selectedEdge != null) {
                state = awaitItem()
            }
            assertEquals(2, state.selectedNodeNum)
            assertEquals(null, state.selectedEdge)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `combined filter correctly tracks hiddenByPeriodCount and hiddenByMqttCount`() = runTest {
        val now = nowMillis
        val recentLocal = TopologyEdge.create(1, 2, 5f, -80, now - 10_000L, TopologySource.LOCAL_RADIO)
        val recentMqtt = TopologyEdge.create(2, 3, 5f, -80, now - 20_000L, TopologySource.MQTT)
        val oldLocal = TopologyEdge.create(3, 4, 5f, -80, now - 2 * 3600_000L, TopologySource.LOCAL_RADIO)
        val oldMqtt = TopologyEdge.create(4, 5, 5f, -80, now - 3 * 3600_000L, TopologySource.MQTT)

        topologyManager.edgesFlow.value = listOf(recentLocal, recentMqtt, oldLocal, oldMqtt)

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.totalEdgesCount != 4) {
                state = awaitItem()
            }
            assertEquals(4, state.edges.size)
            assertEquals(0, state.hiddenByPeriodCount)
            assertEquals(0, state.hiddenByMqttCount)

            viewModel.setTimePeriod(TopologyTimePeriod.ONE_HOUR)
            state = awaitItem()
            assertEquals(2, state.edges.size)
            assertEquals(2, state.hiddenByPeriodCount)
            assertEquals(0, state.hiddenByMqttCount)

            viewModel.setShowMqttData(false)
            state = awaitItem()
            assertEquals(1, state.edges.size)
            assertEquals(listOf(recentLocal), state.edges)
            assertEquals(2, state.hiddenByPeriodCount)
            assertEquals(1, state.hiddenByMqttCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `activeMqttNodesCount reflects unique MQTT nodes within active period`() = runTest {
        val now = nowMillis
        val recentMqtt1 = TopologyEdge.create(10, 20, 5f, -80, now - 10_000L, TopologySource.MQTT)
        val recentMqtt2 = TopologyEdge.create(20, 30, 5f, -80, now - 20_000L, TopologySource.MQTT)
        val oldMqtt = TopologyEdge.create(40, 50, 5f, -80, now - 5 * 3600_000L, TopologySource.MQTT)

        topologyManager.edgesFlow.value = listOf(recentMqtt1, recentMqtt2, oldMqtt)

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.totalEdgesCount != 3) {
                state = awaitItem()
            }
            assertEquals(5, state.activeMqttNodesCount)

            viewModel.setTimePeriod(TopologyTimePeriod.ONE_HOUR)
            state = awaitItem()
            assertEquals(3, state.activeMqttNodesCount)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
