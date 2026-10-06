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
package org.meshtastic.core.network.service

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable

/**
 * Reads up to [maxBytes] from the response body channel into a string, preventing unbounded memory allocation and OOM
 * vulnerabilities when processing untrusted remote endpoints.
 */
suspend fun HttpResponse.readBoundedText(maxBytes: Int = 32_768): String {
    val channel = bodyAsChannel()
    val buffer = ByteArray(maxBytes)
    var totalRead = 0
    while (totalRead < maxBytes && !channel.isClosedForRead) {
        val read = channel.readAvailable(buffer, totalRead, maxBytes - totalRead)
        if (read <= 0) break
        totalRead += read
    }
    return buffer.decodeToString(0, totalRead)
}
