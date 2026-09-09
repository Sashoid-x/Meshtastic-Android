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
 * Result details returned after restoring a message backup.
 *
 * @property importedPackets Number of new packets inserted into the database.
 * @property skippedPackets Number of duplicate packets skipped because they already existed.
 * @property importedReactions Total number of reaction entries processed.
 * @property totalPackets Total number of packets contained in the backup file.
 */
data class MessageImportResult(
    val importedPackets: Int,
    val skippedPackets: Int,
    val importedReactions: Int,
    val totalPackets: Int,
)
