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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.min

private enum class EditorMode {
    DRAW,
    PHOTO_CROP,
}

/**
 * Unified monochrome image editor.
 *
 * Starts in [EditorMode.DRAW] (pixel-by-pixel drawing). Pressing "Импорт из фото" calls [onImportPhoto] which should
 * open a file picker; once the caller has [importedGrayValues], the editor switches to [EditorMode.PHOTO_CROP] where
 * the user can pan/zoom, then tap "Применить" to bake into the drawing grid.
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
    var selectedPresetIndex by remember { mutableIntStateOf(0) }
    var invert by remember { mutableStateOf(false) }
    var editorMode by remember { mutableStateOf(EditorMode.DRAW) }

    // Auto-switch to crop when a photo arrives
    androidx.compose.runtime.LaunchedEffect(importedGrayValues) {
        if (importedGrayValues != null) {
            editorMode = EditorMode.PHOTO_CROP
        }
    }

    val preset = MonochromeImageCodec.getPreset(selectedPresetIndex)

    // DRAW state
    val pixels = remember(preset) { BooleanArray(preset.width * preset.height) }
    val trigger = remember { mutableIntStateOf(0) }
    var brushColorBlack by remember { mutableStateOf(true) }

    val packetSize: Int by
        remember(preset) {
            derivedStateOf {
                @Suppress("UNUSED_EXPRESSION")
                trigger.value
                val finalBits = BooleanArray(pixels.size) { if (invert) pixels[it] else !pixels[it] }
                MonochromeImageCodec.encode(finalBits, selectedPresetIndex).size
            }
        }

    // PHOTO_CROP state
    var photoScale by remember { mutableFloatStateOf(1f) }
    var photoOffset by remember { mutableStateOf(Offset.Zero) }
    var photoRotation by remember { mutableFloatStateOf(0f) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var ditherAmount by remember { mutableFloatStateOf(1f) }

    androidx.compose.runtime.LaunchedEffect(selectedPresetIndex, importedGrayValues) {
        photoScale = 1f
        photoOffset = Offset.Zero
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
            val cosA = kotlin.math.cos(Math.toRadians(photoRotation.toDouble())).toFloat()
            val sinA = kotlin.math.sin(Math.toRadians(photoRotation.toDouble())).toFloat()
            val cx = importedWidth / 2f + photoOffset.x
            val cy = importedHeight / 2f + photoOffset.y
            for (y in 0 until preset.height) {
                for (x in 0 until preset.width) {
                    // Offset from preset center in preset cells, then scale to source pixels
                    val dx = (x - preset.width / 2f) * cellSize
                    val dy = (y - preset.height / 2f) * cellSize
                    // Apply rotation around the source center
                    val sx = cx + cosA * dx + sinA * dy
                    val sy = cy - sinA * dx + cosA * dy
                    val ix = sx.toInt()
                    val iy = sy.toInt()
                    // Out-of-bounds → neutral gray (not black/white stretch)
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
        remember(sampledGrayValues, brightness, contrast, ditherAmount, invert) {
            derivedStateOf {
                MonochromeImageCodec.processToMonochrome(
                    grayValues = sampledGrayValues,
                    width = preset.width,
                    height = preset.height,
                    brightness = brightness,
                    contrast = contrast,
                    ditherAmount = ditherAmount,
                    invert = invert,
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
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text =
                    if (editorMode == EditorMode.PHOTO_CROP) {
                        "Выбор фрагмента"
                    } else {
                        "Редактор изображения"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                // Resolution chips
                Text(
                    "Разрешение (${preset.name}):",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    items(MonochromeImageCodec.PRESETS) { p ->
                        FilterChip(
                            selected = p.index == selectedPresetIndex,
                            onClick = { selectedPresetIndex = p.index },
                            label = { Text(p.name, style = MaterialTheme.typography.bodySmall) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                // Canvas
                if (editorMode == EditorMode.DRAW) {
                    DrawCanvas(
                        preset = preset,
                        pixels = pixels,
                        invert = invert,
                        brushColorBlack = brushColorBlack,
                        trigger = trigger,
                        packetSize = packetSize,
                        onPixelChange = { trigger.value++ },
                    )
                    Spacer(Modifier.height(8.dp))

                    // Brush buttons
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Button(onClick = { brushColorBlack = true }, enabled = !brushColorBlack) {
                            Text(if (invert) "Рисовать (Белый)" else "Рисовать (Чёрный)")
                        }
                        Button(onClick = { brushColorBlack = false }, enabled = brushColorBlack) {
                            Text(if (invert) "Ластик (Чёрный)" else "Ластик (Белый)")
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    // Invert
                    Row(
                        Modifier.fillMaxWidth().clickable { invert = !invert },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Негатив (инверсия)", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = invert, onCheckedChange = { invert = it })
                    }
                    Spacer(Modifier.height(8.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                pixels.fill(false)
                                trigger.value++
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Очистить")
                        }
                        OutlinedButton(onClick = onImportPhoto, modifier = Modifier.weight(1f)) {
                            Text("Импорт из фото")
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    // Action row
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Отмена")
                        }
                        Button(
                            onClick = {
                                val finalBits = BooleanArray(pixels.size) { if (invert) pixels[it] else !pixels[it] }
                                onSendImage(MonochromeImageCodec.encode(finalBits, selectedPresetIndex))
                            },
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        ) {
                            Text("Отправить")
                        }
                    }
                } else {
                    // PHOTO_CROP
                    val previewBytes =
                        remember(monoBitsPreview) {
                            MonochromeImageCodec.encode(
                                monoBitsPreview.map { it }.toBooleanArray(),
                                selectedPresetIndex,
                            )
                                .size
                        }
                    PhotoCropCanvas(
                        preset = preset,
                        monoBitsPreview = monoBitsPreview,
                        packetSize = previewBytes,
                        onTransform = { pan, zoom, rotation, size ->
                            val zf = 1f + (zoom - 1f) * 0.4f
                            photoScale = (photoScale * zf).coerceIn(0.5f, 10f)
                            photoRotation += rotation
                            // Screen pixels per preset cell
                            val screenCellW = size.width.toFloat() / preset.width
                            // Source pixels per preset cell (at current zoom)
                            val fitScaleX = importedWidth.toFloat() / preset.width
                            val fitScaleY = importedHeight.toFloat() / preset.height
                            val fitScale = kotlin.math.min(fitScaleX, fitScaleY)
                            val cellSize = fitScale / photoScale
                            // Convert: screen pan → source pan. Negate because dragging right moves offset right.
                            val srcPanX = -pan.x * (cellSize / screenCellW)
                            val srcPanY = -pan.y * (cellSize / screenCellW)
                            photoOffset += Offset(srcPanX, srcPanY)
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Масштабируйте и перемещайте, " + "затем нажмите «Применить»",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))

                    // Invert
                    Row(
                        Modifier.fillMaxWidth().clickable { invert = !invert },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Негатив (инверсия)", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = invert, onCheckedChange = { invert = it })
                    }
                    Spacer(Modifier.height(8.dp))

                    // Sliders
                    Text(
                        "Яркость: ${(brightness * 100).toInt()}%",
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
                        "Контрастность: ${"%.1f".format(contrast)}x",
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
                        "Дизеринг: ${(ditherAmount * 100).toInt()}%",
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
                        OutlinedButton(onClick = { editorMode = EditorMode.DRAW }, modifier = Modifier.weight(1f)) {
                            Text("Отмена")
                        }
                        Button(
                            onClick = {
                                val baked = monoBitsPreview
                                // monoBits[i]=true means white pixel.
                                // pixels[i]=true means "ink". Ink is black when invert=false.
                                // So: pixels[i]=true when the result is black (monoBits=false, invert=false)
                                //                     or when result is white (monoBits=true, invert=true)
                                for (i in pixels.indices) {
                                    pixels[i] =
                                        if (invert) baked.getOrElse(i) { false } else !baked.getOrElse(i) { true }
                                }
                                trigger.value++
                                editorMode = EditorMode.DRAW
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Применить")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawCanvas(
    preset: MonochromeResolutionPreset,
    pixels: BooleanArray,
    invert: Boolean,
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
                .background(if (invert) Color.Black else Color.White)
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
                val inkColor = if (invert) Color.White else Color.Black
                val cellW = size.width / preset.width
                val cellH = size.height / preset.height
                for (y in 0 until preset.height) {
                    for (x in 0 until preset.width) {
                        if (pixels[y * preset.width + x]) {
                            drawRect(
                                color = inkColor,
                                topLeft = Offset(x * cellW, y * cellH),
                                size = Size(cellW, cellH),
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
                Text(text = "байт", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun PhotoCropCanvas(
    preset: MonochromeResolutionPreset,
    monoBitsPreview: BooleanArray,
    packetSize: Int,
    onTransform: (pan: Offset, zoom: Float, rotation: Float, size: IntSize) -> Unit,
) {
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
                .background(Color.Black)
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
                                color = Color.White,
                                topLeft = Offset(x * cellW, y * cellH),
                                size = Size(cellW + 0.5f, cellH + 0.5f),
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
                Text(text = "байт", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
    }
}
