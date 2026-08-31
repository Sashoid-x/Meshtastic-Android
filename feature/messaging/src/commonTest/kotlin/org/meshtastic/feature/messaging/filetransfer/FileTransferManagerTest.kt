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
package org.meshtastic.feature.messaging.filetransfer

import dev.mokkery.MockMode
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.repository.CommandSender
import org.meshtastic.core.repository.ServiceRepository
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileTransferManagerTest {

    private class FakeAppScope(scope: CoroutineScope) :
        ApplicationCoroutineScope,
        CoroutineScope by scope

    @Test
    fun startSending_fileExceedingMaxLimitFailsImmediately() = runTest(UnconfinedTestDispatcher()) {
        val testScope = TestScope(UnconfinedTestDispatcher())
        val fakeScope = FakeAppScope(testScope)
        val commandSender = mock<CommandSender>(MockMode.autofill)
        val serviceRepository = mock<ServiceRepository>(MockMode.autofill)
        every { serviceRepository.meshPacketFlow } returns MutableSharedFlow<MeshPacket>()

        val manager =
            FileTransferManager(
                applicationScope = fakeScope,
                commandSender = commandSender,
                serviceRepository = serviceRepository,
            )

        val oversizedData = ByteArray(MftProtocol.MAX_FILE_SIZE + 1)
        manager.startSending("!12345678", "large.bin", oversizedData)

        val state = manager.outgoingState.value
        assertTrue(state is TransferState.Failed)
        assertEquals("File too large", state.reason)
    }

    @Test
    fun incomingTransfer_reassemblesChunksAndVerifiesCrc() = runTest(UnconfinedTestDispatcher()) {
        val testScope = TestScope(UnconfinedTestDispatcher())
        val fakeScope = FakeAppScope(testScope)
        val commandSender = mock<CommandSender>(MockMode.autofill)
        val serviceRepository = mock<ServiceRepository>(MockMode.autofill)
        every { serviceRepository.meshPacketFlow } returns MutableSharedFlow<MeshPacket>()

        val sentPackets = mutableListOf<DataPacket>()
        everySuspend { commandSender.sendData(any()) } calls
            { (packet: DataPacket) ->
                sentPackets.add(packet)
                Unit
            }

        var savedFileName: String? = null
        var savedData: ByteArray? = null

        val manager =
            FileTransferManager(
                applicationScope = fakeScope,
                commandSender = commandSender,
                serviceRepository = serviceRepository,
                onFileSaved = { name, bytes ->
                    savedFileName = name
                    savedData = bytes
                    "/path/to/$name"
                },
            )

        val originalData = "Hello, Meshtastic direct file transfer!".encodeToByteArray()
        val crc = computeCrc32(originalData)
        val transferId = 101

        // 1. Send START packet
        val startPacket =
            MftStart(
                transferId = transferId,
                fileSize = originalData.size.toLong(),
                totalChunks = 1,
                crc32 = crc,
                fileName = "hello.txt",
            )
        val consumedStart = manager.onMftPacketReceived("!aabbccdd", startPacket.encode())
        assertTrue(consumedStart)
        assertTrue(manager.incomingState.value is TransferState.Receiving)

        // 2. Send DATA chunk
        val dataPacket = MftData(transferId = transferId, chunkIndex = 0, data = originalData)
        val consumedData = manager.onMftPacketReceived("!aabbccdd", dataPacket.encode())
        assertTrue(consumedData)

        // Transfer should be completed and file saved
        assertEquals("hello.txt", savedFileName)
        assertEquals(originalData.toList(), savedData?.toList())
        assertTrue(manager.incomingState.value is TransferState.Completed)

        // Verified ACK was sent back with hopLimit=0 and isDirectOnly=true
        assertTrue(sentPackets.isNotEmpty())
        val lastAck = sentPackets.last()
        assertTrue(lastAck.isDirectOnly)
        assertEquals(0, lastAck.hopLimit)
        assertEquals(PortNum.PRIVATE_APP.value, lastAck.dataType)
    }

    @Test
    fun incomingTransfer_rejectsCrcMismatch() = runTest(UnconfinedTestDispatcher()) {
        val testScope = TestScope(UnconfinedTestDispatcher())
        val fakeScope = FakeAppScope(testScope)
        val commandSender = mock<CommandSender>(MockMode.autofill)
        val serviceRepository = mock<ServiceRepository>(MockMode.autofill)
        every { serviceRepository.meshPacketFlow } returns MutableSharedFlow<MeshPacket>()

        var saved = false
        val manager =
            FileTransferManager(
                applicationScope = fakeScope,
                commandSender = commandSender,
                serviceRepository = serviceRepository,
                onFileSaved = { _, _ ->
                    saved = true
                    null
                },
            )

        val originalData = "Good data".encodeToByteArray()
        val corruptCrc = 0xDEADBEEFL
        val transferId = 202

        val startPacket =
            MftStart(
                transferId = transferId,
                fileSize = originalData.size.toLong(),
                totalChunks = 1,
                crc32 = corruptCrc,
                fileName = "corrupt.txt",
            )
        manager.onMftPacketReceived("!aabbccdd", startPacket.encode())

        val dataPacket = MftData(transferId = transferId, chunkIndex = 0, data = originalData)
        manager.onMftPacketReceived("!aabbccdd", dataPacket.encode())

        assertEquals(false, saved)
        assertTrue(manager.incomingState.value is TransferState.Failed)
    }
}
