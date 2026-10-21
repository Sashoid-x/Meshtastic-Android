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
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MeshBridgeFrameCodecTest {

    @Test
    fun `client to server round trip`() {
        val payload = "hello meshgames".encodeToByteArray()
        val encoded = MeshBridgeFrameCodec.encodeClientFrame(payload)

        // Verify wire format: [4 bytes LE length][payload]
        assertEquals(4 + payload.size, encoded.size)
        val buffer = Buffer().write(encoded)
        val decoded = MeshBridgeFrameCodec.decodeClientFrame(buffer)

        assertIs<ClientFrame.Payload>(decoded)
        assertContentEquals(payload, decoded.data)
        assertTrue(buffer.exhausted())
    }

    @Test
    fun `client to server ping frame length 0`() {
        val buffer = Buffer()
        buffer.writeIntLe(0) // length = 0

        val decoded = MeshBridgeFrameCodec.decodeClientFrame(buffer)
        assertIs<ClientFrame.Ping>(decoded)
        assertTrue(buffer.exhausted())
    }

    @Test
    fun `client to server payload exactly 220 bytes is accepted`() {
        val payload = ByteArray(220) { it.toByte() }
        val encoded = MeshBridgeFrameCodec.encodeClientFrame(payload)

        val buffer = Buffer().write(encoded)
        val decoded = MeshBridgeFrameCodec.decodeClientFrame(buffer)

        assertIs<ClientFrame.Payload>(decoded)
        assertContentEquals(payload, decoded.data)
        assertTrue(buffer.exhausted())
    }

    @Test
    fun `client to server payload 221 bytes is rejected`() {
        val buffer = Buffer()
        buffer.writeIntLe(221)
        buffer.write(ByteArray(221))

        val decoded = MeshBridgeFrameCodec.decodeClientFrame(buffer)
        assertIs<ClientFrame.Reject>(decoded)
        assertTrue(decoded.shouldClose)
    }

    @Test
    fun `client to server length greater than 1024 signals close`() {
        val buffer = Buffer()
        buffer.writeIntLe(1025)
        buffer.write(ByteArray(1025))

        val decoded = MeshBridgeFrameCodec.decodeClientFrame(buffer)
        assertIs<ClientFrame.Reject>(decoded)
        assertTrue(decoded.shouldClose)
    }

    @Test
    fun `server to client round trip`() {
        val from = 0x11223344
        val to = 0x55667788
        val payload = "pong meshgames".encodeToByteArray()

        val encoded = MeshBridgeFrameCodec.encodeServerFrame(from = from, to = to, payload = payload)

        // Wire format: [4 bytes LE length = 8 + payload.size][4 bytes LE from][4 bytes LE to][payload]
        assertEquals(12 + payload.size, encoded.size)

        val buffer = Buffer().write(encoded)
        val decoded = MeshBridgeFrameCodec.decodeServerFrame(buffer)

        assertEquals(from, decoded.from)
        assertEquals(to, decoded.to)
        assertContentEquals(payload, decoded.payload)
        assertTrue(buffer.exhausted())
    }
}
