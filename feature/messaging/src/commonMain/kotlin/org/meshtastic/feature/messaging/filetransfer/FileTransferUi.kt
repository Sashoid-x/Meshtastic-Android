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
    onRetry: (() -> Unit)? = null,
    onOpenFile: ((String) -> Unit)? = null,
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
            TransferProgressButtons(
                state = state,
                onMinimize = onMinimize,
                onCancel = onCancel,
                onDismiss = onDismiss,
                onRetry = onRetry,
                onOpenFile = onOpenFile,
            )
        },
    )
}

@Composable
private fun TransferProgressContent(state: TransferState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when (state) {
            is TransferState.Sending -> SendingProgressContent(state)

            is TransferState.Receiving -> ReceivingProgressContent(state)

            is TransferState.Completed -> {
                Text(text = state.fileName, style = MaterialTheme.typography.bodyMedium)
                if (state.statusMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
@Suppress("LongMethod")
private fun SendingProgressContent(state: TransferState.Sending) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = state.fileName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (state.isGzip) {
                Text(
                    text = "Gzip",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val countText =
                stringResource(Res.string.file_transfer_chunk_progress, state.currentChunk, state.totalChunks)
            Text(
                text = "$countText (${state.percent}%)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatSpeed(state.speedBytesPerSec),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        state.etaSeconds?.let { eta ->
            Text(
                text = formatEta(eta),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.statusMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = state.statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (state.passNumber > 1) {
            Text(
                text = "Досыл чанков (проход ${state.passNumber})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (state.retryCount > 0) {
            Text(
                text = "Retries: ${state.retryCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
@Suppress("LongMethod")
private fun ReceivingProgressContent(state: TransferState.Receiving) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = state.fileName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (state.isGzip) {
                Text(
                    text = "Gzip",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val countText =
                stringResource(Res.string.file_transfer_chunk_progress, state.receivedChunks, state.totalChunks)
            Text(
                text = "$countText (${state.percent}%)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatSpeed(state.speedBytesPerSec),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        state.etaSeconds?.let { eta ->
            Text(
                text = formatEta(eta),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.statusMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = state.statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (state.passNumber > 1) {
            Text(
                text = "Досыл чанков (проход ${state.passNumber})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun TransferProgressButtons(
    state: TransferState,
    onMinimize: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null,
    onOpenFile: ((String) -> Unit)? = null,
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

        is TransferState.Failed -> {
            Row {
                if (state.canRetry && onRetry != null) {
                    TextButton(onClick = onRetry) { Text(text = "Повторить") }
                }
                TextButton(onClick = onDismiss) { Text(text = "OK") }
            }
        }

        is TransferState.Completed -> {
            Row {
                if (state.savedPath != null && onOpenFile != null) {
                    TextButton(onClick = { onOpenFile(state.savedPath) }) { Text(text = "Открыть") }
                }
                TextButton(onClick = onDismiss) { Text(text = "OK") }
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
                BannerDetails(state = state, modifier = Modifier.weight(1f))
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

@Composable
private fun BannerDetails(state: TransferState, modifier: Modifier = Modifier) {
    val info = bannerInfo(state)
    Column(modifier = modifier) {
        Text(
            text = "${info.title} • ${info.percent}% (${info.speed})",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            text = info.fileName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(progress = { info.progress }, modifier = Modifier.fillMaxWidth())
    }
}

private data class BannerDisplayInfo(
    val title: String,
    val fileName: String,
    val progress: Float,
    val percent: Int,
    val speed: String,
)

@Composable
private fun bannerInfo(state: TransferState): BannerDisplayInfo = when (state) {
    is TransferState.Sending ->
        BannerDisplayInfo(
            title = stringResource(Res.string.file_transfer_sending),
            fileName = state.fileName,
            progress = state.progress,
            percent = state.percent,
            speed = formatSpeed(state.speedBytesPerSec),
        )

    is TransferState.Receiving ->
        BannerDisplayInfo(
            title = stringResource(Res.string.file_transfer_receiving),
            fileName = state.fileName,
            progress = state.progress,
            percent = state.percent,
            speed = formatSpeed(state.speedBytesPerSec),
        )

    else -> BannerDisplayInfo("", "", 0f, 0, "")
}
