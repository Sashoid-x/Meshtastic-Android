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
package org.meshtastic.core.data.model

import kotlinx.serialization.Serializable
import org.meshtastic.core.database.entity.ContactSettings
import org.meshtastic.core.database.entity.Packet
import org.meshtastic.core.database.entity.ReactionEntity
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.MessageStatus

/**
 * Root serializable container for exporting and importing Meshtastic chat message history, reactions, and conversation
 * settings.
 */
@Serializable
data class MessagesExport(
    val schemaVersion: Int = 1,
    val exportedAt: String,
    val myNodeNum: Long? = null,
    val packets: List<PacketExport> = emptyList(),
    val reactions: List<ReactionExport> = emptyList(),
    val contactSettings: List<ContactSettingsExport> = emptyList(),
)

@Serializable
data class PacketExport(
    val portNum: Int,
    val contactKey: String,
    val receivedTime: Long,
    val read: Boolean = true,
    val data: DataPacket,
    val packetId: Int = 0,
    val routingError: Int = -1,
    val snr: Float? = null,
    val rssi: Int? = null,
    val hopsAway: Int = -1,
    val filtered: Boolean = false,
    val messageText: String = "",
    val translatedText: String? = null,
    val showTranslated: Boolean = false,
    val pinnedMessage: Boolean = false,
)

@Serializable
data class ReactionExport(
    val myNodeNum: Int = 0,
    val replyId: Int,
    val userId: String,
    val emoji: String,
    val timestamp: Long,
    val snr: Float? = null,
    val rssi: Int? = null,
    val hopsAway: Int = -1,
    val packetId: Int = 0,
    val status: MessageStatus = MessageStatus.UNKNOWN,
    val routingError: Int = 0,
    val relays: Int = 0,
    val relayNode: Int? = null,
    val to: String? = null,
    val channel: Int = 0,
)

@Serializable
data class ContactSettingsExport(
    val contactKey: String,
    val muteUntil: Long = 0L,
    val lastReadMessageTimestamp: Long? = null,
    val filteringDisabled: Boolean = false,
    val draft: String = "",
    val pinned: Boolean = false,
)

fun Packet.toExport(): PacketExport = PacketExport(
    portNum = port_num,
    contactKey = contact_key,
    receivedTime = received_time,
    read = read,
    data = data,
    packetId = packetId,
    routingError = routingError,
    snr = snr,
    rssi = rssi,
    hopsAway = hopsAway,
    filtered = filtered,
    messageText = messageText,
    translatedText = translatedText,
    showTranslated = showTranslated,
    pinnedMessage = pinnedMessage,
)

fun PacketExport.toPacket(): Packet = Packet(
    uuid = 0L,
    myNodeNum = 0,
    port_num = portNum,
    contact_key = contactKey,
    received_time = receivedTime,
    read = read,
    data = data,
    packetId = packetId,
    routingError = routingError,
    snr = snr,
    rssi = rssi,
    hopsAway = hopsAway,
    sfpp_hash = null,
    filtered = filtered,
    messageText = messageText.ifEmpty { data.text.orEmpty() },
    translatedText = translatedText,
    showTranslated = showTranslated,
    pinnedMessage = pinnedMessage,
)

fun ReactionEntity.toExport(): ReactionExport = ReactionExport(
    myNodeNum = myNodeNum,
    replyId = replyId,
    userId = userId,
    emoji = emoji,
    timestamp = timestamp,
    snr = snr,
    rssi = rssi,
    hopsAway = hopsAway,
    packetId = packetId,
    status = status,
    routingError = routingError,
    relays = relays,
    relayNode = relayNode,
    to = to,
    channel = channel,
)

fun ReactionExport.toReactionEntity(): ReactionEntity = ReactionEntity(
    myNodeNum = myNodeNum,
    replyId = replyId,
    userId = userId,
    emoji = emoji,
    timestamp = timestamp,
    snr = snr,
    rssi = rssi,
    hopsAway = hopsAway,
    packetId = packetId,
    status = status,
    routingError = routingError,
    relays = relays,
    relayNode = relayNode,
    to = to,
    channel = channel,
)

fun ContactSettings.toExport(): ContactSettingsExport = ContactSettingsExport(
    contactKey = contact_key,
    muteUntil = muteUntil,
    lastReadMessageTimestamp = lastReadMessageTimestamp,
    filteringDisabled = filteringDisabled,
    draft = draft,
    pinned = pinned,
)

fun ContactSettingsExport.toContactSettings(): ContactSettings = ContactSettings(
    contact_key = contactKey,
    muteUntil = muteUntil,
    lastReadMessageTimestamp = lastReadMessageTimestamp,
    filteringDisabled = filteringDisabled,
    draft = draft,
    pinned = pinned,
)

fun Packet.fingerprint(): String =
    "$port_num|$contact_key|$received_time|$packetId|${data.from}|${data.to}|${data.time}|${data.text}"

fun PacketExport.fingerprint(): String =
    "$portNum|$contactKey|$receivedTime|$packetId|${data.from}|${data.to}|${data.time}|${data.text}"
