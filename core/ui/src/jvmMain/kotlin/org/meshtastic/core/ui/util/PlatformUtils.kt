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
@file:Suppress("TooManyFunctions")

package org.meshtastic.core.ui.util

import androidx.compose.runtime.Composable
import co.touchlab.kermit.Logger
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.meshtastic.core.common.util.CommonUri
import org.meshtastic.core.common.util.ioDispatcher
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import javax.swing.JFileChooser

/** JVM stub — NFC settings are not available on Desktop. */
@Composable
actual fun rememberOpenNfcSettings(): () -> Unit = { Logger.w { "NFC settings not available on JVM/Desktop" } }

/** JVM stub — toast messages are logged instead. */
@Composable actual fun rememberShowToast(): suspend (String) -> Unit = { message -> Logger.i { "Toast: $message" } }

/** JVM stub — toast messages are logged instead. */
@Composable
actual fun rememberShowToastResource(): suspend (StringResource) -> Unit = { _ -> Logger.i { "Toast (resource)" } }

/** JVM stub — map opening is not available on Desktop. */
@Composable
actual fun rememberOpenMap(): (latitude: Double, longitude: Double, label: String) -> Unit = { lat, lon, label ->
    Logger.i { "Open map: $lat, $lon ($label)" }
}

/** JVM stub — URL opening via Desktop browse API. */
@Composable
actual fun rememberOpenUrl(): (url: String) -> Unit = { url ->
    try {
        java.awt.Desktop.getDesktop().browse(java.net.URI(url))
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Logger.w(e) { "Failed to open URL: $url" }
    }
}

/** JVM — Opens a native file dialog to save a file. */
@Composable
actual fun rememberSaveFileLauncher(
    onUriReceived: (CommonUri) -> Unit,
): (defaultFilename: String, mimeType: String) -> Unit = { defaultFilename, _ ->
    val dialog = FileDialog(null as Frame?, "Save File", FileDialog.SAVE)
    dialog.file = defaultFilename
    dialog.isVisible = true
    val file = dialog.file
    val dir = dialog.directory
    if (file != null && dir != null) {
        val path = File(dir, file)
        onUriReceived(CommonUri.parse(path.toURI().toString()))
    }
}

/** JVM — Opens a native file dialog to pick a file. */
@Composable
actual fun rememberOpenFileLauncher(onUriReceived: (CommonUri?) -> Unit): (mimeType: String) -> Unit = { _ ->
    // Explicit Frame? local disambiguates the FileDialog(Frame, ...) overload from FileDialog(Dialog, ...)
    val parentFrame: Frame? = null
    val dialog = FileDialog(parentFrame, "Open File", FileDialog.LOAD)
    dialog.isVisible = true
    val file = dialog.file
    val dir = dialog.directory
    if (file != null && dir != null) {
        val path = File(dir, file)
        onUriReceived(CommonUri.parse(path.toURI().toString()))
    }
}

/** JVM — Opens a native dialog to pick a directory. */
@Composable
actual fun rememberOpenDocumentTreeLauncher(onTreeUriSelect: (CommonUri?) -> Unit): () -> Unit = {
    // AWT FileDialog cannot select directories portably; JFileChooser can.
    val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        onTreeUriSelect(CommonUri.parse(chooser.selectedFile.toURI().toString()))
    } else {
        onTreeUriSelect(null)
    }
}

/** JVM — Reads text from a file URI. */
@Composable
actual fun rememberReadTextFromUri(): suspend (uri: CommonUri, maxChars: Int) -> String? = { uri, maxChars ->
    withContext(ioDispatcher) {
        @Suppress("TooGenericExceptionCaught")
        try {
            val file = File(URI(uri.toString()))
            if (file.exists()) {
                file.bufferedReader().use { reader ->
                    val buffer = CharArray(maxChars)
                    val read = reader.read(buffer)
                    if (read > 0) String(buffer, 0, read) else null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Logger.e(e) { "Failed to read text from URI: $uri" }
            null
        }
    }
}

/** JVM no-op — Keep screen on is not applicable on Desktop. */
@Composable
actual fun KeepScreenOn(enabled: Boolean) {
    // No-op on JVM/Desktop
}

@Composable
actual fun rememberOpenLocationSettings(): () -> Unit = { Logger.w { "Location settings not implemented on Desktop" } }

/** JVM stub — Bluetooth settings are not available on Desktop. */
@Composable
actual fun rememberOpenBluetoothSettings(): () -> Unit = {
    Logger.w { "Bluetooth settings not available on JVM/Desktop" }
}

/** JVM stub — Wi-Fi settings are not available on Desktop. */
@Composable
actual fun rememberOpenWifiSettings(): () -> Unit = { Logger.w { "Wi-Fi settings not available on JVM/Desktop" } }

/** JVM — GPS is never disabled on Desktop (concept doesn't apply). */
actual val bleScanRequiresLocationServices: Boolean = false

@Composable actual fun isGpsDisabled(): Boolean = false

/** JVM — Bluetooth adapter state is not surfaced on Desktop. */
@Composable actual fun isBluetoothDisabled(): Boolean = false

/** JVM — local-network availability is not gated on Desktop. */
@Composable actual fun isWifiUnavailable(): Boolean = false

/** JVM stub — app settings are not available on Desktop. */
@Composable
actual fun rememberOpenAppSettings(): () -> Unit = { Logger.w { "App settings not available on JVM/Desktop" } }

/** JVM — Desktop does not gate location behind a runtime permission. */
@Composable actual fun rememberLocationPermissionState(): PermissionUiState = grantedPermissionUiState()

/** JVM — Desktop does not gate Bluetooth behind a runtime permission. */
@Composable actual fun rememberBluetoothPermissionState(): PermissionUiState = grantedPermissionUiState()

/** JVM — Desktop does not gate notifications behind a runtime permission. */
@Composable actual fun rememberNotificationPermissionState(): PermissionUiState = grantedPermissionUiState()

/** JVM — Desktop does not gate local-network access behind a runtime permission. */
@Composable actual fun rememberLocalNetworkPermissionState(): PermissionUiState = grantedPermissionUiState()

/** JVM — Desktop does not gate the camera behind a runtime permission. */
@Composable actual fun rememberCameraPermissionState(): PermissionUiState = grantedPermissionUiState()

/** JVM — Reads image, resizes, and converts to grayscale array for import. */
@Suppress("LongMethod", "TooGenericExceptionCaught", "MagicNumber")
@Composable
actual fun rememberReadImageGrayValuesFromUri():
    suspend (uri: CommonUri, reqWidth: Int, reqHeight: Int) -> FloatArray? =
    { uri, reqWidth, reqHeight ->
        withContext(ioDispatcher) {
            try {
                val file = File(URI(uri.toString()))
                if (file.exists()) {
                    val originalImage = javax.imageio.ImageIO.read(file)
                    if (originalImage != null) {
                        val origW = originalImage.width
                        val origH = originalImage.height
                        val aspect = origW.toFloat() / origH.toFloat()

                        val targetWidth: Int
                        val targetHeight: Int
                        if (origW > origH) {
                            targetWidth = reqWidth
                            targetHeight = (reqWidth / aspect).toInt()
                        } else {
                            targetHeight = reqHeight
                            targetWidth = (reqHeight * aspect).toInt()
                        }

                        val scaled =
                            java.awt.image.BufferedImage(
                                targetWidth.coerceAtLeast(1),
                                targetHeight.coerceAtLeast(1),
                                java.awt.image.BufferedImage.TYPE_INT_ARGB,
                            )
                        val g2d = scaled.createGraphics()
                        g2d.setRenderingHint(
                            java.awt.RenderingHints.KEY_INTERPOLATION,
                            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                        )
                        g2d.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null)
                        g2d.dispose()

                        val pixels = IntArray(targetWidth * targetHeight)
                        scaled.getRGB(0, 0, targetWidth, targetHeight, pixels, 0, targetWidth)

                        val grayValues = FloatArray(reqWidth * reqHeight)
                        val startX = (reqWidth - targetWidth) / 2
                        val startY = (reqHeight - targetHeight) / 2

                        for (y in 0 until targetHeight) {
                            for (x in 0 until targetWidth) {
                                val color = pixels[y * targetWidth + x]
                                val r = (color shr 16) and 0xFF
                                val g = (color shr 8) and 0xFF
                                val b = color and 0xFF
                                val a = (color shr 24) and 0xFF

                                val luminance =
                                    if (a < 128) {
                                        1f
                                    } else {
                                        (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                                    }

                                val outIdx = (startY + y) * reqWidth + (startX + x)
                                grayValues[outIdx] = luminance
                            }
                        }
                        grayValues
                    } else {
                        null
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                Logger.e(e) { "Failed to read image from URI: $uri" }
                null
            }
        }
    }

@Suppress("TooGenericExceptionCaught")
@Composable
actual fun rememberReadBytesFromUri(): suspend (uri: CommonUri) -> ByteArray? = { uri ->
    withContext(ioDispatcher) {
        try {
            val file = File(URI(uri.toString()))
            if (file.exists()) file.readBytes() else null
        } catch (e: Exception) {
            Logger.e(e) { "Failed to read bytes from URI: $uri" }
            null
        }
    }
}

@Suppress("TooGenericExceptionCaught")
@Composable
actual fun rememberGetFileInfo(): suspend (uri: CommonUri) -> FileInfo? = { uri ->
    withContext(ioDispatcher) {
        try {
            val file = File(URI(uri.toString()))
            if (file.exists()) FileInfo(name = file.name, size = file.length()) else null
        } catch (e: Exception) {
            Logger.e(e) { "Failed to get file info from URI: $uri" }
            null
        }
    }
}

@Composable
actual fun rememberSaveToDownloads(): suspend (fileName: String, data: ByteArray) -> String? = { fileName, data ->
    withContext(ioDispatcher) { saveFileToDownloads(fileName, data) }
}

@Suppress("TooGenericExceptionCaught")
actual fun saveFileToDownloads(fileName: String, data: ByteArray): String? = try {
    val home = System.getProperty("user.home") ?: "."
    val downloadsDir = File(home, "Downloads")
    val meshtasticDir = File(downloadsDir, "Meshtastic")
    if (!meshtasticDir.exists()) {
        meshtasticDir.mkdirs()
    }
    val targetFile = File(meshtasticDir, fileName)
    targetFile.writeBytes(data)
    targetFile.absolutePath
} catch (e: Exception) {
    Logger.e(e) { "Failed to save file to Downloads/Meshtastic: $fileName" }
    null
}
