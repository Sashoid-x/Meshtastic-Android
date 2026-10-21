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
@file:Suppress("LongMethod", "ModifierMissing", "MatchingDeclarationName")

package org.meshtastic.feature.settings.radio.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.meshtastic.core.meshbridge.MeshBridgeServerManager
import org.meshtastic.core.model.Channel
import org.meshtastic.core.repository.MeshBridgePrefs
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.back
import org.meshtastic.core.resources.mesh_bridge
import org.meshtastic.core.resources.mesh_bridge_channel
import org.meshtastic.core.resources.mesh_bridge_channel_desc
import org.meshtastic.core.resources.mesh_bridge_channel_option
import org.meshtastic.core.resources.mesh_bridge_enabled
import org.meshtastic.core.resources.mesh_bridge_enabled_desc
import org.meshtastic.core.resources.mesh_bridge_info_desc
import org.meshtastic.core.resources.mesh_bridge_section
import org.meshtastic.core.resources.mesh_bridge_status
import org.meshtastic.core.resources.mesh_bridge_status_connected
import org.meshtastic.core.resources.mesh_bridge_status_failed
import org.meshtastic.core.resources.mesh_bridge_status_not_running
import org.meshtastic.core.resources.mesh_bridge_status_off
import org.meshtastic.core.resources.mesh_bridge_status_starting
import org.meshtastic.core.resources.mesh_bridge_status_unavailable
import org.meshtastic.core.resources.mesh_bridge_status_waiting
import org.meshtastic.core.ui.component.DropDownPreference
import org.meshtastic.core.ui.component.SwitchPreference
import org.meshtastic.core.ui.component.TitledCard
import org.meshtastic.core.ui.icon.ArrowBack
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.Warning
import org.meshtastic.proto.ChannelSet

internal enum class MeshBridgeStatus {
    Unavailable,
    Off,
    Starting,
    WaitingForClient,
    Connected,
    Failed,
    NotRunning,
    ;

    companion object {
        fun resolve(
            isSupported: Boolean,
            isEnabled: Boolean,
            isRunning: Boolean,
            isStarting: Boolean,
            clientCount: Int,
            hasError: Boolean,
        ): MeshBridgeStatus = when {
            !isSupported -> Unavailable
            !isEnabled -> Off
            hasError -> Failed
            isStarting -> Starting
            isRunning && clientCount > 0 -> Connected
            isRunning -> WaitingForClient
            else -> NotRunning
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshBridgeScreen(onBack: () -> Unit) {
    val meshBridgePrefs: MeshBridgePrefs = koinInject()
    val meshBridgeManager: MeshBridgeServerManager = koinInject()
    val radioConfigRepository: RadioConfigRepository = koinInject()

    val isMeshBridgeEnabled by meshBridgePrefs.isMeshBridgeEnabled.collectAsStateWithLifecycle()
    val meshBridgeChannel by meshBridgePrefs.meshBridgeChannel.collectAsStateWithLifecycle()
    val isRunning by meshBridgeManager.isRunning.collectAsStateWithLifecycle()
    val isStarting by meshBridgeManager.isStarting.collectAsStateWithLifecycle()
    val connectionCount by meshBridgeManager.connectionCount.collectAsStateWithLifecycle()
    val hasError by meshBridgeManager.hasError.collectAsStateWithLifecycle()

    val channelSet by
        radioConfigRepository.channelSetFlow.collectAsStateWithLifecycle(initialValue = ChannelSet.Builder().build())

    val channelOptions =
        channelSet.settings
            .mapIndexed { index, settings ->
                val channelName = Channel(settings, channelSet.lora_config ?: Channel.default.loraConfig).name
                index to stringResource(Res.string.mesh_bridge_channel_option, index, channelName)
            }
            .let { options ->
                if (options.none { it.first == meshBridgeChannel }) {
                    options + (meshBridgeChannel to meshBridgeChannel.toString())
                } else {
                    options
                }
            }

    val status =
        MeshBridgeStatus.resolve(
            isSupported = meshBridgeManager.isSupported,
            isEnabled = isMeshBridgeEnabled,
            isRunning = isRunning,
            isStarting = isStarting,
            clientCount = connectionCount,
            hasError = hasError,
        )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.mesh_bridge)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MeshtasticIcons.ArrowBack,
                            contentDescription = stringResource(Res.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            TitledCard(title = stringResource(Res.string.mesh_bridge_section)) {
                SwitchPreference(
                    title = stringResource(Res.string.mesh_bridge_enabled),
                    summary = stringResource(Res.string.mesh_bridge_enabled_desc),
                    checked = isMeshBridgeEnabled,
                    enabled = true,
                    onCheckedChange = { meshBridgePrefs.setMeshBridgeEnabled(it) },
                )
                HorizontalDivider()
                MeshBridgeStatusRow(status = status, clientCount = connectionCount)

                if (isMeshBridgeEnabled) {
                    HorizontalDivider()
                    DropDownPreference(
                        title = stringResource(Res.string.mesh_bridge_channel),
                        summary = stringResource(Res.string.mesh_bridge_channel_desc),
                        enabled = true,
                        items = channelOptions,
                        selectedItem = meshBridgeChannel,
                        onItemSelected = { meshBridgePrefs.setMeshBridgeChannel(it) },
                    )
                    HorizontalDivider()
                    Text(
                        text = stringResource(Res.string.mesh_bridge_info_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }

                if (hasError) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = MeshtasticIcons.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = stringResource(Res.string.mesh_bridge_status_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MeshBridgeStatusRow(status: MeshBridgeStatus, clientCount: Int) {
    val (description, color) =
        when (status) {
            MeshBridgeStatus.Unavailable ->
                stringResource(Res.string.mesh_bridge_status_unavailable) to MaterialTheme.colorScheme.onSurfaceVariant

            MeshBridgeStatus.Off ->
                stringResource(Res.string.mesh_bridge_status_off) to MaterialTheme.colorScheme.onSurfaceVariant

            MeshBridgeStatus.Starting ->
                stringResource(Res.string.mesh_bridge_status_starting) to MaterialTheme.colorScheme.onSurfaceVariant

            MeshBridgeStatus.WaitingForClient ->
                stringResource(Res.string.mesh_bridge_status_waiting) to MaterialTheme.colorScheme.primary

            MeshBridgeStatus.Connected ->
                pluralStringResource(Res.plurals.mesh_bridge_status_connected, clientCount, clientCount) to
                    MaterialTheme.colorScheme.primary

            MeshBridgeStatus.Failed ->
                stringResource(Res.string.mesh_bridge_status_failed) to MaterialTheme.colorScheme.error

            MeshBridgeStatus.NotRunning ->
                stringResource(Res.string.mesh_bridge_status_not_running) to MaterialTheme.colorScheme.onSurfaceVariant
        }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(text = stringResource(Res.string.mesh_bridge_status), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
