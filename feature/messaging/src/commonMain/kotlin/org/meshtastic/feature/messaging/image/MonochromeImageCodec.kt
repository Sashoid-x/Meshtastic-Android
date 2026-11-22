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

data class MonochromeTheme(
    val id: Int,
    val name: String,
    val backgroundColor: Long,
    val foregroundColor: Long,
    val gridColor: Long,
)

data class DecodedMonochromeImage(
    val presetIndex: Int,
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val themeIndex: Int = 0,
    val showGrid: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedMonochromeImage) return false
        return presetIndex == other.presetIndex &&
            width == other.width &&
            height == other.height &&
            themeIndex == other.themeIndex &&
            showGrid == other.showGrid &&
            pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = presetIndex
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + themeIndex
        result = 31 * result + showGrid.hashCode()
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

    val THEMES =
        listOf(
            MonochromeTheme(0, "Classic", 0xFFFFFFFFL, 0xFF000000L, 0x22000000L),
            MonochromeTheme(1, "Classic Dark", 0xFF000000L, 0xFFFFFFFFL, 0x33FFFFFFL),
            MonochromeTheme(2, "E-Paper", 0xFFF5EFEBL, 0xFF2C2420L, 0x222C2420L),
            MonochromeTheme(3, "Sepia", 0xFFEADCC9L, 0xFF4A3525L, 0x224A3525L),
            MonochromeTheme(4, "Blueprint", 0xFF0A2E5CL, 0xFFE0F0FFL, 0x28E0F0FFL),
            MonochromeTheme(5, "Game Boy", 0xFF8B956DL, 0xFF0F380FL, 0x250F380FL),
            MonochromeTheme(6, "Game Boy Pocket", 0xFFC4BEBBL, 0xFF2C2C2CL, 0x222C2C2CL),
            MonochromeTheme(7, "Matrix Green", 0xFF0A0F0DL, 0xFF00FF66L, 0x3000FF66L),
            MonochromeTheme(8, "Amber CRT", 0xFF140A00L, 0xFFFFB000L, 0x30FFB000L),
            MonochromeTheme(9, "Solarized Light", 0xFFFDF6E3L, 0xFF657B83L, 0x22657B83L),
            MonochromeTheme(10, "Solarized Dark", 0xFF002B36L, 0xFF2AA198L, 0x302AA198L),
            MonochromeTheme(11, "Cyberpunk", 0xFF0B001AL, 0xFFFF007FL, 0x30FF007FL),
            MonochromeTheme(12, "Synthwave", 0xFF1A0A2AL, 0xFF00F0FFL, 0x3000F0FFL),
            MonochromeTheme(13, "Ocean Blue", 0xFF001428L, 0xFF00D2FFL, 0x3000D2FFL),
            MonochromeTheme(14, "Forest Moss", 0xFF0D1A10L, 0xFFA8E063L, 0x30A8E063L),
            MonochromeTheme(15, "Blood Moon", 0xFF150000L, 0xFFFF3333L, 0x30FF3333L),
            MonochromeTheme(16, "Sunset Gold", 0xFF1A091AL, 0xFFFFAA33L, 0x30FFAA33L),
            MonochromeTheme(17, "Nordic Frost", 0xFF2E3440L, 0xFF88C0D0L, 0x3088C0D0L),
            MonochromeTheme(18, "Dracula", 0xFF282A36L, 0xFFBD93F9L, 0x30BD93F9L),
            MonochromeTheme(19, "Chalkboard", 0xFF233227L, 0xFFE8F5E9L, 0x25E8F5E9L),
            MonochromeTheme(20, "Monokai", 0xFF272822L, 0xFFE6DB74L, 0x30E6DB74L),
            MonochromeTheme(21, "Terminal White", 0xFF0F0F0FL, 0xFFF0F0F0L, 0x25FFFFFFL),
            MonochromeTheme(22, "Notebook", 0xFFFAF8F5L, 0xFF1A365DL, 0x201A365DL),
            MonochromeTheme(23, "Graphite", 0xFFECEFF1L, 0xFF37474FL, 0x2237474FL),
        )

    fun getTheme(index: Int): MonochromeTheme = THEMES.getOrElse(index) { THEMES[0] }

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

        var hasUnderflow = false
            private set

        val hasBits: Boolean
            get() = bitPos < bytes.size * 8

        fun readBit(): Boolean {
            if (bitPos >= bytes.size * 8) {
                hasUnderflow = true
                return false
            }
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

    internal fun bitPack(bits: BooleanArray, count: Int = bits.size): ByteArray {
        val out = ByteArray((count + 7) / 8)
        for (i in 0 until count) {
            if (i < bits.size && bits[i]) {
                out[i / 8] = (out[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
            }
        }
        return out
    }

    internal fun bitUnpack(bytes: ByteArray, count: Int): BooleanArray {
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
        if (reader.hasUnderflow) return -1
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

    private fun decodeVarRle(reader: BitReader, count: Int): BooleanArray? {
        val bits = BooleanArray(count)
        if (!reader.hasBits) return null
        var currentColor = reader.readBit()
        if (reader.hasUnderflow) return null
        var written = 0
        while (written < count) {
            if (!reader.hasBits || reader.hasUnderflow) return null
            val run = readVarRleRun(reader)
            if (run <= 0 || reader.hasUnderflow || run > (count - written)) return null
            for (i in 0 until run) {
                bits[written++] = currentColor
            }
            currentColor = !currentColor
        }
        if (reader.hasUnderflow || written != count) return null
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

    private fun decodeBlock4x4(reader: BitReader, width: Int, height: Int): BooleanArray? {
        val bits = BooleanArray(width * height)
        val bxCount = (width + 3) / 4
        val byCount = (height + 3) / 4

        for (by in 0 until byCount) {
            for (bx in 0 until bxCount) {
                val startX = bx * 4
                val startY = by * 4
                val isMixed = reader.readBit()
                if (reader.hasUnderflow) return null
                if (!isMixed) {
                    // All 0s: nothing to set (already false)
                } else {
                    val isAllOne = !reader.readBit()
                    if (reader.hasUnderflow) return null
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
                        if (reader.hasUnderflow) return null
                    }
                }
            }
        }
        if (reader.hasUnderflow) return null
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

    private fun decodeBlock8x8(reader: BitReader, width: Int, height: Int): BooleanArray? {
        val bits = BooleanArray(width * height)
        val bx8Count = (width + 7) / 8
        val by8Count = (height + 7) / 8

        for (by8 in 0 until by8Count) {
            for (bx8 in 0 until bx8Count) {
                val startX8 = bx8 * 8
                val startY8 = by8 * 8
                val isMixed8 = reader.readBit()
                if (reader.hasUnderflow) return null
                if (!isMixed8) {
                    // All 0s
                } else {
                    val isAllOne8 = !reader.readBit()
                    if (reader.hasUnderflow) return null
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
                                if (reader.hasUnderflow) return null
                                if (!isMixed4) {
                                    // Sub-block all 0s
                                } else {
                                    val isAllOne4 = !reader.readBit()
                                    if (reader.hasUnderflow) return null
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
                                        if (reader.hasUnderflow) return null
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (reader.hasUnderflow) return null
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

    private fun decodeDelta2D(reader: BitReader, width: Int, height: Int): BooleanArray? {
        val residuals = decodeVarRle(reader, width * height) ?: return null
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

    private fun decodeLzss(reader: BitReader, expectedByteCount: Int): ByteArray? {
        val out = ByteArray(expectedByteCount)
        var outPos = 0
        while (outPos < expectedByteCount) {
            if (!reader.hasBits || reader.hasUnderflow) return null
            val isMatch = reader.readBit()
            if (reader.hasUnderflow) return null
            if (isMatch) {
                val offset = reader.readBits(6) + 1
                val length = reader.readBits(4) + 2
                if (reader.hasUnderflow) return null
                val start = outPos - offset
                if (start < 0 || outPos + length > expectedByteCount) return null
                for (i in 0 until length) {
                    out[outPos] = out[start + i]
                    outPos++
                }
            } else {
                val byteVal = reader.readBits(8)
                if (reader.hasUnderflow) return null
                out[outPos++] = byteVal.toByte()
            }
        }
        if (reader.hasUnderflow || outPos != expectedByteCount) return null
        return out
    }

    private fun makePacket(enc: Int, presetIndex: Int, payload: ByteArray): ByteArray {
        val result = ByteArray(1 + payload.size)
        result[0] = ((enc shl 4) or (presetIndex and 0x0F)).toByte()
        payload.copyInto(result, 1)
        return result
    }

    fun encode(monoBits: BooleanArray, presetIndex: Int, themeIndex: Int = 0, showGrid: Boolean = false): ByteArray {
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

        val trailer = (((if (showGrid) 1 else 0) shl 7) or (themeIndex and 0x7F)).toByte()
        val result = ByteArray(best.size + 1)
        best.copyInto(result, 0)
        result[best.size] = trailer
        return result
    }

    fun isPixelArt(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < 4) return false
        return decode(bytes) != null
    }

    fun decode(bytes: ByteArray): DecodedMonochromeImage? {
        if (bytes.size < 4) return null
        // Check for MFT magic "MFT\x01"
        if (
            bytes.size >= 4 &&
            bytes[0] == 0x4D.toByte() &&
            bytes[1] == 0x46.toByte() &&
            bytes[2] == 0x54.toByte() &&
            bytes[3] == 0x01.toByte()
        ) {
            return null
        }
        val header = bytes[0].toInt() and 0xFF
        val enc = (header shr 4) and 0x0F
        val presetIndex = header and 0x0F
        if (enc > 6) return null
        val preset = PRESETS.getOrNull(presetIndex) ?: return null
        val pixelCount = preset.totalPixels
        val payload = bytes.copyOfRange(1, bytes.size)

        val reader = BitReader(payload)
        val bits: BooleanArray =
            when (enc) {
                ENC_RAW -> {
                    val rawByteCount = (pixelCount + 7) / 8
                    if (payload.size != rawByteCount && payload.size != rawByteCount + 1) {
                        return null
                    }
                    bitUnpack(payload, pixelCount)
                }

                ENC_BLOCK_4X4 -> decodeBlock4x4(reader, preset.width, preset.height) ?: return null

                ENC_BLOCK_8X8 -> decodeBlock8x8(reader, preset.width, preset.height) ?: return null

                ENC_VAR_RLE_H -> decodeVarRle(reader, pixelCount) ?: return null

                ENC_VAR_RLE_V -> {
                    val rle = decodeVarRle(reader, pixelCount) ?: return null
                    transpose(rle, preset.height, preset.width)
                }

                ENC_DELTA_2D -> decodeDelta2D(reader, preset.width, preset.height) ?: return null

                ENC_LZSS -> {
                    val lzssBytes = decodeLzss(reader, (pixelCount + 7) / 8) ?: return null
                    bitUnpack(lzssBytes, pixelCount)
                }

                else -> return null
            }

        val consumedBytes = if (enc == ENC_RAW) (pixelCount + 7) / 8 else (reader.bitPos + 7) / 8
        if (payload.size != consumedBytes && payload.size != consumedBytes + 1) {
            return null
        }

        // Validate unused padding bits in the last consumed byte of the bitstream
        if (enc != ENC_RAW) {
            val remainderBits = reader.bitPos % 8
            if (remainderBits != 0) {
                val padMask = (1 shl (8 - remainderBits)) - 1
                val lastByte = payload[consumedBytes - 1].toInt() and 0xFF
                if ((lastByte and padMask) != 0) {
                    return null
                }
            }
        }

        val hasTrailer = payload.size == consumedBytes + 1
        val trailer = if (hasTrailer) payload.last().toInt() and 0xFF else 0
        val showGrid = hasTrailer && ((trailer and 0x80) != 0)
        val themeIndex = if (hasTrailer) (trailer and 0x7F) else 0

        if (hasTrailer && themeIndex >= THEMES.size) {
            return null
        }

        val theme = getTheme(themeIndex)
        val pixels =
            IntArray(pixelCount) { i ->
                if (i < bits.size && bits[i]) {
                    theme.foregroundColor.toInt()
                } else {
                    theme.backgroundColor.toInt()
                }
            }
        return DecodedMonochromeImage(
            presetIndex = presetIndex,
            width = preset.width,
            height = preset.height,
            pixels = pixels,
            themeIndex = themeIndex,
            showGrid = showGrid,
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
                result[i] = if (invert) valNorm >= threshold else valNorm < threshold
            }
        }
        return result
    }

    fun toAsciiArt(image: DecodedMonochromeImage): String {
        val sb = StringBuilder()
        val theme = getTheme(image.themeIndex)
        val fgInt = theme.foregroundColor.toInt()
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.pixels[y * image.width + x]
                sb.append(if (pixel == fgInt) "██" else "  ")
            }
            if (y < image.height - 1) sb.append('\n')
        }
        return sb.toString()
    }

    fun toPng(image: DecodedMonochromeImage, scale: Int = 8): ByteArray {
        val safeScale = scale.coerceAtLeast(1)
        val outWidth = image.width * safeScale
        val outHeight = image.height * safeScale

        val rowSize = 1 + outWidth * 4
        val rawData = ByteArray(outHeight * rowSize)

        for (y in 0 until outHeight) {
            val srcY = y / safeScale
            val rowOffset = y * rowSize
            rawData[rowOffset] = 0 // Filter type: None
            for (x in 0 until outWidth) {
                val srcX = x / safeScale
                val argb = image.pixels[srcY * image.width + srcX]
                val a = ((argb ushr 24) and 0xFF).toByte()
                val r = ((argb ushr 16) and 0xFF).toByte()
                val g = ((argb ushr 8) and 0xFF).toByte()
                val b = (argb and 0xFF).toByte()

                val pixelOffset = rowOffset + 1 + x * 4
                rawData[pixelOffset] = r
                rawData[pixelOffset + 1] = g
                rawData[pixelOffset + 2] = b
                rawData[pixelOffset + 3] = a
            }
        }

        val maxBlockSize = 65535
        val numBlocks = (rawData.size + maxBlockSize - 1) / maxBlockSize
        val zlibSize = 2 + (numBlocks * 5) + rawData.size + 4
        val zlibStream = ByteArray(zlibSize)
        var zPos = 0

        zlibStream[zPos++] = 0x78.toByte()
        zlibStream[zPos++] = 0x01.toByte()

        var rawOffset = 0
        while (rawOffset < rawData.size) {
            val blockSize = kotlin.math.min(maxBlockSize, rawData.size - rawOffset)
            val isFinal = (rawOffset + blockSize == rawData.size)

            zlibStream[zPos++] = (if (isFinal) 0x01 else 0x00).toByte()
            zlibStream[zPos++] = (blockSize and 0xFF).toByte()
            zlibStream[zPos++] = ((blockSize ushr 8) and 0xFF).toByte()
            val invBlockSize = blockSize.inv()
            zlibStream[zPos++] = (invBlockSize and 0xFF).toByte()
            zlibStream[zPos++] = ((invBlockSize ushr 8) and 0xFF).toByte()

            rawData.copyInto(zlibStream, zPos, rawOffset, rawOffset + blockSize)
            zPos += blockSize
            rawOffset += blockSize
        }

        val adler = computeAdler32(rawData)
        zlibStream[zPos++] = ((adler ushr 24) and 0xFF).toByte()
        zlibStream[zPos++] = ((adler ushr 16) and 0xFF).toByte()
        zlibStream[zPos++] = ((adler ushr 8) and 0xFF).toByte()
        zlibStream[zPos++] = (adler and 0xFF).toByte()

        val totalPngSize = 8 + 25 + (12 + zlibSize) + 12
        val png = ByteArray(totalPngSize)
        var p = 0

        val signature =
            byteArrayOf(
                0x89.toByte(),
                0x50.toByte(),
                0x4E.toByte(),
                0x47.toByte(),
                0x0D.toByte(),
                0x0A.toByte(),
                0x1A.toByte(),
                0x0A.toByte(),
            )
        signature.copyInto(png, p)
        p += signature.size

        // IHDR Chunk
        val ihdrType = byteArrayOf('I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte())
        val ihdrData = ByteArray(13)
        writeInt32BE(ihdrData, 0, outWidth)
        writeInt32BE(ihdrData, 4, outHeight)
        ihdrData[8] = 8.toByte()
        ihdrData[9] = 6.toByte() // RGBA
        ihdrData[10] = 0.toByte()
        ihdrData[11] = 0.toByte()
        ihdrData[12] = 0.toByte()

        writeInt32BE(png, p, 13)
        p += 4
        ihdrType.copyInto(png, p)
        p += 4
        ihdrData.copyInto(png, p)
        p += ihdrData.size
        writeInt32BE(png, p, computeCrc32(ihdrType, ihdrData))
        p += 4

        // IDAT Chunk
        val idatType = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte())
        writeInt32BE(png, p, zlibSize)
        p += 4
        idatType.copyInto(png, p)
        p += 4
        zlibStream.copyInto(png, p)
        p += zlibStream.size
        writeInt32BE(png, p, computeCrc32(idatType, zlibStream))
        p += 4

        // IEND Chunk
        val iendType = byteArrayOf('I'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte())
        writeInt32BE(png, p, 0)
        p += 4
        iendType.copyInto(png, p)
        p += 4
        writeInt32BE(png, p, computeCrc32(iendType, ByteArray(0)))
        p += 4

        return png
    }

    private fun writeInt32BE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    private fun computeAdler32(data: ByteArray): Int {
        var s1 = 1
        var s2 = 0
        val mod = 65521
        for (b in data) {
            s1 = (s1 + (b.toInt() and 0xFF)) % mod
            s2 = (s2 + s1) % mod
        }
        return (s2 shl 16) or s1
    }

    private val CRC32_TABLE =
        IntArray(256) { n ->
            var c = n
            for (k in 0 until 8) {
                c = if ((c and 1) != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1)
            }
            c
        }

    private fun computeCrc32(type: ByteArray, data: ByteArray): Int {
        var crc = -1
        for (b in type) {
            crc = CRC32_TABLE[(crc xor (b.toInt() and 0xFF)) and 0xFF] xor (crc ushr 8)
        }
        for (b in data) {
            crc = CRC32_TABLE[(crc xor (b.toInt() and 0xFF)) and 0xFF] xor (crc ushr 8)
        }
        return crc.inv()
    }
}
