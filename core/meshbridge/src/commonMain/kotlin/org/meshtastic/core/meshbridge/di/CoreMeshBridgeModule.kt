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
package org.meshtastic.core.meshbridge.di

import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.meshbridge.MeshBridgeIntegration
import org.meshtastic.core.meshbridge.MeshBridgeServer
import org.meshtastic.core.meshbridge.MeshBridgeServerManager
import org.meshtastic.core.meshbridge.MeshBridgeServerManagerImpl
import org.meshtastic.core.meshbridge.createMeshBridgeServer
import org.meshtastic.core.repository.CommandSender
import org.meshtastic.core.repository.MeshBridgePrefs
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.ServiceRepository

@Module
class CoreMeshBridgeModule {
    @Single
    fun provideMeshBridgeServer(dispatchers: CoroutineDispatchers): MeshBridgeServer =
        createMeshBridgeServer(dispatchers = dispatchers)

    @Single
    fun provideMeshBridgeServerManager(server: MeshBridgeServer): MeshBridgeServerManager =
        MeshBridgeServerManagerImpl(server)

    @Single
    fun provideMeshBridgeIntegration(
        meshBridgeServerManager: MeshBridgeServerManager,
        commandSender: CommandSender,
        serviceRepository: ServiceRepository,
        meshBridgePrefs: MeshBridgePrefs,
        nodeRepository: NodeRepository,
    ): MeshBridgeIntegration = MeshBridgeIntegration(
        meshBridgeServerManager = meshBridgeServerManager,
        commandSender = commandSender,
        serviceRepository = serviceRepository,
        meshBridgePrefs = meshBridgePrefs,
        nodeRepository = nodeRepository,
    )
}
