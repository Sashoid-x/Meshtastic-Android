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
package org.meshtastic.feature.automation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.automation.engine.ActionExecutor
import org.meshtastic.core.automation.engine.TriggerEvent
import org.meshtastic.core.automation.model.AutomationAction
import org.meshtastic.core.automation.model.AutomationCondition
import org.meshtastic.core.automation.model.AutomationRule
import org.meshtastic.core.automation.model.AutomationTrigger
import org.meshtastic.core.automation.model.LogicalOperator
import org.meshtastic.core.automation.repository.AutomationRepository
import org.meshtastic.core.model.Node
import org.meshtastic.core.repository.NodeRepository
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class AutomationBuilderUiState(
    val id: String = "",
    val name: String = "",
    val isEnabled: Boolean = true,
    val trigger: AutomationTrigger = AutomationTrigger.NodeAppeared(),
    val conditionOperator: LogicalOperator = LogicalOperator.AND,
    val conditions: List<AutomationCondition> = emptyList(),
    val actions: List<AutomationAction> =
        listOf(AutomationAction.ShowNotification("Node Appeared", "Node {node_name} joined mesh")),
    val isSaved: Boolean = false,
    val isLoading: Boolean = true,
    val createdAt: Long = 0L,
    val lastFiredAt: Long = 0L,
    val fireCount: Int = 0,
)

private const val RULE_ID_PREFIX_LENGTH = 6

@OptIn(ExperimentalUuidApi::class)
@KoinViewModel
class AutomationBuilderViewModel(
    @InjectedParam val ruleId: String?,
    private val repository: AutomationRepository,
    private val nodeRepository: NodeRepository? = null,
    private val actionExecutor: ActionExecutor? = null,
) : ViewModel() {

    val nodes: StateFlow<Map<Int, Node>> = nodeRepository?.nodeDBbyNum ?: MutableStateFlow(emptyMap())

    private val _uiState =
        MutableStateFlow(
            AutomationBuilderUiState(
                id = ruleId ?: Uuid.random().toString(),
                isLoading = ruleId != null,
            ),
        )
    val uiState: StateFlow<AutomationBuilderUiState> = _uiState.asStateFlow()

    init {
        if (ruleId != null) {
            viewModelScope.launch {
                val rule = repository.getRule(ruleId)
                if (rule != null) {
                    _uiState.update {
                        it.copy(
                            id = rule.id,
                            name = rule.name,
                            isEnabled = rule.isEnabled,
                            trigger = rule.trigger,
                            conditionOperator = rule.conditionOperator,
                            conditions = rule.conditions,
                            actions = rule.actions,
                            isLoading = false,
                            createdAt = rule.createdAt,
                            lastFiredAt = rule.lastFiredAt,
                            fireCount = rule.fireCount,
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun setName(name: String) {
        _uiState.update { it.copy(name = name) }
    }

    fun setTrigger(trigger: AutomationTrigger) {
        _uiState.update { it.copy(trigger = trigger) }
    }

    fun addCondition(condition: AutomationCondition) {
        _uiState.update { it.copy(conditions = it.conditions + condition) }
    }

    fun updateCondition(index: Int, condition: AutomationCondition) {
        _uiState.update {
            if (index in it.conditions.indices) {
                val updated = it.conditions.toMutableList()
                updated[index] = condition
                it.copy(conditions = updated)
            } else {
                it
            }
        }
    }

    fun removeCondition(index: Int) {
        _uiState.update {
            if (index in it.conditions.indices) {
                it.copy(conditions = it.conditions.filterIndexed { i, _ -> i != index })
            } else {
                it
            }
        }
    }

    fun addAction(action: AutomationAction) {
        _uiState.update { it.copy(actions = it.actions + action) }
    }

    fun updateAction(index: Int, action: AutomationAction) {
        _uiState.update {
            if (index in it.actions.indices) {
                val updated = it.actions.toMutableList()
                updated[index] = action
                it.copy(actions = updated)
            } else {
                it
            }
        }
    }

    fun removeAction(index: Int) {
        _uiState.update {
            if (index in it.actions.indices) {
                it.copy(actions = it.actions.filterIndexed { i, _ -> i != index })
            } else {
                it
            }
        }
    }

    fun setConditionOperator(operator: LogicalOperator) {
        _uiState.update { it.copy(conditionOperator = operator) }
    }

    fun testAction(action: AutomationAction) {
        viewModelScope.launch {
            runCatching {
                actionExecutor?.execute(
                    action,
                    TriggerEvent(
                        nodeId = 0x12345678,
                        nodeName = "Test Node",
                        messageText = "Hello from Meshtastic!",
                        batteryLevel = 85,
                        voltage = 4.12f,
                        temperature = 22.5f,
                        humidity = 45.0f,
                        pressure = 1013.2f,
                        distanceMeters = 1500.0,
                    ),
                )
            }
        }
    }

    fun saveRule(onSaved: () -> Unit) {
        val state = _uiState.value
        val name = state.name.ifBlank { "Rule ${state.id.take(RULE_ID_PREFIX_LENGTH)}" }
        val rule =
            AutomationRule(
                id = state.id,
                name = name,
                isEnabled = state.isEnabled,
                trigger = state.trigger,
                conditionOperator = state.conditionOperator,
                conditions = state.conditions,
                actions = state.actions,
                createdAt = if (state.createdAt > 0L) state.createdAt else Clock.System.now().toEpochMilliseconds(),
                lastFiredAt = state.lastFiredAt,
                fireCount = state.fireCount,
            )
        viewModelScope.launch {
            repository.saveRule(rule)
            _uiState.update { it.copy(isSaved = true) }
            onSaved()
        }
    }
}
