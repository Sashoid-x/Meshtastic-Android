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
package org.meshtastic.desktop

import co.touchlab.kermit.Logger
import org.koin.core.annotation.Single
import org.meshtastic.core.automation.engine.AutomationAudioSpeaker
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Single(binds = [AutomationAudioSpeaker::class])
class DesktopAutomationAudioSpeaker : AutomationAudioSpeaker {

    override fun playAlarm(alarmType: String, durationSeconds: Int) {
        try {
            Toolkit.getDefaultToolkit().beep()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Logger.e(e) { "Failed to play desktop alarm" }
        }
    }

    override fun playSound(soundId: String) {
        try {
            Toolkit.getDefaultToolkit().beep()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Logger.e(e) { "Failed to play desktop sound: $soundId" }
        }
    }

    override fun speakText(text: String, speechRate: Float) {
        Logger.i { "Desktop TTS: $text" }
    }

    override fun vibrate(pattern: String, durationMs: Long) {
        Logger.d { "Desktop vibration requested: $pattern ($durationMs ms)" }
    }

    override fun copyToClipboard(text: String) {
        try {
            val selection = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Logger.e(e) { "Failed to copy to desktop clipboard" }
        }
    }
}
