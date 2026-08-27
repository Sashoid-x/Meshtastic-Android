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
    "TooGenericExceptionCaught",
)

package org.meshtastic.feature.messaging.compress

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.meshtastic.core.resources.Res

internal const val TC_EMPTY = "!"
internal const val TC_COMPRESSED_NOESC = "\""
internal const val TC_COMPRESSED_ESC = "#"
internal val TC_MARKERS = setOf(TC_EMPTY, TC_COMPRESSED_NOESC, TC_COMPRESSED_ESC)

internal fun String.toCodePointStrings(): List<String> {
    val list = mutableListOf<String>()
    var i = 0
    while (i < length) {
        val high = this[i]
        if (high.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            list.add(substring(i, i + 2))
            i += 2
        } else {
            list.add(high.toString())
            i++
        }
    }
    return list
}

private fun bitsToBytes(bits: List<Int>): ByteArray {
    val len = (bits.size + 7) / 8
    val out = ByteArray(len)
    for (i in bits.indices) {
        if (bits[i] != 0) {
            val byteIdx = i shr 3
            val bitIdx = 7 - (i and 7)
            out[byteIdx] = (out[byteIdx].toInt() or (1 shl bitIdx)).toByte()
        }
    }
    return out
}

private fun bitsToMinBytes(bits: List<Int>): ByteArray {
    val out = bitsToBytes(bits)
    var end = out.size
    while (end > 0 && out[end - 1].toInt() == 0) {
        end--
    }
    return out.copyOfRange(0, end)
}

internal data class CompressAcResult(val flags: Int, val bits: List<Int>)

internal fun compressAcBits(text: String, model: NGramModel): CompressAcResult {
    val symbols = text.toCodePointStrings()
    var hasExtras = false
    for (ch in symbols) {
        if (!model.vocabSet.contains(ch)) {
            hasExtras = true
            break
        }
    }
    val flags = if (hasExtras) 1 else 0
    val enc = ArithmeticEncoder()
    var context = BOS.repeat(model.order)

    for (ch in symbols) {
        val cdf = model.getCDF(context, hasExtras)
        if (model.vocabSet.contains(ch)) {
            for (item in cdf) {
                if (item.symbol == ch) {
                    enc.encodeSymbol(item.cumLow, item.cumHigh, CDF_SCALE)
                    break
                }
            }
        } else {
            for (item in cdf) {
                if (item.symbol == ESC) {
                    enc.encodeSymbol(item.cumLow, item.cumHigh, CDF_SCALE)
                    break
                }
            }
            encodeCodepoint(enc, ch.codePointAt(0))
        }
        context = (context + ch).takeLast(model.order)
    }

    val cdf = model.getCDF(context, hasExtras)
    for (item in cdf) {
        if (item.symbol == EOF) {
            enc.encodeSymbol(item.cumLow, item.cumHigh, CDF_SCALE)
            break
        }
    }

    return CompressAcResult(flags, enc.finishBits())
}

object MeshTextCompressor {
    private var model: NGramModel? = null
    private val mutex = Mutex()

    @OptIn(ExperimentalResourceApi::class)
    suspend fun getOrLoadModel(): NGramModel = mutex.withLock {
        model?.let {
            return@withLock it
        }
        withContext(Dispatchers.IO) {
            val jsonBytes = Res.readBytes("files/model_en_ru.json")
            val jsonString = jsonBytes.decodeToString()
            val loadedModel = NGramModel.fromJsonString(jsonString)
            model = loadedModel
            loadedModel
        }
    }

    fun isCompressed(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        val first = text[0].toString()
        return first == TC_COMPRESSED_NOESC || first == TC_COMPRESSED_ESC
    }

    suspend fun compress(text: String): String = compress(text, getOrLoadModel())

    suspend fun decompress(text: String): String = decompress(text, getOrLoadModel())

    fun compressSync(text: String): String = model?.let { compress(text, it) } ?: text

    fun decompressSync(text: String): String = model?.let { decompress(text, it) } ?: text

    fun compress(text: String, model: NGramModel): String {
        if (text.isEmpty()) return TC_EMPTY
        val (flags, bits) = compressAcBits(text, model)
        val payload = bitsToMinBytes(bits)
        val marker = if ((flags and 1) != 0) TC_COMPRESSED_ESC else TC_COMPRESSED_NOESC
        val compressed = marker + Base91.encode(payload)
        if (compressed.length >= text.length && !TC_MARKERS.contains(text[0].toString())) {
            return text
        }
        return compressed
    }

    fun decompress(text: String, model: NGramModel): String {
        if (text.isEmpty()) return ""
        val head = text[0].toString()
        if (head == TC_EMPTY) return ""
        val hasEscapes =
            when (head) {
                TC_COMPRESSED_NOESC -> false
                TC_COMPRESSED_ESC -> true
                else -> return text
            }
        val payload =
            try {
                Base91.decode(text.substring(1))
            } catch (_: Throwable) {
                return text
            }
        return decompressPayload(payload, hasEscapes, model)
    }

    private fun decompressPayload(payload: ByteArray, hasEscapes: Boolean, model: NGramModel): String {
        val dec = ArithmeticDecoder(payload)
        var context = BOS.repeat(model.order)
        val sb = StringBuilder()
        for (i in 0 until 4096) {
            val cdf = model.getCDF(context, hasEscapes)
            val sym = dec.decodeSymbol(cdf)
            if (sym == EOF) break
            val outCh =
                if (sym == ESC && hasEscapes) {
                    val cp = decodeCodepoint(dec)
                    codePointToString(cp)
                } else {
                    sym
                }
            sb.append(outCh)
            context = (context + outCh).takeLast(model.order)
        }
        return sb.toString()
    }
}
