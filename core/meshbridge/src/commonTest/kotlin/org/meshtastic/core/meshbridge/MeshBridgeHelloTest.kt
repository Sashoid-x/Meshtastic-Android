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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MeshBridgeHelloTest {

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun `golden bytes - nodeId and name`() {
        val nodeId = 0x12345678
        val name = "TEST"
        val frame = MeshBridgeHello.buildHelloFrame(nodeId = nodeId, name = name)

        // Expected wire format:
        // len = 16 + 7 + 4 = 27 (0x1B) -> 1B 00 00 00
        // loraFrom = 0 -> 00 00 00 00
        // loraTo = 0 -> 00 00 00 00
        // from = 0 -> 00 00 00 00
        // to = 0 -> 00 00 00 00
        // magic = 0x49 ('I') -> 49
        // ver = 1 -> 01
        // nodeId (0x12345678 LE) -> 78 56 34 12
        // nameLen = 4 -> 04
        // name ("TEST") -> 54 45 53 54
        val expectedHex = "1b000000000000000000000000000000000000004901785634120454455354"
        val expectedBytes = expectedHex.hexToByteArray()

        assertEquals(31, frame.size)
        assertContentEquals(expectedBytes, frame)

        // Verify with buffer reader
        val buffer = Buffer().write(frame)
        val len = buffer.readIntLe()
        assertEquals(27, len)
        val loraFrom = buffer.readIntLe()
        assertEquals(0, loraFrom)
        val loraTo = buffer.readIntLe()
        assertEquals(0, loraTo)
        val from = buffer.readIntLe()
        assertEquals(0, from)
        val to = buffer.readIntLe()
        assertEquals(0, to)
        val magic = buffer.readByte()
        assertEquals(0x49.toByte(), magic)
        val ver = buffer.readByte()
        assertEquals(1.toByte(), ver)
        val decodedNodeId = buffer.readIntLe()
        assertEquals(nodeId, decodedNodeId)
        val nameLen = buffer.readByte().toInt() and 0xFF
        assertEquals(4, nameLen)
        val decodedName = buffer.readUtf8(nameLen.toLong())
        assertEquals("TEST", decodedName)
        assertEquals(0, buffer.size)
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun `golden bytes - empty or null name yields nameLen 0`() {
        val nodeId = 0x12345678
        val frame = MeshBridgeHello.buildHelloFrame(nodeId = nodeId, name = null)

        // Expected wire format:
        // len = 16 + 7 + 0 = 23 (0x17) -> 17 00 00 00
        // loraFrom = 0 -> 00 00 00 00
        // loraTo = 0 -> 00 00 00 00
        // from = 0 -> 00 00 00 00
        // to = 0 -> 00 00 00 00
        // magic = 0x49 -> 49
        // ver = 1 -> 01
        // nodeId (0x12345678 LE) -> 78 56 34 12
        // nameLen = 0 -> 00
        val expectedHex = "170000000000000000000000000000000000000049017856341200"
        val expectedBytes = expectedHex.hexToByteArray()

        assertEquals(27, frame.size)
        assertContentEquals(expectedBytes, frame)

        val buffer = Buffer().write(frame)
        assertEquals(23, buffer.readIntLe())
        assertEquals(0, buffer.readIntLe())
        assertEquals(0, buffer.readIntLe())
        assertEquals(0, buffer.readIntLe())
        assertEquals(0, buffer.readIntLe())
        assertEquals(0x49.toByte(), buffer.readByte())
        assertEquals(1.toByte(), buffer.readByte())
        assertEquals(nodeId, buffer.readIntLe())
        assertEquals(0.toByte(), buffer.readByte())
        assertEquals(0, buffer.size)
    }

    @Test
    fun `truncateUtf8 - 40 bytes continuous ascii truncated to 32 bytes`() {
        val input = "A".repeat(40)
        val truncated = MeshBridgeHello.truncateUtf8(input, maxBytes = 32)
        assertEquals(32, truncated.size)
        assertEquals("A".repeat(32), truncated.decodeToString())
    }

    @Test
    fun `truncateUtf8 - multibyte boundary prevents severed continuation byte`() {
        // 31 ASCII characters ('A') + 2-byte Cyrillic character ('ж' = 0xD0, 0xB6) -> 33 bytes
        val input = "A".repeat(31) + "ж"
        val inputBytes = input.encodeToByteArray()
        assertEquals(33, inputBytes.size)

        val truncated = MeshBridgeHello.truncateUtf8(input, maxBytes = 32)
        // 'ж' cannot fit completely in 32 bytes (would take byte 31 and 32), so it must be dropped
        assertEquals(31, truncated.size)
        assertEquals("A".repeat(31), truncated.decodeToString())
    }

    @Test
    fun `truncateUtf8 - 3-byte character boundary preserves whole character when fits`() {
        // 29 ASCII bytes + 3-byte character '世' (0xE4, 0xB8, 0x96) = 32 bytes total
        val input = "A".repeat(29) + "世"
        val inputBytes = input.encodeToByteArray()
        assertEquals(32, inputBytes.size)

        val truncated = MeshBridgeHello.truncateUtf8(input, maxBytes = 32)
        assertEquals(32, truncated.size)
        assertEquals(input, truncated.decodeToString())

        // 30 ASCII bytes + 3-byte character '世' = 33 bytes total -> '世' must be dropped
        val inputOver = "A".repeat(30) + "世"
        val truncatedOver = MeshBridgeHello.truncateUtf8(inputOver, maxBytes = 32)
        assertEquals(30, truncatedOver.size)
        assertEquals("A".repeat(30), truncatedOver.decodeToString())
    }

    @Test
    fun `truncateUtf8 - empty or null yields empty byte array`() {
        val emptyTruncated = MeshBridgeHello.truncateUtf8("", maxBytes = 32)
        assertEquals(0, emptyTruncated.size)

        val nullTruncated = MeshBridgeHello.truncateUtf8(null as String?, maxBytes = 32)
        assertEquals(0, nullTruncated.size)
    }

    @Test
    fun `resolveNodeName - preference order and blank handling`() {
        // longName preferred
        assertEquals("Long Name", MeshBridgeHello.resolveNodeName(longName = "Long Name", shortName = "Short"))

        // empty longName falls back to shortName
        assertEquals("Short", MeshBridgeHello.resolveNodeName(longName = "", shortName = "Short"))
        assertEquals("Short", MeshBridgeHello.resolveNodeName(longName = "   ", shortName = "Short"))
        assertEquals("Short", MeshBridgeHello.resolveNodeName(longName = null, shortName = "Short"))

        // both blank or null returns null
        assertNull(MeshBridgeHello.resolveNodeName(longName = "", shortName = ""))
        assertNull(MeshBridgeHello.resolveNodeName(longName = "  ", shortName = "  "))
        assertNull(MeshBridgeHello.resolveNodeName(longName = null, shortName = null))
    }
}
