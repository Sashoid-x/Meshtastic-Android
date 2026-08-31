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
package org.meshtastic.feature.messaging.filetransfer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.cancel
import org.meshtastic.core.resources.file_transfer_cancel
import org.meshtastic.core.resources.file_transfer_chunk_progress
import org.meshtastic.core.resources.file_transfer_complete
import org.meshtastic.core.resources.file_transfer_error
import org.meshtastic.core.resources.file_transfer_minimize
import org.meshtastic.core.resources.file_transfer_receiving
import org.meshtastic.core.resources.file_transfer_saved
import org.meshtastic.core.resources.file_transfer_select_file
import org.meshtastic.core.resources.file_transfer_sending
import org.meshtastic.core.resources.file_transfer_warning_body
import org.meshtastic.core.resources.file_transfer_warning_title
import org.meshtastic.core.ui.icon.Close
import org.meshtastic.core.ui.icon.MeshtasticIcons

/** Dialog shown before file selection, warning about LoRa transfer constraints. */
@Composable
fun FileTransferWarningDialog(estimatedTime: String, onSelectFile: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.file_transfer_warning_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Text(
                text = stringResource(Res.string.file_transfer_warning_body, estimatedTime),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onSelectFile) { Text(text = stringResource(Res.string.file_transfer_select_file)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(text = stringResource(Res.string.cancel)) } },
    )
}

/** Full progress dialog shown during an active file transfer. */
@Composable
fun FileTransferProgressDialog(
    state: TransferState,
    onMinimize: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { /* prevent accidental dismiss */ },
        title = {
            Text(
                text =
                when (state) {
                    is TransferState.Sending -> stringResource(Res.string.file_transfer_sending)
                    is TransferState.Receiving -> stringResource(Res.string.file_transfer_receiving)
                    is TransferState.Completed -> stringResource(Res.string.file_transfer_complete)
                    is TransferState.Failed -> stringResource(Res.string.file_transfer_error, state.reason)
                    else -> ""
                },
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = { TransferProgressContent(state) },
        confirmButton = {
            TransferProgressButtons(state = state, onMinimize = onMinimize, onCancel = onCancel, onDismiss = onDismiss)
        },
    )
}

@Composable
private fun TransferProgressContent(state: TransferState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when (state) {
            is TransferState.Sending -> {
                Text(text = state.fileName, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text =
                    stringResource(Res.string.file_transfer_chunk_progress, state.currentChunk, state.totalChunks),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.retryCount > 0) {
                    Text(
                        text = "Retries: ${state.retryCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            is TransferState.Receiving -> {
                Text(text = state.fileName, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text =
                    stringResource(
                        Res.string.file_transfer_chunk_progress,
                        state.receivedChunks,
                        state.totalChunks,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is TransferState.Completed -> {
                Text(text = state.fileName, style = MaterialTheme.typography.bodyMedium)
                if (state.savedPath != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(Res.string.file_transfer_saved),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            is TransferState.Failed -> {
                Text(text = state.fileName, style = MaterialTheme.typography.bodyMedium)
            }

            else -> {}
        }
    }
}

@Composable
private fun TransferProgressButtons(
    state: TransferState,
    onMinimize: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is TransferState.Sending,
        is TransferState.Receiving,
        -> {
            Row {
                TextButton(onClick = onMinimize) { Text(text = stringResource(Res.string.file_transfer_minimize)) }
                TextButton(onClick = onCancel) {
                    Text(
                        text = stringResource(Res.string.file_transfer_cancel),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        else -> {
            TextButton(onClick = onDismiss) { Text(text = "OK") }
        }
    }
}

/** Compact banner shown when a transfer is minimized. Pinned to the top of the chat. */
@Composable
fun FileTransferBanner(
    state: TransferState,
    onExpand: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = state is TransferState.Sending || state is TransferState.Receiving
    AnimatedVisibility(visible = isActive, enter = expandVertically(), exit = shrinkVertically(), modifier = modifier) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            onClick = onExpand,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val title =
                        when (state) {
                            is TransferState.Sending -> stringResource(Res.string.file_transfer_sending)
                            is TransferState.Receiving -> stringResource(Res.string.file_transfer_receiving)
                            else -> ""
                        }
                    val fileName =
                        when (state) {
                            is TransferState.Sending -> state.fileName
                            is TransferState.Receiving -> state.fileName
                            else -> ""
                        }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val progress =
                        when (state) {
                            is TransferState.Sending -> state.progress
                            is TransferState.Receiving -> state.progress
                            else -> 0f
                        }
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = MeshtasticIcons.Close,
                        contentDescription = stringResource(Res.string.file_transfer_cancel),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}
