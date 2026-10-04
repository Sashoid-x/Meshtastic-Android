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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import org.meshtastic.core.automation.model.AirQualityMetricType
import org.meshtastic.core.automation.model.AutomationTrigger
import org.meshtastic.core.automation.model.ComparisonOperator
import org.meshtastic.core.automation.model.EnvironmentMetricType
import org.meshtastic.core.automation.model.GeofenceTransition
import org.meshtastic.core.model.Node
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.automation_direct_message_only
import org.meshtastic.core.resources.automation_distance_km
import org.meshtastic.core.resources.automation_emoji_reaction
import org.meshtastic.core.resources.automation_max_hops
import org.meshtastic.core.resources.automation_message_pattern
import org.meshtastic.core.resources.automation_min_distance_meters
import org.meshtastic.core.resources.automation_min_hops
import org.meshtastic.core.resources.automation_radius_meters
import org.meshtastic.core.resources.automation_step_1_trigger
import org.meshtastic.core.resources.automation_threshold_percent
import org.meshtastic.core.resources.automation_threshold_value
import org.meshtastic.core.resources.automation_timeout_minutes
import org.meshtastic.core.resources.automation_trigger_air_quality
import org.meshtastic.core.resources.automation_trigger_device_battery_low
import org.meshtastic.core.resources.automation_trigger_environment
import org.meshtastic.core.resources.automation_trigger_geofence
import org.meshtastic.core.resources.automation_trigger_hop_limit
import org.meshtastic.core.resources.automation_trigger_message_received
import org.meshtastic.core.resources.automation_trigger_node_appeared
import org.meshtastic.core.resources.automation_trigger_node_battery_low
import org.meshtastic.core.resources.automation_trigger_node_disappeared
import org.meshtastic.core.resources.automation_trigger_node_moved
import org.meshtastic.core.resources.automation_trigger_proximity
import org.meshtastic.core.resources.automation_trigger_radio_connected
import org.meshtastic.core.resources.automation_trigger_radio_disconnected
import org.meshtastic.core.resources.automation_trigger_reaction_received
import org.meshtastic.core.resources.automation_trigger_schedule
import org.meshtastic.core.resources.automation_trigger_soil_moisture
import org.meshtastic.core.resources.filter_regex_pattern

private const val DEFAULT_TIMEOUT_MINUTES = 30
private const val DEFAULT_RADIUS_METERS = 500.0
private const val DEFAULT_DISTANCE_KM = 5.0
private const val DEFAULT_MIN_DISTANCE_METERS = 50.0
private const val DEFAULT_TEMP_THRESHOLD = 30.0f
private const val DEFAULT_CO2_THRESHOLD = 1000.0f
private const val DEFAULT_SOIL_MOISTURE_THRESHOLD = 20.0f
private const val DEFAULT_BATTERY_THRESHOLD = 20
private const val DEFAULT_MIN_HOPS_THRESHOLD = 3
private const val BATTERY_SLIDER_MIN = 5f
private const val BATTERY_SLIDER_MAX = 50f
private const val BATTERY_SLIDER_STEPS = 8

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun TriggerCard(
    trigger: AutomationTrigger,
    onTriggerChange: (AutomationTrigger) -> Unit,
    nodes: Map<Int, Node>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(Res.string.automation_step_1_trigger),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            TriggerSelectionContent(
                trigger = trigger,
                onTriggerChange = onTriggerChange,
                nodes = nodes,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriggerSelectionContent(
    trigger: AutomationTrigger,
    onTriggerChange: (AutomationTrigger) -> Unit,
    nodes: Map<Int, Node>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = triggerLabel(trigger),
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                TriggerDropdownItems(
                    onSelect = {
                        onTriggerChange(it)
                        expanded = false
                    },
                )
            }
        }

        TriggerFormFields(trigger = trigger, onTriggerChange = onTriggerChange, nodes = nodes)
    }
}

@Suppress("LongMethod", "MultipleEmitters")
@Composable
private fun ColumnScope.TriggerDropdownItems(onSelect: (AutomationTrigger) -> Unit) {
    TriggerMenuItem(Res.string.automation_trigger_message_received) {
        onSelect(AutomationTrigger.MessageReceived())
    }
    TriggerMenuItem(Res.string.automation_trigger_reaction_received) {
        onSelect(AutomationTrigger.ReactionReceived(emoji = "🚨"))
    }
    TriggerMenuItem(Res.string.automation_trigger_node_appeared) {
        onSelect(AutomationTrigger.NodeAppeared())
    }
    TriggerMenuItem(Res.string.automation_trigger_node_disappeared) {
        onSelect(AutomationTrigger.NodeDisappeared(timeoutMinutes = DEFAULT_TIMEOUT_MINUTES))
    }
    TriggerMenuItem(Res.string.automation_trigger_node_battery_low) {
        onSelect(AutomationTrigger.NodeBatteryLow(thresholdPercent = DEFAULT_BATTERY_THRESHOLD))
    }
    DropdownMenuItem(
        text = { Text("📊 Получена телеметрия (Любая / Пороги в ЕСЛИ)") },
        onClick = { onSelect(AutomationTrigger.TelemetryReceived()) },
    )
    DropdownMenuItem(
        text = { Text("📍 Получены координаты (Геопозиция в ЕСЛИ)") },
        onClick = { onSelect(AutomationTrigger.PositionUpdated()) },
    )
    TriggerMenuItem(Res.string.automation_trigger_environment) {
        onSelect(
            AutomationTrigger.EnvironmentThreshold(
                metricType = EnvironmentMetricType.TEMPERATURE,
                operator = ComparisonOperator.GREATER_THAN,
                thresholdValue = DEFAULT_TEMP_THRESHOLD,
            ),
        )
    }
    TriggerMenuItem(Res.string.automation_trigger_air_quality) {
        onSelect(
            AutomationTrigger.AirQualityThreshold(
                metricType = AirQualityMetricType.CO2,
                operator = ComparisonOperator.GREATER_THAN,
                thresholdValue = DEFAULT_CO2_THRESHOLD,
            ),
        )
    }
    TriggerMenuItem(Res.string.automation_trigger_soil_moisture) {
        onSelect(
            AutomationTrigger.SoilMoistureThreshold(
                operator = ComparisonOperator.LESS_THAN,
                thresholdPercent = DEFAULT_SOIL_MOISTURE_THRESHOLD,
            ),
        )
    }
    TriggerMenuItem(Res.string.automation_trigger_geofence) {
        onSelect(
            AutomationTrigger.NodeGeofence(
                centerLatitude = 0.0,
                centerLongitude = 0.0,
                radiusMeters = DEFAULT_RADIUS_METERS,
                transition = GeofenceTransition.ENTER,
            ),
        )
    }
    TriggerMenuItem(Res.string.automation_trigger_proximity) {
        onSelect(
            AutomationTrigger.NodeProximity(
                distanceKilometers = DEFAULT_DISTANCE_KM,
                operator = ComparisonOperator.LESS_THAN,
            ),
        )
    }
    TriggerMenuItem(Res.string.automation_trigger_node_moved) {
        onSelect(AutomationTrigger.NodeMoved(minDistanceMeters = DEFAULT_MIN_DISTANCE_METERS))
    }
    TriggerMenuItem(Res.string.automation_trigger_hop_limit) {
        onSelect(AutomationTrigger.HopLimitChanged(minHops = DEFAULT_MIN_HOPS_THRESHOLD))
    }
    TriggerMenuItem(Res.string.automation_trigger_schedule) {
        onSelect(AutomationTrigger.Schedule(cronExpression = "* * * * *"))
    }
    TriggerMenuItem(Res.string.automation_trigger_device_battery_low) {
        onSelect(AutomationTrigger.DeviceBatteryLow(thresholdPercent = DEFAULT_BATTERY_THRESHOLD))
    }
    TriggerMenuItem(Res.string.automation_trigger_radio_connected) {
        onSelect(AutomationTrigger.RadioConnected)
    }
    TriggerMenuItem(Res.string.automation_trigger_radio_disconnected) {
        onSelect(AutomationTrigger.RadioDisconnected)
    }
}

@Composable
private fun TriggerMenuItem(resource: org.jetbrains.compose.resources.StringResource, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(resource)) }, onClick = onClick)
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun ColumnScope.TriggerFormFields(
    trigger: AutomationTrigger,
    onTriggerChange: (AutomationTrigger) -> Unit,
    nodes: Map<Int, Node>,
) {
    when (trigger) {
        is AutomationTrigger.MessageReceived -> {
            NodePickerField(
                selectedNodeId = trigger.fromNodeId,
                nodes = nodes,
                onSelectNode = { onTriggerChange(trigger.copy(fromNodeId = it)) },
                label = "Отправитель сообщения",
            )
            ChannelPickerBox(
                selectedChannel = trigger.channelIndex,
                onSelectChannel = { onTriggerChange(trigger.copy(channelIndex = it)) },
                label = "Канал сообщений",
            )
            OutlinedTextField(
                value = trigger.pattern,
                onValueChange = { onTriggerChange(trigger.copy(pattern = it)) },
                label = { Text(stringResource(Res.string.automation_message_pattern)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = trigger.isDirectMessage,
                    onCheckedChange = { onTriggerChange(trigger.copy(isDirectMessage = it)) },
                )
                Text(
                    text = stringResource(Res.string.automation_direct_message_only),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = trigger.isRegex,
                    onCheckedChange = { onTriggerChange(trigger.copy(isRegex = it)) },
                )
                Text(
                    text = stringResource(Res.string.filter_regex_pattern),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        is AutomationTrigger.ReactionReceived -> {
            NodePickerField(
                selectedNodeId = trigger.fromNodeId,
                nodes = nodes,
                onSelectNode = { onTriggerChange(trigger.copy(fromNodeId = it)) },
                label = "Отправитель реакции",
            )
            ChannelPickerBox(
                selectedChannel = trigger.channelIndex,
                onSelectChannel = { onTriggerChange(trigger.copy(channelIndex = it)) },
                label = "Канал",
            )
            OutlinedTextField(
                value = trigger.emoji,
                onValueChange = { onTriggerChange(trigger.copy(emoji = it)) },
                label = { Text(stringResource(Res.string.automation_emoji_reaction)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is AutomationTrigger.NodeBatteryLow -> {
            NodePickerField(
                selectedNodeId = trigger.nodeId,
                nodes = nodes,
                onSelectNode = { onTriggerChange(trigger.copy(nodeId = it)) },
                label = "Нода",
            )
            Text(stringResource(Res.string.automation_threshold_percent, trigger.thresholdPercent))
            Slider(
                value = trigger.thresholdPercent.toFloat(),
                onValueChange = { onTriggerChange(trigger.copy(thresholdPercent = it.toInt())) },
                valueRange = BATTERY_SLIDER_MIN..BATTERY_SLIDER_MAX,
                steps = BATTERY_SLIDER_STEPS,
            )
        }

        is AutomationTrigger.TelemetryReceived -> {
            NodePickerField(
                selectedNodeId = trigger.fromNodeId,
                nodes = nodes,
                onSelectNode = { onTriggerChange(trigger.copy(fromNodeId = it)) },
                label = "Нода-источник телеметрии",
            )
        }

        is AutomationTrigger.PositionUpdated -> {
            NodePickerField(
                selectedNodeId = trigger.fromNodeId,
                nodes = nodes,
                onSelectNode = { onTriggerChange(trigger.copy(fromNodeId = it)) },
                label = "Нода с обновлением позиции",
            )
        }

        is AutomationTrigger.NodeAppeared -> {
            NodePickerField(
                selectedNodeId = trigger.nodeId,
                nodes = nodes,
                onSelectNode = { onTriggerChange(trigger.copy(nodeId = it)) },
                label = "Нода, которая появилась в сети",
            )
        }

        is AutomationTrigger.NodeDisappeared -> {
            NodePickerField(
                selectedNodeId = trigger.nodeId,
                nodes = nodes,
                onSelectNode = { onTriggerChange(trigger.copy(nodeId = it)) },
                label = "Нода, которая пропала из сети",
            )
            OutlinedTextField(
                value = trigger.timeoutMinutes.toString(),
                onValueChange = {
                    onTriggerChange(trigger.copy(timeoutMinutes = it.toIntOrNull() ?: trigger.timeoutMinutes))
                },
                label = { Text(stringResource(Res.string.automation_timeout_minutes)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is AutomationTrigger.EnvironmentThreshold -> {
            NodePickerField(
                selectedNodeId = trigger.nodeId,
                nodes = nodes,
                onSelectNode = { onTriggerChange(trigger.copy(nodeId = it)) },
                label = "Нода с датчиком",
            )
            OutlinedTextField(
                value = trigger.thresholdValue.toString(),
                onValueChange = {
                    onTriggerChange(trigger.copy(thresholdValue = it.toFloatOrNull() ?: trigger.thresholdValue))
                },
                label = { Text("${trigger.metricType.name} ${stringResource(Res.string.automation_threshold_value)}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is AutomationTrigger.AirQualityThreshold -> {
            NodePickerField(
                selectedNodeId = trigger.nodeId,
                nodes = nodes,
                onSelectNode = { onTriggerChange(trigger.copy(nodeId = it)) },
                label = "Нода с датчиком воздуха",
            )
            OutlinedTextField(
                value = trigger.thresholdValue.toString(),
                onValueChange = {
                    onTriggerChange(trigger.copy(thresholdValue = it.toFloatOrNull() ?: trigger.thresholdValue))
                },
                label = { Text("${trigger.metricType.name} ${stringResource(Res.string.automation_threshold_value)}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is AutomationTrigger.SoilMoistureThreshold -> {
            NodePickerField(
                selectedNodeId = trigger.nodeId,
                nodes = nodes,
                onSelectNode = { onTriggerChange(trigger.copy(nodeId = it)) },
                label = "Нода с датчиком влажности почвы",
            )
            OutlinedTextField(
                value = trigger.thresholdPercent.toString(),
                onValueChange = {
                    onTriggerChange(trigger.copy(thresholdPercent = it.toFloatOrNull() ?: trigger.thresholdPercent))
                },
                label = { Text(stringResource(Res.string.automation_threshold_value)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is AutomationTrigger.NodeGeofence -> {
            NodePickerField(
                selectedNodeId = trigger.nodeId,
                nodes = nodes,
                onSelectNode = { onTriggerChange(trigger.copy(nodeId = it)) },
                label = "Отслеживаемая нода",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = trigger.centerLatitude.toString(),
                    onValueChange = {
                        onTriggerChange(trigger.copy(centerLatitude = it.toDoubleOrNull() ?: trigger.centerLatitude))
                    },
                    label = { Text("Широта (Lat)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = trigger.centerLongitude.toString(),
                    onValueChange = {
                        onTriggerChange(trigger.copy(centerLongitude = it.toDoubleOrNull() ?: trigger.centerLongitude))
                    },
                    label = { Text("Долгота (Lon)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = trigger.radiusMeters.toString(),
                onValueChange = {
                    onTriggerChange(trigger.copy(radiusMeters = it.toDoubleOrNull() ?: trigger.radiusMeters))
                },
                label = { Text(stringResource(Res.string.automation_radius_meters)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is AutomationTrigger.NodeProximity -> {
            NodePickerField(
                selectedNodeId = trigger.nodeId,
                nodes = nodes,
                onSelectNode = { onTriggerChange(trigger.copy(nodeId = it)) },
                label = "Нода для дистанции",
            )
            OutlinedTextField(
                value = trigger.distanceKilometers.toString(),
                onValueChange = {
                    onTriggerChange(
                        trigger.copy(distanceKilometers = it.toDoubleOrNull() ?: trigger.distanceKilometers),
                    )
                },
                label = { Text(stringResource(Res.string.automation_distance_km)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is AutomationTrigger.NodeMoved -> {
            NodePickerField(
                selectedNodeId = trigger.nodeId,
                nodes = nodes,
                onSelectNode = { onTriggerChange(trigger.copy(nodeId = it)) },
                label = "Нода",
            )
            OutlinedTextField(
                value = trigger.minDistanceMeters.toString(),
                onValueChange = {
                    onTriggerChange(trigger.copy(minDistanceMeters = it.toDoubleOrNull() ?: trigger.minDistanceMeters))
                },
                label = { Text(stringResource(Res.string.automation_min_distance_meters)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is AutomationTrigger.HopLimitChanged -> {
            NodePickerField(
                selectedNodeId = trigger.nodeId,
                nodes = nodes,
                onSelectNode = { onTriggerChange(trigger.copy(nodeId = it)) },
                label = "Нода",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = trigger.minHops?.toString().orEmpty(),
                    onValueChange = { onTriggerChange(trigger.copy(minHops = it.toIntOrNull())) },
                    label = { Text(stringResource(Res.string.automation_min_hops)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = trigger.maxHops?.toString().orEmpty(),
                    onValueChange = { onTriggerChange(trigger.copy(maxHops = it.toIntOrNull())) },
                    label = { Text(stringResource(Res.string.automation_max_hops)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        is AutomationTrigger.Schedule -> {
            OutlinedTextField(
                value = trigger.cronExpression,
                onValueChange = { onTriggerChange(trigger.copy(cronExpression = it)) },
                label = { Text("Cron выражение (* * * * *)") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is AutomationTrigger.DeviceBatteryLow -> {
            Text(stringResource(Res.string.automation_threshold_percent, trigger.thresholdPercent))
            Slider(
                value = trigger.thresholdPercent.toFloat(),
                onValueChange = { onTriggerChange(trigger.copy(thresholdPercent = it.toInt())) },
                valueRange = BATTERY_SLIDER_MIN..BATTERY_SLIDER_MAX,
                steps = BATTERY_SLIDER_STEPS,
            )
        }

        else -> Unit
    }
}

@Composable
fun triggerLabel(trigger: AutomationTrigger): String = when (trigger) {
    is AutomationTrigger.NodeAppeared -> stringResource(Res.string.automation_trigger_node_appeared)
    is AutomationTrigger.NodeDisappeared -> stringResource(Res.string.automation_trigger_node_disappeared)
    is AutomationTrigger.NodeBatteryLow -> stringResource(Res.string.automation_trigger_node_battery_low)
    is AutomationTrigger.EnvironmentThreshold -> stringResource(Res.string.automation_trigger_environment)
    is AutomationTrigger.AirQualityThreshold -> stringResource(Res.string.automation_trigger_air_quality)
    is AutomationTrigger.SoilMoistureThreshold -> stringResource(Res.string.automation_trigger_soil_moisture)
    is AutomationTrigger.NodeGeofence -> stringResource(Res.string.automation_trigger_geofence)
    is AutomationTrigger.NodeProximity -> stringResource(Res.string.automation_trigger_proximity)
    is AutomationTrigger.NodeMoved -> stringResource(Res.string.automation_trigger_node_moved)
    is AutomationTrigger.HopLimitChanged -> stringResource(Res.string.automation_trigger_hop_limit)
    is AutomationTrigger.ReactionReceived -> stringResource(Res.string.automation_trigger_reaction_received)
    is AutomationTrigger.MessageReceived -> stringResource(Res.string.automation_trigger_message_received)
    is AutomationTrigger.Schedule -> stringResource(Res.string.automation_trigger_schedule)
    is AutomationTrigger.DeviceBatteryLow -> stringResource(Res.string.automation_trigger_device_battery_low)
    is AutomationTrigger.RadioConnected -> stringResource(Res.string.automation_trigger_radio_connected)
    is AutomationTrigger.RadioDisconnected -> stringResource(Res.string.automation_trigger_radio_disconnected)
    is AutomationTrigger.TelemetryReceived -> "📊 Получена телеметрия"
    is AutomationTrigger.PositionUpdated -> "📍 Получены координаты ноды"
    is AutomationTrigger.NodeStatusChanged -> "📶 Изменение статуса ноды"
    is AutomationTrigger.RadioConnectionChanged -> "📻 Подключение к радио"
    is AutomationTrigger.DeviceBatteryChanged -> "🔋 Батарея смартфона"
    is AutomationTrigger.ScheduleTick -> "⏱️ Периодический таймер"
}
