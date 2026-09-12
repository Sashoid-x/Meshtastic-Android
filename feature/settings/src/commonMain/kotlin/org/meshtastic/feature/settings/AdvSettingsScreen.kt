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
package org.meshtastic.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.common.util.CommonUri
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.model.BackupPacketType
import org.meshtastic.core.model.MessageImportResult
import org.meshtastic.core.model.PhotoHostingProvider
import org.meshtastic.core.model.ReactionNotificationMode
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.adv_appearance_settings_summary
import org.meshtastic.core.resources.adv_appearance_settings_title
import org.meshtastic.core.resources.adv_export_messages
import org.meshtastic.core.resources.adv_export_messages_error
import org.meshtastic.core.resources.adv_export_messages_success
import org.meshtastic.core.resources.adv_export_messages_summary
import org.meshtastic.core.resources.adv_exporting_messages_desc
import org.meshtastic.core.resources.adv_exporting_messages_title
import org.meshtastic.core.resources.adv_import_messages
import org.meshtastic.core.resources.adv_import_messages_error
import org.meshtastic.core.resources.adv_import_messages_summary
import org.meshtastic.core.resources.adv_import_result_all_skipped
import org.meshtastic.core.resources.adv_import_result_summary
import org.meshtastic.core.resources.adv_import_result_title
import org.meshtastic.core.resources.adv_importing_messages_desc
import org.meshtastic.core.resources.adv_importing_messages_title
import org.meshtastic.core.resources.adv_section_appearance
import org.meshtastic.core.resources.adv_section_backup
import org.meshtastic.core.resources.adv_section_media
import org.meshtastic.core.resources.adv_section_messaging
import org.meshtastic.core.resources.adv_section_notifications
import org.meshtastic.core.resources.adv_section_photos
import org.meshtastic.core.resources.adv_settings
import org.meshtastic.core.resources.built_in_image_viewer
import org.meshtastic.core.resources.built_in_image_viewer_summary
import org.meshtastic.core.resources.file_transfer_setting
import org.meshtastic.core.resources.file_transfer_setting_summary
import org.meshtastic.core.resources.insert_photo_link
import org.meshtastic.core.resources.insert_photo_link_summary
import org.meshtastic.core.resources.okay
import org.meshtastic.core.resources.photo_hosting_provider_disabled
import org.meshtastic.core.resources.photo_hosting_provider_meshapp
import org.meshtastic.core.resources.photo_hosting_provider_meshpic
import org.meshtastic.core.resources.photo_hosting_setting
import org.meshtastic.core.resources.photo_hosting_setting_summary
import org.meshtastic.core.resources.pinned_messages
import org.meshtastic.core.resources.pinned_messages_summary
import org.meshtastic.core.resources.pixel_art_messaging
import org.meshtastic.core.resources.pixel_art_messaging_summary
import org.meshtastic.core.resources.reaction_mode_all
import org.meshtastic.core.resources.reaction_mode_disabled
import org.meshtastic.core.resources.reaction_mode_private_only
import org.meshtastic.core.resources.reaction_notifications
import org.meshtastic.core.resources.reaction_notifications_summary
import org.meshtastic.core.resources.send_on_enter
import org.meshtastic.core.resources.send_on_enter_summary
import org.meshtastic.core.resources.show_bell_button
import org.meshtastic.core.resources.show_bell_button_summary
import org.meshtastic.core.resources.text_compression
import org.meshtastic.core.resources.text_compression_summary
import org.meshtastic.core.ui.component.DropDownPreference
import org.meshtastic.core.ui.component.ListItem
import org.meshtastic.core.ui.component.MainAppBar
import org.meshtastic.core.ui.component.MeshtasticDialog
import org.meshtastic.core.ui.component.SwitchPreference
import org.meshtastic.core.ui.icon.FileDownload
import org.meshtastic.core.ui.icon.FormatPaint
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.Upload
import org.meshtastic.core.ui.util.rememberOpenFileLauncher
import org.meshtastic.core.ui.util.rememberSaveFileLauncher
import org.meshtastic.core.ui.util.rememberShowToast
import org.meshtastic.feature.settings.component.ExpressiveSection
import org.meshtastic.feature.settings.component.PacketTypePickerDialog
import kotlin.time.Instant.Companion.fromEpochMilliseconds

private val BACKUP_TIMESTAMP_FORMAT =
    LocalDateTime.Format {
        year()
        monthNumber()
        day()
        char('_')
        hour()
        minute()
        second()
    }

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun AdvSettingsScreen(
    settingsViewModel: SettingsViewModel,
    onNavigateUp: () -> Unit,
    onNavigateToAppearance: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ourNode by settingsViewModel.ourNodeInfo.collectAsStateWithLifecycle()
    val textCompressionEnabled by settingsViewModel.textCompressionEnabled.collectAsStateWithLifecycle()
    val pixelArtEnabled by settingsViewModel.pixelArtEnabled.collectAsStateWithLifecycle()
    val fileTransferEnabled by settingsViewModel.fileTransferEnabled.collectAsStateWithLifecycle()
    val photoHostingProvider by settingsViewModel.photoHostingProvider.collectAsStateWithLifecycle()
    val builtInImageViewerEnabled by settingsViewModel.builtInImageViewerEnabled.collectAsStateWithLifecycle()
    val insertPhotoLinkEnabled by settingsViewModel.insertPhotoLinkEnabled.collectAsStateWithLifecycle()
    val sendOnEnterEnabled by settingsViewModel.sendOnEnterEnabled.collectAsStateWithLifecycle()
    val showBellButton by settingsViewModel.showBellButton.collectAsStateWithLifecycle()
    val reactionNotificationMode by settingsViewModel.reactionNotificationMode.collectAsStateWithLifecycle()
    val pinnedMessagesEnabled by settingsViewModel.pinnedMessagesEnabled.collectAsStateWithLifecycle()

    val isImporting by settingsViewModel.isImporting.collectAsStateWithLifecycle()
    val isExporting by settingsViewModel.isExporting.collectAsStateWithLifecycle()

    val coroutineScope = rememberCoroutineScope()
    val showToast = rememberShowToast()

    var pendingImportUri by remember { mutableStateOf<CommonUri?>(null) }
    var showImportTypeDialog by remember { mutableStateOf(false) }
    var showExportTypeDialog by remember { mutableStateOf(false) }
    var pendingExportTypes by remember { mutableStateOf(BackupPacketType.entries.toSet()) }
    var importResult by remember { mutableStateOf<MessageImportResult?>(null) }
    var importFailed by remember { mutableStateOf(false) }
    var importErrorMessage by remember { mutableStateOf<String?>(null) }

    val exportMessagesLauncher = rememberSaveFileLauncher { uri ->
        settingsViewModel.exportMessages(uri, pendingExportTypes) { success, count ->
            coroutineScope.launch {
                if (success) {
                    showToast(getString(Res.string.adv_export_messages_success, count))
                } else {
                    showToast(getString(Res.string.adv_export_messages_error))
                }
            }
        }
    }

    val importMessagesLauncher = rememberOpenFileLauncher { uri ->
        if (uri != null) {
            pendingImportUri = uri
            showImportTypeDialog = true
        }
    }

    if (showExportTypeDialog) {
        PacketTypePickerDialog(
            title = stringResource(Res.string.adv_export_messages),
            confirmText = stringResource(Res.string.adv_export_messages),
            onConfirm = { types ->
                pendingExportTypes = types
                showExportTypeDialog = false
                val nodeShortName = ourNode?.user?.short_name?.takeIf { it.isNotBlank() } ?: "meshtastic"
                val timestamp =
                    fromEpochMilliseconds(nowMillis)
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                        .format(BACKUP_TIMESTAMP_FORMAT)
                exportMessagesLauncher("Meshtastic_messages_${nodeShortName}_$timestamp.json", "application/json")
            },
            onDismiss = { showExportTypeDialog = false },
        )
    }

    if (showImportTypeDialog && pendingImportUri != null) {
        PacketTypePickerDialog(
            title = stringResource(Res.string.adv_import_messages),
            confirmText = stringResource(Res.string.adv_import_messages),
            onConfirm = { types ->
                val uri = pendingImportUri
                showImportTypeDialog = false
                pendingImportUri = null
                if (uri != null) {
                    settingsViewModel.importMessages(uri, types) { success, result, errorMsg ->
                        coroutineScope.launch {
                            if (success && result != null) {
                                importResult = result
                                importFailed = false
                                importErrorMessage = null
                            } else {
                                importResult = null
                                importFailed = true
                                importErrorMessage = errorMsg
                            }
                        }
                    }
                }
            },
            onDismiss = {
                showImportTypeDialog = false
                pendingImportUri = null
            },
        )
    }

    if (isImporting) {
        MeshtasticDialog(
            title = stringResource(Res.string.adv_importing_messages_title),
            onDismiss = {},
            confirmText = null,
            dismissText = null,
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(Res.string.adv_importing_messages_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
        )
    }

    if (isExporting) {
        MeshtasticDialog(
            title = stringResource(Res.string.adv_exporting_messages_title),
            onDismiss = {},
            confirmText = null,
            dismissText = null,
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(Res.string.adv_exporting_messages_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
        )
    }

    val currentResult = importResult
    if (currentResult != null) {
        val resultMessage =
            if (currentResult.importedPackets == 0 && currentResult.skippedPackets > 0) {
                stringResource(Res.string.adv_import_result_all_skipped, currentResult.skippedPackets)
            } else {
                stringResource(
                    Res.string.adv_import_result_summary,
                    currentResult.importedPackets,
                    currentResult.skippedPackets,
                    currentResult.importedReactions,
                    currentResult.totalPackets,
                )
            }
        MeshtasticDialog(
            title = stringResource(Res.string.adv_import_result_title),
            onDismiss = { importResult = null },
            confirmText = stringResource(Res.string.okay),
            onConfirm = { importResult = null },
            text = { Text(text = resultMessage, style = MaterialTheme.typography.bodyMedium) },
        )
    }

    if (importFailed) {
        val baseError = stringResource(Res.string.adv_import_messages_error)
        val textToShow =
            if (!importErrorMessage.isNullOrBlank()) {
                "$baseError\n\n$importErrorMessage"
            } else {
                baseError
            }
        MeshtasticDialog(
            title = stringResource(Res.string.adv_import_result_title),
            onDismiss = {
                importFailed = false
                importErrorMessage = null
            },
            confirmText = stringResource(Res.string.okay),
            onConfirm = {
                importFailed = false
                importErrorMessage = null
            },
            text = { Text(text = textToShow, style = MaterialTheme.typography.bodyMedium) },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            MainAppBar(
                title = stringResource(Res.string.adv_settings),
                canNavigateUp = true,
                onNavigateUp = onNavigateUp,
                ourNode = null,
                showNodeChip = false,
                actions = {},
                onClickChip = {},
            )
        },
    ) { paddingValues ->
        AdvSettingsContent(
            modifier = Modifier.padding(paddingValues),
            textCompressionEnabled = textCompressionEnabled,
            sendOnEnterEnabled = sendOnEnterEnabled,
            insertPhotoLinkEnabled = insertPhotoLinkEnabled,
            showBellButton = showBellButton,
            builtInImageViewerEnabled = builtInImageViewerEnabled,
            photoHostingProvider = photoHostingProvider,
            pixelArtEnabled = pixelArtEnabled,
            fileTransferEnabled = fileTransferEnabled,
            reactionNotificationMode = reactionNotificationMode,
            pinnedMessagesEnabled = pinnedMessagesEnabled,
            onTextCompressionChange = settingsViewModel::setTextCompressionEnabled,
            onSendOnEnterChange = settingsViewModel::setSendOnEnterEnabled,
            onInsertPhotoLinkChange = settingsViewModel::setInsertPhotoLinkEnabled,
            onShowBellButtonChange = settingsViewModel::setShowBellButton,
            onBuiltInImageViewerChange = settingsViewModel::setBuiltInImageViewerEnabled,
            onPhotoHostingChange = settingsViewModel::setPhotoHostingProvider,
            onPixelArtChange = settingsViewModel::setPixelArtEnabled,
            onFileTransferChange = settingsViewModel::setFileTransferEnabled,
            onReactionNotificationModeChange = settingsViewModel::setReactionNotificationMode,
            onPinnedMessagesChange = settingsViewModel::setPinnedMessagesEnabled,
            onNavigateToAppearance = onNavigateToAppearance,
            onExportMessages = { showExportTypeDialog = true },
            onImportMessages = { importMessagesLauncher("*/*") },
        )
    }
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun AdvSettingsContent(
    textCompressionEnabled: Boolean,
    sendOnEnterEnabled: Boolean,
    insertPhotoLinkEnabled: Boolean,
    showBellButton: Boolean,
    builtInImageViewerEnabled: Boolean,
    photoHostingProvider: PhotoHostingProvider,
    pixelArtEnabled: Boolean,
    fileTransferEnabled: Boolean,
    reactionNotificationMode: ReactionNotificationMode,
    pinnedMessagesEnabled: Boolean,
    onTextCompressionChange: (Boolean) -> Unit,
    onSendOnEnterChange: (Boolean) -> Unit,
    onInsertPhotoLinkChange: (Boolean) -> Unit,
    onShowBellButtonChange: (Boolean) -> Unit,
    onBuiltInImageViewerChange: (Boolean) -> Unit,
    onPhotoHostingChange: (PhotoHostingProvider) -> Unit,
    onPixelArtChange: (Boolean) -> Unit,
    onFileTransferChange: (Boolean) -> Unit,
    onReactionNotificationModeChange: (ReactionNotificationMode) -> Unit,
    onPinnedMessagesChange: (Boolean) -> Unit,
    onNavigateToAppearance: () -> Unit,
    onExportMessages: () -> Unit,
    onImportMessages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ExpressiveSection(title = stringResource(Res.string.adv_section_appearance)) {
            ListItem(
                text = stringResource(Res.string.adv_appearance_settings_title),
                supportingText = stringResource(Res.string.adv_appearance_settings_summary),
                leadingIcon = MeshtasticIcons.FormatPaint,
                trailingIcon = null,
                onClick = onNavigateToAppearance,
            )
        }

        ExpressiveSection(title = stringResource(Res.string.adv_section_messaging)) {
            SwitchPreference(
                title = stringResource(Res.string.pinned_messages),
                summary = stringResource(Res.string.pinned_messages_summary),
                checked = pinnedMessagesEnabled,
                enabled = true,
                onCheckedChange = onPinnedMessagesChange,
            )
            SwitchPreference(
                title = stringResource(Res.string.text_compression),
                summary = stringResource(Res.string.text_compression_summary),
                checked = textCompressionEnabled,
                enabled = true,
                onCheckedChange = onTextCompressionChange,
            )
            SwitchPreference(
                title = stringResource(Res.string.send_on_enter),
                summary = stringResource(Res.string.send_on_enter_summary),
                checked = sendOnEnterEnabled,
                enabled = true,
                onCheckedChange = onSendOnEnterChange,
            )
            SwitchPreference(
                title = stringResource(Res.string.show_bell_button),
                summary = stringResource(Res.string.show_bell_button_summary),
                checked = showBellButton,
                enabled = true,
                onCheckedChange = onShowBellButtonChange,
            )
        }

        ExpressiveSection(title = stringResource(Res.string.adv_section_photos)) {
            DropDownPreference(
                title = stringResource(Res.string.photo_hosting_setting),
                summary = stringResource(Res.string.photo_hosting_setting_summary),
                selectedItem = photoHostingProvider,
                onItemSelected = onPhotoHostingChange,
                enabled = true,
                itemLabel = { provider ->
                    when (provider) {
                        PhotoHostingProvider.DISABLED -> stringResource(Res.string.photo_hosting_provider_disabled)
                        PhotoHostingProvider.MESHPIC -> stringResource(Res.string.photo_hosting_provider_meshpic)
                        PhotoHostingProvider.MESHAPP -> stringResource(Res.string.photo_hosting_provider_meshapp)
                    }
                },
            )
            SwitchPreference(
                title = stringResource(Res.string.insert_photo_link),
                summary = stringResource(Res.string.insert_photo_link_summary),
                checked = insertPhotoLinkEnabled,
                enabled = true,
                onCheckedChange = onInsertPhotoLinkChange,
            )
            SwitchPreference(
                title = stringResource(Res.string.built_in_image_viewer),
                summary = stringResource(Res.string.built_in_image_viewer_summary),
                checked = builtInImageViewerEnabled,
                enabled = true,
                onCheckedChange = onBuiltInImageViewerChange,
            )
        }

        ExpressiveSection(title = stringResource(Res.string.adv_section_media)) {
            SwitchPreference(
                title = stringResource(Res.string.file_transfer_setting),
                summary = stringResource(Res.string.file_transfer_setting_summary),
                checked = fileTransferEnabled,
                enabled = true,
                onCheckedChange = onFileTransferChange,
            )
            SwitchPreference(
                title = stringResource(Res.string.pixel_art_messaging),
                summary = stringResource(Res.string.pixel_art_messaging_summary),
                checked = pixelArtEnabled,
                enabled = true,
                onCheckedChange = onPixelArtChange,
            )
        }

        ExpressiveSection(title = stringResource(Res.string.adv_section_notifications)) {
            DropDownPreference(
                title = stringResource(Res.string.reaction_notifications),
                summary = stringResource(Res.string.reaction_notifications_summary),
                selectedItem = reactionNotificationMode,
                onItemSelected = onReactionNotificationModeChange,
                enabled = true,
                itemLabel = { mode ->
                    when (mode) {
                        ReactionNotificationMode.ALL -> stringResource(Res.string.reaction_mode_all)
                        ReactionNotificationMode.PRIVATE_ONLY -> stringResource(Res.string.reaction_mode_private_only)
                        ReactionNotificationMode.DISABLED -> stringResource(Res.string.reaction_mode_disabled)
                    }
                },
            )
        }

        ExpressiveSection(title = stringResource(Res.string.adv_section_backup)) {
            ListItem(
                text = stringResource(Res.string.adv_export_messages),
                supportingText = stringResource(Res.string.adv_export_messages_summary),
                leadingIcon = MeshtasticIcons.FileDownload,
                trailingIcon = null,
                onClick = onExportMessages,
            )
            ListItem(
                text = stringResource(Res.string.adv_import_messages),
                supportingText = stringResource(Res.string.adv_import_messages_summary),
                leadingIcon = MeshtasticIcons.Upload,
                trailingIcon = null,
                onClick = onImportMessages,
            )
        }
    }
}
