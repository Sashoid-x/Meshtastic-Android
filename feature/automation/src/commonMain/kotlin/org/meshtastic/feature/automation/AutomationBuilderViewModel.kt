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
import kotlinx.coroutines.CancellationException
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
import org.meshtastic.core.automation.model.LocationConditionType
import org.meshtastic.core.automation.model.LogicalOperator
import org.meshtastic.core.automation.repository.AutomationRepository
import org.meshtastic.core.automation.util.CronExpression
import org.meshtastic.core.model.Node
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.feature.automation.model.AutomationTemplates
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
    val isDirty: Boolean = false,
    val validationErrors: List<String> = emptyList(),
    val testActionResult: String? = null,
    val createdAt: Long = 0L,
    val lastFiredAt: Long = 0L,
    val fireCount: Int = 0,
)

private const val RULE_ID_PREFIX_LENGTH = 6
private const val MAX_HOUR_OF_DAY = 23

@OptIn(ExperimentalUuidApi::class)
@Suppress("TooManyFunctions")
@KoinViewModel
class AutomationBuilderViewModel(
    @InjectedParam val ruleId: String? = null,
    @InjectedParam val templateId: String? = null,
    private val repository: AutomationRepository,
    nodeRepository: NodeRepository? = null,
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
                    _uiState.update { current ->
                        if (current.isDirty) {
                            current.copy(isLoading = false)
                        } else {
                            current.copy(
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
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        } else if (templateId != null) {
            val template = AutomationTemplates.list.firstOrNull { it.id == templateId }
            if (template != null) {
                _uiState.update {
                    it.copy(
                        name = template.initialRuleName,
                        trigger = template.trigger,
                        conditions = template.conditions,
                        actions = template.actions,
                        isLoading = false,
                        isDirty = true,
                    )
                }
            }
        }
    }

    fun setName(name: String) {
        _uiState.update { it.copy(name = name, isDirty = true, validationErrors = emptyList()) }
    }

    fun setTrigger(trigger: AutomationTrigger) {
        _uiState.update { it.copy(trigger = trigger, isDirty = true, validationErrors = emptyList()) }
    }

    fun addCondition(condition: AutomationCondition) {
        _uiState.update {
            it.copy(conditions = it.conditions + condition, isDirty = true, validationErrors = emptyList())
        }
    }

    fun updateCondition(index: Int, condition: AutomationCondition) {
        _uiState.update {
            if (index in it.conditions.indices) {
                val updated = it.conditions.toMutableList()
                updated[index] = condition
                it.copy(conditions = updated, isDirty = true, validationErrors = emptyList())
            } else {
                it
            }
        }
    }

    fun removeCondition(index: Int) {
        _uiState.update {
            if (index in it.conditions.indices) {
                it.copy(
                    conditions = it.conditions.filterIndexed { i, _ -> i != index },
                    isDirty = true,
                    validationErrors = emptyList(),
                )
            } else {
                it
            }
        }
    }

    fun addAction(action: AutomationAction) {
        _uiState.update { it.copy(actions = it.actions + action, isDirty = true, validationErrors = emptyList()) }
    }

    fun updateAction(index: Int, action: AutomationAction) {
        _uiState.update {
            if (index in it.actions.indices) {
                val updated = it.actions.toMutableList()
                updated[index] = action
                it.copy(actions = updated, isDirty = true, validationErrors = emptyList())
            } else {
                it
            }
        }
    }

    fun removeAction(index: Int) {
        _uiState.update {
            if (index in it.actions.indices) {
                it.copy(
                    actions = it.actions.filterIndexed { i, _ -> i != index },
                    isDirty = true,
                    validationErrors = emptyList(),
                )
            } else {
                it
            }
        }
    }

    fun setConditionOperator(operator: LogicalOperator) {
        _uiState.update { it.copy(conditionOperator = operator, isDirty = true) }
    }

    fun validate(): List<String> {
        val state = _uiState.value
        val errors = mutableListOf<String>()
        if (state.name.isBlank()) {
            errors.add("Rule name cannot be empty")
        }
        if (state.actions.isEmpty()) {
            errors.add("Rule must have at least one action")
        }
        when (val trigger = state.trigger) {
            is AutomationTrigger.Schedule -> {
                if (!CronExpression.isValid(trigger.cronExpression)) {
                    errors.add("Invalid cron expression (expected 5 fields, e.g. '0 8 * * *')")
                }
            }

            is AutomationTrigger.NodeGeofence -> {
                if (trigger.centerLatitude == null || trigger.centerLongitude == null) {
                    errors.add("Geofence center coordinates (latitude & longitude) are required")
                }
                if (trigger.radiusMeters <= 0.0) {
                    errors.add("Geofence radius must be greater than 0")
                }
            }

            else -> {}
        }
        state.conditions.forEach { condition ->
            when (condition) {
                is AutomationCondition.TimeOfDay -> {
                    if (condition.startHour !in 0..MAX_HOUR_OF_DAY || condition.endHour !in 0..MAX_HOUR_OF_DAY) {
                        errors.add("Time window hours must be between 0 and 23")
                    }
                }

                is AutomationCondition.LocationFilter -> {
                    if (
                        condition.type == LocationConditionType.WITHIN_GEOFENCE ||
                        condition.type == LocationConditionType.OUTSIDE_GEOFENCE
                    ) {
                        if (condition.centerLatitude == null || condition.centerLongitude == null) {
                            errors.add("Location filter center coordinates are required")
                        }
                        if (condition.radiusMeters <= 0.0) {
                            errors.add("Location filter radius must be greater than 0")
                        }
                    } else {
                        if (condition.distanceKm <= 0.0) {
                            errors.add("Distance must be greater than 0")
                        }
                    }
                }

                else -> {}
            }
        }
        state.actions.forEach { action ->
            when (action) {
                is AutomationAction.SendTraceroute -> {
                    if (action.destNodeId == 0) errors.add("Traceroute action requires a valid destination node")
                }

                is AutomationAction.RequestTelemetry -> {
                    if (action.destNodeId == 0) errors.add("Request telemetry action requires a valid destination node")
                }

                is AutomationAction.RequestPosition -> {
                    if (action.destNodeId == 0) errors.add("Request position action requires a valid destination node")
                }

                else -> {}
            }
        }
        return errors
    }

    @Suppress("TooGenericExceptionCaught")
    fun testAction(action: AutomationAction) {
        viewModelScope.launch {
            try {
                val sampleEvent =
                    TriggerEvent(
                        nodeId = 0x12345678,
                        nodeName = "Test Node",
                        shortName = "TEST",
                        messageText = "Hello from Meshtastic!",
                        batteryLevel = 85,
                        voltage = 4.12f,
                        temperature = 22.5f,
                        humidity = 45.0f,
                        pressure = 1013.2f,
                        distanceMeters = 1500.0,
                        packetId = 12345,
                        channelIndex = 0,
                        hops = 1,
                        snr = 8.5f,
                        rssi = -75,
                        transport = "LoRa",
                        lastHop = "Direct",
                        contactKey = "0^all",
                    )
                val description =
                    actionExecutor?.execute(
                        action = action,
                        event = sampleEvent,
                        dryRun = false,
                    ) ?: "Action executed successfully"
                _uiState.update { it.copy(testActionResult = description) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(testActionResult = "Test failed: ${e.message}") }
            }
        }
    }

    fun dismissTestActionResult() {
        _uiState.update { it.copy(testActionResult = null) }
    }

    fun dismissValidationErrors() {
        _uiState.update { it.copy(validationErrors = emptyList()) }
    }

    fun saveRule(onSaved: () -> Unit) {
        val errors = validate()
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(validationErrors = errors) }
            return
        }
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
            _uiState.update { it.copy(isSaved = true, isDirty = false, validationErrors = emptyList()) }
            onSaved()
        }
    }
}
