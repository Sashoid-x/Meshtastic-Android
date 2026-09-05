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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.repository.CommandSender
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.repository.ServiceRepository
import org.meshtastic.proto.LocalConfig
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileTransferManagerTest {

    private class FakeAppScope(scope: CoroutineScope) :
        ApplicationCoroutineScope,
        CoroutineScope by scope

    private fun createMocks(): Triple<CommandSender, ServiceRepository, RadioConfigRepository> {
        val commandSender = mock<CommandSender>(MockMode.autofill)
        val serviceRepository = mock<ServiceRepository>(MockMode.autofill)
        val radioConfigRepository = mock<RadioConfigRepository>(MockMode.autofill)
        every { serviceRepository.meshPacketFlow } returns MutableSharedFlow<MeshPacket>()
        every { radioConfigRepository.localConfigFlow } returns MutableStateFlow(LocalConfig())
        return Triple(commandSender, serviceRepository, radioConfigRepository)
    }

    @Test
    fun startSending_fileExceedingMaxLimitFailsImmediately() = runTest(UnconfinedTestDispatcher()) {
        val testScope = TestScope(UnconfinedTestDispatcher())
        val fakeScope = FakeAppScope(testScope)
        val (commandSender, serviceRepository, radioConfigRepository) = createMocks()

        val manager =
            FileTransferManager(
                applicationScope = fakeScope,
                commandSender = commandSender,
                serviceRepository = serviceRepository,
                radioConfigRepository = radioConfigRepository,
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
        val (commandSender, serviceRepository, radioConfigRepository) = createMocks()

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
                radioConfigRepository = radioConfigRepository,
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
                isGzip = false,
            )
        val consumedStart = manager.onMftPacketReceived("!aabbccdd", startPacket.encode())
        assertTrue(consumedStart)
        assertTrue(manager.incomingState.value is TransferState.Receiving)

        // 2. Send DATA chunk
        val dataPacket = MftData(transferId = transferId, chunkIndex = 0, data = originalData)
        val consumedData = manager.onMftPacketReceived("!aabbccdd", dataPacket.encode())
        assertTrue(consumedData)

        // 3. Send PASS_END marker
        val passEnd = MftPassEnd(transferId = transferId, passNumber = 1)
        manager.onMftPacketReceived("!aabbccdd", passEnd.encode())

        // Transfer should be completed and file saved
        assertEquals("hello.txt", savedFileName)
        assertEquals(originalData.toList(), savedData?.toList())
        assertTrue(manager.incomingState.value is TransferState.Completed)

        // Verified Complete packet was sent back with hopLimit=0 and isDirectOnly=true
        assertTrue(sentPackets.isNotEmpty())
        val lastPacket = sentPackets.last()
        assertTrue(lastPacket.isDirectOnly)
        assertEquals(0, lastPacket.hopLimit)
        assertEquals(PortNum.PRIVATE_APP.value, lastPacket.dataType)
    }

    @Test
    fun incomingTransfer_decompressGzipFile() = runTest(UnconfinedTestDispatcher()) {
        val testScope = TestScope(UnconfinedTestDispatcher())
        val fakeScope = FakeAppScope(testScope)
        val (commandSender, serviceRepository, radioConfigRepository) = createMocks()

        var savedData: ByteArray? = null
        val manager =
            FileTransferManager(
                applicationScope = fakeScope,
                commandSender = commandSender,
                serviceRepository = serviceRepository,
                radioConfigRepository = radioConfigRepository,
                onFileSaved = { _, bytes ->
                    savedData = bytes
                    "/path/to/test.txt"
                },
            )

        val text = "Repeated text for high compression ratio. ".repeat(15)
        val originalData = text.encodeToByteArray()
        val (compressed, isCompressed) = MftCompression.compress(originalData)
        assertTrue(isCompressed)

        val originalCrc = computeCrc32(originalData)
        val transferId = 303

        val startPacket =
            MftStart(
                transferId = transferId,
                fileSize = originalData.size.toLong(),
                totalChunks = 1,
                crc32 = originalCrc,
                fileName = "test.txt",
                isGzip = true,
            )
        manager.onMftPacketReceived("!aabbccdd", startPacket.encode())

        val dataPacket = MftData(transferId = transferId, chunkIndex = 0, data = compressed)
        manager.onMftPacketReceived("!aabbccdd", dataPacket.encode())

        val passEnd = MftPassEnd(transferId = transferId, passNumber = 1)
        manager.onMftPacketReceived("!aabbccdd", passEnd.encode())

        assertEquals(originalData.toList(), savedData?.toList())
        assertTrue(manager.incomingState.value is TransferState.Completed)
    }

    @Test
    fun cancelIncoming_stopsTransferAndSendsCancelPacket() = runTest(UnconfinedTestDispatcher()) {
        val testScope = TestScope(UnconfinedTestDispatcher())
        val fakeScope = FakeAppScope(testScope)
        val (commandSender, serviceRepository, radioConfigRepository) = createMocks()

        val sentPackets = mutableListOf<DataPacket>()
        everySuspend { commandSender.sendData(any()) } calls
            { (packet: DataPacket) ->
                sentPackets.add(packet)
                Unit
            }

        val manager =
            FileTransferManager(
                applicationScope = fakeScope,
                commandSender = commandSender,
                serviceRepository = serviceRepository,
                radioConfigRepository = radioConfigRepository,
            )

        val startPacket =
            MftStart(
                transferId = 404,
                fileSize = 1000L,
                totalChunks = 5,
                crc32 = 0x1234L,
                fileName = "cancel_test.txt",
                isGzip = false,
            )
        manager.onMftPacketReceived("!receiver123", startPacket.encode())
        assertTrue(manager.incomingState.value is TransferState.Receiving)

        // Cancel incoming
        manager.cancelIncoming()

        // Incoming state should transition out of Receiving
        assertFalse(manager.incomingState.value is TransferState.Receiving)
        assertTrue(manager.incomingState.value is TransferState.Failed)

        // Cancel packet should be sent to sender
        val cancelPacket =
            sentPackets.lastOrNull { MftProtocol.packetType(it.bytes!!.toByteArray()) == MftProtocol.TYPE_CANCEL }
        assertTrue(cancelPacket != null)

        // Subsequent in-flight chunks must be silently dropped, NOT triggering new cancel packets
        val packetCountBefore = sentPackets.size
        manager.onMftPacketReceived("!receiver123", MftData(404, 1, byteArrayOf(1, 2, 3)).encode())
        assertEquals(
            packetCountBefore,
            sentPackets.size,
            "Subsequent in-flight chunks must not trigger new cancel packets",
        )
    }

    @Test
    fun incomingTransfer_rejectsCrcMismatch() = runTest(UnconfinedTestDispatcher()) {
        val testScope = TestScope(UnconfinedTestDispatcher())
        val fakeScope = FakeAppScope(testScope)
        val (commandSender, serviceRepository, radioConfigRepository) = createMocks()

        var saved = false
        val manager =
            FileTransferManager(
                applicationScope = fakeScope,
                commandSender = commandSender,
                serviceRepository = serviceRepository,
                radioConfigRepository = radioConfigRepository,
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
                isGzip = false,
            )
        manager.onMftPacketReceived("!aabbccdd", startPacket.encode())

        val dataPacket = MftData(transferId = transferId, chunkIndex = 0, data = originalData)
        manager.onMftPacketReceived("!aabbccdd", dataPacket.encode())

        val passEnd = MftPassEnd(transferId = transferId, passNumber = 1)
        manager.onMftPacketReceived("!aabbccdd", passEnd.encode())

        assertEquals(false, saved)
        assertTrue(manager.incomingState.value is TransferState.Failed)
    }

    @Test
    fun incomingTransfer_requestsMissingChunksAndAssemblesWhenRepaired() = runTest(UnconfinedTestDispatcher()) {
        val testScope = TestScope(UnconfinedTestDispatcher())
        val fakeScope = FakeAppScope(testScope)
        val (commandSender, serviceRepository, radioConfigRepository) = createMocks()

        val sentPackets = mutableListOf<DataPacket>()
        everySuspend { commandSender.sendData(any()) } calls
            { (packet: DataPacket) ->
                sentPackets.add(packet)
                Unit
            }

        var savedData: ByteArray? = null
        val manager =
            FileTransferManager(
                applicationScope = fakeScope,
                commandSender = commandSender,
                serviceRepository = serviceRepository,
                radioConfigRepository = radioConfigRepository,
                onFileSaved = { _, bytes ->
                    savedData = bytes
                    "/path/to/repaired.bin"
                },
            )

        val chunk0 = byteArrayOf(10, 20, 30)
        val chunk1 = byteArrayOf(40, 50, 60)
        val chunk2 = byteArrayOf(70, 80, 90)
        val fullData = chunk0 + chunk1 + chunk2
        val transferId = 505
        val fullCrc = computeCrc32(fullData)

        // 1. Send START for a 3-chunk file
        val startPacket =
            MftStart(
                transferId = transferId,
                fileSize = fullData.size.toLong(),
                totalChunks = 3,
                crc32 = fullCrc,
                fileName = "repaired.bin",
                isGzip = false,
            )
        manager.onMftPacketReceived("!senderNode", startPacket.encode())

        // 2. Pass 1: Deliver chunk 0 and chunk 2 (chunk 1 was dropped in the air!)
        manager.onMftPacketReceived("!senderNode", MftData(transferId, 0, chunk0).encode())
        manager.onMftPacketReceived("!senderNode", MftData(transferId, 2, chunk2).encode())

        // 3. Sender finishes Pass 1
        manager.onMftPacketReceived("!senderNode", MftPassEnd(transferId, 1).encode())

        // Receiver should have responded with MftMissing containing index [1]!
        val lastPacket = sentPackets.last()
        val missingPacket = checkNotNull(MftMissing.decode(lastPacket.bytes!!.toByteArray()))
        assertEquals(1, missingPacket.passNumber)
        assertEquals(1, missingPacket.totalMissing)
        assertEquals(listOf(1), missingPacket.missingIndices)

        // 4. Pass 2: Sender delivers missing chunk 1 and sends PassEnd(passNumber=2)
        manager.onMftPacketReceived("!senderNode", MftData(transferId, 1, chunk1).encode())
        manager.onMftPacketReceived("!senderNode", MftPassEnd(transferId, 2).encode())

        // File should now be 100% assembled and verified!
        assertEquals(fullData.toList(), savedData?.toList())
        assertTrue(manager.incomingState.value is TransferState.Completed)
    }

    @Test
    fun incomingTransfer_sendsMissingWithCorrectPassNumber() = runTest(UnconfinedTestDispatcher()) {
        val testScope = TestScope(UnconfinedTestDispatcher())
        val fakeScope = FakeAppScope(testScope)
        val (commandSender, serviceRepository, radioConfigRepository) = createMocks()

        val sentPackets = mutableListOf<DataPacket>()
        everySuspend { commandSender.sendData(any()) } calls
            { (packet: DataPacket) ->
                sentPackets.add(packet)
                Unit
            }

        val manager =
            FileTransferManager(
                applicationScope = fakeScope,
                commandSender = commandSender,
                serviceRepository = serviceRepository,
                radioConfigRepository = radioConfigRepository,
            )

        val totalChunks = 5
        val transferId = 888
        val start =
            MftStart(
                transferId = transferId,
                fileSize = 1000L,
                totalChunks = totalChunks,
                crc32 = 0x1122L,
                fileName = "test.bin",
                isGzip = false,
            )
        manager.onMftPacketReceived("!senderNode", start.encode())

        // Receive chunks 0, 1, 3 (chunks 2 and 4 missing)
        manager.onMftPacketReceived("!senderNode", MftData(transferId, 0, byteArrayOf(1)).encode())
        manager.onMftPacketReceived("!senderNode", MftData(transferId, 1, byteArrayOf(2)).encode())
        manager.onMftPacketReceived("!senderNode", MftData(transferId, 3, byteArrayOf(4)).encode())

        // Sender ends Pass 2
        manager.onMftPacketReceived("!senderNode", MftPassEnd(transferId, 2).encode())

        val missingPacket =
            sentPackets.lastOrNull { MftProtocol.packetType(it.bytes!!.toByteArray()) == MftProtocol.TYPE_MISSING }
        assertNotNull(missingPacket)
        val missing = checkNotNull(MftMissing.decode(missingPacket.bytes!!.toByteArray()))
        assertEquals(2, missing.passNumber)
        assertEquals(2, missing.totalMissing)
        assertEquals(listOf(2, 4), missing.missingIndices)
        assertEquals(MeshPacket.Priority.MAX.value, missingPacket.priority)
    }

    @Test
    fun incomingTransfer_updatesStatusMessageDuringTransferAndCompletion() = runTest(UnconfinedTestDispatcher()) {
        val testScope = TestScope(UnconfinedTestDispatcher())
        val fakeScope = FakeAppScope(testScope)
        val (commandSender, serviceRepository, radioConfigRepository) = createMocks()

        val manager =
            FileTransferManager(
                applicationScope = fakeScope,
                commandSender = commandSender,
                serviceRepository = serviceRepository,
                radioConfigRepository = radioConfigRepository,
                onFileSaved = { _, _ -> "/storage/emulated/0/Download/Meshtastic/doc.pdf" },
            )

        val chunk0 = byteArrayOf(1, 2)
        val chunk1 = byteArrayOf(3, 4)
        val fileData = chunk0 + chunk1
        val transferId = 999
        val start =
            MftStart(
                transferId = transferId,
                fileSize = fileData.size.toLong(),
                totalChunks = 2,
                crc32 = computeCrc32(fileData),
                fileName = "doc.pdf",
                isGzip = false,
            )
        manager.onMftPacketReceived("!sender", start.encode())

        val receivingState = manager.incomingState.value as TransferState.Receiving
        assertTrue(receivingState.statusMessage.contains("Приём файла"))

        manager.onMftPacketReceived("!sender", MftData(transferId, 0, chunk0).encode())
        val dataState = manager.incomingState.value as TransferState.Receiving
        assertTrue(dataState.statusMessage.contains("Приём: 1 из 2"))

        manager.onMftPacketReceived("!sender", MftData(transferId, 1, chunk1).encode())
        val completedState = manager.incomingState.value as TransferState.Completed
        assertTrue(completedState.statusMessage.contains("doc.pdf"))
        assertEquals("/storage/emulated/0/Download/Meshtastic/doc.pdf", completedState.savedPath)
    }
}
