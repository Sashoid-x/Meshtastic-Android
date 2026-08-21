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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MonochromeImageCodecTest {

    @Test
    fun testResolutionPresetsCountAndSize() {
        assertEquals(10, MonochromeImageCodec.PRESETS.size)

        MonochromeImageCodec.PRESETS.forEach { preset ->
            // Max total bytes = 1 byte (preset index) + pixel byte data <= 200 bytes
            val totalPacketBytes = 1 + preset.byteSize
            assertTrue(
                totalPacketBytes <= 200,
                "Preset ${preset.name} total byte size $totalPacketBytes exceeds 200 bytes limit",
            )
        }
    }

    @Test
    fun testEncodeAndDecodeRoundtrip() {
        for (presetIndex in 0 until MonochromeImageCodec.PRESETS.size) {
            val preset = MonochromeImageCodec.getPreset(presetIndex)

            val patterns =
                listOf(
                    BooleanArray(preset.totalPixels) { false }, // All white
                    BooleanArray(preset.totalPixels) { true }, // All black
                    BooleanArray(preset.totalPixels) { i -> i % 2 == 0 }, // Checkerboard
                    BooleanArray(preset.totalPixels) { i -> i == 42 || i == 100 }, // Sparse dots
                    BooleanArray(preset.totalPixels) { i -> (i / preset.width) == 5 }, // Horizontal line
                    BooleanArray(preset.totalPixels) { i -> (i % preset.width) == 5 }, // Vertical line
                    BooleanArray(preset.totalPixels) { i -> (i / preset.width) == (i % preset.width) }, // Diagonal
                )

            for (testBits in patterns) {
                val encoded = MonochromeImageCodec.encode(testBits, presetIndex)
                assertTrue(
                    encoded.size <= 1 + preset.byteSize,
                    "Encoded size ${encoded.size} exceeds raw size ${1 + preset.byteSize}",
                )
                val headerPreset = encoded[0].toInt() and 0x0F
                assertEquals(presetIndex, headerPreset)

                val decoded = MonochromeImageCodec.decode(encoded)
                assertNotNull(decoded)
                assertEquals(presetIndex, decoded.presetIndex)
                assertEquals(preset.width, decoded.width)
                assertEquals(preset.height, decoded.height)
                assertEquals(preset.totalPixels, decoded.pixels.size)

                for (i in testBits.indices) {
                    val expectedColor = if (testBits[i]) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
                    assertEquals(expectedColor, decoded.pixels[i], "Mismatch at pixel $i in preset $presetIndex")
                }
            }
        }
    }

    @Test
    fun testSparseImageCompressionEfficiency() {
        val preset = MonochromeImageCodec.getPreset(0) // 39x40, total 1560 pixels (195 bytes raw)
        val sparseBits = BooleanArray(preset.totalPixels) { i -> i in 100..105 || i in 500..505 }
        val encoded = MonochromeImageCodec.encode(sparseBits, 0)
        // With 4x4 / 8x8 / var-RLE, sparse drawing should be significantly smaller than raw 196 bytes (under 50 bytes)
        assertTrue(encoded.size < 50, "Expected sparse image to compress under 50 bytes, but was ${encoded.size}")
    }

    @Test
    fun testProcessToMonochrome() {
        // processToMonochrome uses Bayer ordered dithering; with ditherAmount=0 it behaves
        // as a simple threshold at 0.5. We test on a single row (width=4, height=1).
        val grays = floatArrayOf(0.1f, 0.4f, 0.6f, 0.9f)
        val mono =
            MonochromeImageCodec.processToMonochrome(
                grayValues = grays,
                width = 4,
                height = 1,
                brightness = 0f,
                contrast = 1f,
                ditherAmount = 0f,
                invert = false,
            )
        assertEquals(false, mono[0])
        assertEquals(false, mono[1])
        assertEquals(true, mono[2])
        assertEquals(true, mono[3])
    }
}
