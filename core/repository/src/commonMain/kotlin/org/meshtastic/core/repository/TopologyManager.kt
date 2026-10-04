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
package org.meshtastic.core.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.meshtastic.core.database.entity.TopologyEdge
import org.meshtastic.core.model.TopologySource
import org.meshtastic.proto.MeshPacket

/** Service for collecting, persisting, and observing network topology links between mesh nodes. */
interface TopologyManager {

    /** Observable flow of all persisted topology edges. */
    val allEdgesFlow: Flow<List<TopologyEdge>>

    /** Number of unique nodes discovered via MQTT links. */
    val activeMqttNodesCount: Flow<Int>

    /** Observable state indicating whether MQTT is currently active (connected or proxy active). */
    val isMqttActive: StateFlow<Boolean>

    /** Analyzes an incoming [packet] from the given [source] and updates topology edges accordingly. */
    fun processPacket(packet: MeshPacket, source: TopologySource, gatewayId: String? = null)

    /** Clears all recorded topology edges. */
    suspend fun clearAllEdges()
}
