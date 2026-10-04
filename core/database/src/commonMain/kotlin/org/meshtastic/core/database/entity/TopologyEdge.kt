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
package org.meshtastic.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import org.meshtastic.core.model.TopologySource

/**
 * Entity representing an undirected edge between two mesh nodes in the network topology.
 *
 * To ensure bidirectional uniqueness, keys are always normalized: [node1] is the smaller node number and [node2] is the
 * larger node number.
 */
@Entity(
    tableName = "topology_edge",
    primaryKeys = ["node1", "node2"],
    indices =
    [
        Index(value = ["node1"]),
        Index(value = ["node2"]),
        Index(value = ["source"]),
    ],
)
data class TopologyEdge(
    @ColumnInfo(name = "node1") val node1: Int,
    @ColumnInfo(name = "node2") val node2: Int,
    @ColumnInfo(name = "best_snr") val bestSnr: Float,
    @ColumnInfo(name = "best_rssi") val bestRssi: Int,
    @ColumnInfo(name = "last_seen") val lastSeen: Long,
    @ColumnInfo(name = "source") val source: TopologySource,
) {
    companion object {
        /** Constructs a [TopologyEdge] with normalized node numbers ([node1] <= [node2]). */
        fun create(
            from: Int,
            to: Int,
            bestSnr: Float,
            bestRssi: Int,
            lastSeen: Long,
            source: TopologySource,
        ): TopologyEdge = TopologyEdge(
            node1 = minOf(from, to),
            node2 = maxOf(from, to),
            bestSnr = bestSnr,
            bestRssi = bestRssi,
            lastSeen = lastSeen,
            source = source,
        )
    }
}
