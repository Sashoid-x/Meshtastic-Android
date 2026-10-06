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
package org.meshtastic.core.automation.di

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.meshtastic.core.automation.engine.ActionExecutor
import org.meshtastic.core.automation.engine.AutomationEngine
import org.meshtastic.core.automation.repository.AutomationRepository
import org.meshtastic.core.automation.repository.AutomationRepositoryImpl
import org.meshtastic.core.automation.trigger.TriggerSource
import org.meshtastic.core.database.dao.AutomationDao
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.ServiceRepository

@Module
@ComponentScan("org.meshtastic.core.automation")
class CoreAutomationModule {

    /**
     * Repository backed by [AutomationDao].
     *
     * [AutomationDao] is provided by the host platform module (e.g. `androidApp`'s `MainKoinModule` via
     * `database.automationDao()`), keeping [core:automation] free of any Room / DatabaseProvider types.
     */
    @Single fun provideAutomationRepository(dao: AutomationDao): AutomationRepository = AutomationRepositoryImpl(dao)

    /**
     * The engine singleton.
     *
     * [TriggerSource] and [ActionExecutor] must be provided by the platform module (e.g. `androidApp`'s
     * `MainKoinModule`) before this module is loaded.
     */
    @Single
    fun provideAutomationEngine(
        repository: AutomationRepository,
        triggerSource: TriggerSource,
        actionExecutor: ActionExecutor,
        nodeRepository: NodeRepository,
        serviceRepository: ServiceRepository,
    ): AutomationEngine = AutomationEngine(
        repository = repository,
        triggerSource = triggerSource,
        actionExecutor = actionExecutor,
        nodeRepository = nodeRepository,
        serviceRepository = serviceRepository,
    )
}
