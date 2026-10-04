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
package org.meshtastic.feature.automation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.meshtastic.core.model.Node
import org.meshtastic.core.ui.icon.Close
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.Nodes
import org.meshtastic.core.ui.icon.Person
import org.meshtastic.core.ui.icon.PlayArrow
import org.meshtastic.core.ui.icon.Search

private const val HEX_RADIX = 16
private const val HEX_PAD = 8
private const val MAX_PICKER_HEIGHT_DP = 400

private fun formatHexNodeId(nodeId: Int?): String =
    nodeId?.toUInt()?.toString(HEX_RADIX)?.padStart(HEX_PAD, '0')?.let { "!$it" } ?: ""

@Composable
fun NodePickerField(
    selectedNodeId: Int?,
    nodes: Map<Int, Node>,
    onSelectNode: (Int?) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    nullLabel: String = "Любая нода (Все)",
) {
    var showDialog by remember { mutableStateOf(false) }
    val selectedNode = selectedNodeId?.let { nodes[it] }

    OutlinedCard(
        onClick = { showDialog = true },
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(2.dp))
                if (selectedNode != null) {
                    Text(
                        text = selectedNode.user.long_name.ifBlank { "Node ${selectedNode.num}" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = formatHexNodeId(selectedNode.num),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else if (selectedNodeId != null) {
                    Text(
                        text = "Node $selectedNodeId",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = formatHexNodeId(selectedNodeId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    Text(
                        text = nullLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(onClick = { showDialog = true }) {
                Icon(MeshtasticIcons.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Выбрать")
            }
        }
    }

    if (showDialog) {
        NodePickerDialog(
            currentNodeId = selectedNodeId,
            nodes = nodes.values.toList(),
            onDismiss = { showDialog = false },
            onSelect = {
                onSelectNode(it)
                showDialog = false
            },
            nullLabel = nullLabel,
        )
    }
}

@Composable
fun NodePickerDialog(
    currentNodeId: Int?,
    nodes: List<Node>,
    onDismiss: () -> Unit,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    nullLabel: String = "Любая нода (Все)",
) {
    var searchQuery by remember { mutableStateOf("") }

    val filtered =
        remember(nodes, searchQuery) {
            if (searchQuery.isBlank()) {
                nodes.sortedByDescending { it.isFavorite }
            } else {
                nodes
                    .filter { node ->
                        node.user.long_name.contains(searchQuery, ignoreCase = true) ||
                            node.user.short_name.contains(searchQuery, ignoreCase = true) ||
                            formatHexNodeId(node.num).contains(searchQuery, ignoreCase = true) ||
                            node.num.toString().contains(searchQuery)
                    }
                    .sortedByDescending { it.isFavorite }
            }
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text("Выберите ноду") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = MAX_PICKER_HEIGHT_DP.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Поиск по имени или !HEX") },
                    leadingIcon = { Icon(MeshtasticIcons.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(MeshtasticIcons.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                NodePickerList(
                    filteredNodes = filtered,
                    currentNodeId = currentNodeId,
                    nullLabel = nullLabel,
                    onSelect = onSelect,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

@Composable
private fun NodePickerList(
    filteredNodes: List<Node>,
    currentNodeId: Int?,
    nullLabel: String,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { onSelect(null) },
                colors =
                CardDefaults.cardColors(
                    containerColor =
                    if (currentNodeId == null) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MeshtasticIcons.Nodes, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = nullLabel,
                        fontWeight = if (currentNodeId == null) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        items(filteredNodes) { node ->
            val isSelected = node.num == currentNodeId
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { onSelect(node.num) },
                colors =
                CardDefaults.cardColors(
                    containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = node.user.long_name.ifBlank { "Node ${node.num}" },
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            )
                            if (node.isFavorite) {
                                Text(" ⭐", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(
                            text = "${node.user.short_name} • ${formatHexNodeId(node.num)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

private const val CHANNEL_COUNT = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelPickerBox(
    selectedChannel: Int?,
    onSelectChannel: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Канал связи",
    allowAll: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }

    val currentText =
        when (selectedChannel) {
            null -> if (allowAll) "Любой канал" else "Канал 0 (Primary)"
            0 -> "Канал 0 (Primary)"
            else -> "Канал $selectedChannel"
        }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = currentText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (allowAll) {
                DropdownMenuItem(
                    text = { Text("Любой канал") },
                    onClick = {
                        onSelectChannel(null)
                        expanded = false
                    },
                )
            }
            repeat(CHANNEL_COUNT) { ch ->
                DropdownMenuItem(
                    text = { Text(if (ch == 0) "Канал 0 (Primary)" else "Канал $ch") },
                    onClick = {
                        onSelectChannel(ch)
                        expanded = false
                    },
                )
            }
        }
    }
}

data class TemplateChip(val label: String, val token: String)

private val TEMPLATE_VARIABLES =
    listOf(
        TemplateChip("+ Имя", "{node_name}"),
        TemplateChip("+ !Hex", "{node_hex}"),
        TemplateChip("+ Батарея", "{battery_level}%"),
        TemplateChip("+ Текст", "{text}"),
        TemplateChip("+ Канал", "{channel}"),
        TemplateChip("+ Дистанция", "{distance_km}"),
        TemplateChip("+ Температура", "{temperature}"),
        TemplateChip("+ Влажность", "{humidity}"),
    )

@Composable
fun VariableChipsRow(onInsert: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Вставить переменную в текст:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TEMPLATE_VARIABLES.forEach { chip ->
                AssistChip(
                    onClick = { onInsert(chip.token) },
                    label = { Text(chip.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

@Composable
fun TestActionButton(onTest: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onTest,
        modifier = modifier,
    ) {
        Icon(MeshtasticIcons.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text("Тест действия", style = MaterialTheme.typography.labelMedium)
    }
}
