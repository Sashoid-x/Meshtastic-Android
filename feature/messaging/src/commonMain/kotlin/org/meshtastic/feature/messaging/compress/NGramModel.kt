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
@file:Suppress(
    "LongMethod",
    "MagicNumber",
    "CyclomaticComplexMethod",
    "ReturnCount",
    "NestedBlockDepth",
    "MaxLineLength",
)

package org.meshtastic.feature.messaging.compress

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

internal const val BOS = "\u0002"
internal const val EOF = "\u0003"
internal const val ESC = "\u0004"

private const val SCRIPT_BOOST = 8
private const val ESC_PROB = 500

private val UNICODE_BLOCKS =
    arrayOf(
        intArrayOf(0, 0x0400, 0x04FF), // Cyrillic
        intArrayOf(1, 0x0100, 0x024F), // Latin Extended
        intArrayOf(2, 0x2000, 0x206F), // General Punctuation
        intArrayOf(3, 0x2190, 0x21FF), // Arrows
        intArrayOf(4, 0x2600, 0x27BF), // Misc Symbols + Dingbats
        intArrayOf(5, 0x1F300, 0x1F5FF), // Misc Symbols and Pictographs
        intArrayOf(6, 0x1F600, 0x1F64F), // Emoticons
        intArrayOf(7, 0x1F900, 0x1F9FF), // Supplemental Symbols and Pictographs
        intArrayOf(8, 0xFE00, 0xFE0F), // Variation Selectors
        intArrayOf(9, 0x1FA70, 0x1FAFF), // Symbols and Pictographs Extended-A
    )

private val NUM_BLOCKS = UNICODE_BLOCKS.size
private val FALLBACK_BLOCK_ID = NUM_BLOCKS
private val TOTAL_BLOCK_IDS = NUM_BLOCKS + 1

internal fun String.codePointAt(index: Int = 0): Int {
    if (index >= length) return 0
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) {
            val highCode = high.code - 0xD800
            val lowCode = low.code - 0xDC00
            return (highCode shl 10) + lowCode + 0x10000
        }
    }
    return high.code
}

internal fun codePointToString(cp: Int): String {
    if (cp < 0x10000) {
        return cp.toChar().toString()
    }
    val offset = cp - 0x10000
    val highOffset = 0xD800 + (offset ushr 10)
    val lowOffset = 0xDC00 + (offset and 0x3FF)
    val high = highOffset.toChar()
    val low = lowOffset.toChar()
    return "$high$low"
}

internal fun charScript(ch: String): String {
    val cp = ch.codePointAt(0)
    return when {
        cp < 0x0041 -> "Common"
        cp <= 0x024F || (cp in 0x1E00..0x1EFF) -> "Latin"
        cp in 0x0400..0x052F -> "Cyrillic"
        cp > 0xFFFF -> "Common"
        else -> "Other"
    }
}

internal fun encodeCodepoint(encoder: ArithmeticEncoder, cp: Int) {
    for (block in UNICODE_BLOCKS) {
        val bid = block[0]
        val start = block[1]
        val end = block[2]
        if (cp in start..end) {
            encoder.encodeSymbol(bid, bid + 1, TOTAL_BLOCK_IDS)
            val size = end - start + 1
            val off = cp - start
            encoder.encodeSymbol(off, off + 1, size)
            return
        }
    }
    encoder.encodeSymbol(FALLBACK_BLOCK_ID, FALLBACK_BLOCK_ID + 1, TOTAL_BLOCK_IDS)
    val byte0 = cp and 0x7F
    val byte1 = (cp shr 7) and 0x7F
    val byte2 = (cp shr 14) and 0x7F
    encoder.encodeSymbol(byte0, byte0 + 1, 128)
    encoder.encodeSymbol(byte1, byte1 + 1, 128)
    encoder.encodeSymbol(byte2, byte2 + 1, 128)
}

internal fun decodeCodepoint(decoder: ArithmeticDecoder): Int {
    val blockCdf = Array(TOTAL_BLOCK_IDS) { idx -> SymbolCdf(idx.toString(), idx, idx + 1) }
    val sym = decoder.decodeSymbol(blockCdf, TOTAL_BLOCK_IDS)
    val bid = sym.toInt()
    if (bid < NUM_BLOCKS) {
        val block = UNICODE_BLOCKS[bid]
        val start = block[1]
        val end = block[2]
        val size = end - start + 1
        val offCdf = Array(size) { idx -> SymbolCdf(idx.toString(), idx, idx + 1) }
        val offSym = decoder.decodeSymbol(offCdf, size)
        val off = offSym.toInt()
        return start + off
    }
    val cdf128 = Array(128) { idx -> SymbolCdf(idx.toString(), idx, idx + 1) }
    val b0 = decoder.decodeSymbol(cdf128, 128).toInt()
    val b1 = decoder.decodeSymbol(cdf128, 128).toInt()
    val b2 = decoder.decodeSymbol(cdf128, 128).toInt()
    return b0 or (b1 shl 7) or (b2 shl 14)
}

class NGramModel(val order: Int, val vocab: List<String>, private val counts: List<Map<String, Map<String, Int>>>) {
    val vocabSet: Set<String> = vocab.toSet()
    private val vocabIdx: Map<String, Int> = vocab.mapIndexed { index, s -> s to index }.toMap()
    private val totals: List<Map<String, Int>>
    private val charScripts: Map<String, String> = vocab.associateWith { charScript(it) }
    private val cdfCache = mutableMapOf<String, Array<SymbolCdf>>()

    init {
        val tList = mutableListOf<Map<String, Int>>()
        for (n in 0..order) {
            val countMap = counts.getOrNull(n) ?: emptyMap()
            val totalMap = mutableMapOf<String, Int>()
            for ((ctx, charCounts) in countMap) {
                var sum = 0
                for (c in charCounts.values) {
                    sum += c
                }
                totalMap[ctx] = sum
            }
            tList.add(totalMap)
        }
        totals = tList
    }

    fun getCDF(context: String, hasEscapes: Boolean): Array<SymbolCdf> {
        val prefix = if (hasEscapes) "1|" else "0|"
        val key = prefix + context
        val cached = cdfCache[key]
        if (cached != null) return cached
        val cdf = computeCDF(context, hasEscapes)
        if (cdfCache.size < 50000) {
            cdfCache[key] = cdf
        }
        return cdf
    }

    private data class ActiveOrder(val n: Int, val ctx: String, val total: Int, val weight: Double)

    private fun computeCDF(context: String, hasEscapes: Boolean): Array<SymbolCdf> {
        val nVocab = vocab.size

        // Active orders and weights
        val active = mutableListOf<ActiveOrder>()
        var totalW = 0.0
        var maxMatchOrder = -1
        for (n in order downTo 0) {
            val ctx = if (n > 0) context.takeLast(n) else ""
            val totalForCtx = totals[n][ctx]
            if (totalForCtx != null && totalForCtx > 0) {
                val tDbl = totalForCtx.toDouble()
                val confidence = tDbl / (tDbl + 1.5)
                val nPlusOne = (n + 1).toDouble()
                val cubic = nPlusOne * nPlusOne * nPlusOne
                val w = cubic * ln(1.0 + tDbl) * confidence
                active.add(ActiveOrder(n, ctx, totalForCtx, w))
                totalW += w
                if (n > maxMatchOrder) {
                    maxMatchOrder = n
                }
            }
        }
        val scriptBoost = if (maxMatchOrder <= 2) SCRIPT_BOOST * 4 else SCRIPT_BOOST

        // Detect context script
        var ctxScript: String? = null
        for (i in context.length - 1 downTo 0) {
            val ch = context[i].toString()
            if (ch != BOS) {
                val s = charScripts[ch] ?: charScript(ch)
                if (s != "Common") {
                    ctxScript = s
                    break
                }
                if (ctxScript == null) {
                    ctxScript = s
                }
            }
        }

        val compat = if (ctxScript != null && ctxScript != "Common") setOf(ctxScript, "Common") else null

        // Epsilon frequencies
        val freqs = IntArray(nVocab)
        var epsilonTotal = 0
        for (i in 0 until nVocab) {
            val ch = vocab[i]
            val sc = charScripts[ch] ?: "Other"
            val eps =
                when {
                    ch == ESC -> if (hasEscapes) ESC_PROB else 0
                    compat != null && compat.contains(sc) -> scriptBoost
                    sc == "Common" -> max(1, scriptBoost / 3)
                    else -> 1
                }
            freqs[i] = eps
            epsilonTotal += eps
        }

        if (epsilonTotal > CDF_SCALE / 2) {
            val factor = (CDF_SCALE / 2.0) / epsilonTotal.toDouble()
            epsilonTotal = 0
            for (i in 0 until nVocab) {
                val scaled = (freqs[i].toDouble() * factor).toInt()
                val adjusted = max(1, scaled)
                freqs[i] = adjusted
                epsilonTotal += adjusted
            }
        }

        if (totalW > 0.0) {
            val scale = (CDF_SCALE - epsilonTotal).toDouble()
            for (ao in active) {
                val countsN = counts[ao.n][ao.ctx]
                if (countsN != null) {
                    val factor = (ao.weight / totalW) * scale / ao.total.toDouble()
                    for ((ch, count) in countsN) {
                        val idx = vocabIdx[ch]
                        if (idx != null) {
                            val addVal = (count.toDouble() * factor).toInt()
                            freqs[idx] += addVal
                        }
                    }
                }
            }
        }

        // Normalize exactly to CDF_SCALE
        var total = 0
        for (f in freqs) {
            total += f
        }
        if (total != CDF_SCALE) {
            val diff = CDF_SCALE - total
            if (diff > 0) {
                var mi = 0
                for (i in 1 until nVocab) {
                    if (freqs[i] > freqs[mi]) {
                        mi = i
                    }
                }
                freqs[mi] += diff
            } else {
                val idxs = Array(nVocab) { idx -> idx }
                idxs.sortByDescending { idx -> freqs[idx] }
                var remaining = -diff
                for (idx in idxs) {
                    if (remaining <= 0) break
                    val can = freqs[idx] - 1
                    val rm = min(can, remaining)
                    freqs[idx] -= rm
                    remaining -= rm
                }
            }
        }

        val cdf = Array(nVocab) { i -> SymbolCdf(vocab[i], 0, 0) }
        var cum = 0
        for (i in 0 until nVocab) {
            val f = freqs[i]
            cdf[i] = SymbolCdf(vocab[i], cum, cum + f)
            cum += f
        }
        return cdf
    }

    companion object {
        fun fromJsonString(jsonString: String): NGramModel {
            val parsedElement: JsonElement = Json.parseToJsonElement(jsonString)
            val jsonObject: JsonObject = parsedElement.jsonObject
            val oElem = jsonObject["o"]
            val order = if (oElem != null) oElem.jsonPrimitive.int else 11

            val vElem = jsonObject["v"]
            val vocabList = mutableListOf<String>()
            if (vElem != null) {
                val vArray: JsonArray = vElem.jsonArray
                for (item in vArray) {
                    vocabList.add(item.jsonPrimitive.content)
                }
            }

            if (!vocabList.contains(EOF)) {
                vocabList.add(EOF)
            }
            if (!vocabList.contains(ESC)) {
                vocabList.add(ESC)
            }
            vocabList.sort()

            val cElem = jsonObject["c"]
            val cArray: JsonArray = if (cElem != null) cElem.jsonArray else JsonArray(emptyList())
            val countsList = mutableListOf<Map<String, Map<String, Int>>>()

            for (n in 0..order) {
                val orderElem = cArray.getOrNull(n)
                val orderObj: JsonObject = if (orderElem != null) orderElem.jsonObject else JsonObject(emptyMap())
                val orderMap = mutableMapOf<String, Map<String, Int>>()
                for ((ctx, charCountsElement) in orderObj) {
                    val charMap = mutableMapOf<String, Int>()
                    val charCountsObj = charCountsElement.jsonObject
                    for ((ch, countElement) in charCountsObj) {
                        charMap[ch] = countElement.jsonPrimitive.int
                    }
                    orderMap[ctx] = charMap
                }
                countsList.add(orderMap)
            }
            return NGramModel(order, vocabList, countsList)
        }
    }
}
