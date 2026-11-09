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
package org.meshtastic.core.common.util

import kotlin.math.pow

data class RtttlSong(
    val name: String,
    val defaultDuration: Int,
    val defaultOctave: Int,
    val bpm: Int,
    val notes: List<RtttlNote>,
) {
    val totalDurationMs: Long = notes.sumOf { it.durationMs }

    val formattedDuration: String
        get() {
            val totalSec = (totalDurationMs + 500) / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            return "$min:${sec.toString().padStart(2, '0')}"
        }
}

data class RtttlNote(val frequencyHz: Float, val durationMs: Long)

object RtttlParser {
    private val NOTE_SEMITONES =
        mapOf(
            'c' to 0,
            'd' to 2,
            'e' to 4,
            'f' to 5,
            'g' to 7,
            'a' to 9,
            'b' to 11,
            'h' to 11,
        )

    private val NOTE_REGEX = Regex("""^(\d{1,2})?(\.)?([a-hpA-HP])([#b_])?(\.)?([1-8])?(\.)?$""")

    fun noteToFrequency(noteChar: Char, sharp: Boolean, flat: Boolean, octave: Int): Float {
        val s = NOTE_SEMITONES[noteChar.lowercaseChar()] ?: return 0f
        val semitone =
            s +
                (
                    if (sharp) {
                        1
                    } else if (flat) {
                        -1
                    } else {
                        0
                    }
                    )
        val midiNote = 12 * (octave + 1) + semitone
        return (440.0 * 2.0.pow((midiNote - 69) / 12.0)).toFloat()
    }

    fun isRtttl(text: String): Boolean = parse(text) != null

    @Suppress("ReturnCount", "MagicNumber", "CyclomaticComplexMethod")
    fun parse(text: String): RtttlSong? {
        val trimmed = text.trim()
        if (trimmed.length > 2000) return null
        val parts = trimmed.split(":")
        if (parts.size != 3) return null

        val name = parts[0].trim()
        if (name.isEmpty()) return null

        val controlPart = parts[1].trim()
        var defaultDuration = 4
        var defaultOctave = 6
        var bpm = 63

        var hasDuration = false
        var hasOctave = false
        var hasBpm = false

        for (control in controlPart.split(",")) {
            val trimmedCtrl = control.trim().lowercase()
            if (!trimmedCtrl.contains("=")) continue
            val subparts = trimmedCtrl.split("=", limit = 2)
            if (subparts.size != 2) continue
            val key = subparts[0].trim()
            val value = subparts[1].trim().toIntOrNull() ?: continue
            when (key) {
                "d" -> {
                    defaultDuration = value
                    hasDuration = true
                }

                "o" -> {
                    defaultOctave = value
                    hasOctave = true
                }

                "b" -> {
                    bpm = value
                    hasBpm = true
                }
            }
        }

        val controlCount = (if (hasDuration) 1 else 0) + (if (hasOctave) 1 else 0) + (if (hasBpm) 1 else 0)
        if (controlCount < 2) return null
        if (bpm <= 0 || defaultDuration <= 0 || defaultOctave !in 1..8) return null

        val notesPart = parts[2].trim()
        val noteTokens = notesPart.split(",")
        if (noteTokens.size < 2) return null

        val notes = ArrayList<RtttlNote>(noteTokens.size)
        val wholeNoteMs = 240_000.0 / bpm

        for (token in noteTokens) {
            val t = token.trim()
            if (t.isEmpty()) continue
            val match = NOTE_REGEX.matchEntire(t) ?: return null

            val (durStr, dot1Str, noteCharStr, sharpStr, dot2Str, octStr, dot3Str) = match.destructured
            val noteChar = noteCharStr.first().lowercaseChar()
            val duration = durStr.toIntOrNull() ?: defaultDuration
            if (duration <= 0) return null

            val isDotted = dot1Str.isNotEmpty() || dot2Str.isNotEmpty() || dot3Str.isNotEmpty()
            val octave = octStr.toIntOrNull() ?: defaultOctave
            if (octave !in 1..8) return null

            var durationMs = (wholeNoteMs / duration).toLong()
            if (isDotted) {
                durationMs = (durationMs * 1.5).toLong()
            }

            val isRest = noteChar == 'p'
            val isSharp = sharpStr == "#"
            val isFlat = sharpStr == "b" || sharpStr == "_"
            val freq = if (isRest) 0f else noteToFrequency(noteChar, isSharp, isFlat, octave)

            notes.add(RtttlNote(freq, durationMs))
        }

        if (notes.isEmpty()) return null
        return RtttlSong(name, defaultDuration, defaultOctave, bpm, notes)
    }
}
