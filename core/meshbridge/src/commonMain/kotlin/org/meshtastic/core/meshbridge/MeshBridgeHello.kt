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

/**
 * Pure functions for building and parsing Mesh Bridge HELLO control frames.
 *
 * Wire format (Server -> Client):
 * ```
 * [u32 len LE][u32 loraFrom LE = 0][u32 loraTo LE = 0]
 * [u32 from LE = 0][u32 to LE = 0][body…]
 * len = 16 + body.size
 * body = [0x49][ver = 1][nodeId u32 LE][nameLen u8][name UTF-8 <= 32]
 * ```
 */
object MeshBridgeHello {

    /**
     * Truncates [bytes] to at most [maxBytes] ensuring no multibyte UTF-8 sequence is severed.
     *
     * If the byte at or preceding [maxBytes] would leave an incomplete UTF-8 continuation sequence, the incomplete code
     * point is dropped entirely.
     */
    @Suppress("MagicNumber", "NestedBlockDepth", "ReturnCount")
    fun truncateUtf8(bytes: ByteArray, maxBytes: Int = MeshBridgeDefaults.MAX_HELLO_NAME_BYTES): ByteArray {
        if (bytes.size <= maxBytes) return bytes.copyOf()
        if (maxBytes <= 0) return ByteArray(0)

        // Find the start of the code point that crosses or ends at maxBytes
        var i = maxBytes - 1
        while (i >= 0 && (bytes[i].toInt() and 0xC0) == 0x80) {
            i--
        }
        if (i < 0) {
            // All bytes in 0 until maxBytes were continuation bytes (malformed UTF-8), drop all
            return ByteArray(0)
        }

        val lead = bytes[i].toInt() and 0xFF
        val charLen =
            when {
                lead and 0x80 == 0x00 -> 1
                lead and 0xE0 == 0xC0 -> 2
                lead and 0xF0 == 0xE0 -> 3
                lead and 0xF8 == 0xF0 -> 4
                else -> 1 // Malformed leading byte
            }

        val validLength =
            if (i + charLen <= maxBytes) {
                i + charLen
            } else {
                i
            }
        return bytes.copyOfRange(0, validLength)
    }

    /**
     * Truncates [name] to at most [maxBytes] of UTF-8, without breaking multibyte characters. Returns an empty byte
     * array if [name] is null or empty.
     */
    fun truncateUtf8(name: String?, maxBytes: Int = MeshBridgeDefaults.MAX_HELLO_NAME_BYTES): ByteArray {
        if (name.isNullOrEmpty()) return ByteArray(0)
        return truncateUtf8(name.encodeToByteArray(), maxBytes)
    }

    /**
     * Resolves node name for the HELLO frame: [longName] preferred; falls back to [shortName] if blank; returns null if
     * both are blank/null.
     */
    fun resolveNodeName(longName: String?, shortName: String?): String? =
        longName?.trim()?.takeIf { it.isNotEmpty() } ?: shortName?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Builds the complete Server -> Client HELLO frame as a [ByteArray].
     *
     * Wire layout:
     * - `[u32 len LE]` = 16 + body.size (where body.size = 7 + nameBytes.size)
     * - `[u32 loraFrom LE = 0]`
     * - `[u32 loraTo LE = 0]`
     * - `[u32 from LE = 0]`
     * - `[u32 to LE = 0]`
     * - `body = [0x49][ver = 1][nodeId u32 LE][nameLen u8][name UTF-8 <= 32]`
     */
    fun buildHelloFrame(nodeId: Int, name: String?): ByteArray {
        val nameBytes = truncateUtf8(name, MeshBridgeDefaults.MAX_HELLO_NAME_BYTES)
        val bodySize = MeshBridgeDefaults.HELLO_BODY_HEADER_BYTES + nameBytes.size
        val frameLen = MeshBridgeDefaults.SERVER_FRAME_FULL_HEADER_BYTES + bodySize

        val buffer = Buffer()
        buffer.writeIntLe(frameLen)
        buffer.writeIntLe(0) // loraFrom = 0
        buffer.writeIntLe(0) // loraTo = 0
        buffer.writeIntLe(0) // from = 0
        buffer.writeIntLe(0) // to = 0
        buffer.writeByte(MeshBridgeDefaults.MAGIC_HELLO.toInt())
        buffer.writeByte(MeshBridgeDefaults.HELLO_VERSION.toInt())
        buffer.writeIntLe(nodeId)
        buffer.writeByte(nameBytes.size)
        if (nameBytes.isNotEmpty()) {
            buffer.write(nameBytes)
        }
        return buffer.readByteArray()
    }

    /** Builds the complete Server -> Client HELLO frame, resolving [longName] and [shortName]. */
    fun buildHelloFrame(nodeId: Int, longName: String?, shortName: String?): ByteArray {
        val resolvedName = resolveNodeName(longName, shortName)
        return buildHelloFrame(nodeId, resolvedName)
    }
}
