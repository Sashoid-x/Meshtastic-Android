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
package org.meshtastic.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.model.AdvThemeColors
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.adv_appearance_base_color
import org.meshtastic.core.resources.adv_appearance_bubble_padding
import org.meshtastic.core.resources.adv_appearance_bubble_spacing
import org.meshtastic.core.resources.adv_appearance_colors_hint
import org.meshtastic.core.resources.adv_appearance_colors_reset
import org.meshtastic.core.resources.adv_appearance_colors_section
import org.meshtastic.core.resources.adv_appearance_dark_base
import org.meshtastic.core.resources.adv_appearance_font_scale
import org.meshtastic.core.resources.adv_appearance_preview_sample_received
import org.meshtastic.core.resources.adv_appearance_preview_sample_sent
import org.meshtastic.core.resources.adv_appearance_preview_title
import org.meshtastic.core.resources.adv_appearance_primary_color
import org.meshtastic.core.resources.adv_appearance_reaction_spacing
import org.meshtastic.core.resources.adv_appearance_secondary_color
import org.meshtastic.core.resources.adv_appearance_settings_title
import org.meshtastic.core.resources.adv_appearance_spacing_section
import org.meshtastic.core.resources.adv_appearance_tertiary_color
import org.meshtastic.core.resources.clear
import org.meshtastic.core.ui.component.MainAppBar
import org.meshtastic.core.ui.component.SwitchListItem
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.Send
import org.meshtastic.core.ui.theme.AdvColorSchemeBuilder
import org.meshtastic.core.ui.theme.MODE_ADV_THEME
import org.meshtastic.core.ui.theme.hslToColor
import org.meshtastic.core.ui.theme.primaryLight
import org.meshtastic.core.ui.theme.toHsl
import org.meshtastic.feature.settings.component.AdvColorPickerRow
import org.meshtastic.feature.settings.component.AdvColorPickerSheet
import org.meshtastic.feature.settings.component.ExpressiveSection
import kotlin.math.roundToInt

private const val DEFAULT_BUBBLE_SPACING = 8
private const val DEFAULT_BUBBLE_PADDING = 8
private const val DEFAULT_FONT_SCALE = 1.0f
private const val DEFAULT_REACTION_SPACING = 4

private enum class ColorSlot {
    PRIMARY,
    SECONDARY,
    TERTIARY,
    BASE,
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Suppress("LongMethod", "CyclomaticComplexMethod", "MagicNumber")
@Composable
fun AppearanceSettingsScreen(
    settingsViewModel: SettingsViewModel,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bubbleSpacing by settingsViewModel.messageBubbleSpacing.collectAsStateWithLifecycle()
    val bubblePadding by settingsViewModel.messageBubblePadding.collectAsStateWithLifecycle()
    val fontScale by settingsViewModel.messageFontSizeScale.collectAsStateWithLifecycle()
    val reactionSpacing by settingsViewModel.reactionChipSpacing.collectAsStateWithLifecycle()
    val currentTheme by settingsViewModel.theme.collectAsStateWithLifecycle()
    val advColorsJson by settingsViewModel.advThemeColorsJson.collectAsStateWithLifecycle()

    val advColors = remember(advColorsJson) { AdvThemeColors.fromJson(advColorsJson) }
    var editingSlot by remember { mutableStateOf<ColorSlot?>(null) }

    val previewScheme = remember(advColors) { AdvColorSchemeBuilder.build(advColors) }

    val primarySeed = advColors.primaryArgb?.let { Color(it) } ?: primaryLight
    val primaryHsl = remember(primarySeed) { primarySeed.toHsl() }
    val defaultSecondary =
        remember(primaryHsl) {
            hslToColor(
                hue = (primaryHsl.hue + 40f) % 360f,
                saturation = (primaryHsl.saturation * 0.7f).coerceIn(0.15f, 0.6f),
                lightness = primaryHsl.lightness,
            )
        }
    val defaultTertiary =
        remember(primaryHsl) {
            hslToColor(
                hue = (primaryHsl.hue + 80f) % 360f,
                saturation = (primaryHsl.saturation * 0.8f).coerceIn(0.2f, 0.7f),
                lightness = primaryHsl.lightness,
            )
        }
    val defaultBase =
        remember(primaryHsl, advColors.darkBase) {
            if (advColors.darkBase) {
                hslToColor(primaryHsl.hue, 0.05f, 0.08f)
            } else {
                hslToColor(primaryHsl.hue, 0.04f, 0.97f)
            }
        }

    val effectivePrimary = primarySeed
    val effectiveSecondary = advColors.secondaryArgb?.let { Color(it) } ?: defaultSecondary
    val effectiveTertiary = advColors.tertiaryArgb?.let { Color(it) } ?: defaultTertiary
    val effectiveBase = advColors.baseArgb?.let { Color(it) } ?: defaultBase

    Scaffold(
        modifier = modifier,
        topBar = {
            MainAppBar(
                title = stringResource(Res.string.adv_appearance_settings_title),
                canNavigateUp = true,
                onNavigateUp = onNavigateUp,
                ourNode = null,
                showNodeChip = false,
                actions = {},
                onClickChip = {},
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
            Modifier.fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Live Chat Preview Section (rendered in previewScheme)
            ExpressiveSection(title = stringResource(Res.string.adv_appearance_preview_title)) {
                MaterialExpressiveTheme(colorScheme = previewScheme) {
                    Card(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp),
                        border = CardDefaults.outlinedCardBorder(),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(bubbleSpacing.dp),
                        ) {
                            // Received message
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                                Column(
                                    modifier =
                                    Modifier.clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(horizontal = (bubblePadding + 4).dp, vertical = bubblePadding.dp),
                                ) {
                                    Text(
                                        text = "Alice",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = (12 * fontScale).sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(Res.string.adv_appearance_preview_sample_received),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = (14 * fontScale).sp,
                                    )
                                }
                            }

                            // Reactions under received message
                            Row(
                                modifier = Modifier.padding(start = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(reactionSpacing.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = CardDefaults.outlinedCardBorder(),
                                ) {
                                    Text(
                                        text = "👍 2",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        fontSize = (12 * fontScale).sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = CardDefaults.outlinedCardBorder(),
                                ) {
                                    Text(
                                        text = "❤️ 1",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        fontSize = (12 * fontScale).sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }

                            // Sent message
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Column(
                                    modifier =
                                    Modifier.clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .padding(horizontal = (bubblePadding + 4).dp, vertical = bubblePadding.dp),
                                ) {
                                    Text(
                                        text = stringResource(Res.string.adv_appearance_preview_sample_sent),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontSize = (14 * fontScale).sp,
                                    )
                                }
                            }

                            // Fake chat input bar preview
                            Row(
                                modifier =
                                Modifier.fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "Message…",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    fontSize = (14 * fontScale).sp,
                                )
                                Box(
                                    modifier =
                                    Modifier.size(32.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = MeshtasticIcons.Send,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ADV Theme Colors Section
            ExpressiveSection(title = stringResource(Res.string.adv_appearance_colors_section)) {
                if (currentTheme != MODE_ADV_THEME) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.adv_appearance_colors_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                AdvColorPickerRow(
                    text = stringResource(Res.string.adv_appearance_primary_color),
                    effectiveColor = effectivePrimary,
                    isAuto = advColors.primaryArgb == null,
                    onClick = { editingSlot = ColorSlot.PRIMARY },
                )

                AdvColorPickerRow(
                    text = stringResource(Res.string.adv_appearance_secondary_color),
                    effectiveColor = effectiveSecondary,
                    isAuto = advColors.secondaryArgb == null,
                    onClick = { editingSlot = ColorSlot.SECONDARY },
                )

                AdvColorPickerRow(
                    text = stringResource(Res.string.adv_appearance_tertiary_color),
                    effectiveColor = effectiveTertiary,
                    isAuto = advColors.tertiaryArgb == null,
                    onClick = { editingSlot = ColorSlot.TERTIARY },
                )

                AdvColorPickerRow(
                    text = stringResource(Res.string.adv_appearance_base_color),
                    effectiveColor = effectiveBase,
                    isAuto = advColors.baseArgb == null,
                    onClick = { editingSlot = ColorSlot.BASE },
                )

                SwitchListItem(
                    checked = advColors.darkBase,
                    text = stringResource(Res.string.adv_appearance_dark_base),
                    onClick = { settingsViewModel.setAdvThemeColors(advColors.copy(darkBase = !advColors.darkBase)) },
                )

                OutlinedButton(
                    onClick = { settingsViewModel.resetAdvThemeColors() },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Text(text = stringResource(Res.string.adv_appearance_colors_reset))
                }
            }

            // Spacing and Typography Controls
            ExpressiveSection(title = stringResource(Res.string.adv_appearance_spacing_section)) {
                // Bubble Spacing
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        text = stringResource(Res.string.adv_appearance_bubble_spacing, bubbleSpacing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = bubbleSpacing.toFloat(),
                        onValueChange = { settingsViewModel.setMessageBubbleSpacing(it.roundToInt()) },
                        valueRange = 0f..20f,
                        steps = 19,
                    )
                }

                // Bubble Internal Padding
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        text = stringResource(Res.string.adv_appearance_bubble_padding, bubblePadding),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = bubblePadding.toFloat(),
                        onValueChange = { settingsViewModel.setMessageBubblePadding(it.roundToInt()) },
                        valueRange = 4f..24f,
                        steps = 19,
                    )
                }

                // Font Size Scale
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    val formattedScale = ((fontScale * 100).roundToInt() / 100f).toString()
                    Text(
                        text = stringResource(Res.string.adv_appearance_font_scale, formattedScale),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = fontScale,
                        onValueChange = { settingsViewModel.setMessageFontSizeScale((it * 20).roundToInt() / 20f) },
                        valueRange = 0.8f..1.4f,
                        steps = 11,
                    )
                }

                // Reaction Chip Spacing
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        text = stringResource(Res.string.adv_appearance_reaction_spacing, reactionSpacing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = reactionSpacing.toFloat(),
                        onValueChange = { settingsViewModel.setReactionChipSpacing(it.roundToInt()) },
                        valueRange = 0f..16f,
                        steps = 15,
                    )
                }

                OutlinedButton(
                    onClick = {
                        settingsViewModel.setMessageBubbleSpacing(DEFAULT_BUBBLE_SPACING)
                        settingsViewModel.setMessageBubblePadding(DEFAULT_BUBBLE_PADDING)
                        settingsViewModel.setMessageFontSizeScale(DEFAULT_FONT_SCALE)
                        settingsViewModel.setReactionChipSpacing(DEFAULT_REACTION_SPACING)
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(text = stringResource(Res.string.clear))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    when (editingSlot) {
        ColorSlot.PRIMARY -> {
            AdvColorPickerSheet(
                title = stringResource(Res.string.adv_appearance_primary_color),
                initialArgb = advColors.primaryArgb,
                defaultAutoColor = primaryLight,
                onConfirm = { newArgb -> settingsViewModel.setAdvThemeColors(advColors.copy(primaryArgb = newArgb)) },
                onDismiss = { editingSlot = null },
            )
        }

        ColorSlot.SECONDARY -> {
            AdvColorPickerSheet(
                title = stringResource(Res.string.adv_appearance_secondary_color),
                initialArgb = advColors.secondaryArgb,
                defaultAutoColor = defaultSecondary,
                onConfirm = { newArgb -> settingsViewModel.setAdvThemeColors(advColors.copy(secondaryArgb = newArgb)) },
                onDismiss = { editingSlot = null },
            )
        }

        ColorSlot.TERTIARY -> {
            AdvColorPickerSheet(
                title = stringResource(Res.string.adv_appearance_tertiary_color),
                initialArgb = advColors.tertiaryArgb,
                defaultAutoColor = defaultTertiary,
                onConfirm = { newArgb -> settingsViewModel.setAdvThemeColors(advColors.copy(tertiaryArgb = newArgb)) },
                onDismiss = { editingSlot = null },
            )
        }

        ColorSlot.BASE -> {
            AdvColorPickerSheet(
                title = stringResource(Res.string.adv_appearance_base_color),
                initialArgb = advColors.baseArgb,
                defaultAutoColor = defaultBase,
                onConfirm = { newArgb -> settingsViewModel.setAdvThemeColors(advColors.copy(baseArgb = newArgb)) },
                onDismiss = { editingSlot = null },
            )
        }

        null -> {}
    }
}
