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

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.atomicfu.atomic
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.model.DeviceType
import org.meshtastic.core.model.PhotoHostingProvider
import org.meshtastic.core.model.ReactionNotificationMode
import org.meshtastic.core.prefs.cachedFlow
import org.meshtastic.core.prefs.di.UiDataStore
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
        dataStore.data.map { it[KEY_SHOW_QUICK_CHAT_PREF] ?: false }.stateIn(scope, SharingStarted.Eagerly, false)

    override fun setShowQuickChat(show: Boolean) {
        scope.launch { dataStore.edit { it[KEY_SHOW_QUICK_CHAT_PREF] = show } }
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
        dataStore.data.map { it[KEY_FILE_TRANSFER_ENABLED] ?: true }.stateIn(scope, SharingStarted.Eagerly, true)

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

    override val builtInImageViewerEnabled: StateFlow<Boolean> =
        dataStore.data
            .map { it[KEY_BUILT_IN_IMAGE_VIEWER_ENABLED] ?: true }
            .stateIn(scope, SharingStarted.Eagerly, true)

    override fun setBuiltInImageViewerEnabled(enabled: Boolean) {
        scope.launch { dataStore.edit { it[KEY_BUILT_IN_IMAGE_VIEWER_ENABLED] = enabled } }
    }

    override val insertPhotoLinkEnabled: StateFlow<Boolean> =
        dataStore.data.map { it[KEY_INSERT_PHOTO_LINK_ENABLED] ?: true }.stateIn(scope, SharingStarted.Eagerly, true)

    override fun setInsertPhotoLinkEnabled(enabled: Boolean) {
        scope.launch { dataStore.edit { it[KEY_INSERT_PHOTO_LINK_ENABLED] = enabled } }
    }

    override val sendOnEnterEnabled: StateFlow<Boolean> =
        dataStore.data.map { it[KEY_SEND_ON_ENTER_ENABLED] ?: true }.stateIn(scope, SharingStarted.Eagerly, true)

    override fun setSendOnEnterEnabled(enabled: Boolean) {
        scope.launch { dataStore.edit { it[KEY_SEND_ON_ENTER_ENABLED] = enabled } }
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

    override val advThemeColorsJson: StateFlow<String> =
        dataStore.data.map { it[KEY_ADV_THEME_COLORS_JSON] ?: "" }.stateIn(scope, SharingStarted.Eagerly, "")

    override fun setAdvThemeColorsJson(json: String) {
        scope.launch { dataStore.edit { it[KEY_ADV_THEME_COLORS_JSON] = json } }
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

    override fun shouldProvideNodeLocation(nodeNum: Int): StateFlow<Boolean> =
        cachedFlow(provideNodeLocationFlows, nodeNum) {
            val key = booleanPreferencesKey(provideLocationKey(nodeNum))
            dataStore.data.map { it[key] ?: false }.stateIn(scope, SharingStarted.Eagerly, false)
        }

    override fun setShouldProvideNodeLocation(nodeNum: Int, provide: Boolean) {
        scope.launch { dataStore.edit { it[booleanPreferencesKey(provideLocationKey(nodeNum))] = provide } }
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

    private fun Preferences.toNodeFilterPrefs() = NodeFilterPrefs(
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

    private fun MutablePreferences.writeNodeFilterPrefs(prefs: NodeFilterPrefs) {
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
        val KEY_HAS_SHOWN_NOT_PAIRED_WARNING_PREF = booleanPreferencesKey("has_shown_not_paired_warning")
        val KEY_SHOW_QUICK_CHAT_PREF = booleanPreferencesKey("show-quick-chat")
        val KEY_SHOW_FULL_MESSAGE_TIMESTAMPS = booleanPreferencesKey("show-full-message-timestamps")
        val KEY_TEXT_COMPRESSION_ENABLED = booleanPreferencesKey("text-compression-enabled")
        val KEY_PIXEL_ART_ENABLED = booleanPreferencesKey("pixel-art-enabled")
        val KEY_FILE_TRANSFER_ENABLED = booleanPreferencesKey("file-transfer-enabled")
        val KEY_PHOTO_HOSTING_ENABLED = booleanPreferencesKey("photo-hosting-enabled")
        val KEY_PHOTO_HOSTING_PROVIDER = stringPreferencesKey("photo-hosting-provider")
        val KEY_BUILT_IN_IMAGE_VIEWER_ENABLED = booleanPreferencesKey("built-in-image-viewer")
        val KEY_INSERT_PHOTO_LINK_ENABLED = booleanPreferencesKey("insert-photo-link")
        val KEY_SEND_ON_ENTER_ENABLED = booleanPreferencesKey("send-on-enter")
        val KEY_SHOW_BELL_BUTTON = booleanPreferencesKey("show-bell-button")
        val KEY_REACTION_NOTIFICATION_MODE = stringPreferencesKey("reaction-notification-mode")
        val KEY_PINNED_MESSAGES_ENABLED = booleanPreferencesKey("pinned-messages-enabled")
        val KEY_EVENT_THEME_ENABLED = booleanPreferencesKey("event-theme-enabled")
        val KEY_ADV_THEME_COLORS_JSON = stringPreferencesKey("adv-theme-colors-json")
        val KEY_MESSAGE_BUBBLE_SPACING = intPreferencesKey("message-bubble-spacing")
        val KEY_MESSAGE_BUBBLE_PADDING = intPreferencesKey("message-bubble-padding")
        val KEY_MESSAGE_FONT_SIZE_SCALE = floatPreferencesKey("message-font-size-scale")
        val KEY_REACTION_CHIP_SPACING = intPreferencesKey("reaction-chip-spacing")
        val KEY_PRESSURE_IN_MMHG = booleanPreferencesKey("pressure-in-mm-hg")

        val KEY_APP_INTRO_COMPLETED = booleanPreferencesKey("app_intro_completed")
        val KEY_THEME = intPreferencesKey("theme")
        private val KEY_UNITS_OVERRIDE = intPreferencesKey("units_override")
        val KEY_LOCALE = stringPreferencesKey("locale")
        val KEY_NODE_SORT = intPreferencesKey("node-sort-option")
        val KEY_INCLUDE_UNKNOWN = booleanPreferencesKey("include-unknown")
        val KEY_EXCLUDE_INFRASTRUCTURE = booleanPreferencesKey("exclude-infrastructure")
        val KEY_ONLY_ONLINE = booleanPreferencesKey("only-online")
        val KEY_ONLY_DIRECT = booleanPreferencesKey("only-direct")
        val KEY_ONLY_SIGNED = booleanPreferencesKey("only-signed")
        val KEY_ONLY_ENCRYPTED = booleanPreferencesKey("only-encrypted")
        val KEY_SHOW_IGNORED = booleanPreferencesKey("show-ignored")
        val KEY_EXCLUDE_MQTT = booleanPreferencesKey("exclude-mqtt")
        val KEY_EXCLUDE_UNHEARD = booleanPreferencesKey("exclude-unheard")
        val KEY_BLE_AUTO_SCAN = booleanPreferencesKey("ble-auto-scan")
        val KEY_NETWORK_AUTO_SCAN = booleanPreferencesKey("network-auto-scan")
        val KEY_SELECTED_CONNECTION_TRANSPORT = stringPreferencesKey("selected-connection-transport")
        val KEY_FIRMWARE_UPDATE_NOTIFICATION_KEYS = stringPreferencesKey("firmware-update-notification-keys")
        val KEY_SHOW_BLE_TRANSPORT = booleanPreferencesKey("show-ble-transport")
        val KEY_SHOW_NETWORK_TRANSPORT = booleanPreferencesKey("show-network-transport")
        val KEY_SHOW_USB_TRANSPORT = booleanPreferencesKey("show-usb-transport")
        private const val MAX_FIRMWARE_UPDATE_NOTIFICATION_KEYS = 100

        private fun parseDeviceType(name: String): DeviceType? = DeviceType.entries.firstOrNull { it.name == name }

        private fun legacySelectedConnectionTransport(preferences: Preferences): DeviceType? {
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
