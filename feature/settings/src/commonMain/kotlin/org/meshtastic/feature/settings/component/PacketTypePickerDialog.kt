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
package org.meshtastic.feature.settings.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.model.BackupPacketType
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.adv_backup_type_contact_settings
import org.meshtastic.core.resources.adv_backup_type_messages
import org.meshtastic.core.resources.adv_backup_type_reactions
import org.meshtastic.core.resources.adv_backup_type_select_all
import org.meshtastic.core.resources.adv_backup_type_waypoints
import org.meshtastic.core.resources.adv_backup_types_title
import org.meshtastic.core.resources.cancel
import org.meshtastic.core.ui.component.MeshtasticDialog

@Suppress("LongMethod")
@Composable
fun PacketTypePickerDialog(
    confirmText: String,
    onConfirm: (Set<BackupPacketType>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(Res.string.adv_backup_types_title),
) {
    var selectedTypes by remember { mutableStateOf(BackupPacketType.entries.toSet()) }
    val allSelected = selectedTypes.size == BackupPacketType.entries.size

    MeshtasticDialog(
        modifier = modifier,
        title = title,
        onDismiss = onDismiss,
        confirmText = confirmText,
        onConfirm = {
            if (selectedTypes.isNotEmpty()) {
                onConfirm(selectedTypes)
            }
        },
        dismissTextRes = Res.string.cancel,
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier =
                    Modifier.fillMaxWidth()
                        .clickable {
                            selectedTypes =
                                if (allSelected) {
                                    emptySet()
                                } else {
                                    BackupPacketType.entries.toSet()
                                }
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = { checked ->
                            selectedTypes =
                                if (checked) {
                                    BackupPacketType.entries.toSet()
                                } else {
                                    emptySet()
                                }
                        },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.adv_backup_type_select_all),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                BackupPacketType.entries.forEach { type ->
                    val isChecked = type in selectedTypes
                    val labelRes =
                        when (type) {
                            BackupPacketType.MESSAGES -> Res.string.adv_backup_type_messages
                            BackupPacketType.REACTIONS -> Res.string.adv_backup_type_reactions
                            BackupPacketType.WAYPOINTS -> Res.string.adv_backup_type_waypoints
                            BackupPacketType.CONTACT_SETTINGS -> Res.string.adv_backup_type_contact_settings
                        }
                    Row(
                        modifier =
                        Modifier.fillMaxWidth()
                            .clickable {
                                selectedTypes =
                                    if (isChecked) {
                                        selectedTypes - type
                                    } else {
                                        selectedTypes + type
                                    }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                selectedTypes =
                                    if (checked) {
                                        selectedTypes + type
                                    } else {
                                        selectedTypes - type
                                    }
                            },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
    )
}
