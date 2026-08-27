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

@Suppress("MagicNumber", "NestedBlockDepth")
internal object Base91 {
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!#\$%&()*+,./:;<=>?@[]^_`{|}~\""

    private val DECODE_TABLE =
        IntArray(256) { -1 }
            .apply {
                for (i in ALPHABET.indices) {
                    this[ALPHABET[i].code] = i
                }
            }

    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder()
        var n = 0
        var nbits = 0
        for (b in data) {
            val byteVal = b.toInt() and 0xFF
            n = n or (byteVal shl nbits)
            nbits += 8
            if (nbits > 13) {
                var v = n and 8191
                if (v > 88) {
                    n = n ushr 13
                    nbits -= 13
                } else {
                    v = n and 16383
                    n = n ushr 14
                    nbits -= 14
                }
                sb.append(ALPHABET[v % 91])
                sb.append(ALPHABET[v / 91])
            }
        }
        if (nbits > 0) {
            sb.append(ALPHABET[n % 91])
            if (n >= 91 || nbits > 7) {
                sb.append(ALPHABET[n / 91])
            }
        }
        return sb.toString()
    }

    fun decode(text: String): ByteArray {
        if (text.isEmpty()) return ByteArray(0)
        val out = mutableListOf<Byte>()
        var n = 0
        var nbits = 0
        var v = -1
        for (i in text.indices) {
            val ch = text[i]
            val code = ch.code
            val c = if (code in DECODE_TABLE.indices) DECODE_TABLE[code] else -1
            require(c != -1) { "Invalid Base91 char: $ch" }
            if (v == -1) {
                v = c
            } else {
                v += c * 91
                val b = if ((v and 8191) > 88) 13 else 14
                n = n or (v shl nbits)
                nbits += b
                v = -1
                while (nbits >= 8) {
                    out.add((n and 0xFF).toByte())
                    n = n ushr 8
                    nbits -= 8
                }
            }
        }
        if (v != -1) {
            n = n or (v shl nbits)
            nbits += 7
            while (nbits >= 8) {
                out.add((n and 0xFF).toByte())
                n = n ushr 8
                nbits -= 8
            }
        }
        return out.toByteArray()
    }
}
