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
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single

@Serializable
data class JunkDataItem(
    @SerialName("code") val code: String,
    @SerialName("url") val url: String? = null,
    @SerialName("filename") val filename: String? = null,
    @SerialName("size") val size: Long? = null,
    @SerialName("expires_at") val expiresAt: Double? = null,
)

@Serializable
data class JunkDataUploadResponse(
    @SerialName("items") val items: List<JunkDataItem> = emptyList(),
    @SerialName("hours") val hours: Int? = null,
)

interface JunkDataService {
    suspend fun uploadImage(
        imageBytes: ByteArray,
        filename: String = "photo.jpg",
        retentionHours: Int = 24,
    ): Result<String>
}

@Single
class JunkDataServiceImpl(private val httpClient: HttpClient = HttpClient()) : JunkDataService {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val logger = Logger.withTag("JunkData")

    override suspend fun uploadImage(imageBytes: ByteArray, filename: String, retentionHours: Int): Result<String> =
        runCatching {
            logger.i { "Starting upload to junkdata.ru (${imageBytes.size} bytes)..." }

            val contentType = if (filename.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
            val response =
                httpClient.submitFormWithBinaryData(
                    url = JUNKDATA_UPLOAD_URL,
                    formData =
                    formData {
                        append(
                            key = "files",
                            value = imageBytes,
                            headers =
                            Headers.build {
                                append(HttpHeaders.ContentType, contentType)
                                append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                            },
                        )
                        append(key = "hours", value = retentionHours.toString())
                    },
                ) {
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    header(HttpHeaders.Origin, JUNKDATA_BASE_URL)
                    header(HttpHeaders.Referrer, "$JUNKDATA_BASE_URL/")
                }

            if (!response.status.isSuccess()) {
                val errorBody = runCatching { response.bodyAsText() }.getOrDefault("")
                logger.e { "Upload failed: HTTP ${response.status.value}: $errorBody" }
                error("HTTP ${response.status.value}: ${errorBody.take(MAX_ERROR_BODY_LENGTH)}")
            }

            val responseBody = response.bodyAsText()
            logger.i { "Upload response: $responseBody" }
            val uploadResponse = json.decodeFromString<JunkDataUploadResponse>(responseBody)
            val item = uploadResponse.items.firstOrNull() ?: error("No items returned from junkdata.ru")
            item.code
        }

    companion object {
        const val JUNKDATA_BASE_URL = "https://junkdata.ru"
        const val JUNKDATA_UPLOAD_URL = "https://junkdata.ru/api/upload"
        const val JUNKDATA_VIEW_URL_PREFIX = "https://junkdata.ru/v/"
        private const val MAX_ERROR_BODY_LENGTH = 120
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
