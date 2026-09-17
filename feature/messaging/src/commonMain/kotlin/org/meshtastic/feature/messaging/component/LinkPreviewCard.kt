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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import org.meshtastic.core.model.LinkPreview
import org.meshtastic.core.ui.icon.Link
import org.meshtastic.core.ui.icon.MeshtasticIcons

private const val CARD_CORNER_RADIUS_DP = 10
private const val BANNER_HEIGHT_DP = 120
private const val CARD_WIDTH_DP = 256

/**
 * Rich link preview card displaying open graph metadata for general web links.
 *
 * Tapping opens the link via [LocalUriHandler].
 */
@Composable
fun LinkPreviewCard(preview: LinkPreview, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val shape = RoundedCornerShape(CARD_CORNER_RADIUS_DP.dp)

    Column(
        modifier =
        modifier
            .width(CARD_WIDTH_DP.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                shape = shape,
            )
            .clickable { uriHandler.openUri(preview.url) },
    ) {
        val bannerUrl = preview.imageUrl
        if (!bannerUrl.isNullOrBlank()) {
            LinkPreviewBanner(imageUrl = bannerUrl, title = preview.title)
        }
        LinkPreviewDetails(preview = preview)
    }
}

@Composable
private fun LinkPreviewBanner(imageUrl: String, title: String?) {
    SubcomposeAsyncImage(
        model = imageUrl,
        contentDescription = title,
        modifier =
        Modifier.fillMaxWidth()
            .height(BANNER_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(topStart = CARD_CORNER_RADIUS_DP.dp, topEnd = CARD_CORNER_RADIUS_DP.dp)),
        contentScale = ContentScale.Crop,
        loading = {
            Box(modifier = Modifier.fillMaxWidth().height(BANNER_HEIGHT_DP.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        },
        error = null,
    )
}

@Composable
private fun LinkPreviewDetails(preview: LinkPreview) {
    Column(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val site = preview.siteName
        if (!site.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    imageVector = MeshtasticIcons.Link,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = site,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val title = preview.title
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val desc = preview.description
        if (!desc.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
