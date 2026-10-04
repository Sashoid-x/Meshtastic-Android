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
package org.meshtastic.core.service.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.koin.android.annotation.KoinWorker
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.repository.CommandSender
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.RadioController
import org.meshtastic.core.repository.TopologyManager
import org.meshtastic.core.repository.UiPrefs

/**
 * Background worker that performs periodic topology discovery when the app is in "Local Radio" mode.
 *
 * If Auto-Topology Discovery is enabled and MQTT is inactive, this worker selects the top active nodes that have not
 * recently been tracerouted and initiates a quiet [RouteDiscovery] request to each.
 */
@KoinWorker
class TopologyDiscoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val uiPrefs: UiPrefs,
    private val topologyManager: TopologyManager,
    private val nodeRepository: NodeRepository,
    private val commandSender: CommandSender,
    private val radioController: RadioController,
) : CoroutineWorker(appContext, workerParams) {

    private val logger = Logger.withTag(WORK_NAME)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result = try {
        // 1. Check user preference (must be explicitly enabled)
        if (!uiPrefs.autoTopologyDiscoveryEnabled.value) {
            logger.d { "Auto-topology discovery is disabled; skipping." }
            Result.success()
        } else if (topologyManager.isMqttActive.value) {
            // 2. Automatically pause if MQTT is active
            logger.i { "MQTT is active; pausing auto-topology discovery over LoRa airtime." }
            Result.success()
        } else if (radioController.connectionState.value !is ConnectionState.Connected) {
            // 3. Radio not currently connected
            logger.d { "Radio is not connected; will retry." }
            Result.retry()
        } else {
            // 4. Select 3-5 active candidate nodes
            val ourNodeNum = nodeRepository.ourNodeInfo.value?.num ?: 0
            val candidates =
                nodeRepository.nodeDBbyNum.value.values
                    .filter { it.num != 0 && it.num != ourNodeNum && !it.isIgnored && it.lastHeard > 0 }
                    .sortedByDescending { it.lastHeard }
                    .take(DISCOVERY_CANDIDATE_LIMIT)

            if (candidates.isEmpty()) {
                logger.d { "No eligible candidate nodes for topology discovery." }
            } else {
                logger.i { "Starting auto-topology discovery for ${candidates.size} nodes." }
                for (node in candidates) {
                    logger.d { "Sending RouteDiscovery request to node 0x${node.num.toString(16)}" }
                    commandSender.requestTraceroute(requestId = 0, destNum = node.num)
                    delay(INTER_PACKET_DELAY_MS)
                }
            }
            Result.success()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.e(e) { "Failed to run TopologyDiscoveryWorker" }
        Result.failure()
    }

    companion object {
        const val WORK_NAME = "topology_discovery_worker"
        private const val DISCOVERY_CANDIDATE_LIMIT = 4
        private const val INTER_PACKET_DELAY_MS = 8_000L
    }
}
