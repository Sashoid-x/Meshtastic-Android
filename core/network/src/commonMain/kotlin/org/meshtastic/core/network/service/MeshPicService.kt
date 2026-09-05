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
import io.ktor.client.request.get
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
data class MeshPicUploadResponse(
    @SerialName("short_id") val shortId: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("view_count") val viewCount: Int? = null,
)

interface MeshPicService {
    suspend fun uploadImage(imageBytes: ByteArray, filename: String = "photo.jpg"): Result<String>
}

@Single
class MeshPicServiceImpl(private val httpClient: HttpClient = HttpClient()) : MeshPicService {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val logger = Logger.withTag("MeshPic")

    override suspend fun uploadImage(imageBytes: ByteArray, filename: String): Result<String> = runCatching {
        logger.i { "Starting upload to meshpic.org (${imageBytes.size} bytes)..." }

        // Step 1: Pre-flight handshake to get session cookie and CSRF token
        var sessionCookie: String? = null
        var csrfToken: String? = null

        runCatching {
            val getResponse = httpClient.get(MESHPIC_BASE_URL) { header(HttpHeaders.UserAgent, USER_AGENT) }
            val rawCookie = getResponse.headers[HttpHeaders.SetCookie]
            sessionCookie = rawCookie?.substringBefore(';')
            val html = getResponse.bodyAsText()
            val match = CSRF_REGEX.find(html)
            csrfToken = match?.groupValues?.getOrNull(1)
        }
            .onFailure { e ->
                logger.w(e) { "Could not pre-fetch CSRF token from meshpic.org, attempting direct upload" }
            }

        // Step 2: Upload image via multipart/form-data
        val contentType = if (filename.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
        val response =
            httpClient.submitFormWithBinaryData(
                url = MESHPIC_UPLOAD_URL,
                formData =
                formData {
                    append(
                        key = "file",
                        value = imageBytes,
                        headers =
                        Headers.build {
                            append(HttpHeaders.ContentType, contentType)
                            append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                        },
                    )
                },
            ) {
                if (!sessionCookie.isNullOrBlank()) {
                    header(HttpHeaders.Cookie, sessionCookie)
                }
                if (!csrfToken.isNullOrBlank()) {
                    header("X-CSRF-Token", csrfToken)
                }
                header("X-Meshpic-UI", "1")
                header(HttpHeaders.Origin, MESHPIC_BASE_URL)
                header("Referer", "$MESHPIC_BASE_URL/")
                header(HttpHeaders.UserAgent, USER_AGENT)
                header("Sec-Fetch-Site", "same-origin")
                header("Sec-Fetch-Mode", "cors")
                header("Sec-Fetch-Dest", "empty")
            }

        if (!response.status.isSuccess()) {
            val errorBody = runCatching { response.bodyAsText() }.getOrDefault("")
            logger.e { "Upload failed: HTTP ${response.status.value}: $errorBody" }
            error("HTTP ${response.status.value}: ${errorBody.take(MAX_ERROR_BODY_LENGTH)}")
        }

        val responseBody = response.bodyAsText()
        logger.i { "Upload response: $responseBody" }
        val uploadResponse = json.decodeFromString<MeshPicUploadResponse>(responseBody)
        uploadResponse.shortId
    }

    companion object {
        const val MESHPIC_BASE_URL = "https://meshpic.org"
        const val MESHPIC_UPLOAD_URL = "https://meshpic.org/upload"
        const val MESHPIC_IMAGE_URL_PREFIX = "https://meshpic.org/image/"
        private const val MAX_ERROR_BODY_LENGTH = 120
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private val CSRF_REGEX = Regex("""name="csrf-token"\s+content="([^"]+)"""")
    }
}
