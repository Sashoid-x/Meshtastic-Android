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
            // Max total bytes = 1 byte (preset index) + pixel byte data + 1 byte (trailer) <= 200 bytes
            val totalPacketBytes = 1 + preset.byteSize + 1
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
                    encoded.size <= 2 + preset.byteSize,
                    "Encoded size ${encoded.size} exceeds max size ${2 + preset.byteSize}",
                )
                val headerPreset = encoded[0].toInt() and 0x0F
                assertEquals(presetIndex, headerPreset)

                val decoded = MonochromeImageCodec.decode(encoded)
                assertNotNull(decoded)
                assertEquals(presetIndex, decoded.presetIndex)
                assertEquals(preset.width, decoded.width)
                assertEquals(preset.height, decoded.height)
                assertEquals(preset.totalPixels, decoded.pixels.size)
                assertEquals(0, decoded.themeIndex)
                assertEquals(false, decoded.showGrid)

                val theme0 = MonochromeImageCodec.getTheme(0)
                for (i in testBits.indices) {
                    val expectedColor =
                        if (testBits[i]) theme0.foregroundColor.toInt() else theme0.backgroundColor.toInt()
                    assertEquals(expectedColor, decoded.pixels[i], "Mismatch at pixel $i in preset $presetIndex")
                }
            }
        }
    }

    @Test
    fun testThemeAndGridTrailer() {
        val preset = MonochromeImageCodec.getPreset(0)
        val bits = BooleanArray(preset.totalPixels) { i -> i % 3 == 0 }

        for (themeIndex in 0 until MonochromeImageCodec.THEMES.size) {
            for (showGrid in listOf(false, true)) {
                val encoded = MonochromeImageCodec.encode(bits, 0, themeIndex = themeIndex, showGrid = showGrid)
                val decoded = MonochromeImageCodec.decode(encoded)
                assertNotNull(decoded)
                assertEquals(themeIndex, decoded.themeIndex)
                assertEquals(showGrid, decoded.showGrid)

                val theme = MonochromeImageCodec.getTheme(themeIndex)
                val expectedFg = theme.foregroundColor.toInt()
                val expectedBg = theme.backgroundColor.toInt()
                for (i in bits.indices) {
                    val expectedColor = if (bits[i]) expectedFg else expectedBg
                    assertEquals(expectedColor, decoded.pixels[i], "Pixel color mismatch for theme $themeIndex")
                }
            }
        }
    }

    @Test
    fun testLegacyPacketWithoutTrailer() {
        val preset = MonochromeImageCodec.getPreset(0)
        val bits = BooleanArray(preset.totalPixels) { i -> i % 2 == 0 }

        // Encode with current encoder, then strip the last byte (trailer) to simulate a legacy packet
        val encodedWithTrailer = MonochromeImageCodec.encode(bits, 0, themeIndex = 3, showGrid = true)
        val legacyPacket = encodedWithTrailer.copyOfRange(0, encodedWithTrailer.size - 1)

        val decoded = MonochromeImageCodec.decode(legacyPacket)
        assertNotNull(decoded)
        // Legacy packets without trailer must default to Classic (theme 0) and showGrid = false
        assertEquals(0, decoded.themeIndex)
        assertEquals(false, decoded.showGrid)

        val defaultTheme = MonochromeImageCodec.getTheme(0)
        for (i in bits.indices) {
            val expectedColor =
                if (bits[i]) defaultTheme.foregroundColor.toInt() else defaultTheme.backgroundColor.toInt()
            assertEquals(expectedColor, decoded.pixels[i])
        }
    }

    @Test
    fun testSparseImageCompressionEfficiency() {
        val preset = MonochromeImageCodec.getPreset(0) // 39x40, total 1560 pixels (195 bytes raw)
        val sparseBits = BooleanArray(preset.totalPixels) { i -> i in 100..105 || i in 500..505 }
        val encoded = MonochromeImageCodec.encode(sparseBits, 0)
        // With 4x4 / 8x8 / var-RLE + 1 byte trailer, sparse drawing should still compress well under 55 bytes
        assertTrue(encoded.size < 55, "Expected sparse image to compress under 55 bytes, but was ${encoded.size}")
    }

    @Test
    fun testProcessToMonochrome() {
        // processToMonochrome uses Bayer ordered dithering; with ditherAmount=0 it behaves
        // as a simple threshold at 0.5. Dark values (< 0.5) become pencil marks (true).
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
        assertEquals(true, mono[0])
        assertEquals(true, mono[1])
        assertEquals(false, mono[2])
        assertEquals(false, mono[3])

        val monoInverted =
            MonochromeImageCodec.processToMonochrome(
                grayValues = grays,
                width = 4,
                height = 1,
                brightness = 0f,
                contrast = 1f,
                ditherAmount = 0f,
                invert = true,
            )
        assertEquals(false, monoInverted[0])
        assertEquals(false, monoInverted[1])
        assertEquals(true, monoInverted[2])
        assertEquals(true, monoInverted[3])
    }
}
