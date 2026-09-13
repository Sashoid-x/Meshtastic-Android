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
package org.meshtastic.core.model

/** Information regarding an available application update. */
data class AppUpdateInfo(
    val versionName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val releaseUrl: String,
    val isPrerelease: Boolean = false,
    val publishedAt: String? = null,
)

/** Represents the current state of the application update checker. */
sealed interface AppUpdateCheckState {
    data object Idle : AppUpdateCheckState

    data object Checking : AppUpdateCheckState

    data class UpdateAvailable(val info: AppUpdateInfo) : AppUpdateCheckState

    data object UpToDate : AppUpdateCheckState

    data class Error(val message: String) : AppUpdateCheckState
}

/** Structured version supporting standard SemVer as well as Meshtastic Advanced mod revisions. */
data class ModVersion(val major: Int, val minor: Int, val patch: Int, val revision: Int = 0) : Comparable<ModVersion> {

    override fun compareTo(other: ModVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.revision })

    companion object {
        private const val REVISION_GROUP_INDEX = 4

        // Matches: v2.8.2-advanced-5, 2.8.2-adv-5, 2.8.2-advanced.5, 2.8.2-adv, 2.8.2, etc.
        private val REGEX =
            Regex("""(?:v)?(\d+)\.(\d+)\.(\d+)(?:[-._]?(?:advanced|adv)[-._]?(\d+))?""", RegexOption.IGNORE_CASE)

        /** Parses a raw version string into a [ModVersion], or null if unparseable. */
        fun parse(versionStr: String): ModVersion? {
            val match = REGEX.find(versionStr.trim()) ?: return null
            val (major, minor, patch) = match.destructured
            val rev = match.groupValues.getOrNull(REVISION_GROUP_INDEX)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            return runCatching { ModVersion(major.toInt(), minor.toInt(), patch.toInt(), rev) }.getOrNull()
        }

        /** Returns true if [latest] is strictly newer than [current]. */
        fun isNewer(latest: String, current: String): Boolean {
            val latestVer = parse(latest)
            val currentVer = parse(current)
            return latestVer != null && currentVer != null && latestVer > currentVer
        }
    }
}
