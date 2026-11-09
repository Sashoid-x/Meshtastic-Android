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
package org.meshtastic.feature.messaging.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.common.util.RtttlSong
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.play
import org.meshtastic.core.resources.stop
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.MusicNote
import org.meshtastic.core.ui.icon.PlayArrow
import org.meshtastic.core.ui.icon.Stop
import org.meshtastic.core.ui.sound.RtttlAudioPlayer

private const val CARD_CORNER_RADIUS_DP = 12
private const val CARD_MIN_WIDTH_DP = 220
private const val CARD_MAX_WIDTH_DP = 280

/** Compact in-chat audio player for RTTTL melodies. */
@Composable
fun RtttlPlayerCard(messageId: String, song: RtttlSong, modifier: Modifier = Modifier) {
    val playingId by RtttlAudioPlayer.playingId.collectAsState()
    val progress by RtttlAudioPlayer.progress.collectAsState()
    val isPlaying = playingId == messageId

    val shape = RoundedCornerShape(CARD_CORNER_RADIUS_DP.dp)

    Column(
        modifier =
        modifier
            .widthIn(min = CARD_MIN_WIDTH_DP.dp, max = CARD_MAX_WIDTH_DP.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = shape,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = MeshtasticIcons.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${song.formattedDuration} • ${song.bpm} BPM",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            FilledIconButton(
                onClick = {
                    if (isPlaying) {
                        RtttlAudioPlayer.stop()
                    } else {
                        RtttlAudioPlayer.play(messageId, song)
                    }
                },
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                colors =
                IconButtonDefaults.filledIconButtonColors(
                    containerColor =
                    if (isPlaying) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    contentColor =
                    if (isPlaying) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                ),
            ) {
                if (isPlaying) {
                    Icon(
                        imageVector = MeshtasticIcons.Stop,
                        contentDescription = stringResource(Res.string.stop),
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Icon(
                        imageVector = MeshtasticIcons.PlayArrow,
                        contentDescription = stringResource(Res.string.play),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { if (isPlaying) progress else 0f },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}
