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
package org.meshtastic.feature.messaging.compress

internal const val CDF_SCALE = 1 shl 20
internal const val PRECISION = 32
internal const val FULL = 1L shl PRECISION
internal const val HALF = 1L shl (PRECISION - 1)
internal const val QUARTER = 1L shl (PRECISION - 2)
internal const val MASK = FULL - 1L
internal const val THREE_QUARTER = 3L * QUARTER

class SymbolCdf(val symbol: String, val cumLow: Int, val cumHigh: Int)

@Suppress("MagicNumber")
class ArithmeticEncoder {
    private var low: Long = 0L
    private var high: Long = MASK
    private var pending: Int = 0
    private val bits = mutableListOf<Int>()

    private fun emitBit(bit: Int) {
        bits.add(bit)
        val opp = 1 - bit
        for (i in 0 until pending) bits.add(opp)
        pending = 0
    }

    fun encodeSymbol(cumLow: Int, cumHigh: Int, total: Int) {
        val cl = cumLow.toLong()
        val ch = cumHigh.toLong()
        val tot = total.toLong()
        val rng = high - low + 1L
        high = low + (rng * ch) / tot - 1L
        low = low + (rng * cl) / tot
        while (true) {
            if (high < HALF) {
                emitBit(0)
            } else if (low >= HALF) {
                emitBit(1)
                low -= HALF
                high -= HALF
            } else if (low >= QUARTER && high < THREE_QUARTER) {
                pending++
                low -= QUARTER
                high -= QUARTER
            } else {
                break
            }
            low = (low shl 1) and MASK
            high = ((high shl 1) or 1L) and MASK
        }
    }

    fun finishBits(): List<Int> {
        pending++
        if (low < QUARTER) emitBit(0) else emitBit(1)
        return bits
    }
}

@Suppress("MagicNumber")
class ArithmeticDecoder(private val data: ByteArray) {
    private var low: Long = 0L
    private var high: Long = MASK
    private var value: Long = 0L
    private var bitPos: Int = 0
    private val totalBits: Int = data.size * 8

    init {
        for (i in 0 until PRECISION) {
            value = (value shl 1) or readBit().toLong()
        }
    }

    private fun readBit(): Int {
        if (bitPos >= totalBits) return 0
        val bi = bitPos shr 3
        val bit = 7 - (bitPos and 7)
        bitPos++
        return ((data[bi].toInt() and 0xFF) shr bit) and 1
    }

    fun decodeSymbol(cdf: Array<SymbolCdf>, total: Int = CDF_SCALE): String {
        val tot = total.toLong()
        val rng = high - low + 1L
        val scaled = (((value - low + 1L) * tot - 1L) / rng).toInt()
        var lo = 0
        var hi = cdf.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cdf[mid].cumHigh <= scaled) {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        val item = cdf[lo]
        high = low + (rng * item.cumHigh.toLong()) / tot - 1L
        low = low + (rng * item.cumLow.toLong()) / tot
        while (true) {
            if (high < HALF) {
                // nothing
            } else if (low >= HALF) {
                low -= HALF
                high -= HALF
                value -= HALF
            } else if (low >= QUARTER && high < THREE_QUARTER) {
                low -= QUARTER
                high -= QUARTER
                value -= QUARTER
            } else {
                break
            }
            low = (low shl 1) and MASK
            high = ((high shl 1) or 1L) and MASK
            value = ((value shl 1) or readBit().toLong()) and MASK
        }
        return item.symbol
    }
}
