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
package org.meshtastic.feature.messaging

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression test to ensure that merge conflicts with upstream main do not accidentally revert critical fixes for
 * LookaheadDelegate crashes, retired channel guards, or desktop build compatibility.
 */
class MessageLookaheadRegressionTest {

    private fun findProjectRoot(): File {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        return dir
    }

    @Test
    fun `Message kt must never contain LazyLayoutCacheWindow`() {
        val root = findProjectRoot()
        val messageFile =
            File(root, "feature/messaging/src/commonMain/kotlin/org/meshtastic/feature/messaging/Message.kt")
        assertTrue(messageFile.exists(), "Message.kt must exist at ${messageFile.path}")

        val content = messageFile.readText()
        assertFalse(
            content.contains("LazyLayoutCacheWindow"),
            "CRITICAL REGRESSION: Message.kt must NOT use LazyLayoutCacheWindow! " +
                "Inside ThreePaneScaffold's LookaheadScope, prefetching items outside the viewport " +
                "causes 'IllegalStateException: LookaheadDelegate has not been measured yet'.",
        )
    }

    @Test
    fun `Message kt must retain retired channel guards and notice`() {
        val root = findProjectRoot()
        val messageFile =
            File(root, "feature/messaging/src/commonMain/kotlin/org/meshtastic/feature/messaging/Message.kt")
        assertTrue(messageFile.exists(), "Message.kt must exist at ${messageFile.path}")

        val content = messageFile.readText()
        assertTrue(
            content.contains("isRetiredChannel"),
            "REGRESSION: Message.kt must retain isRetiredChannel guard to protect retired conversations.",
        )
        assertTrue(
            content.contains("RetiredChannelNotice"),
            "REGRESSION: Message.kt must retain RetiredChannelNotice when channel is archived.",
        )
    }

    @Test
    fun `MessageListPaged kt must not delete original message before resending`() {
        val root = findProjectRoot()
        val file =
            File(root, "feature/messaging/src/commonMain/kotlin/org/meshtastic/feature/messaging/MessageListPaged.kt")
        assertTrue(file.exists(), "MessageListPaged.kt must exist at ${file.path}")

        val content = file.readText()
        assertTrue(
            content.contains("canSend"),
            "REGRESSION: MessageListPaged.kt must retain canSend in state.",
        )
        val resendBlock = content.substringAfter("onResend = {").substringBefore("showStatusDialog = null")
        assertFalse(
            resendBlock.contains("onDeleteMessages"),
            "REGRESSION: MessageListPaged.kt onResend must not call onDeleteMessages before resending.",
        )
        assertTrue(
            resendBlock.contains("handlers.onResendMessage"),
            "REGRESSION: MessageListPaged.kt onResend must call handlers.onResendMessage for atomic replacement.",
        )
    }

    @Test
    fun `desktopApp build gradle kts must not depend on coil-gif`() {
        val root = findProjectRoot()
        val desktopGradle = File(root, "desktopApp/build.gradle.kts")
        assertTrue(desktopGradle.exists(), "desktopApp/build.gradle.kts must exist at ${desktopGradle.path}")

        val content = desktopGradle.readText()
        assertFalse(
            content.contains("libs.coil.gif"),
            "CRITICAL REGRESSION: desktopApp must NOT depend on coil.gif (no JVM variant published on Maven Central).",
        )
    }
}
