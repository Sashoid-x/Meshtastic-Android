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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RtttlParserTest {

    @Test
    fun parseValidNokiaTune() {
        val rtttl = "NokiaTune:d=4,o=5,b=225:8e6,8d6,f#,g#,8c#6,8b,d,e,8b,8a,c#,e,2a"
        val song = RtttlParser.parse(rtttl)
        assertNotNull(song)
        assertEquals("NokiaTune", song.name)
        assertEquals(4, song.defaultDuration)
        assertEquals(5, song.defaultOctave)
        assertEquals(225, song.bpm)
        assertEquals(13, song.notes.size)
        assertTrue(song.totalDurationMs > 0)
        assertTrue(RtttlParser.isRtttl(rtttl))
    }

    @Test
    fun parseRestsAndSharps() {
        val rtttl = "Short:d=4,o=6,b=120:8c,8p,8c#"
        val song = RtttlParser.parse(rtttl)
        assertNotNull(song)
        assertEquals(3, song.notes.size)
        assertTrue(song.notes[0].frequencyHz > 0f)
        assertEquals(0f, song.notes[1].frequencyHz) // rest
        assertTrue(song.notes[2].frequencyHz > song.notes[0].frequencyHz) // C# higher than C
    }

    @Test
    fun parseDottedNotes() {
        val rtttl = "Dotted:d=4,o=5,b=60:4.c,8c."
        val song = RtttlParser.parse(rtttl)
        assertNotNull(song)
        assertEquals(2, song.notes.size)
        // At 60 bpm, whole note = 4000ms, quarter note = 1000ms. Dotted quarter = 1500ms
        assertEquals(1500L, song.notes[0].durationMs)
        // Eighth note = 500ms. Dotted eighth = 750ms
        assertEquals(750L, song.notes[1].durationMs)
    }

    @Test
    fun rejectNonRtttlText() {
        assertNull(RtttlParser.parse("Hello world!"))
        assertNull(RtttlParser.parse("foo:bar:baz"))
        assertNull(RtttlParser.parse(""))
        assertFalse(RtttlParser.isRtttl("This is just a regular message with : colons : inside"))
    }

    @Test
    fun formattedDuration() {
        val rtttl = "NokiaTune:d=4,o=5,b=225:8e6,8d6,f#,g#,8c#6,8b,d,e,8b,8a,c#,e,2a"
        val song = RtttlParser.parse(rtttl)
        assertNotNull(song)
        assertTrue(song.formattedDuration.matches(Regex("""\d+:\d{2}""")))
    }
}
