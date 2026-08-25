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
    const val ENC_RAW = 0
    const val ENC_BLOCK_4X4 = 1
    const val ENC_BLOCK_8X8 = 2
    const val ENC_VAR_RLE_H = 3
    const val ENC_VAR_RLE_V = 4
    const val ENC_DELTA_2D = 5
    const val ENC_LZSS = 6

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

    private class BitWriter(initialCapacity: Int = 64) {
        private var buffer = ByteArray(initialCapacity)
        var bitCount = 0
            private set

        private fun ensureCapacity(additionalBits: Int) {
            val requiredBytes = (bitCount + additionalBits + 7) / 8
            if (requiredBytes > buffer.size) {
                var newSize = buffer.size * 2
                while (newSize < requiredBytes) newSize *= 2
                buffer = buffer.copyOf(newSize)
            }
        }

        fun writeBit(bit: Boolean) {
            ensureCapacity(1)
            if (bit) {
                val byteIdx = bitCount / 8
                val bitOffset = 7 - (bitCount % 8)
                buffer[byteIdx] = (buffer[byteIdx].toInt() or (1 shl bitOffset)).toByte()
            }
            bitCount++
        }

        fun writeBits(value: Int, count: Int) {
            ensureCapacity(count)
            for (i in count - 1 downTo 0) {
                val bit = ((value shr i) and 1) == 1
                writeBit(bit)
            }
        }

        fun toByteArray(): ByteArray {
            val byteLen = (bitCount + 7) / 8
            return buffer.copyOf(byteLen)
        }
    }

    private class BitReader(private val bytes: ByteArray) {
        var bitPos = 0
            private set

        val hasBits: Boolean
            get() = bitPos < bytes.size * 8

        fun readBit(): Boolean {
            if (bitPos >= bytes.size * 8) return false
            val byteIdx = bitPos / 8
            val bitOffset = 7 - (bitPos % 8)
            bitPos++
            return ((bytes[byteIdx].toInt() shr bitOffset) and 1) == 1
        }

        fun readBits(count: Int): Int {
            var value = 0
            for (i in 0 until count) {
                value = (value shl 1) or (if (readBit()) 1 else 0)
            }
            return value
        }
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
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[x * height + y] = bits[y * width + x]
            }
        }
        return out
    }

    private fun writeVarRleRun(writer: BitWriter, run: Int) {
        when {
            run == 1 -> writer.writeBits(0b00, 2)

            run in 2..3 -> {
                writer.writeBits(0b01, 2)
                writer.writeBits(run - 2, 1)
            }

            run in 4..7 -> {
                writer.writeBits(0b100, 3)
                writer.writeBits(run - 4, 2)
            }

            run in 8..15 -> {
                writer.writeBits(0b101, 3)
                writer.writeBits(run - 8, 3)
            }

            run in 16..31 -> {
                writer.writeBits(0b1100, 4)
                writer.writeBits(run - 16, 4)
            }

            run in 32..63 -> {
                writer.writeBits(0b1101, 4)
                writer.writeBits(run - 32, 5)
            }

            run in 64..255 -> {
                writer.writeBits(0b1110, 4)
                writer.writeBits(run - 64, 8)
            }

            else -> {
                writer.writeBits(0b1111, 4)
                writer.writeBits(run - 256, 12)
            }
        }
    }

    private fun readVarRleRun(reader: BitReader): Int {
        val tag2 = reader.readBits(2)
        return when (tag2) {
            0b00 -> 1

            0b01 -> 2 + reader.readBits(1)

            0b10 -> {
                val b = reader.readBit()
                if (!b) 4 + reader.readBits(2) else 8 + reader.readBits(3)
            }

            else -> {
                val tag4 = reader.readBits(2)
                when (tag4) {
                    0b00 -> 16 + reader.readBits(4)
                    0b01 -> 32 + reader.readBits(5)
                    0b10 -> 64 + reader.readBits(8)
                    else -> 256 + reader.readBits(12)
                }
            }
        }
    }

    private fun encodeVarRle(bits: BooleanArray): ByteArray {
        if (bits.isEmpty()) return ByteArray(0)
        val writer = BitWriter()
        var currentColor = bits[0]
        writer.writeBit(currentColor)

        var run = 0
        for (b in bits) {
            if (b == currentColor) {
                run++
            } else {
                writeVarRleRun(writer, run)
                currentColor = b
                run = 1
            }
        }
        writeVarRleRun(writer, run)
        return writer.toByteArray()
    }

    private fun decodeVarRle(bytes: ByteArray, count: Int): BooleanArray {
        val bits = BooleanArray(count)
        if (bytes.isEmpty()) return bits
        val reader = BitReader(bytes)
        var currentColor = reader.readBit()
        var written = 0
        while (written < count && reader.hasBits) {
            val run = readVarRleRun(reader)
            val toWrite = min(run, count - written)
            for (i in 0 until toWrite) {
                bits[written++] = currentColor
            }
            currentColor = !currentColor
        }
        return bits
    }

    private fun encodeBlock4x4(bits: BooleanArray, width: Int, height: Int): ByteArray {
        val writer = BitWriter()
        val bxCount = (width + 3) / 4
        val byCount = (height + 3) / 4

        for (by in 0 until byCount) {
            for (bx in 0 until bxCount) {
                var allZero = true
                var allOne = true
                val startX = bx * 4
                val startY = by * 4

                for (py in 0 until 4) {
                    val y = startY + py
                    for (px in 0 until 4) {
                        val x = startX + px
                        val bit = if (x < width && y < height) bits[y * width + x] else false
                        if (bit) allZero = false else allOne = false
                    }
                }

                if (allZero) {
                    writer.writeBit(false) // 0 -> all 0
                } else if (allOne) {
                    writer.writeBits(0b10, 2) // 10 -> all 1
                } else {
                    writer.writeBits(0b11, 2) // 11 -> mixed
                    for (py in 0 until 4) {
                        val y = startY + py
                        for (px in 0 until 4) {
                            val x = startX + px
                            if (x < width && y < height) {
                                writer.writeBit(bits[y * width + x])
                            }
                        }
                    }
                }
            }
        }
        return writer.toByteArray()
    }

    private fun decodeBlock4x4(bytes: ByteArray, width: Int, height: Int): BooleanArray {
        val bits = BooleanArray(width * height)
        val reader = BitReader(bytes)
        val bxCount = (width + 3) / 4
        val byCount = (height + 3) / 4

        for (by in 0 until byCount) {
            for (bx in 0 until bxCount) {
                val startX = bx * 4
                val startY = by * 4
                val isMixed = reader.readBit()
                if (!isMixed) {
                    // All 0s: nothing to set (already false)
                } else {
                    val isAllOne = !reader.readBit()
                    if (isAllOne) {
                        for (py in 0 until 4) {
                            val y = startY + py
                            for (px in 0 until 4) {
                                val x = startX + px
                                if (x < width && y < height) {
                                    bits[y * width + x] = true
                                }
                            }
                        }
                    } else {
                        // Mixed: read in-bounds bits
                        for (py in 0 until 4) {
                            val y = startY + py
                            for (px in 0 until 4) {
                                val x = startX + px
                                if (x < width && y < height) {
                                    bits[y * width + x] = reader.readBit()
                                }
                            }
                        }
                    }
                }
            }
        }
        return bits
    }

    private fun encodeBlock8x8(bits: BooleanArray, width: Int, height: Int): ByteArray {
        val writer = BitWriter()
        val bx8Count = (width + 7) / 8
        val by8Count = (height + 7) / 8

        for (by8 in 0 until by8Count) {
            for (bx8 in 0 until bx8Count) {
                var allZero8 = true
                var allOne8 = true
                val startX8 = bx8 * 8
                val startY8 = by8 * 8

                for (py in 0 until 8) {
                    val y = startY8 + py
                    for (px in 0 until 8) {
                        val x = startX8 + px
                        val bit = if (x < width && y < height) bits[y * width + x] else false
                        if (bit) allZero8 = false else allOne8 = false
                    }
                }

                if (allZero8) {
                    writer.writeBit(false) // 0
                } else if (allOne8) {
                    writer.writeBits(0b10, 2) // 10
                } else {
                    writer.writeBits(0b11, 2) // 11
                    // 4 sub-blocks of 4x4
                    for (subY in 0 until 2) {
                        for (subX in 0 until 2) {
                            val startX4 = startX8 + subX * 4
                            val startY4 = startY8 + subY * 4
                            var allZero4 = true
                            var allOne4 = true
                            for (py in 0 until 4) {
                                val y = startY4 + py
                                for (px in 0 until 4) {
                                    val x = startX4 + px
                                    val bit = if (x < width && y < height) bits[y * width + x] else false
                                    if (bit) allZero4 = false else allOne4 = false
                                }
                            }

                            if (allZero4) {
                                writer.writeBit(false)
                            } else if (allOne4) {
                                writer.writeBits(0b10, 2)
                            } else {
                                writer.writeBits(0b11, 2)
                                for (py in 0 until 4) {
                                    val y = startY4 + py
                                    for (px in 0 until 4) {
                                        val x = startX4 + px
                                        if (x < width && y < height) {
                                            writer.writeBit(bits[y * width + x])
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return writer.toByteArray()
    }

    private fun decodeBlock8x8(bytes: ByteArray, width: Int, height: Int): BooleanArray {
        val bits = BooleanArray(width * height)
        val reader = BitReader(bytes)
        val bx8Count = (width + 7) / 8
        val by8Count = (height + 7) / 8

        for (by8 in 0 until by8Count) {
            for (bx8 in 0 until bx8Count) {
                val startX8 = bx8 * 8
                val startY8 = by8 * 8
                val isMixed8 = reader.readBit()
                if (!isMixed8) {
                    // All 0s
                } else {
                    val isAllOne8 = !reader.readBit()
                    if (isAllOne8) {
                        for (py in 0 until 8) {
                            val y = startY8 + py
                            for (px in 0 until 8) {
                                val x = startX8 + px
                                if (x < width && y < height) bits[y * width + x] = true
                            }
                        }
                    } else {
                        // 4 sub-blocks of 4x4
                        for (subY in 0 until 2) {
                            for (subX in 0 until 2) {
                                val startX4 = startX8 + subX * 4
                                val startY4 = startY8 + subY * 4
                                val isMixed4 = reader.readBit()
                                if (!isMixed4) {
                                    // Sub-block all 0s
                                } else {
                                    val isAllOne4 = !reader.readBit()
                                    if (isAllOne4) {
                                        for (py in 0 until 4) {
                                            val y = startY4 + py
                                            for (px in 0 until 4) {
                                                val x = startX4 + px
                                                if (x < width && y < height) bits[y * width + x] = true
                                            }
                                        }
                                    } else {
                                        for (py in 0 until 4) {
                                            val y = startY4 + py
                                            for (px in 0 until 4) {
                                                val x = startX4 + px
                                                if (x < width && y < height) {
                                                    bits[y * width + x] = reader.readBit()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return bits
    }

    private fun encodeDelta2D(bits: BooleanArray, width: Int, height: Int): ByteArray {
        val residuals = BooleanArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val pred =
                    when {
                        x == 0 && y == 0 -> false

                        y == 0 -> bits[x - 1]

                        x == 0 -> bits[(y - 1) * width]

                        else -> {
                            val left = bits[y * width + (x - 1)]
                            val top = bits[(y - 1) * width + x]
                            val diag = bits[(y - 1) * width + (x - 1)]
                            if (left == top) left else diag xor left xor top
                        }
                    }
                residuals[i] = bits[i] xor pred
            }
        }
        return encodeVarRle(residuals)
    }

    private fun decodeDelta2D(bytes: ByteArray, width: Int, height: Int): BooleanArray {
        val residuals = decodeVarRle(bytes, width * height)
        val bits = BooleanArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val pred =
                    when {
                        x == 0 && y == 0 -> false

                        y == 0 -> bits[x - 1]

                        x == 0 -> bits[(y - 1) * width]

                        else -> {
                            val left = bits[y * width + (x - 1)]
                            val top = bits[(y - 1) * width + x]
                            val diag = bits[(y - 1) * width + (x - 1)]
                            if (left == top) left else diag xor left xor top
                        }
                    }
                bits[i] = residuals[i] xor pred
            }
        }
        return bits
    }

    private fun encodeLzss(input: ByteArray): ByteArray {
        val writer = BitWriter()
        var inPos = 0
        while (inPos < input.size) {
            var bestOffset = 0
            var bestLen = 0
            val maxLookback = min(inPos, 64)
            for (lookback in 1..maxLookback) {
                val start = inPos - lookback
                var len = 0
                while (
                    len < 17 && (inPos + len) < input.size && input[start + (len % lookback)] == input[inPos + len]
                ) {
                    len++
                }
                if (len > bestLen) {
                    bestLen = len
                    bestOffset = lookback - 1
                }
            }

            if (bestLen >= 2) {
                writer.writeBit(true) // 1 -> Match
                writer.writeBits(bestOffset, 6) // offset: 0..63
                writer.writeBits(bestLen - 2, 4) // len: 0..15 (lengths 2..17)
                inPos += bestLen
            } else {
                writer.writeBit(false) // 0 -> Literal
                writer.writeBits(input[inPos].toInt() and 0xFF, 8)
                inPos++
            }
        }
        return writer.toByteArray()
    }

    private fun decodeLzss(bytes: ByteArray, expectedByteCount: Int): ByteArray {
        val reader = BitReader(bytes)
        val out = ByteArray(expectedByteCount)
        var outPos = 0
        while (outPos < expectedByteCount && reader.hasBits) {
            val isMatch = reader.readBit()
            if (isMatch) {
                val offset = reader.readBits(6) + 1
                val length = reader.readBits(4) + 2
                val start = outPos - offset
                for (i in 0 until length) {
                    if (outPos < expectedByteCount && start + i >= 0) {
                        out[outPos] = out[start + i]
                        outPos++
                    }
                }
            } else {
                out[outPos++] = reader.readBits(8).toByte()
            }
        }
        return out
    }

    private fun makePacket(enc: Int, presetIndex: Int, payload: ByteArray): ByteArray {
        val result = ByteArray(1 + payload.size)
        result[0] = ((enc shl 4) or (presetIndex and 0x0F)).toByte()
        payload.copyInto(result, 1)
        return result
    }

    fun encode(monoBits: BooleanArray, presetIndex: Int): ByteArray {
        val preset = getPreset(presetIndex)
        val pixelCount = min(monoBits.size, preset.totalPixels)
        val bits = if (monoBits.size == pixelCount) monoBits else monoBits.copyOf(pixelCount)

        val rawPacked = bitPack(bits, pixelCount)
        val raw = makePacket(ENC_RAW, presetIndex, rawPacked)
        var best = raw

        val b4 = makePacket(ENC_BLOCK_4X4, presetIndex, encodeBlock4x4(bits, preset.width, preset.height))
        if (b4.size < best.size) best = b4

        val b8 = makePacket(ENC_BLOCK_8X8, presetIndex, encodeBlock8x8(bits, preset.width, preset.height))
        if (b8.size < best.size) best = b8

        val varH = makePacket(ENC_VAR_RLE_H, presetIndex, encodeVarRle(bits))
        if (varH.size < best.size) best = varH

        val varV = makePacket(ENC_VAR_RLE_V, presetIndex, encodeVarRle(transpose(bits, preset.width, preset.height)))
        if (varV.size < best.size) best = varV

        val delta2d = makePacket(ENC_DELTA_2D, presetIndex, encodeDelta2D(bits, preset.width, preset.height))
        if (delta2d.size < best.size) best = delta2d

        val lzss = makePacket(ENC_LZSS, presetIndex, encodeLzss(rawPacked))
        if (lzss.size < best.size) best = lzss

        return best
    }

    fun decode(bytes: ByteArray): DecodedMonochromeImage? {
        if (bytes.isEmpty()) return null
        val header = bytes[0].toInt() and 0xFF
        val enc = (header shr 4) and 0x0F
        val presetIndex = header and 0x0F
        val preset = PRESETS.getOrNull(presetIndex) ?: return null
        val pixelCount = preset.totalPixels
        val payload = bytes.copyOfRange(1, bytes.size)

        val bits: BooleanArray =
            when (enc) {
                ENC_RAW -> bitUnpack(payload, pixelCount)
                ENC_BLOCK_4X4 -> decodeBlock4x4(payload, preset.width, preset.height)
                ENC_BLOCK_8X8 -> decodeBlock8x8(payload, preset.width, preset.height)
                ENC_VAR_RLE_H -> decodeVarRle(payload, pixelCount)
                ENC_VAR_RLE_V -> transpose(decodeVarRle(payload, pixelCount), preset.height, preset.width)
                ENC_DELTA_2D -> decodeDelta2D(payload, preset.width, preset.height)
                ENC_LZSS -> bitUnpack(decodeLzss(payload, (pixelCount + 7) / 8), pixelCount)
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
