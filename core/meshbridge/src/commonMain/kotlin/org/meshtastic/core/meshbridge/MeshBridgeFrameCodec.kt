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

import okio.Buffer
import okio.BufferedSource

sealed interface ClientFrame {
    data class Payload(val data: ByteArray) : ClientFrame {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Payload && data.contentEquals(other.data))

        override fun hashCode(): Int = data.contentHashCode()
    }

    data object Ping : ClientFrame

    data class Reject(val reason: String, val shouldClose: Boolean) : ClientFrame
}

data class ServerFrame(val from: Int, val to: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ServerFrame) return false
        return from == other.from && to == other.to && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = from
        result = 31 * result + to
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

object MeshBridgeFrameCodec {

    /** Encodes a payload into a Client to Server frame: (u32 LE length, payload bytes). */
    fun encodeClientFrame(payload: ByteArray): ByteArray {
        val buffer = Buffer()
        buffer.writeIntLe(payload.size)
        buffer.write(payload)
        return buffer.readByteArray()
    }

    /**
     * Decodes a Client -> Server frame from a [BufferedSource].
     *
     * - Returns [ClientFrame.Ping] if `length == 0`.
     * - Returns [ClientFrame.Reject] with `shouldClose = true` if `length > MAX_FRAME_BYTES` or `length >
     *   MAX_PAYLOAD_BYTES`.
     * - Returns [ClientFrame.Payload] when 1 <= `length` <= MAX_PAYLOAD_BYTES.
     */
    @Suppress("ReturnCount")
    fun decodeClientFrame(source: BufferedSource): ClientFrame {
        if (source.exhausted()) return ClientFrame.Reject("Stream exhausted", shouldClose = true)
        val rawLength = source.readIntLe()
        if (rawLength == 0) {
            return ClientFrame.Ping
        }
        val length = rawLength.toLong() and MeshBridgeDefaults.UINT32_MASK
        if (length > MeshBridgeDefaults.MAX_FRAME_BYTES) {
            return ClientFrame.Reject(
                reason = "Frame length $length exceeds MAX_FRAME_BYTES (${MeshBridgeDefaults.MAX_FRAME_BYTES})",
                shouldClose = true,
            )
        }
        if (length > MeshBridgeDefaults.MAX_PAYLOAD_BYTES) {
            return ClientFrame.Reject(
                reason = "Payload length $length exceeds MAX_PAYLOAD_BYTES (${MeshBridgeDefaults.MAX_PAYLOAD_BYTES})",
                shouldClose = true,
            )
        }
        val bytes = source.readByteArray(length)
        return ClientFrame.Payload(bytes)
    }

    /**
     * Encodes a Server to Client frame: (u32 LE length, u32 LE from, u32 LE to, payload bytes) where `length = 8 +
     * payload.size`.
     */
    fun encodeServerFrame(from: Int, to: Int, payload: ByteArray): ByteArray {
        val buffer = Buffer()
        buffer.writeIntLe(MeshBridgeDefaults.SERVER_FRAME_HEADER_BYTES + payload.size)
        buffer.writeIntLe(from)
        buffer.writeIntLe(to)
        buffer.write(payload)
        return buffer.readByteArray()
    }

    /** Decodes a Server -> Client frame from a [BufferedSource]. */
    fun decodeServerFrame(source: BufferedSource): ServerFrame {
        val length = source.readIntLe().toLong() and MeshBridgeDefaults.UINT32_MASK
        require(length >= MeshBridgeDefaults.SERVER_FRAME_HEADER_BYTES) {
            "Invalid server frame length: $length (expected >= ${MeshBridgeDefaults.SERVER_FRAME_HEADER_BYTES})"
        }
        val from = source.readIntLe()
        val to = source.readIntLe()
        val payloadLength = (length - MeshBridgeDefaults.SERVER_FRAME_HEADER_BYTES).toInt()
        val payload = source.readByteArray(payloadLength.toLong())
        return ServerFrame(from = from, to = to, payload = payload)
    }
}
