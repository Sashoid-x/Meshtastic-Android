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
package org.meshtastic.feature.settings.radio.component

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import co.touchlab.kermit.Logger
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.common.util.RtttlParser
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.import_label
import org.meshtastic.core.resources.play
import org.meshtastic.core.resources.ringtone_file_empty
import org.meshtastic.core.resources.ringtone_import_error
import org.meshtastic.core.resources.ringtone_imported
import org.meshtastic.core.resources.stop
import org.meshtastic.core.ui.icon.FolderOpen
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.PlayArrow
import org.meshtastic.core.ui.icon.Stop
import org.meshtastic.core.ui.sound.RtttlAudioPlayer

private const val MAX_RINGTONE_SIZE = 230
private const val IMPORT_ERROR_PLACEHOLDER = "@@ERROR@@"
private const val RINGTONE_PLAYER_ID = "external_notification_ringtone"

@Suppress("TooGenericExceptionCaught")
@Composable
actual fun RingtoneTrailingIcon(ringtoneInput: String, onRingtoneImported: (String) -> Unit, enabled: Boolean) {
    val context = LocalContext.current
    val importedText = stringResource(Res.string.ringtone_imported)
    val emptyText = stringResource(Res.string.ringtone_file_empty)
    // Pre-resolve the format pattern for use in the non-composable launcher callback.
    // Using a sentinel placeholder that will be replaced at call-site.
    val importErrorPrefix = stringResource(Res.string.ringtone_import_error, IMPORT_ERROR_PLACEHOLDER)

    val playingId by RtttlAudioPlayer.playingId.collectAsState()
    val isPlaying = playingId == RINGTONE_PLAYER_ID

    DisposableEffect(Unit) {
        onDispose {
            if (RtttlAudioPlayer.playingId.value == RINGTONE_PLAYER_ID) {
                RtttlAudioPlayer.stop()
            }
        }
    }

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                try {
                    context.contentResolver.openInputStream(it)?.use { stream ->
                        stream.bufferedReader().use { reader ->
                            val buffer = CharArray(MAX_RINGTONE_SIZE)
                            val read = reader.read(buffer)
                            if (read > 0) {
                                onRingtoneImported(String(buffer, 0, read))
                                Toast.makeText(context, importedText, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, emptyText, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logger.e(e) { "Error importing ringtone" }
                    val errorMsg = importErrorPrefix.replace(IMPORT_ERROR_PLACEHOLDER, e.message ?: e.toString())
                    Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
                }
            }
        }

    Row {
        IconButton(onClick = { launcher.launch("*/*") }, enabled = enabled) {
            Icon(MeshtasticIcons.FolderOpen, contentDescription = stringResource(Res.string.import_label))
        }

        IconButton(
            onClick = {
                if (isPlaying) {
                    RtttlAudioPlayer.stop()
                } else {
                    val song = RtttlParser.parse(ringtoneInput)
                    if (song != null) {
                        RtttlAudioPlayer.play(RINGTONE_PLAYER_ID, song)
                    } else {
                        Toast.makeText(context, "Invalid RTTTL", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            enabled = enabled && ringtoneInput.isNotBlank(),
        ) {
            if (isPlaying) {
                Icon(MeshtasticIcons.Stop, contentDescription = stringResource(Res.string.stop))
            } else {
                Icon(MeshtasticIcons.PlayArrow, contentDescription = stringResource(Res.string.play))
            }
        }
    }
}
