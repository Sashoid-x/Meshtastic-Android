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
package org.meshtastic.feature.automation.navigation

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.meshtastic.core.navigation.AutomationRoute
import org.meshtastic.feature.automation.AutomationBuilderViewModel
import org.meshtastic.feature.automation.AutomationListViewModel
import org.meshtastic.feature.automation.AutomationLogsViewModel
import org.meshtastic.feature.automation.ui.AutomationBuilderScreen
import org.meshtastic.feature.automation.ui.AutomationListScreen
import org.meshtastic.feature.automation.ui.AutomationLogsScreen

/** Registers the automation feature screen entries into the Navigation 3 entry provider. */
fun EntryProviderScope<NavKey>.automationGraph(backStack: NavBackStack<NavKey>) {
    entry<AutomationRoute.AutomationList> {
        val viewModel = koinViewModel<AutomationListViewModel>()
        AutomationListScreen(
            viewModel = viewModel,
            onNavigateUp = dropUnlessResumed { backStack.removeLastOrNull() },
            onNavigateToBuilder = { ruleId -> backStack.add(AutomationRoute.AutomationBuilder(ruleId)) },
            onNavigateToBuilderWithTemplate = { templateId ->
                backStack.add(AutomationRoute.AutomationBuilder(templateId = templateId))
            },
            onNavigateToLogs = { ruleId -> backStack.add(AutomationRoute.AutomationLogs(ruleId)) },
        )
    }
    entry<AutomationRoute.AutomationBuilder> { route ->
        val viewModel = koinViewModel<AutomationBuilderViewModel> { parametersOf(route.ruleId, route.templateId) }
        AutomationBuilderScreen(
            viewModel = viewModel,
            onNavigateUp = dropUnlessResumed { backStack.removeLastOrNull() },
        )
    }
    entry<AutomationRoute.AutomationLogs> { route ->
        val viewModel = koinViewModel<AutomationLogsViewModel> { parametersOf(route.ruleId) }
        AutomationLogsScreen(
            viewModel = viewModel,
            onNavigateUp = dropUnlessResumed { backStack.removeLastOrNull() },
        )
    }
}
