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
package org.meshtastic.core.automation.trigger

import kotlinx.coroutines.flow.Flow
import org.meshtastic.core.automation.engine.TriggerEvent
import org.meshtastic.core.automation.model.AutomationTrigger

/**
 * Provides a [Flow] of [TriggerEvent]s for each supported [AutomationTrigger] variant.
 *
 * This is the bridge between the KMP engine (which knows nothing of Android or BLE) and the platform-level event
 * sources (NodeRepository Flows, BLE connection state, system battery, etc.).
 *
 * The Android implementation lives in `androidApp` / `core:service` and wires up the real repository flows. A test stub
 * can return empty flows or hot [MutableSharedFlow]s.
 */
interface TriggerSource {

    /**
     * Returns a cold or hot [Flow] that emits a [TriggerEvent] each time [trigger] fires.
     *
     * The engine subscribes once per rule; the flow must:
     * - Never throw (catch internally and log).
     * - Emit distinct events only (deduplication is the source's responsibility).
     * - Complete (return an empty flow) for any unsupported trigger type rather than throw.
     */
    fun flowFor(trigger: AutomationTrigger): Flow<TriggerEvent>
}
