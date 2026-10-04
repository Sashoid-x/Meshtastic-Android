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
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.repository.CommandSender
import org.meshtastic.core.repository.MeshNotificationManager
import org.meshtastic.core.repository.MessagingController
import org.meshtastic.core.repository.usecase.SendMessageUseCase
import org.meshtastic.proto.PortNum
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
) : ActionExecutor {

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    override suspend fun execute(action: AutomationAction, event: TriggerEvent) {
        when (action) {
            is AutomationAction.SendMessage -> {
                val resolvedText = TemplateResolver.resolve(action.text, event)
                val contactKey =
                    if (action.destNodeId != null) {
                        "0!${action.destNodeId.toUInt().toString(HEX_RADIX).padStart(HEX_NODE_ID_LENGTH, '0')}"
                    } else {
                        "${action.channelIndex}^all"
                    }
                Logger.i { "Automation executing SendMessage to $contactKey: $resolvedText" }
                sendMessageUseCase(
                    text = resolvedText,
                    contactKey = contactKey,
                    replyId = null,
                )
            }

            is AutomationAction.RequestPosition -> {
                Logger.i { "Automation executing RequestPosition for node ${action.destNodeId}" }
                commandSender?.requestPosition(action.destNodeId, ModelPosition(0.0, 0.0, 0))
            }

            is AutomationAction.RequestTelemetry -> {
                Logger.i { "Automation executing RequestTelemetry for node ${action.destNodeId}" }
                val packetId = commandSender?.generatePacketId() ?: 0
                commandSender?.requestTelemetry(packetId, action.destNodeId, 0)
            }

            is AutomationAction.SendTraceroute -> {
                Logger.i { "Automation executing SendTraceroute to node ${action.destNodeId}" }
                val packetId = commandSender?.generatePacketId() ?: 0
                commandSender?.requestTraceroute(packetId, action.destNodeId)
            }

            is AutomationAction.RemoteGpio -> {
                Logger.i {
                    "Automation executing RemoteGpio on node ${action.destNodeId}, pin=${action.pin}"
                }
                commandSender?.sendData(
                    DataPacket(
                        to = action.destNodeId.toString(),
                        dataType = PortNum.REMOTE_HARDWARE_APP.value,
                        bytes = null,
                    ),
                )
            }

            is AutomationAction.ShowNotification -> {
                val resolvedTitle = TemplateResolver.resolve(action.title, event)
                val resolvedBody = TemplateResolver.resolve(action.body, event)
                Logger.i { "Automation executing ShowNotification: $resolvedTitle - $resolvedBody" }
                notificationManager.showAlertNotification(
                    contactKey = "automation",
                    name = resolvedTitle,
                    alert = resolvedBody,
                )
            }

            is AutomationAction.PlayAlarm -> {
                Logger.i { "Automation executing PlayAlarm (${action.alarmType}, duration=${action.durationSeconds}s)" }
                audioSpeaker?.playAlarm(action.alarmType, action.durationSeconds)
            }

            is AutomationAction.SpeakText -> {
                val resolvedText = TemplateResolver.resolve(action.text, event)
                Logger.i { "Automation executing SpeakText: $resolvedText" }
                audioSpeaker?.speakText(resolvedText, action.speechRate)
            }

            is AutomationAction.PlaySound -> {
                Logger.i { "Automation executing PlaySound: ${action.soundId}" }
                audioSpeaker?.playSound(action.soundId)
            }

            is AutomationAction.VibrateDevice -> {
                Logger.i { "Automation executing VibrateDevice: ${action.pattern}" }
                audioSpeaker?.vibrate(action.pattern, action.durationMs)
            }

            is AutomationAction.SendReaction -> {
                val destId = action.destNodeId ?: event.nodeId
                val contactKey =
                    if (destId != null) {
                        "0!${destId.toUInt().toString(HEX_RADIX).padStart(HEX_NODE_ID_LENGTH, '0')}"
                    } else {
                        "${action.channelIndex}^all"
                    }
                val replyId = event.packetId ?: 0
                Logger.i { "Automation executing SendReaction: ${action.emoji} to $contactKey (replyId=$replyId)" }
                if (replyId != 0 && messagingController != null) {
                    messagingController.sendReaction(action.emoji, replyId, contactKey)
                } else {
                    sendMessageUseCase(text = action.emoji, contactKey = contactKey, replyId = null)
                }
            }

            is AutomationAction.BroadcastLocation -> {
                Logger.i { "Automation executing BroadcastLocation" }
                commandSender?.sendPosition(
                    pos = ProtoPosition.Builder().build(),
                    destNum = action.destNodeId,
                )
            }

            is AutomationAction.CopyToClipboard -> {
                val resolvedText = TemplateResolver.resolve(action.text, event)
                Logger.i { "Automation executing CopyToClipboard: $resolvedText" }
                audioSpeaker?.copyToClipboard(resolvedText)
            }

            is AutomationAction.TriggerRule -> {
                Logger.d { "Automation TriggerRule (${action.ruleId}) routed to ActionExecutor" }
            }
        }
    }
}
