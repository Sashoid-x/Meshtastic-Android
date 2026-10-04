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
@file:Suppress("TooManyFunctions")

package org.meshtastic.feature.automation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.automation.model.AutomationAction
import org.meshtastic.core.model.Node
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.automation_action_broadcast_location
import org.meshtastic.core.resources.automation_action_copy_clipboard
import org.meshtastic.core.resources.automation_action_play_alarm
import org.meshtastic.core.resources.automation_action_play_sound
import org.meshtastic.core.resources.automation_action_remote_gpio
import org.meshtastic.core.resources.automation_action_request_position
import org.meshtastic.core.resources.automation_action_request_telemetry
import org.meshtastic.core.resources.automation_action_send_message
import org.meshtastic.core.resources.automation_action_send_reaction
import org.meshtastic.core.resources.automation_action_send_traceroute
import org.meshtastic.core.resources.automation_action_show_notification
import org.meshtastic.core.resources.automation_action_speak_text
import org.meshtastic.core.resources.automation_action_vibrate
import org.meshtastic.core.resources.automation_add_action
import org.meshtastic.core.resources.automation_duration_seconds
import org.meshtastic.core.resources.automation_gpio_pin
import org.meshtastic.core.resources.automation_gpio_state
import org.meshtastic.core.resources.automation_message_text
import org.meshtastic.core.resources.automation_notification_body
import org.meshtastic.core.resources.automation_notification_title
import org.meshtastic.core.resources.automation_reaction_emoji
import org.meshtastic.core.resources.automation_reaction_quick
import org.meshtastic.core.resources.automation_sound_alarm
import org.meshtastic.core.resources.automation_sound_beep
import org.meshtastic.core.resources.automation_sound_chime
import org.meshtastic.core.resources.automation_sound_double_beep
import org.meshtastic.core.resources.automation_sound_notification
import org.meshtastic.core.resources.automation_sound_picker
import org.meshtastic.core.resources.automation_sound_ringtone
import org.meshtastic.core.resources.automation_sound_siren
import org.meshtastic.core.resources.automation_sound_sos
import org.meshtastic.core.resources.automation_step_2_actions
import org.meshtastic.core.resources.automation_vibrate_double
import org.meshtastic.core.resources.automation_vibrate_long
import org.meshtastic.core.resources.automation_vibrate_pattern
import org.meshtastic.core.resources.automation_vibrate_short
import org.meshtastic.core.resources.automation_vibrate_sos
import org.meshtastic.core.ui.icon.Close
import org.meshtastic.core.ui.icon.MeshtasticIcons

private const val DEFAULT_SPEECH_RATE = 1.0f
private const val DEFAULT_VIBRATE_DURATION_MS = 200L
private const val DEFAULT_ALARM_DURATION_SECONDS = 5

private data class SoundOption(val id: String, val title: StringResource)

private val SOUND_OPTIONS =
    listOf(
        SoundOption("notification", Res.string.automation_sound_notification),
        SoundOption("alarm", Res.string.automation_sound_alarm),
        SoundOption("ringtone", Res.string.automation_sound_ringtone),
        SoundOption("beep", Res.string.automation_sound_beep),
        SoundOption("double_beep", Res.string.automation_sound_double_beep),
        SoundOption("chime", Res.string.automation_sound_chime),
        SoundOption("siren", Res.string.automation_sound_siren),
        SoundOption("morse_sos", Res.string.automation_sound_sos),
    )

private data class VibrationOption(val id: String, val title: StringResource)

private val VIBRATION_OPTIONS =
    listOf(
        VibrationOption("short", Res.string.automation_vibrate_short),
        VibrationOption("double", Res.string.automation_vibrate_double),
        VibrationOption("long", Res.string.automation_vibrate_long),
        VibrationOption("sos", Res.string.automation_vibrate_sos),
    )

private val QUICK_EMOJIS = listOf("👍", "❤️", "⚠️", "🚨", "📍", "👀")

@Composable
fun ActionsCard(
    actions: List<AutomationAction>,
    onAddAction: (AutomationAction) -> Unit,
    onUpdateAction: (Int, AutomationAction) -> Unit,
    onRemoveAction: (Int) -> Unit,
    onTestAction: (AutomationAction) -> Unit,
    nodes: Map<Int, Node>,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(Res.string.automation_step_2_actions),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            actions.forEachIndexed { index, action ->
                ActionItemRow(
                    action = action,
                    onUpdate = { onUpdateAction(index, it) },
                    onRemove = { onRemoveAction(index) },
                    onTest = { onTestAction(action) },
                    nodes = nodes,
                )
            }

            AddActionMenu(onAddAction = onAddAction)
        }
    }
}

private data class ActionOption(val title: StringResource, val create: () -> AutomationAction)

private val ACTION_OPTIONS =
    listOf(
        ActionOption(Res.string.automation_action_show_notification) {
            AutomationAction.ShowNotification("Mesh Alert", "Событие от {node_name}")
        },
        ActionOption(Res.string.automation_action_send_message) {
            AutomationAction.SendMessage("Авто-ответ: {node_name}")
        },
        ActionOption(Res.string.automation_action_play_sound) {
            AutomationAction.PlaySound("notification")
        },
        ActionOption(Res.string.automation_action_vibrate) {
            AutomationAction.VibrateDevice("short", DEFAULT_VIBRATE_DURATION_MS)
        },
        ActionOption(Res.string.automation_action_send_reaction) {
            AutomationAction.SendReaction("👍")
        },
        ActionOption(Res.string.automation_action_broadcast_location) {
            AutomationAction.BroadcastLocation()
        },
        ActionOption(Res.string.automation_action_copy_clipboard) {
            AutomationAction.CopyToClipboard("{node_name}: {message_text}")
        },
        ActionOption(Res.string.automation_action_play_alarm) {
            AutomationAction.PlayAlarm(alarmType = "siren", durationSeconds = DEFAULT_ALARM_DURATION_SECONDS)
        },
        ActionOption(Res.string.automation_action_speak_text) {
            AutomationAction.SpeakText("Внимание! Уведомление от {node_name}", DEFAULT_SPEECH_RATE)
        },
        ActionOption(Res.string.automation_action_request_position) {
            AutomationAction.RequestPosition(0)
        },
        ActionOption(Res.string.automation_action_request_telemetry) {
            AutomationAction.RequestTelemetry(0)
        },
        ActionOption(Res.string.automation_action_send_traceroute) {
            AutomationAction.SendTraceroute(0)
        },
        ActionOption(Res.string.automation_action_remote_gpio) {
            AutomationAction.RemoteGpio(0, pin = 0, state = true)
        },
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddActionMenu(onAddAction: (AutomationAction) -> Unit, modifier: Modifier = Modifier) {
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
            Text(stringResource(Res.string.automation_add_action))
        }
        ExposedDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            ACTION_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.title)) },
                    onClick = {
                        onAddAction(option.create())
                        menuExpanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ActionItemRow(
    action: AutomationAction,
    onUpdate: (AutomationAction) -> Unit,
    onRemove: () -> Unit,
    onTest: () -> Unit,
    nodes: Map<Int, Node>,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = actionName(action),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TestActionButton(onTest = onTest)
                    IconButton(onClick = onRemove) {
                        Icon(MeshtasticIcons.Close, contentDescription = "Remove Action")
                    }
                }
            }

            ActionItemFields(action = action, onUpdate = onUpdate, nodes = nodes)
        }
    }
}

@Composable
private fun ColumnScope.ActionItemFields(
    action: AutomationAction,
    onUpdate: (AutomationAction) -> Unit,
    nodes: Map<Int, Node>,
) {
    when (action) {
        is AutomationAction.ShowNotification -> NotificationActionFields(action, onUpdate)
        is AutomationAction.SendMessage -> MessageActionFields(action, onUpdate, nodes)
        is AutomationAction.PlaySound -> PlaySoundFields(action, onUpdate)
        is AutomationAction.VibrateDevice -> VibrationActionFields(action, onUpdate)
        is AutomationAction.SendReaction -> ReactionActionFields(action, onUpdate, nodes)
        is AutomationAction.BroadcastLocation -> LocationActionFields(action, onUpdate, nodes)
        is AutomationAction.CopyToClipboard -> ClipboardActionFields(action, onUpdate)
        is AutomationAction.PlayAlarm -> PlayAlarmFields(action, onUpdate)
        is AutomationAction.SpeakText -> SpeakTextFields(action, onUpdate)
        is AutomationAction.RequestPosition -> RequestPositionFields(action, onUpdate, nodes)
        is AutomationAction.RequestTelemetry -> RequestTelemetryFields(action, onUpdate, nodes)
        is AutomationAction.SendTraceroute -> SendTracerouteFields(action, onUpdate, nodes)
        is AutomationAction.RemoteGpio -> RemoteGpioFields(action, onUpdate, nodes)
        is AutomationAction.TriggerRule -> TriggerRuleField(action, onUpdate)
    }
}

@Composable
private fun ColumnScope.PlaySoundFields(action: AutomationAction.PlaySound, onUpdate: (AutomationAction) -> Unit) {
    SoundPickerField(
        selectedSoundId = action.soundId,
        onSelectSound = { onUpdate(action.copy(soundId = it)) },
    )
}

@Composable
private fun ColumnScope.RequestPositionFields(
    action: AutomationAction.RequestPosition,
    onUpdate: (AutomationAction) -> Unit,
    nodes: Map<Int, Node>,
) {
    TargetNodeField(action.destNodeId, nodes) { onUpdate(action.copy(destNodeId = it)) }
}

@Composable
private fun ColumnScope.RequestTelemetryFields(
    action: AutomationAction.RequestTelemetry,
    onUpdate: (AutomationAction) -> Unit,
    nodes: Map<Int, Node>,
) {
    TargetNodeField(action.destNodeId, nodes) { onUpdate(action.copy(destNodeId = it)) }
}

@Composable
private fun ColumnScope.SendTracerouteFields(
    action: AutomationAction.SendTraceroute,
    onUpdate: (AutomationAction) -> Unit,
    nodes: Map<Int, Node>,
) {
    TargetNodeField(action.destNodeId, nodes) { onUpdate(action.copy(destNodeId = it)) }
}

@Composable
private fun ColumnScope.NotificationActionFields(
    action: AutomationAction.ShowNotification,
    onUpdate: (AutomationAction) -> Unit,
) {
    VariableChipsRow(onInsert = { onUpdate(action.copy(body = "${action.body} $it".trim())) })
    OutlinedTextField(
        value = action.title,
        onValueChange = { onUpdate(action.copy(title = it)) },
        label = { Text(stringResource(Res.string.automation_notification_title)) },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = action.body,
        onValueChange = { onUpdate(action.copy(body = it)) },
        label = { Text(stringResource(Res.string.automation_notification_body)) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ColumnScope.MessageActionFields(
    action: AutomationAction.SendMessage,
    onUpdate: (AutomationAction) -> Unit,
    nodes: Map<Int, Node>,
) {
    VariableChipsRow(onInsert = { onUpdate(action.copy(text = "${action.text} $it".trim())) })
    OutlinedTextField(
        value = action.text,
        onValueChange = { onUpdate(action.copy(text = it)) },
        label = { Text(stringResource(Res.string.automation_message_text)) },
        modifier = Modifier.fillMaxWidth(),
    )
    NodePickerField(
        selectedNodeId = action.destNodeId,
        nodes = nodes,
        onSelectNode = { onUpdate(action.copy(destNodeId = it)) },
        label = "Получатель (нода или общий канал)",
        nullLabel = "Отправка в канал (Broadcast)",
    )
    ChannelPickerBox(
        selectedChannel = action.channelIndex,
        onSelectChannel = { onUpdate(action.copy(channelIndex = it ?: 0)) },
        label = "Канал для отправки",
        allowAll = false,
    )
}

@Composable
private fun ColumnScope.VibrationActionFields(
    action: AutomationAction.VibrateDevice,
    onUpdate: (AutomationAction) -> Unit,
) {
    VibrationPickerField(
        selectedPattern = action.pattern,
        onSelectPattern = { onUpdate(action.copy(pattern = it)) },
    )
}

@Composable
private fun ColumnScope.ReactionActionFields(
    action: AutomationAction.SendReaction,
    onUpdate: (AutomationAction) -> Unit,
    nodes: Map<Int, Node>,
) {
    OutlinedTextField(
        value = action.emoji,
        onValueChange = { onUpdate(action.copy(emoji = it)) },
        label = { Text(stringResource(Res.string.automation_reaction_emoji)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.automation_reaction_quick), style = MaterialTheme.typography.bodySmall)
        QUICK_EMOJIS.forEach { emoji ->
            AssistChip(
                onClick = { onUpdate(action.copy(emoji = emoji)) },
                label = { Text(emoji) },
            )
        }
    }
    NodePickerField(
        selectedNodeId = action.destNodeId,
        nodes = nodes,
        onSelectNode = { onUpdate(action.copy(destNodeId = it)) },
        label = "Целевая нода (или общий канал)",
        nullLabel = "Ответ на сообщение / канал",
    )
}

@Composable
private fun ColumnScope.LocationActionFields(
    action: AutomationAction.BroadcastLocation,
    onUpdate: (AutomationAction) -> Unit,
    nodes: Map<Int, Node>,
) {
    Text(
        text = "При срабатывании правила устройство передаст свою текущую геопозицию в сеть Meshtastic.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    NodePickerField(
        selectedNodeId = action.destNodeId,
        nodes = nodes,
        onSelectNode = { onUpdate(action.copy(destNodeId = it)) },
        label = "Получатель геопозиции",
        nullLabel = "Широковещательно (Broadcast в канал)",
    )
}

@Composable
private fun ColumnScope.ClipboardActionFields(
    action: AutomationAction.CopyToClipboard,
    onUpdate: (AutomationAction) -> Unit,
) {
    VariableChipsRow(onInsert = { onUpdate(action.copy(text = "${action.text} $it".trim())) })
    OutlinedTextField(
        value = action.text,
        onValueChange = { onUpdate(action.copy(text = it)) },
        label = { Text(stringResource(Res.string.automation_action_copy_clipboard)) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ColumnScope.PlayAlarmFields(action: AutomationAction.PlayAlarm, onUpdate: (AutomationAction) -> Unit) {
    OutlinedTextField(
        value = action.durationSeconds.toString(),
        onValueChange = {
            onUpdate(action.copy(durationSeconds = it.toIntOrNull() ?: action.durationSeconds))
        },
        label = { Text(stringResource(Res.string.automation_duration_seconds)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ColumnScope.SpeakTextFields(action: AutomationAction.SpeakText, onUpdate: (AutomationAction) -> Unit) {
    VariableChipsRow(onInsert = { onUpdate(action.copy(text = "${action.text} $it".trim())) })
    OutlinedTextField(
        value = action.text,
        onValueChange = { onUpdate(action.copy(text = it)) },
        label = { Text(stringResource(Res.string.automation_message_text)) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ColumnScope.TargetNodeField(nodeId: Int, nodes: Map<Int, Node>, onSelect: (Int) -> Unit) {
    NodePickerField(
        selectedNodeId = nodeId.takeIf { it != 0 },
        nodes = nodes,
        onSelectNode = { onSelect(it ?: 0) },
        label = "Целевая нода",
        nullLabel = "Выберите ноду для запроса",
    )
}

@Composable
private fun ColumnScope.RemoteGpioFields(
    action: AutomationAction.RemoteGpio,
    onUpdate: (AutomationAction) -> Unit,
    nodes: Map<Int, Node>,
) {
    NodePickerField(
        selectedNodeId = action.destNodeId.takeIf { it != 0 },
        nodes = nodes,
        onSelectNode = { onUpdate(action.copy(destNodeId = it ?: 0)) },
        label = "Нода с реле/пином",
        nullLabel = "Выберите ноду",
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = action.pin.toString(),
            onValueChange = { onUpdate(action.copy(pin = it.toIntOrNull() ?: action.pin)) },
            label = { Text(stringResource(Res.string.automation_gpio_pin)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = action.state,
            onCheckedChange = { onUpdate(action.copy(state = it)) },
        )
        Text(stringResource(Res.string.automation_gpio_state))
    }
}

@Composable
private fun ColumnScope.TriggerRuleField(action: AutomationAction.TriggerRule, onUpdate: (AutomationAction) -> Unit) {
    OutlinedTextField(
        value = action.ruleId,
        onValueChange = { onUpdate(action.copy(ruleId = it)) },
        label = { Text("ID следующего правила") },
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoundPickerField(selectedSoundId: String, onSelectSound: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val currentOption = SOUND_OPTIONS.firstOrNull { it.id == selectedSoundId } ?: SOUND_OPTIONS.first()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = stringResource(currentOption.title),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(Res.string.automation_sound_picker)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SOUND_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.title)) },
                    onClick = {
                        onSelectSound(option.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VibrationPickerField(
    selectedPattern: String,
    onSelectPattern: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentOption = VIBRATION_OPTIONS.firstOrNull { it.id == selectedPattern } ?: VIBRATION_OPTIONS.first()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = stringResource(currentOption.title),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(Res.string.automation_vibrate_pattern)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            VIBRATION_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.title)) },
                    onClick = {
                        onSelectPattern(option.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun actionName(action: AutomationAction): String = when (action) {
    is AutomationAction.ShowNotification -> stringResource(Res.string.automation_action_show_notification)
    is AutomationAction.SendMessage -> stringResource(Res.string.automation_action_send_message)
    is AutomationAction.PlayAlarm -> stringResource(Res.string.automation_action_play_alarm)
    is AutomationAction.SpeakText -> stringResource(Res.string.automation_action_speak_text)
    is AutomationAction.RequestPosition -> stringResource(Res.string.automation_action_request_position)
    is AutomationAction.RequestTelemetry -> stringResource(Res.string.automation_action_request_telemetry)
    is AutomationAction.SendTraceroute -> stringResource(Res.string.automation_action_send_traceroute)
    is AutomationAction.RemoteGpio -> stringResource(Res.string.automation_action_remote_gpio)
    is AutomationAction.PlaySound -> stringResource(Res.string.automation_action_play_sound)
    is AutomationAction.VibrateDevice -> stringResource(Res.string.automation_action_vibrate)
    is AutomationAction.SendReaction -> stringResource(Res.string.automation_action_send_reaction)
    is AutomationAction.BroadcastLocation -> stringResource(Res.string.automation_action_broadcast_location)
    is AutomationAction.CopyToClipboard -> stringResource(Res.string.automation_action_copy_clipboard)
    is AutomationAction.TriggerRule -> "Запуск другого правила"
}
