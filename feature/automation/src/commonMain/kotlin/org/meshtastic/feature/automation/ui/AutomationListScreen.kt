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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.automation.model.AutomationAction
import org.meshtastic.core.automation.model.AutomationRule
import org.meshtastic.core.common.util.DateFormatter
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.automation
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
import org.meshtastic.core.resources.automation_actions_header
import org.meshtastic.core.resources.automation_delete_confirm
import org.meshtastic.core.resources.automation_empty_description
import org.meshtastic.core.resources.automation_empty_title
import org.meshtastic.core.resources.automation_fires_count
import org.meshtastic.core.resources.automation_last_fired
import org.meshtastic.core.resources.automation_logs
import org.meshtastic.core.resources.automation_never_fired
import org.meshtastic.core.resources.automation_new_rule
import org.meshtastic.core.resources.automation_templates
import org.meshtastic.core.resources.back
import org.meshtastic.core.resources.cancel
import org.meshtastic.core.resources.delete
import org.meshtastic.core.ui.icon.Add
import org.meshtastic.core.ui.icon.ArrowBack
import org.meshtastic.core.ui.icon.Delete
import org.meshtastic.core.ui.icon.Edit
import org.meshtastic.core.ui.icon.History
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.Settings
import org.meshtastic.feature.automation.AutomationListViewModel
import org.meshtastic.feature.automation.model.AutomationTemplate
import org.meshtastic.feature.automation.model.AutomationTemplates

@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationListScreen(
    viewModel: AutomationListViewModel,
    onNavigateUp: () -> Unit,
    onNavigateToBuilder: (ruleId: String?) -> Unit,
    onNavigateToLogs: (ruleId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    var ruleToDelete by remember { mutableStateOf<AutomationRule?>(null) }
    var showTemplatesDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.automation)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(MeshtasticIcons.ArrowBack, contentDescription = stringResource(Res.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = { showTemplatesDialog = true }) {
                        Text(stringResource(Res.string.automation_templates))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onNavigateToBuilder(null) }) {
                Icon(MeshtasticIcons.Add, contentDescription = stringResource(Res.string.automation_new_rule))
            }
        },
    ) { padding ->
        if (rules.isEmpty()) {
            EmptyAutomationState(
                onCreateRule = { onNavigateToBuilder(null) },
                onSelectTemplate = { template ->
                    viewModel.createFromTemplate(template)
                },
                modifier = Modifier.padding(padding).fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(rules, key = { it.id }) { rule ->
                    AutomationRuleCard(
                        rule = rule,
                        onToggle = { enabled -> viewModel.toggleRule(rule.id, enabled) },
                        onEdit = { onNavigateToBuilder(rule.id) },
                        onViewLogs = { onNavigateToLogs(rule.id) },
                        onDelete = { ruleToDelete = rule },
                    )
                }
            }
        }
    }

    if (showTemplatesDialog) {
        AlertDialog(
            onDismissRequest = { showTemplatesDialog = false },
            title = { Text(stringResource(Res.string.automation_templates)) },
            text = {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(AutomationTemplates.list, key = { it.id }) { template ->
                        Card(
                            modifier =
                            Modifier.fillMaxWidth().clickable {
                                viewModel.createFromTemplate(template)
                                showTemplatesDialog = false
                            },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = stringResource(template.titleRes),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(template.descriptionRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTemplatesDialog = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    ruleToDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { ruleToDelete = null },
            title = { Text(stringResource(Res.string.delete)) },
            text = { Text(stringResource(Res.string.automation_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteRule(rule.id)
                        ruleToDelete = null
                    },
                ) {
                    Text(stringResource(Res.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { ruleToDelete = null }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
}

@Suppress("LongMethod")
@Composable
private fun AutomationRuleCard(
    rule: AutomationRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onViewLogs: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onEdit),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = rule.isEnabled,
                    onCheckedChange = onToggle,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = formatRuleSummary(rule),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val lastFiredText =
                    if (rule.lastFiredAt > 0) {
                        stringResource(
                            Res.string.automation_last_fired,
                            DateFormatter.formatRelativeTime(rule.lastFiredAt),
                        )
                    } else {
                        stringResource(Res.string.automation_never_fired)
                    }

                Text(
                    text = "$lastFiredText • ${stringResource(Res.string.automation_fires_count, rule.fireCount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                Row {
                    IconButton(onClick = onViewLogs) {
                        Icon(
                            MeshtasticIcons.History,
                            contentDescription = stringResource(Res.string.automation_logs),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = onEdit) {
                        Icon(
                            MeshtasticIcons.Edit,
                            contentDescription = stringResource(Res.string.automation_actions_header),
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            MeshtasticIcons.Delete,
                            contentDescription = stringResource(Res.string.delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun formatRuleSummary(rule: AutomationRule): String {
    val triggerName = triggerLabel(rule.trigger)
    val actionNames = rule.actions.map { actionLabel(it) }
    val actionSummary = actionNames.joinToString(", ")

    return "КОГДА $triggerName → ТО $actionSummary"
}

@Composable
private fun actionLabel(action: AutomationAction): String = when (action) {
    is AutomationAction.SendMessage -> stringResource(Res.string.automation_action_send_message)
    is AutomationAction.RequestPosition -> stringResource(Res.string.automation_action_request_position)
    is AutomationAction.RequestTelemetry -> stringResource(Res.string.automation_action_request_telemetry)
    is AutomationAction.SendTraceroute -> stringResource(Res.string.automation_action_send_traceroute)
    is AutomationAction.RemoteGpio -> stringResource(Res.string.automation_action_remote_gpio)
    is AutomationAction.ShowNotification -> stringResource(Res.string.automation_action_show_notification)
    is AutomationAction.PlayAlarm -> stringResource(Res.string.automation_action_play_alarm)
    is AutomationAction.SpeakText -> stringResource(Res.string.automation_action_speak_text)
    is AutomationAction.PlaySound -> stringResource(Res.string.automation_action_play_sound)
    is AutomationAction.VibrateDevice -> stringResource(Res.string.automation_action_vibrate)
    is AutomationAction.SendReaction -> stringResource(Res.string.automation_action_send_reaction)
    is AutomationAction.BroadcastLocation -> stringResource(Res.string.automation_action_broadcast_location)
    is AutomationAction.CopyToClipboard -> stringResource(Res.string.automation_action_copy_clipboard)
    is AutomationAction.TriggerRule -> "Trigger Rule (${action.ruleId})"
}

@Composable
private fun EmptyAutomationState(
    onCreateRule: () -> Unit,
    onSelectTemplate: (AutomationTemplate) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            ) {
                Icon(
                    MeshtasticIcons.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(Res.string.automation_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.automation_empty_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onCreateRule) {
                    Icon(MeshtasticIcons.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.automation_new_rule))
                }
            }
        }

        item {
            Text(
                text = stringResource(Res.string.automation_templates),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        items(AutomationTemplates.list, key = { it.id }) { template ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onSelectTemplate(template) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(template.titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(template.descriptionRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
