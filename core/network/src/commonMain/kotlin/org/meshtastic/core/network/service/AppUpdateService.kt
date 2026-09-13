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
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single
import org.meshtastic.core.common.BuildConfigProvider
import org.meshtastic.core.model.AppUpdateCheckState
import org.meshtastic.core.model.AppUpdateInfo
import org.meshtastic.core.model.ModVersion
import org.meshtastic.core.repository.AppUpdateService

@Serializable
internal data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("body") val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
)

@Single
class AppUpdateServiceImpl(
    private val buildConfigProvider: BuildConfigProvider,
    private val httpClient: HttpClient = HttpClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
) : AppUpdateService {

    private val logger = Logger.withTag("AppUpdateService")
    private val _updateState = MutableStateFlow<AppUpdateCheckState>(AppUpdateCheckState.Idle)
    override val updateState: StateFlow<AppUpdateCheckState> = _updateState.asStateFlow()

    override suspend fun checkForUpdates(isManual: Boolean): AppUpdateCheckState {
        _updateState.value = AppUpdateCheckState.Checking
        val currentVersion = buildConfigProvider.versionName
        logger.i { "Checking for updates. Current version: $currentVersion (manual: $isManual)" }

        val resultState =
            runCatching {
                val responseText =
                    httpClient
                        .get(RELEASES_API_URL) {
                            header(HttpHeaders.Accept, "application/vnd.github.v3+json")
                            header(HttpHeaders.UserAgent, "Meshtastic-Android")
                        }
                        .bodyAsText()

                val releases = json.decodeFromString<List<GitHubReleaseDto>>(responseText)
                val latestCandidate =
                    releases
                        .filterNot { it.draft }
                        .firstOrNull { release ->
                            val tag = release.tagName
                            tag != null && ModVersion.isNewer(latest = tag, current = currentVersion)
                        }

                if (latestCandidate != null && latestCandidate.tagName != null) {
                    val info =
                        AppUpdateInfo(
                            versionName = latestCandidate.tagName.removePrefix("v"),
                            releaseTitle = latestCandidate.name ?: latestCandidate.tagName,
                            releaseNotes = latestCandidate.body.orEmpty(),
                            releaseUrl = latestCandidate.htmlUrl ?: RELEASES_PAGE_URL,
                            isPrerelease = latestCandidate.prerelease,
                            publishedAt = latestCandidate.publishedAt,
                        )
                    logger.i { "Update available: ${info.versionName}" }
                    AppUpdateCheckState.UpdateAvailable(info)
                } else {
                    logger.i { "App is up to date (current: $currentVersion)" }
                    AppUpdateCheckState.UpToDate
                }
            }
                .getOrElse { throwable ->
                    logger.w(throwable) { "Failed to check for updates" }
                    if (isManual) {
                        AppUpdateCheckState.Error(throwable.message ?: "Network error")
                    } else {
                        AppUpdateCheckState.Idle
                    }
                }

        _updateState.value = resultState
        return resultState
    }

    override fun dismissUpdate() {
        _updateState.value = AppUpdateCheckState.Idle
    }

    companion object {
        private const val RELEASES_API_URL =
            "https://api.github.com/repos/Sashoid-x/Meshtastic-Android/releases?per_page=5"
        private const val RELEASES_PAGE_URL = "https://github.com/Sashoid-x/Meshtastic-Android/releases"
    }
}
