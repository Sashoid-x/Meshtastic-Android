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
package org.meshtastic.core.domain.usecase.settings

import okio.BufferedSource
import org.koin.core.annotation.Single
import org.meshtastic.core.model.MessageImportResult
import org.meshtastic.core.repository.PacketRepository

/**
 * Use case to import messages, reactions, and conversation settings from a JSON backup, deduplicating existing messages
 * and rebuilding the FTS search index.
 */
@Single
open class ImportMessagesUseCase(private val packetRepository: PacketRepository) {
    open suspend operator fun invoke(source: BufferedSource): MessageImportResult =
        packetRepository.importMessagesFromJson(source)
}
