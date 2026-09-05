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

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single

@Serializable
data class MeshFilesUploadResponse(
    @SerialName("id") val id: String,
    @SerialName("url") val url: String? = null,
    @SerialName("previewUrl") val previewUrl: String? = null,
    @SerialName("expiresAt") val expiresAt: String? = null,
)

interface MeshFilesService {
    suspend fun uploadImage(imageBytes: ByteArray, filename: String = "photo.jpg"): Result<String>
}

@Single
class MeshFilesServiceImpl(private val httpClient: HttpClient = HttpClient()) : MeshFilesService {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val logger = Logger.withTag("MeshFiles")

    override suspend fun uploadImage(imageBytes: ByteArray, filename: String): Result<String> = runCatching {
        logger.i { "Starting upload to MeshFiles ($API_URL, ${imageBytes.size} bytes)..." }

        val contentType =
            if (filename.endsWith(".png", ignoreCase = true)) {
                ContentType.Image.PNG
            } else {
                ContentType.Image.JPEG
            }

        val response =
            httpClient.post(API_URL) {
                header(HttpHeaders.Accept, "application/json")
                header("X-API-Key", API_KEY)
                header(HttpHeaders.UserAgent, USER_AGENT)
                contentType(contentType)
                setBody(imageBytes)
            }

        if (!response.status.isSuccess()) {
            val errorBody = runCatching { response.bodyAsText() }.getOrDefault("")
            logger.e { "Upload failed: HTTP ${response.status.value}: $errorBody" }
            error("HTTP ${response.status.value}: ${errorBody.take(MAX_ERROR_BODY_LENGTH)}")
        }

        val responseBody = response.bodyAsText()
        logger.i { "Upload response: $responseBody" }
        val uploadResponse = json.decodeFromString<MeshFilesUploadResponse>(responseBody)
        uploadResponse.url ?: "$BASE_URL/${uploadResponse.id}"
    }

    companion object {
        const val BASE_URL = "https://d.privatepractice.app"
        const val API_URL = "https://d.privatepractice.app/api/files"
        const val API_KEY = "17386E82-EC3E-4635-9BBA-B049699413F2"
        private const val MAX_ERROR_BODY_LENGTH = 120
        private const val USER_AGENT = "MeshApp MeshFiles Upload"
    }
}
