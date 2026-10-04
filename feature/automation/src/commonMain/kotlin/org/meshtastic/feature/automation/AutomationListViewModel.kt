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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.automation.model.AutomationRule
import org.meshtastic.core.automation.repository.AutomationRepository
import org.meshtastic.feature.automation.model.AutomationTemplate
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@KoinViewModel
class AutomationListViewModel(private val repository: AutomationRepository) : ViewModel() {

    val rules: StateFlow<List<AutomationRule>> =
        repository
            .observeRules()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = emptyList(),
            )

    fun toggleRule(ruleId: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.setEnabled(ruleId, enabled)
        }
    }

    fun deleteRule(ruleId: String) {
        viewModelScope.launch {
            repository.deleteRule(ruleId)
        }
    }

    fun createFromTemplate(template: AutomationTemplate) {
        viewModelScope.launch {
            val rule =
                AutomationRule(
                    id = Uuid.random().toString(),
                    name = template.initialRuleName,
                    isEnabled = true,
                    trigger = template.trigger,
                    conditions = template.conditions,
                    actions = template.actions,
                    createdAt = Clock.System.now().toEpochMilliseconds(),
                )
            repository.saveRule(rule)
        }
    }
}
