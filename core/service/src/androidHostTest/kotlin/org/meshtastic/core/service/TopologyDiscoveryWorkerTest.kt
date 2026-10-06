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
package org.meshtastic.core.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.meshtastic.core.database.entity.TopologyEdge
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.TopologySource
import org.meshtastic.core.repository.TopologyManager
import org.meshtastic.core.service.worker.TopologyDiscoveryWorker
import org.meshtastic.core.testing.FakeCommandSender
import org.meshtastic.core.testing.FakeNodeRepository
import org.meshtastic.core.testing.FakeRadioController
import org.meshtastic.core.testing.FakeUiPrefs
import org.meshtastic.core.testing.TestDataFactory
import org.meshtastic.proto.MeshPacket
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TopologyDiscoveryWorkerTest {

    private lateinit var context: Context
    private lateinit var uiPrefs: FakeUiPrefs
    private lateinit var fakeTopologyManager: FakeWorkerTopologyManager
    private lateinit var nodeRepository: FakeNodeRepository
    private lateinit var commandSender: FakeCommandSender
    private lateinit var radioController: FakeRadioController

    private class FakeWorkerTopologyManager : TopologyManager {
        val isMqttActiveFlow = MutableStateFlow(false)
        override val isMqttActive: StateFlow<Boolean> = isMqttActiveFlow
        override val allEdgesFlow: Flow<List<TopologyEdge>> = emptyFlow()
        override val activeMqttNodesCount: Flow<Int> = emptyFlow()

        override fun getActiveMqttNodesCount(periodStart: Long): Flow<Int> = emptyFlow()

        override fun processPacket(packet: MeshPacket, source: TopologySource, gatewayId: String?) {}

        override suspend fun clearAllEdges() {}
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        uiPrefs = FakeUiPrefs()
        fakeTopologyManager = FakeWorkerTopologyManager()
        nodeRepository = FakeNodeRepository()
        commandSender = FakeCommandSender()
        radioController = FakeRadioController()

        // Defaults
        uiPrefs.setAutoTopologyDiscoveryEnabled(true)
        radioController.setConnectionState(ConnectionState.Connected)
        nodeRepository.setOurNode(TestDataFactory.createTestNode(num = 999))
        TopologyDiscoveryWorker.clearTraceHistory()
    }

    private fun createWorker(): TopologyDiscoveryWorker = TestListenableWorkerBuilder<TopologyDiscoveryWorker>(context)
        .setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = TopologyDiscoveryWorker(
                    appContext = appContext,
                    workerParams = workerParameters,
                    uiPrefs = uiPrefs,
                    topologyManager = fakeTopologyManager,
                    nodeRepository = nodeRepository,
                    commandSender = commandSender,
                    radioController = radioController,
                )
            },
        )
        .build()

    @Test
    fun `when discovery preference is disabled, returns success without sending traceroutes`() = runTest {
        uiPrefs.setAutoTopologyDiscoveryEnabled(false)

        val result = createWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(commandSender.tracerouteRequests.isEmpty())
    }

    @Test
    fun `when MQTT is active, pauses discovery and returns success without airtime requests`() = runTest {
        fakeTopologyManager.isMqttActiveFlow.value = true

        val result = createWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(commandSender.tracerouteRequests.isEmpty())
    }

    @Test
    fun `when radio is disconnected, returns retry`() = runTest {
        radioController.setConnectionState(ConnectionState.Disconnected)

        val result = createWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertTrue(commandSender.tracerouteRequests.isEmpty())
    }

    @Test
    fun `selects top candidates and avoids ourNode, ignored, node0, or uncontacted`() = runTest {
        val node1 = TestDataFactory.createTestNode(num = 1, lastHeard = 500)
        val node2 = TestDataFactory.createTestNode(num = 2, lastHeard = 400)
        val node3 = TestDataFactory.createTestNode(num = 3, lastHeard = 300)
        val node4 = TestDataFactory.createTestNode(num = 4, lastHeard = 200)
        val node5 = TestDataFactory.createTestNode(num = 5, lastHeard = 100)
        val ourNode = TestDataFactory.createTestNode(num = 999, lastHeard = 600)
        val ignoredNode = TestDataFactory.createTestNode(num = 6, lastHeard = 550).copy(isIgnored = true)
        val uncontactedNode = TestDataFactory.createTestNode(num = 7, lastHeard = 0)
        val nodeZero = TestDataFactory.createTestNode(num = 0, lastHeard = 900)

        nodeRepository.setNodes(
            listOf(node1, node2, node3, node4, node5, ourNode, ignoredNode, uncontactedNode, nodeZero),
        )

        val result = createWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        // Top 4 active non-ignored nodes: 1, 2, 3, 4
        val sentDests = commandSender.tracerouteRequests.map { it.second }
        assertEquals(listOf(1, 2, 3, 4), sentDests)
    }

    @Test
    fun `skips recently traced candidates and takes remaining untraced nodes`() = runTest {
        val node1 = TestDataFactory.createTestNode(num = 1, lastHeard = 500)
        val node2 = TestDataFactory.createTestNode(num = 2, lastHeard = 400)
        val node3 = TestDataFactory.createTestNode(num = 3, lastHeard = 300)

        nodeRepository.setNodes(listOf(node1, node2, node3))

        // Mark node1 as recently traced
        TopologyDiscoveryWorker.recordTraced(1, org.meshtastic.core.common.util.nowMillis)

        val result = createWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val sentDests = commandSender.tracerouteRequests.map { it.second }
        assertEquals(listOf(2, 3), sentDests)
    }

    @Test
    fun `when all candidates were recently traced, succeeds with no outbound packets`() = runTest {
        val node1 = TestDataFactory.createTestNode(num = 1, lastHeard = 500)
        nodeRepository.setNodes(listOf(node1))

        TopologyDiscoveryWorker.recordTraced(1, org.meshtastic.core.common.util.nowMillis)

        val result = createWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(commandSender.tracerouteRequests.isEmpty())
    }
}
