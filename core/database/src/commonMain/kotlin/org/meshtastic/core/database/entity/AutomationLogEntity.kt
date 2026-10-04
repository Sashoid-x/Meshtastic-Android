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
package org.meshtastic.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * One execution log entry for an automation rule.
 *
 * Bounded to [AutomationRuleEntity] via a cascade-delete foreign key so that deleting a rule automatically prunes its
 * history. The engine caps stored entries per rule at
 * [org.meshtastic.core.automation.engine.AutomationEngine.MAX_LOG_ENTRIES_PER_RULE].
 *
 * @property id UUID string; generated before insertion.
 * @property ruleId References [AutomationRuleEntity.id].
 * @property timestamp Epoch-millis when the engine processed this event.
 * @property success True when all actions were executed without error.
 * @property detail Human-readable result message or error description (max 512 chars).
 */
@Entity(
    tableName = "automation_log",
    foreignKeys =
    [
        ForeignKey(
            entity = AutomationRuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["rule_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["rule_id"]), Index(value = ["timestamp"])],
)
data class AutomationLogEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "rule_id") val ruleId: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "success") val success: Boolean,
    @ColumnInfo(name = "detail") val detail: String = "",
)
