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
package org.meshtastic.core.automation.engine

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import okio.ByteString
import org.meshtastic.core.automation.model.AutomationAction
import org.meshtastic.core.repository.usecase.SendMessageOutcome
import org.meshtastic.core.repository.usecase.SendMessageUseCase
import kotlin.test.Test

class DefaultActionExecutorTest {

    private val sentMessages = mutableListOf<SentMessageRecord>()

    private val fakeSendMessageUseCase =
        object : SendMessageUseCase {
            override suspend fun invoke(
                text: String,
                contactKey: String,
                replyId: Int?,
                dataType: Int,
                bytes: ByteString?,
            ): SendMessageOutcome {
                sentMessages += SentMessageRecord(text, contactKey, replyId, dataType, bytes)
                return SendMessageOutcome.Queued(12345)
            }
        }

    private val fakeNotificationManager = org.meshtastic.core.testing.FakeMeshNotificationManager()

    private val playedAlarms = mutableListOf<Pair<String, Int>>()
    private val fakeAudioSpeaker =
        object : AutomationAudioSpeaker {
            override fun playAlarm(alarmType: String, durationSeconds: Int) {
                playedAlarms += alarmType to durationSeconds
            }

            override fun playSound(soundId: String) = Unit

            override fun speakText(text: String, speechRate: Float) = Unit

            override fun vibrate(pattern: String, durationMs: Long) = Unit

            override fun copyToClipboard(text: String) = Unit
        }

    @Test
    fun `dry-run returns descriptive strings without performing side-effects`() = runTest {
        val executor = DefaultActionExecutor(fakeSendMessageUseCase, fakeNotificationManager)
        val event = TriggerEvent(nodeId = 42, nodeName = "Repeater Alpha")

        val sendMsgPreview =
            executor.execute(
                AutomationAction.SendMessage("Status check from {node_name}", channelIndex = 0),
                event,
                dryRun = true,
            )
        sendMsgPreview shouldContain "Would send message to channel 0"
        sendMsgPreview shouldContain "Status check from Repeater Alpha"

        val alarmPreview =
            executor.execute(
                AutomationAction.PlayAlarm(alarmType = "siren", durationSeconds = 10),
                event,
                dryRun = true,
            )
        alarmPreview shouldContain "Would play alarm (siren, 10s)"

        val notifyPreview =
            executor.execute(
                AutomationAction.ShowNotification(title = "Alert", body = "Battery {battery_level}%"),
                event,
                dryRun = true,
            )
        notifyPreview shouldContain "Would show notification"

        val gpioPreview =
            executor.execute(
                AutomationAction.RemoteGpio(destNodeId = 12345, pin = 4, state = true),
                event,
                dryRun = true,
            )
        gpioPreview shouldContain "Would set GPIO pin 4 on node 12345"

        // Verify no side-effects were invoked
        sentMessages.isEmpty() shouldBe true
        playedAlarms.isEmpty() shouldBe true
    }

    @Test
    fun `executing SendMessage invokes SendMessageUseCase with resolved template`() = runTest {
        val executor = DefaultActionExecutor(fakeSendMessageUseCase, fakeNotificationManager)
        val event = TriggerEvent(nodeId = 999, nodeName = "Sensor-1", batteryLevel = 85)

        executor.execute(
            AutomationAction.SendMessage(text = "Node {node_name} battery: {battery_level}%", channelIndex = 1),
            event,
            dryRun = false,
        )

        sentMessages.size shouldBe 1
        val sent = sentMessages.first()
        sent.text shouldBe "Node Sensor-1 battery: 85%"
        sent.contactKey shouldContain "1" // Channel 1
    }

    @Test
    fun `missing optional dependencies throw ActionExecutionException`() = runTest {
        // audioSpeaker is null
        val executor = DefaultActionExecutor(fakeSendMessageUseCase, fakeNotificationManager, audioSpeaker = null)
        val event = TriggerEvent(nodeId = 1)

        io.kotest.assertions.throwables.shouldThrow<ActionExecutionException> {
            executor.execute(AutomationAction.PlayAlarm("siren", 5), event, dryRun = false)
        }
        io.kotest.assertions.throwables.shouldThrow<ActionExecutionException> {
            executor.execute(AutomationAction.PlaySound("beep"), event, dryRun = false)
        }
        io.kotest.assertions.throwables.shouldThrow<ActionExecutionException> {
            executor.execute(AutomationAction.SpeakText("Hello"), event, dryRun = false)
        }
    }

    private val sentReactions = mutableListOf<SentReactionRecord>()
    private val fakeMessagingController =
        object : org.meshtastic.core.repository.MessagingController {
            override suspend fun sendMessage(packet: org.meshtastic.core.model.DataPacket) = Unit

            override suspend fun sendReaction(emoji: String, replyId: Int, contactKey: String) {
                sentReactions += SentReactionRecord(emoji, replyId, contactKey)
            }

            override suspend fun importContact(contact: org.meshtastic.proto.SharedContact) = Unit

            override suspend fun sendSharedContact(nodeNum: Int): Boolean = true
        }

    @Test
    fun `audio speaker executes alarm when present`() = runTest {
        val executor =
            DefaultActionExecutor(fakeSendMessageUseCase, fakeNotificationManager, audioSpeaker = fakeAudioSpeaker)
        val event = TriggerEvent(nodeId = 1)

        executor.execute(AutomationAction.PlayAlarm("siren", 7), event, dryRun = false)

        playedAlarms.size shouldBe 1
        playedAlarms.first() shouldBe ("siren" to 7)
    }

    @Test
    fun `SendReaction fails if replyId is 0 or absent`() = runTest {
        val executor =
            DefaultActionExecutor(
                fakeSendMessageUseCase,
                fakeNotificationManager,
                messagingController = fakeMessagingController,
            )
        val event = TriggerEvent(nodeId = 1, packetId = 0)

        io.kotest.assertions.throwables.shouldThrow<ActionExecutionException> {
            executor.execute(AutomationAction.SendReaction(emoji = "👍"), event, dryRun = false)
        }
    }

    @Test
    fun `SendReaction sends to channel contactKey when replyId is present`() = runTest {
        val executor =
            DefaultActionExecutor(
                fakeSendMessageUseCase,
                fakeNotificationManager,
                messagingController = fakeMessagingController,
            )
        val event = TriggerEvent(nodeId = 1, packetId = 42, contactKey = "0^all")

        executor.execute(AutomationAction.SendReaction(emoji = "👍"), event, dryRun = false)

        sentReactions.size shouldBe 1
        sentReactions.first() shouldBe SentReactionRecord(emoji = "👍", replyId = 42, contactKey = "0^all")
    }

    @Test
    fun `SendReaction routes to destNodeId if specified in action`() = runTest {
        val executor =
            DefaultActionExecutor(
                fakeSendMessageUseCase,
                fakeNotificationManager,
                messagingController = fakeMessagingController,
            )
        val event = TriggerEvent(nodeId = 1, packetId = 99, contactKey = "0^all")

        executor.execute(AutomationAction.SendReaction(emoji = "🔥", destNodeId = 0x1234), event, dryRun = false)

        sentReactions.size shouldBe 1
        sentReactions.first() shouldBe SentReactionRecord(emoji = "🔥", replyId = 99, contactKey = "0!00001234")
    }

    private data class SentMessageRecord(
        val text: String,
        val contactKey: String,
        val replyId: Int?,
        val dataType: Int,
        val bytes: ByteString?,
    )

    private data class SentReactionRecord(val emoji: String, val replyId: Int, val contactKey: String)
}
