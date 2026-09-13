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
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.meshtastic.core.model.AppUpdateCheckState
import org.meshtastic.core.testing.FakeBuildConfigProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AppUpdateServiceTest {

    private val jsonResponse =
        """
        [
            {
                "tag_name": "v2.8.2-advanced-6",
                "name": "Meshtastic Android Advanced v2.8.2-advanced-6",
                "body": "## Changes\n- Cool new feature",
                "html_url": "https://github.com/Sashoid-x/Meshtastic-Android/releases/tag/v2.8.2-advanced-6",
                "draft": false,
                "prerelease": true,
                "published_at": "2026-09-13T12:00:00Z"
            },
            {
                "tag_name": "v2.8.2-advanced-5",
                "name": "Meshtastic Android Advanced v2.8.2-advanced-5",
                "body": "Older release",
                "html_url": "https://github.com/Sashoid-x/Meshtastic-Android/releases/tag/v2.8.2-advanced-5",
                "draft": false,
                "prerelease": true,
                "published_at": "2026-09-12T12:00:00Z"
            }
        ]
        """
            .trimIndent()

    @Test
    fun `checkForUpdates detects newer version`() = runTest {
        val engine = MockEngine {
            respond(
                content = jsonResponse,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val buildConfigProvider = FakeBuildConfigProvider(versionName = "2.8.2-advanced-5")
        val service = AppUpdateServiceImpl(buildConfigProvider = buildConfigProvider, httpClient = HttpClient(engine))

        val result = service.checkForUpdates(isManual = false)
        assertIs<AppUpdateCheckState.UpdateAvailable>(result)
        assertEquals("2.8.2-advanced-6", result.info.versionName)
        assertEquals("Meshtastic Android Advanced v2.8.2-advanced-6", result.info.releaseTitle)
        assertEquals(
            "https://github.com/Sashoid-x/Meshtastic-Android/releases/tag/v2.8.2-advanced-6",
            result.info.releaseUrl,
        )
    }

    @Test
    fun `checkForUpdates reports UpToDate when latest release matches current`() = runTest {
        val engine = MockEngine {
            respond(
                content = jsonResponse,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val buildConfigProvider = FakeBuildConfigProvider(versionName = "2.8.2-advanced-6")
        val service = AppUpdateServiceImpl(buildConfigProvider = buildConfigProvider, httpClient = HttpClient(engine))

        val result = service.checkForUpdates(isManual = true)
        assertIs<AppUpdateCheckState.UpToDate>(result)
    }

    @Test
    fun `checkForUpdates handles network failure`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }
        val buildConfigProvider = FakeBuildConfigProvider(versionName = "2.8.2-advanced-5")
        val service = AppUpdateServiceImpl(buildConfigProvider = buildConfigProvider, httpClient = HttpClient(engine))

        // Manual check returns Error
        val manualResult = service.checkForUpdates(isManual = true)
        assertIs<AppUpdateCheckState.Error>(manualResult)

        // Automatic check falls back to Idle
        val autoResult = service.checkForUpdates(isManual = false)
        assertIs<AppUpdateCheckState.Idle>(autoResult)
    }
}
