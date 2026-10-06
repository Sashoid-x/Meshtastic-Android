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

import io.kotest.matchers.shouldBe
import org.meshtastic.core.database.entity.AutomationRuleEntity
import kotlin.test.Test

class AutomationMappersTest {

    @Test
    fun `round trip rule entity mapping preserves all fields`() {
        val originalRule =
            AutomationRule(
                id = "rule-1",
                name = "Low Battery Alert",
                isEnabled = true,
                trigger = AutomationTrigger.NodeBatteryLow(thresholdPercent = 15),
                conditionOperator = LogicalOperator.OR,
                conditions =
                listOf(
                    AutomationCondition.NodeInList(nodeIds = listOf(1, 2)),
                    AutomationCondition.TimeOfDay(startHour = 8, endHour = 20),
                ),
                actions =
                listOf(
                    AutomationAction.ShowNotification(title = "Alert", body = "Battery low"),
                    AutomationAction.PlayAlarm(alarmType = "siren", durationSeconds = 3),
                ),
                createdAt = 1000L,
                lastFiredAt = 2000L,
                fireCount = 5,
            )

        val entity = originalRule.toEntity()
        val mappedRule = entity.toDomain()

        mappedRule shouldBe originalRule
    }

    @Test
    fun `legacy condition json array decodes with default AND operator`() {
        val legacyEntity =
            AutomationRuleEntity(
                id = "legacy-1",
                name = "Legacy Rule",
                isEnabled = true,
                triggerJson = """{"type":"node_appeared"}""",
                conditionsJson = """[{"type":"node_in_list","nodeIds":[42]}]""",
                actionsJson = """[{"type":"play_sound","soundId":"beep"}]""",
                createdAt = 500L,
            )

        val domain = legacyEntity.toDomain()
        domain.conditionOperator shouldBe LogicalOperator.AND
        domain.conditions.size shouldBe 1
        (domain.conditions.first() as AutomationCondition.NodeInList).nodeIds shouldBe listOf(42)
    }

    @Test
    fun `corrupt json returns null with toDomainOrNull`() {
        val corruptEntity =
            AutomationRuleEntity(
                id = "corrupt-1",
                name = "Corrupt Rule",
                isEnabled = true,
                triggerJson = "{ this is invalid json }",
                conditionsJson = "[]",
                actionsJson = "[]",
                createdAt = 100L,
            )

        val domain = corruptEntity.toDomainOrNull()
        domain shouldBe null
    }

    @Test
    fun `automation log round trip preserves fields`() {
        val originalLog =
            AutomationLog(
                id = "42",
                ruleId = "rule-1",
                timestamp = 12345678L,
                success = true,
                detail = "Executed SendMessage",
            )

        val entity = originalLog.toEntity()
        val mappedLog = entity.toDomain()

        mappedLog shouldBe originalLog
    }
}
