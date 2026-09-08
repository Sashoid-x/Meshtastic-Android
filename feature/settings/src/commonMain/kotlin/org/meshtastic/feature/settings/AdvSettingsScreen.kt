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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.model.PhotoHostingProvider
import org.meshtastic.core.model.ReactionNotificationMode
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.adv_section_advanced
import org.meshtastic.core.resources.adv_section_media
import org.meshtastic.core.resources.adv_section_messaging
import org.meshtastic.core.resources.adv_section_notifications
import org.meshtastic.core.resources.adv_settings
import org.meshtastic.core.resources.built_in_image_viewer
import org.meshtastic.core.resources.built_in_image_viewer_summary
import org.meshtastic.core.resources.file_transfer_setting
import org.meshtastic.core.resources.file_transfer_setting_summary
import org.meshtastic.core.resources.insert_photo_link
import org.meshtastic.core.resources.insert_photo_link_summary
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
import org.meshtastic.core.ui.component.MainAppBar
import org.meshtastic.core.ui.component.SwitchPreference
import org.meshtastic.feature.settings.component.ExpressiveSection

@Composable
fun AdvSettingsScreen(settingsViewModel: SettingsViewModel, onNavigateUp: () -> Unit, modifier: Modifier = Modifier) {
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ExpressiveSection(title = stringResource(Res.string.adv_section_messaging)) {
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
                title = stringResource(Res.string.insert_photo_link),
                summary = stringResource(Res.string.insert_photo_link_summary),
                checked = insertPhotoLinkEnabled,
                enabled = true,
                onCheckedChange = onInsertPhotoLinkChange,
            )
            SwitchPreference(
                title = stringResource(Res.string.show_bell_button),
                summary = stringResource(Res.string.show_bell_button_summary),
                checked = showBellButton,
                enabled = true,
                onCheckedChange = onShowBellButtonChange,
            )
        }

        ExpressiveSection(title = stringResource(Res.string.adv_section_media)) {
            SwitchPreference(
                title = stringResource(Res.string.built_in_image_viewer),
                summary = stringResource(Res.string.built_in_image_viewer_summary),
                checked = builtInImageViewerEnabled,
                enabled = true,
                onCheckedChange = onBuiltInImageViewerChange,
            )
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
                title = stringResource(Res.string.pixel_art_messaging),
                summary = stringResource(Res.string.pixel_art_messaging_summary),
                checked = pixelArtEnabled,
                enabled = true,
                onCheckedChange = onPixelArtChange,
            )
            SwitchPreference(
                title = stringResource(Res.string.file_transfer_setting),
                summary = stringResource(Res.string.file_transfer_setting_summary),
                checked = fileTransferEnabled,
                enabled = true,
                onCheckedChange = onFileTransferChange,
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

        ExpressiveSection(title = stringResource(Res.string.adv_section_advanced)) {
            SwitchPreference(
                title = stringResource(Res.string.pinned_messages),
                summary = stringResource(Res.string.pinned_messages_summary),
                checked = pinnedMessagesEnabled,
                enabled = true,
                onCheckedChange = onPinnedMessagesChange,
            )
        }
    }
}
