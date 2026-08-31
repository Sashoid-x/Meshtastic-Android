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

/**
 * Wire protocol codec for Meshtastic File Transfer (MFT).
 *
 * All MFT packets are sent over `PortNum.PRIVATE_APP` (256) and distinguished from other traffic (e.g., monochrome
 * images) by a 4-byte magic signature: `MFT\x01`.
 *
 * Packet layout — every packet starts with [MAGIC] + [TYPE]:
 *
 * | Type   | Byte 4 | Fields                                                                    |
 * |--------|--------|---------------------------------------------------------------------------|
 * | START  | 0x01   | transferId(2), fileSize(4), totalChunks(2), crc32(4), nameLen(1), name(N) |
 * | DATA   | 0x02   | transferId(2), chunkIndex(2), payload(N)                                  |
 * | ACK    | 0x03   | transferId(2), chunkIndex(2), status(1)                                   |
 * | CANCEL | 0x04   | transferId(2), reason(1)                                                  |
 */
@Suppress("MagicNumber")
object MftProtocol {

    /** 4-byte magic identifying MFT packets inside PRIVATE_APP payload. */
    val MAGIC = byteArrayOf(0x4D, 0x46, 0x54, 0x01) // "MFT\x01"

    const val TYPE_START: Byte = 0x01
    const val TYPE_DATA: Byte = 0x02
    const val TYPE_ACK: Byte = 0x03
    const val TYPE_CANCEL: Byte = 0x04

    const val ACK_OK: Byte = 0x00
    const val ACK_ERROR: Byte = 0x01

    const val CANCEL_USER: Byte = 0x00
    const val CANCEL_SIZE_LIMIT: Byte = 0x01
    const val CANCEL_INTEGRITY: Byte = 0x02

    /** Maximum file size in bytes (500 KB). */
    const val MAX_FILE_SIZE = 500 * 1024

    /**
     * Safe payload size per chunk. `Constants.DATA_PAYLOAD_LEN` is 237 but the [Data] proto wrapper adds overhead. We
     * reserve 9 bytes for the MFT header (magic 4 + type 1 + transferId 2 + chunkIndex 2) leaving ≈200 bytes of file
     * data per packet.
     */
    const val CHUNK_PAYLOAD_SIZE = 200

    /** Sentinel chunkIndex used in ACK to acknowledge the START packet. */
    const val ACK_START_INDEX = 0xFFFF

    // ──────────────────────── helpers ────────────────────────

    fun isMftPacket(payload: ByteArray): Boolean = payload.size >= MAGIC.size &&
        payload[0] == MAGIC[0] &&
        payload[1] == MAGIC[1] &&
        payload[2] == MAGIC[2] &&
        payload[3] == MAGIC[3]

    fun packetType(payload: ByteArray): Byte = if (payload.size > MAGIC.size) payload[MAGIC.size] else -1
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
                MftProtocol.TYPE_DATA -> MftData.decode(payload)
                MftProtocol.TYPE_ACK -> MftAck.decode(payload)
                MftProtocol.TYPE_CANCEL -> MftCancel.decode(payload)
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
) : MftPacket {

    @Suppress("MagicNumber")
    override fun encode(): ByteArray {
        val nameBytes = fileName.encodeToByteArray()
        val size = MftProtocol.MAGIC.size + 1 + 2 + 4 + 2 + 4 + 1 + nameBytes.size
        val buf = ByteArray(size)
        var pos = 0
        MftProtocol.MAGIC.copyInto(buf, pos)
        pos += MftProtocol.MAGIC.size
        buf[pos++] = MftProtocol.TYPE_START
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
            // magic(4) + type(1) + transferId(2) + fileSize(4) + totalChunks(2) + crc32(4) + nameLen(1) = 18
            if (payload.size < 18) return null
            var pos = MftProtocol.MAGIC.size + 1
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
            return MftStart(transferId, fileSize, totalChunks, crc32, fileName)
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
            // magic(4) + type(1) + transferId(2) + chunkIndex(2) = 9
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

/** Chunk acknowledgement packet. */
data class MftAck(override val transferId: Int, val chunkIndex: Int, val status: Byte) : MftPacket {

    @Suppress("MagicNumber")
    override fun encode(): ByteArray {
        val size = MftProtocol.MAGIC.size + 1 + 2 + 2 + 1
        val buf = ByteArray(size)
        var pos = 0
        MftProtocol.MAGIC.copyInto(buf, pos)
        pos += MftProtocol.MAGIC.size
        buf[pos++] = MftProtocol.TYPE_ACK
        buf.putUInt16(pos, transferId)
        pos += 2
        buf.putUInt16(pos, chunkIndex)
        pos += 2
        buf[pos] = status
        return buf
    }

    companion object {
        @Suppress("MagicNumber")
        fun decode(payload: ByteArray): MftAck? {
            // magic(4) + type(1) + transferId(2) + chunkIndex(2) + status(1) = 10
            if (payload.size < 10) return null
            var pos = MftProtocol.MAGIC.size + 1
            val transferId = payload.getUInt16(pos)
            pos += 2
            val chunkIndex = payload.getUInt16(pos)
            pos += 2
            val status = payload[pos]
            return MftAck(transferId, chunkIndex, status)
        }
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
