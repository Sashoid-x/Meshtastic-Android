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
package org.meshtastic.core.automation.engine

import org.meshtastic.core.automation.model.AutomationAction

/**
 * Executes the side effects of an [AutomationAction].
 *
 * Platform implementations inject the actual message-sending, notification, and sound APIs. The engine never calls
 * [execute] for [AutomationAction.TriggerRule] — it handles that variant internally to track chain depth.
 *
 * Implementations must not throw for expected failure conditions (e.g. radio disconnected); they should wrap errors and
 * rethrow as [ActionExecutionException] with a human-readable message that the engine can store in
 * [AutomationLog.detail].
 */
interface ActionExecutor {

    /**
     * Executes [action] given [event] context (for template variable substitution).
     *
     * @throws ActionExecutionException if the action could not be completed.
     */
    suspend fun execute(action: AutomationAction, event: TriggerEvent)
}

/** Thrown by [ActionExecutor.execute] to signal a recoverable execution failure. */
class ActionExecutionException(message: String, cause: Throwable? = null) : Exception(message, cause)
