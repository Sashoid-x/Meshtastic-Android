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

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Result of routing a Mesh Bridge client payload to the mesh network. */
sealed interface MeshBridgeRoute {
    /** Route to the broadcast address of the channel. */
    data object Broadcast : MeshBridgeRoute

    /** Route directly to a specific LoRa node (unicast / DM). */
    data class Unicast(val nodeNum: Int) : MeshBridgeRoute
}

/** Pure routing logic for Mesh Bridge client payloads. */
object MeshBridgeRouter {

    /** Reads a 32-bit integer in little-endian format from [bytes] at [offset]. */
    @Suppress("MagicNumber")
    fun readIntLe(bytes: ByteArray, offset: Int): Int {
        require(offset + 4 <= bytes.size) {
            "Cannot read Int LE: offset $offset + 4 exceeds byte array size ${bytes.size}"
        }
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    /**
     * Determines whether to route a payload via broadcast or unicast.
     *
     * @param wrapperFrom The sender client ID from the wrapper.
     * @param wrapperTo The target client ID from the wrapper (0 for broadcast).
     * @param body The body bytes (wrapper minus the 8-byte header), or null/empty.
     * @param peers Lookup function mapping a target client ID to a known LoRa node number.
     */
    @Suppress("UnusedParameter")
    fun route(wrapperFrom: Int, wrapperTo: Int, body: ByteArray?, peers: (clientId: Int) -> Int?): MeshBridgeRoute {
        if (body == null || body.isEmpty()) {
            return MeshBridgeRoute.Broadcast
        }
        val magic = body[0]
        return when {
            magic == MeshBridgeDefaults.MAGIC_PRESENCE -> {
                MeshBridgeRoute.Broadcast
            }

            magic == MeshBridgeDefaults.MAGIC_ENVELOPE -> {
                if (wrapperTo == 0) {
                    MeshBridgeRoute.Broadcast
                } else {
                    val destNode = peers(wrapperTo)
                    if (destNode != null) {
                        MeshBridgeRoute.Unicast(destNode)
                    } else {
                        MeshBridgeRoute.Broadcast
                    }
                }
            }

            else -> MeshBridgeRoute.Broadcast
        }
    }

    /** Overload of [route] that takes a single nullable destination node. */
    fun route(wrapperFrom: Int, wrapperTo: Int, body: ByteArray?, destNode: Int?): MeshBridgeRoute =
        route(wrapperFrom, wrapperTo, body) { id ->
            if (id == wrapperTo) destNode else null
        }
}

/**
 * In-memory LRU cache mapping client IDs to LoRa node numbers.
 *
 * Evicts the least recently used entry when capacity exceeds [maxEntries] (default 32). Thread-safe via [Mutex].
 */
class MeshBridgePeerMap(val maxEntries: Int = MeshBridgeDefaults.MAX_PEER_CACHE_SIZE) {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<Int, Int>()

    /** Retrieves the LoRa node number for [clientId], updating its LRU position. */
    suspend fun get(clientId: Int): Int? = mutex.withLock {
        val nodeNum = entries.remove(clientId) ?: return null
        entries[clientId] = nodeNum
        nodeNum
    }

    /** Associates [clientId] with [nodeNum], evicting the oldest entry if size exceeds [maxEntries]. */
    suspend fun put(clientId: Int, nodeNum: Int): Unit = mutex.withLock {
        entries.remove(clientId)
        if (entries.size >= maxEntries) {
            val oldestKey = entries.keys.firstOrNull()
            if (oldestKey != null) {
                entries.remove(oldestKey)
            }
        }
        entries[clientId] = nodeNum
    }

    /**
     * Attempts to learn peer mapping from an incoming mesh payload.
     *
     * If [payload] has at least [MeshBridgeDefaults.MIN_WRAPPER_HEADER_BYTES] (8 bytes), extracts `wrapper.from` and
     * maps it to [loraFrom].
     *
     * @return `true` if mapping was learned, `false` if payload was too short.
     */
    suspend fun learnFromRxWrapper(payload: ByteArray, loraFrom: Int): Boolean {
        if (payload.size < MeshBridgeDefaults.MIN_WRAPPER_HEADER_BYTES) return false
        val clientFrom = MeshBridgeRouter.readIntLe(payload, MeshBridgeDefaults.WRAPPER_OFFSET_FROM)
        put(clientFrom, loraFrom)
        return true
    }

    suspend fun size(): Int = mutex.withLock { entries.size }

    suspend fun clear(): Unit = mutex.withLock { entries.clear() }
}
