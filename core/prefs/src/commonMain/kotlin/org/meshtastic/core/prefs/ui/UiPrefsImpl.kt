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
package org.meshtastic.core.prefs.ui

import org.meshtastic.core.prefs.store.floatPrefsKey
import kotlinx.atomicfu.atomic
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.model.CustomNodeName
import org.meshtastic.core.model.DeviceType
import org.meshtastic.core.model.ImgbbExpiration
import org.meshtastic.core.model.MeshpicRetention
import org.meshtastic.core.model.PhotoHostingProvider
import org.meshtastic.core.model.ReactionNotificationMode
import org.meshtastic.core.prefs.cachedFlow
import org.meshtastic.core.prefs.di.UiDataStore
import org.meshtastic.core.prefs.store.PrefsSnapshot
import org.meshtastic.core.prefs.store.booleanPrefsKey
import org.meshtastic.core.prefs.store.intPrefsKey
import org.meshtastic.core.prefs.store.stringPrefsKey
import org.meshtastic.core.prefs.store.stringSetPrefsKey
import org.meshtastic.core.repository.NodeFilterPrefs
import org.meshtastic.core.repository.UiPrefs

@Single
@Suppress("TooManyFunctions")
class UiPrefsImpl(private val dataStore: UiDataStore, dispatchers: CoroutineDispatchers) : UiPrefs {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    // Maps nodeNum to a flow for the for the "provide-location-nodeNum" pref
    private val provideNodeLocationFlows = atomic(persistentMapOf<Int, Lazy<StateFlow<Boolean>>>())

    override val appIntroCompleted: StateFlow<Boolean> =
        dataStore.data.map { it[KEY_APP_INTRO_COMPLETED] ?: false }.stateIn(scope, SharingStarted.Eagerly, false)

    override fun setAppIntroCompleted(completed: Boolean) {
        scope.launch { dataStore.edit { it[KEY_APP_INTRO_COMPLETED] = completed } }
    }

    override val theme: StateFlow<Int> =
        dataStore.data.map { it[KEY_THEME] ?: -1 }.stateIn(scope, SharingStarted.Lazily, -1)

    override fun setTheme(value: Int) {
        scope.launch { dataStore.edit { it[KEY_THEME] = value } }
    }

    // Eagerly, unlike most prefs here: LocaleUnitsProvider folds this into every unit the app renders, so the window
    // in which a fresh process shows OS units to a user who overrode them should close as early as possible.
    override val unitsOverride: StateFlow<Int> =
        dataStore.data.map { it[KEY_UNITS_OVERRIDE] ?: 0 }.stateIn(scope, SharingStarted.Eagerly, 0)

    override fun setUnitsOverride(value: Int) {
        scope.launch { dataStore.edit { it[KEY_UNITS_OVERRIDE] = value } }
    }

    override val locale: StateFlow<String> =
        dataStore.data.map { it[KEY_LOCALE] ?: "" }.stateIn(scope, SharingStarted.Eagerly, "")

    override fun setLocale(languageTag: String) {
        scope.launch { dataStore.edit { it[KEY_LOCALE] = languageTag } }
    }

    override val nodeSort: StateFlow<Int> =
        dataStore.data.map { it[KEY_NODE_SORT] ?: -1 }.stateIn(scope, SharingStarted.Lazily, -1)

    override fun setNodeSort(value: Int) {
        scope.launch { dataStore.edit { it[KEY_NODE_SORT] = value } }
    }

    // Defaults on so nodes heard before their NodeInfo arrives stay visible and messageable (design#16).
    override val nodeFilters: StateFlow<NodeFilterPrefs> =
        dataStore.data.map { it.toNodeFilterPrefs() }.stateIn(scope, SharingStarted.Lazily, NodeFilterPrefs())

    override fun updateNodeFilters(transform: (NodeFilterPrefs) -> NodeFilterPrefs) {
        // Read-modify-write inside edit{}, which is transactional — the same lost-update guard updateHiddenLayerUrls
        // uses. Writing every key rather than the changed one keeps this a single pure transform.
        scope.launch { dataStore.edit { it.writeNodeFilterPrefs(transform(it.toNodeFilterPrefs())) } }
    }

    override val hasShownNotPairedWarning: StateFlow<Boolean> =
        dataStore.data
            .map { it[KEY_HAS_SHOWN_NOT_PAIRED_WARNING_PREF] ?: false }
            .stateIn(scope, SharingStarted.Eagerly, false)

    override fun setHasShownNotPairedWarning(shown: Boolean) {
        scope.launch { dataStore.edit { it[KEY_HAS_SHOWN_NOT_PAIRED_WARNING_PREF] = shown } }
    }

    override val showQuickChat: StateFlow<Boolean> =
        dataStore.data
            .map { it[KEY_SHOW_QUICK_CHAT_PREF] ?: SHOW_QUICK_CHAT_BY_DEFAULT }
            .stateIn(scope, SharingStarted.Eagerly, SHOW_QUICK_CHAT_BY_DEFAULT)

    override fun toggleShowQuickChat() {
        scope.launch {
            dataStore.edit {
                it[KEY_SHOW_QUICK_CHAT_PREF] = !(it[KEY_SHOW_QUICK_CHAT_PREF] ?: SHOW_QUICK_CHAT_BY_DEFAULT)
            }
        }
    }

    override val showFullMessageTimestamps: StateFlow<Boolean> =
        dataStore.data
            .map { it[KEY_SHOW_FULL_MESSAGE_TIMESTAMPS] ?: false }
            .stateIn(scope, SharingStarted.Eagerly, false)

    override fun setShowFullMessageTimestamps(show: Boolean) {
        scope.launch { dataStore.edit { it[KEY_SHOW_FULL_MESSAGE_TIMESTAMPS] = show } }
    }

    override val textCompressionEnabled: StateFlow<Boolean> =
        dataStore.data.map { it[KEY_TEXT_COMPRESSION_ENABLED] ?: false }.stateIn(scope, SharingStarted.Eagerly, false)

    override fun setTextCompressionEnabled(enabled: Boolean) {
        scope.launch { dataStore.edit { it[KEY_TEXT_COMPRESSION_ENABLED] = enabled } }
    }

    override val pixelArtEnabled: StateFlow<Boolean> =
        dataStore.data.map { it[KEY_PIXEL_ART_ENABLED] ?: true }.stateIn(scope, SharingStarted.Eagerly, true)

    override fun setPixelArtEnabled(enabled: Boolean) {
        scope.launch { dataStore.edit { it[KEY_PIXEL_ART_ENABLED] = enabled } }
    }

    override val fileTransferEnabled: StateFlow<Boolean> =
        dataStore.data.map { it[KEY_FILE_TRANSFER_ENABLED] ?: false }.stateIn(scope, SharingStarted.Eagerly, false)

    override fun setFileTransferEnabled(enabled: Boolean) {
        scope.launch { dataStore.edit { it[KEY_FILE_TRANSFER_ENABLED] = enabled } }
    }

    override val photoHostingProvider: StateFlow<PhotoHostingProvider> =
        dataStore.data
            .map { prefs ->
                val providerStr = prefs[KEY_PHOTO_HOSTING_PROVIDER]
                if (providerStr != null) {
                    PhotoHostingProvider.fromId(providerStr)
                } else {
                    val legacy = prefs[KEY_PHOTO_HOSTING_ENABLED]
                    if (legacy == false) PhotoHostingProvider.DISABLED else PhotoHostingProvider.MESHPIC
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, PhotoHostingProvider.MESHPIC)

    override fun setPhotoHostingProvider(provider: PhotoHostingProvider) {
        scope.launch {
            dataStore.edit {
                it[KEY_PHOTO_HOSTING_PROVIDER] = provider.id
                it[KEY_PHOTO_HOSTING_ENABLED] = provider.isEnabled
            }
        }
    }

    override val photoHostingEnabled: StateFlow<Boolean> =
        dataStore.data
            .map { prefs ->
                val providerStr = prefs[KEY_PHOTO_HOSTING_PROVIDER]
                if (providerStr != null) {
                    PhotoHostingProvider.fromId(providerStr).isEnabled
                } else {
                    prefs[KEY_PHOTO_HOSTING_ENABLED] ?: true
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, true)

    override fun setPhotoHostingEnabled(enabled: Boolean) {
        setPhotoHostingProvider(if (enabled) PhotoHostingProvider.MESHPIC else PhotoHostingProvider.DISABLED)
    }

    override val insertPhotoLinkEnabled: StateFlow<Boolean> =
        dataStore.data.map { it[KEY_INSERT_PHOTO_LINK_ENABLED] ?: true }.stateIn(scope, SharingStarted.Eagerly, true)

    override fun setInsertPhotoLinkEnabled(enabled: Boolean) {
        scope.launch { dataStore.edit { it[KEY_INSERT_PHOTO_LINK_ENABLED] = enabled } }
    }

    override val imgbbApiKey: StateFlow<String> =
        dataStore.data.map { it[KEY_IMGBB_API_KEY] ?: "" }.stateIn(scope, SharingStarted.Eagerly, "")

    override fun setImgbbApiKey(apiKey: String) {
        scope.launch { dataStore.edit { it[KEY_IMGBB_API_KEY] = apiKey } }
    }

    override val imgbbExpiration: StateFlow<ImgbbExpiration> =
        dataStore.data
            .map { prefs -> ImgbbExpiration.fromId(prefs[KEY_IMGBB_EXPIRATION]) }
            .stateIn(scope, SharingStarted.Eagerly, ImgbbExpiration.DAYS_30)

    override fun setImgbbExpiration(expiration: ImgbbExpiration) {
        scope.launch { dataStore.edit { it[KEY_IMGBB_EXPIRATION] = expiration.id } }
    }

    override val meshpicRetention: StateFlow<MeshpicRetention> =
        dataStore.data
            .map { prefs -> MeshpicRetention.fromId(prefs[KEY_MESHPIC_RETENTION]) }
            .stateIn(scope, SharingStarted.Eagerly, MeshpicRetention.DAYS_1)

    override fun setMeshpicRetention(retention: MeshpicRetention) {
        scope.launch { dataStore.edit { it[KEY_MESHPIC_RETENTION] = retention.id } }
    }

    override val linkPreviewEnabled: StateFlow<Boolean> =
        dataStore.data.map { it[KEY_LINK_PREVIEW_ENABLED] ?: true }.stateIn(scope, SharingStarted.Eagerly, true)

    override fun setLinkPreviewEnabled(enabled: Boolean) {
        scope.launch { dataStore.edit { it[KEY_LINK_PREVIEW_ENABLED] = enabled } }
    }

    override val showBellButton: StateFlow<Boolean> =
        dataStore.data.map { it[KEY_SHOW_BELL_BUTTON] ?: true }.stateIn(scope, SharingStarted.Eagerly, true)

    override fun setShowBellButton(show: Boolean) {
        scope.launch { dataStore.edit { it[KEY_SHOW_BELL_BUTTON] = show } }
    }

    override val reactionNotificationMode: StateFlow<ReactionNotificationMode> =
        dataStore.data
            .map { prefs -> ReactionNotificationMode.fromId(prefs[KEY_REACTION_NOTIFICATION_MODE]) }
            .stateIn(scope, SharingStarted.Eagerly, ReactionNotificationMode.ALL)

    override fun setReactionNotificationMode(mode: ReactionNotificationMode) {
        scope.launch { dataStore.edit { it[KEY_REACTION_NOTIFICATION_MODE] = mode.id } }
    }

    override val pinnedMessagesEnabled: StateFlow<Boolean> =
        dataStore.data.map { it[KEY_PINNED_MESSAGES_ENABLED] ?: true }.stateIn(scope, SharingStarted.Eagerly, true)

    override fun setPinnedMessagesEnabled(enabled: Boolean) {
        scope.launch { dataStore.edit { it[KEY_PINNED_MESSAGES_ENABLED] = enabled } }
    }

    override val skippedAppUpdateVersion: StateFlow<String?> =
        dataStore.data.map { it[KEY_SKIPPED_APP_UPDATE_VERSION] }.stateIn(scope, SharingStarted.Eagerly, null)

    override fun setSkippedAppUpdateVersion(version: String?) {
        scope.launch {
            dataStore.edit {
                if (version == null) {
                    it.remove(KEY_SKIPPED_APP_UPDATE_VERSION)
                } else {
                    it[KEY_SKIPPED_APP_UPDATE_VERSION] = version
                }
            }
        }
    }

    override val advThemeColorsJson: StateFlow<String> =
        dataStore.data.map { it[KEY_ADV_THEME_COLORS_JSON] ?: "" }.stateIn(scope, SharingStarted.Eagerly, "")

    override fun setAdvThemeColorsJson(json: String) {
        scope.launch { dataStore.edit { it[KEY_ADV_THEME_COLORS_JSON] = json } }
    }

    override val customNodeNames: StateFlow<Map<Int, CustomNodeName>> =
        dataStore.data
            .map { prefs ->
                val json = prefs[KEY_CUSTOM_NODE_NAMES]
                if (json.isNullOrBlank()) {
                    emptyMap()
                } else {
                    try {
                        Json.decodeFromString<Map<Int, CustomNodeName>>(json)
                    } catch (_: Exception) {
                        emptyMap()
                    }
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override fun setCustomNodeName(nodeNum: Int, customName: CustomNodeName) {
        scope.launch {
            dataStore.edit { prefs ->
                val current =
                    try {
                        prefs[KEY_CUSTOM_NODE_NAMES]
                            ?.takeIf { it.isNotBlank() }
                            ?.let { Json.decodeFromString<Map<Int, CustomNodeName>>(it) } ?: emptyMap()
                    } catch (_: Exception) {
                        emptyMap()
                    }
                val updated = current + (nodeNum to customName)
                prefs[KEY_CUSTOM_NODE_NAMES] = Json.encodeToString(updated)
            }
        }
    }

    override fun removeCustomNodeName(nodeNum: Int) {
        scope.launch {
            dataStore.edit { prefs ->
                val current =
                    try {
                        prefs[KEY_CUSTOM_NODE_NAMES]
                            ?.takeIf { it.isNotBlank() }
                            ?.let { Json.decodeFromString<Map<Int, CustomNodeName>>(it) } ?: emptyMap()
                    } catch (_: Exception) {
                        emptyMap()
                    }
                val updated = current - nodeNum
                prefs[KEY_CUSTOM_NODE_NAMES] = Json.encodeToString(updated)
            }
        }
    }

    override val messageBubbleSpacing: StateFlow<Int> =
        dataStore.data.map { it[KEY_MESSAGE_BUBBLE_SPACING] ?: 2 }.stateIn(scope, SharingStarted.Eagerly, 2)

    override fun setMessageBubbleSpacing(spacing: Int) {
        scope.launch { dataStore.edit { it[KEY_MESSAGE_BUBBLE_SPACING] = spacing } }
    }

    override val messageBubblePadding: StateFlow<Int> =
        dataStore.data.map { it[KEY_MESSAGE_BUBBLE_PADDING] ?: 8 }.stateIn(scope, SharingStarted.Eagerly, 8)

    override fun setMessageBubblePadding(padding: Int) {
        scope.launch { dataStore.edit { it[KEY_MESSAGE_BUBBLE_PADDING] = padding } }
    }

    override val messageBubbleCornerRadius: StateFlow<Int> =
        dataStore.data.map { it[KEY_MESSAGE_BUBBLE_CORNER_RADIUS] ?: 16 }.stateIn(scope, SharingStarted.Eagerly, 16)

    override fun setMessageBubbleCornerRadius(radius: Int) {
        scope.launch { dataStore.edit { it[KEY_MESSAGE_BUBBLE_CORNER_RADIUS] = radius } }
    }

    override val messageFontSizeScale: StateFlow<Float> =
        dataStore.data.map { it[KEY_MESSAGE_FONT_SIZE_SCALE] ?: 1.0f }.stateIn(scope, SharingStarted.Eagerly, 1.0f)

    override fun setMessageFontSizeScale(scale: Float) {
        scope.launch { dataStore.edit { it[KEY_MESSAGE_FONT_SIZE_SCALE] = scale } }
    }

    override val reactionChipSpacing: StateFlow<Int> =
        dataStore.data.map { it[KEY_REACTION_CHIP_SPACING] ?: 4 }.stateIn(scope, SharingStarted.Eagerly, 4)

    override fun setReactionChipSpacing(spacing: Int) {
        scope.launch { dataStore.edit { it[KEY_REACTION_CHIP_SPACING] = spacing } }
    }

    override val pressureInMmHg: StateFlow<Boolean> =
        dataStore.data.map { it[KEY_PRESSURE_IN_MMHG] ?: false }.stateIn(scope, SharingStarted.Eagerly, false)

    override fun setPressureInMmHg(enabled: Boolean) {
        scope.launch { dataStore.edit { it[KEY_PRESSURE_IN_MMHG] = enabled } }
    }

    override val autoTopologyDiscoveryEnabled: StateFlow<Boolean> =
        dataStore.data
            .map { it[KEY_AUTO_TOPOLOGY_DISCOVERY_ENABLED] ?: false }
            .stateIn(scope, SharingStarted.Eagerly, false)

    override fun setAutoTopologyDiscoveryEnabled(enabled: Boolean) {
        scope.launch { dataStore.edit { it[KEY_AUTO_TOPOLOGY_DISCOVERY_ENABLED] = enabled } }
    }

    override val autoTopologyDiscoveryIntervalMinutes: StateFlow<Int> =
        dataStore.data
            .map {
                it[KEY_AUTO_TOPOLOGY_DISCOVERY_INTERVAL_MINUTES] ?: DEFAULT_AUTO_TOPOLOGY_DISCOVERY_INTERVAL_MINUTES
            }
            .stateIn(scope, SharingStarted.Eagerly, DEFAULT_AUTO_TOPOLOGY_DISCOVERY_INTERVAL_MINUTES)

    override fun setAutoTopologyDiscoveryIntervalMinutes(minutes: Int) {
        scope.launch { dataStore.edit { it[KEY_AUTO_TOPOLOGY_DISCOVERY_INTERVAL_MINUTES] = minutes } }
    }

    override val radioSetupOfferedNodes: StateFlow<Set<Int>> =
        dataStore.data
            .map { prefs -> prefs[KEY_RADIO_SETUP_OFFERED].orEmpty().mapNotNull { it.toIntOrNull() }.toSet() }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    override fun markRadioSetupOffered(nodeNum: Int) {
        scope.launch {
            dataStore.edit { it[KEY_RADIO_SETUP_OFFERED] = it[KEY_RADIO_SETUP_OFFERED].orEmpty() + nodeNum.toString() }
        }
    }

    override val eventThemeEnabled: StateFlow<Boolean> =
        dataStore.data.map { it[KEY_EVENT_THEME_ENABLED] ?: true }.stateIn(scope, SharingStarted.Eagerly, true)

    override fun setEventThemeEnabled(enabled: Boolean) {
        scope.launch { dataStore.edit { it[KEY_EVENT_THEME_ENABLED] = enabled } }
    }

    override val bleAutoScan: StateFlow<Boolean> =
        dataStore.data.map { it[KEY_BLE_AUTO_SCAN] ?: false }.stateIn(scope, SharingStarted.Eagerly, false)

    override fun setBleAutoScan(enabled: Boolean) {
        scope.launch { dataStore.edit { it[KEY_BLE_AUTO_SCAN] = enabled } }
    }

    override val networkAutoScan: StateFlow<Boolean> =
        dataStore.data.map { it[KEY_NETWORK_AUTO_SCAN] ?: false }.stateIn(scope, SharingStarted.Eagerly, false)

    override fun setNetworkAutoScan(enabled: Boolean) {
        scope.launch { dataStore.edit { it[KEY_NETWORK_AUTO_SCAN] = enabled } }
    }

    override val selectedConnectionTransport: StateFlow<DeviceType?> =
        dataStore.data
            .map { preferences ->
                preferences[KEY_SELECTED_CONNECTION_TRANSPORT]?.let(::parseDeviceType)
                    ?: legacySelectedConnectionTransport(preferences)
            }
            .stateIn(scope, SharingStarted.Eagerly, null)

    override fun setSelectedConnectionTransport(type: DeviceType) {
        scope.launch { dataStore.edit { it[KEY_SELECTED_CONNECTION_TRANSPORT] = type.name } }
    }

    override val firmwareUpdateNotificationKeys: StateFlow<Set<String>> =
        dataStore.data
            .map { preferences ->
                preferences[KEY_FIRMWARE_UPDATE_NOTIFICATION_KEYS]?.split('|')?.filter(String::isNotBlank)?.toSet()
                    ?: emptySet()
            }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    override fun recordFirmwareUpdateNotificationKey(key: String) {
        scope.launch {
            dataStore.edit { preferences ->
                val keys =
                    preferences[KEY_FIRMWARE_UPDATE_NOTIFICATION_KEYS]
                        ?.split('|')
                        ?.filter(String::isNotBlank)
                        ?.toMutableList() ?: mutableListOf()
                keys.remove(key)
                keys.add(key)
                preferences[KEY_FIRMWARE_UPDATE_NOTIFICATION_KEYS] =
                    keys.takeLast(MAX_FIRMWARE_UPDATE_NOTIFICATION_KEYS).joinToString("|")
            }
        }
    }

    override val keptUnheardNodes: StateFlow<Set<Int>> =
        dataStore.data
            .map { preferences -> parseNodeNums(preferences[KEY_KEPT_UNHEARD_NODES]).toSet() }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    override fun keepUnheardNodes(nodeNums: Set<Int>) {
        scope.launch {
            dataStore.edit { preferences ->
                val kept = parseNodeNums(preferences[KEY_KEPT_UNHEARD_NODES]) - nodeNums + nodeNums
                preferences[KEY_KEPT_UNHEARD_NODES] = kept.takeLast(MAX_KEPT_UNHEARD_NODES).joinToString(",")
            }
        }
    }

    override fun forgetKeptUnheardNodes(nodeNums: Set<Int>) {
        scope.launch {
            dataStore.edit { preferences ->
                val kept = parseNodeNums(preferences[KEY_KEPT_UNHEARD_NODES]) - nodeNums
                preferences[KEY_KEPT_UNHEARD_NODES] = kept.joinToString(",")
            }
        }
    }

    override fun shouldProvideNodeLocation(nodeNum: Int): StateFlow<Boolean> =
        cachedFlow(provideNodeLocationFlows, nodeNum) {
            val key = booleanPrefsKey(provideLocationKey(nodeNum))
            dataStore.data.map { it[key] ?: false }.stateIn(scope, SharingStarted.Eagerly, false)
        }

    override fun setShouldProvideNodeLocation(nodeNum: Int, provide: Boolean) {
        scope.launch { dataStore.edit { it[booleanPrefsKey(provideLocationKey(nodeNum))] = provide } }
    }

    private fun provideLocationKey(nodeNum: Int) = "provide-location-$nodeNum"

    // Node list layout preferences

    override val nodeListDensity: StateFlow<String> =
        dataStore.data
            .map { it[NodeListLayoutPreferences.KEY_DENSITY] ?: NodeListLayoutPreferences.DEFAULT_DENSITY }
            .stateIn(scope, SharingStarted.Eagerly, NodeListLayoutPreferences.DEFAULT_DENSITY)

    override fun setNodeListDensity(value: String) {
        scope.launch { dataStore.edit { it[NodeListLayoutPreferences.KEY_DENSITY] = value } }
    }

    override val shouldShowPower: StateFlow<Boolean> =
        dataStore.data
            .map { it[NodeListLayoutPreferences.KEY_SHOW_POWER] ?: true }
            .stateIn(scope, SharingStarted.Eagerly, true)

    override fun setShouldShowPower(value: Boolean) {
        scope.launch { dataStore.edit { it[NodeListLayoutPreferences.KEY_SHOW_POWER] = value } }
    }

    override val shouldShowLastHeard: StateFlow<Boolean> =
        dataStore.data
            .map { it[NodeListLayoutPreferences.KEY_SHOW_LAST_HEARD] ?: true }
            .stateIn(scope, SharingStarted.Eagerly, true)

    override fun setShouldShowLastHeard(value: Boolean) {
        scope.launch { dataStore.edit { it[NodeListLayoutPreferences.KEY_SHOW_LAST_HEARD] = value } }
    }

    override val lastHeardIsRelative: StateFlow<Boolean> =
        dataStore.data
            .map { it[NodeListLayoutPreferences.KEY_LAST_HEARD_RELATIVE] ?: false }
            .stateIn(scope, SharingStarted.Eagerly, false)

    override fun setLastHeardIsRelative(value: Boolean) {
        scope.launch { dataStore.edit { it[NodeListLayoutPreferences.KEY_LAST_HEARD_RELATIVE] = value } }
    }

    override val shouldShowLocation: StateFlow<Boolean> =
        dataStore.data
            .map { it[NodeListLayoutPreferences.KEY_SHOW_LOCATION] ?: true }
            .stateIn(scope, SharingStarted.Eagerly, true)

    override fun setShouldShowLocation(value: Boolean) {
        scope.launch { dataStore.edit { it[NodeListLayoutPreferences.KEY_SHOW_LOCATION] = value } }
    }

    override val shouldShowHops: StateFlow<Boolean> =
        dataStore.data
            .map { it[NodeListLayoutPreferences.KEY_SHOW_HOPS] ?: true }
            .stateIn(scope, SharingStarted.Eagerly, true)

    override fun setShouldShowHops(value: Boolean) {
        scope.launch { dataStore.edit { it[NodeListLayoutPreferences.KEY_SHOW_HOPS] = value } }
    }

    override val shouldShowSignal: StateFlow<Boolean> =
        dataStore.data
            .map { it[NodeListLayoutPreferences.KEY_SHOW_SIGNAL] ?: true }
            .stateIn(scope, SharingStarted.Eagerly, true)

    override fun setShouldShowSignal(value: Boolean) {
        scope.launch { dataStore.edit { it[NodeListLayoutPreferences.KEY_SHOW_SIGNAL] = value } }
    }

    override val shouldShowChannel: StateFlow<Boolean> =
        dataStore.data
            .map { it[NodeListLayoutPreferences.KEY_SHOW_CHANNEL] ?: true }
            .stateIn(scope, SharingStarted.Eagerly, true)

    override fun setShouldShowChannel(value: Boolean) {
        scope.launch { dataStore.edit { it[NodeListLayoutPreferences.KEY_SHOW_CHANNEL] = value } }
    }

    override val shouldShowRole: StateFlow<Boolean> =
        dataStore.data
            .map { it[NodeListLayoutPreferences.KEY_SHOW_ROLE] ?: true }
            .stateIn(scope, SharingStarted.Eagerly, true)

    override fun setShouldShowRole(value: Boolean) {
        scope.launch { dataStore.edit { it[NodeListLayoutPreferences.KEY_SHOW_ROLE] = value } }
    }

    override val shouldShowTelemetry: StateFlow<Boolean> =
        dataStore.data
            .map { it[NodeListLayoutPreferences.KEY_SHOW_TELEMETRY] ?: true }
            .stateIn(scope, SharingStarted.Eagerly, true)

    override fun setShouldShowTelemetry(value: Boolean) {
        scope.launch { dataStore.edit { it[NodeListLayoutPreferences.KEY_SHOW_TELEMETRY] = value } }
    }

    override val chirpyHopBestScore: StateFlow<Int> =
        dataStore.data.map { it[KEY_CHIRPY_HOP_BEST_SCORE] ?: 0 }.stateIn(scope, SharingStarted.Eagerly, 0)

    override fun recordChirpyHopScore(score: Int) {
        scope.launch {
            dataStore.edit { prefs ->
                if (score > (prefs[KEY_CHIRPY_HOP_BEST_SCORE] ?: 0)) prefs[KEY_CHIRPY_HOP_BEST_SCORE] = score
            }
        }
    }

    private fun PrefsSnapshot.toNodeFilterPrefs() = NodeFilterPrefs(
        includeUnknown = this[KEY_INCLUDE_UNKNOWN] ?: NodeFilterPrefs().includeUnknown,
        excludeInfrastructure = this[KEY_EXCLUDE_INFRASTRUCTURE] ?: false,
        onlyOnline = this[KEY_ONLY_ONLINE] ?: false,
        onlyDirect = this[KEY_ONLY_DIRECT] ?: false,
        showIgnored = this[KEY_SHOW_IGNORED] ?: false,
        onlySigned = this[KEY_ONLY_SIGNED] ?: false,
        onlyEncrypted = this[KEY_ONLY_ENCRYPTED] ?: false,
        excludeMqtt = this[KEY_EXCLUDE_MQTT] ?: false,
        excludeUnheard = this[KEY_EXCLUDE_UNHEARD] ?: false,
    )

    private fun PrefsSnapshot.Editor.writeNodeFilterPrefs(prefs: NodeFilterPrefs) {
        this[KEY_INCLUDE_UNKNOWN] = prefs.includeUnknown
        this[KEY_EXCLUDE_INFRASTRUCTURE] = prefs.excludeInfrastructure
        this[KEY_ONLY_ONLINE] = prefs.onlyOnline
        this[KEY_ONLY_DIRECT] = prefs.onlyDirect
        this[KEY_SHOW_IGNORED] = prefs.showIgnored
        this[KEY_ONLY_SIGNED] = prefs.onlySigned
        this[KEY_ONLY_ENCRYPTED] = prefs.onlyEncrypted
        this[KEY_EXCLUDE_MQTT] = prefs.excludeMqtt
        this[KEY_EXCLUDE_UNHEARD] = prefs.excludeUnheard
    }

    companion object {
        val KEY_HAS_SHOWN_NOT_PAIRED_WARNING_PREF = booleanPrefsKey("has_shown_not_paired_warning")
        val KEY_SHOW_QUICK_CHAT_PREF = booleanPrefsKey("show-quick-chat")
        val KEY_SHOW_FULL_MESSAGE_TIMESTAMPS = booleanPrefsKey("show-full-message-timestamps")
        val KEY_TEXT_COMPRESSION_ENABLED = booleanPrefsKey("text-compression-enabled")
        val KEY_PIXEL_ART_ENABLED = booleanPrefsKey("pixel-art-enabled")
        val KEY_FILE_TRANSFER_ENABLED = booleanPrefsKey("file-transfer-enabled")
        val KEY_PHOTO_HOSTING_ENABLED = booleanPrefsKey("photo-hosting-enabled")
        val KEY_PHOTO_HOSTING_PROVIDER = stringPrefsKey("photo-hosting-provider")
        val KEY_INSERT_PHOTO_LINK_ENABLED = booleanPrefsKey("insert-photo-link")
        val KEY_IMGBB_API_KEY = stringPrefsKey("imgbb-api-key")
        val KEY_IMGBB_EXPIRATION = stringPrefsKey("imgbb-expiration")
        val KEY_MESHPIC_RETENTION = stringPrefsKey("meshpic-retention")
        val KEY_LINK_PREVIEW_ENABLED = booleanPrefsKey("link-preview-enabled")
        val KEY_SHOW_BELL_BUTTON = booleanPrefsKey("show-bell-button")
        val KEY_REACTION_NOTIFICATION_MODE = stringPrefsKey("reaction-notification-mode")
        val KEY_PINNED_MESSAGES_ENABLED = booleanPrefsKey("pinned-messages-enabled")
        val KEY_SKIPPED_APP_UPDATE_VERSION = stringPrefsKey("skipped-app-update-version")
        val KEY_EVENT_THEME_ENABLED = booleanPrefsKey("event-theme-enabled")
        val KEY_ADV_THEME_COLORS_JSON = stringPrefsKey("adv-theme-colors-json")
        val KEY_CUSTOM_NODE_NAMES = stringPrefsKey("custom-node-names")
        val KEY_MESSAGE_BUBBLE_SPACING = intPrefsKey("message-bubble-spacing")
        val KEY_MESSAGE_BUBBLE_PADDING = intPrefsKey("message-bubble-padding")
        val KEY_MESSAGE_BUBBLE_CORNER_RADIUS = intPrefsKey("message-bubble-corner-radius")
        val KEY_MESSAGE_FONT_SIZE_SCALE = floatPrefsKey("message-font-size-scale")
        val KEY_REACTION_CHIP_SPACING = intPrefsKey("reaction-chip-spacing")
        val KEY_PRESSURE_IN_MMHG = booleanPrefsKey("pressure-in-mm-hg")
        val KEY_AUTO_TOPOLOGY_DISCOVERY_ENABLED = booleanPrefsKey("auto-topology-discovery-enabled")
        val KEY_AUTO_TOPOLOGY_DISCOVERY_INTERVAL_MINUTES = intPrefsKey("auto-topology-discovery-interval-minutes")
        const val DEFAULT_AUTO_TOPOLOGY_DISCOVERY_INTERVAL_MINUTES = 20
        val KEY_RADIO_SETUP_OFFERED = stringSetPrefsKey("radio-setup-offered-nodes")

        val KEY_APP_INTRO_COMPLETED = booleanPrefsKey("app_intro_completed")
        val KEY_THEME = intPrefsKey("theme")
        private val KEY_UNITS_OVERRIDE = intPrefsKey("units_override")
        val KEY_LOCALE = stringPrefsKey("locale")
        val KEY_NODE_SORT = intPrefsKey("node-sort-option")
        val KEY_CHIRPY_HOP_BEST_SCORE = intPrefsKey("chirpy-hop-best-score")
        val KEY_INCLUDE_UNKNOWN = booleanPrefsKey("include-unknown")
        val KEY_EXCLUDE_INFRASTRUCTURE = booleanPrefsKey("exclude-infrastructure")
        val KEY_ONLY_ONLINE = booleanPrefsKey("only-online")
        val KEY_ONLY_DIRECT = booleanPrefsKey("only-direct")
        val KEY_ONLY_SIGNED = booleanPrefsKey("only-signed")
        val KEY_ONLY_ENCRYPTED = booleanPrefsKey("only-encrypted")
        val KEY_SHOW_IGNORED = booleanPrefsKey("show-ignored")
        val KEY_EXCLUDE_MQTT = booleanPrefsKey("exclude-mqtt")
        val KEY_EXCLUDE_UNHEARD = booleanPrefsKey("exclude-unheard")
        val KEY_BLE_AUTO_SCAN = booleanPrefsKey("ble-auto-scan")
        val KEY_NETWORK_AUTO_SCAN = booleanPrefsKey("network-auto-scan")
        val KEY_SELECTED_CONNECTION_TRANSPORT = stringPrefsKey("selected-connection-transport")
        val KEY_FIRMWARE_UPDATE_NOTIFICATION_KEYS = stringPrefsKey("firmware-update-notification-keys")
        val KEY_SHOW_BLE_TRANSPORT = booleanPrefsKey("show-ble-transport")
        val KEY_SHOW_NETWORK_TRANSPORT = booleanPrefsKey("show-network-transport")
        val KEY_SHOW_USB_TRANSPORT = booleanPrefsKey("show-usb-transport")
        val KEY_KEPT_UNHEARD_NODES = stringPrefsKey("kept-unheard-nodes")
        private const val MAX_FIRMWARE_UPDATE_NOTIFICATION_KEYS = 100

        // Kept nums leave only when heard again; this bounds the ones deleted from the DB while still kept.
        private const val MAX_KEPT_UNHEARD_NODES = 1000
        private const val SHOW_QUICK_CHAT_BY_DEFAULT = false

        private fun parseDeviceType(name: String): DeviceType? = DeviceType.entries.firstOrNull { it.name == name }

        private fun parseNodeNums(stored: String?): List<Int> =
            stored?.split(',')?.mapNotNull(String::toIntOrNull)?.distinct().orEmpty()

        private fun legacySelectedConnectionTransport(preferences: PrefsSnapshot): DeviceType? {
            val hasLegacyTransportPreference =
                KEY_SHOW_BLE_TRANSPORT in preferences ||
                    KEY_SHOW_NETWORK_TRANSPORT in preferences ||
                    KEY_SHOW_USB_TRANSPORT in preferences
            if (!hasLegacyTransportPreference) return null

            val selected =
                listOfNotNull(
                    DeviceType.BLE.takeIf { preferences[KEY_SHOW_BLE_TRANSPORT] ?: true },
                    DeviceType.TCP.takeIf { preferences[KEY_SHOW_NETWORK_TRANSPORT] ?: true },
                    DeviceType.USB.takeIf { preferences[KEY_SHOW_USB_TRANSPORT] ?: true },
                )
            return selected.firstOrNull()
        }
    }
}
