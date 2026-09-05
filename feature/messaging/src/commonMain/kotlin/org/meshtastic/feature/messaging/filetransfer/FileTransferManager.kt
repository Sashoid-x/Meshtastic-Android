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
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.repository.ServiceRepository
import org.meshtastic.core.ui.util.saveFileToDownloads
import org.meshtastic.proto.Config.LoRaConfig.ModemPreset
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import kotlin.random.Random

/**
 * Manages outgoing and incoming file transfers using the MFT sub-protocol over `PortNum.PRIVATE_APP`.
 * - Outgoing: Gzip-compresses if beneficial, chunks into 216-byte packets, sends in adaptive sliding window bursts with
 *   immediate Selective ACK (SACK). Supports resume on errors.
 * - Incoming: gathers chunks, deduplicates, sends SACK immediately on window end or idle fallback, verifies CRC32,
 *   decompresses, and saves to Downloads/Meshtastic.
 */
@Suppress("TooManyFunctions", "MagicNumber", "LargeClass")
@Single
class FileTransferManager(
    applicationScope: ApplicationCoroutineScope,
    private val commandSender: CommandSender,
    serviceRepository: ServiceRepository,
    radioConfigRepository: RadioConfigRepository,
    private val onFileSaved: suspend (fileName: String, data: ByteArray) -> String? = { name, bytes ->
        saveFileToDownloads(name, bytes)
    },
) {
    private val scope: CoroutineScope = applicationScope
    private val mutex = Mutex()
    private val logger = Logger.withTag("MFT")

    // ── Observable state for the UI ──
    private val _outgoingState = MutableStateFlow<TransferState>(TransferState.Idle)
    val outgoingState: StateFlow<TransferState> = _outgoingState.asStateFlow()

    private val _incomingState = MutableStateFlow<TransferState>(TransferState.Idle)
    val incomingState: StateFlow<TransferState> = _incomingState.asStateFlow()

    // ── Outgoing transfer state ──
    private var sendJob: Job? = null
    private var outTransferId: Int = 0
    private var outChunks: List<ByteArray> = emptyList()
    private var outFileName: String = ""
    private var outRawFileSize: Long = 0
    private var outCrc32: Long = 0
    private var outDestAddress: String = ""
    private var outIsGzip: Boolean = false
    private var outStartTimeMs: Long = 0L
    private val startAckReceived = MutableStateFlow(false)
    private val latestMissing = MutableStateFlow<MftMissing?>(null)
    private val latestComplete = MutableStateFlow(false)

    @Volatile private var isSendingCancelled = false

    // Last transfer data for retry/resume
    private var lastDestAddress: String = ""
    private var lastFileName: String = ""
    private var lastFileData: ByteArray? = null

    // ── Incoming transfer state ──
    private var inTransferId: Int = 0
    private var inFileName: String = ""
    private var inRawFileSize: Long = 0
    private var inTotalChunks: Int = 0
    private var inCrc32: Long = 0
    private var inIsGzip: Boolean = false
    private var inChunkBuffer: MutableMap<Int, ByteArray> = mutableMapOf()
    private var inFromAddress: String = ""
    private var inStartTimeMs: Long = 0L
    private var idleCheckJob: Job? = null
    private var lastCompletedTransferId: Int = 0
    private var lastCancelledTransferId: Int = 0
    private var inCurrentPassNumber: Int = 1

    @Volatile private var activeTimingProfile: MftTimingProfile = MftTimingProfile.forPreset(ModemPreset.SHORT_TURBO)

    init {
        startListening(serviceRepository.meshPacketFlow)
        scope.launch {
            radioConfigRepository.localConfigFlow.collect { config ->
                val lora = config.lora
                activeTimingProfile =
                    if (lora != null && !lora.use_preset && lora.spread_factor in 7..12 && lora.bandwidth > 0) {
                        MftTimingProfile.fromCustom(lora.spread_factor, lora.bandwidth)
                    } else {
                        MftTimingProfile.forPreset(lora?.modem_preset)
                    }
                logger.i {
                    "adaptive timing profile updated: preset=${lora?.modem_preset}, " +
                        "interChunkDelay=${activeTimingProfile.interChunkDelayMs}ms, " +
                        "passTimeout=${activeTimingProfile.passResponseTimeoutMs}ms"
                }
            }
        }
    }

    companion object {
        const val MAX_RETRIES = 6
        const val CANCEL_BURST_COUNT = 3
        const val CHUNK_PACING_DELAY_MS = 600L
        const val START_ACK_TIMEOUT_MS = 10_000L
        const val PASS_RESPONSE_TIMEOUT_MS = 15_000L
        const val PASS_BURST_DELAY_MS = 300L
        const val IDLE_REPAIR_TIMEOUT_MS = 20_000L
    }

    fun startSending(destAddress: String, fileName: String, fileData: ByteArray) {
        if (fileData.size > MftProtocol.MAX_FILE_SIZE) {
            _outgoingState.value = TransferState.Failed(fileName, "File too large", canRetry = false)
            return
        }
        if (_outgoingState.value is TransferState.Sending) {
            logger.w { "already sending a file, ignoring new request" }
            return
        }

        lastDestAddress = destAddress
        lastFileName = fileName
        lastFileData = fileData

        val (toSend, isGzip) = MftCompression.compress(fileData)
        outTransferId = Random.nextInt(1, 0xFFFE)
        outFileName = fileName
        outRawFileSize = fileData.size.toLong()
        outCrc32 = computeCrc32(fileData)
        outDestAddress = destAddress
        outIsGzip = isGzip
        outChunks = chunkFile(toSend)
        outStartTimeMs = nowMillis
        startAckReceived.value = false
        latestMissing.value = null
        latestComplete.value = false
        isSendingCancelled = false

        val ratio = "gzip=$isGzip -> ${toSend.size} B, ${outChunks.size} chunks"
        logger.i { "send '$fileName' (${fileData.size} B, $ratio) to $destAddress" }

        sendJob =
            scope.launch {
                try {
                    executeSend()
                } catch (e: CancellationException) {
                    _outgoingState.value = TransferState.Failed(outFileName, "Отменено", canRetry = true)
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    logger.e(e) { "send failed" }
                    _outgoingState.value =
                        TransferState.Failed(
                            fileName = outFileName,
                            reason = e.message ?: "Ошибка передачи",
                            canRetry = true,
                        )
                }
            }
    }

    fun retryOutgoing() {
        val data = lastFileData
        val dest = lastDestAddress
        val name = lastFileName
        if (data != null && dest.isNotEmpty()) {
            startSending(dest, name, data)
        }
    }

    fun cancelOutgoing() {
        isSendingCancelled = true
        sendJob?.cancel()
        sendJob = null
        val dest = outDestAddress
        val transferId = outTransferId
        if (dest.isNotEmpty() && transferId != 0) {
            scope.launch {
                repeat(CANCEL_BURST_COUNT) {
                    sendCancelPacket(dest, transferId, MftProtocol.CANCEL_USER)
                    delay(activeTimingProfile.cancelBurstDelayMs)
                }
            }
        }
        _outgoingState.value = TransferState.Failed(outFileName, "Отменено", canRetry = true)
    }

    fun cancelIncoming() {
        idleCheckJob?.cancel()
        idleCheckJob = null
        val dest = inFromAddress
        val transferId = inTransferId
        lastCancelledTransferId = transferId
        if (dest.isNotEmpty() && transferId != 0) {
            scope.launch {
                repeat(CANCEL_BURST_COUNT) {
                    sendCancelPacket(dest, transferId, MftProtocol.CANCEL_USER)
                    delay(activeTimingProfile.cancelBurstDelayMs)
                }
            }
        }
        _incomingState.value = TransferState.Failed(inFileName, "Отменено", canRetry = false)
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

    @Suppress("NestedBlockDepth", "LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    private suspend fun executeSend() {
        val startPacket =
            MftStart(
                transferId = outTransferId,
                fileSize = outRawFileSize,
                totalChunks = outChunks.size,
                crc32 = outCrc32,
                fileName = outFileName,
                isGzip = outIsGzip,
            )

        _outgoingState.value =
            TransferState.Sending(
                fileName = outFileName,
                fileSize = outRawFileSize,
                totalChunks = outChunks.size,
                currentChunk = 0,
                transferId = outTransferId,
                isGzip = outIsGzip,
                statusMessage = "Запрос начала передачи...",
            )

        sendAndWaitStartAck(startPacket.encode())

        val total = outChunks.size
        var pendingIndices = (0 until total).toMutableList()

        // If resuming and receiver already reported missing chunks:
        latestMissing.value?.let { missingPacket ->
            pendingIndices = missingPacket.missingIndices.toMutableList()
            Logger.i { "MFT: resuming transfer, sending ${pendingIndices.size}/$total chunks" }
        }

        var passNumber = 0

        while (pendingIndices.isNotEmpty() && !latestComplete.value && !isSendingCancelled) {
            passNumber++
            logger.i {
                "starting pass #$passNumber with ${pendingIndices.size}/$total chunks" +
                    if (passNumber > 1) " (repair: indices=${pendingIndices.take(20)})" else ""
            }

            var sentInThisPass = 0
            for (chunkIdx in pendingIndices) {
                if (latestComplete.value || isSendingCancelled) break

                val dataPacket = MftData(transferId = outTransferId, chunkIndex = chunkIdx, data = outChunks[chunkIdx])
                sendRawPacket(outDestAddress, dataPacket.encode(), priority = MeshPacket.Priority.RELIABLE)
                sentInThisPass++

                val deliveredChunks = (total - pendingIndices.size + sentInThisPass).coerceAtMost(total)
                val elapsedSec = (nowMillis - outStartTimeMs) / 1000.0
                val bytesSent = (deliveredChunks.toLong() * MftProtocol.CHUNK_PAYLOAD_SIZE).coerceAtMost(outRawFileSize)
                val speed = if (elapsedSec > 0.5) bytesSent / elapsedSec else 0.0
                val wasMinimized = (_outgoingState.value as? TransferState.Sending)?.isMinimized ?: false

                val status =
                    if (passNumber == 1) {
                        "Отправка чанка $deliveredChunks из $total"
                    } else {
                        "Досыл чанка $sentInThisPass из ${pendingIndices.size} (проход $passNumber)"
                    }

                _outgoingState.value =
                    TransferState.Sending(
                        fileName = outFileName,
                        fileSize = outRawFileSize,
                        totalChunks = total,
                        currentChunk = deliveredChunks,
                        retryCount = 0,
                        isMinimized = wasMinimized,
                        transferId = outTransferId,
                        bytesTransferred = bytesSent,
                        speedBytesPerSec = speed,
                        isGzip = outIsGzip,
                        passNumber = passNumber,
                        statusMessage = status,
                    )

                delay(CHUNK_PACING_DELAY_MS)
            }

            if (isSendingCancelled) return

            // FIFO: send MftPassEnd with Priority.RELIABLE so it queues strictly behind all data chunks!
            val passEndPacket = MftPassEnd(transferId = outTransferId, passNumber = passNumber)
            sendRawPacket(outDestAddress, passEndPacket.encode(), priority = MeshPacket.Priority.RELIABLE)

            // DRAIN: Give radio hardware TX queue time to transmit all buffered chunks + MftPassEnd
            // and switch to RX mode before expecting pass status!
            val queueDrainMultiplier = if (sentInThisPass > 8) 6L else sentInThisPass.toLong()
            val drainDelayMs = (queueDrainMultiplier * CHUNK_PACING_DELAY_MS).coerceAtLeast(CHUNK_PACING_DELAY_MS * 2)
            logger.i { "pass #$passNumber: sent $sentInThisPass chunks, draining radio queue for ${drainDelayMs}ms..." }
            val wasMin = (_outgoingState.value as? TransferState.Sending)?.isMinimized ?: false
            _outgoingState.value =
                (_outgoingState.value as? TransferState.Sending)?.copy(
                    statusMessage = "Ожидание отчёта от получателя...",
                    isMinimized = wasMin,
                ) ?: _outgoingState.value
            delay(drainDelayMs)

            if (latestComplete.value) {
                _outgoingState.value = TransferState.Completed(outFileName, null, "Файл успешно передан!")
                logger.i { "send of '$outFileName' complete in $passNumber passes" }
                return
            }

            // Persistent query for receiver's pass status
            latestMissing.value = null
            val statusReceived = queryPassStatus(passNumber)
            if (isSendingCancelled) return

            if (latestComplete.value) {
                _outgoingState.value = TransferState.Completed(outFileName, null, "Файл успешно передан!")
                logger.i { "send of '$outFileName' complete in $passNumber passes" }
                return
            }

            if (!statusReceived) {
                logger.w { "pass #$passNumber handshake failed after $MAX_RETRIES attempts, aborting" }
                _outgoingState.value =
                    TransferState.Failed(
                        fileName = outFileName,
                        reason = "Тайм-аут ожидания ответа приёмника",
                        canRetry = true,
                    )
                return
            }

            val missing = latestMissing.value
            if (missing != null && missing.passNumber == passNumber) {
                val receivedCount = (total - missing.totalMissing).coerceAtLeast(0)
                logger.i {
                    "pass #$passNumber: receiver reports $receivedCount/$total received, " +
                        "${missing.totalMissing} missing (indices in packet: ${missing.missingIndices.size}), " +
                        "will retransmit: ${missing.missingIndices.take(20)}"
                }
                val elapsedSec = (nowMillis - outStartTimeMs) / 1000.0
                val bytesSent = (receivedCount.toLong() * MftProtocol.CHUNK_PAYLOAD_SIZE).coerceAtMost(outRawFileSize)
                val speed = if (elapsedSec > 0.5) bytesSent / elapsedSec else 0.0
                val wasMinimized = (_outgoingState.value as? TransferState.Sending)?.isMinimized ?: false
                _outgoingState.value =
                    TransferState.Sending(
                        fileName = outFileName,
                        fileSize = outRawFileSize,
                        totalChunks = total,
                        currentChunk = receivedCount,
                        retryCount = 0,
                        isMinimized = wasMinimized,
                        transferId = outTransferId,
                        bytesTransferred = bytesSent,
                        speedBytesPerSec = speed,
                        isGzip = outIsGzip,
                        passNumber = passNumber,
                        statusMessage =
                        "Получатель запросил ${missing.missingIndices.size} чанков. Подготовка досыла...",
                    )
                pendingIndices = missing.missingIndices.toMutableList()
            }
        }

        _outgoingState.value = TransferState.Completed(outFileName, null, "Файл успешно передан!")
        logger.i { "send of '$outFileName' complete" }
    }

    private suspend fun sendAndWaitStartAck(startBytes: ByteArray) {
        var retries = 0
        while (retries < MAX_RETRIES) {
            sendRawPacket(outDestAddress, startBytes, priority = MeshPacket.Priority.MAX)
            val startTime = nowMillis
            while (!startAckReceived.value && latestMissing.value == null && !isSendingCancelled) {
                delay(50)
                if (nowMillis - startTime >= START_ACK_TIMEOUT_MS) break
            }
            if (startAckReceived.value || latestMissing.value != null) return
            retries++
            val wasMinimized = (_outgoingState.value as? TransferState.Sending)?.isMinimized ?: false
            _outgoingState.value =
                (_outgoingState.value as? TransferState.Sending)?.copy(
                    statusMessage = "Повторный запрос начала (попытка $retries из $MAX_RETRIES)...",
                    isMinimized = wasMinimized,
                ) ?: _outgoingState.value
            logger.w { "START ACK timeout, retry $retries/$MAX_RETRIES" }
        }
        throw MftTransferException("START ACK timeout after $MAX_RETRIES retries")
    }

    private fun hasValidEarlyResponse(): Boolean = latestComplete.value

    private fun isPassPending(passNumber: Int): Boolean {
        val missing = latestMissing.value
        return !latestComplete.value && (missing == null || missing.passNumber != passNumber)
    }

    private suspend fun queryPassStatus(passNumber: Int): Boolean {
        if (hasValidEarlyResponse()) {
            logger.i { "receiver already reported Complete, skipping query" }
            return true
        }

        var attempt = 1
        while (attempt <= MAX_RETRIES && !isSendingCancelled && isPassPending(passNumber)) {
            pollPassStatusAttempt(passNumber, attempt)
            if (isPassPending(passNumber) && !isSendingCancelled) {
                delay(PASS_BURST_DELAY_MS)
            }
            attempt++
        }
        return latestComplete.value || (latestMissing.value?.passNumber == passNumber)
    }

    private suspend fun pollPassStatusAttempt(passNumber: Int, attempt: Int) {
        logger.i { "querying pass #$passNumber status from receiver (attempt $attempt/$MAX_RETRIES)" }
        val wasMinimized = (_outgoingState.value as? TransferState.Sending)?.isMinimized ?: false
        val status =
            if (attempt == 1) {
                "Ожидание отчёта от получателя..."
            } else {
                "Повторный запрос отчёта у получателя (попытка $attempt из $MAX_RETRIES)..."
            }
        _outgoingState.value =
            (_outgoingState.value as? TransferState.Sending)?.copy(
                statusMessage = status,
                retryCount = attempt - 1,
                isMinimized = wasMinimized,
            ) ?: _outgoingState.value

        // On attempt >= 2, re-query explicitly with Priority.MAX (queue is now drained so safe from overtaking chunks)
        if (attempt > 1) {
            val passEndPacket = MftPassEnd(transferId = outTransferId, passNumber = passNumber)
            sendRawPacket(outDestAddress, passEndPacket.encode(), priority = MeshPacket.Priority.MAX)
        }

        val startTime = nowMillis
        while (isPassPending(passNumber) && !isSendingCancelled) {
            delay(100)
            if (nowMillis - startTime >= PASS_RESPONSE_TIMEOUT_MS) break
        }

        if (isPassPending(passNumber)) {
            logger.w { "pass #$passNumber response timeout, retrying query..." }
        }
    }

    // ──────────────────────── Incoming engine ────────────────────────

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    suspend fun onMftPacketReceived(fromNodeAddress: String, payload: ByteArray): Boolean {
        if (!MftProtocol.isMftPacket(payload)) return false

        when (MftProtocol.packetType(payload)) {
            MftProtocol.TYPE_START -> MftStart.decode(payload)?.let { handleIncomingStart(fromNodeAddress, it) }
            MftProtocol.TYPE_START_ACK -> MftStartAck.decode(payload)?.let { handleIncomingStartAck(it) }
            MftProtocol.TYPE_DATA -> MftData.decode(payload)?.let { handleIncomingData(fromNodeAddress, it) }
            MftProtocol.TYPE_PASS_END -> MftPassEnd.decode(payload)?.let { handleIncomingPassEnd(fromNodeAddress, it) }
            MftProtocol.TYPE_MISSING -> MftMissing.decode(payload)?.let { handleIncomingMissing(it) }
            MftProtocol.TYPE_COMPLETE -> MftComplete.decode(payload)?.let { handleIncomingComplete(it) }
            MftProtocol.TYPE_CANCEL -> MftCancel.decode(payload)?.let { handleIncomingCancel(it) }
            MftProtocol.TYPE_PROGRESS -> MftProgress.decode(payload)?.let { handleIncomingProgress(it) }
            else -> return false
        }
        return true
    }

    private suspend fun handleIncomingStart(from: String, start: MftStart) {
        val info = "(${start.fileSize} B, gzip=${start.isGzip}, ${start.totalChunks} chunks)"
        logger.i { "START from $from: '${start.fileName}' $info" }

        if (start.fileSize > MftProtocol.MAX_FILE_SIZE) {
            logger.w { "rejecting transfer — file too large (${start.fileSize} bytes)" }
            sendCancelPacket(from, start.transferId, MftProtocol.CANCEL_SIZE_LIMIT)
            return
        }

        val isResume =
            start.fileName == inFileName &&
                start.fileSize == inRawFileSize &&
                start.crc32 == inCrc32 &&
                inChunkBuffer.isNotEmpty()

        mutex.withLock {
            inTransferId = start.transferId
            inFileName = start.fileName
            inRawFileSize = start.fileSize
            inTotalChunks = start.totalChunks
            inCrc32 = start.crc32
            inIsGzip = start.isGzip
            inFromAddress = from
            if (!isResume) {
                inChunkBuffer = mutableMapOf()
                inStartTimeMs = nowMillis
            }
        }

        val wasMinimized = (_incomingState.value as? TransferState.Receiving)?.isMinimized ?: false
        _incomingState.value =
            TransferState.Receiving(
                fileName = start.fileName,
                fileSize = start.fileSize,
                totalChunks = start.totalChunks,
                receivedChunks = inChunkBuffer.size,
                isMinimized = false,
                transferId = start.transferId,
                isGzip = start.isGzip,
                statusMessage = "Приём файла '${start.fileName}'...",
            )

        if (isResume) {
            sendMissingListOrComplete(from)
        } else {
            sendStartAck(from, start.transferId, 0)
        }
    }

    @Suppress("ReturnCount")
    private suspend fun handleIncomingData(from: String, data: MftData) {
        if (data.transferId == lastCompletedTransferId) {
            sendRawPacket(from, MftComplete(lastCompletedTransferId).encode(), priority = MeshPacket.Priority.MAX)
            return
        }
        if (data.transferId == lastCancelledTransferId) {
            // Silently drop in-flight chunks of cancelled transfer; do not blast cancel packets back!
            return
        }
        if (data.transferId != inTransferId) return

        mutex.withLock { inChunkBuffer[data.chunkIndex] = data.data }

        val received = inChunkBuffer.size
        val elapsedSec = (nowMillis - inStartTimeMs) / 1000.0
        val bytesRecv = (received.toLong() * MftProtocol.CHUNK_PAYLOAD_SIZE).coerceAtMost(inRawFileSize)
        val speed = if (elapsedSec > 0.5) bytesRecv / elapsedSec else 0.0
        val wasMinimized = (_incomingState.value as? TransferState.Receiving)?.isMinimized ?: false
        val percent = if (inTotalChunks > 0) (received * 100) / inTotalChunks else 0

        _incomingState.value =
            TransferState.Receiving(
                fileName = inFileName,
                fileSize = inRawFileSize,
                totalChunks = inTotalChunks,
                receivedChunks = received,
                isMinimized = wasMinimized,
                transferId = inTransferId,
                bytesTransferred = bytesRecv,
                speedBytesPerSec = speed,
                isGzip = inIsGzip,
                passNumber = inCurrentPassNumber,
                statusMessage = "Приём: $received из $inTotalChunks чанков ($percent%)",
            )

        if (received >= inTotalChunks) {
            idleCheckJob?.cancel()
            idleCheckJob = null
            sendMissingListOrComplete(from, inCurrentPassNumber)
        } else {
            idleCheckJob?.cancel()
            idleCheckJob =
                scope.launch {
                    delay(IDLE_REPAIR_TIMEOUT_MS)
                    sendMissingListOrComplete(from, inCurrentPassNumber)
                }
        }
    }

    @Suppress("ReturnCount")
    private suspend fun handleIncomingPassEnd(from: String, passEnd: MftPassEnd) {
        if (passEnd.transferId == lastCompletedTransferId) {
            val encoded = MftComplete(lastCompletedTransferId).encode()
            sendRawPacket(from, encoded, priority = MeshPacket.Priority.MAX)
            delay(PASS_BURST_DELAY_MS)
            sendRawPacket(from, encoded, priority = MeshPacket.Priority.MAX)
            return
        }
        if (passEnd.transferId == lastCancelledTransferId) {
            // Already cancelled, drop silently
            return
        }
        if (passEnd.transferId != inTransferId) return
        idleCheckJob?.cancel()
        idleCheckJob = null
        inCurrentPassNumber = passEnd.passNumber
        sendMissingListOrComplete(from, inCurrentPassNumber)
    }

    private suspend fun sendMissingListOrComplete(to: String, passNumber: Int = inCurrentPassNumber) {
        if (inTotalChunks <= 0) return
        val missing = ArrayList<Int>()
        mutex.withLock {
            for (i in 0 until inTotalChunks) {
                if (!inChunkBuffer.containsKey(i)) {
                    missing.add(i)
                }
            }
        }

        val wasMinimized = (_incomingState.value as? TransferState.Receiving)?.isMinimized ?: false
        if (missing.isEmpty()) {
            logger.i { "all chunks received, assembling and verifying..." }
            _incomingState.value =
                (_incomingState.value as? TransferState.Receiving)?.copy(
                    statusMessage = "Сборка файла и проверка CRC32...",
                    isMinimized = wasMinimized,
                ) ?: _incomingState.value

            val completedId = inTransferId
            if (assembleAndSaveFile()) {
                val encoded = MftComplete(completedId).encode()
                sendRawPacket(to, encoded, priority = MeshPacket.Priority.MAX)
                delay(PASS_BURST_DELAY_MS)
                sendRawPacket(to, encoded, priority = MeshPacket.Priority.MAX)
                logger.i { "sent MftComplete burst for transfer #$completedId" }
            }
        } else {
            sendMissingChunksRequest(to, passNumber, missing, wasMinimized)
        }
    }

    private suspend fun sendMissingChunksRequest(
        to: String,
        passNumber: Int,
        missing: List<Int>,
        wasMinimized: Boolean,
    ) {
        val indicesToSend = missing.take(MftProtocol.MAX_MISSING_PER_PACKET)
        logger.i {
            "pass #$passNumber: missing ${missing.size} chunks, requesting ${indicesToSend.size}: ${indicesToSend.take(
                20,
            )}"
        }
        _incomingState.value =
            (_incomingState.value as? TransferState.Receiving)?.copy(
                statusMessage = "Запрос досыла ${missing.size} недостающих чанков...",
                isMinimized = wasMinimized,
            ) ?: _incomingState.value

        val packet =
            MftMissing(
                transferId = inTransferId,
                passNumber = passNumber,
                totalMissing = missing.size,
                missingIndices = indicesToSend,
            )
        val encoded = packet.encode()
        sendRawPacket(to, encoded, priority = MeshPacket.Priority.MAX)
        delay(PASS_BURST_DELAY_MS)
        sendRawPacket(to, encoded, priority = MeshPacket.Priority.MAX)

        _incomingState.value =
            (_incomingState.value as? TransferState.Receiving)?.copy(
                statusMessage = "Ожидание досыла ${missing.size} чанков от отправителя...",
                isMinimized = wasMinimized,
            ) ?: _incomingState.value

        idleCheckJob?.cancel()
        idleCheckJob =
            scope.launch {
                delay(IDLE_REPAIR_TIMEOUT_MS)
                if (inChunkBuffer.size < inTotalChunks && !isSendingCancelled) {
                    logger.w { "pass #$passNumber repair timeout, re-sending MftMissing..." }
                    sendMissingListOrComplete(to, passNumber)
                }
            }
    }

    private fun handleIncomingStartAck(startAck: MftStartAck) {
        if (startAck.transferId == outTransferId) {
            startAckReceived.value = true
        }
    }

    private fun handleIncomingMissing(missing: MftMissing) {
        if (missing.transferId == outTransferId) {
            logger.i {
                "got MftMissing from receiver for pass #${missing.passNumber}: totalMissing=${missing.totalMissing}, " +
                    "indices=${missing.missingIndices.take(20)}"
            }
            startAckReceived.value = true
            latestMissing.value = missing
        }
    }

    private fun handleIncomingComplete(complete: MftComplete) {
        if (complete.transferId == outTransferId) {
            latestComplete.value = true
            _outgoingState.value = TransferState.Completed(outFileName, null, "Файл успешно передан!")
        }
    }

    private fun handleIncomingCancel(cancel: MftCancel) {
        if (cancel.transferId == inTransferId) {
            if (lastCancelledTransferId == cancel.transferId) return
            logger.i { "remote cancelled incoming transfer" }
            idleCheckJob?.cancel()
            idleCheckJob = null
            lastCancelledTransferId = cancel.transferId
            _incomingState.value = TransferState.Failed(inFileName, "Отменено отправителем", canRetry = false)
            resetIncoming()
        }
        if (cancel.transferId == outTransferId) {
            if (isSendingCancelled) return
            logger.i { "remote cancelled outgoing transfer" }
            isSendingCancelled = true
            sendJob?.cancel()
            sendJob = null
            _outgoingState.value = TransferState.Failed(outFileName, "Отменено получателем", canRetry = false)
        }
    }

    private fun handleIncomingProgress(progress: MftProgress) {
        if (progress.transferId == outTransferId) {
            val total = if (outChunks.isNotEmpty()) outChunks.size else progress.totalChunks
            val received = progress.receivedChunks.coerceAtMost(total)
            logger.i { "receiver reported progress checkpoint: $received/$total chunks" }
            val elapsedSec = (nowMillis - outStartTimeMs) / 1000.0
            val bytesSent = (received.toLong() * MftProtocol.CHUNK_PAYLOAD_SIZE).coerceAtMost(outRawFileSize)
            val speed = if (elapsedSec > 0.5) bytesSent / elapsedSec else 0.0
            val wasMinimized = (_outgoingState.value as? TransferState.Sending)?.isMinimized ?: false
            _outgoingState.value =
                TransferState.Sending(
                    fileName = outFileName,
                    fileSize = outRawFileSize,
                    totalChunks = total,
                    currentChunk = received,
                    retryCount = (_outgoingState.value as? TransferState.Sending)?.retryCount ?: 0,
                    isMinimized = wasMinimized,
                    transferId = outTransferId,
                    bytesTransferred = bytesSent,
                    speedBytesPerSec = speed,
                    isGzip = outIsGzip,
                )
        }
    }

    // ──────────────────────── Assembly ────────────────────────

    @Suppress("ReturnCount")
    private suspend fun assembleAndSaveFile(): Boolean {
        val orderedChunks: List<ByteArray>
        mutex.withLock {
            orderedChunks =
                (0 until inTotalChunks).map { i ->
                    inChunkBuffer[i]
                        ?: run {
                            _incomingState.value =
                                TransferState.Failed(inFileName, "Missing chunk $i", canRetry = false)
                            return false
                        }
                }
        }

        val assembled = ByteArray(orderedChunks.sumOf { it.size })
        var offset = 0
        for (chunk in orderedChunks) {
            chunk.copyInto(assembled, offset)
            offset += chunk.size
        }

        val finalBytes =
            if (inIsGzip) {
                try {
                    MftCompression.decompress(assembled)
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    _incomingState.value =
                        TransferState.Failed(
                            fileName = inFileName,
                            reason = "Ошибка распаковки: ${e.message}",
                            canRetry = false,
                        )
                    resetIncoming()
                    return false
                }
            } else {
                assembled
            }

        val actualCrc = computeCrc32(finalBytes)
        if (actualCrc != inCrc32) {
            logger.e { "CRC32 mismatch! expected=$inCrc32, actual=$actualCrc" }
            _incomingState.value = TransferState.Failed(inFileName, "CRC32 mismatch", canRetry = false)
            resetIncoming()
            return false
        }

        val savedPath = onFileSaved(inFileName, finalBytes)
        lastCompletedTransferId = inTransferId
        val statusMsg = if (savedPath != null) "Файл успешно сохранён: $inFileName" else "Ошибка сохранения файла"
        _incomingState.value = TransferState.Completed(inFileName, savedPath, statusMsg)
        resetIncoming()
        return true
    }

    // ──────────────────────── Packet I/O ────────────────────────

    private suspend fun sendRawPacket(
        dest: String,
        payload: ByteArray,
        priority: MeshPacket.Priority = MeshPacket.Priority.DEFAULT,
    ) {
        val packet =
            DataPacket(
                to = dest,
                bytes = payload.toByteString(),
                dataType = PortNum.PRIVATE_APP.value,
                wantAck = false,
                hopLimit = 0,
                isDirectOnly = true,
                viaMqtt = false,
                priority = priority.value,
            )
        try {
            commandSender.sendData(packet)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.e(e) { "failed to send packet" }
            throw e
        }
    }

    private suspend fun sendStartAck(dest: String, transferId: Int, cachedChunks: Int) {
        val ack = MftStartAck(transferId, cachedChunks)
        val encoded = ack.encode()
        sendRawPacket(dest, encoded, priority = MeshPacket.Priority.MAX)
        delay(activeTimingProfile.cancelBurstDelayMs)
        sendRawPacket(dest, encoded, priority = MeshPacket.Priority.MAX)
    }

    private suspend fun sendCancelPacket(dest: String, transferId: Int, reason: Byte) {
        if (dest.isEmpty()) return
        val cancel = MftCancel(transferId, reason)
        try {
            sendRawPacket(dest, cancel.encode(), priority = MeshPacket.Priority.MAX)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.w(e) { "failed to send cancel packet" }
        }
    }

    // ──────────────────────── Utilities ────────────────────────

    private fun resetIncoming() {
        idleCheckJob?.cancel()
        idleCheckJob = null
        inTransferId = 0
        inFileName = ""
        inRawFileSize = 0
        inTotalChunks = 0
        inCrc32 = 0
        inIsGzip = false
        inChunkBuffer = mutableMapOf()
        inFromAddress = ""
        inCurrentPassNumber = 1
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
