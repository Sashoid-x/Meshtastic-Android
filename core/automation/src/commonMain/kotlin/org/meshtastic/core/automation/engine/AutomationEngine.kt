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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import org.meshtastic.core.automation.model.AutomationAction
import org.meshtastic.core.automation.model.AutomationCondition
import org.meshtastic.core.automation.model.AutomationLog
import org.meshtastic.core.automation.model.AutomationRule
import org.meshtastic.core.automation.model.LocationConditionType
import org.meshtastic.core.automation.model.LogicalOperator
import org.meshtastic.core.automation.model.TelemetryMetricType
import org.meshtastic.core.automation.repository.AutomationRepository
import org.meshtastic.core.automation.trigger.TriggerSource
import org.meshtastic.core.automation.util.calculateDistanceMeters
import org.meshtastic.core.automation.util.matchesOperator
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.ServiceRepository
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val logger = Logger.withTag("AutomationEngine")

/**
 * Core execution engine for the ЕСЛИ → ТО (IF → THEN) automation system.
 *
 * ### Lifecycle
 * Call [start] once with a [CoroutineScope] tied to the application/service lifetime. The engine observes
 * [AutomationRepository.observeEnabledRules] and dynamically starts or stops per-rule observer coroutines as the rule
 * set changes. Call [stop] to cancel all running rule coroutines without affecting the scope itself.
 *
 * ### Safety guarantees
 * - **Rate limiting:** A rule cannot fire more than [MAX_FIRES_PER_WINDOW] times within [RATE_LIMIT_WINDOW_MS].
 *   Subsequent events are silently dropped until the window expires. Rate limit is checked *after* conditions pass, so
 *   non-matching events do not consume quota.
 * - **Loop prevention:** [AutomationCondition.NotFiredRecently] can be added to any rule that might generate its own
 *   trigger event (e.g. send-message → message-received loop).
 * - **Chain depth limit:** [TriggerRule] actions are tracked via a call-depth counter; chains deeper than
 *   [MAX_CHAIN_DEPTH] are aborted.
 *
 * @param repository Persistence layer.
 * @param triggerSource Platform-supplied hot flows for all supported trigger events.
 * @param actionExecutor Platform-supplied handler for executing [AutomationAction]s.
 */
class AutomationEngine(
    private val repository: AutomationRepository,
    private val triggerSource: TriggerSource,
    private val actionExecutor: ActionExecutor,
    private val nodeRepository: NodeRepository? = null,
    private val serviceRepository: ServiceRepository? = null,
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()

    /** Active coroutine job per rule ID. */
    private val ruleJobs = mutableMapOf<String, Job>()

    /** Active domain rules keyed by rule ID. */
    private val activeRules = mutableMapOf<String, AutomationRule>()

    /** Last-fire timestamps per rule for rate limiting. */
    private val fireTimes = mutableMapOf<String, ArrayDeque<Long>>()

    private var engineScope: CoroutineScope? = null
    private var supervisorJob: Job? = null

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.e(throwable) { "Unhandled exception in AutomationEngine" }
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Starts the engine. Safe to call multiple times (idempotent after first call).
     *
     * The engine subscribes to enabled rules and keeps per-rule observer coroutines in sync with database changes.
     */
    fun start(scope: CoroutineScope) {
        if (engineScope != null) return
        engineScope = scope
        supervisorJob =
            scope.launch(exceptionHandler) {
                repository.observeEnabledRules().distinctUntilChanged().collectLatest { rules ->
                    reconcileRules(rules, scope)
                }
            }
        logger.i { "AutomationEngine started" }
    }

    /** Cancels all rule coroutines. The engine scope itself is NOT cancelled. */
    fun stop() {
        supervisorJob?.cancel()
        supervisorJob = null
        engineScope = null
        ruleJobs.values.forEach { it.cancel() }
        ruleJobs.clear()
        fireTimes.clear()
        activeRules.clear()
        logger.i { "AutomationEngine stopped" }
    }

    // ─── Internal ────────────────────────────────────────────────────────────

    private suspend fun reconcileRules(active: List<AutomationRule>, scope: CoroutineScope) {
        mutex.withLock {
            val activeIds = active.map { it.id }.toSet()

            // Stop jobs for rules that are no longer enabled
            val removed = ruleJobs.keys - activeIds
            removed.forEach { id ->
                ruleJobs.remove(id)?.cancel()
                fireTimes.remove(id)
                activeRules.remove(id)
                logger.d { "Stopped observer for rule $id" }
            }

            // Start or restart jobs for active rules
            active.forEach { rule ->
                val existing = activeRules[rule.id]
                if (existing == null) {
                    activeRules[rule.id] = rule
                    ruleJobs[rule.id] = scope.launch(exceptionHandler) { observeRule(rule.id) }
                    logger.d { "Started observer for rule '${rule.name}' (${rule.id})" }
                } else if (!existing.hasSameConfiguration(rule)) {
                    // Rule configuration (trigger/conditions/actions/operator) changed:
                    // update stored rule and restart its flow observer
                    activeRules[rule.id] = rule
                    ruleJobs.remove(rule.id)?.cancel()
                    ruleJobs[rule.id] = scope.launch(exceptionHandler) { observeRule(rule.id) }
                    logger.d { "Restarted observer for updated rule '${rule.name}' (${rule.id})" }
                } else {
                    // Only runtime execution stats changed (e.g. fireCount, lastFiredAt)
                    activeRules[rule.id] = rule
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun observeRule(ruleId: String) {
        val initialRule = mutex.withLock { activeRules[ruleId] } ?: return
        try {
            triggerSource.flowFor(initialRule.trigger).collect { event ->
                val currentRule = mutex.withLock { activeRules[ruleId] } ?: return@collect
                handleEvent(currentRule, event)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "Error observing rule $ruleId" }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun handleEvent(rule: AutomationRule, event: TriggerEvent, chainDepth: Int = 0) {
        if (chainDepth > MAX_CHAIN_DEPTH) {
            logger.w { "Rule '${rule.name}' chain depth exceeded — aborting" }
            return
        }

        // Evaluate conditions first so non-matching events do not consume rate-limit quota (P0-4)
        if (!evaluateConditions(rule, event)) {
            logger.d { "Rule '${rule.name}' conditions not met" }
            return
        }

        // Check and reserve rate-limit slot only after conditions pass (P0-4, P0-3)
        if (!checkAndRecordRateLimit(rule.id)) {
            logger.d { "Rule '${rule.name}' rate-limited, skipping" }
            return
        }

        val now = clock.now().toEpochMilliseconds()
        val executionErrors = mutableListOf<String>()
        var hasSuccessfulAction = false

        for (action in rule.actions) {
            try {
                when (action) {
                    is AutomationAction.TriggerRule -> {
                        val chained = repository.getRule(action.ruleId)
                        if (chained != null && chained.isEnabled) {
                            handleEvent(chained, event, chainDepth + 1)
                            hasSuccessfulAction = true
                        } else {
                            val msg = "Chained rule ${action.ruleId} not found or disabled"
                            logger.w { msg }
                            executionErrors.add(msg)
                        }
                    }

                    else -> {
                        actionExecutor.execute(action, event)
                        hasSuccessfulAction = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val detail = e.message?.take(MAX_DETAIL_LENGTH) ?: e::class.simpleName ?: "Unknown error"
                logger.e(e) { "Rule '${rule.name}' action failed: $detail" }
                executionErrors.add(detail)
            }
        }

        val success = executionErrors.isEmpty()
        val detail = if (success) "OK" else executionErrors.joinToString("; ").take(MAX_DETAIL_LENGTH)

        try {
            // Only count as fired in repository if at least one action succeeded (P0-4)
            if (hasSuccessfulAction) {
                repository.recordFire(rule.id, now)
            }
            repository.addLog(
                AutomationLog(
                    id = Uuid.random().toString(),
                    ruleId = rule.id,
                    timestamp = now,
                    success = success,
                    detail = detail,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "Failed to record fire/log for rule ${rule.id}" }
        }
    }

    private suspend fun checkAndRecordRateLimit(ruleId: String): Boolean = mutex.withLock {
        val now = clock.now().toEpochMilliseconds()
        val times = fireTimes.getOrPut(ruleId) { ArrayDeque() }
        // Evict entries outside the window
        while (times.isNotEmpty() && (now - times.first()) > RATE_LIMIT_WINDOW_MS) {
            times.removeFirst()
        }
        if (times.size >= MAX_FIRES_PER_WINDOW) {
            false
        } else {
            times.addLast(now)
            true
        }
    }

    private suspend fun evaluateConditions(rule: AutomationRule, event: TriggerEvent): Boolean {
        if (rule.conditions.isEmpty()) return true
        return when (rule.conditionOperator) {
            LogicalOperator.AND -> rule.conditions.all { evaluateCondition(rule, it, event) }
            LogicalOperator.OR -> rule.conditions.any { evaluateCondition(rule, it, event) }
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun evaluateCondition(
        rule: AutomationRule,
        condition: AutomationCondition,
        event: TriggerEvent,
    ): Boolean = when (condition) {
        is AutomationCondition.NodeNameContains ->
            event.nodeName?.contains(condition.substring, ignoreCase = true) != false

        is AutomationCondition.NodeInList ->
            event.nodeId == null || condition.nodeIds.isEmpty() || event.nodeId in condition.nodeIds

        is AutomationCondition.NodeIsFavorite -> {
            val id = event.nodeId
            if (id != null && nodeRepository != null) {
                nodeRepository.nodeDBbyNum.value[id]?.isFavorite == true
            } else {
                true
            }
        }

        is AutomationCondition.ChannelIs ->
            event.channelIndex == null || event.channelIndex == condition.channelIndex

        is AutomationCondition.TimeOfDay -> {
            val hour = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour
            if (condition.startHour <= condition.endHour) {
                hour in condition.startHour..condition.endHour
            } else {
                hour >= condition.startHour || hour <= condition.endHour
            }
        }

        is AutomationCondition.DaysOfWeek -> {
            val dayOfWeek = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).dayOfWeek.isoDayNumber
            dayOfWeek in condition.days
        }

        is AutomationCondition.RadioIsConnected -> {
            if (serviceRepository != null) {
                val isConnected = serviceRepository.connectionState.value is ConnectionState.Connected
                isConnected == condition.requiredConnected
            } else {
                true
            }
        }

        is AutomationCondition.NotFiredRecently -> {
            val lastFired = repository.getRule(rule.id)?.lastFiredAt ?: 0L
            val now = clock.now().toEpochMilliseconds()
            (now - lastFired) > condition.windowSeconds * MILLIS_PER_SECOND
        }

        is AutomationCondition.TelemetryThreshold -> {
            val value =
                when (condition.metric) {
                    TelemetryMetricType.BATTERY_PERCENT -> event.batteryLevel?.toFloat()
                    TelemetryMetricType.VOLTAGE -> event.voltage
                    TelemetryMetricType.TEMPERATURE -> event.temperature
                    TelemetryMetricType.HUMIDITY -> event.humidity
                    TelemetryMetricType.BAROMETRIC_PRESSURE -> event.pressure
                    TelemetryMetricType.AIR_QUALITY_IAQ -> event.iaq
                    TelemetryMetricType.AIR_QUALITY_CO2 -> event.co2
                    TelemetryMetricType.AIR_QUALITY_PM25 -> event.pm25
                    TelemetryMetricType.SOIL_MOISTURE -> event.soilMoisture
                }
            if (value != null) {
                matchesOperator(value, condition.operator, condition.threshold)
            } else {
                false
            }
        }

        is AutomationCondition.LocationFilter -> {
            val centerLat = condition.centerLatitude
            val centerLon = condition.centerLongitude
            when (condition.type) {
                LocationConditionType.WITHIN_GEOFENCE -> {
                    if (
                        event.latitude != null && event.longitude != null && centerLat != null && centerLon != null
                    ) {
                        val dist =
                            calculateDistanceMeters(
                                event.latitude,
                                event.longitude,
                                centerLat,
                                centerLon,
                            )
                        dist <= condition.radiusMeters
                    } else {
                        false
                    }
                }

                LocationConditionType.OUTSIDE_GEOFENCE -> {
                    if (
                        event.latitude != null && event.longitude != null && centerLat != null && centerLon != null
                    ) {
                        val dist =
                            calculateDistanceMeters(
                                event.latitude,
                                event.longitude,
                                centerLat,
                                centerLon,
                            )
                        dist > condition.radiusMeters
                    } else {
                        false
                    }
                }

                LocationConditionType.CLOSER_THAN -> {
                    val dist =
                        if (
                            event.latitude != null &&
                            event.longitude != null &&
                            centerLat != null &&
                            centerLon != null
                        ) {
                            calculateDistanceMeters(
                                event.latitude,
                                event.longitude,
                                centerLat,
                                centerLon,
                            )
                        } else {
                            event.distanceMeters
                        }
                    if (dist != null) dist <= condition.distanceKm * METERS_PER_KM else false
                }

                LocationConditionType.FURTHER_THAN -> {
                    val dist =
                        if (
                            event.latitude != null &&
                            event.longitude != null &&
                            centerLat != null &&
                            centerLon != null
                        ) {
                            calculateDistanceMeters(
                                event.latitude,
                                event.longitude,
                                centerLat,
                                centerLon,
                            )
                        } else {
                            event.distanceMeters
                        }
                    if (dist != null) dist > condition.distanceKm * METERS_PER_KM else false
                }
            }
        }

        is AutomationCondition.NodeFilter -> {
            if (condition.nodeId != null && event.nodeId != condition.nodeId) {
                false
            } else if (
                condition.nameContains.isNotBlank() &&
                event.nodeName?.contains(condition.nameContains, ignoreCase = true) != true
            ) {
                false
            } else if (condition.isFavoriteOnly) {
                val id = event.nodeId
                id != null && nodeRepository?.nodeDBbyNum?.value?.get(id)?.isFavorite == true
            } else {
                true
            }
        }

        is AutomationCondition.MessageFilter -> {
            if (condition.emoji.isNotBlank() && event.emoji != condition.emoji) {
                false
            } else if (condition.pattern.isNotBlank()) {
                val text = event.messageText.orEmpty()
                val matches =
                    if (condition.isRegex) {
                        runCatching { Regex(condition.pattern, RegexOption.IGNORE_CASE).containsMatchIn(text) }
                            .getOrDefault(false)
                    } else {
                        text.contains(condition.pattern, ignoreCase = true)
                    }
                matches
            } else {
                true
            }
        }
    }

    companion object {
        /** Maximum detail length recorded in logs. */
        const val MAX_DETAIL_LENGTH = 512

        /** Number of milliseconds in a second. */
        const val MILLIS_PER_SECOND = 1_000L

        /** Number of meters in one kilometer. */
        const val METERS_PER_KM = 1_000.0

        /** Maximum log entries retained per rule in the database. */
        const val MAX_LOG_ENTRIES_PER_RULE = 500

        /** Rate-limit window in milliseconds (1 minute). */
        const val RATE_LIMIT_WINDOW_MS = 60_000L

        /** Maximum fires within [RATE_LIMIT_WINDOW_MS] for a single rule. */
        const val MAX_FIRES_PER_WINDOW = 10

        /** Maximum chain depth for [AutomationAction.TriggerRule]. */
        const val MAX_CHAIN_DEPTH = 5
    }
}
