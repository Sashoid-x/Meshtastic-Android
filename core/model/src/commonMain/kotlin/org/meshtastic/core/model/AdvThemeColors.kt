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

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Seed colors and configuration for the customizable ADV theme.
 *
 * Any color set to null represents the "Auto" mode, which will be algorithmically derived from the primary seed or
 * default brand palette.
 */
@Serializable
data class AdvThemeColors(
    val primaryArgb: Int? = null,
    val secondaryArgb: Int? = null,
    val tertiaryArgb: Int? = null,
    val baseArgb: Int? = null,
    val darkBase: Boolean = false,
) {
    fun toJson(): String = json.encodeToString(this)

    companion object {
        val DEFAULT = AdvThemeColors()
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(rawJson: String): AdvThemeColors = if (rawJson.isBlank()) {
            DEFAULT
        } else {
            try {
                json.decodeFromString<AdvThemeColors>(rawJson)
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                DEFAULT
            }
        }
    }
}
