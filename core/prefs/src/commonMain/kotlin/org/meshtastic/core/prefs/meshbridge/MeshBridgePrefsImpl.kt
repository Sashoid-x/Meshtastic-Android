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
package org.meshtastic.core.prefs.meshbridge

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.prefs.di.UiDataStore
import org.meshtastic.core.repository.MeshBridgePrefs

@Single(binds = [MeshBridgePrefs::class])
class MeshBridgePrefsImpl(private val dataStore: UiDataStore, dispatchers: CoroutineDispatchers) : MeshBridgePrefs {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    override val isMeshBridgeEnabled: StateFlow<Boolean> =
        dataStore.data.map { it[KEY_MESH_BRIDGE_ENABLED] ?: false }.stateIn(scope, SharingStarted.Eagerly, false)

    override fun setMeshBridgeEnabled(enabled: Boolean) {
        scope.launch { dataStore.edit { prefs -> prefs[KEY_MESH_BRIDGE_ENABLED] = enabled } }
    }

    override val meshBridgeChannel: StateFlow<Int> =
        dataStore.data.map { it[KEY_MESH_BRIDGE_CHANNEL] ?: 0 }.stateIn(scope, SharingStarted.Eagerly, 0)

    override fun setMeshBridgeChannel(index: Int) {
        scope.launch { dataStore.edit { prefs -> prefs[KEY_MESH_BRIDGE_CHANNEL] = index } }
    }

    companion object {
        val KEY_MESH_BRIDGE_ENABLED = booleanPreferencesKey("mesh_bridge_enabled")
        val KEY_MESH_BRIDGE_CHANNEL = intPreferencesKey("mesh_bridge_channel")
    }
}
