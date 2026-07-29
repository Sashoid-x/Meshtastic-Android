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
package org.meshtastic.feature.messaging.image

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Suppress("LongMethod")
@Composable
fun MonochromeImageEditorDialog(
    rawImageGrayValues: FloatArray?, // normalized 0..1 grayscale values or synthesized pattern
    imageWidth: Int = 100,
    imageHeight: Int = 100,
    onDismiss: () -> Unit,
    onSendImage: (ByteArray) -> Unit,
) {
    var selectedPresetIndex by remember { mutableIntStateOf(0) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }

    val preset = MonochromeImageCodec.getPreset(selectedPresetIndex)

    // Sample or crop original image into preset resolution grid
    val sampledGrayValues =
        remember(rawImageGrayValues, selectedPresetIndex, imageWidth, imageHeight) {
            val targetCount = preset.totalPixels
            val result = FloatArray(targetCount)
            if (rawImageGrayValues != null && rawImageGrayValues.isNotEmpty()) {
                for (y in 0 until preset.height) {
                    for (x in 0 until preset.width) {
                        val srcX = (x * imageWidth / preset.width).coerceIn(0, imageWidth - 1)
                        val srcY = (y * imageHeight / preset.height).coerceIn(0, imageHeight - 1)
                        val srcIdx = srcY * imageWidth + srcX
                        result[y * preset.width + x] = rawImageGrayValues.getOrElse(srcIdx) { 0.5f }
                    }
                }
            } else {
                // Default placeholder gradient/circle if raw pixels not parsed
                for (y in 0 until preset.height) {
                    for (x in 0 until preset.width) {
                        val dx = (x - preset.width / 2f) / (preset.width / 2f)
                        val dy = (y - preset.height / 2f) / (preset.height / 2f)
                        val dist = dx * dx + dy * dy
                        result[y * preset.width + x] = if (dist <= 0.6f) 0.9f else 0.1f
                    }
                }
            }
            result
        }

    val monoBits by
        remember(sampledGrayValues, brightness, contrast) {
            derivedStateOf {
                MonochromeImageCodec.processToMonochrome(
                    sampledGrayValues,
                    brightness = brightness,
                    contrast = contrast,
                )
            }
        }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Редактор монохромного изображения",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Resolution Preset selector
                Text(
                    text = "Вариант разрешения (${preset.name}):",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    items(MonochromeImageCodec.PRESETS) { p ->
                        FilterChip(
                            selected = p.index == selectedPresetIndex,
                            onClick = { selectedPresetIndex = p.index },
                            label = { Text(p.name, style = MaterialTheme.typography.bodySmall) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // Monochrome Image Canvas Preview
                Box(
                    modifier =
                    Modifier.fillMaxWidth(0.85f)
                        .aspectRatio(preset.aspectRatio.coerceAtLeast(0.5f).coerceAtMost(2.5f))
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val cellW = size.width / preset.width
                        val cellH = size.height / preset.height
                        for (y in 0 until preset.height) {
                            for (x in 0 until preset.width) {
                                val idx = y * preset.width + x
                                if (idx < monoBits.size && monoBits[idx]) {
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
                Spacer(modifier = Modifier.height(16.dp))

                // Brightness slider
                Text(
                    text = "Яркость: ${(brightness * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                Slider(
                    value = brightness,
                    onValueChange = { brightness = it },
                    valueRange = -1f..1f,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Contrast slider
                Text(
                    text = "Контрастность: ${"%.1f".format(contrast)}x",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                Slider(
                    value = contrast,
                    onValueChange = { contrast = it },
                    valueRange = 0.1f..3f,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onDismiss) { Text("Отмена") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val encoded = MonochromeImageCodec.encode(monoBits, selectedPresetIndex)
                            onSendImage(encoded)
                        },
                    ) {
                        Text("Отправить")
                    }
                }
            }
        }
    }
}
