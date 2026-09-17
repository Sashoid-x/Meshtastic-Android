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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single
import org.meshtastic.core.model.ImgbbExpiration

@Serializable
data class ImgBBUploadResponse(
    @SerialName("data") val data: ImgBBData,
    @SerialName("success") val success: Boolean,
    @SerialName("status") val status: Int,
)

@Serializable
data class ImgBBData(
    @SerialName("id") val id: String,
    @SerialName("url") val url: String? = null,
    @SerialName("display_url") val displayUrl: String? = null,
    @SerialName("url_viewer") val urlViewer: String? = null,
    @SerialName("delete_url") val deleteUrl: String? = null,
)

@Serializable
data class ImgBBApiError(
    @SerialName("status_code") val statusCode: Int? = null,
    @SerialName("error") val error: ImgBBApiErrorDetail? = null,
    @SerialName("status_txt") val statusTxt: String? = null,
)

@Serializable
data class ImgBBApiErrorDetail(
    @SerialName("message") val message: String? = null,
    @SerialName("code") val code: Int? = null,
)

class ImgbbApiKeyMissingException(message: String = "ImgBB API key is not configured") : Exception(message)

class ImgbbInvalidApiKeyException(message: String = "Invalid ImgBB API key") : Exception(message)

interface ImgBBService {
    suspend fun uploadImage(
        imageBytes: ByteArray,
        apiKey: String,
        expirationSeconds: Int? = null,
        filename: String = "photo.jpg",
    ): Result<String>

    suspend fun uploadImage(
        imageBytes: ByteArray,
        apiKey: String,
        expiration: ImgbbExpiration,
        filename: String = "photo.jpg",
    ): Result<String> = uploadImage(imageBytes, apiKey, expiration.seconds, filename)
}

@Single
class ImgBBServiceImpl(private val httpClient: HttpClient = HttpClient()) : ImgBBService {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val logger = Logger.withTag("ImgBB")

    override suspend fun uploadImage(
        imageBytes: ByteArray,
        apiKey: String,
        expirationSeconds: Int?,
        filename: String,
    ): Result<String> = runCatching {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            throw ImgbbApiKeyMissingException()
        }

        logger.i { "Starting upload to api.imgbb.com (${imageBytes.size} bytes)..." }

        val contentType = if (filename.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"

        val uploadUrl = buildString {
            append(IMGBB_UPLOAD_URL)
            append("?key=")
            append(trimmedKey)
            if (expirationSeconds != null && expirationSeconds > 0) {
                append("&expiration=")
                append(expirationSeconds)
            }
        }

        val response =
            httpClient.submitFormWithBinaryData(
                url = uploadUrl,
                formData =
                formData {
                    append(
                        key = "image",
                        value = imageBytes,
                        headers =
                        Headers.build {
                            append(HttpHeaders.ContentType, contentType)
                            append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                        },
                    )
                },
            )

        val responseBody = response.bodyAsText()

        if (!response.status.isSuccess()) {
            logger.e { "Upload failed: HTTP ${response.status.value}: $responseBody" }
            val errorResponse = runCatching { json.decodeFromString<ImgBBApiError>(responseBody) }.getOrNull()
            if (
                errorResponse?.error?.code == ERROR_CODE_INVALID_KEY ||
                errorResponse?.error?.message?.contains("key", ignoreCase = true) == true
            ) {
                throw ImgbbInvalidApiKeyException(errorResponse.error.message ?: "Invalid API key")
            }
            error(
                "HTTP ${response.status.value}: ${errorResponse?.error?.message ?: responseBody.take(
                    MAX_ERROR_BODY_LENGTH,
                )}",
            )
        }

        val uploadResponse = json.decodeFromString<ImgBBUploadResponse>(responseBody)
        uploadResponse.data.urlViewer
            ?: uploadResponse.data.displayUrl
            ?: uploadResponse.data.url
            ?: error("No URL returned from ImgBB")
    }

    companion object {
        const val IMGBB_UPLOAD_URL = "https://api.imgbb.com/1/upload"
        private const val MAX_ERROR_BODY_LENGTH = 120
        private const val ERROR_CODE_INVALID_KEY = 100
    }
}
