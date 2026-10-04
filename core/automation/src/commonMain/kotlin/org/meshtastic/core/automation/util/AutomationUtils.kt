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

import org.meshtastic.core.automation.model.ComparisonOperator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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

fun matchesOperator(value: Double, operator: ComparisonOperator, threshold: Double): Boolean = when (operator) {
    ComparisonOperator.LESS_THAN -> value < threshold
    ComparisonOperator.GREATER_THAN -> value > threshold
    ComparisonOperator.EQUALS -> abs(value - threshold) < DOUBLE_EPSILON
}
