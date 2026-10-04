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
package org.meshtastic.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow
import org.meshtastic.core.database.entity.TopologyEdge
import org.meshtastic.core.model.TopologySource

/** Data Access Object for [TopologyEdge] network topology records. */
@Dao
interface TopologyEdgeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEdge(edge: TopologyEdge)

    @Query(
        "SELECT * FROM topology_edge " +
            "WHERE node1 != 0 AND node2 != 0 AND node1 != -1 AND node2 != -1 " +
            "ORDER BY last_seen DESC",
    )
    fun getAllEdgesFlow(): Flow<List<TopologyEdge>>

    @Query(
        "SELECT COUNT(DISTINCT node) FROM (" +
            "SELECT node1 AS node FROM topology_edge WHERE source = 'MQTT' AND node1 != 0 AND node1 != -1 " +
            "UNION " +
            "SELECT node2 AS node FROM topology_edge WHERE source = 'MQTT' AND node2 != 0 AND node2 != -1" +
            ")",
    )
    fun getActiveMqttNodesCount(): Flow<Int>

    @Query("SELECT * FROM topology_edge WHERE node1 = :node1 AND node2 = :node2 LIMIT 1")
    suspend fun getEdge(node1: Int, node2: Int): TopologyEdge?

    @Query("DELETE FROM topology_edge WHERE node1 = :node1 AND node2 = :node2")
    suspend fun deleteEdge(node1: Int, node2: Int)

    @Query("DELETE FROM topology_edge WHERE node1 = 0 OR node2 = 0 OR node1 = -1 OR node2 = -1")
    suspend fun deleteInvalidEdges()

    @Query("DELETE FROM topology_edge")
    suspend fun clearAllEdges()

    /**
     * Upserts an edge with smart priority logic:
     * - If edge does not exist, insert it.
     * - If edge already exists:
     *     - [bestSnr] and [bestRssi] are updated if the new value is better (higher), OR if the new source is
     *       [TopologySource.MQTT]. Real measurements never get overwritten by 0 (unknown).
     *     - Source is elevated to [TopologySource.MQTT] if either edge was observed via MQTT.
     *     - [lastSeen] is always set to the newest timestamp.
     */
    suspend fun upsertEdgeWithPriority(edge: TopologyEdge) {
        val existing = getEdge(edge.node1, edge.node2)
        if (existing == null) {
            upsertEdge(edge)
        } else {
            val newSnr =
                when {
                    edge.bestSnr != 0.0f && existing.bestSnr == 0.0f -> edge.bestSnr
                    edge.bestSnr == 0.0f -> existing.bestSnr
                    edge.source == TopologySource.MQTT || edge.bestSnr > existing.bestSnr -> edge.bestSnr
                    else -> existing.bestSnr
                }
            val newRssi =
                when {
                    edge.bestRssi != 0 && existing.bestRssi == 0 -> edge.bestRssi
                    edge.bestRssi == 0 -> existing.bestRssi
                    existing.bestRssi < 0 && edge.bestRssi < 0 -> maxOf(edge.bestRssi, existing.bestRssi)
                    edge.source == TopologySource.MQTT -> edge.bestRssi
                    else -> existing.bestRssi
                }
            val newSource =
                if (edge.source == TopologySource.MQTT || existing.source == TopologySource.MQTT) {
                    TopologySource.MQTT
                } else {
                    existing.source
                }
            val updated =
                existing.copy(
                    bestSnr = newSnr,
                    bestRssi = newRssi,
                    lastSeen = maxOf(existing.lastSeen, edge.lastSeen),
                    source = newSource,
                )
            upsertEdge(updated)
        }
    }
}
