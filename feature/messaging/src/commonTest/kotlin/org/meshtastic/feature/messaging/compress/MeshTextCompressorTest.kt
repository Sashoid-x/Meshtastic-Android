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

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeshTextCompressorTest {

    @Test
    fun testBase91Roundtrip() {
        val testData =
            listOf(
                byteArrayOf(),
                byteArrayOf(0),
                byteArrayOf(1, 2, 3, 4, 5),
                "Hello World!".encodeToByteArray(),
                "Тестирование Base91 кодирования".encodeToByteArray(),
                ByteArray(256) { it.toByte() },
            )

        for (data in testData) {
            val encoded = Base91.encode(data)
            val decoded = Base91.decode(encoded)
            assertContentEquals(data, decoded, "Failed Base91 roundtrip for data size ${data.size}")
        }
    }

    @Test
    fun testCompressAndDecompressRussian() = runTest {
        val model = MeshTextCompressor.getOrLoadModel()
        val phrases =
            listOf(
                "Привет, как дела?",
                "Проверка связи. Как слышно?",
                "Батарея на 40%, перехожу в режим энергосбережения.",
                "Встречаемся на точке сбора через 15 минут.",
                "Широта 55.7558, долгота 37.6173, высота 150м.",
            )

        for (phrase in phrases) {
            val compressed = MeshTextCompressor.compress(phrase, model)
            assertTrue(
                compressed.length < phrase.length,
                "Expected compressed length (${compressed.length}) < original (${phrase.length}) for '$phrase'",
            )
            assertTrue(
                MeshTextCompressor.isCompressed(compressed),
                "Expected isCompressed to be true for '$compressed'",
            )
            val decompressed = MeshTextCompressor.decompress(compressed, model)
            assertEquals(phrase, decompressed, "Decompressed text did not match original")
        }
    }

    @Test
    fun testCompressAndDecompressEnglish() = runTest {
        val model = MeshTextCompressor.getOrLoadModel()
        val phrases =
            listOf(
                "Hello, how are you doing today?",
                "Testing mesh network communication with arithmetic compression.",
                "Battery at 40%, switching to power save mode now.",
                "Meeting at waypoint Bravo in 15 minutes.",
            )

        for (phrase in phrases) {
            val compressed = MeshTextCompressor.compress(phrase, model)
            val decompressed = MeshTextCompressor.decompress(compressed, model)
            assertEquals(phrase, decompressed, "Decompressed text did not match original for '$phrase'")
        }
    }

    @Test
    fun testCompressAndDecompressWithEmojisAndSpecialChars() = runTest {
        val model = MeshTextCompressor.getOrLoadModel()
        val phrases =
            listOf(
                "Внимание! 🚨 Обнаружен узел #42 📡",
                "Привет 🤖! Как связь? 👍",
                "Coordinates: 45°12'34\"N, 123°45'67\"W 📍",
                "Emoji party: 🎉 🚀 🔥 🍕 🍺",
            )

        for (phrase in phrases) {
            val compressed = MeshTextCompressor.compress(phrase, model)
            val decompressed = MeshTextCompressor.decompress(compressed, model)
            assertEquals(phrase, decompressed, "Decompressed text with emojis did not match original")
        }
    }

    @Test
    fun testPassthroughForShortText() = runTest {
        val model = MeshTextCompressor.getOrLoadModel()
        val shortPhrases = listOf("ok", "да", "1", "+")

        for (phrase in shortPhrases) {
            val result = MeshTextCompressor.compress(phrase, model)
            val decompressed = MeshTextCompressor.decompress(result, model)
            assertEquals(phrase, decompressed)
        }
    }
}
