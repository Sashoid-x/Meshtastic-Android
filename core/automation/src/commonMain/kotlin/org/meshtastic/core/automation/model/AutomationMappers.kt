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

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.meshtastic.core.database.entity.AutomationLogEntity
import org.meshtastic.core.database.entity.AutomationRuleEntity

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

private fun decodeConditions(conditionsJson: String): Pair<LogicalOperator, List<AutomationCondition>> {
    val trimmed = conditionsJson.trim()
    if (trimmed.startsWith("{")) {
        val block = runCatching { json.decodeFromString<ConditionBlock>(trimmed) }.getOrNull()
        if (block != null) return block.operator to block.conditions
    }
    val list = runCatching { json.decodeFromString<List<AutomationCondition>>(trimmed) }.getOrDefault(emptyList())
    return LogicalOperator.AND to list
}

// ─── AutomationRule ──────────────────────────────────────────────────────────

fun AutomationRuleEntity.toDomain(): AutomationRule {
    val (op, conds) = decodeConditions(conditionsJson)
    return AutomationRule(
        id = id,
        name = name,
        isEnabled = isEnabled,
        trigger = json.decodeFromString<AutomationTrigger>(triggerJson),
        conditionOperator = op,
        conditions = conds,
        actions = json.decodeFromString<List<AutomationAction>>(actionsJson),
        createdAt = createdAt,
        lastFiredAt = lastFiredAt,
        fireCount = fireCount,
    )
}

fun AutomationRule.toEntity(): AutomationRuleEntity = AutomationRuleEntity(
    id = id,
    name = name,
    isEnabled = isEnabled,
    triggerJson = json.encodeToString(trigger),
    conditionsJson = json.encodeToString(ConditionBlock(conditionOperator, conditions)),
    actionsJson = json.encodeToString(actions),
    createdAt = createdAt,
    lastFiredAt = lastFiredAt,
    fireCount = fireCount,
)

// ─── AutomationLog ───────────────────────────────────────────────────────────

fun AutomationLogEntity.toDomain(): AutomationLog = AutomationLog(
    id = id,
    ruleId = ruleId,
    timestamp = timestamp,
    success = success,
    detail = detail,
)

fun AutomationLog.toEntity(): AutomationLogEntity = AutomationLogEntity(
    id = id,
    ruleId = ruleId,
    timestamp = timestamp,
    success = success,
    detail = detail,
)
