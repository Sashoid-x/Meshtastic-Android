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
package org.meshtastic.core.model

/**
 * Metadata extracted from a web link for displaying a rich preview card.
 *
 * @property url The original web link.
 * @property title The page title extracted from OpenGraph or `<title>`.
 * @property description The page description extracted from OpenGraph or meta description.
 * @property imageUrl The preview thumbnail/banner image URL extracted from OpenGraph or twitter:image.
 * @property siteName The website name or host (e.g. "github.com", "Wikipedia").
 */
data class LinkPreview(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val siteName: String? = null,
)
