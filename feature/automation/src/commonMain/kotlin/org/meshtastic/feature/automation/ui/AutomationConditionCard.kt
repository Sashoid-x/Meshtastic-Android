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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.automation.model.AutomationCondition
import org.meshtastic.core.automation.model.AutomationTrigger
import org.meshtastic.core.automation.model.ComparisonOperator
import org.meshtastic.core.automation.model.LocationConditionType
import org.meshtastic.core.automation.model.LogicalOperator
import org.meshtastic.core.automation.model.TelemetryMetricType
import org.meshtastic.core.model.Node
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.automation_add_condition
import org.meshtastic.core.resources.automation_condition_channel
import org.meshtastic.core.resources.automation_condition_days_of_week
import org.meshtastic.core.resources.automation_condition_node_is_favorite
import org.meshtastic.core.resources.automation_condition_node_name
import org.meshtastic.core.resources.automation_condition_not_fired_recently
import org.meshtastic.core.resources.automation_condition_radio_connected
import org.meshtastic.core.resources.automation_condition_time_of_day
import org.meshtastic.core.resources.automation_conditions_match_all
import org.meshtastic.core.resources.automation_conditions_match_any
import org.meshtastic.core.resources.automation_step_1_conditions
import org.meshtastic.core.resources.automation_step_1_event
import org.meshtastic.core.resources.automation_step_1_filter_conditions
import org.meshtastic.core.resources.automation_step_1_no_conditions_hint
import org.meshtastic.core.resources.automation_step_2_conditions
import org.meshtastic.core.resources.filter_regex_pattern
import org.meshtastic.core.ui.icon.Close
import org.meshtastic.core.ui.icon.MeshtasticIcons

private const val DEFAULT_START_HOUR = 8
private const val DEFAULT_END_HOUR = 22
private const val DEFAULT_COOLDOWN_SECONDS = 60

@Suppress("LongMethod")
@Composable
fun ConditionsUnifiedCard(
    trigger: AutomationTrigger,
    onTriggerChange: (AutomationTrigger) -> Unit,
    conditionOperator: LogicalOperator,
    onConditionOperatorChange: (LogicalOperator) -> Unit,
    conditions: List<AutomationCondition>,
    onAddCondition: (AutomationCondition) -> Unit,
    onUpdateCondition: (Int, AutomationCondition) -> Unit,
    onRemoveCondition: (Int) -> Unit,
    nodes: Map<Int, Node>,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(Res.string.automation_step_1_conditions),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            // 1. Событие (Что произошло)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(Res.string.automation_step_1_event),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                TriggerSelectionContent(
                    trigger = trigger,
                    onTriggerChange = onTriggerChange,
                    nodes = nodes,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 2. Дополнительные условия (Критерии)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.automation_step_1_filter_conditions),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (conditions.isNotEmpty()) {
                        Text(
                            text = "${conditions.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                if (conditions.size >= 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = conditionOperator == LogicalOperator.AND,
                            onClick = { onConditionOperatorChange(LogicalOperator.AND) },
                            label = { Text(stringResource(Res.string.automation_conditions_match_all)) },
                        )
                        FilterChip(
                            selected = conditionOperator == LogicalOperator.OR,
                            onClick = { onConditionOperatorChange(LogicalOperator.OR) },
                            label = { Text(stringResource(Res.string.automation_conditions_match_any)) },
                        )
                    }
                }

                if (conditions.isEmpty()) {
                    Card(
                        colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(Res.string.automation_step_1_no_conditions_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                } else {
                    conditions.forEachIndexed { index, condition ->
                        ConditionItemRow(
                            condition = condition,
                            onUpdate = { onUpdateCondition(index, it) },
                            onRemove = { onRemoveCondition(index) },
                            nodes = nodes,
                        )
                    }
                }

                AddConditionMenu(onAddCondition = onAddCondition)
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
fun ConditionsCard(
    conditionOperator: LogicalOperator,
    onConditionOperatorChange: (LogicalOperator) -> Unit,
    conditions: List<AutomationCondition>,
    onAddCondition: (AutomationCondition) -> Unit,
    onUpdateCondition: (Int, AutomationCondition) -> Unit,
    onRemoveCondition: (Int) -> Unit,
    nodes: Map<Int, Node>,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(Res.string.automation_step_2_conditions),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            if (conditions.size >= 2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = conditionOperator == LogicalOperator.AND,
                        onClick = { onConditionOperatorChange(LogicalOperator.AND) },
                        label = { Text(stringResource(Res.string.automation_conditions_match_all)) },
                    )
                    FilterChip(
                        selected = conditionOperator == LogicalOperator.OR,
                        onClick = { onConditionOperatorChange(LogicalOperator.OR) },
                        label = { Text(stringResource(Res.string.automation_conditions_match_any)) },
                    )
                }
            }

            conditions.forEachIndexed { index, condition ->
                ConditionItemRow(
                    condition = condition,
                    onUpdate = { onUpdateCondition(index, it) },
                    onRemove = { onRemoveCondition(index) },
                    nodes = nodes,
                )
            }

            AddConditionMenu(onAddCondition = onAddCondition)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddConditionMenu(onAddCondition: (AutomationCondition) -> Unit, modifier: Modifier = Modifier) {
    var menuExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = menuExpanded,
        onExpandedChange = { menuExpanded = it },
        modifier = modifier,
    ) {
        OutlinedButton(
            onClick = { menuExpanded = true },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        ) {
            Text(stringResource(Res.string.automation_add_condition))
        }
        ExposedDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            ConditionMenuItems(
                onSelect = {
                    onAddCondition(it)
                    menuExpanded = false
                },
            )
        }
    }
}

@Suppress("MultipleEmitters")
@Composable
private fun ColumnScope.ConditionMenuItems(onSelect: (AutomationCondition) -> Unit) {
    DropdownMenuItem(
        text = { Text("👤 Фильтр по ноде (Выбор, Имя, Избранное)") },
        onClick = { onSelect(AutomationCondition.NodeFilter()) },
    )
    DropdownMenuItem(
        text = { Text("📊 Порог телеметрии (Батарея, Вольты, Сенсоры)") },
        onClick = { onSelect(AutomationCondition.TelemetryThreshold()) },
    )
    DropdownMenuItem(
        text = { Text("📍 Геозона / Дистанция (Внутри, Снаружи, Ближе, Дальше)") },
        onClick = { onSelect(AutomationCondition.LocationFilter()) },
    )
    DropdownMenuItem(
        text = { Text("💬 Фильтр текста или реакции") },
        onClick = { onSelect(AutomationCondition.MessageFilter()) },
    )
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.automation_condition_channel)) },
        onClick = { onSelect(AutomationCondition.ChannelIs(0)) },
    )
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.automation_condition_time_of_day)) },
        onClick = { onSelect(AutomationCondition.TimeOfDay(DEFAULT_START_HOUR, DEFAULT_END_HOUR)) },
    )
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.automation_condition_radio_connected)) },
        onClick = { onSelect(AutomationCondition.RadioIsConnected(requiredConnected = true)) },
    )
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.automation_condition_not_fired_recently)) },
        onClick = { onSelect(AutomationCondition.NotFiredRecently(DEFAULT_COOLDOWN_SECONDS)) },
    )
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun ConditionItemRow(
    condition: AutomationCondition,
    onUpdate: (AutomationCondition) -> Unit,
    onRemove: () -> Unit,
    nodes: Map<Int, Node>,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (condition) {
                    is AutomationCondition.NodeFilter -> {
                        NodePickerField(
                            selectedNodeId = condition.nodeId,
                            nodes = nodes,
                            onSelectNode = { onUpdate(condition.copy(nodeId = it)) },
                            label = "Требуемая нода",
                        )
                        OutlinedTextField(
                            value = condition.nameContains,
                            onValueChange = { onUpdate(condition.copy(nameContains = it)) },
                            label = { Text("Имя содержит подстроку") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = condition.isFavoriteOnly,
                                onCheckedChange = { onUpdate(condition.copy(isFavoriteOnly = it)) },
                            )
                            Text(
                                text = "Только избранные ноды (⭐)",
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }

                    is AutomationCondition.TelemetryThreshold -> {
                        TelemetryThresholdForm(condition = condition, onUpdate = onUpdate)
                    }

                    is AutomationCondition.LocationFilter -> {
                        LocationFilterForm(condition = condition, onUpdate = onUpdate)
                    }

                    is AutomationCondition.MessageFilter -> {
                        OutlinedTextField(
                            value = condition.pattern,
                            onValueChange = { onUpdate(condition.copy(pattern = it)) },
                            label = { Text("Текст сообщения содержит") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = condition.isRegex,
                                onCheckedChange = { onUpdate(condition.copy(isRegex = it)) },
                            )
                            Text(
                                text = stringResource(Res.string.filter_regex_pattern),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        OutlinedTextField(
                            value = condition.emoji,
                            onValueChange = { onUpdate(condition.copy(emoji = it)) },
                            label = { Text("Смайл / Реакция (например 🚨)") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    is AutomationCondition.NodeNameContains -> {
                        OutlinedTextField(
                            value = condition.substring,
                            onValueChange = { onUpdate(condition.copy(substring = it)) },
                            label = { Text(stringResource(Res.string.automation_condition_node_name)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    is AutomationCondition.NodeInList -> {
                        OutlinedTextField(
                            value = condition.nodeIds.joinToString(","),
                            onValueChange = { input ->
                                val ids = input.split(",").mapNotNull { it.trim().toIntOrNull() }
                                onUpdate(condition.copy(nodeIds = ids))
                            },
                            label = { Text("Список Node ID через запятую") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    is AutomationCondition.NodeIsFavorite -> {
                        Text(
                            text = stringResource(Res.string.automation_condition_node_is_favorite),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    is AutomationCondition.ChannelIs -> {
                        ChannelPickerBox(
                            selectedChannel = condition.channelIndex,
                            onSelectChannel = { onUpdate(condition.copy(channelIndex = it ?: 0)) },
                            label = "Канал связи",
                            allowAll = false,
                        )
                    }

                    is AutomationCondition.TimeOfDay -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = condition.startHour.toString(),
                                onValueChange = {
                                    onUpdate(condition.copy(startHour = it.toIntOrNull() ?: condition.startHour))
                                },
                                label = { Text("С (час 0-23)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = condition.endHour.toString(),
                                onValueChange = {
                                    onUpdate(condition.copy(endHour = it.toIntOrNull() ?: condition.endHour))
                                },
                                label = { Text("По (час 0-23)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    is AutomationCondition.DaysOfWeek -> {
                        Text(
                            text = stringResource(Res.string.automation_condition_days_of_week),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    is AutomationCondition.RadioIsConnected -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = condition.requiredConnected,
                                onCheckedChange = { onUpdate(condition.copy(requiredConnected = it)) },
                            )
                            Text(
                                text = stringResource(Res.string.automation_condition_radio_connected),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }

                    is AutomationCondition.NotFiredRecently -> {
                        OutlinedTextField(
                            value = condition.windowSeconds.toString(),
                            onValueChange = {
                                onUpdate(condition.copy(windowSeconds = it.toIntOrNull() ?: condition.windowSeconds))
                            },
                            label = { Text(stringResource(Res.string.automation_condition_not_fired_recently)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            IconButton(onClick = onRemove) {
                Icon(MeshtasticIcons.Close, contentDescription = "Remove Condition")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TelemetryMetricDropdown(
    metric: TelemetryMetricType,
    onSelectMetric: (TelemetryMetricType) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val metricName =
        when (metric) {
            TelemetryMetricType.BATTERY_PERCENT -> "Батарея (%)"
            TelemetryMetricType.VOLTAGE -> "Напряжение (В)"
            TelemetryMetricType.TEMPERATURE -> "Температура (°C)"
            TelemetryMetricType.HUMIDITY -> "Влажность (%)"
            TelemetryMetricType.BAROMETRIC_PRESSURE -> "Давление (гПа)"
            TelemetryMetricType.AIR_QUALITY_IAQ -> "Качество воздуха (IAQ)"
            TelemetryMetricType.AIR_QUALITY_CO2 -> "CO2 (ppm)"
            TelemetryMetricType.AIR_QUALITY_PM25 -> "PM2.5"
            TelemetryMetricType.SOIL_MOISTURE -> "Влажность почвы (%)"
        }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = metricName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Параметр телеметрии") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            TelemetryMetricType.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.name) },
                    onClick = {
                        onSelectMetric(m)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TelemetryThresholdForm(
    condition: AutomationCondition.TelemetryThreshold,
    onUpdate: (AutomationCondition) -> Unit,
    modifier: Modifier = Modifier,
) {
    var opMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TelemetryMetricDropdown(
            metric = condition.metric,
            onSelectMetric = { onUpdate(condition.copy(metric = it)) },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExposedDropdownMenuBox(
                expanded = opMenuExpanded,
                onExpandedChange = { opMenuExpanded = it },
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = if (condition.operator == ComparisonOperator.LESS_THAN) "Меньше (<)" else "Больше (>)",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Условие") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = opMenuExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = opMenuExpanded, onDismissRequest = { opMenuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Меньше (<)") },
                        onClick = {
                            onUpdate(condition.copy(operator = ComparisonOperator.LESS_THAN))
                            opMenuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Больше (>)") },
                        onClick = {
                            onUpdate(condition.copy(operator = ComparisonOperator.GREATER_THAN))
                            opMenuExpanded = false
                        },
                    )
                }
            }

            OutlinedTextField(
                value = condition.threshold.toString(),
                onValueChange = { onUpdate(condition.copy(threshold = it.toFloatOrNull() ?: condition.threshold)) },
                label = { Text("Порог") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun GeofenceInputs(
    centerLatitude: Double,
    centerLongitude: Double,
    radiusMeters: Double,
    onUpdateCoords: (Double, Double, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = centerLatitude.toString(),
                onValueChange = {
                    onUpdateCoords(it.toDoubleOrNull() ?: centerLatitude, centerLongitude, radiusMeters)
                },
                label = { Text("Широта") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = centerLongitude.toString(),
                onValueChange = {
                    onUpdateCoords(centerLatitude, it.toDoubleOrNull() ?: centerLongitude, radiusMeters)
                },
                label = { Text("Долгота") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = radiusMeters.toString(),
            onValueChange = {
                onUpdateCoords(centerLatitude, centerLongitude, it.toDoubleOrNull() ?: radiusMeters)
            },
            label = { Text("Радиус зоны (метры)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationFilterForm(
    condition: AutomationCondition.LocationFilter,
    onUpdate: (AutomationCondition) -> Unit,
    modifier: Modifier = Modifier,
) {
    var typeMenuExpanded by remember { mutableStateOf(false) }

    val typeLabel =
        when (condition.type) {
            LocationConditionType.WITHIN_GEOFENCE -> "Внутри геозоны (радиус от точки)"
            LocationConditionType.OUTSIDE_GEOFENCE -> "Снаружи геозоны (за пределами)"
            LocationConditionType.CLOSER_THAN -> "Ближе чем дистанция от нас"
            LocationConditionType.FURTHER_THAN -> "Дальше чем дистанция от нас"
        }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(
            expanded = typeMenuExpanded,
            onExpandedChange = { typeMenuExpanded = it },
        ) {
            OutlinedTextField(
                value = typeLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text("Тип локации") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = typeMenuExpanded, onDismissRequest = { typeMenuExpanded = false }) {
                LocationConditionType.entries.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(t.name) },
                        onClick = {
                            onUpdate(condition.copy(type = t))
                            typeMenuExpanded = false
                        },
                    )
                }
            }
        }

        if (
            condition.type == LocationConditionType.CLOSER_THAN || condition.type == LocationConditionType.FURTHER_THAN
        ) {
            OutlinedTextField(
                value = condition.distanceKm.toString(),
                onValueChange = {
                    onUpdate(condition.copy(distanceKm = it.toDoubleOrNull() ?: condition.distanceKm))
                },
                label = { Text("Дистанция (км)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            GeofenceInputs(
                centerLatitude = condition.centerLatitude,
                centerLongitude = condition.centerLongitude,
                radiusMeters = condition.radiusMeters,
                onUpdateCoords = { lat, lon, rad ->
                    onUpdate(condition.copy(centerLatitude = lat, centerLongitude = lon, radiusMeters = rad))
                },
            )
        }
    }
}
