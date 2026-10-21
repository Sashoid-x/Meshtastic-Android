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

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

interface MeshBridgeServerManager {
    val isSupported: Boolean
    val isRunning: StateFlow<Boolean>
    val isStarting: StateFlow<Boolean>
    val connectionCount: StateFlow<Int>
    val hasError: StateFlow<Boolean>
    val inboundPayloads: SharedFlow<ByteArray>

    fun start(scope: CoroutineScope)

    fun stop()

    suspend fun broadcastToClients(from: Int, to: Int, payload: ByteArray)
}

class MeshBridgeServerManagerImpl(private val server: MeshBridgeServer) : MeshBridgeServerManager {

    private var scope: CoroutineScope? = null

    @Volatile private var startGeneration = 0L

    override val isSupported: Boolean = server.isSupported

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isStarting = MutableStateFlow(false)
    override val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()

    override val connectionCount: StateFlow<Int> = server.connectionCount

    private val _hasError = MutableStateFlow(false)
    override val hasError: StateFlow<Boolean> = _hasError.asStateFlow()

    private val _inboundPayloads = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val inboundPayloads: SharedFlow<ByteArray> = _inboundPayloads.asSharedFlow()

    private val lifecycleMutex = Mutex()

    override fun start(scope: CoroutineScope) {
        if (!isSupported) return
        if (_isRunning.value || _isStarting.value) {
            Logger.w { "MeshBridgeServerManager already running or starting" }
            return
        }
        _hasError.value = false
        _isStarting.value = true
        val generation = ++startGeneration
        this.scope = scope

        scope.launch {
            lifecycleMutex.withLock {
                if (generation != startGeneration) return@withLock

                server.onPayload = { payload ->
                    if (!_inboundPayloads.tryEmit(payload)) {
                        Logger.w { "MeshBridge inbound payload buffer full; dropping frame" }
                    }
                }

                val result = server.start(scope)
                if (generation != startGeneration) {
                    if (result.isSuccess) server.stop()
                    return@withLock
                }

                _isStarting.value = false
                if (result.isSuccess) {
                    _isRunning.value = true
                    Logger.i { "Mesh Bridge Server started" }
                } else {
                    _hasError.value = true
                    Logger.e(result.exceptionOrNull()) { "Failed to start Mesh Bridge Server" }
                    server.onPayload = null
                }
            }
        }
    }

    override fun stop() {
        startGeneration++
        _isRunning.value = false
        _isStarting.value = false
        _hasError.value = false
        scope = null
        server.onPayload = null
        server.stop()
        Logger.i { "Mesh Bridge Server stopped" }
    }

    override suspend fun broadcastToClients(from: Int, to: Int, payload: ByteArray) {
        if (!_isRunning.value) return
        server.broadcastToClients(from, to, payload)
    }
}
