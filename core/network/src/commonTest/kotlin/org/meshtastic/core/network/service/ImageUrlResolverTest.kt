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
import kotlin.test.assertNull

class ImageUrlResolverTest {

    @Test
    fun testExtractFirstUrl() {
        assertEquals("https://meshpic.org/xev", ImageUrlResolver.extractFirstUrl("Check https://meshpic.org/xev!"))
        assertEquals(
            "https://example.com/cat.jpg",
            ImageUrlResolver.extractFirstUrl("Photo: https://example.com/cat.jpg."),
        )
        assertEquals("https://site.org/path", ImageUrlResolver.extractFirstUrl("(https://site.org/path)"))
        assertNull(ImageUrlResolver.extractFirstUrl("No links here"))
    }

    @Test
    fun testMeshpicFastPathNormalization() {
        assertEquals("https://meshpic.org/image/xev", ImageUrlResolver.getFastPathImageUrl("https://meshpic.org/xev"))
        assertEquals("https://meshpic.org/image/xev", ImageUrlResolver.getFastPathImageUrl("http://meshpic.org/xev"))
        assertEquals(
            "https://meshpic.org/image/xev",
            ImageUrlResolver.getFastPathImageUrl("https://meshpic.org/image/xev"),
        )
        assertEquals("https://meshpic.org/image/xev", ImageUrlResolver.getFastPathImageUrl("https://meshpic.org/i/xev"))
        assertEquals(
            "https://meshpic.org/image/xev",
            ImageUrlResolver.getFastPathImageUrl("https://www.meshpic.org/xev/"),
        )
        // Excluded keywords should not match fast-path
        assertNull(ImageUrlResolver.getFastPathImageUrl("https://meshpic.org/upload"))
    }

    @Test
    fun testMeshfilesFastPathNormalization() {
        assertEquals(
            "https://d.privatepractice.app/w5N85eSw",
            ImageUrlResolver.getFastPathImageUrl("https://d.privatepractice.app/w5N85eSw"),
        )
        assertEquals(
            "https://d.privatepractice.app/w5N85eSw",
            ImageUrlResolver.getFastPathImageUrl("https://d.privatepractice.app/w5N85eSw/preview"),
        )
        assertEquals(
            "https://d.privatepractice.app/w5N85eSw",
            ImageUrlResolver.getFastPathImageUrl("http://d.privatepractice.app/w5N85eSw/"),
        )
        assertNull(ImageUrlResolver.getFastPathImageUrl("https://d.privatepractice.app/api"))
    }

    @Test
    fun testKnownExtensionsFastPath() {
        assertEquals("https://example.com/pic.png", ImageUrlResolver.getFastPathImageUrl("https://example.com/pic.png"))
        assertEquals(
            "https://example.com/photo.JPG?w=500",
            ImageUrlResolver.getFastPathImageUrl("https://example.com/photo.JPG?w=500"),
        )
        assertNull(ImageUrlResolver.getFastPathImageUrl("https://example.com/page"))
    }

    @Test
    fun testResolveByContentTypeImage() = runTest {
        val testUrl = "https://cdn.example.com/blob/12345"
        val engine = MockEngine { request ->
            when (request.url.toString()) {
                testUrl -> {
                    respond(
                        content = byteArrayOf(1, 2, 3),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "image/webp"),
                    )
                }

                else -> error("Unexpected: ${request.url}")
            }
        }
        val client = HttpClient(engine)
        val result = ImageUrlResolver.resolveImageUrl(testUrl, client)
        assertEquals(testUrl, result)
    }

    @Test
    fun testResolveByOgImageHtml() = runTest {
        val pageUrl = "https://gallery.example.com/view/999"
        val imageUrl = "https://gallery.example.com/static/photo.jpg"
        val engine = MockEngine { request ->
            when (request.url.toString()) {
                pageUrl -> {
                    respond(
                        content =
                        """
                            <html>
                            <head>
                                <title>Gallery</title>
                                <meta property="og:image" content="$imageUrl">
                            </head>
                            <body></body>
                            </html>
                        """
                            .trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
                    )
                }

                else -> error("Unexpected: ${request.url}")
            }
        }
        val client = HttpClient(engine)
        val result = ImageUrlResolver.resolveImageUrl(pageUrl, client)
        assertEquals(imageUrl, result)
    }

    @Test
    fun testResolveNonImageReturnsNull() = runTest {
        val pageUrl = "https://docs.example.com/readme"
        val engine = MockEngine { request ->
            when (request.url.toString()) {
                pageUrl -> {
                    respond(
                        content = "<html><head><title>Docs</title></head><body>Hello</body></html>",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
                    )
                }

                else -> error("Unexpected: ${request.url}")
            }
        }
        val client = HttpClient(engine)
        val result = ImageUrlResolver.resolveImageUrl(pageUrl, client)
        assertNull(result)
    }
}
