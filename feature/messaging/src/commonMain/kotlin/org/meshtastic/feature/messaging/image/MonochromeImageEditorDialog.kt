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
    "LongMethod", "MagicNumber", "CyclomaticComplexMethod", "ComposableParamOrder",
    "TooGenericExceptionCaught", "MaxLineLength", "ReturnCount", "NestedBlockDepth",
)
package org.meshtastic.feature.messaging.image

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.min

private enum class EditorMode { DRAW, PHOTO_CROP }

/**
 * Unified monochrome image editor.
 *
 * Starts in [EditorMode.DRAW] (pixel-by-pixel drawing). Pressing "Импорт из фото" calls [onImportPhoto]
 * which should open a file picker; once the caller has [importedGrayValues], the editor switches to
 * [EditorMode.PHOTO_CROP] where the user can pan/zoom, then tap "Применить" to bake into the drawing grid.
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

    val packetSize: Int by remember(preset) {
        derivedStateOf {
            @Suppress("UNUSED_EXPRESSION") trigger.value
            val finalBits = BooleanArray(pixels.size) { if (invert) pixels[it] else !pixels[it] }
            MonochromeImageCodec.encode(finalBits, selectedPresetIndex).size
        }
    }

    // PHOTO_CROP state
    var photoScale by remember { mutableFloatStateOf(1f) }
    var photoOffset by remember { mutableStateOf(Offset.Zero) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var ditherAmount by remember { mutableFloatStateOf(1f) }

    androidx.compose.runtime.LaunchedEffect(selectedPresetIndex, importedGrayValues) {
        photoScale = 1f
        photoOffset = Offset.Zero
    }

    val sampledGrayValues = remember(importedGrayValues, selectedPresetIndex, importedWidth, importedHeight, photoScale, photoOffset) {
        val src = importedGrayValues ?: return@remember FloatArray(preset.totalPixels)
        val result = FloatArray(preset.totalPixels)
        // fit-scale: map the entire source image into the preset grid
        val scaleX = importedWidth.toFloat() / preset.width
        val scaleY = importedHeight.toFloat() / preset.height
        val fitScale = kotlin.math.max(scaleX, scaleY) // cover-fit
        val totalScale = fitScale / photoScale // user zoom applied
        val cx = importedWidth / 2f
        val cy = importedHeight / 2f
        for (y in 0 until preset.height) for (x in 0 until preset.width) {
            val sx = cx + (x - preset.width / 2f) * totalScale - photoOffset.x * fitScale
            val sy = cy + (y - preset.height / 2f) * totalScale - photoOffset.y * fitScale
            val ix = sx.toInt().coerceIn(0, importedWidth - 1)
            val iy = sy.toInt().coerceIn(0, importedHeight - 1)
            result[y * preset.width + x] = src.getOrElse(iy * importedWidth + ix) { 0f }
        }
        result
    }

    val monoBitsPreview by remember(sampledGrayValues, brightness, contrast, ditherAmount, invert) {
        derivedStateOf {
            MonochromeImageCodec.processToMonochrome(
                grayValues = sampledGrayValues,
                width = preset.width, height = preset.height,
                brightness = brightness, contrast = contrast,
                ditherAmount = ditherAmount, invert = invert,
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
                    text = if (editorMode == EditorMode.PHOTO_CROP) "Выбор фрагмента" else "Редактор изображения",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                // Resolution chips
                Text("Разрешение (${preset.name}):", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
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
                    DrawCanvas(preset = preset, pixels = pixels, invert = invert, brushColorBlack = brushColorBlack, trigger = trigger, onPixelChanged = { trigger.value++ })
                    // Packet size
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text("~$packetSize байт", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
                    Row(Modifier.fillMaxWidth().clickable { invert = !invert }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Негатив (инверсия)", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = invert, onCheckedChange = { invert = it })
                    }
                    Spacer(Modifier.height(8.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pixels.fill(false); trigger.value++ }, modifier = Modifier.weight(1f)) { Text("Очистить") }
                        OutlinedButton(onClick = onImportPhoto, modifier = Modifier.weight(1f)) { Text("Импорт из фото") }
                    }
                    Spacer(Modifier.height(16.dp))

                    // Action row
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).padding(end = 8.dp)) { Text("Отмена") }
                        Button(
                            onClick = {
                                val finalBits = BooleanArray(pixels.size) { if (invert) pixels[it] else !pixels[it] }
                                onSendImage(MonochromeImageCodec.encode(finalBits, selectedPresetIndex))
                            },
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        ) { Text("Отправить") }
                    }
                } else {
                    // PHOTO_CROP
                    PhotoCropCanvas(
                        preset = preset,
                        monoBitsPreview = monoBitsPreview,
                        onTransform = { pan, zoom, size ->
                            val zf = 1f + (zoom - 1f) * 0.4f
                            photoScale = (photoScale * zf).coerceIn(0.5f, 10f)
                            val cellW = size.width.toFloat() / preset.width
                            photoOffset += Offset(pan.x / cellW, pan.y / cellW) / photoScale
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Масштабируйте и перемещайте, затем нажмите «Применить»",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))

                    // Invert
                    Row(Modifier.fillMaxWidth().clickable { invert = !invert }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Негатив (инверсия)", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = invert, onCheckedChange = { invert = it })
                    }
                    Spacer(Modifier.height(8.dp))

                    // Sliders
                    Text("Яркость: ${(brightness * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                    Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -1f..1f, modifier = Modifier.fillMaxWidth())
                    Text("Контрастность: ${"%.1f".format(contrast)}x", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                    Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0.1f..3f, modifier = Modifier.fillMaxWidth())
                    Text("Дизеринг: ${(ditherAmount * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                    Slider(value = ditherAmount, onValueChange = { ditherAmount = it }, valueRange = 0f..1f, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { editorMode = EditorMode.DRAW }, modifier = Modifier.weight(1f)) { Text("Отмена") }
                        Button(
                            onClick = {
                                val baked = monoBitsPreview
                                // monoBits[i]=true means white pixel.
                                // pixels[i]=true means "ink". Ink is black when invert=false.
                                // So: pixels[i]=true when the result is black (monoBits=false, invert=false)
                                //                     or when result is white (monoBits=true, invert=true)
                                for (i in pixels.indices) {
                                    pixels[i] = if (invert) baked.getOrElse(i) { false } else !baked.getOrElse(i) { true }
                                }
                                trigger.value++
                                editorMode = EditorMode.DRAW
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Применить") }
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
    onPixelChanged: () -> Unit,
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
            onPixelChanged()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(preset.width.toFloat() / preset.height.toFloat())
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
            .background(Color.White)
            .pointerInput(preset) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var isDrawing = true
                    updatePixel(down.position, size)
                    down.consume()
                    
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1) {
                            isDrawing = false
                        }
                        if (isDrawing && event.changes.size == 1) {
                            val change = event.changes.first()
                            if (change.pressed) {
                                updatePixel(change.position, size)
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            @Suppress("UNUSED_EXPRESSION") trigger.value
            val inkColor = if (invert) Color.White else Color.Black
            val cellW = size.width / preset.width
            val cellH = size.height / preset.height
            for (y in 0 until preset.height) for (x in 0 until preset.width) {
                if (pixels[y * preset.width + x]) {
                    drawRect(color = inkColor, topLeft = Offset(x * cellW, y * cellH), size = Size(cellW, cellH))
                }
            }
        }
    }
}

@Composable
private fun PhotoCropCanvas(
    preset: MonochromeResolutionPreset,
    monoBitsPreview: BooleanArray,
    onTransform: (pan: Offset, zoom: Float, size: IntSize) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .aspectRatio(preset.width.toFloat() / preset.height.toFloat())
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var previousDistance = 0f
                    var previousCentroid = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val changes = event.changes
                        if (changes.size == 1) {
                            val change = changes.first()
                            if (change.pressed) {
                                val pan = change.position - change.previousPosition
                                onTransform(pan, 1f, size)
                                change.consume()
                            }
                            previousDistance = 0f
                        } else if (changes.size == 2) {
                            val pos0 = changes[0].position
                            val pos1 = changes[1].position
                            val distance = (pos0 - pos1).getDistance()
                            val centroid = (pos0 + pos1) / 2f
                            if (previousDistance > 0f) {
                                val zoom = distance / previousDistance
                                val pan = centroid - previousCentroid
                                onTransform(pan, zoom, size)
                            }
                            previousDistance = distance
                            previousCentroid = centroid
                        } else {
                            previousDistance = 0f
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val cellW = size.width / preset.width
            val cellH = size.height / preset.height
            for (y in 0 until preset.height) for (x in 0 until preset.width) {
                val idx = y * preset.width + x
                if (idx < monoBitsPreview.size && monoBitsPreview[idx]) {
                    drawRect(color = Color.White, topLeft = Offset(x * cellW, y * cellH), size = Size(cellW + 0.5f, cellH + 0.5f))
                }
            }
        }
    }
}