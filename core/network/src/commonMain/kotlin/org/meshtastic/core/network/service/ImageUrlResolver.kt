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
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update

object ImageUrlResolver {
    private val logger = Logger.withTag("ImageUrlResolver")
    private val cache = atomic(mapOf<String, String?>())
    private const val MAX_CACHE_SIZE = 500
    private const val EVICTION_COUNT = 50

    private val KNOWN_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg", "avif")
    private val MESHPIC_REGEX =
        Regex("""https?://(?:www\.)?meshpic\.org/(?:image/|i/)?([A-Za-z0-9_-]+)/?""", RegexOption.IGNORE_CASE)
    private val MESHFILES_REGEX =
        Regex(
            """https?://(?:www\.)?d\.privatepractice\.app/([A-Za-z0-9_-]{6,64})(?:/preview)?/?""",
            RegexOption.IGNORE_CASE,
        )
    private val GENERAL_URL_REGEX = Regex("""https?://[^\s<>"']+[^\s<>"'.,;:!?)]""", RegexOption.IGNORE_CASE)
    private val OG_IMAGE_REGEX =
        Regex(
            """<meta\s+[^>]*?(?:property|name)=["'](?:og:image|twitter:image)["'][^>]*?content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
    private val OG_IMAGE_REVERSE_REGEX =
        Regex(
            """<meta\s+[^>]*?content=["']([^"']+)["'][^>]*?(?:property|name)=["'](?:og:image|twitter:image)["']""",
            RegexOption.IGNORE_CASE,
        )
    private val EXCLUDED_KEYWORDS = setOf("upload", "static", "privacy", "terms", "favicon", "about", "api")
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val sharedHttpClient by lazy { HttpClient() }

    fun extractFirstUrl(text: String): String? {
        val match = GENERAL_URL_REGEX.find(text) ?: return null
        return match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'', '>')
    }

    @Suppress("ReturnCount")
    fun getFastPathImageUrl(url: String): String? {
        cache.value[url]?.let {
            return it
        }

        val meshpicMatch = MESHPIC_REGEX.matchEntire(url)
        if (meshpicMatch != null) {
            val id = meshpicMatch.groupValues[1]
            if (id.lowercase() !in EXCLUDED_KEYWORDS) {
                val resolved = "https://meshpic.org/image/$id"
                putInCache(url, resolved)
                return resolved
            }
        }

        val meshfilesMatch = MESHFILES_REGEX.matchEntire(url)
        if (meshfilesMatch != null) {
            val id = meshfilesMatch.groupValues[1]
            if (id.lowercase() !in EXCLUDED_KEYWORDS) {
                val resolved = "https://d.privatepractice.app/$id"
                putInCache(url, resolved)
                return resolved
            }
        }
        val cleanUrl = url.substringBefore('?').substringBefore('#')
        val ext = cleanUrl.substringAfterLast('.', "").lowercase()
        if (ext in KNOWN_IMAGE_EXTENSIONS) {
            putInCache(url, url)
            return url
        }
        return null
    }

    @Suppress("ReturnCount")
    suspend fun resolveImageUrl(url: String, httpClient: HttpClient = sharedHttpClient): String? {
        getFastPathImageUrl(url)?.let {
            return it
        }
        if (cache.value.containsKey(url)) return cache.value[url]

        return try {
            val headResponse =
                runCatching { httpClient.head(url) { header(HttpHeaders.UserAgent, USER_AGENT) } }.getOrNull()

            val headContentType = headResponse?.headers?.get(HttpHeaders.ContentType)?.lowercase()
            if (headContentType != null && headContentType.startsWith("image/")) {
                putInCache(url, url)
                return url
            }

            val getResponse =
                runCatching {
                    httpClient.get(url) {
                        header(HttpHeaders.UserAgent, USER_AGENT)
                        header(HttpHeaders.Range, "bytes=0-8192")
                    }
                }
                    .getOrNull()

            val getContentType = getResponse?.headers?.get(HttpHeaders.ContentType)?.lowercase()
            if (getContentType != null && getContentType.startsWith("image/")) {
                putInCache(url, url)
                return url
            }

            if (getContentType != null && getContentType.contains("text/html")) {
                val body = runCatching { getResponse.bodyAsText() }.getOrDefault("")
                val ogMatch = OG_IMAGE_REGEX.find(body) ?: OG_IMAGE_REVERSE_REGEX.find(body)
                val ogImage = ogMatch?.groupValues?.getOrNull(1)
                if (!ogImage.isNullOrBlank()) {
                    val resolved = resolveRelativeUrl(url, ogImage)
                    putInCache(url, resolved)
                    return resolved
                }
            }

            putInCache(url, null)
            null
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.w(e) { "Failed to resolve image URL for $url" }
            putInCache(url, null)
            null
        }
    }

    @Suppress("ReturnCount")
    private fun resolveRelativeUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) return relativeUrl
        if (relativeUrl.startsWith("//")) return "https:$relativeUrl"
        val uriParts = baseUrl.split("://", limit = 2)
        val scheme = if (uriParts.size > 1) uriParts[0] else "https"
        val host = (if (uriParts.size > 1) uriParts[1] else baseUrl).substringBefore('/')
        return if (relativeUrl.startsWith("/")) {
            "$scheme://$host$relativeUrl"
        } else {
            "$scheme://$host/$relativeUrl"
        }
    }

    private fun putInCache(url: String, result: String?) {
        cache.update { current ->
            if (current.size >= MAX_CACHE_SIZE) {
                val pruned =
                    current.entries.drop(current.size - MAX_CACHE_SIZE + EVICTION_COUNT).associate {
                        it.key to it.value
                    }
                pruned + (url to result)
            } else {
                current + (url to result)
            }
        }
    }
}
