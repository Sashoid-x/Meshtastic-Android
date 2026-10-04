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
package org.meshtastic.core.automation.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Enums & Subtypes ─────────────────────────────────────────────────────────

@Serializable
enum class LogicalOperator {
    @SerialName("and")
    AND,

    @SerialName("or")
    OR,
}

@Serializable
enum class TelemetryMetricType {
    @SerialName("battery_percent")
    BATTERY_PERCENT,

    @SerialName("voltage")
    VOLTAGE,

    @SerialName("temperature")
    TEMPERATURE,

    @SerialName("humidity")
    HUMIDITY,

    @SerialName("barometric_pressure")
    BAROMETRIC_PRESSURE,

    @SerialName("iaq")
    AIR_QUALITY_IAQ,

    @SerialName("co2")
    AIR_QUALITY_CO2,

    @SerialName("pm25")
    AIR_QUALITY_PM25,

    @SerialName("soil_moisture")
    SOIL_MOISTURE,
}

@Serializable
enum class LocationConditionType {
    @SerialName("within_geofence")
    WITHIN_GEOFENCE,

    @SerialName("outside_geofence")
    OUTSIDE_GEOFENCE,

    @SerialName("closer_than")
    CLOSER_THAN,

    @SerialName("further_than")
    FURTHER_THAN,
}

@Serializable
enum class NodeStatusType {
    @SerialName("appeared")
    APPEARED,

    @SerialName("disappeared")
    DISAPPEARED,
}

@Serializable
enum class ComparisonOperator {
    @SerialName("less_than")
    LESS_THAN,

    @SerialName("greater_than")
    GREATER_THAN,

    @SerialName("equals")
    EQUALS,
}

@Serializable
enum class EnvironmentMetricType {
    @SerialName("temperature")
    TEMPERATURE,

    @SerialName("humidity")
    HUMIDITY,

    @SerialName("barometric_pressure")
    BAROMETRIC_PRESSURE,

    @SerialName("voltage")
    VOLTAGE,

    @SerialName("current")
    CURRENT,
}

@Serializable
enum class AirQualityMetricType {
    @SerialName("pm25")
    PM25,

    @SerialName("co2")
    CO2,

    @SerialName("iaq")
    IAQ,
}

@Serializable
enum class GeofenceTransition {
    @SerialName("enter")
    ENTER,

    @SerialName("exit")
    EXIT,
}

@Serializable
data class ConditionBlock(
    val operator: LogicalOperator = LogicalOperator.AND,
    val conditions: List<AutomationCondition> = emptyList(),
)

// ─── Trigger ─────────────────────────────────────────────────────────────────

/** The event that activates an automation rule. */
@Serializable
sealed class AutomationTrigger {

    // ─── Messages & Reactions ──────────────────────────

    /** Fires when a text message is received matching criteria. */
    @Serializable
    @SerialName("message_received")
    data class MessageReceived(
        val pattern: String = "",
        val isRegex: Boolean = false,
        val channelIndex: Int? = null,
        val fromNodeId: Int? = null,
        val isDirectMessage: Boolean = false,
    ) : AutomationTrigger()

    /** Fires when an emoji reaction is received. */
    @Serializable
    @SerialName("reaction_received")
    data class ReactionReceived(val emoji: String = "", val fromNodeId: Int? = null, val channelIndex: Int? = null) :
        AutomationTrigger()

    // ─── Telemetry & Sensors ───────────────────────────

    /** Fires when node battery falls below a threshold (% or Volts). */
    @Serializable
    @SerialName("node_battery_low")
    data class NodeBatteryLow(
        val nodeId: Int? = null,
        val thresholdPercent: Int = 20,
        val thresholdVoltage: Float? = null,
    ) : AutomationTrigger()

    /** Fires when an environmental sensor value crosses a threshold. */
    @Serializable
    @SerialName("environment_threshold")
    data class EnvironmentThreshold(
        val nodeId: Int? = null,
        val metricType: EnvironmentMetricType = EnvironmentMetricType.TEMPERATURE,
        val operator: ComparisonOperator = ComparisonOperator.GREATER_THAN,
        val thresholdValue: Float = 30.0f,
    ) : AutomationTrigger()

    /** Fires when air quality metric crosses a threshold. */
    @Serializable
    @SerialName("air_quality_threshold")
    data class AirQualityThreshold(
        val nodeId: Int? = null,
        val metricType: AirQualityMetricType = AirQualityMetricType.CO2,
        val operator: ComparisonOperator = ComparisonOperator.GREATER_THAN,
        val thresholdValue: Float = 1000.0f,
    ) : AutomationTrigger()

    /** Fires when soil moisture sensor crosses a threshold. */
    @Serializable
    @SerialName("soil_moisture_threshold")
    data class SoilMoistureThreshold(
        val nodeId: Int? = null,
        val operator: ComparisonOperator = ComparisonOperator.LESS_THAN,
        val thresholdPercent: Float = 20.0f,
    ) : AutomationTrigger()

    // ─── Geolocation & Geofencing ──────────────────────

    /** Fires when a node enters or exits a circular geofence around a coordinate. */
    @Serializable
    @SerialName("node_geofence")
    data class NodeGeofence(
        val nodeId: Int? = null,
        val centerLatitude: Double = 0.0,
        val centerLongitude: Double = 0.0,
        val radiusMeters: Double = 500.0,
        val transition: GeofenceTransition = GeofenceTransition.ENTER,
    ) : AutomationTrigger()

    /** Fires when a node gets closer than or further than a distance relative to our radio. */
    @Serializable
    @SerialName("node_proximity")
    data class NodeProximity(
        val nodeId: Int? = null,
        val distanceKilometers: Double = 5.0,
        val operator: ComparisonOperator = ComparisonOperator.LESS_THAN,
    ) : AutomationTrigger()

    /** Fires when a node moves by at least [minDistanceMeters]. */
    @Serializable
    @SerialName("node_moved")
    data class NodeMoved(val nodeId: Int? = null, val minDistanceMeters: Double = 100.0) : AutomationTrigger()

    // ─── Network & Connectivity ────────────────────────

    /** Fires whenever a node appears / is heard in the mesh. */
    @Serializable
    @SerialName("node_appeared")
    data class NodeAppeared(val nodeId: Int? = null) : AutomationTrigger()

    /** Fires whenever a node stops being heard for [timeoutMinutes]. */
    @Serializable
    @SerialName("node_disappeared")
    data class NodeDisappeared(val nodeId: Int? = null, val timeoutMinutes: Int = 30) : AutomationTrigger()

    /** Fires when the hop limit/count of received packets from a node changes. */
    @Serializable
    @SerialName("hop_limit_changed")
    data class HopLimitChanged(val nodeId: Int? = null, val minHops: Int? = null, val maxHops: Int? = null) :
        AutomationTrigger()

    // ─── System & Schedule ─────────────────────────────

    /** Fires on a cron or minute interval schedule. */
    @Serializable
    @SerialName("schedule")
    data class Schedule(val cronExpression: String = "*/15 * * * *") : AutomationTrigger()

    /** Fires when host device battery drops at or below [thresholdPercent]. */
    @Serializable
    @SerialName("device_battery_low")
    data class DeviceBatteryLow(val thresholdPercent: Int = 20) : AutomationTrigger()

    /** Fires when the app connects to a radio. */
    @Serializable
    @SerialName("radio_connected")
    data object RadioConnected : AutomationTrigger()

    /** Fires when the app disconnects from a radio. */
    @Serializable
    @SerialName("radio_disconnected")
    data object RadioDisconnected : AutomationTrigger()

    // ─── Unified Core Triggers ─────────────────────────

    /** Fires when telemetry metrics are received. */
    @Serializable
    @SerialName("telemetry_received")
    data class TelemetryReceived(val fromNodeId: Int? = null) : AutomationTrigger()

    /** Fires when position updates are received. */
    @Serializable
    @SerialName("position_updated")
    data class PositionUpdated(val fromNodeId: Int? = null) : AutomationTrigger()

    /** Fires when a node status changes (appeared or disappeared). */
    @Serializable
    @SerialName("node_status_changed")
    data class NodeStatusChanged(
        val statusType: NodeStatusType = NodeStatusType.APPEARED,
        val fromNodeId: Int? = null,
        val timeoutMinutes: Int = 30,
    ) : AutomationTrigger()

    /** Fires when the radio connection state changes. */
    @Serializable
    @SerialName("radio_connection_changed")
    data class RadioConnectionChanged(val requiredConnected: Boolean = true) : AutomationTrigger()

    /** Fires when host device battery changes. */
    @Serializable
    @SerialName("device_battery_changed")
    data class DeviceBatteryChanged(val minPercent: Int = 20) : AutomationTrigger()

    /** Fires on a periodic schedule tick. */
    @Serializable
    @SerialName("schedule_tick")
    data class ScheduleTick(val intervalMinutes: Int = 60) : AutomationTrigger()
}

// ─── Condition ────────────────────────────────────────────────────────────────

/** Filter applied after a trigger fires. */
@Serializable
sealed class AutomationCondition {

    /** Passes if triggering node name contains [substring]. */
    @Serializable
    @SerialName("node_name_contains")
    data class NodeNameContains(val substring: String) : AutomationCondition()

    /** Passes only if the triggering node is in [nodeIds]. */
    @Serializable
    @SerialName("node_in_list")
    data class NodeInList(val nodeIds: List<Int> = emptyList()) : AutomationCondition()

    /** Passes only if the triggering node is marked as Favorite. */
    @Serializable
    @SerialName("node_is_favorite")
    data object NodeIsFavorite : AutomationCondition()

    /** Passes if triggering message/packet is on [channelIndex]. */
    @Serializable
    @SerialName("channel_is")
    data class ChannelIs(val channelIndex: Int) : AutomationCondition()

    /** Passes only if current time is between [startHour] and [endHour]. */
    @Serializable
    @SerialName("time_of_day")
    data class TimeOfDay(val startHour: Int, val endHour: Int) : AutomationCondition()

    /** Passes only on specified days of week (1 = Mon ... 7 = Sun). */
    @Serializable
    @SerialName("days_of_week")
    @Suppress("MagicNumber")
    data class DaysOfWeek(val days: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7)) : AutomationCondition()

    /** Passes only if radio is actively connected. */
    @Serializable
    @SerialName("radio_is_connected")
    data class RadioIsConnected(val requiredConnected: Boolean = true) : AutomationCondition()

    /** Passes only if rule has NOT fired within [windowSeconds]. */
    @Serializable
    @SerialName("not_fired_recently")
    data class NotFiredRecently(val windowSeconds: Int = 60) : AutomationCondition()

    // ─── Unified Core Conditions ───────────────────────

    /** Passes when a telemetry metric meets a comparison threshold. */
    @Serializable
    @SerialName("telemetry_threshold")
    data class TelemetryThreshold(
        val metric: TelemetryMetricType = TelemetryMetricType.BATTERY_PERCENT,
        val operator: ComparisonOperator = ComparisonOperator.LESS_THAN,
        val threshold: Float = 20.0f,
    ) : AutomationCondition()

    /** Passes when node location meets a spatial condition. */
    @Serializable
    @SerialName("location_filter")
    data class LocationFilter(
        val type: LocationConditionType = LocationConditionType.OUTSIDE_GEOFENCE,
        val centerLatitude: Double = 0.0,
        val centerLongitude: Double = 0.0,
        val radiusMeters: Double = 500.0,
        val distanceKm: Double = 5.0,
    ) : AutomationCondition()

    /** Passes when sender matches node criteria. */
    @Serializable
    @SerialName("node_filter")
    data class NodeFilter(
        val nodeId: Int? = null,
        val nameContains: String = "",
        val isFavoriteOnly: Boolean = false,
    ) : AutomationCondition()

    /** Passes when message matches text / emoji criteria. */
    @Serializable
    @SerialName("message_filter")
    data class MessageFilter(val pattern: String = "", val isRegex: Boolean = false, val emoji: String = "") :
        AutomationCondition()
}

// ─── Action ──────────────────────────────────────────────────────────────────

/** The effect executed when a rule fires and conditions pass. */
@Serializable
sealed class AutomationAction {

    // ─── Radio Network Actions ─────────────────────────

    /** Sends a text message to a channel or direct message. */
    @Serializable
    @SerialName("send_message")
    data class SendMessage(val text: String, val channelIndex: Int = 0, val destNodeId: Int? = null) :
        AutomationAction()

    /** Requests position from a node. */
    @Serializable
    @SerialName("request_position")
    data class RequestPosition(val destNodeId: Int) : AutomationAction()

    /** Requests telemetry metrics from a node. */
    @Serializable
    @SerialName("request_telemetry")
    data class RequestTelemetry(val destNodeId: Int) : AutomationAction()

    /** Sends a Traceroute request to a node. */
    @Serializable
    @SerialName("send_traceroute")
    data class SendTraceroute(val destNodeId: Int) : AutomationAction()

    /** Controls a GPIO pin (relay, buzzer, switch) on a remote node. */
    @Serializable
    @SerialName("remote_gpio")
    data class RemoteGpio(val destNodeId: Int, val pin: Int, val state: Boolean) : AutomationAction()

    // ─── Device / App Actions ──────────────────────────

    /** Posts a local notification. */
    @Serializable
    @SerialName("show_notification")
    data class ShowNotification(val title: String, val body: String, val highPriority: Boolean = true) :
        AutomationAction()

    /** Plays an audible alert or siren. */
    @Serializable
    @SerialName("play_alarm")
    data class PlayAlarm(val alarmType: String = "siren", val durationSeconds: Int = 5) : AutomationAction()

    /** Speaks text via Text-to-Speech. */
    @Serializable
    @SerialName("speak_text")
    data class SpeakText(val text: String, val speechRate: Float = 1.0f) : AutomationAction()

    /** Plays an in-app alert sound. */
    @Serializable
    @SerialName("play_sound")
    data class PlaySound(val soundId: String = "notification") : AutomationAction()

    /** Vibrates the device using the requested pattern. */
    @Serializable
    @SerialName("vibrate")
    data class VibrateDevice(val pattern: String = "short", val durationMs: Long = 500L) : AutomationAction()

    /** Sends an emoji reaction. */
    @Serializable
    @SerialName("send_reaction")
    data class SendReaction(val emoji: String = "👍", val channelIndex: Int = 0, val destNodeId: Int? = null) :
        AutomationAction()

    /** Broadcasts current GPS position to the mesh. */
    @Serializable
    @SerialName("broadcast_location")
    data class BroadcastLocation(val channelIndex: Int = 0, val destNodeId: Int? = null) : AutomationAction()

    /** Copies text (with template variables) to clipboard. */
    @Serializable
    @SerialName("copy_to_clipboard")
    data class CopyToClipboard(val text: String) : AutomationAction()

    /** Chains to another rule by ID. */
    @Serializable
    @SerialName("trigger_rule")
    data class TriggerRule(val ruleId: String) : AutomationAction()
}
