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

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeUrlValidatorTest {

    @Test
    fun `isPrivateOrReservedIp detects dangerous IPv4 addresses`() {
        assertTrue(SafeUrlValidator.isPrivateOrReservedIp("127.0.0.1"))
        assertTrue(SafeUrlValidator.isPrivateOrReservedIp("127.255.255.255"))
        assertTrue(SafeUrlValidator.isPrivateOrReservedIp("10.0.0.1"))
        assertTrue(SafeUrlValidator.isPrivateOrReservedIp("10.255.255.255"))
        assertTrue(SafeUrlValidator.isPrivateOrReservedIp("172.16.0.1"))
        assertTrue(SafeUrlValidator.isPrivateOrReservedIp("172.31.255.255"))
        assertTrue(SafeUrlValidator.isPrivateOrReservedIp("192.168.1.1"))
        assertTrue(SafeUrlValidator.isPrivateOrReservedIp("169.254.169.254")) // AWS metadata
        assertTrue(SafeUrlValidator.isPrivateOrReservedIp("100.64.0.1")) // CGNAT
        assertTrue(SafeUrlValidator.isPrivateOrReservedIp("0.0.0.0"))
        assertTrue(SafeUrlValidator.isPrivateOrReservedIp("224.0.0.1")) // Multicast
        assertTrue(SafeUrlValidator.isPrivateOrReservedIp("255.255.255.255")) // Broadcast

        assertFalse(SafeUrlValidator.isPrivateOrReservedIp("8.8.8.8"))
        assertFalse(SafeUrlValidator.isPrivateOrReservedIp("1.1.1.1"))
        assertFalse(SafeUrlValidator.isPrivateOrReservedIp("93.184.215.14"))
    }

    @Test
    fun `isPrivateOrReservedIp detects dangerous IPv6 addresses`() {
        assertTrue(SafeUrlValidator.isPrivateOrReservedIp("::1"))
        assertTrue(SafeUrlValidator.isPrivateOrReservedIp("::"))
        assertTrue(SafeUrlValidator.isPrivateOrReservedIp("fe80::1"))
        assertTrue(SafeUrlValidator.isPrivateOrReservedIp("fc00::1"))
        assertTrue(SafeUrlValidator.isPrivateOrReservedIp("fd12:3456::1"))
        assertTrue(SafeUrlValidator.isPrivateOrReservedIp("ff02::1"))
        assertTrue(SafeUrlValidator.isPrivateOrReservedIp("::ffff:192.168.1.1"))

        assertFalse(SafeUrlValidator.isPrivateOrReservedIp("2606:4700:4700::1111"))
    }

    @Test
    fun `isBlockedHostName detects local and internal names`() {
        assertTrue(SafeUrlValidator.isBlockedHostName("localhost"))
        assertTrue(SafeUrlValidator.isBlockedHostName("my.localhost"))
        assertTrue(SafeUrlValidator.isBlockedHostName("router.local"))
        assertTrue(SafeUrlValidator.isBlockedHostName("service.internal"))
        assertTrue(SafeUrlValidator.isBlockedHostName("nas.lan"))
        assertTrue(SafeUrlValidator.isBlockedHostName("host.localdomain"))
        assertTrue(SafeUrlValidator.isBlockedHostName("device.home.arpa"))

        assertFalse(SafeUrlValidator.isBlockedHostName("clck.ru"))
        assertFalse(SafeUrlValidator.isBlockedHostName("meshpic.org"))
        assertFalse(SafeUrlValidator.isBlockedHostName("github.com"))
    }

    @Test
    fun `isSafeUrl rejects malicious schemes and IPs`() = runTest {
        assertFalse(SafeUrlValidator.isSafeUrl("http://127.0.0.1:8080/"))
        assertFalse(SafeUrlValidator.isSafeUrl("http://192.168.1.1/admin"))
        assertFalse(SafeUrlValidator.isSafeUrl("http://169.254.169.254/latest/meta-data/"))
        assertFalse(SafeUrlValidator.isSafeUrl("http://localhost:3000/"))
        assertFalse(SafeUrlValidator.isSafeUrl("http://router.local/"))
        assertFalse(SafeUrlValidator.isSafeUrl("file:///etc/passwd"))
        assertFalse(SafeUrlValidator.isSafeUrl("javascript:alert(1)"))
        assertFalse(SafeUrlValidator.isSafeUrl("ftp://example.com/file"))
        assertFalse(SafeUrlValidator.isSafeUrl("http://admin:secret@example.com/"))

        // Public IP
        assertTrue(SafeUrlValidator.isSafeUrl("https://93.184.215.14/"))
    }

    @Test
    fun `resolveRelativeUrl correctly resolves RFC 3986 relative paths`() {
        // Direct absolute
        assertEquals(
            "https://other.com/pic.jpg",
            SafeUrlValidator.resolveRelativeUrl("https://example.com/page", "https://other.com/pic.jpg"),
        )
        // Root relative
        assertEquals(
            "https://example.com/assets/logo.png",
            SafeUrlValidator.resolveRelativeUrl("https://example.com/blog/article", "/assets/logo.png"),
        )
        // Directory relative (Synology photos example)
        assertEquals(
            "https://nas.netbird.forment.ru/mo/sharing/3yw30Nylv/cover.jpg",
            SafeUrlValidator.resolveRelativeUrl(
                "https://nas.netbird.forment.ru/mo/sharing/3yw30Nylv?clckid=a8232b5d",
                "3yw30Nylv/cover.jpg",
            ),
        )
    }
}
