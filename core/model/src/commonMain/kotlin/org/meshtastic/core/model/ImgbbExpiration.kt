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
@file:Suppress("MagicNumber")

package org.meshtastic.core.model

/**
 * Storage duration options for ImgBB photo hosting.
 *
 * @param id Stable identifier for persistence.
 * @param seconds Expiration time in seconds sent to the ImgBB API (60..15552000).
 */
enum class ImgbbExpiration(val id: String, val seconds: Int) {
    MINUTES_30("30m", 1800),
    HOURS_1("1h", 3600),
    HOURS_6("6h", 21600),
    HOURS_12("12h", 43200),
    DAYS_1("1d", 86400),
    DAYS_3("3d", 259200),
    DAYS_7("7d", 604800),
    DAYS_14("14d", 1209600),
    DAYS_30("30d", 2592000),
    ;

    companion object {
        fun fromId(id: String?): ImgbbExpiration =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DAYS_30
    }
}
