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
@file:Suppress("TooGenericExceptionCaught")

package org.meshtastic.core.meshbridge

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import okio.source
import org.meshtastic.core.di.CoroutineDispatchers
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.withLock
import kotlin.random.Random

private const val ACCEPT_LOOP_RETRY_DELAY_MS = 250L
private const val HEX_RADIX = 16

internal class MeshBridgeClientConnection(
    private val socket: Socket,
    val id: String,
    private val onPayload: (ByteArray) -> Unit,
    private val onDisconnected: (String) -> Unit,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Volatile private var running = true
    private val sink = socket.getOutputStream().sink().buffer()

    fun start() {
        scope.launch(ioDispatcher) {
            try {
                val source = socket.getInputStream().source().buffer()
                while (running && scope.isActive) {
                    val frame = MeshBridgeFrameCodec.decodeClientFrame(source)
                    when (frame) {
                        is ClientFrame.Ping -> {
                            Logger.d { "MeshBridge client $id ping" }
                        }

                        is ClientFrame.Payload -> {
                            onPayload(frame.data)
                        }

                        is ClientFrame.Reject -> {
                            Logger.w { "MeshBridge client $id invalid frame: ${frame.reason}" }
                            if (frame.shouldClose) break
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.d { "MeshBridge client $id disconnected: ${e.message}" }
            } finally {
                close()
                onDisconnected(id)
            }
        }
    }

    fun send(bytes: ByteArray) {
        synchronized(sink) {
            sink.write(bytes)
            sink.flush()
        }
    }

    fun close() {
        running = false
        try {
            socket.close()
        } catch (_: Exception) {}
    }
}

internal class MeshBridgeServerJvm(
    private val dispatchers: CoroutineDispatchers,
    private val port: Int = MeshBridgeDefaults.DEFAULT_PORT,
) : MeshBridgeServer {

    private var serverSocket: ServerSocket? = null

    @Volatile private var running = false
    private var serverScope: CoroutineScope? = null
    private var acceptJob: Job? = null

    private val connectionsLock = ReentrantLock()
    private val connections = mutableMapOf<String, MeshBridgeClientConnection>()

    private val _connectionCount = MutableStateFlow(0)
    override val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()

    override var onPayload: ((ByteArray) -> Unit)? = null

    override suspend fun start(scope: CoroutineScope): Result<Unit> {
        if (running) {
            Logger.w { "Mesh Bridge Server already running on port $port" }
            return Result.success(Unit)
        }

        return try {
            serverScope = scope

            val boundSocket =
                withContext(dispatchers.io) {
                    val loopback = InetAddress.getByName("127.0.0.1")
                    val socket = ServerSocket(port, MeshBridgeDefaults.MAX_CLIENTS, loopback)
                    socket.reuseAddress = true
                    socket
                }

            serverSocket = boundSocket
            running = true
            Logger.i { "Mesh Bridge Server listening on 127.0.0.1:$port (plain TCP, loopback only)" }

            acceptJob = scope.launch(dispatchers.io) { acceptLoop() }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e) { "Failed to bind Mesh Bridge Server to 127.0.0.1:$port" }
            running = false
            try {
                serverSocket?.close()
            } catch (_: Exception) {}
            serverSocket = null
            Result.failure(e)
        }
    }

    private suspend fun acceptLoop() {
        val scope = serverScope ?: return
        while (running && scope.isActive) {
            try {
                val clientSocket = withContext(dispatchers.io) { serverSocket?.accept() }
                if (clientSocket != null) {
                    handleConnection(clientSocket)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (running) {
                    Logger.w(e) { "Mesh Bridge Server accept error: ${e.message}" }
                }
                delay(ACCEPT_LOOP_RETRY_DELAY_MS)
            }
        }
    }

    @Suppress("ReturnCount")
    private fun handleConnection(clientSocket: Socket) {
        val scope = serverScope ?: return
        val endpoint = clientSocket.remoteSocketAddress?.toString() ?: "unknown"

        if (clientSocket.inetAddress?.isLoopbackAddress != true) {
            Logger.w { "Mesh Bridge Server rejected non-loopback connection from $endpoint" }
            try {
                clientSocket.close()
            } catch (_: Exception) {}
            return
        }

        val currentCount = connectionsLock.withLock { connections.size }
        if (currentCount >= MeshBridgeDefaults.MAX_CLIENTS) {
            Logger.w {
                "Mesh Bridge Server rejected connection from $endpoint: " +
                    "max clients reached (${MeshBridgeDefaults.MAX_CLIENTS})"
            }
            try {
                clientSocket.close()
            } catch (_: Exception) {}
            return
        }

        clientSocket.keepAlive = true
        val connectionId = Random.nextInt().toString(HEX_RADIX)
        Logger.i { "Mesh Bridge client connected: id=$connectionId endpoint=$endpoint" }

        val connection =
            MeshBridgeClientConnection(
                socket = clientSocket,
                id = connectionId,
                onPayload = { payload -> onPayload?.invoke(payload) },
                onDisconnected = { id -> removeConnection(id) },
                scope = scope,
                ioDispatcher = dispatchers.io,
            )

        connectionsLock.withLock {
            connections[connectionId] = connection
            _connectionCount.value = connections.size
            Logger.i { "Mesh Bridge connection count now ${connections.size}" }
        }

        connection.start()
    }

    private fun removeConnection(connectionId: String) {
        connectionsLock.withLock {
            val removed = connections.remove(connectionId)
            if (removed != null) {
                _connectionCount.value = connections.size
                Logger.i { "Mesh Bridge client disconnected: $connectionId (active: ${connections.size})" }
            }
        }
    }

    override fun stop() {
        running = false
        acceptJob?.cancel()
        acceptJob = null

        val toClose = connectionsLock.withLock {
            val snapshot = connections.values.toList()
            connections.clear()
            _connectionCount.value = 0
            snapshot
        }
        toClose.forEach { it.close() }

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        serverScope = null
        Logger.i { "Mesh Bridge Server stopped" }
    }

    override suspend fun broadcastToClients(from: Int, to: Int, payload: ByteArray) {
        val frameBytes = MeshBridgeFrameCodec.encodeServerFrame(from = from, to = to, payload = payload)
        val activeConnections = connectionsLock.withLock { connections.values.toList() }
        if (activeConnections.isEmpty()) {
            Logger.d { "MeshBridge broadcast: no TCP clients connected, dropping payload (${payload.size} bytes)" }
            return
        }
        Logger.d { "MeshBridge broadcast: sending ${payload.size} bytes to ${activeConnections.size} client(s)" }
        activeConnections.forEach { client ->
            try {
                client.send(frameBytes)
            } catch (e: Exception) {
                Logger.w(e) { "Failed to send frame to client ${client.id}, closing" }
                client.close()
                removeConnection(client.id)
            }
        }
    }

    override suspend fun hasConnections(): Boolean = connectionsLock.withLock { connections.isNotEmpty() }
}

actual fun createMeshBridgeServer(dispatchers: CoroutineDispatchers, port: Int): MeshBridgeServer =
    MeshBridgeServerJvm(dispatchers = dispatchers, port = port)
