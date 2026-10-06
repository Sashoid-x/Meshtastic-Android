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

import kotlinx.coroutines.flow.Flow
import org.meshtastic.core.database.DatabaseProvider
import org.meshtastic.core.database.entity.TopologyEdge

/**
 * A switch-aware [TopologyEdgeDao] that resolves the active database on every call instead of pinning the one that was
 * current at injection time.
 */
class SwitchingTopologyEdgeDao(private val dbManager: DatabaseProvider) : TopologyEdgeDao {

    override suspend fun upsertEdge(edge: TopologyEdge) {
        dbManager.withDb { it.topologyEdgeDao().upsertEdge(edge) }
    }

    override fun getAllEdgesFlow(): Flow<List<TopologyEdge>> = dbManager.observeCurrentDb {
        it.topologyEdgeDao().getAllEdgesFlow()
    }

    override suspend fun getAllEdgesSnapshot(): List<TopologyEdge> = dbManager
        .withDb {
            it.topologyEdgeDao().getAllEdgesSnapshot()
        }
        .orEmpty()

    override fun getActiveMqttNodesCount(): Flow<Int> = dbManager.observeCurrentDb {
        it.topologyEdgeDao().getActiveMqttNodesCount()
    }

    override fun getActiveMqttNodesCount(periodStart: Long): Flow<Int> = dbManager.observeCurrentDb {
        it.topologyEdgeDao().getActiveMqttNodesCount(periodStart)
    }

    override suspend fun getEdge(node1: Int, node2: Int): TopologyEdge? = dbManager.withDb {
        it.topologyEdgeDao().getEdge(node1, node2)
    }

    override suspend fun deleteEdge(node1: Int, node2: Int) {
        dbManager.withDb { it.topologyEdgeDao().deleteEdge(node1, node2) }
    }

    override suspend fun deleteInvalidEdges() {
        dbManager.withDb { it.topologyEdgeDao().deleteInvalidEdges() }
    }

    override suspend fun pruneEdgesBefore(cutoff: Long) {
        dbManager.withDb { it.topologyEdgeDao().pruneEdgesBefore(cutoff) }
    }

    override suspend fun clearAllEdges() {
        dbManager.withDb { it.topologyEdgeDao().clearAllEdges() }
    }

    override suspend fun upsertEdgeWithPriority(edge: TopologyEdge) {
        dbManager.withDb { it.topologyEdgeDao().upsertEdgeWithPriority(edge) }
    }
}
