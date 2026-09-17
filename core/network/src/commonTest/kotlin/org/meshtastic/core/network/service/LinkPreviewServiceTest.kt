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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinkPreviewServiceTest {

    @Test
    fun `parseHtml extracts opengraph metadata and unescapes html`() {
        val service = LinkPreviewServiceImpl()
        val html =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:site_name" content="GitHub" />
                <meta property="og:title" content="meshtastic/Meshtastic-Android &amp; Friends" />
                <meta property="og:description" content="Android app for &quot;Meshtastic&quot; device communication." />
                <meta property="og:image" content="https://opengraph.githubassets.com/123/banner.png" />
            </head>
            <body></body>
            </html>
            """
                .trimIndent()

        val preview = service.parseHtml("https://github.com/meshtastic/Meshtastic-Android", html)
        assertNotNull(preview)
        assertEquals("https://github.com/meshtastic/Meshtastic-Android", preview.url)
        assertEquals("meshtastic/Meshtastic-Android & Friends", preview.title)
        assertEquals("Android app for \"Meshtastic\" device communication.", preview.description)
        assertEquals("https://opengraph.githubassets.com/123/banner.png", preview.imageUrl)
        assertEquals("GitHub", preview.siteName)
    }

    @Test
    fun `parseHtml falls back to standard html title and description with host siteName`() {
        val service = LinkPreviewServiceImpl()
        val html =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Wikipedia, the free encyclopedia</title>
                <meta name="description" content="Wikipedia is a free online encyclopedia." />
            </head>
            <body></body>
            </html>
            """
                .trimIndent()

        val preview = service.parseHtml("https://www.wikipedia.org/wiki/Main_Page", html)
        assertNotNull(preview)
        assertEquals("Wikipedia, the free encyclopedia", preview.title)
        assertEquals("Wikipedia is a free online encyclopedia.", preview.description)
        assertNull(preview.imageUrl)
        assertEquals("wikipedia.org", preview.siteName)
    }

    @Test
    fun `parseHtml resolves relative image URL`() {
        val service = LinkPreviewServiceImpl()
        val html =
            """
            <html>
            <head>
                <meta property="og:title" content="Test Page" />
                <meta property="og:image" content="/assets/logo.png" />
            </head>
            </html>
            """
                .trimIndent()

        val preview = service.parseHtml("https://example.com/blog/article", html)
        assertNotNull(preview)
        assertEquals("https://example.com/assets/logo.png", preview.imageUrl)
    }

    @Test
    fun `parseHtml returns null when no title description or image found`() {
        val service = LinkPreviewServiceImpl()
        val html = "<html><head></head><body>No metadata here</body></html>"
        val preview = service.parseHtml("https://example.com/empty", html)
        assertNull(preview)
    }

    @Test
    fun `getLinkPreview fetches and caches html preview`() = runTest {
        val engine = MockEngine {
            respond(
                content =
                """
                    <html>
                    <head>
                        <meta property="og:title" content="Mock Page" />
                    </head>
                    </html>
                    """
                    .trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
            )
        }
        val service = LinkPreviewServiceImpl(HttpClient(engine))
        val preview = service.getLinkPreview("https://mock.test/page")
        assertNotNull(preview)
        assertEquals("Mock Page", preview.title)
        assertEquals("mock.test", preview.siteName)

        // Cached call
        val cached = service.getLinkPreview("https://mock.test/page")
        assertNotNull(cached)
        assertEquals("Mock Page", cached.title)
    }

    @Test
    fun `getLinkPreview ignores non-html content types`() = runTest {
        val engine = MockEngine {
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val service = LinkPreviewServiceImpl(HttpClient(engine))
        val preview = service.getLinkPreview("https://mock.test/api")
        assertNull(preview)
    }
}
