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
    "ComposableParamOrder",
    "TooGenericExceptionCaught",
    "ReturnCount",
    "NestedBlockDepth",
    "MaxLineLength",
)

package org.meshtastic.feature.messaging.image

import kotlin.math.min

data class MonochromeResolutionPreset(val index: Int, val width: Int, val height: Int, val name: String) {
    val totalPixels: Int
        get() = width * height

    val byteSize: Int
        get() = (totalPixels + 7) / 8

    val aspectRatio: Float
        get() = width.toFloat() / height.toFloat()
}

data class DecodedMonochromeImage(val presetIndex: Int, val width: Int, val height: Int, val pixels: IntArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedMonochromeImage) return false
        return presetIndex == other.presetIndex &&
            width == other.width &&
            height == other.height &&
            pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = presetIndex
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + pixels.contentHashCode()
        return result
    }
}

@Suppress("TooManyFunctions")
object MonochromeImageCodec {
    const val PORT_NUM = 264
    private const val ENC_RAW = 0
    private const val ENC_HRLE = 1
    private const val ENC_VRLE = 2
    private const val ENC_DELTA = 3

    val PRESETS =
        listOf(
            MonochromeResolutionPreset(0, 39, 40, "39x40 (Square)"),
            MonochromeResolutionPreset(1, 32, 32, "32x32 (Small Sq)"),
            MonochromeResolutionPreset(2, 48, 32, "48x32 (3:2)"),
            MonochromeResolutionPreset(3, 32, 48, "32x48 (2:3)"),
            MonochromeResolutionPreset(4, 64, 24, "64x24 (8:3)"),
            MonochromeResolutionPreset(5, 24, 64, "24x64 (3:8)"),
            MonochromeResolutionPreset(6, 44, 36, "44x36 (11:9)"),
            MonochromeResolutionPreset(7, 36, 44, "36x44 (9:11)"),
            MonochromeResolutionPreset(8, 52, 30, "52x30 (16:9)"),
            MonochromeResolutionPreset(9, 30, 52, "30x52 (9:16)"),
        )

    fun getPreset(index: Int): MonochromeResolutionPreset = PRESETS.getOrElse(index) { PRESETS[0] }

    private fun rleEncode(bits: BooleanArray): ByteArray {
        val out = mutableListOf<Byte>()
        var color = false
        var run = 0
        for (bit in bits) {
            if (bit == color) {
                run++
                if (run == 255) {
                    out.add(255.toByte())
                    color = !color
                    out.add(0.toByte())
                    color = !color
                    run = 0
                }
            } else {
                out.add(run.toByte())
                color = bit
                run = 1
            }
        }
        out.add(run.toByte())
        return out.toByteArray()
    }

    private fun rleDecode(rleBytes: ByteArray, pixelCount: Int): BooleanArray {
        val bits = BooleanArray(pixelCount)
        var idx = 0
        var color = false
        for (b in rleBytes) {
            val run = b.toInt() and 0xFF
            repeat(run) { if (idx < pixelCount) bits[idx++] = color }
            color = !color
        }
        return bits
    }

    private fun bitPack(bits: BooleanArray, count: Int): ByteArray {
        val out = ByteArray((count + 7) / 8)
        for (i in 0 until count) {
            if (i < bits.size && bits[i]) {
                out[i / 8] = (out[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
            }
        }
        return out
    }

    private fun bitUnpack(bytes: ByteArray, count: Int): BooleanArray {
        val bits = BooleanArray(count)
        for (i in 0 until count) {
            val byteIdx = i / 8
            if (byteIdx >= bytes.size) break
            bits[i] = ((bytes[byteIdx].toInt() shr (7 - i % 8)) and 1) == 1
        }
        return bits
    }

    private fun transpose(bits: BooleanArray, width: Int, height: Int): BooleanArray {
        val out = BooleanArray(width * height)
        for (y in 0 until height) for (x in 0 until width) out[x * height + y] = bits[y * width + x]
        return out
    }

    private fun deltaEncode(bits: BooleanArray, width: Int, height: Int): BooleanArray {
        val out = BooleanArray(width * height)
        for (x in 0 until width) out[x] = bits[x]
        for (y in 1 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                out[i] = bits[i] xor bits[i - width]
            }
        }
        return out
    }

    private fun deltaDecode(delta: BooleanArray, width: Int, height: Int): BooleanArray {
        val out = BooleanArray(width * height)
        for (x in 0 until width) out[x] = delta[x]
        for (y in 1 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                out[i] = delta[i] xor out[i - width]
            }
        }
        return out
    }

    private fun makePacket(enc: Int, presetIndex: Int, payload: ByteArray): ByteArray {
        val result = ByteArray(1 + payload.size)
        result[0] = ((enc shl 6) or (presetIndex and 0x3F)).toByte()
        payload.copyInto(result, 1)
        return result
    }

    fun encode(monoBits: BooleanArray, presetIndex: Int): ByteArray {
        val preset = getPreset(presetIndex)
        val pixelCount = min(monoBits.size, preset.totalPixels)
        val bits = if (monoBits.size == pixelCount) monoBits else monoBits.copyOf(pixelCount)
        val raw = makePacket(ENC_RAW, presetIndex, bitPack(bits, pixelCount))
        var best = raw
        val hRle = makePacket(ENC_HRLE, presetIndex, rleEncode(bits))
        if (hRle.size < best.size) best = hRle
        val vRle = makePacket(ENC_VRLE, presetIndex, rleEncode(transpose(bits, preset.width, preset.height)))
        if (vRle.size < best.size) best = vRle
        val dRle = makePacket(ENC_DELTA, presetIndex, rleEncode(deltaEncode(bits, preset.width, preset.height)))
        if (dRle.size < best.size) best = dRle
        return best
    }

    fun decode(bytes: ByteArray): DecodedMonochromeImage? {
        if (bytes.isEmpty()) return null
        val header = bytes[0].toInt() and 0xFF
        val enc = (header shr 6) and 0x3
        val presetIndex = header and 0x3F
        val preset = PRESETS.getOrNull(presetIndex) ?: return null
        val pixelCount = preset.totalPixels
        val payload = bytes.copyOfRange(1, bytes.size)
        val bits: BooleanArray =
            when (enc) {
                ENC_RAW -> bitUnpack(payload, pixelCount)
                ENC_HRLE -> rleDecode(payload, pixelCount)
                ENC_VRLE -> transpose(rleDecode(payload, pixelCount), preset.height, preset.width)
                ENC_DELTA -> deltaDecode(rleDecode(payload, pixelCount), preset.width, preset.height)
                else -> return null
            }
        val pixels =
            IntArray(pixelCount) { i ->
                if (i < bits.size && bits[i]) {
                    0xFFFFFFFF.toInt()
                } else {
                    0xFF000000.toInt()
                }
            }
        return DecodedMonochromeImage(
            presetIndex = presetIndex,
            width = preset.width,
            height = preset.height,
            pixels = pixels,
        )
    }

    private val bayerMatrix = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    fun processToMonochrome(
        grayValues: FloatArray,
        width: Int,
        height: Int,
        brightness: Float = 0f,
        contrast: Float = 1f,
        ditherAmount: Float = 1f,
        invert: Boolean = false,
    ): BooleanArray {
        val result = BooleanArray(grayValues.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (i >= grayValues.size) break
                var valNorm = (grayValues[i] - 0.5f) * contrast + 0.5f + brightness
                valNorm = kotlin.math.max(0f, kotlin.math.min(1f, valNorm))
                val bayerVal = (bayerMatrix[(y % 4) * 4 + (x % 4)] + 0.5f) / 16f
                val threshold = 0.5f + (bayerVal - 0.5f) * ditherAmount
                result[i] = if (invert) valNorm < threshold else valNorm >= threshold
            }
        }
        return result
    }
}
