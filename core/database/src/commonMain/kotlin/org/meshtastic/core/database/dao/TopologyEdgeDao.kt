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
import androidx.room3.Transaction
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
        "SELECT * FROM topology_edge " +
            "WHERE node1 != 0 AND node2 != 0 AND node1 != -1 AND node2 != -1 " +
            "ORDER BY last_seen DESC",
    )
    suspend fun getAllEdgesSnapshot(): List<TopologyEdge>

    @Query(
        "SELECT COUNT(DISTINCT node) FROM (" +
            "SELECT node1 AS node FROM topology_edge WHERE source = 'MQTT' AND node1 != 0 AND node1 != -1 " +
            "UNION " +
            "SELECT node2 AS node FROM topology_edge WHERE source = 'MQTT' AND node2 != 0 AND node2 != -1" +
            ")",
    )
    fun getActiveMqttNodesCount(): Flow<Int>

    @Query(
        "SELECT COUNT(DISTINCT node) FROM (" +
            "SELECT node1 AS node FROM topology_edge " +
            "WHERE source = 'MQTT' AND node1 != 0 AND node1 != -1 AND last_seen >= :periodStart " +
            "UNION " +
            "SELECT node2 AS node FROM topology_edge " +
            "WHERE source = 'MQTT' AND node2 != 0 AND node2 != -1 AND last_seen >= :periodStart" +
            ")",
    )
    fun getActiveMqttNodesCount(periodStart: Long): Flow<Int>

    @Query("SELECT * FROM topology_edge WHERE node1 = :node1 AND node2 = :node2 LIMIT 1")
    suspend fun getEdge(node1: Int, node2: Int): TopologyEdge?

    @Query("DELETE FROM topology_edge WHERE node1 = :node1 AND node2 = :node2")
    suspend fun deleteEdge(node1: Int, node2: Int)

    @Query("DELETE FROM topology_edge WHERE node1 = 0 OR node2 = 0 OR node1 = -1 OR node2 = -1")
    suspend fun deleteInvalidEdges()

    @Query("DELETE FROM topology_edge WHERE last_seen < :cutoff")
    suspend fun pruneEdgesBefore(cutoff: Long)

    @Query("DELETE FROM topology_edge")
    suspend fun clearAllEdges()

    /**
     * Upserts an edge with smart priority logic:
     * - If edge does not exist, insert it.
     * - If edge already exists:
     *     - [bestSnr] is updated if the new value is better (higher), OR if the new source is [TopologySource.MQTT].
     *     - [bestRssi] is updated to the best (highest) signal measurement (maxOf).
     *     - Real measurements never get overwritten by 0 (unknown).
     *     - Source is elevated to [TopologySource.MQTT] if either edge was observed via MQTT.
     *     - [lastSeen] is always set to the newest timestamp.
     */
    @Transaction
    suspend fun upsertEdgeWithPriority(edge: TopologyEdge) {
        val existing = getEdge(edge.node1, edge.node2)
        if (existing == null) {
            upsertEdge(edge)
        } else {
            upsertEdge(mergeEdges(existing, edge))
        }
    }

    companion object {
        /**
         * Pure merge function combining an [existing] edge record with an [incoming] edge observation. Used
         * consistently by DAO, tests, and database migrations.
         */
        fun mergeEdges(existing: TopologyEdge, incoming: TopologyEdge): TopologyEdge {
            val newSnr =
                when {
                    incoming.bestSnr != 0.0f && existing.bestSnr == 0.0f -> incoming.bestSnr
                    incoming.bestSnr == 0.0f -> existing.bestSnr
                    incoming.source == TopologySource.MQTT || incoming.bestSnr > existing.bestSnr -> incoming.bestSnr
                    else -> existing.bestSnr
                }
            val newRssi =
                when {
                    incoming.bestRssi != 0 && existing.bestRssi == 0 -> incoming.bestRssi
                    incoming.bestRssi == 0 -> existing.bestRssi
                    existing.bestRssi < 0 && incoming.bestRssi < 0 -> maxOf(incoming.bestRssi, existing.bestRssi)
                    incoming.source == TopologySource.MQTT -> incoming.bestRssi
                    else -> existing.bestRssi
                }
            val newSource =
                if (incoming.source == TopologySource.MQTT || existing.source == TopologySource.MQTT) {
                    TopologySource.MQTT
                } else {
                    existing.source
                }
            return existing.copy(
                bestSnr = newSnr,
                bestRssi = newRssi,
                lastSeen = maxOf(existing.lastSeen, incoming.lastSeen),
                source = newSource,
            )
        }
    }
}
