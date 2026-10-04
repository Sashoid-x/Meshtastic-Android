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
package org.meshtastic.core.automation.engine

/** Platform audio interface for playing loud alarms / sirens and Text-to-Speech synthesis. */
interface AutomationAudioSpeaker {
    /** Plays an audible siren or alarm on the device, bypassing silent mode where permitted. */
    fun playAlarm(alarmType: String, durationSeconds: Int)

    /** Plays a specific sound effect or system tone. */
    fun playSound(soundId: String)

    /** Synthesizes and speaks the given text aloud via Text-to-Speech (TTS). */
    fun speakText(text: String, speechRate: Float = 1.0f)

    /** Vibrates the device using the requested pattern. */
    fun vibrate(pattern: String = "short", durationMs: Long = 500L)

    /** Copies text to the system clipboard. */
    fun copyToClipboard(text: String)
}
