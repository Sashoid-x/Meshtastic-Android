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
@file:Suppress("MagicNumber", "TooManyFunctions")

package org.meshtastic.feature.node.topology

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.common.util.MeasurementSystem
import org.meshtastic.core.common.util.NumberFormatter
import org.meshtastic.core.database.entity.TopologyEdge
import org.meshtastic.core.model.MqttConnectionState
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.TopologySource
import org.meshtastic.core.model.util.toDistanceString
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.cancel
import org.meshtastic.core.resources.delete
import org.meshtastic.core.resources.distance
import org.meshtastic.core.resources.mqtt_status_connected
import org.meshtastic.core.resources.mqtt_status_connecting
import org.meshtastic.core.resources.mqtt_status_disconnected
import org.meshtastic.core.resources.mqtt_status_disconnected_with_reason
import org.meshtastic.core.resources.mqtt_status_inactive
import org.meshtastic.core.resources.mqtt_status_reconnecting
import org.meshtastic.core.resources.mqtt_status_reconnecting_with_attempt
import org.meshtastic.core.resources.mqtt_status_topics_refused_all
import org.meshtastic.core.resources.mqtt_status_topics_refused_some
import org.meshtastic.core.resources.reset
import org.meshtastic.core.resources.topology_clear_edges
import org.meshtastic.core.resources.topology_clear_edges_confirm
import org.meshtastic.core.resources.topology_edges_count
import org.meshtastic.core.resources.topology_empty_desc
import org.meshtastic.core.resources.topology_empty_title
import org.meshtastic.core.resources.topology_filter_mqtt
import org.meshtastic.core.resources.topology_filters
import org.meshtastic.core.resources.topology_graph_title
import org.meshtastic.core.resources.topology_link_details_title
import org.meshtastic.core.resources.topology_mode_full_mqtt
import org.meshtastic.core.resources.topology_mode_local_radio
import org.meshtastic.core.resources.topology_mode_mqtt_hidden
import org.meshtastic.core.resources.topology_mqtt_connect
import org.meshtastic.core.resources.topology_mqtt_disable
import org.meshtastic.core.resources.topology_mqtt_enable
import org.meshtastic.core.resources.topology_mqtt_msg_rate
import org.meshtastic.core.resources.topology_mqtt_warning_desc
import org.meshtastic.core.resources.topology_mqtt_warning_title
import org.meshtastic.core.resources.topology_node_our
import org.meshtastic.core.resources.topology_nodes_count
import org.meshtastic.core.resources.topology_signal_level
import org.meshtastic.core.resources.topology_snr_critical
import org.meshtastic.core.resources.topology_snr_excellent
import org.meshtastic.core.resources.topology_snr_fair
import org.meshtastic.core.resources.topology_snr_good
import org.meshtastic.core.resources.topology_snr_poor
import org.meshtastic.core.resources.topology_source_direct
import org.meshtastic.core.resources.topology_source_local
import org.meshtastic.core.resources.topology_source_mqtt
import org.meshtastic.core.resources.topology_view_node
import org.meshtastic.core.ui.component.MainAppBar
import org.meshtastic.core.ui.component.MeshtasticDialog
import org.meshtastic.core.ui.icon.ChevronRight
import org.meshtastic.core.ui.icon.Cloud
import org.meshtastic.core.ui.icon.CloudOff
import org.meshtastic.core.ui.icon.DeleteNode
import org.meshtastic.core.ui.icon.ExpandLess
import org.meshtastic.core.ui.icon.ExpandMore
import org.meshtastic.core.ui.icon.HopCount
import org.meshtastic.core.ui.icon.Hub
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.Refresh
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private class SimulationNode(val num: Int, var x: Float, var y: Float, var vx: Float = 0f, var vy: Float = 0f)

private const val MAX_ANIMATED_NODES = 150
private const val STATIC_RELAXATION_STEPS = 45
private const val COLOR_SNR_EXCELLENT = 0xFF4CAF50
private const val COLOR_SNR_GOOD = 0xFF8BC34A
private const val COLOR_SNR_FAIR = 0xFFFFC107
private const val COLOR_SNR_POOR = 0xFFFF9800
private const val COLOR_SNR_CRITICAL = 0xFFF44336

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun TopologyGraphScreen(
    viewModel: TopologyGraphViewModel,
    onNavigateUp: () -> Unit,
    onNavigateToNodeDetail: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current.density

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showMqttWarningDialog by remember { mutableStateOf(false) }
    var showFilterBar by remember { mutableStateOf(true) }

    val simulationNodes = remember { mutableMapOf<Int, SimulationNode>() }
    var stepTrigger by remember { mutableIntStateOf(0) }
    var simulationWakeUp by remember { mutableIntStateOf(0) }
    var draggedNodeNum by remember { mutableStateOf<Int?>(null) }

    val backState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = backState,
        isBackEnabled = uiState.selectedNodeNum != null || uiState.selectedEdge != null,
        onBackCompleted = {
            viewModel.selectNode(null)
            viewModel.selectEdge(null)
        },
    )

    // Synchronize simulation nodes with discovered unique nodes
    LaunchedEffect(uiState.uniqueNodeNums) {
        val currentNums = uiState.uniqueNodeNums
        simulationNodes.keys.retainAll(currentNums)

        val rng = Random(42)
        val total = currentNums.size
        var idx = 0
        for (num in currentNums) {
            if (!simulationNodes.containsKey(num)) {
                val angle = (idx.toFloat() / maxOf(1, total)) * 2f * PI.toFloat()
                val radius = 130f + (idx % 4) * 45f
                simulationNodes[num] =
                    SimulationNode(
                        num = num,
                        x = cos(angle) * radius + (rng.nextFloat() - 0.5f) * 30f,
                        y = sin(angle) * radius + (rng.nextFloat() - 0.5f) * 30f,
                    )
            }
            idx++
        }
        stepTrigger++
    }

    // Force-directed physics loop
    val isStatic = uiState.uniqueNodeNums.size > MAX_ANIMATED_NODES
    LaunchedEffect(uiState.edges, uiState.uniqueNodeNums, isStatic, simulationWakeUp) {
        if (simulationNodes.isEmpty()) return@LaunchedEffect

        if (isStatic) {
            repeat(STATIC_RELAXATION_STEPS) {
                runSimulationStep(simulationNodes.values.toList(), uiState.edges, draggedNodeNum)
            }
            stepTrigger++
        } else {
            var frame = 0
            while (frame < 120 || draggedNodeNum != null) {
                val energy = runSimulationStep(simulationNodes.values.toList(), uiState.edges, draggedNodeNum)
                stepTrigger++
                frame++
                if (draggedNodeNum == null && energy < 0.25f && frame > 30) {
                    break
                }
                delay(16)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            MainAppBar(
                title = stringResource(Res.string.topology_graph_title),
                canNavigateUp = true,
                onNavigateUp = onNavigateUp,
                ourNode = uiState.ourNode,
                showNodeChip = false,
                actions = {
                    IconButton(
                        onClick = {
                            if (uiState.isMqttClientEnabled) {
                                viewModel.setMqttClientEnabled(false)
                            } else {
                                showMqttWarningDialog = true
                            }
                        },
                    ) {
                        Icon(
                            imageVector =
                            if (uiState.isMqttClientEnabled) {
                                MeshtasticIcons.Cloud
                            } else {
                                MeshtasticIcons.CloudOff
                            },
                            contentDescription =
                            stringResource(
                                if (uiState.isMqttClientEnabled) {
                                    Res.string.topology_mqtt_disable
                                } else {
                                    Res.string.topology_mqtt_enable
                                },
                            ),
                            tint =
                            if (uiState.isMqttClientEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(
                        onClick = {
                            scale = 1f
                            offset = Offset.Zero
                        },
                    ) {
                        Icon(
                            imageVector = MeshtasticIcons.Refresh,
                            contentDescription = stringResource(Res.string.reset),
                        )
                    }
                    if (uiState.edges.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirmDialog = true }) {
                            Icon(
                                imageVector = MeshtasticIcons.DeleteNode,
                                contentDescription = stringResource(Res.string.topology_clear_edges),
                            )
                        }
                    }
                },
                onClickChip = {},
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding).clipToBounds()) {
            if (uiState.edges.isEmpty() && simulationNodes.isEmpty()) {
                // Empty state
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = MeshtasticIcons.Hub,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(Res.string.topology_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(Res.string.topology_empty_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Interactive Canvas
                val primaryColor = MaterialTheme.colorScheme.primary
                val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
                val nodeFillColor = MaterialTheme.colorScheme.surfaceContainerHigh
                val nodeBorderColor = MaterialTheme.colorScheme.outline
                val onSurfaceColor = MaterialTheme.colorScheme.onSurface
                val selectionColor = MaterialTheme.colorScheme.tertiary
                val strokeExcellent = Color(COLOR_SNR_EXCELLENT)
                val strokeGood = Color(COLOR_SNR_GOOD)
                val strokeFair = Color(COLOR_SNR_FAIR)
                val strokePoor = Color(COLOR_SNR_POOR)
                val strokeCritical = Color(COLOR_SNR_CRITICAL)
                val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f) }

                Box(
                    modifier =
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val virtualX = (down.position.x - center.x - offset.x) / scale
                            val virtualY = (down.position.y - center.y - offset.y) / scale
                            val hitRadius = (34f * density) / scale.coerceAtLeast(0.6f)

                            val hitNode =
                                simulationNodes.values
                                    .minByOrNull {
                                        val dx = it.x - virtualX
                                        val dy = it.y - virtualY
                                        dx * dx + dy * dy
                                    }
                                    ?.takeIf {
                                        val dx = it.x - virtualX
                                        val dy = it.y - virtualY
                                        (dx * dx + dy * dy) <= hitRadius * hitRadius
                                    }

                            if (hitNode != null) {
                                var isDrag = false
                                val touchSlop = viewConfiguration.touchSlop
                                draggedNodeNum = hitNode.num
                                simulationWakeUp++

                                var tracking = true
                                while (tracking) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null) {
                                        tracking = false
                                    } else if (change.pressed) {
                                        val moveDist = (change.position - down.position).getDistance()
                                        if (!isDrag && moveDist > touchSlop) {
                                            isDrag = true
                                        }
                                        if (isDrag) {
                                            change.consume()
                                            hitNode.x = (change.position.x - center.x - offset.x) / scale
                                            hitNode.y = (change.position.y - center.y - offset.y) / scale
                                            hitNode.vx = 0f
                                            hitNode.vy = 0f
                                            simulationWakeUp++
                                        }
                                    } else {
                                        if (!isDrag) {
                                            viewModel.selectNode(hitNode.num)
                                        }
                                        tracking = false
                                    }
                                }

                                draggedNodeNum = null
                            } else {
                                var isTransform = false
                                val touchSlop = viewConfiguration.touchSlop

                                var transforming = true
                                while (transforming) {
                                    val event = awaitPointerEvent()
                                    val pressedChanges = event.changes.filter { it.pressed }
                                    if (pressedChanges.isEmpty()) {
                                        if (!isTransform) {
                                            val hitThreshold = (22f * density) / scale.coerceAtLeast(0.6f)
                                            val hitThresholdSq = hitThreshold * hitThreshold
                                            val hitEdge =
                                                uiState.edges
                                                    .mapNotNull { edge ->
                                                        val p1 =
                                                            simulationNodes[edge.node1] ?: return@mapNotNull null
                                                        val p2 =
                                                            simulationNodes[edge.node2] ?: return@mapNotNull null
                                                        val dx = p2.x - p1.x
                                                        val dy = p2.y - p1.y
                                                        val lenSq = dx * dx + dy * dy
                                                        val distSq =
                                                            if (lenSq < 1e-4) {
                                                                val ex = virtualX - p1.x
                                                                val ey = virtualY - p1.y
                                                                ex * ex + ey * ey
                                                            } else {
                                                                val t =
                                                                    (
                                                                        (
                                                                            (virtualX - p1.x) * dx +
                                                                                (virtualY - p1.y) * dy
                                                                            ) / lenSq
                                                                        )
                                                                        .coerceIn(0f, 1f)
                                                                val projX = p1.x + t * dx
                                                                val projY = p1.y + t * dy
                                                                val ex = virtualX - projX
                                                                val ey = virtualY - projY
                                                                ex * ex + ey * ey
                                                            }
                                                        if (distSq <= hitThresholdSq) Pair(edge, distSq) else null
                                                    }
                                                    .minByOrNull { it.second }
                                                    ?.first

                                            if (hitEdge != null) {
                                                viewModel.selectEdge(hitEdge)
                                            } else {
                                                viewModel.selectNode(null)
                                                viewModel.selectEdge(null)
                                            }
                                        }
                                        transforming = false
                                    } else if (pressedChanges.size >= 2) {
                                        isTransform = true
                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()
                                        val centroid = event.calculateCentroid(useCurrent = false)

                                        val oldScale = scale
                                        val newScale = (scale * zoomChange).coerceIn(0.2f, 4.0f)
                                        offset =
                                            (offset + centroid - center) * (newScale / oldScale) -
                                            (centroid - center) + panChange
                                        scale = newScale
                                        event.changes.forEach { it.consume() }
                                    } else if (pressedChanges.size == 1) {
                                        val change = pressedChanges[0]
                                        val pan = change.positionChange()
                                        if (
                                            !isTransform &&
                                            (change.position - down.position).getDistance() > touchSlop
                                        ) {
                                            isTransform = true
                                        }
                                        if (isTransform) {
                                            offset += pan
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        }
                    },
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // stepTrigger dependency triggers recomposition during simulation ticks
                        @Suppress("UNUSED_VARIABLE")
                        val trigger = stepTrigger

                        withTransform({
                            translate(size.width / 2f + offset.x, size.height / 2f + offset.y)
                            scale(scale, scale, Offset.Zero)
                        }) {
                            // 1. Draw Edges
                            for (edge in uiState.edges) {
                                val n1 = simulationNodes[edge.node1]
                                val n2 = simulationNodes[edge.node2]
                                if (n1 == null || n2 == null) continue

                                val isSelected = uiState.selectedEdge == edge
                                val (color, strokeWidth, pathEffect) =
                                    when {
                                        edge.bestSnr >= 2.0f -> Triple(strokeExcellent, 4.5f, null)
                                        edge.bestSnr >= -4.0f -> Triple(strokeGood, 3.5f, null)
                                        edge.bestSnr >= -10.0f -> Triple(strokeFair, 3.0f, null)
                                        edge.bestSnr >= -16.0f -> Triple(strokePoor, 2.5f, null)
                                        else -> Triple(strokeCritical, 2.0f, dashEffect)
                                    }

                                if (isSelected) {
                                    drawLine(
                                        color = selectionColor.copy(alpha = 0.5f),
                                        start = Offset(n1.x, n1.y),
                                        end = Offset(n2.x, n2.y),
                                        strokeWidth = strokeWidth + 8f,
                                    )
                                }

                                drawLine(
                                    color = if (isSelected) selectionColor else color,
                                    start = Offset(n1.x, n1.y),
                                    end = Offset(n2.x, n2.y),
                                    strokeWidth = if (isSelected) strokeWidth + 2f else strokeWidth,
                                    pathEffect = pathEffect,
                                )
                            }

                            // 2. Draw Nodes
                            val nodeRadius = 26f
                            for ((num, simNode) in simulationNodes) {
                                val isOur = uiState.ourNode?.num == num
                                val isSelected = uiState.selectedNodeNum == num
                                val node = uiState.nodesByNum[num]

                                if (isSelected) {
                                    drawCircle(
                                        color = selectionColor,
                                        radius = nodeRadius + 6f,
                                        center = Offset(simNode.x, simNode.y),
                                        style = Stroke(width = 3f),
                                    )
                                }

                                if (isOur) {
                                    drawCircle(
                                        color = primaryColor.copy(alpha = 0.35f),
                                        radius = nodeRadius + 5f,
                                        center = Offset(simNode.x, simNode.y),
                                    )
                                }

                                drawCircle(
                                    color = if (isOur) primaryColor else nodeFillColor,
                                    radius = nodeRadius,
                                    center = Offset(simNode.x, simNode.y),
                                )

                                drawCircle(
                                    color = if (isOur) onPrimaryColor else nodeBorderColor,
                                    radius = nodeRadius,
                                    center = Offset(simNode.x, simNode.y),
                                    style = Stroke(width = 2f),
                                )

                                val shortName =
                                    node?.user?.short_name?.takeIf { it.isNotBlank() }
                                        ?: num.toUInt().toString(16).takeLast(4).uppercase()

                                val textLayout =
                                    textMeasurer.measure(
                                        text = shortName,
                                        style =
                                        TextStyle(
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isOur) onPrimaryColor else onSurfaceColor,
                                        ),
                                    )

                                drawText(
                                    textLayoutResult = textLayout,
                                    topLeft =
                                    Offset(
                                        simNode.x - textLayout.size.width / 2f,
                                        simNode.y - textLayout.size.height / 2f,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            // Top Status Badge & Filters
            Card(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            ) {
                Column(
                    modifier = Modifier.animateContentSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val (badgeColor, badgeTextRes) =
                        if (uiState.isMqttMode && uiState.showMqttData) {
                            Color(COLOR_SNR_EXCELLENT) to Res.string.topology_mode_full_mqtt
                        } else if (uiState.isMqttMode && !uiState.showMqttData) {
                            Color(COLOR_SNR_FAIR) to Res.string.topology_mode_mqtt_hidden
                        } else {
                            Color(COLOR_SNR_FAIR) to Res.string.topology_mode_local_radio
                        }

                    Row(
                        modifier =
                        Modifier.clickable { showFilterBar = !showFilterBar }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(modifier = Modifier.size(9.dp).background(badgeColor, CircleShape))
                        Text(
                            text = stringResource(badgeTextRes),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            text =
                            "${uiState.uniqueNodeNums.size} ${stringResource(
                                Res.string.topology_nodes_count,
                            ).lowercase()} / ${uiState.edges.size} ${stringResource(
                                Res.string.topology_edges_count,
                            ).lowercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            imageVector = if (showFilterBar) MeshtasticIcons.ExpandLess else MeshtasticIcons.ExpandMore,
                            contentDescription = stringResource(Res.string.topology_filters),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (uiState.isMqttClientEnabled) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        MqttConnectionStatusBar(
                            state = uiState.mqttConnectionState,
                            messageRate = uiState.mqttMessageRate,
                        )
                    }

                    AnimatedVisibility(visible = showFilterBar) {
                        Column {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                            Row(
                                modifier =
                                Modifier.horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (uiState.isMqttMode) {
                                    FilterChip(
                                        selected = uiState.showMqttData,
                                        onClick = { viewModel.setShowMqttData(!uiState.showMqttData) },
                                        label = {
                                            Text(
                                                text = stringResource(Res.string.topology_filter_mqtt),
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector =
                                                if (uiState.showMqttData) {
                                                    MeshtasticIcons.Cloud
                                                } else {
                                                    MeshtasticIcons.CloudOff
                                                },
                                                contentDescription = null,
                                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                                            )
                                        },
                                    )
                                }
                                TopologyTimePeriod.entries.forEach { period ->
                                    FilterChip(
                                        selected = uiState.timePeriod == period,
                                        onClick = { viewModel.setTimePeriod(period) },
                                        label = {
                                            Text(
                                                text = stringResource(period.labelRes),
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom SNR Quality Legend
            Card(
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LegendItem(Color(COLOR_SNR_EXCELLENT), "≥ 2 dB")
                    LegendItem(Color(COLOR_SNR_GOOD), "≥ -4 dB")
                    LegendItem(Color(COLOR_SNR_FAIR), "≥ -10 dB")
                    LegendItem(Color(COLOR_SNR_POOR), "≥ -16 dB")
                    LegendItem(Color(COLOR_SNR_CRITICAL), "< -16 dB")
                }
            }
        }
    }

    // Node Details BottomSheet
    if (uiState.selectedNodeNum != null) {
        val selectedNum = uiState.selectedNodeNum
        val selectedNode = uiState.selectedNode
        val edges = uiState.selectedNodeEdges
        val sheetState = rememberModalBottomSheetState()

        ModalBottomSheet(
            onDismissRequest = { viewModel.selectNode(null) },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text =
                                selectedNode?.user?.short_name?.take(3)?.uppercase()
                                    ?: selectedNum?.toUInt()?.toString(16)?.takeLast(3)?.uppercase()
                                    ?: "?",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text =
                            selectedNode?.user?.long_name?.takeIf { it.isNotBlank() }
                                ?: selectedNode?.user?.short_name
                                ?: "!${selectedNum?.toUInt()?.toString(16)?.padStart(8, '0')}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "!${selectedNum?.toUInt()?.toString(16)?.padStart(8, '0')}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    selectedNum?.let { num ->
                        Button(
                            onClick = {
                                viewModel.selectNode(null)
                                onNavigateToNodeDetail(num)
                            },
                        ) {
                            Text(text = stringResource(Res.string.topology_view_node))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Badges Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val isOur = uiState.ourNode?.num == selectedNum
                    if (isOur) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text(stringResource(Res.string.topology_node_our)) },
                            colors =
                            SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            ),
                        )
                    }

                    selectedNode?.user?.role?.name?.let { roleName ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(roleName) },
                        )
                    }

                    selectedNode?.deviceMetrics?.battery_level?.let { battery ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text("$battery%") },
                        )
                    }

                    val source = uiState.selectedNodeSource
                    val sourceTextRes =
                        when (source) {
                            TopologySource.MQTT -> Res.string.topology_source_mqtt
                            TopologySource.LOCAL_RADIO -> Res.string.topology_source_direct
                            null -> Res.string.topology_source_local
                        }
                    SuggestionChip(
                        onClick = {},
                        label = { Text(stringResource(sourceTextRes)) },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // Neighbors list
                Text(
                    text = "${stringResource(Res.string.topology_edges_count)} (${edges.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(edges) { edge ->
                        val peerNum = if (edge.node1 == selectedNum) edge.node2 else edge.node1
                        val peerNode = uiState.nodesByNum[peerNum]
                        val peerName =
                            peerNode?.user?.short_name?.takeIf { it.isNotBlank() }
                                ?: "!${peerNum.toUInt().toString(16).padStart(8, '0')}"

                        val snrFormatted = NumberFormatter.format(edge.bestSnr, 1)

                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(
                                        text = peerName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = "!${peerNum.toUInt().toString(16).padStart(8, '0')}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "SNR: $snrFormatted dB",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color =
                                        when {
                                            edge.bestSnr >= 2.0f -> Color(COLOR_SNR_EXCELLENT)
                                            edge.bestSnr >= -4.0f -> Color(COLOR_SNR_GOOD)
                                            edge.bestSnr >= -10.0f -> Color(COLOR_SNR_FAIR)
                                            edge.bestSnr >= -16.0f -> Color(COLOR_SNR_POOR)
                                            else -> Color(COLOR_SNR_CRITICAL)
                                        },
                                    )
                                    if (edge.bestRssi != 0) {
                                        Text(
                                            text = "RSSI: ${edge.bestRssi} dBm",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Link Details BottomSheet
    if (uiState.selectedEdge != null) {
        val selectedEdge = uiState.selectedEdge
        val node1 = selectedEdge?.let { uiState.nodesByNum[it.node1] }
        val node2 = selectedEdge?.let { uiState.nodesByNum[it.node2] }
        val sheetState = rememberModalBottomSheetState()

        ModalBottomSheet(
            onDismissRequest = { viewModel.selectEdge(null) },
            sheetState = sheetState,
        ) {
            selectedEdge?.let { edge ->
                LinkDetailContent(
                    edge = edge,
                    node1 = node1,
                    node2 = node2,
                    onSelectNode = { num ->
                        viewModel.selectEdge(null)
                        viewModel.selectNode(num)
                    },
                    onNavigateToNodeDetail = { num ->
                        viewModel.selectEdge(null)
                        onNavigateToNodeDetail(num)
                    },
                )
            }
        }
    }

    if (showMqttWarningDialog) {
        MeshtasticDialog(
            titleRes = Res.string.topology_mqtt_warning_title,
            messageRes = Res.string.topology_mqtt_warning_desc,
            confirmTextRes = Res.string.topology_mqtt_connect,
            dismissTextRes = Res.string.cancel,
            onConfirm = {
                viewModel.setMqttClientEnabled(true)
                showMqttWarningDialog = false
            },
            onDismiss = { showMqttWarningDialog = false },
        )
    }

    if (showClearConfirmDialog) {
        MeshtasticDialog(
            titleRes = Res.string.topology_clear_edges,
            messageRes = Res.string.topology_clear_edges_confirm,
            confirmTextRes = Res.string.delete,
            onConfirm = {
                viewModel.clearTopology()
                showClearConfirmDialog = false
            },
            dismissTextRes = Res.string.cancel,
            onDismiss = { showClearConfirmDialog = false },
        )
    }
}

@Composable
private fun MqttConnectionStatusBar(state: MqttConnectionState, messageRate: Float, modifier: Modifier = Modifier) {
    val (statusText, statusColor) =
        when (state) {
            is MqttConnectionState.Inactive ->
                stringResource(Res.string.mqtt_status_inactive) to MaterialTheme.colorScheme.outline

            is MqttConnectionState.Disconnected -> {
                val text =
                    state.reason?.let { stringResource(Res.string.mqtt_status_disconnected_with_reason, it) }
                        ?: stringResource(Res.string.mqtt_status_disconnected)
                text to MaterialTheme.colorScheme.error
            }

            is MqttConnectionState.Connecting ->
                stringResource(Res.string.mqtt_status_connecting) to Color(COLOR_SNR_FAIR)

            is MqttConnectionState.Connected ->
                stringResource(Res.string.mqtt_status_connected) to Color(COLOR_SNR_EXCELLENT)

            is MqttConnectionState.SubscriptionRefused -> {
                val topics = state.refused.entries.joinToString { (topic, reason) -> "$topic ($reason)" }
                if (state.granted == 0) {
                    stringResource(Res.string.mqtt_status_topics_refused_all, topics) to MaterialTheme.colorScheme.error
                } else {
                    stringResource(Res.string.mqtt_status_topics_refused_some, topics) to Color(COLOR_SNR_FAIR)
                }
            }

            is MqttConnectionState.Reconnecting -> {
                val err = state.lastError
                val text =
                    if (err != null) {
                        stringResource(Res.string.mqtt_status_reconnecting_with_attempt, state.attempt, err)
                    } else {
                        stringResource(Res.string.mqtt_status_reconnecting)
                    }
                text to Color(COLOR_SNR_FAIR)
            }
        }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(modifier = Modifier.size(7.dp).background(statusColor, CircleShape))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (state is MqttConnectionState.Connected) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "•",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(Res.string.topology_mqtt_msg_rate, NumberFormatter.format(messageRate, 1)),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(modifier = Modifier.size(7.dp).background(color, CircleShape))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LinkDetailContent(
    edge: TopologyEdge,
    node1: Node?,
    node2: Node?,
    onSelectNode: (Int) -> Unit,
    onNavigateToNodeDetail: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        LinkHeaderRow(source = edge.source)
        Spacer(modifier = Modifier.height(16.dp))
        LinkNodesRow(
            edge = edge,
            node1 = node1,
            node2 = node2,
            onSelectNode = onSelectNode,
            onNavigateToNodeDetail = onNavigateToNodeDetail,
        )
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))
        LinkMetricsRow(edge = edge, node1 = node1, node2 = node2)
    }
}

@Composable
private fun LinkHeaderRow(source: TopologySource) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = MeshtasticIcons.HopCount,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = stringResource(Res.string.topology_link_details_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        val sourceTextRes =
            when (source) {
                TopologySource.MQTT -> Res.string.topology_source_mqtt
                TopologySource.LOCAL_RADIO -> Res.string.topology_source_direct
            }
        SuggestionChip(
            onClick = {},
            label = { Text(stringResource(sourceTextRes)) },
        )
    }
}

@Composable
private fun LinkNodesRow(
    edge: TopologyEdge,
    node1: Node?,
    node2: Node?,
    onSelectNode: (Int) -> Unit,
    onNavigateToNodeDetail: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.topology_nodes_count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NodeLinkCard(
                nodeNum = edge.node1,
                node = node1,
                onClick = { onSelectNode(edge.node1) },
                onDetailClick = { onNavigateToNodeDetail(edge.node1) },
                modifier = Modifier.weight(1f),
            )

            Icon(
                imageVector = MeshtasticIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )

            NodeLinkCard(
                nodeNum = edge.node2,
                node = node2,
                onClick = { onSelectNode(edge.node2) },
                onDetailClick = { onNavigateToNodeDetail(edge.node2) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LinkMetricsRow(edge: TopologyEdge, node1: Node?, node2: Node?) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.topology_signal_level),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SnrMetricCard(bestSnr = edge.bestSnr, modifier = Modifier.weight(1f))
            if (edge.bestRssi != 0) {
                RssiMetricCard(rssi = edge.bestRssi, modifier = Modifier.weight(1f))
            }
            val distanceMeters = if (node1 != null && node2 != null) node1.distance(node2) else null
            val distanceStr = distanceMeters?.toDistanceString(MeasurementSystem.METRIC)
            if (distanceStr != null) {
                DistanceMetricCard(distanceStr = distanceStr, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SnrMetricCard(bestSnr: Float, modifier: Modifier = Modifier) {
    val (qualityTextRes, qualityColor) =
        when {
            bestSnr >= 2.0f -> Res.string.topology_snr_excellent to Color(COLOR_SNR_EXCELLENT)
            bestSnr >= -4.0f -> Res.string.topology_snr_good to Color(COLOR_SNR_GOOD)
            bestSnr >= -10.0f -> Res.string.topology_snr_fair to Color(COLOR_SNR_FAIR)
            bestSnr >= -16.0f -> Res.string.topology_snr_poor to Color(COLOR_SNR_POOR)
            else -> Res.string.topology_snr_critical to Color(COLOR_SNR_CRITICAL)
        }

    OutlinedCard(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "SNR",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${NumberFormatter.format(bestSnr, 1)} dB",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = qualityColor,
            )
            Text(
                text = stringResource(qualityTextRes),
                style = MaterialTheme.typography.labelSmall,
                color = qualityColor,
            )
        }
    }
}

@Composable
private fun RssiMetricCard(rssi: Int, modifier: Modifier = Modifier) {
    OutlinedCard(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "RSSI",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$rssi dBm",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DistanceMetricCard(distanceStr: String, modifier: Modifier = Modifier) {
    OutlinedCard(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(Res.string.distance),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = distanceStr,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun NodeLinkCard(
    nodeNum: Int,
    node: Node?,
    onClick: () -> Unit,
    onDetailClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(32.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text =
                            node?.user?.short_name?.take(3)?.uppercase()
                                ?: nodeNum.toUInt().toString(16).takeLast(3).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                IconButton(
                    onClick = onDetailClick,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = MeshtasticIcons.ChevronRight,
                        contentDescription = stringResource(Res.string.topology_view_node),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text =
                node?.user?.long_name?.takeIf { it.isNotBlank() }
                    ?: node?.user?.short_name
                    ?: "!${nodeNum.toUInt().toString(16).padStart(8, '0')}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                text = "!${nodeNum.toUInt().toString(16).padStart(8, '0')}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun runSimulationStep(
    nodes: List<SimulationNode>,
    edges: List<TopologyEdge>,
    pinnedNodeNum: Int? = null,
): Float {
    val nodeCount = nodes.size
    if (nodeCount == 0) return 0f

    // 1. Repulsion between all pairs
    for (i in 0 until nodeCount) {
        val n1 = nodes[i]
        for (j in i + 1 until nodeCount) {
            val n2 = nodes[j]
            val dx = n1.x - n2.x
            val dy = n1.y - n2.y
            val distSq = dx * dx + dy * dy + 150f
            val dist = sqrt(distSq)
            val force = (28000f / (distSq * dist)).coerceAtMost(30f)
            val fx = dx * force
            val fy = dy * force
            n1.vx += fx
            n1.vy += fy
            n2.vx -= fx
            n2.vy -= fy
        }
    }

    // 2. Spring attraction along edges
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
        val force = (delta * 0.04f).coerceIn(-25f, 25f)
        val fx = (dx / dist) * force
        val fy = (dy / dist) * force

        n1.vx += fx
        n1.vy += fy
        n2.vx -= fx
        n2.vy -= fy
    }

    // 3. Gravity and damping
    var totalEnergy = 0f
    val kGravity = 0.008f
    val damping = 0.82f
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
