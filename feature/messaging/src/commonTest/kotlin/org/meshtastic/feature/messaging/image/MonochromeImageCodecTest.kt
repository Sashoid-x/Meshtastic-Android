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
        val presetIndex = 2 // 48x32
        val preset = MonochromeImageCodec.getPreset(presetIndex)
        val testBits = BooleanArray(preset.totalPixels) { index -> index % 2 == 0 }

        val encoded = MonochromeImageCodec.encode(testBits, presetIndex)
        // Multi-strategy encoder picks smallest — raw size is the upper bound
        assertTrue(
            encoded.size <= 1 + preset.byteSize,
            "Encoded size ${encoded.size} exceeds raw size ${1 + preset.byteSize}",
        )
        // Header byte: top 2 bits = strategy, bottom 6 bits = preset index
        val headerPreset = encoded[0].toInt() and 0x3F
        assertEquals(presetIndex, headerPreset)

        val decoded = MonochromeImageCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(presetIndex, decoded.presetIndex)
        assertEquals(preset.width, decoded.width)
        assertEquals(preset.height, decoded.height)
        assertEquals(preset.totalPixels, decoded.pixels.size)

        for (i in testBits.indices) {
            val expectedColor = if (testBits[i]) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            assertEquals(expectedColor, decoded.pixels[i], "Mismatch at pixel $i")
        }
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
