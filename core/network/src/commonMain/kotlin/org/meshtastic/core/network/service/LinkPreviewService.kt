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
@file:Suppress("MagicNumber")

package org.meshtastic.core.network.service

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import org.koin.core.annotation.Single
import org.meshtastic.core.model.LinkPreview

interface LinkPreviewService {
    suspend fun getLinkPreview(url: String): LinkPreview?
}

@Single
@Suppress("TooManyFunctions")
class LinkPreviewServiceImpl(private val httpClient: HttpClient) : LinkPreviewService {
    private val logger = Logger.withTag("LinkPreviewService")
    private val cache = atomic(mapOf<String, LinkPreview?>())
    private val client = httpClient.config {
        followRedirects = false
    }

    companion object {
        private const val MAX_CACHE_SIZE = 300
        private const val EVICTION_COUNT = 30
        private const val MAX_REDIRECT_HOPS = 5
        private const val MAX_HTML_BYTES = 32768
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val OG_TITLE_REGEX =
            Regex(
                """<meta\s+[^>]*?(?:property|name)=["'](?:og:title|twitter:title)["'][^>]*?content=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE,
            )
        private val OG_TITLE_REVERSE_REGEX =
            Regex(
                """<meta\s+[^>]*?content=["']([^"']+)["'][^>]*?(?:property|name)=["'](?:og:title|twitter:title)["']""",
                RegexOption.IGNORE_CASE,
            )
        private val HTML_TITLE_REGEX = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)

        private val OG_DESC_REGEX =
            Regex(
                """<meta\s+[^>]*?(?:property|name)=["'](?:og:description|twitter:description|description)["']""" +
                    """[^>]*?content=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE,
            )
        private val OG_DESC_REVERSE_REGEX =
            Regex(
                """<meta\s+[^>]*?content=["']([^"']+)["']""" +
                    """[^>]*?(?:property|name)=["'](?:og:description|twitter:description|description)["']""",
                RegexOption.IGNORE_CASE,
            )

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

        private val OG_SITE_NAME_REGEX =
            Regex(
                """<meta\s+[^>]*?(?:property|name)=["'](?:og:site_name)["'][^>]*?content=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE,
            )
        private val OG_SITE_NAME_REVERSE_REGEX =
            Regex(
                """<meta\s+[^>]*?content=["']([^"']+)["'][^>]*?(?:property|name)=["'](?:og:site_name)["']""",
                RegexOption.IGNORE_CASE,
            )
    }

    @Suppress("ReturnCount", "ComplexMethod")
    override suspend fun getLinkPreview(url: String): LinkPreview? {
        cache.value[url]?.let {
            return it
        }
        if (cache.value.containsKey(url)) return null

        if (!SafeUrlValidator.isSafeUrl(url)) {
            putInCache(url, null)
            return null
        }

        return try {
            var currentUrl = url
            var hops = 0
            var finalResponse: HttpResponse? = null

            while (hops < MAX_REDIRECT_HOPS) {
                if (!SafeUrlValidator.isSafeUrl(currentUrl)) {
                    putInCache(url, null)
                    return null
                }

                val response =
                    client.get(currentUrl) {
                        header(HttpHeaders.UserAgent, USER_AGENT)
                        header(HttpHeaders.Range, "bytes=0-$MAX_HTML_BYTES")
                    }

                if (response.status.value in 300..399) {
                    val location = response.headers[HttpHeaders.Location]
                    if (location.isNullOrBlank()) {
                        putInCache(url, null)
                        return null
                    }
                    currentUrl = SafeUrlValidator.resolveRelativeUrl(currentUrl, location)
                    hops++
                } else {
                    finalResponse = response
                    break
                }
            }

            if (finalResponse == null || finalResponse.status.value !in 200..299) {
                putInCache(url, null)
                return null
            }

            val contentType = finalResponse.headers[HttpHeaders.ContentType]?.lowercase() ?: ""
            if (contentType.startsWith("image/")) {
                val preview =
                    LinkPreview(
                        url = url,
                        title = null,
                        description = null,
                        imageUrl = currentUrl,
                        siteName = extractHost(currentUrl),
                    )
                putInCache(url, preview)
                return preview
            }

            if (!contentType.contains("text/html") && !contentType.contains("application/xhtml+xml")) {
                putInCache(url, null)
                return null
            }

            val html = finalResponse.readBoundedText(MAX_HTML_BYTES)
            val preview = parseHtml(currentUrl, html)?.copy(url = url)
            putInCache(url, preview)
            preview
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.d(e) { "Failed to fetch link preview for $url" }
            putInCache(url, null)
            null
        }
    }

    internal fun parseHtml(url: String, html: String): LinkPreview? {
        val title = extractTitle(html)
        val description = extractDescription(html)
        val rawImageUrl = extractImage(html)
        val imageUrl = rawImageUrl?.let { resolveRelativeUrl(url, it) }
        val siteName = extractSiteName(html) ?: extractHost(url)

        if (title.isNullOrBlank() && description.isNullOrBlank() && imageUrl.isNullOrBlank()) {
            return null
        }

        return LinkPreview(
            url = url,
            title = title?.let { unescapeHtml(it).trim() },
            description = description?.let { unescapeHtml(it).trim() },
            imageUrl = imageUrl?.trim(),
            siteName = siteName?.let { unescapeHtml(it).trim() },
        )
    }

    private fun extractTitle(html: String): String? {
        val ogMatch = OG_TITLE_REGEX.find(html) ?: OG_TITLE_REVERSE_REGEX.find(html)
        if (ogMatch != null) {
            val title = ogMatch.groupValues[1]
            if (title.isNotBlank()) return title
        }
        val tagMatch = HTML_TITLE_REGEX.find(html)
        return tagMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun extractDescription(html: String): String? {
        val ogMatch = OG_DESC_REGEX.find(html) ?: OG_DESC_REVERSE_REGEX.find(html)
        return ogMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun extractImage(html: String): String? {
        val ogMatch = OG_IMAGE_REGEX.find(html) ?: OG_IMAGE_REVERSE_REGEX.find(html)
        return ogMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun extractSiteName(html: String): String? {
        val ogMatch = OG_SITE_NAME_REGEX.find(html) ?: OG_SITE_NAME_REVERSE_REGEX.find(html)
        return ogMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    internal fun extractHost(url: String): String? {
        val clean = url.substringAfter("://", "").substringBefore('/').substringBefore(':')
        return clean.removePrefix("www.").takeIf { it.isNotBlank() }
    }

    internal fun resolveRelativeUrl(baseUrl: String, relativeUrl: String): String =
        SafeUrlValidator.resolveRelativeUrl(baseUrl, relativeUrl)

    internal fun unescapeHtml(input: String): String = input
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&#x27;", "'")
        .replace("&#x2F;", "/")

    private fun putInCache(url: String, result: LinkPreview?) {
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
