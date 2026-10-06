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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
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
import org.meshtastic.core.resources.automation_channel_label
import org.meshtastic.core.resources.automation_condition_channel
import org.meshtastic.core.resources.automation_condition_comparison_label
import org.meshtastic.core.resources.automation_condition_days_of_week
import org.meshtastic.core.resources.automation_condition_end_hour
import org.meshtastic.core.resources.automation_condition_location_closer
import org.meshtastic.core.resources.automation_condition_location_filter_menu
import org.meshtastic.core.resources.automation_condition_location_further
import org.meshtastic.core.resources.automation_condition_location_outside
import org.meshtastic.core.resources.automation_condition_location_type
import org.meshtastic.core.resources.automation_condition_location_within
import org.meshtastic.core.resources.automation_condition_message_contains
import org.meshtastic.core.resources.automation_condition_message_filter_menu
import org.meshtastic.core.resources.automation_condition_metric_battery
import org.meshtastic.core.resources.automation_condition_metric_co2
import org.meshtastic.core.resources.automation_condition_metric_humidity
import org.meshtastic.core.resources.automation_condition_metric_iaq
import org.meshtastic.core.resources.automation_condition_metric_pm25
import org.meshtastic.core.resources.automation_condition_metric_pressure
import org.meshtastic.core.resources.automation_condition_metric_soil
import org.meshtastic.core.resources.automation_condition_metric_temperature
import org.meshtastic.core.resources.automation_condition_metric_voltage
import org.meshtastic.core.resources.automation_condition_name_contains
import org.meshtastic.core.resources.automation_condition_node_filter_menu
import org.meshtastic.core.resources.automation_condition_node_is_favorite
import org.meshtastic.core.resources.automation_condition_node_list_hint
import org.meshtastic.core.resources.automation_condition_node_name
import org.meshtastic.core.resources.automation_condition_not_fired_recently
import org.meshtastic.core.resources.automation_condition_only_favorites
import org.meshtastic.core.resources.automation_condition_operator_greater
import org.meshtastic.core.resources.automation_condition_operator_less
import org.meshtastic.core.resources.automation_condition_radio_connected
import org.meshtastic.core.resources.automation_condition_reaction_label
import org.meshtastic.core.resources.automation_condition_remove
import org.meshtastic.core.resources.automation_condition_required_node
import org.meshtastic.core.resources.automation_condition_start_hour
import org.meshtastic.core.resources.automation_condition_telemetry_parameter
import org.meshtastic.core.resources.automation_condition_telemetry_threshold_menu
import org.meshtastic.core.resources.automation_condition_threshold_label
import org.meshtastic.core.resources.automation_condition_time_of_day
import org.meshtastic.core.resources.automation_conditions_match_all
import org.meshtastic.core.resources.automation_conditions_match_any
import org.meshtastic.core.resources.automation_day_fri
import org.meshtastic.core.resources.automation_day_mon
import org.meshtastic.core.resources.automation_day_sat
import org.meshtastic.core.resources.automation_day_sun
import org.meshtastic.core.resources.automation_day_thu
import org.meshtastic.core.resources.automation_day_tue
import org.meshtastic.core.resources.automation_day_wed
import org.meshtastic.core.resources.automation_distance_km
import org.meshtastic.core.resources.automation_radius_meters
import org.meshtastic.core.resources.automation_step_1_conditions
import org.meshtastic.core.resources.automation_step_1_event
import org.meshtastic.core.resources.automation_step_1_filter_conditions
import org.meshtastic.core.resources.automation_step_1_no_conditions_hint
import org.meshtastic.core.resources.automation_trigger_latitude
import org.meshtastic.core.resources.automation_trigger_longitude
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddConditionMenu(onAddCondition: (AutomationCondition) -> Unit, modifier: Modifier = Modifier) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }

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
        text = { Text(stringResource(Res.string.automation_condition_node_filter_menu)) },
        onClick = { onSelect(AutomationCondition.NodeFilter()) },
    )
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.automation_condition_telemetry_threshold_menu)) },
        onClick = { onSelect(AutomationCondition.TelemetryThreshold()) },
    )
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.automation_condition_location_filter_menu)) },
        onClick = { onSelect(AutomationCondition.LocationFilter()) },
    )
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.automation_condition_message_filter_menu)) },
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

@Suppress("MagicNumber")
@Composable
private fun DaysOfWeekPicker(selectedDays: List<Int>, onToggleDay: (Int) -> Unit, modifier: Modifier = Modifier) {
    val dayLabels =
        listOf(
            1 to Res.string.automation_day_mon,
            2 to Res.string.automation_day_tue,
            3 to Res.string.automation_day_wed,
            4 to Res.string.automation_day_thu,
            5 to Res.string.automation_day_fri,
            6 to Res.string.automation_day_sat,
            7 to Res.string.automation_day_sun,
        )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        dayLabels.forEach { (dayIndex, labelRes) ->
            FilterChip(
                selected = selectedDays.contains(dayIndex),
                onClick = { onToggleDay(dayIndex) },
                label = { Text(stringResource(labelRes)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
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
                            label = stringResource(Res.string.automation_condition_required_node),
                        )
                        OutlinedTextField(
                            value = condition.nameContains,
                            onValueChange = { onUpdate(condition.copy(nameContains = it)) },
                            label = { Text(stringResource(Res.string.automation_condition_name_contains)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = condition.isFavoriteOnly,
                                onCheckedChange = { onUpdate(condition.copy(isFavoriteOnly = it)) },
                            )
                            Text(
                                text = stringResource(Res.string.automation_condition_only_favorites),
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
                            label = { Text(stringResource(Res.string.automation_condition_message_contains)) },
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
                            label = { Text(stringResource(Res.string.automation_condition_reaction_label)) },
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
                            label = { Text(stringResource(Res.string.automation_condition_node_list_hint)) },
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
                            label = stringResource(Res.string.automation_channel_label),
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
                                label = { Text(stringResource(Res.string.automation_condition_start_hour)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = condition.endHour.toString(),
                                onValueChange = {
                                    onUpdate(condition.copy(endHour = it.toIntOrNull() ?: condition.endHour))
                                },
                                label = { Text(stringResource(Res.string.automation_condition_end_hour)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    is AutomationCondition.DaysOfWeek -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(Res.string.automation_condition_days_of_week),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            DaysOfWeekPicker(
                                selectedDays = condition.days,
                                onToggleDay = { day ->
                                    val newDays =
                                        if (condition.days.contains(day)) {
                                            if (condition.days.size > 1) condition.days - day else condition.days
                                        } else {
                                            (condition.days + day).sorted()
                                        }
                                    onUpdate(condition.copy(days = newDays))
                                },
                            )
                        }
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
                Icon(
                    MeshtasticIcons.Close,
                    contentDescription = stringResource(Res.string.automation_condition_remove),
                )
            }
        }
    }
}

private fun metricTitle(metric: TelemetryMetricType): StringResource = when (metric) {
    TelemetryMetricType.BATTERY_PERCENT -> Res.string.automation_condition_metric_battery
    TelemetryMetricType.VOLTAGE -> Res.string.automation_condition_metric_voltage
    TelemetryMetricType.TEMPERATURE -> Res.string.automation_condition_metric_temperature
    TelemetryMetricType.HUMIDITY -> Res.string.automation_condition_metric_humidity
    TelemetryMetricType.BAROMETRIC_PRESSURE -> Res.string.automation_condition_metric_pressure
    TelemetryMetricType.AIR_QUALITY_IAQ -> Res.string.automation_condition_metric_iaq
    TelemetryMetricType.AIR_QUALITY_CO2 -> Res.string.automation_condition_metric_co2
    TelemetryMetricType.AIR_QUALITY_PM25 -> Res.string.automation_condition_metric_pm25
    TelemetryMetricType.SOIL_MOISTURE -> Res.string.automation_condition_metric_soil
}

private fun locationTypeTitle(type: LocationConditionType): StringResource = when (type) {
    LocationConditionType.WITHIN_GEOFENCE -> Res.string.automation_condition_location_within
    LocationConditionType.OUTSIDE_GEOFENCE -> Res.string.automation_condition_location_outside
    LocationConditionType.CLOSER_THAN -> Res.string.automation_condition_location_closer
    LocationConditionType.FURTHER_THAN -> Res.string.automation_condition_location_further
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TelemetryMetricDropdown(
    metric: TelemetryMetricType,
    onSelectMetric: (TelemetryMetricType) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = stringResource(metricTitle(metric)),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(Res.string.automation_condition_telemetry_parameter)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            TelemetryMetricType.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(stringResource(metricTitle(m))) },
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
    var opMenuExpanded by rememberSaveable { mutableStateOf(false) }

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
                    value =
                    if (condition.operator == ComparisonOperator.LESS_THAN) {
                        stringResource(Res.string.automation_condition_operator_less)
                    } else {
                        stringResource(Res.string.automation_condition_operator_greater)
                    },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(Res.string.automation_condition_comparison_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = opMenuExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = opMenuExpanded, onDismissRequest = { opMenuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.automation_condition_operator_less)) },
                        onClick = {
                            onUpdate(condition.copy(operator = ComparisonOperator.LESS_THAN))
                            opMenuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.automation_condition_operator_greater)) },
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
                label = { Text(stringResource(Res.string.automation_condition_threshold_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun GeofenceInputs(
    centerLatitude: Double?,
    centerLongitude: Double?,
    radiusMeters: Double,
    onUpdateCoords: (Double?, Double?, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = centerLatitude?.toString().orEmpty(),
                onValueChange = {
                    onUpdateCoords(it.toDoubleOrNull(), centerLongitude, radiusMeters)
                },
                label = { Text(stringResource(Res.string.automation_trigger_latitude)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = centerLongitude?.toString().orEmpty(),
                onValueChange = {
                    onUpdateCoords(centerLatitude, it.toDoubleOrNull(), radiusMeters)
                },
                label = { Text(stringResource(Res.string.automation_trigger_longitude)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = radiusMeters.toString(),
            onValueChange = {
                onUpdateCoords(centerLatitude, centerLongitude, it.toDoubleOrNull() ?: radiusMeters)
            },
            label = { Text(stringResource(Res.string.automation_radius_meters)) },
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
    var typeMenuExpanded by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(
            expanded = typeMenuExpanded,
            onExpandedChange = { typeMenuExpanded = it },
        ) {
            OutlinedTextField(
                value = stringResource(locationTypeTitle(condition.type)),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(Res.string.automation_condition_location_type)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = typeMenuExpanded, onDismissRequest = { typeMenuExpanded = false }) {
                LocationConditionType.entries.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(stringResource(locationTypeTitle(t))) },
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
                label = { Text(stringResource(Res.string.automation_distance_km)) },
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
