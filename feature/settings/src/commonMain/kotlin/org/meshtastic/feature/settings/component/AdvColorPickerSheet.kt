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
@file:Suppress("MagicNumber", "LongMethod")

package org.meshtastic.feature.settings.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.adv_appearance_colors_auto
import org.meshtastic.core.resources.adv_appearance_colors_custom
import org.meshtastic.core.resources.adv_appearance_hex_code
import org.meshtastic.core.resources.adv_appearance_hue
import org.meshtastic.core.resources.adv_appearance_lightness
import org.meshtastic.core.resources.adv_appearance_saturation
import org.meshtastic.core.resources.cancel
import org.meshtastic.core.resources.save
import org.meshtastic.core.ui.theme.hslToColor
import org.meshtastic.core.ui.theme.safeOnColor
import org.meshtastic.core.ui.theme.toHsl

private fun Color.toHexRgb(): String {
    val r = (red * 255f).toInt().coerceIn(0, 255)
    val g = (green * 255f).toInt().coerceIn(0, 255)
    val b = (blue * 255f).toInt().coerceIn(0, 255)
    return "#${r.toString(
        16,
    ).padStart(
        2,
        '0',
    ).uppercase()}${g.toString(16).padStart(2, '0').uppercase()}${b.toString(16).padStart(2, '0').uppercase()}"
}

private fun Color.toRgbInt(): Int {
    val r = (red * 255f).toInt().coerceIn(0, 255)
    val g = (green * 255f).toInt().coerceIn(0, 255)
    val b = (blue * 255f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

private fun parseHexToColor(input: String): Color? {
    val clean = input.removePrefix("#").trim()
    if (clean.length != 6) return null
    return try {
        val r = clean.substring(0, 2).toInt(16)
        val g = clean.substring(2, 4).toInt(16)
        val b = clean.substring(4, 6).toInt(16)
        Color(r / 255f, g / 255f, b / 255f, 1f)
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "CyclomaticComplexMethod", "MagicNumber")
@Composable
fun AdvColorPickerSheet(
    title: String,
    initialArgb: Int?,
    defaultAutoColor: Color,
    onConfirm: (Int?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var isAuto by remember(initialArgb) { mutableStateOf(initialArgb == null) }

    val startColor = initialArgb?.let { Color(it) } ?: defaultAutoColor
    val startHsl = remember(startColor) { startColor.toHsl() }

    var hue by remember { mutableFloatStateOf(startHsl.hue) }
    var saturation by remember { mutableFloatStateOf(startHsl.saturation) }
    var lightness by remember { mutableFloatStateOf(startHsl.lightness) }

    var hexText by remember { mutableStateOf(startColor.toHexRgb()) }

    fun updateFromHsl(newH: Float, newS: Float, newL: Float) {
        hue = newH
        saturation = newS
        lightness = newL
        val c = hslToColor(newH, newS, newL)
        hexText = c.toHexRgb()
    }

    val currentColor = remember(hue, saturation, lightness) { hslToColor(hue, saturation, lightness) }

    val activeDisplayColor = if (isAuto) defaultAutoColor else currentColor

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(
            modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            // Auto vs Custom selection
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { isAuto = true }.padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = isAuto, onClick = { isAuto = true })
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = stringResource(Res.string.adv_appearance_colors_auto))
                }

                Row(
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { isAuto = false }.padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = !isAuto, onClick = { isAuto = false })
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = stringResource(Res.string.adv_appearance_colors_custom))
                }
            }

            // Preview card
            Box(
                modifier =
                Modifier.fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(activeDisplayColor)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text =
                    if (isAuto) {
                        "${stringResource(Res.string.adv_appearance_colors_auto)} (${defaultAutoColor.toHexRgb()})"
                    } else {
                        activeDisplayColor.toHexRgb()
                    },
                    color = safeOnColor(activeDisplayColor),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (!isAuto) {
                // Sliders section
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Hue Slider
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = stringResource(Res.string.adv_appearance_hue),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                text = "${hue.toInt()}°",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Box(
                            modifier =
                            Modifier.fillMaxWidth()
                                .height(12.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            Color.Red,
                                            Color.Yellow,
                                            Color.Green,
                                            Color.Cyan,
                                            Color.Blue,
                                            Color.Magenta,
                                            Color.Red,
                                        ),
                                    ),
                                ),
                        )
                        Slider(
                            value = hue,
                            onValueChange = { updateFromHsl(it, saturation, lightness) },
                            valueRange = 0f..360f,
                            colors =
                            SliderDefaults.colors(
                                thumbColor = currentColor,
                                activeTrackColor = Color.Transparent,
                                inactiveTrackColor = Color.Transparent,
                            ),
                        )
                    }

                    // Saturation Slider
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = stringResource(Res.string.adv_appearance_saturation),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                text = "${(saturation * 100f).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Box(
                            modifier =
                            Modifier.fillMaxWidth()
                                .height(12.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(hslToColor(hue, 0f, lightness), hslToColor(hue, 1f, lightness)),
                                    ),
                                ),
                        )
                        Slider(
                            value = saturation,
                            onValueChange = { updateFromHsl(hue, it, lightness) },
                            valueRange = 0f..1f,
                            colors =
                            SliderDefaults.colors(
                                thumbColor = currentColor,
                                activeTrackColor = Color.Transparent,
                                inactiveTrackColor = Color.Transparent,
                            ),
                        )
                    }

                    // Lightness Slider
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = stringResource(Res.string.adv_appearance_lightness),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                text = "${(lightness * 100f).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Box(
                            modifier =
                            Modifier.fillMaxWidth()
                                .height(12.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color.Black, hslToColor(hue, saturation, 0.5f), Color.White),
                                    ),
                                ),
                        )
                        Slider(
                            value = lightness,
                            onValueChange = { updateFromHsl(hue, saturation, it) },
                            valueRange = 0f..1f,
                            colors =
                            SliderDefaults.colors(
                                thumbColor = currentColor,
                                activeTrackColor = Color.Transparent,
                                inactiveTrackColor = Color.Transparent,
                            ),
                        )
                    }

                    // Hex code input
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { newText ->
                            hexText = newText
                            val parsed = parseHexToColor(newText)
                            if (parsed != null) {
                                val hsl = parsed.toHsl()
                                hue = hsl.hue
                                saturation = hsl.saturation
                                lightness = hsl.lightness
                            }
                        },
                        label = { Text(stringResource(Res.string.adv_appearance_hex_code)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            Box(
                                modifier =
                                Modifier.size(24.dp)
                                    .clip(CircleShape)
                                    .background(currentColor)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                            )
                        },
                    )
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onDismiss) { Text(text = stringResource(Res.string.cancel)) }
                Button(
                    onClick = {
                        val result = if (isAuto) null else currentColor.toRgbInt()
                        onConfirm(result)
                        onDismiss()
                    },
                ) {
                    Text(text = stringResource(Res.string.save))
                }
            }
        }
    }
}
