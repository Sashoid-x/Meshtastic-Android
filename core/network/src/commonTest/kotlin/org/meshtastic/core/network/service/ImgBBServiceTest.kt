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

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.meshtastic.core.model.ImgbbExpiration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImgBBServiceTest {

    @Test
    fun `empty api key returns failure with ImgbbApiKeyMissingException`() = runTest {
        val service = ImgBBServiceImpl()
        val result = service.uploadImage(byteArrayOf(1, 2, 3), apiKey = "   ", expiration = ImgbbExpiration.DAYS_1)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ImgbbApiKeyMissingException)
    }

    @Test
    fun `successful upload returns viewer url`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("test_key", request.url.parameters["key"])
            assertEquals("86400", request.url.parameters["expiration"])
            respond(
                content =
                """
                    {
                        "data": {
                            "id": "2ndCYJK",
                            "title": "c1f64245b6e0",
                            "url_viewer": "https://ibb.co/2ndCYJK",
                            "url": "https://i.ibb.co/2ndCYJK/c1f64245b6e0.jpg",
                            "display_url": "https://i.ibb.co/2ndCYJK/c1f64245b6e0.jpg",
                            "width": "1",
                            "height": "1",
                            "size": "42",
                            "time": "1552042869",
                            "expiration": "86400",
                            "image": {
                                "filename": "c1f64245b6e0.jpg",
                                "name": "c1f64245b6e0",
                                "mime": "image/jpeg",
                                "extension": "jpg",
                                "url": "https://i.ibb.co/2ndCYJK/c1f64245b6e0.jpg"
                            },
                            "delete_url": "https://ibb.co/2ndCYJK/681737895b03b168805767e2661c468b"
                        },
                        "success": true,
                        "status": 200
                    }
                    """
                    .trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val client = HttpClient(engine)
        val service = ImgBBServiceImpl(client)
        val result = service.uploadImage(byteArrayOf(1, 2, 3), apiKey = "test_key", expiration = ImgbbExpiration.DAYS_1)

        assertTrue(result.isSuccess)
        assertEquals("https://i.ibb.co/2ndCYJK/c1f64245b6e0.jpg", result.getOrNull())
    }

    @Test
    fun `invalid api key returns failure with ImgbbInvalidApiKeyException`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content =
                """
                    {
                        "status_code": 400,
                        "error": {
                            "message": "Invalid API v1 key.",
                            "code": 100
                        },
                        "status_txt": "Bad Request"
                    }
                    """
                    .trimIndent(),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val client = HttpClient(engine)
        val service = ImgBBServiceImpl(client)
        val result =
            service.uploadImage(byteArrayOf(1, 2, 3), apiKey = "bad_key", expiration = ImgbbExpiration.MINUTES_30)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ImgbbInvalidApiKeyException)
    }

    @Test
    fun `generic network or server error returns failure`() = runTest {
        val engine = MockEngine { _ ->
            respond(content = "Internal Server Error", status = HttpStatusCode.InternalServerError)
        }

        val client = HttpClient(engine)
        val service = ImgBBServiceImpl(client)
        val result = service.uploadImage(byteArrayOf(1, 2, 3), apiKey = "test_key", expiration = ImgbbExpiration.DAYS_1)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() !is ImgbbInvalidApiKeyException)
        assertTrue(result.exceptionOrNull() !is ImgbbApiKeyMissingException)
    }
}
