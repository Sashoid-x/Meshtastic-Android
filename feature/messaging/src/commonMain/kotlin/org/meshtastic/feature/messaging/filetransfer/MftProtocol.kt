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

import okio.Buffer
import okio.GzipSink
import okio.GzipSource
import okio.buffer

/**
 * Wire protocol codec for Meshtastic File Transfer (MFT).
 *
 * All MFT packets are sent over `PortNum.PRIVATE_APP` (256) and distinguished from other traffic (e.g., monochrome
 * images) by a 4-byte magic signature: `MFT\x01`.
 *
 * Packet layout — every packet starts with [MAGIC] + [TYPE]:
 *
 * | Type    | Byte 4 | Fields                                                                              |
 * |---------|--------|-------------------------------------------------------------------------------------|
 * | START   | 0x01   | flags(1), transferId(2), fileSize(4), totalChunks(2), crc32(4), nameLen(1), name(N) |
 * | DATA    | 0x02   | transferId(2), chunkIndex(2), payload(N)                                            |
 * | SACK    | 0x03   | transferId(2), baseChunkIndex(2), mask(4)                                           |
 * | CANCEL  | 0x04   | transferId(2), reason(1)                                                            |
 * | ACK_REQ | 0x05   | transferId(2), baseChunkIndex(2)                                                    |
 */
@Suppress("MagicNumber")
object MftProtocol {

    /** 4-byte magic identifying MFT packets inside PRIVATE_APP payload. */
    val MAGIC = byteArrayOf(0x4D, 0x46, 0x54, 0x01) // "MFT\x01"

    const val TYPE_START: Byte = 0x01
    const val TYPE_START_ACK: Byte = 0x02
    const val TYPE_DATA: Byte = 0x03
    const val TYPE_PASS_END: Byte = 0x04
    const val TYPE_MISSING: Byte = 0x05
    const val TYPE_COMPLETE: Byte = 0x06
    const val TYPE_CANCEL: Byte = 0x07
    const val TYPE_PROGRESS: Byte = 0x08

    const val FLAG_NONE: Byte = 0x00
    const val FLAG_GZIP: Byte = 0x01

    const val CANCEL_USER: Byte = 0x00
    const val CANCEL_SIZE_LIMIT: Byte = 0x01
    const val CANCEL_INTEGRITY: Byte = 0x02

    /** Maximum file size in bytes (500 KB). */
    const val MAX_FILE_SIZE = 500 * 1024

    /**
     * Safe payload size per chunk. We reserve 9 bytes for the MFT header (magic 4 + type 1 + transferId 2 +
     * chunkIndex 2) leaving 200 bytes of file data per packet. Fits within SX1262 LoRa 256-byte hardware frame.
     */
    const val CHUNK_PAYLOAD_SIZE = 200

    /** Maximum missing chunk indices packed into a single [MftMissing] packet. */
    const val MAX_MISSING_PER_PACKET = 90

    // ──────────────────────── helpers ────────────────────────

    fun isMftPacket(payload: ByteArray): Boolean = payload.size >= MAGIC.size &&
        payload[0] == MAGIC[0] &&
        payload[1] == MAGIC[1] &&
        payload[2] == MAGIC[2] &&
        payload[3] == MAGIC[3]

    fun packetType(payload: ByteArray): Byte = if (payload.size > MAGIC.size) payload[MAGIC.size] else -1

    /**
     * Calculates the interval of chunks after which the receiver emits a high-priority [MftProgress] packet. Returns 0
     * if the file has too few chunks to warrant intermediate progress reports.
     */
    fun progressReportInterval(totalChunks: Int): Int = when {
        totalChunks <= 8 -> 0
        totalChunks <= 20 -> 5
        totalChunks <= 50 -> 10
        totalChunks <= 100 -> 15
        else -> 20
    }
}

// ──────────────────────── Sealed packet hierarchy ────────────────────────

sealed interface MftPacket {
    val transferId: Int // UInt16

    /** Serialize to a byte array suitable for [DataPacket.bytes]. */
    fun encode(): ByteArray

    companion object {
        fun decode(payload: ByteArray): MftPacket? {
            if (!MftProtocol.isMftPacket(payload)) return null
            return when (MftProtocol.packetType(payload)) {
                MftProtocol.TYPE_START -> MftStart.decode(payload)
                MftProtocol.TYPE_START_ACK -> MftStartAck.decode(payload)
                MftProtocol.TYPE_DATA -> MftData.decode(payload)
                MftProtocol.TYPE_PASS_END -> MftPassEnd.decode(payload)
                MftProtocol.TYPE_MISSING -> MftMissing.decode(payload)
                MftProtocol.TYPE_COMPLETE -> MftComplete.decode(payload)
                MftProtocol.TYPE_CANCEL -> MftCancel.decode(payload)
                MftProtocol.TYPE_PROGRESS -> MftProgress.decode(payload)
                else -> null
            }
        }
    }
}

/** Transfer initiation packet carrying file metadata. */
data class MftStart(
    override val transferId: Int,
    val fileSize: Long,
    val totalChunks: Int,
    val crc32: Long,
    val fileName: String,
    val isGzip: Boolean = false,
) : MftPacket {

    @Suppress("MagicNumber")
    override fun encode(): ByteArray {
        val nameBytes = fileName.encodeToByteArray()
        val size = MftProtocol.MAGIC.size + 1 + 1 + 2 + 4 + 2 + 4 + 1 + nameBytes.size
        val buf = ByteArray(size)
        var pos = 0
        MftProtocol.MAGIC.copyInto(buf, pos)
        pos += MftProtocol.MAGIC.size
        buf[pos++] = MftProtocol.TYPE_START
        buf[pos++] = if (isGzip) MftProtocol.FLAG_GZIP else MftProtocol.FLAG_NONE
        buf.putUInt16(pos, transferId)
        pos += 2
        buf.putUInt32(pos, fileSize)
        pos += 4
        buf.putUInt16(pos, totalChunks)
        pos += 2
        buf.putUInt32(pos, crc32)
        pos += 4
        buf[pos++] = nameBytes.size.toByte()
        nameBytes.copyInto(buf, pos)
        return buf
    }

    companion object {
        @Suppress("MagicNumber", "ReturnCount")
        fun decode(payload: ByteArray): MftStart? {
            if (payload.size < 19) return null
            var pos = MftProtocol.MAGIC.size + 1
            val flags = payload[pos++]
            val isGzip = (flags.toInt() and MftProtocol.FLAG_GZIP.toInt()) != 0
            val transferId = payload.getUInt16(pos)
            pos += 2
            val fileSize = payload.getUInt32(pos)
            pos += 4
            val totalChunks = payload.getUInt16(pos)
            pos += 2
            val crc32 = payload.getUInt32(pos)
            pos += 4
            val nameLen = payload[pos++].toInt() and 0xFF
            if (payload.size < pos + nameLen) return null
            val fileName = payload.decodeToString(pos, pos + nameLen)
            return MftStart(transferId, fileSize, totalChunks, crc32, fileName, isGzip)
        }
    }
}

/** Explicit handshake response confirming receiver is ready to receive data. */
data class MftStartAck(override val transferId: Int, val cachedChunksCount: Int = 0) : MftPacket {

    @Suppress("MagicNumber")
    override fun encode(): ByteArray {
        val size = MftProtocol.MAGIC.size + 1 + 2 + 2
        val buf = ByteArray(size)
        var pos = 0
        MftProtocol.MAGIC.copyInto(buf, pos)
        pos += MftProtocol.MAGIC.size
        buf[pos++] = MftProtocol.TYPE_START_ACK
        buf.putUInt16(pos, transferId)
        pos += 2
        buf.putUInt16(pos, cachedChunksCount)
        return buf
    }

    companion object {
        @Suppress("MagicNumber")
        fun decode(payload: ByteArray): MftStartAck? {
            if (payload.size < 9) return null
            var pos = MftProtocol.MAGIC.size + 1
            val transferId = payload.getUInt16(pos)
            pos += 2
            val cached = payload.getUInt16(pos)
            return MftStartAck(transferId, cached)
        }
    }
}

/** A single file data chunk. */
data class MftData(override val transferId: Int, val chunkIndex: Int, val data: ByteArray) : MftPacket {

    @Suppress("MagicNumber")
    override fun encode(): ByteArray {
        val size = MftProtocol.MAGIC.size + 1 + 2 + 2 + data.size
        val buf = ByteArray(size)
        var pos = 0
        MftProtocol.MAGIC.copyInto(buf, pos)
        pos += MftProtocol.MAGIC.size
        buf[pos++] = MftProtocol.TYPE_DATA
        buf.putUInt16(pos, transferId)
        pos += 2
        buf.putUInt16(pos, chunkIndex)
        pos += 2
        data.copyInto(buf, pos)
        return buf
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MftData) return false
        return transferId == other.transferId && chunkIndex == other.chunkIndex && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = transferId
        result = 31 * result + chunkIndex
        result = 31 * result + data.contentHashCode()
        return result
    }

    companion object {
        @Suppress("MagicNumber")
        fun decode(payload: ByteArray): MftData? {
            if (payload.size < 9) return null
            var pos = MftProtocol.MAGIC.size + 1
            val transferId = payload.getUInt16(pos)
            pos += 2
            val chunkIndex = payload.getUInt16(pos)
            pos += 2
            val data = payload.copyOfRange(pos, payload.size)
            return MftData(transferId, chunkIndex, data)
        }
    }
}

/** Sender signals that all chunks for the current pass have been transmitted. */
data class MftPassEnd(override val transferId: Int, val passNumber: Int) : MftPacket {

    @Suppress("MagicNumber")
    override fun encode(): ByteArray {
        val size = MftProtocol.MAGIC.size + 1 + 2 + 1
        val buf = ByteArray(size)
        var pos = 0
        MftProtocol.MAGIC.copyInto(buf, pos)
        pos += MftProtocol.MAGIC.size
        buf[pos++] = MftProtocol.TYPE_PASS_END
        buf.putUInt16(pos, transferId)
        pos += 2
        buf[pos] = (passNumber and 0xFF).toByte()
        return buf
    }

    companion object {
        @Suppress("MagicNumber")
        fun decode(payload: ByteArray): MftPassEnd? {
            if (payload.size < 8) return null
            var pos = MftProtocol.MAGIC.size + 1
            val transferId = payload.getUInt16(pos)
            pos += 2
            val passNumber = payload[pos].toInt() and 0xFF
            return MftPassEnd(transferId, passNumber)
        }
    }
}

/** Receiver requests retransmission of specific missing chunk indices (hole-punching repair). */
data class MftMissing(
    override val transferId: Int,
    val passNumber: Int,
    val totalMissing: Int,
    val missingIndices: List<Int>,
) : MftPacket {

    @Suppress("MagicNumber")
    override fun encode(): ByteArray {
        val count = minOf(missingIndices.size, MftProtocol.MAX_MISSING_PER_PACKET)
        val size = MftProtocol.MAGIC.size + 1 + 2 + 1 + 2 + 2 + (count * 2)
        val buf = ByteArray(size)
        var pos = 0
        MftProtocol.MAGIC.copyInto(buf, pos)
        pos += MftProtocol.MAGIC.size
        buf[pos++] = MftProtocol.TYPE_MISSING
        buf.putUInt16(pos, transferId)
        pos += 2
        buf[pos++] = (passNumber and 0xFF).toByte()
        buf.putUInt16(pos, totalMissing)
        pos += 2
        buf.putUInt16(pos, count)
        pos += 2
        for (i in 0 until count) {
            buf.putUInt16(pos, missingIndices[i])
            pos += 2
        }
        return buf
    }

    companion object {
        @Suppress("MagicNumber", "ReturnCount")
        fun decode(payload: ByteArray): MftMissing? {
            if (payload.size < 12) return null
            var pos = MftProtocol.MAGIC.size + 1
            val transferId = payload.getUInt16(pos)
            pos += 2
            val passNumber = payload[pos++].toInt() and 0xFF
            val totalMissing = payload.getUInt16(pos)
            pos += 2
            val count = payload.getUInt16(pos)
            pos += 2
            if (payload.size < pos + (count * 2)) return null
            val indices = ArrayList<Int>(count)
            for (i in 0 until count) {
                indices.add(payload.getUInt16(pos))
                pos += 2
            }
            return MftMissing(transferId, passNumber, totalMissing, indices)
        }
    }
}

/** Receiver confirms that 100% of chunks have been received and verified. */
data class MftComplete(override val transferId: Int) : MftPacket {

    @Suppress("MagicNumber")
    override fun encode(): ByteArray {
        val size = MftProtocol.MAGIC.size + 1 + 2
        val buf = ByteArray(size)
        var pos = 0
        MftProtocol.MAGIC.copyInto(buf, pos)
        pos += MftProtocol.MAGIC.size
        buf[pos++] = MftProtocol.TYPE_COMPLETE
        buf.putUInt16(pos, transferId)
        return buf
    }

    companion object {
        @Suppress("MagicNumber")
        fun decode(payload: ByteArray): MftComplete? {
            if (payload.size < 7) return null
            var pos = MftProtocol.MAGIC.size + 1
            val transferId = payload.getUInt16(pos)
            return MftComplete(transferId)
        }
    }
}

/** Multiplatform Gzip compression helper. */
object MftCompression {
    fun compress(data: ByteArray): Pair<ByteArray, Boolean> = try {
        val buffer = Buffer()
        val gzipSink = GzipSink(buffer).buffer()
        gzipSink.write(data)
        gzipSink.close()
        val compressed = buffer.readByteArray()
        if (compressed.size < data.size) {
            Pair(compressed, true)
        } else {
            Pair(data, false)
        }
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
        Pair(data, false)
    }

    fun decompress(data: ByteArray): ByteArray {
        val buffer = Buffer().write(data)
        val gzipSource = GzipSource(buffer).buffer()
        return gzipSource.readByteArray()
    }
}

/** Transfer cancellation packet. */
data class MftCancel(override val transferId: Int, val reason: Byte) : MftPacket {

    @Suppress("MagicNumber")
    override fun encode(): ByteArray {
        val size = MftProtocol.MAGIC.size + 1 + 2 + 1
        val buf = ByteArray(size)
        var pos = 0
        MftProtocol.MAGIC.copyInto(buf, pos)
        pos += MftProtocol.MAGIC.size
        buf[pos++] = MftProtocol.TYPE_CANCEL
        buf.putUInt16(pos, transferId)
        pos += 2
        buf[pos] = reason
        return buf
    }

    companion object {
        @Suppress("MagicNumber")
        fun decode(payload: ByteArray): MftCancel? {
            // magic(4) + type(1) + transferId(2) + reason(1) = 8
            if (payload.size < 8) return null
            var pos = MftProtocol.MAGIC.size + 1
            val transferId = payload.getUInt16(pos)
            pos += 2
            val reason = payload[pos]
            return MftCancel(transferId, reason)
        }
    }
}

/** Intermediate progress report sent by receiver to confirm reception of chunks. */
data class MftProgress(override val transferId: Int, val receivedChunks: Int, val totalChunks: Int) : MftPacket {

    @Suppress("MagicNumber")
    override fun encode(): ByteArray {
        val size = MftProtocol.MAGIC.size + 1 + 2 + 2 + 2
        val buf = ByteArray(size)
        var pos = 0
        MftProtocol.MAGIC.copyInto(buf, pos)
        pos += MftProtocol.MAGIC.size
        buf[pos++] = MftProtocol.TYPE_PROGRESS
        buf.putUInt16(pos, transferId)
        pos += 2
        buf.putUInt16(pos, receivedChunks)
        pos += 2
        buf.putUInt16(pos, totalChunks)
        return buf
    }

    companion object {
        @Suppress("MagicNumber")
        fun decode(payload: ByteArray): MftProgress? {
            // magic(4) + type(1) + transferId(2) + receivedChunks(2) + totalChunks(2) = 11
            if (payload.size < 11) return null
            var pos = MftProtocol.MAGIC.size + 1
            val transferId = payload.getUInt16(pos)
            pos += 2
            val received = payload.getUInt16(pos)
            pos += 2
            val total = payload.getUInt16(pos)
            return MftProgress(transferId, received, total)
        }
    }
}

// ──────────────────────── ByteArray extensions (big-endian) ────────────────────────

@Suppress("MagicNumber")
internal fun ByteArray.putUInt16(offset: Int, value: Int) {
    this[offset] = (value shr 8 and 0xFF).toByte()
    this[offset + 1] = (value and 0xFF).toByte()
}

@Suppress("MagicNumber")
internal fun ByteArray.getUInt16(offset: Int): Int =
    (this[offset].toInt() and 0xFF shl 8) or (this[offset + 1].toInt() and 0xFF)

@Suppress("MagicNumber")
internal fun ByteArray.putUInt32(offset: Int, value: Long) {
    this[offset] = (value shr 24 and 0xFF).toByte()
    this[offset + 1] = (value shr 16 and 0xFF).toByte()
    this[offset + 2] = (value shr 8 and 0xFF).toByte()
    this[offset + 3] = (value and 0xFF).toByte()
}

@Suppress("MagicNumber")
internal fun ByteArray.getUInt32(offset: Int): Long = (this[offset].toLong() and 0xFF shl 24) or
    (this[offset + 1].toLong() and 0xFF shl 16) or
    (this[offset + 2].toLong() and 0xFF shl 8) or
    (this[offset + 3].toLong() and 0xFF)
