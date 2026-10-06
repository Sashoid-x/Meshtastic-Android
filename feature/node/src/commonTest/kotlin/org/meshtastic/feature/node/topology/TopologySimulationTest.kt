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
package org.meshtastic.feature.node.topology

import org.meshtastic.core.database.entity.TopologyEdge
import org.meshtastic.core.model.TopologySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TopologySimulationTest {

    @Test
    fun `empty nodes list produces zero energy`() {
        val energy = TopologySimulation.runStep(emptyList(), emptyList())
        assertEquals(0f, energy)
    }

    @Test
    fun `pinned node maintains zero velocity and fixed position`() {
        val n1 = SimulationNode(num = 1, x = 0f, y = 0f)
        val n2 = SimulationNode(num = 2, x = 10f, y = 0f)
        val nodes = listOf(n1, n2)

        TopologySimulation.runStep(nodes, emptyList(), pinnedNodeNum = 1)

        assertEquals(0f, n1.vx)
        assertEquals(0f, n1.vy)
        assertEquals(0f, n1.x)
        assertEquals(0f, n1.y)

        // n2 should have moved away due to repulsion
        assertTrue(n2.x > 10f, "Unpinned node should be repelled away along x-axis")
    }

    @Test
    fun `two nodes close to each other repel each other`() {
        val n1 = SimulationNode(num = 1, x = -10f, y = 0f)
        val n2 = SimulationNode(num = 2, x = 10f, y = 0f)
        val nodes = listOf(n1, n2)

        TopologySimulation.runStep(nodes, emptyList())

        assertTrue(n1.x < -10f, "n1 should move left")
        assertTrue(n2.x > 10f, "n2 should move right")
    }

    @Test
    fun `spring force attracts distant nodes along edge`() {
        val n1 = SimulationNode(num = 1, x = -300f, y = 0f)
        val n2 = SimulationNode(num = 2, x = 300f, y = 0f)
        val nodes = listOf(n1, n2)
        val edge =
            TopologyEdge.create(
                1,
                2,
                bestSnr = 10.0f,
                bestRssi = -60,
                lastSeen = 1000L,
                source = TopologySource.LOCAL_RADIO,
            )

        val distInitial = n2.x - n1.x
        TopologySimulation.runStep(nodes, listOf(edge))
        val distAfter = n2.x - n1.x

        assertTrue(distAfter < distInitial, "Spring should pull distant nodes closer")
    }

    @Test
    fun `simulation converges and energy decays`() {
        val n1 = SimulationNode(num = 1, x = -100f, y = 50f)
        val n2 = SimulationNode(num = 2, x = 100f, y = -50f)
        val n3 = SimulationNode(num = 3, x = 0f, y = 120f)
        val nodes = listOf(n1, n2, n3)
        val edge1 =
            TopologyEdge.create(
                1,
                2,
                bestSnr = 5.0f,
                bestRssi = -70,
                lastSeen = 1000L,
                source = TopologySource.LOCAL_RADIO,
            )
        val edge2 =
            TopologyEdge.create(
                2,
                3,
                bestSnr = 2.0f,
                bestRssi = -75,
                lastSeen = 1000L,
                source = TopologySource.LOCAL_RADIO,
            )
        val edges = listOf(edge1, edge2)

        var lastEnergy = Float.MAX_VALUE
        repeat(80) {
            val energy = TopologySimulation.runStep(nodes, edges)
            lastEnergy = energy
        }

        assertTrue(lastEnergy < 1.0f, "Energy should decay toward equilibrium, was $lastEnergy")
    }
}
