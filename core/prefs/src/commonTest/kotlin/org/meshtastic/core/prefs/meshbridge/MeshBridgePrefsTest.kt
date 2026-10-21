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

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.prefs.di.asUiDataStore
import org.meshtastic.core.repository.MeshBridgePrefs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class MeshBridgePrefsTest {
    private lateinit var tmpDir: Path
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var meshBridgePrefs: MeshBridgePrefs
    private lateinit var dispatchers: CoroutineDispatchers

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @BeforeTest
    fun setup() {
        tmpDir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "meshBridgePrefsTest-${Uuid.random()}"
        FileSystem.SYSTEM.createDirectories(tmpDir)
        dataStore =
            PreferenceDataStoreFactory.createWithPath(
                scope = testScope,
                produceFile = { tmpDir / "test.preferences_pb" },
            )
        dispatchers = CoroutineDispatchers(testDispatcher, testDispatcher, testDispatcher)
        meshBridgePrefs = MeshBridgePrefsImpl(dataStore.asUiDataStore(), dispatchers)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(tmpDir)
    }

    @Test
    fun `isMeshBridgeEnabled defaults to false`() = testScope.runTest {
        assertFalse(meshBridgePrefs.isMeshBridgeEnabled.value)
    }

    @Test
    fun `meshBridgeChannel defaults to 0`() = testScope.runTest {
        assertEquals(0, meshBridgePrefs.meshBridgeChannel.value)
    }

    @Test
    fun `setting isMeshBridgeEnabled updates preference`() = testScope.runTest {
        meshBridgePrefs.setMeshBridgeEnabled(true)
        assertTrue(meshBridgePrefs.isMeshBridgeEnabled.value)

        meshBridgePrefs.setMeshBridgeEnabled(false)
        assertFalse(meshBridgePrefs.isMeshBridgeEnabled.value)
    }

    @Test
    fun `setting meshBridgeChannel updates preference`() = testScope.runTest {
        meshBridgePrefs.setMeshBridgeChannel(3)
        assertEquals(3, meshBridgePrefs.meshBridgeChannel.value)

        meshBridgePrefs.setMeshBridgeChannel(0)
        assertEquals(0, meshBridgePrefs.meshBridgeChannel.value)
    }
}
