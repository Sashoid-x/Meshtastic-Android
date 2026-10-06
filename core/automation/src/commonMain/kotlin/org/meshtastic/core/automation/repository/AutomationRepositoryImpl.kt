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
package org.meshtastic.core.automation.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.meshtastic.core.automation.engine.AutomationEngine
import org.meshtastic.core.automation.model.AutomationLog
import org.meshtastic.core.automation.model.AutomationRule
import org.meshtastic.core.automation.model.toDomain
import org.meshtastic.core.automation.model.toDomainOrNull
import org.meshtastic.core.automation.model.toEntity
import org.meshtastic.core.common.util.ioDispatcher
import org.meshtastic.core.database.dao.AutomationDao

class AutomationRepositoryImpl(private val dao: AutomationDao) : AutomationRepository {

    override fun observeRules(): Flow<List<AutomationRule>> =
        dao.observeAllRules().map { entities -> entities.mapNotNull { it.toDomainOrNull() } }

    override fun observeEnabledRules(): Flow<List<AutomationRule>> = dao.observeEnabledRules().map { entities ->
        entities.mapNotNull { entity ->
            val domain = entity.toDomainOrNull()
            if (domain == null) {
                // Disable corrupt rule in the database so it does not fail open or repeatedly trigger errors
                dao.setEnabled(entity.id, false)
            }
            domain
        }
    }

    override suspend fun getRule(id: String): AutomationRule? =
        withContext(ioDispatcher) { dao.getRule(id)?.toDomainOrNull() }

    override suspend fun saveRule(rule: AutomationRule) = withContext(ioDispatcher) { dao.upsertRule(rule.toEntity()) }

    override suspend fun deleteRule(id: String) = withContext(ioDispatcher) { dao.deleteRuleById(id) }

    override suspend fun setEnabled(id: String, enabled: Boolean) =
        withContext(ioDispatcher) { dao.setEnabled(id, enabled) }

    override suspend fun recordFire(id: String, timestamp: Long) =
        withContext(ioDispatcher) { dao.recordFire(id, timestamp) }

    override fun observeLogs(ruleId: String, limit: Int): Flow<List<AutomationLog>> =
        dao.observeLogs(ruleId, limit).map { entities -> entities.map { it.toDomain() } }

    override suspend fun addLog(log: AutomationLog) {
        withContext(ioDispatcher) {
            dao.insertLogAndPrune(log.toEntity(), AutomationEngine.MAX_LOG_ENTRIES_PER_RULE)
        }
    }
}
