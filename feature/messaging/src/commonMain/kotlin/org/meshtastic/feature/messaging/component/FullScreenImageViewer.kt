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
@file:Suppress("MagicNumber")

package org.meshtastic.feature.messaging.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.close
import org.meshtastic.core.resources.image_loading_error
import org.meshtastic.core.resources.image_viewer_open_system
import org.meshtastic.core.resources.image_viewer_save
import org.meshtastic.core.resources.image_viewer_share
import org.meshtastic.core.ui.icon.Close
import org.meshtastic.core.ui.icon.Download
import org.meshtastic.core.ui.icon.FolderOpen
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.Share
import org.meshtastic.core.ui.util.rememberGetLocalImageFile
import org.meshtastic.core.ui.util.rememberOpenFile
import org.meshtastic.core.ui.util.rememberSaveImageLocally
import org.meshtastic.core.ui.util.rememberShareFileOrUrl

/**
 * Built-in full-screen image viewer supporting pinch-to-zoom, pan, double-tap zoom, and Share, Open in System, and Save
 * actions.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun FullScreenImageViewer(
    imageUrl: String,
    onDismiss: () -> Unit,
    rawUrl: String? = null,
    initialLocalFilePath: String? = null,
    onShowSnackbar: (String) -> Unit = {},
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var showControls by remember { mutableStateOf(true) }

        val openFile = rememberOpenFile()
        val getLocalImageFile = rememberGetLocalImageFile()
        val saveImageLocally = rememberSaveImageLocally()
        val shareFileOrUrl = rememberShareFileOrUrl()
        val coroutineScope = rememberCoroutineScope()

        var currentLocalPath by
            remember(imageUrl) { mutableStateOf(initialLocalFilePath ?: getLocalImageFile(imageUrl)) }

        Box(
            modifier =
            Modifier.fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showControls = !showControls },
                        onDoubleTap = {
                            if (scale > 1.2f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        scale = newScale
                        if (newScale > 1f) {
                            val maxOffsetX = (size.width * (newScale - 1f)) / 2f
                            val maxOffsetY = (size.height * (newScale - 1f)) / 2f
                            offset =
                                Offset(
                                    x = (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                                    y = (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY),
                                )
                        } else {
                            offset = Offset.Zero
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            SubcomposeAsyncImage(
                model = currentLocalPath ?: imageUrl,
                contentDescription = null,
                modifier =
                Modifier.fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
                contentScale = ContentScale.Fit,
                onSuccess = { state ->
                    if (currentLocalPath == null) {
                        currentLocalPath = saveImageLocally(imageUrl, state.result.image)
                    }
                },
                loading = {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                },
                error = {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(Res.string.image_loading_error),
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                },
            )

            // Top control bar
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Row(
                    modifier =
                    Modifier.fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)),
                    ) {
                        Icon(
                            imageVector = MeshtasticIcons.Close,
                            contentDescription = stringResource(Res.string.close),
                            tint = Color.White,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }

            // Bottom control bar
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Surface(color = Color.Black.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Share
                        FilledTonalIconButton(
                            onClick = {
                                val target = currentLocalPath ?: getLocalImageFile(imageUrl)
                                shareFileOrUrl(target, rawUrl ?: imageUrl)
                            },
                            colors =
                            IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color.White.copy(alpha = 0.2f),
                                contentColor = Color.White,
                            ),
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = MeshtasticIcons.Share,
                                contentDescription = stringResource(Res.string.image_viewer_share),
                                modifier = Modifier.size(24.dp),
                            )
                        }

                        // Open in System
                        FilledTonalIconButton(
                            onClick = {
                                val target = currentLocalPath ?: getLocalImageFile(imageUrl)
                                if (target != null) {
                                    openFile(target)
                                } else {
                                    shareFileOrUrl(null, rawUrl ?: imageUrl)
                                }
                            },
                            colors =
                            IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color.White.copy(alpha = 0.2f),
                                contentColor = Color.White,
                            ),
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = MeshtasticIcons.FolderOpen,
                                contentDescription = stringResource(Res.string.image_viewer_open_system),
                                modifier = Modifier.size(24.dp),
                            )
                        }

                        // Save
                        FilledTonalIconButton(
                            onClick = {
                                coroutineScope.launch {
                                    val path = currentLocalPath ?: getLocalImageFile(imageUrl)
                                    if (path != null) {
                                        onShowSnackbar("Saved: $path")
                                    } else {
                                        onShowSnackbar("Image cached")
                                    }
                                }
                            },
                            colors =
                            IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color.White.copy(alpha = 0.2f),
                                contentColor = Color.White,
                            ),
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = MeshtasticIcons.Download,
                                contentDescription = stringResource(Res.string.image_viewer_save),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
