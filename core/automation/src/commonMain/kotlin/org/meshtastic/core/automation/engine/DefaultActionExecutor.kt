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

import co.touchlab.kermit.Logger
import org.koin.core.annotation.Single
import org.meshtastic.core.automation.model.AutomationAction
import org.meshtastic.core.automation.util.TemplateResolver
import org.meshtastic.core.common.BuildConfigProvider
import org.meshtastic.core.repository.CommandSender
import org.meshtastic.core.repository.MeshNotificationManager
import org.meshtastic.core.repository.MessagingController
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.usecase.SendMessageUseCase
import kotlin.time.Clock
import org.meshtastic.core.model.Position as ModelPosition
import org.meshtastic.proto.Position as ProtoPosition

private const val HEX_RADIX = 16
private const val HEX_NODE_ID_LENGTH = 8

@Single(binds = [ActionExecutor::class])
class DefaultActionExecutor(
    private val sendMessageUseCase: SendMessageUseCase,
    private val notificationManager: MeshNotificationManager,
    private val commandSender: CommandSender? = null,
    private val messagingController: MessagingController? = null,
    private val audioSpeaker: AutomationAudioSpeaker? = null,
    private val nodeRepository: NodeRepository? = null,
    private val buildConfigProvider: BuildConfigProvider? = null,
) : ActionExecutor {

    private fun enrichEvent(event: TriggerEvent): TriggerEvent {
        val appVersion = event.appVersion ?: buildConfigProvider?.versionName ?: "2.8.3"
        val totalNodes = event.totalNodes ?: nodeRepository?.nodeDBbyNum?.value?.size ?: 0
        val directNodes =
            event.directNodes ?: nodeRepository?.nodeDBbyNum?.value?.values?.count { it.hopsAway == 0 } ?: 0
        val onlineNodes = event.onlineNodes ?: nodeRepository?.nodeDBbyNum?.value?.values?.count { it.isOnline } ?: 0
        val recentNodes =
            event.recentNodes ?: nodeRepository?.nodeDBbyNum?.value?.values?.count { it.lastHeard != 0 } ?: 0
        val uptime = event.uptimeSeconds ?: nodeRepository?.localStats?.value?.uptime_seconds?.toLong() ?: 0L
        val features = event.features ?: "LoRa, MQTT, Automation"

        return event.copy(
            appVersion = appVersion,
            totalNodes = totalNodes,
            directNodes = directNodes,
            onlineNodes = onlineNodes,
            recentNodes = recentNodes,
            uptimeSeconds = uptime,
            features = features,
        )
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ThrowsCount")
    override suspend fun execute(action: AutomationAction, event: TriggerEvent, dryRun: Boolean): String? {
        val resolvedEvent = enrichEvent(event)
        if (dryRun) {
            return when (action) {
                is AutomationAction.SendMessage -> {
                    val resolvedText = TemplateResolver.resolve(action.text, resolvedEvent)
                    val target =
                        if (action.destNodeId != null) "node ${action.destNodeId}" else "channel ${action.channelIndex}"
                    "Would send message to $target: \"$resolvedText\""
                }

                is AutomationAction.RequestPosition -> "Would request position from node ${action.destNodeId}"

                is AutomationAction.RequestTelemetry -> "Would request telemetry from node ${action.destNodeId}"

                is AutomationAction.SendTraceroute -> "Would send traceroute to node ${action.destNodeId}"

                is AutomationAction.RemoteGpio -> "Would set GPIO pin ${action.pin} on node ${action.destNodeId}"

                is AutomationAction.ShowNotification -> {
                    val resolvedTitle = TemplateResolver.resolve(action.title, resolvedEvent)
                    val resolvedBody = TemplateResolver.resolve(action.body, resolvedEvent)
                    "Would show notification: \"$resolvedTitle\" - \"$resolvedBody\""
                }

                is AutomationAction.PlayAlarm -> "Would play alarm (${action.alarmType}, ${action.durationSeconds}s)"

                is AutomationAction.SpeakText -> {
                    val resolvedText = TemplateResolver.resolve(action.text, resolvedEvent)
                    "Would speak text: \"$resolvedText\""
                }

                is AutomationAction.PlaySound -> "Would play sound: ${action.soundId}"

                is AutomationAction.VibrateDevice -> "Would vibrate device (${action.pattern}, ${action.durationMs}ms)"

                is AutomationAction.SendReaction -> {
                    val target =
                        when {
                            action.destNodeId != null -> "node ${action.destNodeId}"
                            !resolvedEvent.contactKey.isNullOrBlank() -> resolvedEvent.contactKey
                            resolvedEvent.channelIndex != null -> "channel ${resolvedEvent.channelIndex}"
                            else -> "channel ${action.channelIndex}"
                        }
                    "Would send reaction ${action.emoji} to $target"
                }

                is AutomationAction.BroadcastLocation -> "Would broadcast device location"

                is AutomationAction.CopyToClipboard -> {
                    val resolvedText = TemplateResolver.resolve(action.text, resolvedEvent)
                    "Would copy to clipboard: \"$resolvedText\""
                }

                is AutomationAction.TriggerRule -> "Would trigger rule ${action.ruleId}"
            }
        }

        when (action) {
            is AutomationAction.SendMessage -> {
                val resolvedText = TemplateResolver.resolve(action.text, resolvedEvent)
                val contactKey =
                    if (action.destNodeId != null) {
                        "0!${action.destNodeId.toUInt().toString(HEX_RADIX).padStart(HEX_NODE_ID_LENGTH, '0')}"
                    } else {
                        "${action.channelIndex}^all"
                    }
                // P1-14: Log metadata only, never leak message content in logs
                Logger.i { "Automation executing SendMessage to $contactKey (length: ${resolvedText.length})" }
                sendMessageUseCase(
                    text = resolvedText,
                    contactKey = contactKey,
                    replyId = null,
                )
            }

            is AutomationAction.RequestPosition -> {
                val sender =
                    commandSender ?: throw ActionExecutionException("skipped: radio/commandSender not available")
                Logger.i { "Automation executing RequestPosition for node ${action.destNodeId}" }
                sender.requestPosition(action.destNodeId, ModelPosition(0.0, 0.0, 0))
            }

            is AutomationAction.RequestTelemetry -> {
                val sender =
                    commandSender ?: throw ActionExecutionException("skipped: radio/commandSender not available")
                Logger.i { "Automation executing RequestTelemetry for node ${action.destNodeId}" }
                val packetId = sender.generatePacketId()
                sender.requestTelemetry(packetId, action.destNodeId, 0)
            }

            is AutomationAction.SendTraceroute -> {
                val sender =
                    commandSender ?: throw ActionExecutionException("skipped: radio/commandSender not available")
                Logger.i { "Automation executing SendTraceroute to node ${action.destNodeId}" }
                val packetId = sender.generatePacketId()
                sender.requestTraceroute(packetId, action.destNodeId)
            }

            is AutomationAction.RemoteGpio -> {
                // P0-7: RemoteHardware proto payload is not implemented upstream
                throw ActionExecutionException("skipped: RemoteHardware action is not supported")
            }

            is AutomationAction.ShowNotification -> {
                val resolvedTitle = TemplateResolver.resolve(action.title, resolvedEvent)
                val resolvedBody = TemplateResolver.resolve(action.body, resolvedEvent)
                // P1-14: Log lengths only
                Logger.i {
                    "Automation executing ShowNotification " +
                        "(title length: ${resolvedTitle.length}, body length: ${resolvedBody.length})"
                }
                notificationManager.showAlertNotification(
                    contactKey = "automation",
                    name = resolvedTitle,
                    alert = resolvedBody,
                )
            }

            is AutomationAction.PlayAlarm -> {
                val speaker = audioSpeaker ?: throw ActionExecutionException("skipped: audioSpeaker not available")
                Logger.i { "Automation executing PlayAlarm (${action.alarmType}, duration=${action.durationSeconds}s)" }
                speaker.playAlarm(action.alarmType, action.durationSeconds)
            }

            is AutomationAction.SpeakText -> {
                val speaker = audioSpeaker ?: throw ActionExecutionException("skipped: audioSpeaker not available")
                val resolvedText = TemplateResolver.resolve(action.text, resolvedEvent)
                // P1-14: Log length only
                Logger.i { "Automation executing SpeakText (length: ${resolvedText.length})" }
                speaker.speakText(resolvedText, action.speechRate)
            }

            is AutomationAction.PlaySound -> {
                val speaker = audioSpeaker ?: throw ActionExecutionException("skipped: audioSpeaker not available")
                Logger.i { "Automation executing PlaySound: ${action.soundId}" }
                speaker.playSound(action.soundId)
            }

            is AutomationAction.VibrateDevice -> {
                val speaker = audioSpeaker ?: throw ActionExecutionException("skipped: audioSpeaker not available")
                Logger.i { "Automation executing VibrateDevice: ${action.pattern}" }
                speaker.vibrate(action.pattern, action.durationMs)
            }

            is AutomationAction.SendReaction -> {
                val replyId = resolvedEvent.packetId ?: 0
                if (replyId == 0) {
                    Logger.w { "Automation SendReaction skipped: reaction requires a target message" }
                    throw ActionExecutionException("skipped: reaction requires an incoming message to reply to")
                }
                val controller =
                    messagingController ?: throw ActionExecutionException("skipped: messagingController not available")

                val targetContactKey =
                    when {
                        action.destNodeId != null ->
                            "0!${action.destNodeId.toUInt().toString(HEX_RADIX).padStart(HEX_NODE_ID_LENGTH, '0')}"

                        !resolvedEvent.contactKey.isNullOrBlank() -> resolvedEvent.contactKey

                        resolvedEvent.channelIndex != null -> "${resolvedEvent.channelIndex}^all"

                        else -> "${action.channelIndex}^all"
                    }

                Logger.i { "Automation executing SendReaction ${action.emoji} to $targetContactKey (replyId=$replyId)" }
                controller.sendReaction(action.emoji, replyId, targetContactKey)
            }

            is AutomationAction.BroadcastLocation -> {
                val sender =
                    commandSender ?: throw ActionExecutionException("skipped: radio/commandSender not available")
                val myPos = nodeRepository?.ourNodeInfo?.value?.position
                val myLat = myPos?.latitude_i?.let { ModelPosition.degD(it) }
                val myLon = myPos?.longitude_i?.let { ModelPosition.degD(it) }
                val lat = resolvedEvent.latitude ?: myLat
                val lon = resolvedEvent.longitude ?: myLon
                if (lat == null || lon == null) {
                    throw ActionExecutionException("skipped: location unavailable")
                }
                Logger.i { "Automation executing BroadcastLocation" }
                val pos =
                    ProtoPosition.Builder()
                        .apply {
                            latitude_i = ModelPosition.degI(lat)
                            longitude_i = ModelPosition.degI(lon)
                            altitude = myPos?.altitude
                            time = Clock.System.now().epochSeconds.toInt()
                        }
                        .build()
                sender.sendPosition(pos = pos, destNum = action.destNodeId)
            }

            is AutomationAction.CopyToClipboard -> {
                val speaker = audioSpeaker ?: throw ActionExecutionException("skipped: audioSpeaker not available")
                val resolvedText = TemplateResolver.resolve(action.text, resolvedEvent)
                // P1-14: Log length only
                Logger.i { "Automation executing CopyToClipboard (length: ${resolvedText.length})" }
                speaker.copyToClipboard(resolvedText)
            }

            is AutomationAction.TriggerRule -> {
                // Handled directly by AutomationEngine; no-op if reached
            }
        }
        return "Action executed successfully: ${action::class.simpleName}"
    }
}
