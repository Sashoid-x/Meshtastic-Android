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
package org.meshtastic.core.data.repository

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.meshtastic.core.data.datasource.NodeInfoReadDataSource
import org.meshtastic.core.data.datasource.NodeInfoWriteDataSource
import org.meshtastic.core.database.entity.MyNodeEntity
import org.meshtastic.core.database.entity.NodeEntity
import org.meshtastic.core.database.entity.NodeWithRelations
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.model.CustomNodeName
import org.meshtastic.core.model.MeshLog
import org.meshtastic.core.repository.UiPrefs
import org.meshtastic.core.testing.FakeAppPreferences
import org.meshtastic.core.testing.FakeLocalStatsDataSource
import org.meshtastic.proto.User
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

abstract class CommonNodeRepositoryTest {

    protected lateinit var lifecycleOwner: LifecycleOwner
    protected lateinit var readDataSource: NodeInfoReadDataSource
    protected lateinit var writeDataSource: NodeInfoWriteDataSource
    protected lateinit var localStatsDataSource: FakeLocalStatsDataSource
    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchers = CoroutineDispatchers(main = testDispatcher, io = testDispatcher, default = testDispatcher)

    private val myNodeInfoFlow = MutableStateFlow<MyNodeEntity?>(null)

    protected lateinit var fakeUiPrefs: UiPrefs
    protected lateinit var repository: NodeRepositoryImpl

    @BeforeTest
    fun setupRepo() {
        Dispatchers.setMain(testDispatcher)
        lifecycleOwner =
            object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this)
            }
        (lifecycleOwner.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        readDataSource = mock(MockMode.autofill)
        writeDataSource = mock(MockMode.autofill)
        localStatsDataSource = FakeLocalStatsDataSource()
        fakeUiPrefs = FakeAppPreferences().ui

        every { readDataSource.myNodeInfoFlow() } returns myNodeInfoFlow
        every { readDataSource.nodeDBbyNumFlow() } returns MutableStateFlow<Map<Int, NodeWithRelations>>(emptyMap())

        repository =
            NodeRepositoryImpl(
                lifecycleOwner.lifecycle,
                readDataSource,
                writeDataSource,
                dispatchers,
                localStatsDataSource,
                fakeUiPrefs,
            )
    }

    @AfterTest
    fun tearDown() {
        // Essential to stop background jobs in NodeRepositoryImpl
        (lifecycleOwner.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        Dispatchers.resetMain()
    }

    private fun createMyNodeEntity(nodeNum: Int) = MyNodeEntity(
        myNodeNum = nodeNum,
        model = "model",
        firmwareVersion = "1.0",
        couldUpdate = false,
        shouldUpdate = false,
        currentPacketId = 0L,
        messageTimeoutMsec = 0,
        minAppVersion = 0,
        maxChannels = 0,
        hasWifi = false,
    )

    @Test
    fun `effectiveLogNodeId maps local node number to NODE_NUM_LOCAL`() = runTest(testDispatcher) {
        val myNodeNum = 12345
        myNodeInfoFlow.value = createMyNodeEntity(myNodeNum)

        val result = repository.effectiveLogNodeId(myNodeNum).filter { it == MeshLog.NODE_NUM_LOCAL }.first()

        assertEquals(MeshLog.NODE_NUM_LOCAL, result)
    }

    @Test
    fun `effectiveLogNodeId preserves remote node numbers`() = runTest(testDispatcher) {
        val myNodeNum = 12345
        val remoteNodeNum = 67890
        myNodeInfoFlow.value = createMyNodeEntity(myNodeNum)

        val result = repository.effectiveLogNodeId(remoteNodeNum).first()

        assertEquals(remoteNodeNum, result)
    }

    @Test
    fun `custom node names override node user display names when enabled`() = runTest(testDispatcher) {
        val baseEntity =
            NodeWithRelations(
                node =
                NodeEntity(num = 42, user = User(id = "42", long_name = "Original Long", short_name = "ORIG")),
                metadata = null,
            )
        val nodeDbFlow = MutableStateFlow(mapOf(42 to baseEntity))
        every { readDataSource.nodeDBbyNumFlow() } returns nodeDbFlow

        val testRepo =
            NodeRepositoryImpl(
                lifecycleOwner.lifecycle,
                readDataSource,
                writeDataSource,
                dispatchers,
                localStatsDataSource,
                fakeUiPrefs,
            )

        assertEquals("Original Long", testRepo.nodeDBbyNum.value[42]?.user?.long_name)

        fakeUiPrefs.setCustomNodeName(
            42,
            CustomNodeName(shortName = "NEW", longName = "New Custom Name", enabled = true),
        )

        val updated = testRepo.nodeDBbyNum.value[42]
        assertEquals("New Custom Name", updated?.user?.long_name)
        assertEquals("NEW", updated?.user?.short_name)
        assertEquals("Original Long", updated?.originalUser?.long_name)

        // When disabled, falls back to original name
        fakeUiPrefs.setCustomNodeName(
            42,
            CustomNodeName(shortName = "NEW", longName = "New Custom Name", enabled = false),
        )
        val disabled = testRepo.nodeDBbyNum.value[42]
        assertEquals("Original Long", disabled?.user?.long_name)
        assertEquals("ORIG", disabled?.user?.short_name)
    }
}
