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
@file:Suppress("MagicNumber", "ReturnCount", "ComplexMethod")

package org.meshtastic.core.network.service

import io.ktor.http.Url

/**
 * Validates URLs to prevent Server-Side Request Forgery (SSRF).
 *
 * Rejects non-HTTP(S) protocols, loopback, private RFC-1918, link-local, carrier-grade NAT, multicast, and reserved IP
 * ranges both for explicit host IPs and resolved domain names.
 */
@Suppress("MagicNumber")
object SafeUrlValidator {

    private val BLOCKED_HOST_SUFFIXES =
        listOf(
            "localhost",
            ".localhost",
            ".local",
            ".internal",
            ".lan",
            ".localdomain",
            ".home.arpa",
            ".test",
            ".example",
            ".invalid",
        )

    private val IPV4_REGEX = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    internal var dnsResolverForTesting: (suspend (String) -> List<String>)? = null
    internal var skipHostNameFilterForTesting: Boolean = false

    suspend fun isSafeUrl(urlString: String): Boolean {
        val url = runCatching { Url(urlString) }.getOrNull() ?: return false
        val protocol = url.protocol.name.lowercase()
        if (protocol != "http" && protocol != "https") {
            return false
        }

        val host = url.host.trim().lowercase()
        if (host.isBlank() || url.user != null || url.password != null) {
            return false
        }

        if (!skipHostNameFilterForTesting && isBlockedHostName(host)) {
            return false
        }

        if (isIpAddress(host)) {
            return !isPrivateOrReservedIp(host)
        }

        val resolvedIps = dnsResolverForTesting?.invoke(host) ?: resolveDnsHost(host)
        if (resolvedIps.isEmpty()) {
            return false
        }

        return resolvedIps.none { isPrivateOrReservedIp(it) }
    }

    internal fun isBlockedHostName(host: String): Boolean {
        if (host == "localhost") return true
        return BLOCKED_HOST_SUFFIXES.any { host.endsWith(it) }
    }

    internal fun isIpAddress(host: String): Boolean {
        val cleanHost = host.removePrefix("[").removeSuffix("]")
        return IPV4_REGEX.matches(cleanHost) || cleanHost.contains(':')
    }

    @Suppress("ComplexMethod", "ReturnCount")
    internal fun isPrivateOrReservedIp(ipString: String): Boolean {
        val clean = ipString.removePrefix("[").removeSuffix("]").trim().lowercase()

        val ipv4Match = IPV4_REGEX.matchEntire(clean)
        if (ipv4Match != null) {
            val octets = ipv4Match.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
            if (octets.size != 4 || octets.any { it !in 0..255 }) return true

            val o0 = octets[0]
            val o1 = octets[1]
            val o2 = octets[2]

            return when {
                o0 == 0 -> true

                // 0.0.0.0/8
                o0 == 10 -> true

                // 10.0.0.0/8
                o0 == 127 -> true

                // 127.0.0.0/8
                o0 == 169 && o1 == 254 -> true

                // 169.254.0.0/16
                o0 == 172 && o1 in 16..31 -> true

                // 172.16.0.0/12
                o0 == 192 && o1 == 168 -> true

                // 192.168.0.0/16
                o0 == 100 && o1 in 64..127 -> true

                // 100.64.0.0/10 CGNAT
                o0 == 192 && o1 == 0 && (o2 == 0 || o2 == 2) -> true

                // 192.0.0.0/24 & TEST-NET-1
                o0 == 198 && (o1 == 18 || o1 == 19 || (o1 == 51 && o2 == 100)) -> true

                // Bench & TEST-NET-2
                o0 == 203 && o1 == 0 && o2 == 113 -> true

                // TEST-NET-3
                o0 in 224..239 -> true

                // Multicast
                o0 in 240..255 -> true

                // Reserved / Broadcast
                else -> false
            }
        }

        if (clean.contains(':')) {
            return isPrivateOrReservedIpv6(clean)
        }

        return true
    }

    private fun isPrivateOrReservedIpv6(clean: String): Boolean {
        if (clean == "::1" || clean == "::" || clean.endsWith("::1")) return true
        if (clean.startsWith("fe8") || clean.startsWith("fe9") || clean.startsWith("fea") || clean.startsWith("feb")) {
            return true // fe80::/10 link-local
        }
        if (clean.startsWith("fc") || clean.startsWith("fd")) {
            return true // fc00::/7 ULA
        }
        if (clean.startsWith("ff")) {
            return true // ff00::/8 multicast
        }
        // IPv4-mapped IPv6 e.g. ::ffff:192.168.1.1
        val lastColon = clean.lastIndexOf(':')
        if (lastColon >= 0 && clean.contains('.')) {
            val embeddedIpv4 = clean.substring(lastColon + 1)
            if (IPV4_REGEX.matches(embeddedIpv4)) {
                return isPrivateOrReservedIp(embeddedIpv4)
            }
        }
        return false
    }

    /** Resolves a potentially relative URL against [baseUrl] according to RFC 3986 directory rules. */
    internal fun resolveRelativeUrl(baseUrl: String, relativeUrl: String): String {
        val trimmed = relativeUrl.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        val uriParts = baseUrl.split("://", limit = 2)
        val scheme = if (uriParts.size > 1) uriParts[0] else "https"
        if (trimmed.startsWith("//")) {
            return "$scheme:$trimmed"
        }
        val pathAndQuery = if (uriParts.size > 1) uriParts[1] else baseUrl
        val host = pathAndQuery.substringBefore('/')
        if (trimmed.startsWith("/")) {
            return "$scheme://$host$trimmed"
        }
        val pathOnly = pathAndQuery.substringAfter('/', "").substringBefore('?').substringBefore('#')
        val baseDir =
            if (pathOnly.contains('/')) {
                "/" + pathOnly.substringBeforeLast('/') + "/"
            } else {
                "/"
            }
        return "$scheme://$host$baseDir$trimmed"
    }
}
