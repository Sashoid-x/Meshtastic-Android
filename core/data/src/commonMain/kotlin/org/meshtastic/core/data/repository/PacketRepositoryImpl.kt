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
package org.meshtastic.core.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.json.okio.encodeToBufferedSink
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString.Companion.toByteString
import org.koin.core.annotation.Single
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.data.model.MessagesExport
import org.meshtastic.core.data.model.fingerprint
import org.meshtastic.core.data.model.toContactSettings
import org.meshtastic.core.data.model.toExport
import org.meshtastic.core.data.model.toMeshLog
import org.meshtastic.core.data.model.toPacket
import org.meshtastic.core.data.model.toReactionEntity
import org.meshtastic.core.database.DatabaseProvider
import org.meshtastic.core.database.dao.NodeInfoDao
import org.meshtastic.core.database.entity.PacketEntity
import org.meshtastic.core.database.entity.toReaction
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.model.BackupPacketType
import org.meshtastic.core.model.ContactSettings
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.Message
import org.meshtastic.core.model.MessageImportResult
import org.meshtastic.core.model.MessageStatus
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.model.Reaction
import org.meshtastic.core.repository.PersistedPacket
import org.meshtastic.core.repository.PersistedPacketId
import org.meshtastic.core.repository.PersistedReaction
import org.meshtastic.core.repository.PersistedReactionId
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import kotlin.time.Instant
import org.meshtastic.core.database.entity.ContactSettings as ContactSettingsEntity
import org.meshtastic.core.database.entity.Packet as RoomPacket
import org.meshtastic.core.database.entity.ReactionEntity as RoomReaction
import org.meshtastic.core.repository.PacketRepository as SharedPacketRepository

@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
@Single
class PacketRepositoryImpl(private val dbManager: DatabaseProvider, private val dispatchers: CoroutineDispatchers) :
    SharedPacketRepository {

    override fun getWaypoints(): Flow<List<DataPacket>> =
        dbManager.observeCurrentDb { db -> db.packetDao().getAllWaypointsFlow() }.map { list -> list.map { it.data } }

    override fun getContacts(): Flow<Map<String, DataPacket>> = dbManager
        .observeCurrentDb { db -> db.packetDao().getContactKeys() }
        .map { map -> map.mapValues { it.value.data } }

    override fun getContactsPaged(): Flow<PagingData<Pair<String, DataPacket>>> = Pager(
        config =
        PagingConfig(
            pageSize = CONTACTS_PAGE_SIZE,
            enablePlaceholders = false,
            initialLoadSize = CONTACTS_PAGE_SIZE,
        ),
        pagingSourceFactory = { dbManager.currentDb.value.packetDao().getContactKeysPaged() },
    )
        .flow
        .map { pagingData -> pagingData.map { it.contact_key to it.data } }

    override suspend fun getMessageCount(contact: String): Int =
        withContext(dispatchers.io) { dbManager.currentDb.value.packetDao().getMessageCount(contact) }

    override suspend fun getUnreadCount(contact: String): Int =
        withContext(dispatchers.io) { dbManager.currentDb.value.packetDao().getUnreadCount(contact) }

    override fun getUnreadCountFlow(contact: String): Flow<Int> =
        dbManager.observeCurrentDb { db -> db.packetDao().getUnreadCountFlow(contact) }

    override fun getFirstUnreadMessageUuid(contact: String): Flow<Long?> =
        dbManager.observeCurrentDb { db -> db.packetDao().getFirstUnreadMessageUuid(contact) }

    override fun hasUnreadMessages(contact: String): Flow<Boolean> =
        dbManager.observeCurrentDb { db -> db.packetDao().hasUnreadMessages(contact) }

    override fun getUnreadCountTotal(): Flow<Int> =
        dbManager.observeCurrentDb { db -> db.packetDao().getUnreadCountTotal() }

    // One-shot writes go through withDb so they register with the cross-transport merge drain barrier. The callback
    // is never replayed after it starts; callers needing retries must make that policy explicit where idempotency is
    // known. Reads and Flow/Paging factories stay on currentDb by design.

    override suspend fun clearUnreadCount(contact: String, timestamp: Long) {
        withContext(dispatchers.io + NonCancellable) {
            dbManager.withDb { it.packetDao().clearUnreadCount(contact, timestamp) }
        }
    }

    override suspend fun clearAllUnreadCounts() {
        withContext(dispatchers.io + NonCancellable) { dbManager.withDb { it.packetDao().clearAllUnreadCounts() } }
    }

    override suspend fun updateLastReadMessage(contact: String, messageUuid: Long, lastReadTimestamp: Long) {
        withContext(dispatchers.io + NonCancellable) {
            dbManager.withDb { it.packetDao().updateLastReadMessage(contact, messageUuid, lastReadTimestamp) }
        }
    }

    override suspend fun getQueuedPackets(): List<PersistedPacket> = withContext(dispatchers.io) {
        dbManager.currentDb.value
            .packetDao()
            .getAllPersistedPackets()
            .filter { it.data.status == MessageStatus.QUEUED }
            .map { PersistedPacket(id = PersistedPacketId(it.myNodeNum, it.uuid), packet = it.data) }
    }

    override suspend fun getEnroutePackets(): List<PersistedPacket> = withContext(dispatchers.io) {
        dbManager.currentDb.value
            .packetDao()
            .getAllPersistedPackets()
            .filter { it.data.status == MessageStatus.ENROUTE }
            .map { PersistedPacket(id = PersistedPacketId(it.myNodeNum, it.uuid), packet = it.data) }
    }

    override suspend fun getEnrouteReactions(): List<PersistedReaction> = withContext(dispatchers.io) {
        dbManager.currentDb.value.packetDao().getReactionsByStatus(MessageStatus.ENROUTE).map { entity ->
            PersistedReaction(
                id = PersistedReactionId(entity.myNodeNum, entity.replyId, entity.userId, entity.emoji),
                reaction = entity.toReaction { null },
            )
        }
    }

    // A null from withDb means no database was available, so nothing was timed out.
    override suspend fun timeOutEnroutePacket(id: PersistedPacketId, routingError: Int): Boolean =
        withContext(dispatchers.io + NonCancellable) {
            dbManager.withDb { it.packetDao().timeOutEnroutePacket(id.myNodeNum, id.uuid, routingError) } ?: false
        }

    // A null from withDb means no database was available, so nothing was timed out.
    override suspend fun timeOutEnrouteReaction(id: PersistedReactionId, routingError: Int): Boolean =
        withContext(dispatchers.io + NonCancellable) {
            dbManager.withDb {
                it.packetDao()
                    .timeOutEnrouteReaction(
                        myNodeNum = id.myNodeNum,
                        replyId = id.replyId,
                        userId = id.userId,
                        emoji = id.emoji,
                        routingError = routingError,
                    )
            } ?: false
        }

    suspend fun insertRoomPacket(packet: RoomPacket): Long = withContext(dispatchers.io + NonCancellable) {
        checkNotNull(dbManager.withDb { it.packetDao().insertAndGetId(packet) })
    }

    override suspend fun savePacket(
        myNodeNum: Int,
        contactKey: String,
        packet: DataPacket,
        receivedTime: Long,
        read: Boolean,
        filtered: Boolean,
    ): PersistedPacketId {
        val packetToSave =
            RoomPacket(
                uuid = 0L,
                myNodeNum = myNodeNum,
                packetId = packet.id,
                port_num = packet.dataType,
                contact_key = contactKey,
                received_time = receivedTime,
                read = read,
                data = packet,
                snr = packet.snr,
                rssi = packet.rssi,
                hopsAway = packet.hopsAway,
                filtered = filtered,
                messageText = packet.text.orEmpty(),
            )
        val uuid = insertRoomPacket(packetToSave)
        return PersistedPacketId(myNodeNum = myNodeNum, uuid = uuid)
    }

    override suspend fun getMessagesFrom(
        contact: String,
        limit: Int?,
        includeFiltered: Boolean,
        getNode: suspend (String?) -> Node,
    ): Flow<List<Message>> = withContext(dispatchers.io) {
        val dao = dbManager.currentDb.value.packetDao()
        val flow =
            when {
                limit != null -> dao.getMessagesFrom(contact, limit)
                !includeFiltered -> dao.getMessagesFrom(contact, includeFiltered = false)
                else -> dao.getMessagesFrom(contact)
            }
        flow.mapLatest { packets ->
            val cachedGetNode = memoize(getNode)
            val replyIds = packets.mapNotNull { it.packet.data.replyId?.takeIf { id -> id != 0 } }.distinct()
            val replyMap = batchGetReplyParents(replyIds, contact)
            packets.map { packet ->
                val message = packet.toMessage(cachedGetNode)
                val replyId = message.replyId?.takeIf { it != 0 }
                val originalMessage = replyId?.let { replyMap[it] }?.toMessage(cachedGetNode)
                if (originalMessage != null) message.copy(originalMessage = originalMessage) else message
            }
        }
    }

    override fun getMessagesFromPaged(contact: String, getNode: suspend (String?) -> Node): Flow<PagingData<Message>> =
        Pager(
            config =
            PagingConfig(
                pageSize = MESSAGES_PAGE_SIZE,
                enablePlaceholders = false,
                initialLoadSize = MESSAGES_PAGE_SIZE,
            ),
            pagingSourceFactory = { dbManager.currentDb.value.packetDao().getMessagesFromPaged(contact) },
        )
            .flow
            .map { pagingData ->
                val cachedGetNode = memoize(getNode)
                val replyCache = mutableMapOf<Int, PacketEntity?>()
                pagingData.map { packet ->
                    val message = packet.toMessage(cachedGetNode)
                    val replyId = message.replyId?.takeIf { it != 0 }
                    val originalMessage =
                        replyId
                            ?.let { id -> replyCache.getOrPut(id) { getReplyParent(id, contact) } }
                            ?.toMessage(cachedGetNode)
                    if (originalMessage != null) message.copy(originalMessage = originalMessage) else message
                }
            }

    override fun getMessagesFromPaged(
        contactKey: String,
        includeFiltered: Boolean,
        getNode: suspend (String?) -> Node,
    ): Flow<PagingData<Message>> = Pager(
        config =
        PagingConfig(
            pageSize = MESSAGES_PAGE_SIZE,
            enablePlaceholders = false,
            initialLoadSize = MESSAGES_PAGE_SIZE,
        ),
        pagingSourceFactory = {
            dbManager.currentDb.value.packetDao().getMessagesFromPaged(contactKey, includeFiltered)
        },
    )
        .flow
        .map { pagingData ->
            val cachedGetNode = memoize(getNode)
            val replyCache = mutableMapOf<Int, PacketEntity?>()
            pagingData.map { packet ->
                val message = packet.toMessage(cachedGetNode)
                val replyId = message.replyId?.takeIf { it != 0 }
                val originalMessage =
                    replyId
                        ?.let { id -> replyCache.getOrPut(id) { getReplyParent(id, contactKey) } }
                        ?.toMessage(cachedGetNode)
                if (originalMessage != null) message.copy(originalMessage = originalMessage) else message
            }
        }

    override suspend fun updateMessageStatus(d: DataPacket, m: MessageStatus) {
        withContext(dispatchers.io) { dbManager.withDb { it.packetDao().updateMessageStatus(d, m) } }
    }

    override suspend fun updateMessageStatus(id: PersistedPacketId, status: MessageStatus) {
        withContext(dispatchers.io) {
            dbManager.withDb { it.packetDao().updateMessageStatusByPersistedId(id.myNodeNum, id.uuid, status) }
        }
    }

    override suspend fun claimQueuedPacket(id: PersistedPacketId): PersistedPacket? =
        withContext(dispatchers.io + NonCancellable) {
            dbManager
                .withDb { it.packetDao().claimQueuedPacket(id.myNodeNum, id.uuid) }
                ?.let { packet -> PersistedPacket(PersistedPacketId(packet.myNodeNum, packet.uuid), packet.data) }
        }

    override suspend fun claimQueuedPacketByPacketIdIfUnique(packetId: Int): PersistedPacket? =
        withContext(dispatchers.io + NonCancellable) {
            dbManager
                .withDb { it.packetDao().claimQueuedPacketByPacketIdIfUnique(packetId) }
                ?.let { packet -> PersistedPacket(PersistedPacketId(packet.myNodeNum, packet.uuid), packet.data) }
        }

    override suspend fun rollbackEnroutePacket(id: PersistedPacketId): Boolean =
        withContext(dispatchers.io + NonCancellable) {
            dbManager.withDb { it.packetDao().rollbackEnroutePacket(id.myNodeNum, id.uuid) } ?: false
        }

    override suspend fun updateOutgoingMessageStatus(packet: MeshPacket, status: MessageStatus): PersistedPacketId? =
        withContext(dispatchers.io) {
            dbManager
                .withDb { it.packetDao().updateOutgoingMessageStatus(packet, status) }
                ?.let { PersistedPacketId(it.myNodeNum, it.uuid) }
        }

    override suspend fun resolveOutgoingPacket(packet: MeshPacket): PersistedPacket? = withContext(dispatchers.io) {
        dbManager.currentDb.value.packetDao().resolveOutgoingPacket(packet)?.let { stored ->
            PersistedPacket(PersistedPacketId(stored.myNodeNum, stored.uuid), stored.data)
        }
    }

    override suspend fun applyOutgoingQueueStatus(packet: MeshPacket, status: MessageStatus): PersistedPacket? =
        withContext(dispatchers.io + NonCancellable) {
            dbManager
                .withDb { it.packetDao().applyOutgoingQueueStatus(packet, status) }
                ?.let { stored -> PersistedPacket(PersistedPacketId(stored.myNodeNum, stored.uuid), stored.data) }
        }

    override suspend fun applyOutgoingReactionQueueStatus(packetId: Int, status: MessageStatus): PersistedReaction? =
        withContext(dispatchers.io + NonCancellable) {
            dbManager
                .withDb { it.packetDao().applyOutgoingReactionQueueStatus(packetId, status) }
                ?.let { entity ->
                    PersistedReaction(
                        id = PersistedReactionId(entity.myNodeNum, entity.replyId, entity.userId, entity.emoji),
                        reaction = entity.toReaction { null },
                    )
                }
        }

    override suspend fun updateMessageId(d: DataPacket, id: Int) {
        withContext(dispatchers.io) { dbManager.withDb { it.packetDao().updateMessageId(d, id) } }
    }

    override suspend fun setMessageTranslation(uuid: Long, translatedText: String) {
        withContext(dispatchers.io) { dbManager.withDb { it.packetDao().setTranslation(uuid, translatedText) } }
    }

    override suspend fun setShowTranslated(uuid: Long, showTranslated: Boolean) {
        withContext(dispatchers.io) { dbManager.withDb { it.packetDao().setShowTranslated(uuid, showTranslated) } }
    }

    override suspend fun getPacketById(id: Int): DataPacket? =
        withContext(dispatchers.io) { dbManager.currentDb.value.packetDao().getPacketById(id)?.data }

    override suspend fun getPacketByPacketId(packetId: Int): DataPacket? = withContext(dispatchers.io) {
        dbManager.currentDb.value.packetDao().getPacketByPacketId(packetId)?.packet?.data
    }

    override suspend fun getPacketByPacketIdIfUnique(packetId: Int): DataPacket? = withContext(dispatchers.io) {
        dbManager.currentDb.value.packetDao().findPacketsWithId(packetId).singleOrNull()?.data
    }

    override suspend fun getPacketByPersistedId(id: PersistedPacketId): DataPacket? = withContext(dispatchers.io) {
        dbManager.currentDb.value.packetDao().getPacketByPersistedId(id.myNodeNum, id.uuid)?.data
    }

    private suspend fun getReplyParent(packetId: Int, contactKey: String) = withContext(dispatchers.io) {
        dbManager.currentDb.value.packetDao().getPacketsByPacketIdAndContact(packetId, contactKey).singleOrNull()
    }

    private suspend fun batchGetReplyParents(ids: List<Int>, contactKey: String): Map<Int, PacketEntity> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            withContext(dispatchers.io) {
                val dao = dbManager.currentDb.value.packetDao()
                ids.chunked(NodeInfoDao.MAX_BIND_PARAMS)
                    .flatMap { dao.getPacketsByPacketIdsAndContact(it, contactKey) }
                    .groupBy { it.packet.packetId }
                    .mapNotNull { (packetId, candidates) -> candidates.singleOrNull()?.let { packetId to it } }
                    .toMap()
            }
        }

    private fun memoize(getNode: suspend (String?) -> Node): suspend (String?) -> Node {
        val cache = mutableMapOf<String?, Node>()
        return { id -> cache.getOrPut(id) { getNode(id) } }
    }

    override suspend fun insert(
        packet: DataPacket,
        myNodeNum: Int,
        contactKey: String,
        receivedTime: Long,
        read: Boolean,
        filtered: Boolean,
    ) {
        val packetToSave =
            RoomPacket(
                uuid = 0L,
                myNodeNum = myNodeNum,
                packetId = packet.id,
                port_num = packet.dataType,
                contact_key = contactKey,
                received_time = receivedTime,
                read = read,
                data = packet,
                snr = packet.snr,
                rssi = packet.rssi,
                hopsAway = packet.hopsAway,
                filtered = filtered,
                messageText = packet.text.orEmpty(),
            )
        insertRoomPacket(packetToSave)
    }

    override suspend fun update(packet: DataPacket, routingError: Int): Unit =
        withContext(dispatchers.io) { dbManager.withDb { it.packetDao().updatePacketByKey(packet, routingError) } }

    override suspend fun insertReaction(reaction: Reaction, myNodeNum: Int) {
        withContext(dispatchers.io) { dbManager.withDb { it.packetDao().insert(reaction.toEntity(myNodeNum)) } }
    }

    override suspend fun updateReaction(reaction: Reaction) {
        withContext(dispatchers.io) { dbManager.withDb { it.packetDao().updateReactionByKey(reaction.toEntity(0)) } }
    }

    override suspend fun getReactionByPacketId(packetId: Int): Reaction? = withContext(dispatchers.io) {
        dbManager.currentDb.value.packetDao().getReactionByPacketId(packetId)?.toReaction { null }
    }

    override suspend fun findPacketsWithId(packetId: Int): List<DataPacket> = withContext(dispatchers.io) {
        dbManager.currentDb.value.packetDao().findPacketsWithId(packetId).map { it.data }
    }

    override suspend fun findReactionsWithId(packetId: Int): List<Reaction> = withContext(dispatchers.io) {
        dbManager.currentDb.value.packetDao().findReactionsWithId(packetId).toReaction { null }
    }

    override suspend fun updateSFPPStatus(
        packetId: Int,
        from: Int,
        to: Int,
        hash: ByteArray,
        status: MessageStatus,
        rxTime: Long,
        myNodeNum: Int?,
    ) {
        withContext(dispatchers.io) {
            dbManager.withDb {
                it.packetDao().applySFPPStatus(packetId, from, to, hash.toByteString(), status, rxTime, myNodeNum)
            }
        }
    }

    override suspend fun updateSFPPStatusByHash(hash: ByteArray, status: MessageStatus, rxTime: Long) {
        withContext(dispatchers.io) {
            dbManager.withDb { it.packetDao().applySFPPStatusByHash(hash.toByteString(), status, rxTime) }
        }
    }

    override suspend fun deleteMessages(uuidList: List<Long>) {
        withContext(dispatchers.io) { dbManager.withDb { it.packetDao().deleteMessagesAtomic(uuidList) } }
    }

    override suspend fun deleteContacts(contactList: List<String>) {
        withContext(dispatchers.io) { dbManager.withDb { it.packetDao().deleteContacts(contactList) } }
    }

    override suspend fun deleteWaypoint(id: Int) {
        withContext(dispatchers.io) { dbManager.withDb { it.packetDao().deleteWaypoint(id) } }
    }

    suspend fun delete(packet: RoomPacket) {
        withContext(dispatchers.io) { dbManager.withDb { it.packetDao().delete(packet) } }
    }

    suspend fun update(packet: RoomPacket) {
        withContext(dispatchers.io) { dbManager.withDb { it.packetDao().update(packet) } }
    }

    override fun getContactSettings(): Flow<Map<String, ContactSettings>> = dbManager
        .observeCurrentDb { db -> db.packetDao().getContactSettings() }
        .map { map -> map.mapValues { it.value.toShared() } }

    override suspend fun getContactSettings(contact: String): ContactSettings = withContext(dispatchers.io) {
        dbManager.currentDb.value.packetDao().getContactSettings(contact)?.toShared() ?: ContactSettings(contact)
    }

    override suspend fun setMuteUntil(contacts: List<String>, until: Long) {
        withContext(dispatchers.io) { dbManager.withDb { it.packetDao().setMuteUntil(contacts, until) } }
    }

    suspend fun insertReaction(reaction: RoomReaction) {
        withContext(dispatchers.io) { dbManager.withDb { it.packetDao().insert(reaction) } }
    }

    suspend fun updateReaction(reaction: RoomReaction) {
        withContext(dispatchers.io) { dbManager.withDb { it.packetDao().update(reaction) } }
    }

    override fun getFilteredCountFlow(contactKey: String): Flow<Int> =
        dbManager.observeCurrentDb { db -> db.packetDao().getFilteredCountFlow(contactKey) }

    override suspend fun getFilteredCount(contactKey: String): Int =
        withContext(dispatchers.io) { dbManager.currentDb.value.packetDao().getFilteredCount(contactKey) }

    override suspend fun setContactFilteringDisabled(contactKey: String, disabled: Boolean) {
        withContext(dispatchers.io) {
            dbManager.withDb { it.packetDao().setContactFilteringDisabled(contactKey, disabled) }
        }
    }

    override suspend fun setDraft(contactKey: String, draft: String) {
        withContext(dispatchers.io) { dbManager.withDb { it.packetDao().setDraft(contactKey, draft) } }
    }

    override suspend fun getDraft(contactKey: String): String =
        withContext(dispatchers.io) { dbManager.currentDb.value.packetDao().getDraft(contactKey).orEmpty() }

    override suspend fun setPinned(contactKeys: List<String>, pinned: Boolean) {
        withContext(dispatchers.io) { dbManager.withDb { it.packetDao().setPinned(contactKeys, pinned) } }
    }

    override suspend fun markContactUnread(contactKey: String) {
        withContext(dispatchers.io) { dbManager.withDb { it.packetDao().markContactUnread(contactKey) } }
    }

    override suspend fun clearPacketDB() {
        withContext(dispatchers.io) { dbManager.withDb { it.packetDao().deleteAll() } }
    }

    override suspend fun migrateChannelsByPSK(oldSettings: List<ChannelSettings>, newSettings: List<ChannelSettings>) {
        withContext(dispatchers.io) {
            dbManager.withDb { it.packetDao().migrateChannelsByPSK(oldSettings, newSettings) }
        }
    }

    override suspend fun updateFilteredBySender(senderId: String, filtered: Boolean) {
        val pattern = "%\"from\":\"${senderId}\"%"
        withContext(dispatchers.io) { dbManager.withDb { it.packetDao().updateFilteredBySender(pattern, filtered) } }
    }

    private fun org.meshtastic.core.database.dao.PacketDao.getAllWaypointsFlow(): Flow<List<RoomPacket>> =
        getAllPackets(PortNum.WAYPOINT_APP.value)

    private fun ContactSettingsEntity.toShared() = ContactSettings(
        contactKey = contact_key,
        muteUntil = muteUntil,
        lastReadMessageUuid = lastReadMessageUuid,
        lastReadMessageTimestamp = lastReadMessageTimestamp,
        filteringDisabled = filteringDisabled,
        isMuted = isMuted,
        draft = draft,
        pinned = pinned,
    )

    private fun Reaction.toEntity(myNodeNum: Int) = RoomReaction(
        myNodeNum = myNodeNum,
        replyId = replyId,
        userId = user.id,
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
        sfpp_hash = sfppHash,
    )

    override fun searchMessages(query: String, contactKey: String?, getNode: (String?) -> Node): Flow<List<Message>> {
        val sanitized = sanitizeFtsQuery(query)
        if (sanitized.isBlank()) return flowOf(emptyList())
        return dbManager.observeCurrentDb { db ->
            kotlinx.coroutines.flow.flow {
                val dao = db.packetDao()
                val packets =
                    if (contactKey != null) {
                        dao.searchMessagesInConversation(sanitized, contactKey)
                    } else {
                        dao.searchMessages(sanitized)
                    }
                emit(
                    packets.map { packet ->
                        val node = getNode(packet.data.from)
                        val isFromLocal =
                            node.user.id == NodeAddress.ID_LOCAL ||
                                (packet.myNodeNum != 0 && node.num == packet.myNodeNum)
                        Message(
                            uuid = packet.uuid,
                            receivedTime = packet.received_time,
                            node = node,
                            text = packet.data.text.orEmpty(),
                            fromLocal = isFromLocal,
                            meshTime = packet.data.time,
                            time =
                            org.meshtastic.core.model.util.getShortDateTime(
                                packet.data.time.takeIf { it > 0 } ?: packet.received_time,
                            ),
                            snr = packet.snr,
                            rssi = packet.rssi,
                            hopsAway = packet.hopsAway,
                            read = packet.read,
                            status = packet.data.status,
                            routingError = packet.routingError,
                            packetId = packet.packetId,
                            emojis = emptyList(),
                            replyId = packet.data.replyId,
                        )
                    },
                )
            }
        }
    }

    override suspend fun setPinnedMessage(uuid: Long, pinned: Boolean) =
        withContext(dispatchers.io) { dbManager.currentDb.value.packetDao().setPinnedMessage(uuid, pinned) }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getPinnedMessages(contactKey: String, getNode: suspend (String?) -> Node): Flow<List<Message>> {
        val dao = dbManager.currentDb.value.packetDao()
        return dao.getPinnedMessages(contactKey).mapLatest { packets ->
            val cachedGetNode = memoize(getNode)
            val replyIds = packets.mapNotNull { it.packet.data.replyId?.takeIf { id -> id != 0 } }.distinct()
            val replyMap = batchGetReplyParents(replyIds, contactKey)
            packets.map { packet ->
                val message = packet.toMessage(cachedGetNode)
                val replyId = message.replyId?.takeIf { it != 0 }
                val originalMessage = replyId?.let { replyMap[it] }?.toMessage(cachedGetNode)
                if (originalMessage != null) message.copy(originalMessage = originalMessage) else message
            }
        }
    }

    /**
     * Sanitizes a user query for FTS5 by wrapping each token in double quotes. This escapes FTS5 special characters (*,
     * -, NEAR, etc.) while still allowing multi-word searches as implicit AND queries.
     */
    private fun sanitizeFtsQuery(query: String): String =
        query.split("\\s+".toRegex()).filter { it.isNotBlank() }.joinToString(" ") { "\"${it.replace("\"", "")}\"" }

    private fun packetMatchesType(portNum: Int, types: Set<BackupPacketType>): Boolean = when (portNum) {
        PortNum.TEXT_MESSAGE_APP.value -> BackupPacketType.MESSAGES in types
        PortNum.WAYPOINT_APP.value -> BackupPacketType.WAYPOINTS in types
        PortNum.TELEMETRY_APP.value -> BackupPacketType.TELEMETRY in types
        PortNum.POSITION_APP.value -> BackupPacketType.POSITIONS in types
        PortNum.NODEINFO_APP.value -> BackupPacketType.NODE_INFO in types
        PortNum.TRACEROUTE_APP.value -> BackupPacketType.TRACEROUTE in types
        PortNum.PRIVATE_APP.value -> BackupPacketType.PRIVATE_APP in types
        else -> BackupPacketType.OTHER in types
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override suspend fun exportMessagesToJson(sink: BufferedSink, types: Set<BackupPacketType>): Int =
        withContext(dispatchers.io) {
            val shouldExportLogs =
                BackupPacketType.TELEMETRY in types ||
                    BackupPacketType.POSITIONS in types ||
                    BackupPacketType.NODE_INFO in types ||
                    BackupPacketType.TRACEROUTE in types ||
                    BackupPacketType.OTHER in types

            var packets: List<RoomPacket> = emptyList()
            var reactions: List<RoomReaction> = emptyList()
            var contactSettings: List<ContactSettingsEntity> = emptyList()
            var meshLogs: List<org.meshtastic.core.database.entity.MeshLog> = emptyList()
            var myNodeNum = 0

            dbManager.withReadDb { db ->
                val pDao = db.packetDao()
                packets = pDao.getAllPacketsSnapshot()
                reactions = pDao.getAllReactionsSnapshot()
                contactSettings = pDao.getAllContactSettingsSnapshot()
                if (shouldExportLogs) {
                    meshLogs = db.meshLogDao().getAllLogsSnapshot()
                }
                myNodeNum = db.nodeInfoDao().getMyNodeInfo().firstOrNull()?.myNodeNum ?: 0
            }

            val filteredPackets = packets.filter { packetMatchesType(it.port_num, types) }
            val filteredReactions = if (BackupPacketType.REACTIONS in types) reactions else emptyList()
            val filteredSettings = if (BackupPacketType.CONTACT_SETTINGS in types) contactSettings else emptyList()
            val filteredLogs = meshLogs.filter { packetMatchesType(it.portNum, types) }

            val export =
                MessagesExport(
                    schemaVersion = 1,
                    exportedAt = Instant.fromEpochMilliseconds(nowMillis).toString(),
                    myNodeNum = myNodeNum.toLong(),
                    packets = filteredPackets.map { it.toExport() },
                    reactions = filteredReactions.map { it.toExport() },
                    contactSettings = filteredSettings.map { it.toExport() },
                    logs = filteredLogs.map { it.toExport() },
                )

            val json = Json {
                prettyPrint = true
                explicitNulls = false
            }
            json.encodeToBufferedSink(export, sink)
            sink.flush()
            filteredPackets.size
        }

    override suspend fun importMessagesFromJson(
        source: BufferedSource,
        types: Set<BackupPacketType>,
    ): MessageImportResult = withContext(dispatchers.io) {
        val export: MessagesExport = decodeMessagesExport(source)

        val packetsToImport = export.packets.filter { packetMatchesType(it.portNum, types) }
        val reactionsToImport = if (BackupPacketType.REACTIONS in types) export.reactions else emptyList()
        val contactSettingsToImport =
            if (BackupPacketType.CONTACT_SETTINGS in types) export.contactSettings else emptyList()
        val logsToImport = export.logs.filter { packetMatchesType(it.portNum, types) }

        dbManager.withDb { db ->
            val pDao = db.packetDao()
            val mLogDao = db.meshLogDao()
            val currentMyNodeNum = db.nodeInfoDao().getMyNodeInfo().firstOrNull()?.myNodeNum ?: 0
            val defaultMyNodeNum = export.myNodeNum?.toInt()?.takeIf { it != 0 } ?: currentMyNodeNum

            val existingPackets = pDao.getAllPacketsSnapshot()
            val existingFingerprints = existingPackets.mapTo(HashSet(existingPackets.size)) { it.fingerprint() }

            var importedCount = 0
            var skippedCount = 0
            val toInsert = mutableListOf<RoomPacket>()

            for (packetDto in packetsToImport) {
                val fp = packetDto.fingerprint()
                if (fp in existingFingerprints) {
                    skippedCount++
                } else {
                    toInsert.add(packetDto.toPacket(defaultMyNodeNum = defaultMyNodeNum))
                    existingFingerprints.add(fp)
                    importedCount++
                }
            }

            pDao.importPacketsAndReactions(
                packetsToImport = toInsert,
                reactionsToImport = reactionsToImport.map { it.toReactionEntity() },
                contactSettingsToImport = contactSettingsToImport.map { it.toContactSettings() },
            )

            if (logsToImport.isNotEmpty()) {
                val meshLogs = logsToImport.map { it.toMeshLog() }
                for (chunk in meshLogs.chunked(BATCH_CHUNK_SIZE)) {
                    mLogDao.insertIgnore(chunk)
                }
            }

            if (defaultMyNodeNum != 0) {
                pDao.updateZeroMyNodeNum(defaultMyNodeNum)
            }

            if (importedCount > 0) {
                pDao.rebuildFtsIndex()
            }

            MessageImportResult(
                importedPackets = importedCount,
                skippedPackets = skippedCount,
                importedReactions = reactionsToImport.size,
                totalPackets = export.packets.size,
            )
        } ?: MessageImportResult(0, 0, 0, 0)
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun decodeMessagesExport(source: BufferedSource): MessagesExport {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        if (source.rangeEquals(0, UTF8_BOM)) {
            source.skip(UTF8_BOM_SIZE)
        }
        val peeked = source.peek()
        if (peeked.rangeEquals(0, UTF8_BOM)) {
            peeked.skip(UTF8_BOM_SIZE)
        }
        peeked.request(PEEK_SAMPLE_SIZE_BYTES)
        val sample = peeked.readUtf8(peeked.buffer.size).trim().trim('\uFEFF')
        return if (sample.startsWith("[")) {
            val packetList: List<org.meshtastic.core.data.model.PacketExport> = json.decodeFromBufferedSource(source)
            MessagesExport(exportedAt = "", packets = packetList)
        } else {
            json.decodeFromBufferedSource(source)
        }
    }

    @Suppress(
        "detekt:CyclomaticComplexMethod",
        "detekt:LongMethod",
        "detekt:NestedBlockDepth",
        "detekt:LoopWithTooManyJumpStatements",
        "LoopWithTooManyJumpStatements",
        "MagicNumber",
        "detekt:MagicNumber",
    )
    override suspend fun importMessagesFromCsv(
        source: BufferedSource,
        types: Set<BackupPacketType>,
    ): MessageImportResult = withContext(dispatchers.io) {
        if (source.rangeEquals(0, UTF8_BOM)) {
            source.skip(UTF8_BOM_SIZE)
        }
        dbManager.withDb { db ->
            val pDao = db.packetDao()
            val currentMyNodeNum = db.nodeInfoDao().getMyNodeInfo().firstOrNull()?.myNodeNum ?: 0
            val existingPackets = pDao.getAllPacketsSnapshot()
            val existingFingerprints = existingPackets.mapTo(HashSet(existingPackets.size)) { it.fingerprint() }

            var importedCount = 0
            var skippedCount = 0
            var totalPacketsInCsv = 0
            val toInsert = mutableListOf<RoomPacket>()
            val reactionsToInsert = mutableListOf<RoomReaction>()

            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val tokens = parseCsvLine(line)
                if (tokens.size <= CSV_COL_PAYLOAD) continue

                if (
                    tokens[CSV_COL_DATE].contains("date", ignoreCase = true) &&
                    tokens[CSV_COL_PAYLOAD].contains("payload", ignoreCase = true)
                ) {
                    continue
                }

                val payload = tokens[CSV_COL_PAYLOAD].trim()
                if (
                    payload.isEmpty() ||
                    (payload.startsWith("<") && payload.endsWith(">")) ||
                    payload.endsWith("encrypted bytes")
                ) {
                    continue
                }

                totalPacketsInCsv++
                val isReaction = payload.startsWith("reaction_for_id:")
                val isWaypoint = payload.startsWith("waypoint:")

                val fromHex = tokens[CSV_COL_FROM]
                val fromNodeNum = fromHex.toLongOrNull() ?: fromHex.toLongOrNull(HEX_RADIX) ?: 0L
                val fromAddress = "!${fromNodeNum.toUInt().toString(HEX_RADIX).padStart(8, '0')}"
                val dateStr = tokens[CSV_COL_DATE]
                val timeStr = if (tokens.size > CSV_COL_TIME) tokens[CSV_COL_TIME] else ""
                val timestamp = parseDateTimeToMillis(dateStr, timeStr)
                val snr = if (tokens.size > CSV_COL_SNR) tokens[CSV_COL_SNR].toFloatOrNull() else null
                val hopLimit =
                    if (tokens.size > CSV_COL_HOP_LIMIT) tokens[CSV_COL_HOP_LIMIT].toIntOrNull() ?: 0 else 0
                val hopStart =
                    if (tokens.size > CSV_COL_HOP_START) tokens[CSV_COL_HOP_START].toIntOrNull() ?: 0 else 0
                val relayNode =
                    if (tokens.size > CSV_COL_RELAY_NODE) {
                        tokens[CSV_COL_RELAY_NODE].toIntOrNull(HEX_RADIX)
                    } else {
                        null
                    }

                val hopsAway =
                    if (hopStart > 0 && hopLimit >= 0 && hopStart >= hopLimit) hopStart - hopLimit else -1
                val deterministicPacketId = (timestamp.hashCode() xor fromAddress.hashCode() xor payload.hashCode())

                if (isReaction) {
                    if (BackupPacketType.REACTIONS in types) {
                        val reactionChar = payload.substringAfter("reaction_for_id:").substringAfter(':')
                        val reactionEntity =
                            RoomReaction(
                                myNodeNum = currentMyNodeNum,
                                replyId = deterministicPacketId,
                                userId = fromAddress,
                                emoji = reactionChar,
                                timestamp = timestamp,
                                snr = snr,
                                hopsAway = hopsAway,
                                packetId = deterministicPacketId,
                                relayNode = relayNode,
                                to = "^all",
                            )
                        reactionsToInsert.add(reactionEntity)
                    }
                } else {
                    val port = if (isWaypoint) PortNum.WAYPOINT_APP.value else PortNum.TEXT_MESSAGE_APP.value
                    if (packetMatchesType(port, types)) {
                        val dataPacket =
                            DataPacket(
                                to = "^all",
                                bytes = payload.encodeToByteArray().toByteString(),
                                dataType = port,
                                from = fromAddress,
                                time = timestamp,
                                id = deterministicPacketId,
                                status = MessageStatus.RECEIVED,
                                hopLimit = hopLimit,
                                hopStart = hopStart,
                                snr = snr,
                                relayNode = relayNode,
                            )
                        val packet =
                            RoomPacket(
                                uuid = 0L,
                                myNodeNum = currentMyNodeNum,
                                port_num = dataPacket.dataType,
                                contact_key = "0^all",
                                received_time = timestamp,
                                read = true,
                                data = dataPacket,
                                packetId = deterministicPacketId,
                                routingError = -1,
                                snr = snr,
                                rssi = null,
                                hopsAway = hopsAway,
                                sfpp_hash = null,
                                filtered = false,
                                messageText = payload,
                                translatedText = null,
                                showTranslated = false,
                                pinnedMessage = false,
                            )
                        val fp = packet.fingerprint()
                        if (fp in existingFingerprints) {
                            skippedCount++
                        } else {
                            toInsert.add(packet)
                            existingFingerprints.add(fp)
                            importedCount++
                        }
                    }
                }
            }

            if (toInsert.isNotEmpty() || reactionsToInsert.isNotEmpty()) {
                pDao.importPacketsAndReactions(
                    packetsToImport = toInsert,
                    reactionsToImport = reactionsToInsert,
                    contactSettingsToImport = emptyList<ContactSettingsEntity>(),
                )
            }

            if (currentMyNodeNum != 0) {
                pDao.updateZeroMyNodeNum(currentMyNodeNum)
            }

            if (importedCount > 0) {
                pDao.rebuildFtsIndex()
            }

            MessageImportResult(
                importedPackets = importedCount,
                skippedPackets = skippedCount,
                importedReactions = reactionsToInsert.size,
                totalPackets = totalPacketsInCsv,
            )
        } ?: MessageImportResult(0, 0, 0, 0)
    }

    companion object {
        private const val BATCH_CHUNK_SIZE = 50
        private const val CONTACTS_PAGE_SIZE = 30
        private const val MESSAGES_PAGE_SIZE = 50
        private const val CSV_COL_DATE = 0
        private const val CSV_COL_TIME = 1
        private const val CSV_COL_FROM = 2
        private const val CSV_COL_SNR = 9
        private const val CSV_COL_HOP_LIMIT = 11
        private const val CSV_COL_HOP_START = 12
        private const val CSV_COL_RELAY_NODE = 13
        private const val CSV_COL_PAYLOAD = 14
        private const val HEX_RADIX = 16
        private const val MILLIS_PER_SECOND = 1000L
        private const val UTF8_BOM_SIZE = 3L
        private const val PEEK_SAMPLE_SIZE_BYTES = 1024L
        private val UTF8_BOM = okio.ByteString.of(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}

private fun parseCsvLine(line: String): List<String> {
    val tokens = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        if (inQuotes) {
            if (c == '"') {
                if (i + 1 < line.length && line[i + 1] == '"') {
                    sb.append('"')
                    i++
                } else {
                    inQuotes = false
                }
            } else {
                sb.append(c)
            }
        } else {
            when (c) {
                '"' -> inQuotes = true

                ',' -> {
                    tokens.add(sb.toString().trim())
                    sb.clear()
                }

                else -> sb.append(c)
            }
        }
        i++
    }
    tokens.add(sb.toString().trim())
    return tokens
}

@Suppress("detekt:TooGenericExceptionCaught", "detekt:SwallowedException", "MagicNumber")
private fun parseDateTimeToMillis(dateStr: String, timeStr: String): Long {
    try {
        val dateParts = dateStr.split("-")
        val timeParts = timeStr.split(":")
        if (dateParts.size == 3 && timeParts.size >= 2) {
            val year = dateParts[0].toInt()
            val month = dateParts[1].toInt()
            val day = dateParts[2].toInt()
            val hour = timeParts[0].toInt()
            val min = timeParts[1].toInt()
            val sec = timeParts[2].substringBefore('.').toInt()
            val y = if (month <= 2) year - 1 else year
            val m = if (month <= 2) month + 9 else month - 3
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val doy = (153 * m + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            val days = (era * 146097 + doe - 719468).toLong()
            val seconds = days * 86400L + hour * 3600L + min * 60L + sec
            return seconds * 1000L
        }
    } catch (_: Exception) {}
    return nowMillis
}
