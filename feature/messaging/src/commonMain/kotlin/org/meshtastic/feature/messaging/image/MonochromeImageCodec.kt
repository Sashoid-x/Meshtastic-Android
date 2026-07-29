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
package org.meshtastic.feature.messaging.image

import kotlin.math.max
import kotlin.math.min

/** Preset resolution options for low-bandwidth 1-bit monochrome images. */
data class MonochromeResolutionPreset(val index: Int, val width: Int, val height: Int, val name: String) {
    val totalPixels: Int
        get() = width * height

    val byteSize: Int
        get() = (totalPixels + 7) / 8

    val aspectRatio: Float
        get() = width.toFloat() / height.toFloat()
}

data class DecodedMonochromeImage(
    val presetIndex: Int,
    val width: Int,
    val height: Int,
    /** Pixel ARGB color values (0xFFFFFFFF for white, 0xFF000000 for black). */
    val pixels: IntArray,
) {
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

object MonochromeImageCodec {
    const val PORT_NUM = 264

    val PRESETS =
        listOf(
            MonochromeResolutionPreset(0, 39, 40, "39×40 (Square)"),
            MonochromeResolutionPreset(1, 32, 32, "32×32 (Small Sq)"),
            MonochromeResolutionPreset(2, 48, 32, "48×32 (3:2)"),
            MonochromeResolutionPreset(3, 32, 48, "32×48 (2:3)"),
            MonochromeResolutionPreset(4, 64, 24, "64×24 (8:3)"),
            MonochromeResolutionPreset(5, 24, 64, "24×64 (3:8)"),
            MonochromeResolutionPreset(6, 44, 36, "44×36 (11:9)"),
            MonochromeResolutionPreset(7, 36, 44, "36×44 (9:11)"),
            MonochromeResolutionPreset(8, 52, 30, "52×30 (16:9)"),
            MonochromeResolutionPreset(9, 30, 52, "30×52 (9:16)"),
        )

    fun getPreset(index: Int): MonochromeResolutionPreset = PRESETS.getOrElse(index) { PRESETS[0] }

    /**
     * Encodes monochrome pixels (1 = white, 0 = black) into a packed byte array. Byte 0: presetIndex (0..9) Bytes 1..N:
     * bit-packed pixels
     */
    fun encode(monoBits: BooleanArray, presetIndex: Int): ByteArray {
        val preset = getPreset(presetIndex)
        val pixelCount = min(monoBits.size, preset.totalPixels)
        val dataByteCount = (preset.totalPixels + 7) / 8
        val result = ByteArray(1 + dataByteCount)

        result[0] = presetIndex.toByte()

        for (i in 0 until pixelCount) {
            if (monoBits[i]) {
                val byteIndex = 1 + (i / 8)
                val bitIndex = 7 - (i % 8)
                result[byteIndex] = (result[byteIndex].toInt() or (1 shl bitIndex)).toByte()
            }
        }
        return result
    }

    /** Decodes a packed byte array into [DecodedMonochromeImage]. */
    fun decode(bytes: ByteArray): DecodedMonochromeImage? {
        if (bytes.isEmpty()) return null
        val presetIndex = bytes[0].toInt() and 0xFF
        val preset = PRESETS.getOrNull(presetIndex) ?: return null

        val pixelCount = preset.totalPixels
        val pixels = IntArray(pixelCount)

        for (i in 0 until pixelCount) {
            val byteIndex = 1 + (i / 8)
            if (byteIndex >= bytes.size) break
            val bitIndex = 7 - (i % 8)
            val isWhite = ((bytes[byteIndex].toInt() shr bitIndex) and 1) == 1
            pixels[i] = if (isWhite) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }

        return DecodedMonochromeImage(
            presetIndex = presetIndex,
            width = preset.width,
            height = preset.height,
            pixels = pixels,
        )
    }

    /**
     * Applies brightness (-1.0 to 1.0) and contrast (0.1 to 3.0) adjustments, then thresholding/dithering to 1-bit
     * mono.
     */
    fun processToMonochrome(
        grayValues: FloatArray, // 0.0f..1.0f
        brightness: Float = 0f,
        contrast: Float = 1f,
        threshold: Float = 0.5f,
    ): BooleanArray {
        val result = BooleanArray(grayValues.size)
        for (i in grayValues.indices) {
            // Apply brightness & contrast
            var valNorm = (grayValues[i] - 0.5f) * contrast + 0.5f + brightness
            valNorm = max(0f, min(1f, valNorm))
            result[i] = valNorm >= threshold
        }
        return result
    }
}
