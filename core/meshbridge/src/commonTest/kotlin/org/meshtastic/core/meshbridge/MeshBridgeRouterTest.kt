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

import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeshBridgeRouterTest {

    // --- Routing Table Tests (all 5 rows) ---

    @Test
    fun `row 1 presence body0 0x50 with wrapperTo 0 routes to broadcast`() {
        val body = byteArrayOf(MeshBridgeDefaults.MAGIC_PRESENCE, 0x01, 0x00, 0x04, 't'.code.toByte())
        val peers = mapOf(0x11223344 to 0x77889900)

        val route =
            MeshBridgeRouter.route(
                wrapperFrom = 0x12345678,
                wrapperTo = 0,
                body = body,
                peers = { peers[it] },
            )

        assertEquals(MeshBridgeRoute.Broadcast, route)
    }

    @Test
    fun `row 1 presence body0 0x50 with non-zero wrapperTo routes to broadcast`() {
        val body = byteArrayOf(MeshBridgeDefaults.MAGIC_PRESENCE, 0x01, 0x00)
        val peers = mapOf(0x11223344 to 0x77889900)

        val route =
            MeshBridgeRouter.route(
                wrapperFrom = 0x12345678,
                wrapperTo = 0x11223344,
                body = body,
                peers = { peers[it] },
            )

        assertEquals(MeshBridgeRoute.Broadcast, route)
    }

    @Test
    fun `row 2 envelope body0 0x47 with wrapperTo 0 routes to broadcast`() {
        val body = byteArrayOf(MeshBridgeDefaults.MAGIC_ENVELOPE, 0x10, 0x20)
        val peers = mapOf(0x11223344 to 0x77889900)

        val route =
            MeshBridgeRouter.route(
                wrapperFrom = 0x12345678,
                wrapperTo = 0,
                body = body,
                peers = { peers[it] },
            )

        assertEquals(MeshBridgeRoute.Broadcast, route)
    }

    @Test
    fun `row 3 envelope body0 0x47 with wrapperTo in map routes to unicast DM`() {
        val body = byteArrayOf(MeshBridgeDefaults.MAGIC_ENVELOPE, 0x01, 0x02)
        val peers = mapOf(0x11223344 to 0x77889900)

        val route =
            MeshBridgeRouter.route(
                wrapperFrom = 0x12345678,
                wrapperTo = 0x11223344,
                body = body,
                peers = { peers[it] },
            )

        val unicast = assertIs<MeshBridgeRoute.Unicast>(route)
        assertEquals(0x77889900, unicast.nodeNum)
    }

    @Test
    fun `row 4 envelope body0 0x47 with wrapperTo not in map routes to broadcast fallback`() {
        val body = byteArrayOf(MeshBridgeDefaults.MAGIC_ENVELOPE, 0x01, 0x02)
        val peers = emptyMap<Int, Int>()

        val route =
            MeshBridgeRouter.route(
                wrapperFrom = 0x12345678,
                wrapperTo = 0x99887766.toInt(),
                body = body,
                peers = { peers[it] },
            )

        assertEquals(MeshBridgeRoute.Broadcast, route)
    }

    @Test
    fun `row 5 other body0 routes to broadcast`() {
        val body = byteArrayOf(0x00, 0x11, 0x22)
        val peers = mapOf(0x11223344 to 0x77889900)

        val route =
            MeshBridgeRouter.route(
                wrapperFrom = 0x12345678,
                wrapperTo = 0x11223344,
                body = body,
                peers = { peers[it] },
            )

        assertEquals(MeshBridgeRoute.Broadcast, route)
    }

    @Test
    fun `empty or null body routes to broadcast`() {
        val peers = mapOf(0x11223344 to 0x77889900)

        assertEquals(
            MeshBridgeRoute.Broadcast,
            MeshBridgeRouter.route(0x1234, 0x11223344, null) { peers[it] },
        )
        assertEquals(
            MeshBridgeRoute.Broadcast,
            MeshBridgeRouter.route(0x1234, 0x11223344, byteArrayOf()) { peers[it] },
        )
    }

    // --- Peer Map Tests ---

    @Test
    fun `peer map learns from RX wrapper`() = runTest {
        val peerMap = MeshBridgePeerMap()

        // Build RX payload: [u32 from LE = 0x12345678][u32 to LE = 0][body = 0x50...]
        val buffer = Buffer()
        buffer.writeIntLe(0x12345678)
        buffer.writeIntLe(0)
        buffer.writeByte(MeshBridgeDefaults.MAGIC_PRESENCE.toInt())
        val rxPayload = buffer.readByteArray()

        val loraSenderNode = 0x00AABBCC

        val learned = peerMap.learnFromRxWrapper(rxPayload, loraSenderNode)
        assertTrue(learned)
        assertEquals(loraSenderNode, peerMap.get(0x12345678))
    }

    @Test
    fun `peer map rejects learning from payload shorter than 8 bytes`() = runTest {
        val peerMap = MeshBridgePeerMap()
        val shortPayload = byteArrayOf(1, 2, 3, 4)

        val learned = peerMap.learnFromRxWrapper(shortPayload, 0x1234)
        assertFalse(learned)
        assertEquals(0, peerMap.size())
    }

    @Test
    fun `peer map overwrites node for same clientId`() = runTest {
        val peerMap = MeshBridgePeerMap()

        peerMap.put(clientId = 42, nodeNum = 0x1111)
        assertEquals(0x1111, peerMap.get(42))
        assertEquals(1, peerMap.size())

        // Same client, new node
        peerMap.put(clientId = 42, nodeNum = 0x2222)
        assertEquals(0x2222, peerMap.get(42))
        assertEquals(1, peerMap.size())
    }

    @Test
    fun `peer map LRU evicts oldest entry on 33rd record`() = runTest {
        val peerMap = MeshBridgePeerMap(maxEntries = 32)

        // Insert 32 items: key 1..32
        for (i in 1..32) {
            peerMap.put(clientId = i, nodeNum = i * 100)
        }
        assertEquals(32, peerMap.size())

        // All 32 are present
        for (i in 1..32) {
            assertEquals(i * 100, peerMap.get(i))
        }

        // Now key 1 was accessed first among 1..32, key 32 accessed last.
        // Let's reset order cleanly by re-inserting without get calls:
        peerMap.clear()
        for (i in 1..32) {
            peerMap.put(clientId = i, nodeNum = i * 10)
        }

        // Insert 33rd record
        peerMap.put(clientId = 33, nodeNum = 330)

        assertEquals(32, peerMap.size())
        // Oldest entry (1) must be evicted
        assertNull(peerMap.get(1))
        // Entries 2..33 must still exist
        for (i in 2..33) {
            assertEquals(i * 10, peerMap.get(i))
        }
    }

    @Test
    fun `peer map get updates LRU access order`() = runTest {
        val peerMap = MeshBridgePeerMap(maxEntries = 32)

        // Insert 32 items
        for (i in 1..32) {
            peerMap.put(clientId = i, nodeNum = i * 10)
        }

        // Access entry 1, making it most recently used
        assertEquals(10, peerMap.get(1))

        // Insert 33rd item -> entry 2 should be evicted, not 1!
        peerMap.put(clientId = 33, nodeNum = 330)

        assertEquals(32, peerMap.size())
        assertEquals(10, peerMap.get(1)) // 1 was retained!
        assertNull(peerMap.get(2)) // 2 was evicted!
    }
}
