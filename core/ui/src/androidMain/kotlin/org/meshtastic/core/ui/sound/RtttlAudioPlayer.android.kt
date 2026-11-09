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
package org.meshtastic.core.ui.sound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.meshtastic.core.common.util.RtttlSong
import kotlin.math.PI
import kotlin.math.sin

@Suppress("TooGenericExceptionCaught")
actual object RtttlAudioPlayer {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var playbackJob: Job? = null
    private var activeTrack: AudioTrack? = null

    private val _playingId = MutableStateFlow<String?>(null)
    actual val playingId: StateFlow<String?> = _playingId.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    actual val progress: StateFlow<Float> = _progress.asStateFlow()

    private const val SAMPLE_RATE = 44100
    private const val ATTACK_MS = 3L
    private const val DECAY_MS = 3L
    private const val GAP_MS = 15L
    private const val MAX_AMPLITUDE = 28000.0

    @Synchronized
    actual fun play(id: String, song: RtttlSong, onCompletion: () -> Unit) {
        stop()

        _playingId.value = id
        _progress.value = 0f

        playbackJob = scope.launch {
            var audioTrack: AudioTrack? = null
            try {
                val pcmData = synthesizePcm(song)
                if (pcmData.isEmpty()) {
                    return@launch
                }

                val audioAttributes =
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()

                val audioFormat =
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()

                val track =
                    AudioTrack.Builder()
                        .setAudioAttributes(audioAttributes)
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(pcmData.size * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()

                track.write(pcmData, 0, pcmData.size)
                synchronized(this@RtttlAudioPlayer) {
                    activeTrack = track
                }
                audioTrack = track
                track.play()

                val totalFrames = pcmData.size
                while (isActive && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    val head = track.playbackHeadPosition
                    _progress.value = (head.toFloat() / totalFrames).coerceIn(0f, 1f)
                    if (head >= totalFrames) {
                        break
                    }
                    delay(30)
                }
            } catch (e: Exception) {
                Logger.e(e) { "Error playing RTTTL melody" }
            } finally {
                synchronized(this@RtttlAudioPlayer) {
                    if (activeTrack == audioTrack) {
                        activeTrack = null
                    }
                }
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                } catch (e: Exception) {
                    Logger.w(e) { "Error releasing AudioTrack" }
                }
                _progress.value = 0f
                if (_playingId.value == id) {
                    _playingId.value = null
                }
                onCompletion()
            }
        }
    }

    @Synchronized
    actual fun stop() {
        val job = playbackJob
        playbackJob = null
        job?.cancel()

        val track = activeTrack
        activeTrack = null
        try {
            track?.stop()
            track?.release()
        } catch (e: Exception) {
            Logger.w(e) { "Error stopping AudioTrack" }
        }

        _progress.value = 0f
        _playingId.value = null
    }

    private fun synthesizePcm(song: RtttlSong): ShortArray {
        var totalSamples = 0
        for (note in song.notes) {
            totalSamples += ((note.durationMs * SAMPLE_RATE) / 1000).toInt()
        }
        if (totalSamples <= 0) return ShortArray(0)

        val buffer = ShortArray(totalSamples)
        var offset = 0

        val attackSamples = ((ATTACK_MS * SAMPLE_RATE) / 1000).toInt()
        val decaySamples = ((DECAY_MS * SAMPLE_RATE) / 1000).toInt()
        val gapSamples = ((GAP_MS * SAMPLE_RATE) / 1000).toInt()

        for (note in song.notes) {
            val noteSamples = ((note.durationMs * SAMPLE_RATE) / 1000).toInt()
            if (noteSamples <= 0) continue

            if (note.frequencyHz > 0f) {
                val gap = gapSamples.coerceAtMost(noteSamples / 4)
                val activeSamples = noteSamples - gap
                val attack = attackSamples.coerceAtMost(activeSamples / 2)
                val decay = decaySamples.coerceAtMost(activeSamples / 2)

                val omega = 2.0 * PI * note.frequencyHz / SAMPLE_RATE
                var phase = 0.0

                for (i in 0 until activeSamples) {
                    val amp =
                        when {
                            attack > 0 && i < attack -> i.toDouble() / attack
                            decay > 0 && i > activeSamples - decay -> (activeSamples - i).toDouble() / decay
                            else -> 1.0
                        }
                    val sample =
                        (sin(phase) * amp * MAX_AMPLITUDE)
                            .toInt()
                            .coerceIn(
                                Short.MIN_VALUE.toInt(),
                                Short.MAX_VALUE.toInt(),
                            )
                    buffer[offset + i] = sample.toShort()
                    phase += omega
                }
            }
            offset += noteSamples
        }

        return buffer
    }
}
