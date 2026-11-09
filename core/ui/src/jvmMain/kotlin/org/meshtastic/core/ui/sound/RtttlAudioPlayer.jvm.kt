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

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.meshtastic.core.common.util.RtttlSong
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.PI
import kotlin.math.sin

@Suppress("TooGenericExceptionCaught")
actual object RtttlAudioPlayer {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var playbackJob: Job? = null
    private var activeLine: SourceDataLine? = null

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
            var dataLine: SourceDataLine? = null
            try {
                val pcmBytes = synthesizePcmBytes(song)
                if (pcmBytes.isEmpty()) return@launch

                val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
                val line = AudioSystem.getSourceDataLine(format)
                line.open(format)
                synchronized(this@RtttlAudioPlayer) {
                    activeLine = line
                }
                dataLine = line
                line.start()

                val chunkSize = 4096
                var offset = 0
                val totalBytes = pcmBytes.size

                while (isActive && offset < totalBytes) {
                    val toWrite = minOf(chunkSize, totalBytes - offset)
                    line.write(pcmBytes, offset, toWrite)
                    offset += toWrite
                    _progress.value = (offset.toFloat() / totalBytes).coerceIn(0f, 1f)
                }
                line.drain()
            } catch (e: Exception) {
                Logger.e(e) { "Error playing RTTTL on JVM" }
            } finally {
                synchronized(this@RtttlAudioPlayer) {
                    if (activeLine == dataLine) {
                        activeLine = null
                    }
                }
                try {
                    dataLine?.stop()
                    dataLine?.close()
                } catch (e: Exception) {
                    Logger.w(e) { "Error closing line" }
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

        val line = activeLine
        activeLine = null
        try {
            line?.stop()
            line?.close()
        } catch (e: Exception) {
            Logger.w(e) { "Error stopping line" }
        }

        _progress.value = 0f
        _playingId.value = null
    }

    private fun synthesizePcmBytes(song: RtttlSong): ByteArray {
        var totalSamples = 0
        for (note in song.notes) {
            totalSamples += ((note.durationMs * SAMPLE_RATE) / 1000).toInt()
        }
        if (totalSamples <= 0) return ByteArray(0)

        val byteBuffer = ByteBuffer.allocate(totalSamples * 2).order(ByteOrder.LITTLE_ENDIAN)

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
                    byteBuffer.putShort(sample.toShort())
                    phase += omega
                }
                for (i in activeSamples until noteSamples) {
                    byteBuffer.putShort(0)
                }
            } else {
                for (i in 0 until noteSamples) {
                    byteBuffer.putShort(0)
                }
            }
        }
        return byteBuffer.array()
    }
}
