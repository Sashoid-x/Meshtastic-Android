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
package org.meshtastic.core.database.dao

import kotlinx.coroutines.flow.Flow
import org.meshtastic.core.database.DatabaseProvider
import org.meshtastic.core.database.entity.AutomationLogEntity
import org.meshtastic.core.database.entity.AutomationRuleEntity

/**
 * A switch-aware [AutomationDao] that resolves the active database on every call instead of pinning the one that was
 * current at injection time.
 */
@Suppress("TooManyFunctions")
class SwitchingAutomationDao(private val dbManager: DatabaseProvider) : AutomationDao {

    override fun observeAllRules(): Flow<List<AutomationRuleEntity>> = dbManager.observeCurrentDb {
        it.automationDao().observeAllRules()
    }

    override fun observeEnabledRules(): Flow<List<AutomationRuleEntity>> = dbManager.observeCurrentDb {
        it.automationDao().observeEnabledRules()
    }

    override suspend fun getRule(id: String): AutomationRuleEntity? = dbManager.withDb {
        it.automationDao().getRule(id)
    }

    override suspend fun upsertRule(rule: AutomationRuleEntity) {
        dbManager.withDb { it.automationDao().upsertRule(rule) }
    }

    override suspend fun deleteRule(rule: AutomationRuleEntity) {
        dbManager.withDb { it.automationDao().deleteRule(rule) }
    }

    override suspend fun deleteRuleById(id: String) {
        dbManager.withDb { it.automationDao().deleteRuleById(id) }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        dbManager.withDb { it.automationDao().setEnabled(id, enabled) }
    }

    override suspend fun recordFire(id: String, timestamp: Long) {
        dbManager.withDb { it.automationDao().recordFire(id, timestamp) }
    }

    override fun observeLogs(ruleId: String, limit: Int): Flow<List<AutomationLogEntity>> = dbManager.observeCurrentDb {
        it.automationDao().observeLogs(ruleId, limit)
    }

    override suspend fun insertLog(log: AutomationLogEntity) {
        dbManager.withDb { it.automationDao().insertLog(log) }
    }

    override suspend fun insertLogAndPrune(log: AutomationLogEntity, keep: Int) {
        dbManager.withDb { it.automationDao().insertLogAndPrune(log, keep) }
    }

    override suspend fun pruneOldLogs(ruleId: String, keep: Int) {
        dbManager.withDb { it.automationDao().pruneOldLogs(ruleId, keep) }
    }

    override suspend fun countLogs(ruleId: String): Int = dbManager.withDb { it.automationDao().countLogs(ruleId) } ?: 0

    override suspend fun deleteOldestLogs(ruleId: String, deleteCount: Int) {
        dbManager.withDb { it.automationDao().deleteOldestLogs(ruleId, deleteCount) }
    }

    override suspend fun clearLogs(ruleId: String) {
        dbManager.withDb { it.automationDao().clearLogs(ruleId) }
    }
}
