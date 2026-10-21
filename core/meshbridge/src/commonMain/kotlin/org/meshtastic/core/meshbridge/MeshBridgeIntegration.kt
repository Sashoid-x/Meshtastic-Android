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
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import okio.ByteString.Companion.toByteString
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.repository.CommandSender
import org.meshtastic.core.repository.MeshBridgePrefs
import org.meshtastic.core.repository.ServiceRepository
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import kotlin.concurrent.Volatile

/**
 * Bidirectional bridge between the local TCP Mesh Bridge server and the Meshtastic mesh network.
 *
 * Exclusively relays packets for [PortNum.SIMULATOR_APP] (69) with wantAck=false:
 * - Client -> Mesh: payload from local TCP client is wrapped into [DataPacket] with wantAck=false and dispatched over
 *   LoRa via [CommandSender.sendData].
 * - Mesh -> Client: packets received on portnum 69 from [ServiceRepository.meshPacketFlow] are broadcast to all
 *   connected TCP clients as (u32 LE length, u32 LE from, u32 LE to, payload bytes).
 */
class MeshBridgeIntegration(
    private val meshBridgeServerManager: MeshBridgeServerManager,
    private val commandSender: CommandSender,
    private val serviceRepository: ServiceRepository,
    private val meshBridgePrefs: MeshBridgePrefs,
    val peerMap: MeshBridgePeerMap = MeshBridgePeerMap(),
) {
    private val isRunning = atomic(false)

    @Volatile private var jobs: List<Job> = emptyList()

    fun start(scope: CoroutineScope) {
        if (!isRunning.compareAndSet(expect = false, update = true)) return

        meshBridgeServerManager.start(scope)

        val newJobs =
            listOf(
                // Outbound: Client -> Mesh
                scope.launch {
                    meshBridgeServerManager.inboundPayloads.collect { payload ->
                        handleClientPayload(payload)
                    }
                },
                // Inbound: Mesh -> Client
                scope.launch {
                    serviceRepository.meshPacketFlow
                        .filter { packet ->
                            packet.decoded?.portnum == PortNum.SIMULATOR_APP && packet.decoded?.payload != null
                        }
                        .collect { packet ->
                            handleMeshPacket(packet)
                        }
                },
            )

        jobs = newJobs
        Logger.i {
            "Mesh Bridge Integration started on port ${MeshBridgeDefaults.DEFAULT_PORT} " +
                "for portnum ${PortNum.SIMULATOR_APP.value}"
        }
    }

    private suspend fun resolveRoute(payload: ByteArray): Pair<String, String> {
        if (payload.size < MeshBridgeDefaults.MIN_ROUTABLE_WRAPPER_BYTES) {
            return NodeAddress.ID_BROADCAST to "short/malformed payload (${payload.size} bytes) -> broadcast"
        }
        val wrapperFrom = MeshBridgeRouter.readIntLe(payload, MeshBridgeDefaults.WRAPPER_OFFSET_FROM)
        val wrapperTo = MeshBridgeRouter.readIntLe(payload, MeshBridgeDefaults.WRAPPER_OFFSET_TO)
        val bodyFirstByte = payload[MeshBridgeDefaults.WRAPPER_OFFSET_BODY]
        val destNode = if (wrapperTo != 0) peerMap.get(wrapperTo) else null

        val route =
            MeshBridgeRouter.route(
                wrapperFrom = wrapperFrom,
                wrapperTo = wrapperTo,
                body = payload.copyOfRange(MeshBridgeDefaults.WRAPPER_OFFSET_BODY, payload.size),
                destNode = destNode,
            )

        return when (route) {
            is MeshBridgeRoute.Unicast -> {
                val toClientHex =
                    (wrapperTo.toLong() and MeshBridgeDefaults.UINT32_MASK).toString(MeshBridgeDefaults.HEX_RADIX)
                val nodeHex =
                    (route.nodeNum.toLong() and MeshBridgeDefaults.UINT32_MASK).toString(MeshBridgeDefaults.HEX_RADIX)
                NodeAddress.numToDefaultId(route.nodeNum) to "envelope toClient=0x$toClientHex -> node=0x$nodeHex (DM)"
            }

            is MeshBridgeRoute.Broadcast -> {
                val toClientHex =
                    (wrapperTo.toLong() and MeshBridgeDefaults.UINT32_MASK).toString(MeshBridgeDefaults.HEX_RADIX)
                val detail =
                    when (bodyFirstByte) {
                        MeshBridgeDefaults.MAGIC_PRESENCE -> "presence -> broadcast"

                        MeshBridgeDefaults.MAGIC_ENVELOPE -> {
                            if (wrapperTo == 0) {
                                "envelope toClient=0x0 -> broadcast"
                            } else {
                                "envelope toClient=0x$toClientHex -> broadcast (нет карты)"
                            }
                        }

                        else -> {
                            val magicHex = bodyFirstByte.toUByte().toString(MeshBridgeDefaults.HEX_RADIX)
                            "unknown body[0]=0x$magicHex -> broadcast"
                        }
                    }
                NodeAddress.ID_BROADCAST to detail
            }
        }
    }

    private suspend fun handleClientPayload(payload: ByteArray) {
        if (payload.isEmpty()) return
        if (payload.size > MeshBridgeDefaults.MAX_PAYLOAD_BYTES) {
            Logger.w {
                "MeshBridge payload exceeds max limit (${payload.size} > " +
                    "${MeshBridgeDefaults.MAX_PAYLOAD_BYTES}), dropping"
            }
            return
        }

        val (destination, logDetails) = resolveRoute(payload)

        try {
            val dataPacket =
                DataPacket(
                    to = destination,
                    bytes = payload.toByteString(),
                    dataType = PortNum.SIMULATOR_APP.value,
                    channel = meshBridgePrefs.meshBridgeChannel.value,
                    wantAck = false,
                )
            commandSender.sendData(dataPacket)
            Logger.d { "meshbridge: TX $logDetails" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e) { "MeshBridge failed to send payload to mesh: ${e.message}" }
        }
    }

    private suspend fun handleMeshPacket(packet: MeshPacket) {
        val payload = packet.decoded?.payload?.toByteArray() ?: return
        val from = packet.from
        val to = packet.to

        if (peerMap.learnFromRxWrapper(payload, from)) {
            val clientFrom = MeshBridgeRouter.readIntLe(payload, MeshBridgeDefaults.WRAPPER_OFFSET_FROM)
            val clientHex =
                (clientFrom.toLong() and MeshBridgeDefaults.UINT32_MASK).toString(MeshBridgeDefaults.HEX_RADIX)
            val nodeHex = (from.toLong() and MeshBridgeDefaults.UINT32_MASK).toString(MeshBridgeDefaults.HEX_RADIX)
            Logger.d { "meshbridge: learned peer toClient=0x$clientHex -> node=0x$nodeHex" }
        }

        Logger.d { "MeshBridge received ${payload.size} bytes from mesh (from=$from, to=$to), broadcasting" }
        meshBridgeServerManager.broadcastToClients(from = from, to = to, payload = payload)
    }

    fun stop() {
        if (!isRunning.compareAndSet(expect = true, update = false)) return
        val toCancel = jobs
        jobs = emptyList()
        toCancel.forEach(Job::cancel)
        meshBridgeServerManager.stop()
        Logger.i { "Mesh Bridge Integration stopped" }
    }
}
