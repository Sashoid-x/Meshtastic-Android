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
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.adv_settings
import org.meshtastic.core.resources.file_transfer_setting
import org.meshtastic.core.resources.file_transfer_setting_summary
import org.meshtastic.core.resources.photo_hosting_provider_disabled
import org.meshtastic.core.resources.photo_hosting_provider_meshapp
import org.meshtastic.core.resources.photo_hosting_provider_meshpic
import org.meshtastic.core.resources.photo_hosting_setting
import org.meshtastic.core.resources.photo_hosting_setting_summary
import org.meshtastic.core.resources.pixel_art_messaging
import org.meshtastic.core.resources.pixel_art_messaging_summary
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
            fileTransferEnabled = fileTransferEnabled,
            pixelArtEnabled = pixelArtEnabled,
            photoHostingProvider = photoHostingProvider,
            onTextCompressionChange = settingsViewModel::setTextCompressionEnabled,
            onFileTransferChange = settingsViewModel::setFileTransferEnabled,
            onPixelArtChange = settingsViewModel::setPixelArtEnabled,
            onPhotoHostingChange = settingsViewModel::setPhotoHostingProvider,
        )
    }
}

@Composable
private fun AdvSettingsContent(
    textCompressionEnabled: Boolean,
    fileTransferEnabled: Boolean,
    pixelArtEnabled: Boolean,
    photoHostingProvider: PhotoHostingProvider,
    onTextCompressionChange: (Boolean) -> Unit,
    onFileTransferChange: (Boolean) -> Unit,
    onPixelArtChange: (Boolean) -> Unit,
    onPhotoHostingChange: (PhotoHostingProvider) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ExpressiveSection(title = stringResource(Res.string.adv_settings)) {
            SwitchPreference(
                title = stringResource(Res.string.text_compression),
                summary = stringResource(Res.string.text_compression_summary),
                checked = textCompressionEnabled,
                enabled = true,
                onCheckedChange = onTextCompressionChange,
            )
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
        }
    }
}
