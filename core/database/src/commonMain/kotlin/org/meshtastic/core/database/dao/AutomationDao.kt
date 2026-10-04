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

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow
import org.meshtastic.core.database.entity.AutomationLogEntity
import org.meshtastic.core.database.entity.AutomationRuleEntity

@Dao
@Suppress("TooManyFunctions")
interface AutomationDao {

    // ─── Rules ────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM automation_rule ORDER BY created_at ASC")
    fun observeAllRules(): Flow<List<AutomationRuleEntity>>

    @Query("SELECT * FROM automation_rule WHERE is_enabled = 1 ORDER BY created_at ASC")
    fun observeEnabledRules(): Flow<List<AutomationRuleEntity>>

    @Query("SELECT * FROM automation_rule WHERE id = :id LIMIT 1")
    suspend fun getRule(id: String): AutomationRuleEntity?

    @Upsert suspend fun upsertRule(rule: AutomationRuleEntity)

    @Delete suspend fun deleteRule(rule: AutomationRuleEntity)

    @Query("DELETE FROM automation_rule WHERE id = :id")
    suspend fun deleteRuleById(id: String)

    @Query("UPDATE automation_rule SET is_enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE automation_rule SET last_fired_at = :timestamp, fire_count = fire_count + 1 WHERE id = :id")
    suspend fun recordFire(id: String, timestamp: Long)

    // ─── Logs ─────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM automation_log WHERE rule_id = :ruleId ORDER BY timestamp DESC LIMIT :limit")
    fun observeLogs(ruleId: String, limit: Int = 100): Flow<List<AutomationLogEntity>>

    @Upsert suspend fun insertLog(log: AutomationLogEntity)

    /**
     * Prunes the oldest entries for [ruleId], keeping only the most recent [keep] rows. Called by the engine after each
     * successful log insert to cap log growth.
     */
    @Transaction
    suspend fun pruneOldLogs(ruleId: String, keep: Int) {
        val count = countLogs(ruleId)
        if (count > keep) {
            deleteOldestLogs(ruleId, count - keep)
        }
    }

    @Query("SELECT COUNT(*) FROM automation_log WHERE rule_id = :ruleId")
    suspend fun countLogs(ruleId: String): Int

    @Query(
        """
        DELETE FROM automation_log WHERE id IN (
            SELECT id FROM automation_log
            WHERE rule_id = :ruleId
            ORDER BY timestamp ASC
            LIMIT :deleteCount
        )
        """,
    )
    suspend fun deleteOldestLogs(ruleId: String, deleteCount: Int)

    @Query("DELETE FROM automation_log WHERE rule_id = :ruleId")
    suspend fun clearLogs(ruleId: String)
}
