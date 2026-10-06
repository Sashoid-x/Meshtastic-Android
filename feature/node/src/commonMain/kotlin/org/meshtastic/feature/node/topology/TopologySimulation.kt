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
@file:Suppress("MagicNumber")

package org.meshtastic.feature.node.topology

import org.meshtastic.core.database.entity.TopologyEdge
import kotlin.math.sqrt

/** Represents a 2D node point within the force-directed topology graph simulation. */
data class SimulationNode(val num: Int, var x: Float, var y: Float, var vx: Float = 0f, var vy: Float = 0f)

/** Pure functional physics implementation for the force-directed topology graph. */
object TopologySimulation {
    const val DEFAULT_GRAVITY = 0.008f
    const val DEFAULT_DAMPING = 0.82f
    private const val MAX_REPULSION_FORCE = 30f
    private const val MAX_SPRING_FORCE = 25f
    private const val REPULSION_CONSTANT = 28000f
    private const val SPRING_STIFFNESS = 0.04f

    /** Computes pairwise electrostatic repulsion forces among all nodes. */
    fun applyRepulsion(nodes: List<SimulationNode>) {
        val nodeCount = nodes.size
        for (i in 0 until nodeCount) {
            val n1 = nodes[i]
            for (j in i + 1 until nodeCount) {
                val n2 = nodes[j]
                val dx = n1.x - n2.x
                val dy = n1.y - n2.y
                val distSq = dx * dx + dy * dy + 150f
                val dist = sqrt(distSq)
                val force = (REPULSION_CONSTANT / (distSq * dist)).coerceAtMost(MAX_REPULSION_FORCE)
                val fx = dx * force
                val fy = dy * force
                n1.vx += fx
                n1.vy += fy
                n2.vx -= fx
                n2.vy -= fy
            }
        }
    }

    /** Applies Hooke's law spring forces along known topology edges. */
    fun applySprings(nodes: List<SimulationNode>, edges: List<TopologyEdge>) {
        val nodeMap = nodes.associateBy { it.num }
        for (edge in edges) {
            val n1 = nodeMap[edge.node1]
            val n2 = nodeMap[edge.node2]
            if (n1 == null || n2 == null) continue

            val dx = n2.x - n1.x
            val dy = n2.y - n1.y
            val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)

            val snrClamped = edge.bestSnr.coerceIn(-15f, 15f)
            val targetDist = 240f - (snrClamped * 5f)
            val delta = dist - targetDist
            val force = (delta * SPRING_STIFFNESS).coerceIn(-MAX_SPRING_FORCE, MAX_SPRING_FORCE)
            val fx = (dx / dist) * force
            val fy = (dy / dist) * force

            n1.vx += fx
            n1.vy += fy
            n2.vx -= fx
            n2.vy -= fy
        }
    }

    /** Applies central gravity toward (0,0) and velocity damping, returning total kinetic energy. */
    fun applyGravityAndDamping(
        nodes: List<SimulationNode>,
        pinnedNodeNum: Int? = null,
        kGravity: Float = DEFAULT_GRAVITY,
        damping: Float = DEFAULT_DAMPING,
    ): Float {
        var totalEnergy = 0f
        for (n in nodes) {
            if (n.num == pinnedNodeNum) {
                n.vx = 0f
                n.vy = 0f
            } else {
                n.vx -= n.x * kGravity
                n.vy -= n.y * kGravity
                n.vx *= damping
                n.vy *= damping
                n.x += n.vx
                n.y += n.vy
                totalEnergy += n.vx * n.vx + n.vy * n.vy
            }
        }
        return totalEnergy
    }

    /** Executes one full iteration of the force-directed simulation step and returns total energy. */
    fun runStep(nodes: List<SimulationNode>, edges: List<TopologyEdge>, pinnedNodeNum: Int? = null): Float {
        if (nodes.isEmpty()) return 0f
        applyRepulsion(nodes)
        applySprings(nodes, edges)
        return applyGravityAndDamping(nodes, pinnedNodeNum)
    }
}
