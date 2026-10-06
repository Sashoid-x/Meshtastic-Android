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
package org.meshtastic.core.automation.util

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.meshtastic.core.automation.model.ComparisonOperator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private const val EARTH_RADIUS_METERS = 6371000.0
private const val DEG_TO_RAD = PI / 180.0
private const val FLOAT_EPSILON = 0.001f

fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = (lat2 - lat1) * DEG_TO_RAD
    val dLon = (lon2 - lon1) * DEG_TO_RAD
    val a = sin(dLat / 2).pow(2) + cos(lat1 * DEG_TO_RAD) * cos(lat2 * DEG_TO_RAD) * sin(dLon / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return EARTH_RADIUS_METERS * c
}

fun matchesOperator(value: Float, operator: ComparisonOperator, threshold: Float): Boolean = when (operator) {
    ComparisonOperator.LESS_THAN -> value < threshold
    ComparisonOperator.GREATER_THAN -> value > threshold
    ComparisonOperator.EQUALS -> abs(value - threshold) < FLOAT_EPSILON
}

private const val DOUBLE_EPSILON = 0.001
private const val DAYS_IN_WEEK = 7
private const val MAX_DAYS_IN_MONTH = 31
private const val MAX_SEARCH_DAYS = 366
private const val HOURS_IN_DAY = 24
private const val MINUTES_IN_HOUR = 60
private const val CRON_FIELDS_COUNT = 5
private const val MAX_CRON_MINUTE = 59
private const val MAX_CRON_HOUR = 23
private const val MAX_CRON_MONTH = 12
private const val MAX_CRON_DAY_OF_WEEK = 7

fun matchesOperator(value: Double, operator: ComparisonOperator, threshold: Double): Boolean = when (operator) {
    ComparisonOperator.LESS_THAN -> value < threshold
    ComparisonOperator.GREATER_THAN -> value > threshold
    ComparisonOperator.EQUALS -> abs(value - threshold) < DOUBLE_EPSILON
}

/** 5-field cron expression parser: minute (0-59), hour (0-23), dayOfMonth (1-31), month (1-12), dayOfWeek (0-7). */
@Suppress("MagicNumber")
data class CronExpression(
    val minutes: Set<Int>,
    val hours: Set<Int>,
    val daysOfMonth: Set<Int>,
    val months: Set<Int>,
    val daysOfWeek: Set<Int>,
) {
    fun matches(minute: Int, hour: Int, dayOfMonth: Int, month: Int, dayOfWeek: Int): Boolean {
        val monthMatch = month in months
        val dowMatch =
            dayOfWeek in daysOfWeek || (dayOfWeek == DAYS_IN_WEEK && (0 in daysOfWeek || DAYS_IN_WEEK in daysOfWeek))
        val domMatch = dayOfMonth in daysOfMonth
        val dayMatch =
            if (daysOfMonth.size == MAX_DAYS_IN_MONTH) {
                dowMatch
            } else if (daysOfWeek.size >= DAYS_IN_WEEK) {
                domMatch
            } else {
                (dowMatch && domMatch)
            }
        val hourMatch = hour in hours
        val minuteMatch = minute in minutes
        return monthMatch && dayMatch && hourMatch && minuteMatch
    }

    /** Calculates the next execution time strictly after [from]. */
    @Suppress("DEPRECATION")
    fun nextExecution(from: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault()): Instant? {
        val fromLocal = from.toLocalDateTime(timeZone)
        var cursor =
            LocalDateTime(
                year = fromLocal.year,
                monthNumber = fromLocal.monthNumber,
                dayOfMonth = fromLocal.dayOfMonth,
                hour = fromLocal.hour,
                minute = fromLocal.minute,
                second = 0,
                nanosecond = 0,
            )
                .toInstant(timeZone) + 1.minutes

        // Max search window: 366 days
        val maxMinutes = MAX_SEARCH_DAYS * HOURS_IN_DAY * MINUTES_IN_HOUR
        var count = 0
        while (count < maxMinutes) {
            val local = cursor.toLocalDateTime(timeZone)
            if (
                matches(
                    minute = local.minute,
                    hour = local.hour,
                    dayOfMonth = local.dayOfMonth,
                    month = local.monthNumber,
                    dayOfWeek = local.dayOfWeek.isoDayNumber,
                )
            ) {
                return cursor
            }
            cursor += 1.minutes
            count++
        }
        return null
    }

    companion object {
        @Suppress("ReturnCount")
        fun parse(expression: String): CronExpression? {
            val parts = expression.trim().split(Regex("\\s+"))
            if (parts.size != CRON_FIELDS_COUNT) return null
            val mins = parseField(parts[0], 0, MAX_CRON_MINUTE) ?: return null
            val hrs = parseField(parts[1], 0, MAX_CRON_HOUR) ?: return null
            val dom = parseField(parts[2], 1, MAX_DAYS_IN_MONTH) ?: return null
            val mon = parseField(parts[3], 1, MAX_CRON_MONTH) ?: return null
            val dow = parseField(parts[4], 0, MAX_CRON_DAY_OF_WEEK) ?: return null
            return CronExpression(mins, hrs, dom, mon, dow)
        }

        fun isValid(expression: String): Boolean = parse(expression) != null

        @Suppress("ReturnCount", "CyclomaticComplexMethod")
        private fun parseField(field: String, min: Int, max: Int): Set<Int>? {
            val result = mutableSetOf<Int>()
            val items = field.split(",")
            for (item in items) {
                val trimmed = item.trim()
                if (trimmed.isEmpty()) return null
                if (trimmed == "*") {
                    result.addAll(min..max)
                } else if (trimmed.startsWith("*/")) {
                    val step = trimmed.substring(2).toIntOrNull() ?: return null
                    if (step <= 0) return null
                    for (i in min..max step step) {
                        result.add(i)
                    }
                } else if ("-" in trimmed) {
                    val rangeParts = trimmed.split("-")
                    if (rangeParts.size != 2) return null
                    val start = rangeParts[0].toIntOrNull() ?: return null
                    val endWithStep = rangeParts[1]
                    if ("/" in endWithStep) {
                        val subParts = endWithStep.split("/")
                        if (subParts.size != 2) return null
                        val end = subParts[0].toIntOrNull() ?: return null
                        val step = subParts[1].toIntOrNull() ?: return null
                        if (start !in min..max || end !in min..max || start > end || step <= 0) return null
                        for (i in start..end step step) result.add(i)
                    } else {
                        val end = endWithStep.toIntOrNull() ?: return null
                        if (start !in min..max || end !in min..max || start > end) return null
                        result.addAll(start..end)
                    }
                } else {
                    val single = trimmed.toIntOrNull() ?: return null
                    if (single !in min..max) return null
                    result.add(single)
                }
            }
            return if (result.isEmpty()) null else result
        }
    }
}
