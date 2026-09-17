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
 * Storage duration options for Meshpic photo hosting.
 *
 * @param id Stable identifier for persistence.
 * @param hours Retention time in hours accepted by meshpic.org.
 */
enum class MeshpicRetention(val id: String, val hours: Int) {
    HOURS_1("1h", 1),
    HOURS_6("6h", 6),
    DAYS_1("1d", 24),
    DAYS_3("3d", 72),
    DAYS_7("7d", 168),
    ;

    companion object {
        fun fromId(id: String?): MeshpicRetention =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DAYS_1
    }
}
