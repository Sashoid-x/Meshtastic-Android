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
import kotlinx.coroutines.flow.StateFlow
import org.meshtastic.core.di.CoroutineDispatchers

/**
 * Platform-agnostic contract for the Meshtastic Mesh Bridge server.
 *
 * The production implementation on Android / JVM runs a plain TCP listener on
 * 127.0.0.1:[MeshBridgeDefaults.DEFAULT_PORT] (8090) dedicated to bridging PortNum.SIMULATOR_APP (69) traffic for
 * MeshGames and custom game clients.
 */
interface MeshBridgeServer {
    /** Whether this platform supports a local Mesh Bridge TCP listener. */
    val isSupported: Boolean
        get() = true

    /** Observable count of currently-connected TCP clients. */
    val connectionCount: StateFlow<Int>

    /** Callback invoked on the IO dispatcher for every valid inbound payload received from a client. */
    var onPayload: ((ByteArray) -> Unit)?

    /** Callback invoked on the IO dispatcher when a client successfully connects. */
    var onClientConnected: (() -> Unit)?

    /** Bind the TCP listener on 127.0.0.1:port and begin accepting connections. Idempotent if already running. */
    suspend fun start(scope: CoroutineScope): Result<Unit>

    /** Stop the listener, close all client sockets, and release resources. */
    fun stop()

    /** Broadcast a payload originating from the mesh to every currently-connected client. */
    suspend fun broadcastToClients(from: Int, to: Int, payload: ByteArray)

    /** Send an already-encoded frame to every currently-connected client. */
    suspend fun sendRawFrameToClients(frame: ByteArray)

    /** Returns true if at least one client is currently connected. */
    suspend fun hasConnections(): Boolean
}

/** Platform factory for [MeshBridgeServer]. */
expect fun createMeshBridgeServer(
    dispatchers: CoroutineDispatchers,
    port: Int = MeshBridgeDefaults.DEFAULT_PORT,
): MeshBridgeServer
