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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.automation_discard_changes_message
import org.meshtastic.core.resources.automation_discard_changes_title
import org.meshtastic.core.resources.automation_edit_rule
import org.meshtastic.core.resources.automation_new_rule
import org.meshtastic.core.resources.automation_rule_name
import org.meshtastic.core.resources.automation_rule_name_hint
import org.meshtastic.core.resources.automation_step_3_details
import org.meshtastic.core.resources.automation_validation_errors
import org.meshtastic.core.resources.back
import org.meshtastic.core.resources.cancel
import org.meshtastic.core.resources.close
import org.meshtastic.core.resources.discard_changes
import org.meshtastic.core.resources.save
import org.meshtastic.core.ui.component.MeshtasticDialog
import org.meshtastic.core.ui.icon.ArrowBack
import org.meshtastic.core.ui.icon.Check
import org.meshtastic.core.ui.icon.Close
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.feature.automation.AutomationBuilderViewModel

@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationBuilderScreen(
    viewModel: AutomationBuilderViewModel,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nodes by viewModel.nodes.collectAsStateWithLifecycle()
    val isEdit = viewModel.ruleId != null
    val snackbarHostState = remember { SnackbarHostState() }
    var showDiscardConfirmation by rememberSaveable { mutableStateOf(false) }

    val backHandlerState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = backHandlerState,
        isBackEnabled = state.isDirty,
        onBackCompleted = { showDiscardConfirmation = true },
    )

    LaunchedEffect(state.testActionResult) {
        val result = state.testActionResult
        if (result != null) {
            snackbarHostState.showSnackbar(result)
            viewModel.dismissTestActionResult()
        }
    }

    if (showDiscardConfirmation) {
        MeshtasticDialog(
            titleRes = Res.string.automation_discard_changes_title,
            messageRes = Res.string.automation_discard_changes_message,
            confirmTextRes = Res.string.discard_changes,
            onConfirm = {
                showDiscardConfirmation = false
                onNavigateUp()
            },
            dismissTextRes = Res.string.cancel,
            onDismiss = { showDiscardConfirmation = false },
        )
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(if (isEdit) Res.string.automation_edit_rule else Res.string.automation_new_rule),
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (state.isDirty) {
                                showDiscardConfirmation = true
                            } else {
                                onNavigateUp()
                            }
                        },
                    ) {
                        Icon(MeshtasticIcons.ArrowBack, contentDescription = stringResource(Res.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.saveRule(onSaved = onNavigateUp) }) {
                        Icon(MeshtasticIcons.Check, contentDescription = stringResource(Res.string.save))
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.validationErrors.isNotEmpty()) {
                    ValidationErrorsBanner(
                        errors = state.validationErrors,
                        onDismiss = viewModel::dismissValidationErrors,
                    )
                }

                // Step 1: Conditions (ЕСЛИ: Событие + Дополнительные условия)
                ConditionsUnifiedCard(
                    trigger = state.trigger,
                    onTriggerChange = viewModel::setTrigger,
                    conditionOperator = state.conditionOperator,
                    onConditionOperatorChange = viewModel::setConditionOperator,
                    conditions = state.conditions,
                    onAddCondition = viewModel::addCondition,
                    onUpdateCondition = viewModel::updateCondition,
                    onRemoveCondition = viewModel::removeCondition,
                    nodes = nodes,
                )

                // Step 2: Actions (ТО: с кнопками теста и шаблонами)
                ActionsCard(
                    actions = state.actions,
                    onAddAction = viewModel::addAction,
                    onUpdateAction = viewModel::updateAction,
                    onRemoveAction = viewModel::removeAction,
                    onTestAction = viewModel::testAction,
                    nodes = nodes,
                )

                // Step 3: Rule Name & Details
                RuleDetailsCard(
                    name = state.name,
                    onNameChange = viewModel::setName,
                )
            }
        }
    }
}

@Composable
private fun ValidationErrorsBanner(errors: List<String>, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        colors =
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.automation_validation_errors),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        MeshtasticIcons.Close,
                        contentDescription = stringResource(Res.string.close),
                    )
                }
            }
            errors.forEach { error ->
                Text(
                    text = "• $error",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun RuleDetailsCard(name: String, onNameChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(Res.string.automation_step_3_details),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text(stringResource(Res.string.automation_rule_name)) },
                placeholder = { Text(stringResource(Res.string.automation_rule_name_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}
