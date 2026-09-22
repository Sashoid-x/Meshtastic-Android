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
@file:Suppress(
    "LongMethod",
    "MagicNumber",
    "CyclomaticComplexMethod",
    "ComposableParamOrder",
    "TooGenericExceptionCaught",
    "MaxLineLength",
    "ReturnCount",
    "NestedBlockDepth",
)

package org.meshtastic.feature.messaging.image

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.common.util.NumberFormatter
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.cancel
import org.meshtastic.core.resources.image_editor_apply
import org.meshtastic.core.resources.image_editor_brightness
import org.meshtastic.core.resources.image_editor_bytes
import org.meshtastic.core.resources.image_editor_clear
import org.meshtastic.core.resources.image_editor_contrast
import org.meshtastic.core.resources.image_editor_crop_title
import org.meshtastic.core.resources.image_editor_dithering
import org.meshtastic.core.resources.image_editor_draw
import org.meshtastic.core.resources.image_editor_erase
import org.meshtastic.core.resources.image_editor_grid
import org.meshtastic.core.resources.image_editor_hint_pan_zoom
import org.meshtastic.core.resources.image_editor_import_photo
import org.meshtastic.core.resources.image_editor_invert
import org.meshtastic.core.resources.image_editor_resolution
import org.meshtastic.core.resources.image_editor_theme
import org.meshtastic.core.resources.image_editor_title
import org.meshtastic.core.resources.send
import kotlin.math.min

private enum class EditorMode {
    DRAW,
    PHOTO_CROP,
}

private fun pixelsSaver(preset: MonochromeResolutionPreset): Saver<BooleanArray, ByteArray> = Saver(
    save = { MonochromeImageCodec.bitPack(it, preset.totalPixels) },
    restore = { MonochromeImageCodec.bitUnpack(it, preset.totalPixels) },
)

/**
 * Unified monochrome image editor.
 *
 * Starts in [EditorMode.DRAW] (pixel-by-pixel drawing). Pressing "Import from photo" calls [onImportPhoto] which should
 * open a file picker; once the caller has [importedGrayValues], the editor switches to [EditorMode.PHOTO_CROP] where
 * the user can pan/zoom, then tap "Apply" to bake into the drawing grid.
 */
@Suppress("LongMethod")
@Composable
fun MonochromeImageEditorDialog(
    onDismiss: () -> Unit,
    onSendImage: (ByteArray) -> Unit,
    onImportPhoto: () -> Unit = {},
    importedGrayValues: FloatArray? = null,
    importedWidth: Int = 500,
    importedHeight: Int = 500,
) {
    var selectedPresetIndex by rememberSaveable { mutableIntStateOf(0) }
    var selectedThemeIndex by rememberSaveable { mutableIntStateOf(0) }
    var showGrid by rememberSaveable { mutableStateOf(false) }
    var photoInvert by rememberSaveable { mutableStateOf(false) }
    var editorMode by rememberSaveable { mutableStateOf(EditorMode.DRAW) }

    // Auto-switch to crop when a photo arrives
    androidx.compose.runtime.LaunchedEffect(importedGrayValues) {
        if (importedGrayValues != null) {
            editorMode = EditorMode.PHOTO_CROP
        }
    }

    val preset = MonochromeImageCodec.getPreset(selectedPresetIndex)
    val currentTheme = MonochromeImageCodec.getTheme(selectedThemeIndex)
    val drawColor = Color(currentTheme.foregroundColor)
    val eraseColor = Color(currentTheme.backgroundColor)

    // DRAW state preserved across configuration changes (orientation)
    val pixels = rememberSaveable(preset.index, saver = pixelsSaver(preset)) { BooleanArray(preset.totalPixels) }
    val trigger = remember { mutableIntStateOf(0) }
    var brushColorBlack by rememberSaveable { mutableStateOf(true) }

    val packetSize: Int by
        remember(preset, selectedThemeIndex, showGrid) {
            derivedStateOf {
                @Suppress("UNUSED_EXPRESSION")
                trigger.value
                MonochromeImageCodec.encode(
                    pixels,
                    selectedPresetIndex,
                    themeIndex = selectedThemeIndex,
                    showGrid = showGrid,
                )
                    .size
            }
        }

    // PHOTO_CROP state
    var photoScale by rememberSaveable { mutableFloatStateOf(1f) }
    var photoOffsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var photoOffsetY by rememberSaveable { mutableFloatStateOf(0f) }
    val photoOffset = Offset(photoOffsetX, photoOffsetY)
    var photoRotation by rememberSaveable { mutableFloatStateOf(0f) }
    var brightness by rememberSaveable { mutableFloatStateOf(0f) }
    var contrast by rememberSaveable { mutableFloatStateOf(1f) }
    var ditherAmount by rememberSaveable { mutableFloatStateOf(1f) }

    androidx.compose.runtime.LaunchedEffect(selectedPresetIndex, importedGrayValues) {
        photoScale = 1f
        photoOffsetX = 0f
        photoOffsetY = 0f
        photoRotation = 0f
    }

    val sampledGrayValues =
        remember(
            importedGrayValues,
            selectedPresetIndex,
            importedWidth,
            importedHeight,
            photoScale,
            photoOffset,
            photoRotation,
        ) {
            val src = importedGrayValues ?: return@remember FloatArray(preset.totalPixels)
            val result = FloatArray(preset.totalPixels)
            // Compute the scale that fits the source image fully into the preset grid (fit = no cropping by default)
            val fitScaleX = importedWidth.toFloat() / preset.width
            val fitScaleY = importedHeight.toFloat() / preset.height
            // Use min so the whole image is visible (fit-inside). User zooms in from there.
            val fitScale = kotlin.math.min(fitScaleX, fitScaleY)
            // At photoScale=1 one preset-cell = fitScale source pixels; zoom multiplies.
            val cellSize = fitScale / photoScale
            val rad = photoRotation.toDouble() * kotlin.math.PI / 180.0
            val cosA = kotlin.math.cos(rad).toFloat()
            val sinA = kotlin.math.sin(rad).toFloat()
            val cx = importedWidth / 2f + photoOffset.x
            val cy = importedHeight / 2f + photoOffset.y
            for (y in 0 until preset.height) {
                for (x in 0 until preset.width) {
                    val dx = (x - preset.width / 2f) * cellSize
                    val dy = (y - preset.height / 2f) * cellSize
                    val sx = cx + cosA * dx + sinA * dy
                    val sy = cy - sinA * dx + cosA * dy
                    val ix = sx.toInt()
                    val iy = sy.toInt()
                    result[y * preset.width + x] =
                        if (ix in 0 until importedWidth && iy in 0 until importedHeight) {
                            src[iy * importedWidth + ix]
                        } else {
                            0.5f // Neutral gray
                        }
                }
            }
            result
        }

    val monoBitsPreview by
        remember(sampledGrayValues, brightness, contrast, ditherAmount, photoInvert) {
            derivedStateOf {
                MonochromeImageCodec.processToMonochrome(
                    grayValues = sampledGrayValues,
                    width = preset.width,
                    height = preset.height,
                    brightness = brightness,
                    contrast = contrast,
                    ditherAmount = ditherAmount,
                    invert = photoInvert,
                )
            }
        }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(0.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                val isLandscape = maxWidth > maxHeight

                if (isLandscape && editorMode == EditorMode.DRAW) {
                    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Left column: Canvas centered
                        Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            DrawCanvas(
                                preset = preset,
                                pixels = pixels,
                                theme = currentTheme,
                                showGrid = showGrid,
                                brushColorBlack = brushColorBlack,
                                trigger = trigger,
                                packetSize = packetSize,
                                onPixelChange = { trigger.value++ },
                            )
                        }

                        // Right column: Scrollable controls
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(Res.string.image_editor_title),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )

                            // Resolution chips
                            Text(
                                stringResource(Res.string.image_editor_resolution, preset.name),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(4.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(MonochromeImageCodec.PRESETS) { p ->
                                    FilterChip(
                                        selected = p.index == selectedPresetIndex,
                                        onClick = { selectedPresetIndex = p.index },
                                        label = { Text(p.name, style = MaterialTheme.typography.bodySmall) },
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))

                            // Theme chips
                            Text(
                                stringResource(Res.string.image_editor_theme, currentTheme.name),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(4.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(MonochromeImageCodec.THEMES) { t ->
                                    FilterChip(
                                        selected = t.id == selectedThemeIndex,
                                        onClick = { selectedThemeIndex = t.id },
                                        leadingIcon = {
                                            Box(
                                                modifier =
                                                Modifier.size(14.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(t.backgroundColor))
                                                    .border(1.dp, Color(t.foregroundColor), CircleShape),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Box(
                                                    modifier =
                                                    Modifier.size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(t.foregroundColor)),
                                                )
                                            }
                                        },
                                        label = { Text(t.name, style = MaterialTheme.typography.bodySmall) },
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))

                            // Brush buttons
                            BrushModeButtons(
                                drawColor = drawColor,
                                eraseColor = eraseColor,
                                brushColorBlack = brushColorBlack,
                                onSelectDraw = { brushColorBlack = true },
                                onSelectErase = { brushColorBlack = false },
                            )
                            Spacer(Modifier.height(8.dp))

                            // Grid toggle
                            Row(
                                Modifier.fillMaxWidth().clickable { showGrid = !showGrid },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    stringResource(Res.string.image_editor_grid),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Switch(checked = showGrid, onCheckedChange = { showGrid = it })
                            }
                            Spacer(Modifier.height(8.dp))

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        for (i in pixels.indices) pixels[i] = !pixels[i]
                                        trigger.value++
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(Res.string.image_editor_invert))
                                }
                                OutlinedButton(
                                    onClick = {
                                        pixels.fill(false)
                                        trigger.value++
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(Res.string.image_editor_clear))
                                }
                                OutlinedButton(onClick = onImportPhoto, modifier = Modifier.weight(1f)) {
                                    Text(stringResource(Res.string.image_editor_import_photo))
                                }
                            }
                            Spacer(Modifier.height(12.dp))

                            // Action row
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                OutlinedButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                ) {
                                    Text(stringResource(Res.string.cancel))
                                }
                                Button(
                                    onClick = {
                                        onSendImage(
                                            MonochromeImageCodec.encode(
                                                pixels,
                                                selectedPresetIndex,
                                                themeIndex = selectedThemeIndex,
                                                showGrid = showGrid,
                                            ),
                                        )
                                    },
                                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                                ) {
                                    Text(stringResource(Res.string.send))
                                }
                            }
                        }
                    }
                } else {
                    // Portrait layout or Photo Crop mode
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text =
                            if (editorMode == EditorMode.PHOTO_CROP) {
                                stringResource(Res.string.image_editor_crop_title)
                            } else {
                                stringResource(Res.string.image_editor_title)
                            },
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )

                        // Resolution chips
                        Text(
                            stringResource(Res.string.image_editor_resolution, preset.name),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(MonochromeImageCodec.PRESETS) { p ->
                                FilterChip(
                                    selected = p.index == selectedPresetIndex,
                                    onClick = { selectedPresetIndex = p.index },
                                    label = { Text(p.name, style = MaterialTheme.typography.bodySmall) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))

                        // Theme chips (in DRAW mode)
                        if (editorMode == EditorMode.DRAW) {
                            Text(
                                stringResource(Res.string.image_editor_theme, currentTheme.name),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(4.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(MonochromeImageCodec.THEMES) { t ->
                                    FilterChip(
                                        selected = t.id == selectedThemeIndex,
                                        onClick = { selectedThemeIndex = t.id },
                                        leadingIcon = {
                                            Box(
                                                modifier =
                                                Modifier.size(14.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(t.backgroundColor))
                                                    .border(1.dp, Color(t.foregroundColor), CircleShape),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Box(
                                                    modifier =
                                                    Modifier.size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(t.foregroundColor)),
                                                )
                                            }
                                        },
                                        label = { Text(t.name, style = MaterialTheme.typography.bodySmall) },
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }

                        // Canvas
                        if (editorMode == EditorMode.DRAW) {
                            DrawCanvas(
                                preset = preset,
                                pixels = pixels,
                                theme = currentTheme,
                                showGrid = showGrid,
                                brushColorBlack = brushColorBlack,
                                trigger = trigger,
                                packetSize = packetSize,
                                onPixelChange = { trigger.value++ },
                            )
                            Spacer(Modifier.height(8.dp))

                            // Brush buttons
                            BrushModeButtons(
                                drawColor = drawColor,
                                eraseColor = eraseColor,
                                brushColorBlack = brushColorBlack,
                                onSelectDraw = { brushColorBlack = true },
                                onSelectErase = { brushColorBlack = false },
                            )
                            Spacer(Modifier.height(8.dp))

                            // Grid toggle
                            Row(
                                Modifier.fillMaxWidth().clickable { showGrid = !showGrid },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    stringResource(Res.string.image_editor_grid),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Switch(checked = showGrid, onCheckedChange = { showGrid = it })
                            }
                            Spacer(Modifier.height(8.dp))

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        for (i in pixels.indices) pixels[i] = !pixels[i]
                                        trigger.value++
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(Res.string.image_editor_invert))
                                }
                                OutlinedButton(
                                    onClick = {
                                        pixels.fill(false)
                                        trigger.value++
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(Res.string.image_editor_clear))
                                }
                                OutlinedButton(onClick = onImportPhoto, modifier = Modifier.weight(1f)) {
                                    Text(stringResource(Res.string.image_editor_import_photo))
                                }
                            }
                            Spacer(Modifier.height(16.dp))

                            // Action row
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                OutlinedButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                ) {
                                    Text(stringResource(Res.string.cancel))
                                }
                                Button(
                                    onClick = {
                                        onSendImage(
                                            MonochromeImageCodec.encode(
                                                pixels,
                                                selectedPresetIndex,
                                                themeIndex = selectedThemeIndex,
                                                showGrid = showGrid,
                                            ),
                                        )
                                    },
                                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                                ) {
                                    Text(stringResource(Res.string.send))
                                }
                            }
                        } else {
                            // PHOTO_CROP
                            val previewBytes =
                                remember(monoBitsPreview, selectedThemeIndex, showGrid) {
                                    MonochromeImageCodec.encode(
                                        monoBitsPreview.map { it }.toBooleanArray(),
                                        selectedPresetIndex,
                                        themeIndex = selectedThemeIndex,
                                        showGrid = showGrid,
                                    )
                                        .size
                                }
                            PhotoCropCanvas(
                                preset = preset,
                                monoBitsPreview = monoBitsPreview,
                                theme = currentTheme,
                                showGrid = showGrid,
                                packetSize = previewBytes,
                                onTransform = { pan, zoom, rotation, size ->
                                    val zf = 1f + (zoom - 1f) * 0.4f
                                    photoScale = (photoScale * zf).coerceIn(0.5f, 10f)
                                    photoRotation += rotation
                                    val screenCellW = size.width.toFloat() / preset.width
                                    val fitScaleX = importedWidth.toFloat() / preset.width
                                    val fitScaleY = importedHeight.toFloat() / preset.height
                                    val fitScale = kotlin.math.min(fitScaleX, fitScaleY)
                                    val cellSize = fitScale / photoScale
                                    val srcPanX = -pan.x * (cellSize / screenCellW)
                                    val srcPanY = -pan.y * (cellSize / screenCellW)
                                    photoOffsetX += srcPanX
                                    photoOffsetY += srcPanY
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(Res.string.image_editor_hint_pan_zoom),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))

                            // Invert
                            Row(
                                Modifier.fillMaxWidth().clickable { photoInvert = !photoInvert },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    stringResource(Res.string.image_editor_invert),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Switch(checked = photoInvert, onCheckedChange = { photoInvert = it })
                            }
                            Spacer(Modifier.height(8.dp))

                            // Sliders
                            Text(
                                stringResource(Res.string.image_editor_brightness, (brightness * 100).toInt()),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Slider(
                                value = brightness,
                                onValueChange = { brightness = it },
                                valueRange = -1f..1f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                stringResource(Res.string.image_editor_contrast, NumberFormatter.format(contrast, 1)),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Slider(
                                value = contrast,
                                onValueChange = { contrast = it },
                                valueRange = 0.1f..3f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                stringResource(Res.string.image_editor_dithering, (ditherAmount * 100).toInt()),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Slider(
                                value = ditherAmount,
                                onValueChange = { ditherAmount = it },
                                valueRange = 0f..1f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { editorMode = EditorMode.DRAW },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(Res.string.cancel))
                                }
                                Button(
                                    onClick = {
                                        val baked = monoBitsPreview
                                        for (i in pixels.indices) {
                                            pixels[i] = baked.getOrNull(i) ?: false
                                        }
                                        trigger.value++
                                        editorMode = EditorMode.DRAW
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(Res.string.image_editor_apply))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun contrastingGridColor(color: Color): Color {
    val lum = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
    return if (lum < 0.45f) {
        Color.White.copy(alpha = 0.35f)
    } else {
        Color.Black.copy(alpha = 0.35f)
    }
}

@Composable
private fun BrushModeButtons(
    drawColor: Color,
    eraseColor: Color,
    brushColorBlack: Boolean,
    onSelectDraw: () -> Unit,
    onSelectErase: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (brushColorBlack) {
            Button(onClick = {}, modifier = Modifier.weight(1f)) {
                Box(
                    modifier =
                    Modifier.size(16.dp)
                        .clip(CircleShape)
                        .background(drawColor)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.image_editor_draw))
            }
        } else {
            OutlinedButton(onClick = onSelectDraw, modifier = Modifier.weight(1f)) {
                Box(
                    modifier =
                    Modifier.size(16.dp)
                        .clip(CircleShape)
                        .background(drawColor)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.image_editor_draw))
            }
        }

        if (!brushColorBlack) {
            Button(onClick = {}, modifier = Modifier.weight(1f)) {
                Box(
                    modifier =
                    Modifier.size(16.dp)
                        .clip(CircleShape)
                        .background(eraseColor)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.image_editor_erase))
            }
        } else {
            OutlinedButton(onClick = onSelectErase, modifier = Modifier.weight(1f)) {
                Box(
                    modifier =
                    Modifier.size(16.dp)
                        .clip(CircleShape)
                        .background(eraseColor)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.image_editor_erase))
            }
        }
    }
}

@Composable
private fun DrawCanvas(
    preset: MonochromeResolutionPreset,
    pixels: BooleanArray,
    theme: MonochromeTheme,
    showGrid: Boolean,
    brushColorBlack: Boolean,
    trigger: androidx.compose.runtime.State<Int>,
    packetSize: Int,
    onPixelChange: () -> Unit,
) {
    val currentBrushColor by androidx.compose.runtime.rememberUpdatedState(brushColorBlack)
    fun updatePixel(offset: Offset, size: IntSize) {
        val cellW = size.width.toFloat() / preset.width
        val cellH = size.height.toFloat() / preset.height
        val gridX = (offset.x / cellW).toInt().coerceIn(0, preset.width - 1)
        val gridY = (offset.y / cellH).toInt().coerceIn(0, preset.height - 1)
        val idx = gridY * preset.width + gridX
        if (idx in pixels.indices) {
            pixels[idx] = currentBrushColor
            onPixelChange()
        }
    }

    val bgColor = Color(theme.backgroundColor)
    val inkColor = Color(theme.foregroundColor)
    val gridColor = Color(theme.gridColor)

    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
            Modifier.weight(1f)
                .aspectRatio(preset.width.toFloat() / preset.height.toFloat())
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                .background(bgColor)
                .pointerInput(preset) {
                    detectDragGestures(
                        onDragStart = { offset -> updatePixel(offset, this.size) },
                        onDrag = { change, _ -> updatePixel(change.position, this.size) },
                    )
                }
                .pointerInput(preset) { detectTapGestures { offset -> updatePixel(offset, this.size) } },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                @Suppress("UNUSED_EXPRESSION")
                trigger.value
                val cellW = size.width / preset.width
                val cellH = size.height / preset.height
                for (y in 0 until preset.height) {
                    for (x in 0 until preset.width) {
                        if (pixels[y * preset.width + x]) {
                            drawRect(
                                color = inkColor,
                                topLeft = Offset(x * cellW, y * cellH),
                                size = Size(cellW + 0.5f, cellH + 0.5f),
                            )
                        }
                    }
                }
                if (showGrid) {
                    val inkGridColor = contrastingGridColor(inkColor)
                    val bgGridColor = contrastingGridColor(bgColor)
                    for (y in 0 until preset.height) {
                        for (x in 0 until preset.width) {
                            val isDrawn = pixels[y * preset.width + x]
                            drawRect(
                                color = if (isDrawn) inkGridColor else bgGridColor,
                                topLeft = Offset(x * cellW, y * cellH),
                                size = Size(cellW, cellH),
                                style = Stroke(width = 1f),
                            )
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.width(48.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(text = "~", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text(
                    text = "$packetSize",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(Res.string.image_editor_bytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                )
            }
        }
    }
}

@Composable
private fun PhotoCropCanvas(
    preset: MonochromeResolutionPreset,
    monoBitsPreview: BooleanArray,
    theme: MonochromeTheme,
    showGrid: Boolean,
    packetSize: Int,
    onTransform: (pan: Offset, zoom: Float, rotation: Float, size: IntSize) -> Unit,
) {
    val bgColor = Color(theme.backgroundColor)
    val inkColor = Color(theme.foregroundColor)
    val gridColor = Color(theme.gridColor)

    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
            Modifier.weight(1f)
                .aspectRatio(preset.width.toFloat() / preset.height.toFloat())
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                .background(bgColor)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, rotation ->
                        onTransform(pan, zoom, rotation, this.size)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val cellW = size.width / preset.width
                val cellH = size.height / preset.height
                for (y in 0 until preset.height) {
                    for (x in 0 until preset.width) {
                        val idx = y * preset.width + x
                        if (idx < monoBitsPreview.size && monoBitsPreview[idx]) {
                            drawRect(
                                color = inkColor,
                                topLeft = Offset(x * cellW, y * cellH),
                                size = Size(cellW + 0.5f, cellH + 0.5f),
                            )
                        }
                    }
                }
                if (showGrid) {
                    val inkGridColor = contrastingGridColor(inkColor)
                    val bgGridColor = contrastingGridColor(bgColor)
                    for (y in 0 until preset.height) {
                        for (x in 0 until preset.width) {
                            val idx = y * preset.width + x
                            val isDrawn = idx < monoBitsPreview.size && monoBitsPreview[idx]
                            drawRect(
                                color = if (isDrawn) inkGridColor else bgGridColor,
                                topLeft = Offset(x * cellW, y * cellH),
                                size = Size(cellW, cellH),
                                style = Stroke(width = 1f),
                            )
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.width(48.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(text = "~", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text(
                    text = "$packetSize",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(Res.string.image_editor_bytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                )
            }
        }
    }
}
