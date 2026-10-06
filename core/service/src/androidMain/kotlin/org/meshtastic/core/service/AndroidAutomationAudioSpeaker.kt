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
package org.meshtastic.core.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import co.touchlab.kermit.Logger
import org.koin.core.annotation.Single
import org.meshtastic.core.automation.engine.AutomationAudioSpeaker
import java.util.Locale

private const val MILLIS_PER_SECOND = 1000L
private const val ALARM_DURATION_MS = 5000L
private const val NOTIF_DURATION_MS = 3000L
private const val BEEP_DURATION_MS = 250
private const val DOUBLE_BEEP_DURATION_MS = 350
private const val CHIME_DURATION_MS = 400
private const val SIREN_DURATION_MS = 1500
private const val SOS_DURATION_MS = 1000
private const val TONE_MAX_VOLUME = 100
private const val TONE_RELEASE_BUFFER_MS = 200
private const val MAX_PENDING_TTS_QUEUE_SIZE = 10

@Suppress("TooGenericExceptionCaught")
@Single(binds = [AutomationAudioSpeaker::class])
class AndroidAutomationAudioSpeaker(private val context: Context) : AutomationAudioSpeaker {

    private var activeRingtone: Ringtone? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false
    private val pendingTtsQueue = mutableListOf<Pair<String, Float>>()

    init {
        mainHandler.post {
            textToSpeech =
                TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        isTtsInitialized = true
                        textToSpeech?.language = Locale.getDefault()
                        synchronized(pendingTtsQueue) {
                            pendingTtsQueue.forEach { (text, rate) ->
                                speakInternal(text, rate)
                            }
                            pendingTtsQueue.clear()
                        }
                    } else {
                        Logger.w { "Automation TTS initialization failed with status $status" }
                    }
                }
        }
    }

    override fun playAlarm(alarmType: String, durationSeconds: Int) {
        mainHandler.post {
            try {
                activeRingtone?.stop()
                val (type, usage) =
                    when (alarmType.lowercase()) {
                        "ringtone" -> Pair(RingtoneManager.TYPE_RINGTONE, AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        "notification" -> Pair(RingtoneManager.TYPE_NOTIFICATION, AudioAttributes.USAGE_NOTIFICATION)
                        else -> Pair(RingtoneManager.TYPE_ALARM, AudioAttributes.USAGE_ALARM)
                    }
                val alarmUri =
                    RingtoneManager.getDefaultUri(type)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

                val ringtone = RingtoneManager.getRingtone(context, alarmUri)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    ringtone.audioAttributes =
                        AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                }
                activeRingtone = ringtone
                ringtone.play()

                val duration = durationSeconds.coerceAtLeast(1) * MILLIS_PER_SECOND
                mainHandler.postDelayed(
                    {
                        if (activeRingtone == ringtone) {
                            ringtone.stop()
                            activeRingtone = null
                        }
                    },
                    duration,
                )
            } catch (e: Exception) {
                Logger.e(e) { "Failed to play automation alarm ($alarmType)" }
            }
        }
    }

    override fun playSound(soundId: String) {
        mainHandler.post {
            try {
                when (soundId) {
                    "alarm" -> playUri(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), ALARM_DURATION_MS)

                    "ringtone" ->
                        playUri(
                            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                            ALARM_DURATION_MS,
                        )

                    "beep" -> playTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS)

                    "double_beep" -> playTone(ToneGenerator.TONE_PROP_ACK, DOUBLE_BEEP_DURATION_MS)

                    "chime" -> playTone(ToneGenerator.TONE_PROP_PROMPT, CHIME_DURATION_MS)

                    "siren" -> playTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, SIREN_DURATION_MS)

                    "morse_sos" -> playTone(ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE, SOS_DURATION_MS)

                    else -> playUri(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), NOTIF_DURATION_MS)
                }
            } catch (e: Exception) {
                Logger.e(e) { "Failed to play automation sound: $soundId" }
            }
        }
    }

    private fun playUri(uri: Uri?, maxDurationMs: Long) {
        if (uri == null) return
        val ringtone = RingtoneManager.getRingtone(context, uri) ?: return
        activeRingtone?.stop()
        activeRingtone = ringtone
        ringtone.play()
        mainHandler.postDelayed(
            {
                if (activeRingtone == ringtone) {
                    ringtone.stop()
                    activeRingtone = null
                }
            },
            maxDurationMs,
        )
    }

    private fun playTone(toneType: Int, durationMs: Int) {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_MAX_VOLUME)
            toneGen.startTone(toneType, durationMs)
            mainHandler.postDelayed(
                {
                    runCatching { toneGen.release() }
                },
                (durationMs + TONE_RELEASE_BUFFER_MS).toLong(),
            )
        } catch (e: Exception) {
            Logger.e(e) { "Failed to generate tone" }
        }
    }

    override fun vibrate(pattern: String, durationMs: Long) {
        mainHandler.post {
            try {
                val vibrator = getVibrator() ?: return@post
                if (!vibrator.hasVibrator()) return@post
                triggerVibration(vibrator, pattern, durationMs)
            } catch (e: Exception) {
                Logger.e(e) { "Failed to vibrate device" }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getVibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    @Suppress("DEPRECATION", "MagicNumber")
    private fun triggerVibration(vibrator: Vibrator, pattern: String, durationMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect =
                when (pattern) {
                    "double" -> VibrationEffect.createWaveform(longArrayOf(0, 150, 100, 150), -1)

                    "long" -> VibrationEffect.createOneShot(1000L, VibrationEffect.DEFAULT_AMPLITUDE)

                    "sos" ->
                        VibrationEffect.createWaveform(
                            longArrayOf(
                                0,
                                150,
                                100,
                                150,
                                100,
                                150,
                                300,
                                400,
                                100,
                                400,
                                100,
                                400,
                                300,
                                150,
                                100,
                                150,
                                100,
                                150,
                            ),
                            -1,
                        )

                    else ->
                        VibrationEffect.createOneShot(
                            durationMs.coerceAtLeast(100L),
                            VibrationEffect.DEFAULT_AMPLITUDE,
                        )
                }
            vibrator.vibrate(effect)
        } else {
            when (pattern) {
                "double" -> vibrator.vibrate(longArrayOf(0, 150, 100, 150), -1)

                "long" -> vibrator.vibrate(1000L)

                "sos" ->
                    vibrator.vibrate(
                        longArrayOf(
                            0,
                            150,
                            100,
                            150,
                            100,
                            150,
                            300,
                            400,
                            100,
                            400,
                            100,
                            400,
                            300,
                            150,
                            100,
                            150,
                            100,
                            150,
                        ),
                        -1,
                    )

                else -> vibrator.vibrate(durationMs)
            }
        }
    }

    override fun copyToClipboard(text: String) {
        mainHandler.post {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("Meshtastic Automation", text)
                clipboard?.setPrimaryClip(clip)
            } catch (e: Exception) {
                Logger.e(e) { "Failed to copy to clipboard" }
            }
        }
    }

    override fun speakText(text: String, speechRate: Float) {
        mainHandler.post {
            if (isTtsInitialized) {
                speakInternal(text, speechRate)
            } else {
                synchronized(pendingTtsQueue) {
                    if (pendingTtsQueue.size >= MAX_PENDING_TTS_QUEUE_SIZE) {
                        pendingTtsQueue.removeAt(0)
                    }
                    pendingTtsQueue.add(text to speechRate)
                }
            }
        }
    }

    private fun speakInternal(text: String, speechRate: Float) {
        textToSpeech?.let { tts ->
            tts.setSpeechRate(speechRate)
            val utteranceId = "automation_tts_${System.currentTimeMillis()}"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
            } else {
                @Suppress("DEPRECATION")
                tts.speak(text, TextToSpeech.QUEUE_ADD, null)
            }
        }
    }
}
