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

class MeshFilesServiceTest {

    @Test
    fun `successful upload returns url`() = runTest {
        val engine = MockEngine { request ->
            assertEquals(MeshFilesServiceImpl.API_URL, request.url.toString())
            assertEquals("application/json", request.headers[HttpHeaders.Accept])
            assertEquals(MeshFilesServiceImpl.API_KEY, request.headers["X-API-Key"])

            respond(
                content =
                """
                    {
                        "id": "e9p8h8j7-1772-7359-5485-614349479b1d",
                        "url": "https://d.privatepractice.app/e9p8h8j7-1772-7359-5485-614349479b1d",
                        "previewUrl": "https://d.privatepractice.app/e9p8h8j7-1772-7359-5485-614349479b1d/preview",
                        "expiresAt": "2026-03-08T15:51:02.000Z"
                    }
                    """
                    .trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val client = HttpClient(engine)
        val service = MeshFilesServiceImpl(client)
        val result = service.uploadImage(byteArrayOf(1, 2, 3), "photo.jpg")

        assertTrue(result.isSuccess)
        assertEquals("https://d.privatepractice.app/e9p8h8j7-1772-7359-5485-614349479b1d", result.getOrNull())
    }

    @Test
    fun `successful upload falls back to generated url if url field is missing`() = runTest {
        val engine = MockEngine { request ->
            respond(
                content = """{"id": "abc-123"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val client = HttpClient(engine)
        val service = MeshFilesServiceImpl(client)
        val result = service.uploadImage(byteArrayOf(1, 2, 3), "photo.jpg")

        assertTrue(result.isSuccess)
        assertEquals("https://d.privatepractice.app/abc-123", result.getOrNull())
    }

    @Test
    fun `failed upload returns failure result`() = runTest {
        val engine = MockEngine { _ ->
            respond(content = "Internal Server Error", status = HttpStatusCode.InternalServerError)
        }

        val client = HttpClient(engine)
        val service = MeshFilesServiceImpl(client)
        val result = service.uploadImage(byteArrayOf(1, 2, 3), "photo.jpg")

        assertTrue(result.isFailure)
    }
}
