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
package org.meshtastic.feature.node.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconToggleButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.common.util.MeasurementSystem
import org.meshtastic.core.model.CustomNodeName
import org.meshtastic.core.model.Node
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.actions
import org.meshtastic.core.resources.direct_message
import org.meshtastic.core.resources.favorite
import org.meshtastic.core.resources.ignore
import org.meshtastic.core.resources.long_name
import org.meshtastic.core.resources.mute_notifications
import org.meshtastic.core.resources.remove
import org.meshtastic.core.resources.rename
import org.meshtastic.core.resources.save
import org.meshtastic.core.resources.share_contact
import org.meshtastic.core.resources.short_name
import org.meshtastic.core.ui.component.ListItem
import org.meshtastic.core.ui.component.SwitchListItem
import org.meshtastic.core.ui.icon.Delete
import org.meshtastic.core.ui.icon.Edit
import org.meshtastic.core.ui.icon.Favorite
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.Message
import org.meshtastic.core.ui.icon.NotFavorite
import org.meshtastic.core.ui.icon.QrCode2
import org.meshtastic.core.ui.icon.VolumeMute
import org.meshtastic.core.ui.icon.VolumeOff
import org.meshtastic.core.ui.icon.VolumeUp
import org.meshtastic.feature.node.model.LogsType
import org.meshtastic.feature.node.model.NodeDetailAction
import org.meshtastic.feature.node.model.showsDirectMessageAction
import org.meshtastic.proto.Telemetry

@Composable
fun DeviceActions(
    node: Node,
    ourNode: Node?,
    lastTracerouteTime: Long?,
    lastRequestNeighborsTime: Long?,
    availableLogs: Set<LogsType>,
    onAction: (NodeDetailAction) -> Unit,
    displayUnits: MeasurementSystem,
    isFahrenheit: Boolean,
    modifier: Modifier = Modifier,
    isLocal: Boolean = false,
    hasConversation: Boolean = false,
    pressureInMmHg: Boolean = false,
    airQualityHistory: List<Telemetry> = emptyList(),
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionCard(title = Res.string.actions) {
            PrimaryActionsRow(node = node, isLocal = isLocal, hasConversation = hasConversation, onAction = onAction)

            if (!isLocal) {
                SectionDivider(Modifier.padding(vertical = 8.dp))
                ManagementActions(node = node, onAction = onAction)
            }
        }

        TelemetricActionsSection(
            node = node,
            ourNode = ourNode,
            availableLogs = availableLogs,
            lastTracerouteTime = lastTracerouteTime,
            lastRequestNeighborsTime = lastRequestNeighborsTime,
            displayUnits = displayUnits,
            isFahrenheit = isFahrenheit,
            onAction = onAction,
            isLocal = isLocal,
            pressureInMmHg = pressureInMmHg,
            airQualityHistory = airQualityHistory,
        )
    }
}

@Composable
private fun PrimaryActionsRow(
    node: Node,
    isLocal: Boolean,
    hasConversation: Boolean,
    onAction: (NodeDetailAction) -> Unit,
) {
    // Hidden for a node the radio would refuse to send to: unmessagable, or no public key on file. An existing
    // thread keeps it visible so a conversation is never stranded.
    val showMessageAction = !isLocal && node.showsDirectMessageAction(hasConversation)
    Row(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showMessageAction) {
            Button(
                onClick = { onAction(NodeDetailAction.HandleNodeMenuAction(NodeMenuAction.DirectMessage(node))) },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.large,
                colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Icon(MeshtasticIcons.Message, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.direct_message))
            }
        }

        OutlinedButton(
            onClick = { onAction(NodeDetailAction.ShareContact) },
            modifier = if (showMessageAction) Modifier else Modifier.weight(1f),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(MeshtasticIcons.QrCode2, contentDescription = null)
            if (!showMessageAction) {
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.share_contact))
            }
        }

        if (!isLocal) {
            OutlinedIconToggleButton(
                checked = node.isFavorite,
                onCheckedChange = { onAction(NodeDetailAction.HandleNodeMenuAction(NodeMenuAction.Favorite(node))) },
            ) {
                Icon(
                    imageVector = if (node.isFavorite) MeshtasticIcons.Favorite else MeshtasticIcons.NotFavorite,
                    contentDescription = stringResource(Res.string.favorite),
                    tint = if (node.isFavorite) Color.Yellow else LocalContentColor.current,
                )
            }
        }
    }
}

@Composable
private fun ManagementActions(node: Node, onAction: (NodeDetailAction) -> Unit) {
    Column {
        RenameNodeAction(node = node, onAction = onAction)

        SwitchListItem(
            text = stringResource(Res.string.ignore),
            leadingIcon =
            if (node.isIgnored) {
                MeshtasticIcons.VolumeMute
            } else {
                MeshtasticIcons.VolumeUp
            },
            checked = node.isIgnored,
            onClick = { onAction(NodeDetailAction.HandleNodeMenuAction(NodeMenuAction.Ignore(node))) },
        )

        if (node.capabilities.canMuteNode) {
            SwitchListItem(
                text = stringResource(Res.string.mute_notifications),
                leadingIcon =
                if (node.isMuted) {
                    MeshtasticIcons.VolumeOff
                } else {
                    MeshtasticIcons.VolumeUp
                },
                checked = node.isMuted,
                onClick = { onAction(NodeDetailAction.HandleNodeMenuAction(NodeMenuAction.Mute(node))) },
            )
        }

        ListItem(
            text = stringResource(Res.string.remove),
            leadingIcon = MeshtasticIcons.Delete,
            trailingIcon = null,
            textColor = MaterialTheme.colorScheme.error,
            leadingIconTint = MaterialTheme.colorScheme.error,
            onClick = { onAction(NodeDetailAction.HandleNodeMenuAction(NodeMenuAction.Remove(node))) },
        )
    }
}

@Composable
private fun RenameNodeAction(node: Node, onAction: (NodeDetailAction) -> Unit) {
    val isRenamed = node.customName?.enabled == true
    var isRenameExpanded by remember(node.num, isRenamed) { mutableStateOf(isRenamed) }
    var customShort by
        remember(node.num, node.customName?.shortName) { mutableStateOf(node.customName?.shortName ?: "") }
    var customLong by remember(node.num, node.customName?.longName) { mutableStateOf(node.customName?.longName ?: "") }

    SwitchListItem(
        text = stringResource(Res.string.rename),
        leadingIcon = MeshtasticIcons.Edit,
        checked = isRenameExpanded,
        onClick = {
            val newExpanded = !isRenameExpanded
            isRenameExpanded = newExpanded
            val updatedName = CustomNodeName(shortName = customShort, longName = customLong, enabled = newExpanded)
            onAction(NodeDetailAction.SetCustomNodeName(node.num, updatedName))
        },
    )

    AnimatedVisibility(visible = isRenameExpanded) {
        RenameNodeInputs(
            node = node,
            customShort = customShort,
            onShortChange = { customShort = it },
            customLong = customLong,
            onLongChange = { customLong = it },
            onSave = {
                onAction(
                    NodeDetailAction.SetCustomNodeName(
                        node.num,
                        CustomNodeName(shortName = customShort, longName = customLong, enabled = true),
                    ),
                )
            },
        )
    }
}

@Composable
private fun RenameNodeInputs(
    node: Node,
    customShort: String,
    onShortChange: (String) -> Unit,
    customLong: String,
    onLongChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val isDirty =
            customShort != (node.customName?.shortName ?: "") || customLong != (node.customName?.longName ?: "")
        val keyboardController = LocalSoftwareKeyboardController.current
        val saveAndDismiss: () -> Unit = {
            onSave()
            keyboardController?.hide()
        }

        OutlinedTextField(
            value = customShort,
            onValueChange = onShortChange,
            label = { Text(stringResource(Res.string.short_name)) },
            placeholder = { Text(node.originalUser.short_name) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )

        OutlinedTextField(
            value = customLong,
            onValueChange = onLongChange,
            label = { Text(stringResource(Res.string.long_name)) },
            placeholder = { Text(node.originalUser.long_name) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { saveAndDismiss() }),
        )

        if (isDirty) {
            Button(
                onClick = saveAndDismiss,
                modifier = Modifier.align(Alignment.End),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(stringResource(Res.string.save))
            }
        }
    }
}
