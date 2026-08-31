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

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.toByteString
import org.koin.core.annotation.Single
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.repository.CommandSender
import org.meshtastic.core.repository.ServiceRepository
import org.meshtastic.core.ui.util.saveFileToDownloads
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import kotlin.random.Random

/**
 * Manages outgoing and incoming file transfers using the MFT sub-protocol over `PortNum.PRIVATE_APP`.
 * - Outgoing: chunks a file, sends each chunk with Stop-and-Wait ARQ.
 * - Incoming: receives and ACKs chunks, reassembles the file, saves via callback.
 *
 * Thread safety: all mutable state is guarded by [mutex] or confined to the [scope] dispatcher.
 */
@Suppress("TooManyFunctions", "MagicNumber")
@Single
class FileTransferManager(
    applicationScope: ApplicationCoroutineScope,
    private val commandSender: CommandSender,
    serviceRepository: ServiceRepository,
    private val onFileSaved: suspend (fileName: String, data: ByteArray) -> String? = { name, bytes ->
        saveFileToDownloads(name, bytes)
    },
) {
    private val scope: CoroutineScope = applicationScope

    private val mutex = Mutex()

    // ── Observable state for the UI ──
    private val _outgoingState = MutableStateFlow<TransferState>(TransferState.Idle)
    val outgoingState: StateFlow<TransferState> = _outgoingState.asStateFlow()

    private val _incomingState = MutableStateFlow<TransferState>(TransferState.Idle)
    val incomingState: StateFlow<TransferState> = _incomingState.asStateFlow()

    // ── Outgoing transfer ──
    private var sendJob: Job? = null
    private var outTransferId: Int = 0
    private var outChunks: List<ByteArray> = emptyList()
    private var outFileName: String = ""
    private var outFileSize: Long = 0
    private var outCrc32: Long = 0
    private var outDestAddress: String = ""
    private var ackReceived = MutableStateFlow(false)

    // ── Incoming transfer ──
    private var inTransferId: Int = 0
    private var inFileName: String = ""
    private var inFileSize: Long = 0
    private var inTotalChunks: Int = 0
    private var inCrc32: Long = 0
    private var inChunkBuffer: MutableMap<Int, ByteArray> = mutableMapOf()
    private var inFromAddress: String = ""

    init {
        startListening(serviceRepository.meshPacketFlow)
    }

    // ── Retry config ──
    companion object {
        const val ACK_TIMEOUT_MS = 8_000L
        const val MAX_RETRIES = 5
        const val INTER_CHUNK_DELAY_MS = 500L
    }

    /**
     * Start sending a file to the given destination.
     *
     * @param destAddress The destination node address string (e.g., "!deadbeef").
     * @param fileName Name of the file.
     * @param fileData Raw file bytes.
     */
    fun startSending(destAddress: String, fileName: String, fileData: ByteArray) {
        if (fileData.size > MftProtocol.MAX_FILE_SIZE) {
            _outgoingState.value = TransferState.Failed(fileName, "File too large")
            return
        }
        if (_outgoingState.value is TransferState.Sending) {
            Logger.w { "MFT: already sending a file, ignoring new request" }
            return
        }

        outTransferId = Random.nextInt(0, 0xFFFF)
        outFileName = fileName
        outFileSize = fileData.size.toLong()
        outCrc32 = computeCrc32(fileData)
        outDestAddress = destAddress
        outChunks = chunkFile(fileData)

        Logger.i {
            "MFT: starting send of '$fileName' (${fileData.size} bytes, ${outChunks.size} chunks) to $destAddress"
        }

        sendJob =
            scope.launch {
                try {
                    executeSend()
                } catch (e: CancellationException) {
                    _outgoingState.value = TransferState.Failed(outFileName, "Cancelled")
                    sendCancelPacket(outDestAddress, outTransferId, MftProtocol.CANCEL_USER)
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    Logger.e(e) { "MFT: send failed" }
                    _outgoingState.value = TransferState.Failed(outFileName, e.message ?: "Unknown error")
                }
            }
    }

    fun cancelOutgoing() {
        sendJob?.cancel()
        sendJob = null
        scope.launch { sendCancelPacket(outDestAddress, outTransferId, MftProtocol.CANCEL_USER) }
        _outgoingState.value = TransferState.Idle
    }

    fun cancelIncoming() {
        scope.launch { sendCancelPacket(inFromAddress, inTransferId, MftProtocol.CANCEL_USER) }
        resetIncoming()
    }

    fun toggleOutgoingMinimized() {
        val current = _outgoingState.value
        if (current is TransferState.Sending) {
            _outgoingState.value = current.copy(isMinimized = !current.isMinimized)
        }
    }

    fun toggleIncomingMinimized() {
        val current = _incomingState.value
        if (current is TransferState.Receiving) {
            _incomingState.value = current.copy(isMinimized = !current.isMinimized)
        }
    }

    fun dismissResult() {
        _outgoingState.value = TransferState.Idle
        _incomingState.value = TransferState.Idle
    }

    /** Observes incoming mesh packets and processes MFT packets. */
    fun startListening(meshPacketFlow: Flow<MeshPacket>) {
        scope.launch {
            meshPacketFlow.collect { packet ->
                val decoded = packet.decoded ?: return@collect
                if (decoded.portnum == PortNum.PRIVATE_APP) {
                    val payload = decoded.payload.toByteArray()
                    if (MftProtocol.isMftPacket(payload)) {
                        val fromAddress = NodeAddress.numToDefaultId(packet.from)
                        onMftPacketReceived(fromAddress, payload)
                    }
                }
            }
        }
    }

    // ──────────────────────── Outgoing engine ────────────────────────

    @Suppress("NestedBlockDepth")
    private suspend fun executeSend() {
        // 1. Send START packet and wait for ACK
        val startPacket =
            MftStart(
                transferId = outTransferId,
                fileSize = outFileSize,
                totalChunks = outChunks.size,
                crc32 = outCrc32,
                fileName = outFileName,
            )

        _outgoingState.value =
            TransferState.Sending(
                fileName = outFileName,
                fileSize = outFileSize,
                totalChunks = outChunks.size,
                currentChunk = 0,
                transferId = outTransferId,
            )

        sendAndWaitAck(outDestAddress, startPacket.encode(), expectedChunkIndex = MftProtocol.ACK_START_INDEX)

        // 2. Send each chunk, wait for ACK
        for ((index, chunk) in outChunks.withIndex()) {
            val dataPacket = MftData(transferId = outTransferId, chunkIndex = index, data = chunk)

            _outgoingState.value =
                TransferState.Sending(
                    fileName = outFileName,
                    fileSize = outFileSize,
                    totalChunks = outChunks.size,
                    currentChunk = index,
                    transferId = outTransferId,
                )

            sendAndWaitAck(outDestAddress, dataPacket.encode(), expectedChunkIndex = index)

            // Pacing delay to avoid flooding the radio
            delay(INTER_CHUNK_DELAY_MS)
        }

        _outgoingState.value = TransferState.Completed(outFileName, null)
        Logger.i { "MFT: send of '$outFileName' complete" }
    }

    @Suppress("ThrowsCount")
    private suspend fun sendAndWaitAck(dest: String, payload: ByteArray, expectedChunkIndex: Int) {
        var retries = 0
        while (retries < MAX_RETRIES) {
            ackReceived.value = false
            sendRawPacket(dest, payload)

            // Wait for ACK
            val startTime = nowMillis
            while (!ackReceived.value) {
                delay(100)
                val elapsed = nowMillis - startTime
                if (elapsed >= ACK_TIMEOUT_MS) break
            }

            if (ackReceived.value) {
                return
            }

            retries++
            Logger.w { "MFT: ACK timeout for chunk $expectedChunkIndex, retry $retries/$MAX_RETRIES" }
            _outgoingState.value =
                (_outgoingState.value as? TransferState.Sending)?.copy(retryCount = retries) ?: _outgoingState.value
        }
        throw MftTransferException("ACK timeout after $MAX_RETRIES retries for chunk $expectedChunkIndex")
    }

    // ──────────────────────── Incoming engine ────────────────────────

    /**
     * Called when an MFT packet is received from the radio layer. Returns `true` if the packet was consumed (and should
     * NOT be persisted as a message).
     */
    @Suppress("ReturnCount")
    suspend fun onMftPacketReceived(fromNodeAddress: String, payload: ByteArray): Boolean {
        if (!MftProtocol.isMftPacket(payload)) return false

        when (MftProtocol.packetType(payload)) {
            MftProtocol.TYPE_START -> {
                val start = MftStart.decode(payload) ?: return false
                handleIncomingStart(fromNodeAddress, start)
            }

            MftProtocol.TYPE_DATA -> {
                val data = MftData.decode(payload) ?: return false
                handleIncomingData(fromNodeAddress, data)
            }

            MftProtocol.TYPE_ACK -> {
                val ack = MftAck.decode(payload) ?: return false
                handleIncomingAck(ack)
            }

            MftProtocol.TYPE_CANCEL -> {
                val cancel = MftCancel.decode(payload) ?: return false
                handleIncomingCancel(cancel)
            }

            else -> return false
        }
        return true
    }

    private suspend fun handleIncomingStart(from: String, start: MftStart) {
        Logger.i {
            "MFT: received START from $from: '${start.fileName}' (${start.fileSize} bytes, ${start.totalChunks} chunks)"
        }

        if (start.fileSize > MftProtocol.MAX_FILE_SIZE) {
            Logger.w { "MFT: rejecting transfer — file too large (${start.fileSize} bytes)" }
            sendAck(from, start.transferId, MftProtocol.ACK_START_INDEX, MftProtocol.ACK_ERROR)
            return
        }

        mutex.withLock {
            inTransferId = start.transferId
            inFileName = start.fileName
            inFileSize = start.fileSize
            inTotalChunks = start.totalChunks
            inCrc32 = start.crc32
            inChunkBuffer = mutableMapOf()
            inFromAddress = from
        }

        _incomingState.value =
            TransferState.Receiving(
                fileName = start.fileName,
                fileSize = start.fileSize,
                totalChunks = start.totalChunks,
                receivedChunks = 0,
                transferId = start.transferId,
            )

        sendAck(from, start.transferId, MftProtocol.ACK_START_INDEX, MftProtocol.ACK_OK)
    }

    private suspend fun handleIncomingData(from: String, data: MftData) {
        if (data.transferId != inTransferId) {
            Logger.w { "MFT: ignoring data for unknown transfer ${data.transferId}" }
            return
        }

        // Always ACK, even duplicates
        sendAck(from, data.transferId, data.chunkIndex, MftProtocol.ACK_OK)

        // Deduplicate
        mutex.withLock {
            if (inChunkBuffer.containsKey(data.chunkIndex)) {
                Logger.d { "MFT: duplicate chunk ${data.chunkIndex}, ACK sent but not stored" }
                return
            }
            inChunkBuffer[data.chunkIndex] = data.data
        }

        val received = inChunkBuffer.size
        _incomingState.value =
            TransferState.Receiving(
                fileName = inFileName,
                fileSize = inFileSize,
                totalChunks = inTotalChunks,
                receivedChunks = received,
                transferId = inTransferId,
            )

        // Check if transfer is complete
        if (received >= inTotalChunks) {
            assembleAndSaveFile()
        }
    }

    private fun handleIncomingAck(ack: MftAck) {
        if (ack.transferId == outTransferId) {
            Logger.d { "MFT: ACK received for chunk ${ack.chunkIndex}" }
            ackReceived.value = true
        }
    }

    private fun handleIncomingCancel(cancel: MftCancel) {
        if (cancel.transferId == inTransferId) {
            Logger.i { "MFT: remote cancelled incoming transfer (reason=${cancel.reason})" }
            _incomingState.value = TransferState.Failed(inFileName, "Cancelled by sender")
            resetIncoming()
        }
        if (cancel.transferId == outTransferId) {
            Logger.i { "MFT: remote cancelled outgoing transfer (reason=${cancel.reason})" }
            sendJob?.cancel()
            _outgoingState.value = TransferState.Failed(outFileName, "Cancelled by receiver")
        }
    }

    // ──────────────────────── Assembly ────────────────────────

    private suspend fun assembleAndSaveFile() {
        val orderedChunks: List<ByteArray>
        mutex.withLock {
            orderedChunks =
                (0 until inTotalChunks).map { i ->
                    inChunkBuffer[i]
                        ?: run {
                            _incomingState.value = TransferState.Failed(inFileName, "Missing chunk $i")
                            return
                        }
                }
        }

        val assembled = ByteArray(orderedChunks.sumOf { it.size })
        var offset = 0
        for (chunk in orderedChunks) {
            chunk.copyInto(assembled, offset)
            offset += chunk.size
        }

        // Verify CRC32
        val actualCrc = computeCrc32(assembled)
        if (actualCrc != inCrc32) {
            Logger.e { "MFT: CRC32 mismatch! expected=$inCrc32, actual=$actualCrc" }
            _incomingState.value = TransferState.Failed(inFileName, "CRC32 mismatch")
            resetIncoming()
            return
        }

        Logger.i { "MFT: file '$inFileName' fully received (${assembled.size} bytes), saving..." }
        val savedPath = onFileSaved(inFileName, assembled)
        _incomingState.value = TransferState.Completed(inFileName, savedPath)
        resetIncoming()
    }

    // ──────────────────────── Packet I/O ────────────────────────

    private suspend fun sendRawPacket(dest: String, payload: ByteArray) {
        val packet =
            DataPacket(
                to = dest,
                bytes = payload.toByteString(),
                dataType = PortNum.PRIVATE_APP.value,
                wantAck = false, // ARQ is at the application level
                hopLimit = 0, // Direct link only, no mesh routing
                isDirectOnly = true,
                viaMqtt = false,
            )
        try {
            commandSender.sendData(packet)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Logger.e(e) { "MFT: failed to send packet" }
            throw e
        }
    }

    private suspend fun sendAck(dest: String, transferId: Int, chunkIndex: Int, status: Byte) {
        val ack = MftAck(transferId, chunkIndex, status)
        sendRawPacket(dest, ack.encode())
    }

    private suspend fun sendCancelPacket(dest: String, transferId: Int, reason: Byte) {
        if (dest.isEmpty()) return
        val cancel = MftCancel(transferId, reason)
        try {
            sendRawPacket(dest, cancel.encode())
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Logger.w(e) { "MFT: failed to send cancel packet" }
        }
    }

    // ──────────────────────── Utilities ────────────────────────

    private fun resetIncoming() {
        inTransferId = 0
        inFileName = ""
        inFileSize = 0
        inTotalChunks = 0
        inCrc32 = 0
        inChunkBuffer = mutableMapOf()
        inFromAddress = ""
    }

    private fun chunkFile(data: ByteArray): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + MftProtocol.CHUNK_PAYLOAD_SIZE, data.size)
            chunks.add(data.copyOfRange(offset, end))
            offset = end
        }
        return chunks
    }
}

class MftTransferException(message: String) : Exception(message)

/**
 * CRC-32 implementation (standard ITU-T/zip polynomial). Using a simple table-based approach so we stay in commonMain
 * without platform deps.
 */
@Suppress("MagicNumber")
fun computeCrc32(data: ByteArray): Long {
    var crc = 0xFFFFFFFFL
    for (byte in data) {
        val index = ((crc xor byte.toLong()) and 0xFFL).toInt()
        crc = CRC32_TABLE[index] xor (crc shr 8)
    }
    return crc xor 0xFFFFFFFFL
}

@Suppress("MagicNumber")
private val CRC32_TABLE: LongArray =
    LongArray(256) { n ->
        var c = n.toLong()
        for (k in 0 until 8) {
            c =
                if (c and 1L != 0L) {
                    0xEDB88320L xor (c shr 1)
                } else {
                    c shr 1
                }
        }
        c
    }
