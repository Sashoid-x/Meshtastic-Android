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
package org.meshtastic.core.automation.model

/**
 * Rich domain model for a single automation rule exposed to the UI and engine.
 *
 * This is the projection consumers work with; persistence uses [AutomationRuleEntity].
 *
 * @property id Stable UUID string.
 * @property name Human-readable label.
 * @property isEnabled Whether the engine should observe this rule.
 * @property trigger The event that activates this rule.
 * @property conditions Zero or more filters; all must pass before actions are executed.
 * @property actions Ordered list of effects to apply (at least one).
 * @property createdAt Epoch-millis of rule creation.
 * @property lastFiredAt Epoch-millis of last successful execution, or 0 if never fired.
 * @property fireCount Lifetime number of successful executions.
 */
data class AutomationRule(
    val id: String,
    val name: String,
    val isEnabled: Boolean,
    val trigger: AutomationTrigger,
    val conditionOperator: LogicalOperator = LogicalOperator.AND,
    val conditions: List<AutomationCondition>,
    val actions: List<AutomationAction>,
    val createdAt: Long,
    val lastFiredAt: Long = 0L,
    val fireCount: Int = 0,
) {
    fun hasSameConfiguration(other: AutomationRule?): Boolean {
        if (other == null) return false
        return trigger == other.trigger &&
            conditionOperator == other.conditionOperator &&
            conditions == other.conditions &&
            actions == other.actions &&
            isEnabled == other.isEnabled
    }
}

/**
 * One entry in the execution history of an automation rule.
 *
 * @property id Stable UUID.
 * @property ruleId ID of the parent [AutomationRule].
 * @property timestamp Epoch-millis when the engine processed this event.
 * @property success True when all actions were executed without error.
 * @property detail Short description of the outcome (or error message on failure).
 */
data class AutomationLog(
    val id: String,
    val ruleId: String,
    val timestamp: Long,
    val success: Boolean,
    val detail: String = "",
)
