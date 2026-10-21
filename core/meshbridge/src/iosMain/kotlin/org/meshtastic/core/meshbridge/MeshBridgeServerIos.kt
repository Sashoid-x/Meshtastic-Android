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
package org.meshtastic.core.meshbridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.meshtastic.core.di.CoroutineDispatchers

private class NoopMeshBridgeServer : MeshBridgeServer {
    override val isSupported: Boolean = false
    private val _connectionCount = MutableStateFlow(0)
    override val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()
    override var onPayload: ((ByteArray) -> Unit)? = null

    override suspend fun start(scope: CoroutineScope): Result<Unit> = Result.success(Unit)

    override fun stop() = Unit

    override suspend fun broadcastToClients(from: Int, to: Int, payload: ByteArray) = Unit

    override suspend fun hasConnections(): Boolean = false
}

actual fun createMeshBridgeServer(dispatchers: CoroutineDispatchers, port: Int): MeshBridgeServer =
    NoopMeshBridgeServer()
