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
package org.meshtastic.core.meshbridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.meshtastic.core.testing.FakeCommandSender
import org.meshtastic.core.testing.FakeMeshBridgePrefs
import org.meshtastic.core.testing.FakeServiceRepository
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MeshBridgeIntegrationTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private class FakeMeshBridgeServerManager : MeshBridgeServerManager {
        override val isSupported: Boolean = true
        override val isRunning = MutableStateFlow(false)
        override val isStarting = MutableStateFlow(false)
        override val connectionCount = MutableStateFlow(0)
        override val hasError = MutableStateFlow(false)

        val inbound = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
        override val inboundPayloads: SharedFlow<ByteArray> = inbound

        data class BroadcastRecord(val from: Int, val to: Int, val payload: ByteArray)

        val broadcasts = mutableListOf<BroadcastRecord>()

        override fun start(scope: CoroutineScope) {
            isRunning.value = true
        }

        override fun stop() {
            isRunning.value = false
        }

        override suspend fun broadcastToClients(from: Int, to: Int, payload: ByteArray) {
            broadcasts += BroadcastRecord(from, to, payload)
        }

        override var onClientConnected: (() -> Unit)? = null
        val rawFrames = mutableListOf<ByteArray>()

        override suspend fun sendRawFrameToClients(frame: ByteArray) {
            rawFrames += frame
        }
    }

    @Test
    fun `inbound payload from client is sent to mesh with wantAck false`() = testScope.runTest {
        val commandSender = FakeCommandSender()
        val serviceRepository = FakeServiceRepository()
        val serverManager = FakeMeshBridgeServerManager()
        val meshBridgePrefs = FakeMeshBridgePrefs()

        meshBridgePrefs.setMeshBridgeChannel(2)

        val integration =
            MeshBridgeIntegration(
                meshBridgeServerManager = serverManager,
                commandSender = commandSender,
                serviceRepository = serviceRepository,
                meshBridgePrefs = meshBridgePrefs,
            )

        integration.start(this)

        val payload = "game_move".encodeToByteArray()
        serverManager.inbound.emit(payload)

        assertEquals(1, commandSender.sentPackets.size)
        val packet = commandSender.sentPackets.first()
        assertEquals(PortNum.SIMULATOR_APP.value, packet.dataType)
        assertFalse(packet.wantAck)
        assertEquals(2, packet.channel)
        assertEquals(payload.toByteString(), packet.bytes)

        integration.stop()
    }

    @Test
    fun `incoming SIMULATOR_APP packet from mesh is broadcast to clients`() = testScope.runTest {
        val commandSender = FakeCommandSender()
        val serviceRepository = FakeServiceRepository()
        val serverManager = FakeMeshBridgeServerManager()
        val meshBridgePrefs = FakeMeshBridgePrefs()

        val integration =
            MeshBridgeIntegration(
                meshBridgeServerManager = serverManager,
                commandSender = commandSender,
                serviceRepository = serviceRepository,
                meshBridgePrefs = meshBridgePrefs,
            )

        integration.start(this)

        val payloadBytes = "server_update".encodeToByteArray()
        val simulatorPacket =
            MeshPacket.Builder()
                .also { wb ->
                    wb.from = 0x12345678
                    wb.to = 0x76543210
                    wb.decoded =
                        Data.Builder()
                            .also { db ->
                                db.portnum = PortNum.SIMULATOR_APP
                                db.payload = payloadBytes.toByteString()
                            }
                            .build()
                }
                .build()

        serviceRepository.emitMeshPacket(simulatorPacket)

        assertEquals(1, serverManager.broadcasts.size)
        val broadcast = serverManager.broadcasts.first()
        assertEquals(0x12345678, broadcast.from)
        assertEquals(0x76543210, broadcast.to)
        assertTrue(broadcast.payload.contentEquals(payloadBytes))

        integration.stop()
    }

    @Test
    fun `incoming non-SIMULATOR_APP packet from mesh is ignored`() = testScope.runTest {
        val commandSender = FakeCommandSender()
        val serviceRepository = FakeServiceRepository()
        val serverManager = FakeMeshBridgeServerManager()
        val meshBridgePrefs = FakeMeshBridgePrefs()

        val integration =
            MeshBridgeIntegration(
                meshBridgeServerManager = serverManager,
                commandSender = commandSender,
                serviceRepository = serviceRepository,
                meshBridgePrefs = meshBridgePrefs,
            )

        integration.start(this)

        val textPacket =
            MeshPacket.Builder()
                .also { wb ->
                    wb.from = 0x12345678
                    wb.to = 0x76543210
                    wb.decoded =
                        Data.Builder()
                            .also { db ->
                                db.portnum = PortNum.TEXT_MESSAGE_APP
                                db.payload = "hello".encodeToByteArray().toByteString()
                            }
                            .build()
                }
                .build()

        serviceRepository.emitMeshPacket(textPacket)

        assertTrue(serverManager.broadcasts.isEmpty())

        integration.stop()
    }

    @Test
    fun `outbound presence packet is sent as broadcast with wantAck false`() = testScope.runTest {
        val commandSender = FakeCommandSender()
        val serviceRepository = FakeServiceRepository()
        val serverManager = FakeMeshBridgeServerManager()
        val meshBridgePrefs = FakeMeshBridgePrefs()

        val integration =
            MeshBridgeIntegration(
                meshBridgeServerManager = serverManager,
                commandSender = commandSender,
                serviceRepository = serviceRepository,
                meshBridgePrefs = meshBridgePrefs,
            )

        integration.start(this)

        val buffer = okio.Buffer()
        buffer.writeIntLe(0x11112222) // from
        buffer.writeIntLe(0) // to = broadcast
        buffer.writeByte(MeshBridgeDefaults.MAGIC_PRESENCE.toInt())
        buffer.writeUtf8("nodeName")
        val payload = buffer.readByteArray()

        serverManager.inbound.emit(payload)

        assertEquals(1, commandSender.sentPackets.size)
        val packet = commandSender.sentPackets.first()
        assertEquals(org.meshtastic.core.model.NodeAddress.ID_BROADCAST, packet.to)
        assertEquals(PortNum.SIMULATOR_APP.value, packet.dataType)
        assertFalse(packet.wantAck)

        integration.stop()
    }

    @Test
    fun `outbound envelope with unknown peer falls back to broadcast`() = testScope.runTest {
        val commandSender = FakeCommandSender()
        val serviceRepository = FakeServiceRepository()
        val serverManager = FakeMeshBridgeServerManager()
        val meshBridgePrefs = FakeMeshBridgePrefs()

        val integration =
            MeshBridgeIntegration(
                meshBridgeServerManager = serverManager,
                commandSender = commandSender,
                serviceRepository = serviceRepository,
                meshBridgePrefs = meshBridgePrefs,
            )

        integration.start(this)

        val buffer = okio.Buffer()
        buffer.writeIntLe(0x11112222) // from
        buffer.writeIntLe(0x33334444) // to = unknown peer
        buffer.writeByte(MeshBridgeDefaults.MAGIC_ENVELOPE.toInt())
        buffer.writeUtf8("invite")
        val payload = buffer.readByteArray()

        serverManager.inbound.emit(payload)

        assertEquals(1, commandSender.sentPackets.size)
        val packet = commandSender.sentPackets.first()
        assertEquals(org.meshtastic.core.model.NodeAddress.ID_BROADCAST, packet.to)
        assertEquals(PortNum.SIMULATOR_APP.value, packet.dataType)
        assertFalse(packet.wantAck)

        integration.stop()
    }

    @Test
    fun `inbound packet learns peer and subsequent outbound envelope routes to DM`() = testScope.runTest {
        val commandSender = FakeCommandSender()
        val serviceRepository = FakeServiceRepository()
        val serverManager = FakeMeshBridgeServerManager()
        val meshBridgePrefs = FakeMeshBridgePrefs()

        val integration =
            MeshBridgeIntegration(
                meshBridgeServerManager = serverManager,
                commandSender = commandSender,
                serviceRepository = serviceRepository,
                meshBridgePrefs = meshBridgePrefs,
            )

        integration.start(this)

        val peerClientId = 0x55667788
        val peerLoraNode = 0x1234ABCD

        // 1. Peer sends presence over mesh
        val rxBuffer = okio.Buffer()
        rxBuffer.writeIntLe(peerClientId)
        rxBuffer.writeIntLe(0)
        rxBuffer.writeByte(MeshBridgeDefaults.MAGIC_PRESENCE.toInt())
        val rxPayload = rxBuffer.readByteArray()

        val meshPacket =
            MeshPacket.Builder()
                .also { wb ->
                    wb.from = peerLoraNode
                    wb.to = org.meshtastic.core.model.NodeAddress.NODENUM_BROADCAST
                    wb.decoded =
                        Data.Builder()
                            .also { db ->
                                db.portnum = PortNum.SIMULATOR_APP
                                db.payload = rxPayload.toByteString()
                            }
                            .build()
                }
                .build()

        serviceRepository.emitMeshPacket(meshPacket)

        // Verify map learned peer
        assertEquals(peerLoraNode, integration.peerMap.get(peerClientId))

        // 2. Client sends game envelope to peerClientId
        val txBuffer = okio.Buffer()
        txBuffer.writeIntLe(0x11112222) // clientFrom
        txBuffer.writeIntLe(peerClientId) // clientTo
        txBuffer.writeByte(MeshBridgeDefaults.MAGIC_ENVELOPE.toInt())
        txBuffer.writeUtf8("move")
        val txPayload = txBuffer.readByteArray()

        serverManager.inbound.emit(txPayload)

        assertEquals(1, commandSender.sentPackets.size)
        val packet = commandSender.sentPackets.first()
        val expectedAddress = org.meshtastic.core.model.NodeAddress.numToDefaultId(peerLoraNode)
        assertEquals(expectedAddress, packet.to)
        assertEquals(PortNum.SIMULATOR_APP.value, packet.dataType)
        assertFalse(packet.wantAck)

        integration.stop()
    }

    @Test
    fun `when client connects and node info is known HELLO frame is sent to client`() = testScope.runTest {
        val commandSender = FakeCommandSender()
        val serviceRepository = FakeServiceRepository()
        val serverManager = FakeMeshBridgeServerManager()
        val meshBridgePrefs = FakeMeshBridgePrefs()
        val nodeRepository = org.meshtastic.core.testing.FakeNodeRepository()

        val myNodeNum = 0x12345678
        val longName = "Alice Node"
        nodeRepository.setMyNodeInfo(
            org.meshtastic.core.testing.TestDataFactory.createMyNodeInfo(myNodeNum = myNodeNum),
        )
        nodeRepository.setOurNode(
            org.meshtastic.core.testing.TestDataFactory.createTestNode(num = myNodeNum, longName = longName),
        )

        val integration =
            MeshBridgeIntegration(
                meshBridgeServerManager = serverManager,
                commandSender = commandSender,
                serviceRepository = serviceRepository,
                meshBridgePrefs = meshBridgePrefs,
                nodeRepository = nodeRepository,
            )

        integration.start(this)

        // Simulate client connection
        serverManager.connectionCount.value = 1
        serverManager.onClientConnected?.invoke()

        assertEquals(1, serverManager.rawFrames.size)
        val frame = serverManager.rawFrames.first()

        val buffer = okio.Buffer().write(frame)
        val len = buffer.readIntLe()
        assertEquals(16 + 7 + longName.encodeToByteArray().size, len)
        assertEquals(0, buffer.readIntLe()) // loraFrom
        assertEquals(0, buffer.readIntLe()) // loraTo
        assertEquals(0, buffer.readIntLe()) // from
        assertEquals(0, buffer.readIntLe()) // to
        assertEquals(MeshBridgeDefaults.MAGIC_HELLO, buffer.readByte())
        assertEquals(MeshBridgeDefaults.HELLO_VERSION, buffer.readByte())
        assertEquals(myNodeNum, buffer.readIntLe())
        val nameLen = buffer.readByte().toInt() and 0xFF
        assertEquals(longName.encodeToByteArray().size, nameLen)
        assertEquals(longName, buffer.readUtf8(nameLen.toLong()))

        integration.stop()
    }

    @Test
    fun `when longName changes updated HELLO frame is sent to connected client`() = testScope.runTest {
        val commandSender = FakeCommandSender()
        val serviceRepository = FakeServiceRepository()
        val serverManager = FakeMeshBridgeServerManager()
        val meshBridgePrefs = FakeMeshBridgePrefs()
        val nodeRepository = org.meshtastic.core.testing.FakeNodeRepository()

        val myNodeNum = 0x12345678
        nodeRepository.setMyNodeInfo(
            org.meshtastic.core.testing.TestDataFactory.createMyNodeInfo(myNodeNum = myNodeNum),
        )
        nodeRepository.setOurNode(
            org.meshtastic.core.testing.TestDataFactory.createTestNode(num = myNodeNum, longName = "Alice"),
        )

        val integration =
            MeshBridgeIntegration(
                meshBridgeServerManager = serverManager,
                commandSender = commandSender,
                serviceRepository = serviceRepository,
                meshBridgePrefs = meshBridgePrefs,
                nodeRepository = nodeRepository,
            )

        integration.start(this)

        // Client is connected
        serverManager.connectionCount.value = 1
        serverManager.onClientConnected?.invoke()
        assertEquals(1, serverManager.rawFrames.size)

        // Change longName
        nodeRepository.setOurNode(
            org.meshtastic.core.testing.TestDataFactory.createTestNode(num = myNodeNum, longName = "Bob"),
        )
        testScheduler.advanceUntilIdle()

        assertEquals(2, serverManager.rawFrames.size)
        val secondFrame = serverManager.rawFrames[1]
        val buffer = okio.Buffer().write(secondFrame)
        buffer.skip(20) // skip len(4) + loraFrom(4) + loraTo(4) + from(4) + to(4)
        assertEquals(MeshBridgeDefaults.MAGIC_HELLO, buffer.readByte())
        assertEquals(MeshBridgeDefaults.HELLO_VERSION, buffer.readByte())
        assertEquals(myNodeNum, buffer.readIntLe())
        val nameLen = buffer.readByte().toInt() and 0xFF
        assertEquals("Bob".encodeToByteArray().size, nameLen)
        assertEquals("Bob", buffer.readUtf8(nameLen.toLong()))

        integration.stop()
    }

    @Test
    fun `when node info is not available no HELLO frame is sent`() = testScope.runTest {
        val commandSender = FakeCommandSender()
        val serviceRepository = FakeServiceRepository()
        val serverManager = FakeMeshBridgeServerManager()
        val meshBridgePrefs = FakeMeshBridgePrefs()
        val nodeRepository = org.meshtastic.core.testing.FakeNodeRepository()

        val integration =
            MeshBridgeIntegration(
                meshBridgeServerManager = serverManager,
                commandSender = commandSender,
                serviceRepository = serviceRepository,
                meshBridgePrefs = meshBridgePrefs,
                nodeRepository = nodeRepository,
            )

        integration.start(this)

        serverManager.connectionCount.value = 1
        serverManager.onClientConnected?.invoke()

        assertTrue(serverManager.rawFrames.isEmpty())

        integration.stop()
    }

    @Test
    fun `when node info becomes available after client connected HELLO frame is sent`() = testScope.runTest {
        val commandSender = FakeCommandSender()
        val serviceRepository = FakeServiceRepository()
        val serverManager = FakeMeshBridgeServerManager()
        val meshBridgePrefs = FakeMeshBridgePrefs()
        val nodeRepository = org.meshtastic.core.testing.FakeNodeRepository()

        val integration =
            MeshBridgeIntegration(
                meshBridgeServerManager = serverManager,
                commandSender = commandSender,
                serviceRepository = serviceRepository,
                meshBridgePrefs = meshBridgePrefs,
                nodeRepository = nodeRepository,
            )

        integration.start(this)

        // Client connects while node info is not yet available
        serverManager.connectionCount.value = 1
        serverManager.onClientConnected?.invoke()
        assertTrue(serverManager.rawFrames.isEmpty())

        // Radio connects and node info becomes available
        val myNodeNum = 0x19887766
        nodeRepository.setMyNodeInfo(
            org.meshtastic.core.testing.TestDataFactory.createMyNodeInfo(myNodeNum = myNodeNum),
        )
        nodeRepository.setOurNode(
            org.meshtastic.core.testing.TestDataFactory.createTestNode(num = myNodeNum, longName = "Late Node"),
        )
        testScheduler.advanceUntilIdle()

        assertEquals(2, serverManager.rawFrames.size)
        val frame = serverManager.rawFrames.last()
        val buffer = okio.Buffer().write(frame)
        buffer.skip(20)
        assertEquals(MeshBridgeDefaults.MAGIC_HELLO, buffer.readByte())
        assertEquals(MeshBridgeDefaults.HELLO_VERSION, buffer.readByte())
        assertEquals(myNodeNum, buffer.readIntLe())
        val nameLen = buffer.readByte().toInt() and 0xFF
        assertEquals("Late Node", buffer.readUtf8(nameLen.toLong()))

        integration.stop()
    }

    @Test
    fun `when longName is blank shortName is used in HELLO frame`() = testScope.runTest {
        val commandSender = FakeCommandSender()
        val serviceRepository = FakeServiceRepository()
        val serverManager = FakeMeshBridgeServerManager()
        val meshBridgePrefs = FakeMeshBridgePrefs()
        val nodeRepository = org.meshtastic.core.testing.FakeNodeRepository()

        val myNodeNum = 0x55443322
        nodeRepository.setMyNodeInfo(
            org.meshtastic.core.testing.TestDataFactory.createMyNodeInfo(myNodeNum = myNodeNum),
        )
        nodeRepository.setOurNode(
            org.meshtastic.core.testing.TestDataFactory.createTestNode(
                num = myNodeNum,
                longName = "",
                shortName = "SN",
            ),
        )

        val integration =
            MeshBridgeIntegration(
                meshBridgeServerManager = serverManager,
                commandSender = commandSender,
                serviceRepository = serviceRepository,
                meshBridgePrefs = meshBridgePrefs,
                nodeRepository = nodeRepository,
            )

        integration.start(this)

        serverManager.connectionCount.value = 1
        serverManager.onClientConnected?.invoke()

        assertEquals(1, serverManager.rawFrames.size)
        val frame = serverManager.rawFrames.first()
        val buffer = okio.Buffer().write(frame)
        buffer.skip(20)
        assertEquals(MeshBridgeDefaults.MAGIC_HELLO, buffer.readByte())
        assertEquals(MeshBridgeDefaults.HELLO_VERSION, buffer.readByte())
        assertEquals(myNodeNum, buffer.readIntLe())
        val nameLen = buffer.readByte().toInt() and 0xFF
        assertEquals(2, nameLen)
        assertEquals("SN", buffer.readUtf8(nameLen.toLong()))

        integration.stop()
    }
}
