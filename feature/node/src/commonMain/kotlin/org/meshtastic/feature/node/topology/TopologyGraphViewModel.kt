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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.database.entity.TopologyEdge
import org.meshtastic.core.model.MqttConnectionState
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.TopologySource
import org.meshtastic.core.repository.MqttManager
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.TopologyManager
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.topology_period_1h
import org.meshtastic.core.resources.topology_period_24h
import org.meshtastic.core.resources.topology_period_3d
import org.meshtastic.core.resources.topology_period_6h
import org.meshtastic.core.resources.topology_period_7d
import org.meshtastic.core.resources.topology_period_all

private const val ONE_HOUR_MS = 60 * 60 * 1000L
private const val SIX_HOURS_MS = 6 * ONE_HOUR_MS
private const val ONE_DAY_MS = 24 * ONE_HOUR_MS
private const val THREE_DAYS_MS = 3 * ONE_DAY_MS
private const val SEVEN_DAYS_MS = 7 * ONE_DAY_MS

enum class TopologyTimePeriod(val durationMs: Long?, val labelRes: StringResource) {
    ONE_HOUR(ONE_HOUR_MS, Res.string.topology_period_1h),
    SIX_HOURS(SIX_HOURS_MS, Res.string.topology_period_6h),
    TWENTY_FOUR_HOURS(ONE_DAY_MS, Res.string.topology_period_24h),
    THREE_DAYS(THREE_DAYS_MS, Res.string.topology_period_3d),
    SEVEN_DAYS(SEVEN_DAYS_MS, Res.string.topology_period_7d),
    ALL(null, Res.string.topology_period_all),
}

private data class FilterState(val showMqtt: Boolean, val period: TopologyTimePeriod)

private data class NodeState(val nodeMap: Map<Int, Node>, val ourNode: Node?)

private data class MqttState(
    val isMqttActive: Boolean,
    val activeMqttEdgesCount: Int,
    val isClientEnabled: Boolean,
    val connectionState: MqttConnectionState,
    val messageRate: Float,
)

private data class SelectionState(val selectedNum: Int?, val selectedEdge: TopologyEdge?)

data class TopologyGraphUiState(
    val edges: List<TopologyEdge> = emptyList(),
    val totalEdgesCount: Int = 0,
    val nodesByNum: Map<Int, Node> = emptyMap(),
    val ourNode: Node? = null,
    val isMqttActive: Boolean = false,
    val activeMqttEdgesCount: Int = 0,
    val isMqttClientEnabled: Boolean = false,
    val mqttConnectionState: MqttConnectionState = MqttConnectionState.Disconnected(null),
    val mqttMessageRate: Float = 0f,
    val showMqttData: Boolean = true,
    val timePeriod: TopologyTimePeriod = TopologyTimePeriod.ALL,
    val selectedNodeNum: Int? = null,
    val selectedEdge: TopologyEdge? = null,
) {
    val isMqttMode: Boolean
        get() = isMqttActive || activeMqttEdgesCount > 0 || isMqttClientEnabled

    val uniqueNodeNums: Set<Int> by lazy {
        val set = mutableSetOf<Int>()
        edges.forEach {
            set.add(it.node1)
            set.add(it.node2)
        }
        ourNode?.let { set.add(it.num) }
        set
    }

    val selectedNode: Node?
        get() = selectedNodeNum?.let { nodesByNum[it] }

    val selectedNodeEdges: List<TopologyEdge>
        get() =
            selectedNodeNum?.let { num ->
                edges.filter { it.node1 == num || it.node2 == num }
            } ?: emptyList()

    val selectedNodeSource: TopologySource?
        get() = selectedNodeEdges.firstOrNull()?.source
}

@KoinViewModel
class TopologyGraphViewModel(
    private val topologyManager: TopologyManager,
    private val nodeRepository: NodeRepository,
    private val mqttManager: MqttManager,
) : ViewModel() {

    private val _selectedNodeNum = MutableStateFlow<Int?>(null)
    val selectedNodeNum: StateFlow<Int?> = _selectedNodeNum.asStateFlow()

    private val _selectedEdge = MutableStateFlow<TopologyEdge?>(null)
    val selectedEdge: StateFlow<TopologyEdge?> = _selectedEdge.asStateFlow()

    private val _showMqttData = MutableStateFlow(true)
    val showMqttData: StateFlow<Boolean> = _showMqttData.asStateFlow()

    private val _timePeriod = MutableStateFlow(TopologyTimePeriod.ALL)
    val timePeriod: StateFlow<TopologyTimePeriod> = _timePeriod.asStateFlow()

    private val filterStateFlow = combine(_showMqttData, _timePeriod, ::FilterState)
    private val selectionStateFlow = combine(_selectedNodeNum, _selectedEdge, ::SelectionState)
    private val mqttStateFlow =
        combine(
            topologyManager.isMqttActive,
            topologyManager.activeMqttNodesCount,
            mqttManager.isClientEnabled,
            mqttManager.mqttConnectionState,
            mqttManager.messageRate,
            ::MqttState,
        )
    private val nodeStateFlow = combine(nodeRepository.nodeDBbyNum, nodeRepository.ourNodeInfo, ::NodeState)

    val uiState: StateFlow<TopologyGraphUiState> =
        combine(
            topologyManager.allEdgesFlow,
            nodeStateFlow,
            mqttStateFlow,
            filterStateFlow,
            selectionStateFlow,
        ) { rawEdges, nodeState, mqttState, filterState, selectionState ->
            val now = nowMillis
            val filteredEdges = rawEdges.filter { edge ->
                val mqttOk = filterState.showMqtt || edge.source != TopologySource.MQTT
                val timeOk =
                    filterState.period.durationMs == null || edge.lastSeen >= (now - filterState.period.durationMs)
                mqttOk && timeOk
            }

            TopologyGraphUiState(
                edges = filteredEdges,
                totalEdgesCount = rawEdges.size,
                nodesByNum = nodeState.nodeMap,
                ourNode = nodeState.ourNode,
                isMqttActive = mqttState.isMqttActive,
                activeMqttEdgesCount = mqttState.activeMqttEdgesCount,
                isMqttClientEnabled = mqttState.isClientEnabled,
                mqttConnectionState = mqttState.connectionState,
                mqttMessageRate = mqttState.messageRate,
                showMqttData = filterState.showMqtt,
                timePeriod = filterState.period,
                selectedNodeNum = selectionState.selectedNum,
                selectedEdge = selectionState.selectedEdge,
            )
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = TopologyGraphUiState(),
            )

    fun setMqttClientEnabled(enabled: Boolean) {
        mqttManager.setClientEnabled(enabled)
    }

    fun setShowMqttData(show: Boolean) {
        _showMqttData.value = show
    }

    fun setTimePeriod(period: TopologyTimePeriod) {
        _timePeriod.value = period
    }

    fun selectNode(nodeNum: Int?) {
        _selectedNodeNum.value = nodeNum
        if (nodeNum != null) {
            _selectedEdge.value = null
        }
    }

    fun selectEdge(edge: TopologyEdge?) {
        _selectedEdge.value = edge
        if (edge != null) {
            _selectedNodeNum.value = null
        }
    }

    fun clearTopology() {
        viewModelScope.launch {
            _selectedNodeNum.value = null
            _selectedEdge.value = null
            topologyManager.clearAllEdges()
        }
    }
}
