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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JunkDataServiceTest {

    @Test
    fun `successful upload returns short id`() = runTest {
        val engine = MockEngine { request ->
            when (request.url.toString()) {
                JunkDataServiceImpl.JUNKDATA_UPLOAD_URL -> {
                    respond(
                        content =
                        """
                            {
                                "items": [
                                    {
                                        "code": "ty8tjhs",
                                        "url": "/i/ty8tjhs",
                                        "filename": "test.jpg",
                                        "size": 149,
                                        "expires_at": 1790174863.8
                                    }
                                ],
                                "hours": 24
                            }
                            """
                            .trimIndent(),
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }

                else -> error("Unexpected URL: ${request.url}")
            }
        }

        val client = HttpClient(engine)
        val service = JunkDataServiceImpl(client)
        val result = service.uploadImage(byteArrayOf(1, 2, 3), "photo.jpg", 24)

        assertTrue(result.isSuccess)
        assertEquals("ty8tjhs", result.getOrNull())
    }

    @Test
    fun `failed upload returns failure result`() = runTest {
        val engine = MockEngine { request ->
            when (request.url.toString()) {
                JunkDataServiceImpl.JUNKDATA_UPLOAD_URL -> {
                    respond(content = "Internal Server Error", status = HttpStatusCode.InternalServerError)
                }

                else -> error("Unexpected URL: ${request.url}")
            }
        }

        val client = HttpClient(engine)
        val service = JunkDataServiceImpl(client)
        val result = service.uploadImage(byteArrayOf(1, 2, 3), "photo.jpg", 24)

        assertTrue(result.isFailure)
    }
}
