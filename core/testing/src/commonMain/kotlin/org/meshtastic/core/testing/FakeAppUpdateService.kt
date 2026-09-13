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
package org.meshtastic.core.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.meshtastic.core.model.AppUpdateCheckState
import org.meshtastic.core.repository.AppUpdateService

class FakeAppUpdateService(initialState: AppUpdateCheckState = AppUpdateCheckState.Idle) : AppUpdateService {

    private val _updateState = MutableStateFlow(initialState)
    override val updateState: StateFlow<AppUpdateCheckState> = _updateState.asStateFlow()

    var checkResultToReturn: AppUpdateCheckState = AppUpdateCheckState.UpToDate
    var checkForUpdatesCallCount = 0
        private set

    override suspend fun checkForUpdates(isManual: Boolean): AppUpdateCheckState {
        checkForUpdatesCallCount++
        _updateState.value = checkResultToReturn
        return checkResultToReturn
    }

    override fun dismissUpdate() {
        _updateState.value = AppUpdateCheckState.Idle
    }

    fun setUpdateState(state: AppUpdateCheckState) {
        _updateState.value = state
    }
}
