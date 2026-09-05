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
package org.meshtastic.feature.messaging.filetransfer

/** Observable state of a file transfer (send or receive). */
sealed interface TransferState {
    data object Idle : TransferState

    data class Sending(
        val fileName: String,
        val fileSize: Long,
        val totalChunks: Int,
        val currentChunk: Int,
        val retryCount: Int = 0,
        val isMinimized: Boolean = false,
        val transferId: Int = 0,
        val bytesTransferred: Long = 0L,
        val speedBytesPerSec: Double = 0.0,
        val isGzip: Boolean = false,
        val passNumber: Int = 1,
        val statusMessage: String = "",
    ) : TransferState {
        val progress: Float
            get() = if (totalChunks > 0) currentChunk.toFloat() / totalChunks else 0f

        val percent: Int
            get() = (progress * 100).toInt().coerceIn(0, 100)

        val etaSeconds: Long?
            get() =
                if (speedBytesPerSec > 5.0 && fileSize > bytesTransferred) {
                    ((fileSize - bytesTransferred) / speedBytesPerSec).toLong()
                } else {
                    null
                }
    }

    data class Receiving(
        val fileName: String,
        val fileSize: Long,
        val totalChunks: Int,
        val receivedChunks: Int,
        val isMinimized: Boolean = false,
        val transferId: Int = 0,
        val bytesTransferred: Long = 0L,
        val speedBytesPerSec: Double = 0.0,
        val isGzip: Boolean = false,
        val passNumber: Int = 1,
        val statusMessage: String = "",
    ) : TransferState {
        val progress: Float
            get() = if (totalChunks > 0) receivedChunks.toFloat() / totalChunks else 0f

        val percent: Int
            get() = (progress * 100).toInt().coerceIn(0, 100)

        val etaSeconds: Long?
            get() =
                if (speedBytesPerSec > 5.0 && fileSize > bytesTransferred) {
                    ((fileSize - bytesTransferred) / speedBytesPerSec).toLong()
                } else {
                    null
                }
    }

    data class Completed(val fileName: String, val savedPath: String?, val statusMessage: String = "") : TransferState

    data class Failed(val fileName: String, val reason: String, val canRetry: Boolean = false) : TransferState
}

@Suppress("MagicNumber")
fun formatSpeed(bytesPerSec: Double): String = when {
    bytesPerSec >= 1024.0 -> {
        val kb = bytesPerSec / 1024.0
        "${((kb * 10).toInt() / 10.0)} КБ/с"
    }

    bytesPerSec > 0.0 -> "${bytesPerSec.toInt()} Б/с"

    else -> "—"
}

@Suppress("MagicNumber")
fun formatEta(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "~$m мин $s сек" else "~$s сек"
}
