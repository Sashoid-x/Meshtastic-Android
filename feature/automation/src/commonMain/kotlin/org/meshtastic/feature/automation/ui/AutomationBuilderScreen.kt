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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.automation_edit_rule
import org.meshtastic.core.resources.automation_new_rule
import org.meshtastic.core.resources.automation_rule_name
import org.meshtastic.core.resources.automation_rule_name_hint
import org.meshtastic.core.resources.automation_step_3_details
import org.meshtastic.core.resources.back
import org.meshtastic.core.resources.save
import org.meshtastic.core.ui.icon.ArrowBack
import org.meshtastic.core.ui.icon.Check
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.feature.automation.AutomationBuilderViewModel

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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(if (isEdit) Res.string.automation_edit_rule else Res.string.automation_new_rule),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
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
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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
