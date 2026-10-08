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
package org.meshtastic.feature.automation.model

import org.jetbrains.compose.resources.StringResource
import org.meshtastic.core.automation.model.AutomationAction
import org.meshtastic.core.automation.model.AutomationCondition
import org.meshtastic.core.automation.model.AutomationTrigger
import org.meshtastic.core.automation.model.GeofenceTransition
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.automation_template_auto_responder
import org.meshtastic.core.resources.automation_template_auto_responder_desc
import org.meshtastic.core.resources.automation_template_geofence
import org.meshtastic.core.resources.automation_template_geofence_desc
import org.meshtastic.core.resources.automation_template_panic_button
import org.meshtastic.core.resources.automation_template_panic_button_desc
import org.meshtastic.core.resources.automation_template_repeater_monitor
import org.meshtastic.core.resources.automation_template_repeater_monitor_desc

private const val DEFAULT_PANIC_ALARM_DURATION = 15
private const val DEFAULT_REPEATER_BATTERY_THRESHOLD = 20
private const val DEFAULT_REPEATER_COOLDOWN_SECONDS = 3600
private const val DEFAULT_GEOFENCE_RADIUS_METERS = 500.0

/** Predefined rule templates for fast rule setup. */
data class AutomationTemplate(
    val id: String,
    val titleRes: StringResource,
    val descriptionRes: StringResource,
    val initialRuleName: String,
    val trigger: AutomationTrigger,
    val conditions: List<AutomationCondition> = emptyList(),
    val actions: List<AutomationAction> = emptyList(),
)

object AutomationTemplates {
    val list: List<AutomationTemplate> =
        listOf(
            AutomationTemplate(
                id = "panic_button",
                titleRes = Res.string.automation_template_panic_button,
                descriptionRes = Res.string.automation_template_panic_button_desc,
                initialRuleName = "Panic Alarm (🚨)",
                trigger = AutomationTrigger.ReactionReceived(emoji = "🚨"),
                actions =
                listOf(
                    AutomationAction.PlayAlarm(alarmType = "siren", durationSeconds = DEFAULT_PANIC_ALARM_DURATION),
                    AutomationAction.ShowNotification(
                        title = "🚨 SOS Alert!",
                        body = "Panic alert received from {node_name}!",
                        highPriority = true,
                    ),
                ),
            ),
            AutomationTemplate(
                id = "repeater_monitor",
                titleRes = Res.string.automation_template_repeater_monitor,
                descriptionRes = Res.string.automation_template_repeater_monitor_desc,
                initialRuleName = "Repeater Low Battery Alert",
                trigger = AutomationTrigger.NodeBatteryLow(thresholdPercent = DEFAULT_REPEATER_BATTERY_THRESHOLD),
                conditions =
                listOf(AutomationCondition.NotFiredRecently(windowSeconds = DEFAULT_REPEATER_COOLDOWN_SECONDS)),
                actions =
                listOf(
                    AutomationAction.ShowNotification(
                        title = "Low Battery Alert",
                        body = "Node {node_name} battery is {battery_level}% ({voltage}V)",
                        highPriority = true,
                    ),
                ),
            ),
            AutomationTemplate(
                id = "geofence_exit",
                titleRes = Res.string.automation_template_geofence,
                descriptionRes = Res.string.automation_template_geofence_desc,
                initialRuleName = "Geofence Perimeter Alert",
                trigger =
                AutomationTrigger.NodeGeofence(
                    transition = GeofenceTransition.EXIT,
                    radiusMeters = DEFAULT_GEOFENCE_RADIUS_METERS,
                ),
                actions =
                listOf(
                    AutomationAction.SpeakText(text = "Warning: node {node_name} has left the zone"),
                    AutomationAction.ShowNotification(
                        title = "Geofence Alert",
                        body = "Node {node_name} left the designated perimeter",
                        highPriority = true,
                    ),
                ),
            ),
            AutomationTemplate(
                id = "auto_responder",
                titleRes = Res.string.automation_template_auto_responder,
                descriptionRes = Res.string.automation_template_auto_responder_desc,
                initialRuleName = "Auto-Responder (test)",
                trigger = AutomationTrigger.MessageReceived(pattern = "test"),
                actions = listOf(AutomationAction.SendReaction(emoji = "{HOP_REACTION}")),
            ),
        )
}
