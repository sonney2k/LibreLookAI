package com.librelookai.travel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librelookai.feature.travel.R
import com.librelookai.core.designsystem.R as DsR

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun BulkRefineSection(
    vibes: Set<String>,
    onToggleVibe: (String) -> Unit,
    considerations: com.librelookai.settings.AiConsiderations,
    onToggleConsideration: ((com.librelookai.settings.AiConsiderations) -> com.librelookai.settings.AiConsiderations) -> Unit,
    isRefining: Boolean,
    onSubmit: (String) -> Unit,
    /** Selected source-closet names. null hides the picker (e.g. only one closet exists). */
    closetNames: List<String>? = null,
    onPickClosets: () -> Unit = {},
    tokens: com.librelookai.gemini.CostTokens? = null,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    var text by remember { mutableStateOf("") }
    val presets = listOf(
        stringResource(R.string.trip_bulk_refine_brighter),
        stringResource(R.string.trip_bulk_refine_lighter),
        stringResource(R.string.trip_bulk_refine_formal),
        stringResource(R.string.trip_bulk_refine_skip_jacket),
    )
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.trip_bulk_refine_action),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (closetNames != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(DsR.string.composer_section_sources),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                AssistChip(
                    onClick = onPickClosets,
                    label = {
                        Text(
                            closetNames.takeIf { it.isNotEmpty() }?.joinToString(" · ")
                                ?: stringResource(DsR.string.composer_closets_all),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                )
            }
        }
        // Style refinement + what-AI-considers now live in the outfit-refinement section.
        com.librelookai.travel.VibeChips(selected = vibes, onToggle = onToggleVibe)
        com.librelookai.travel.AiConsidersChips(
            considerations = considerations,
            onToggle = onToggleConsideration,
        )
        // Presets only fill the instruction field — no action is taken until "Apply with AI".
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            presets.forEach { preset ->
                AssistChip(
                    onClick = { text = if (text.isBlank()) preset else "$text, $preset" },
                    label = { Text(preset, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        // Instruction input — styled like the trip-purpose field, with the AI icon.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(palette.primaryDim, palette.chipBg)))
                .border(1.dp, palette.primary.copy(alpha = 0.33f), RoundedCornerShape(16.dp))
                .padding(14.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = palette.primary,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.trip_bulk_refine_action).uppercase(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.primary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.trip_bulk_refine_hint)) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = palette.textMuted,
                            modifier = Modifier.size(13.dp),
                        )
                    },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    enabled = !isRefining,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LocalTravelCostBadge.current?.invoke(tokens)
            Spacer(Modifier.width(8.dp))
            TripGradientButton(
                label = stringResource(R.string.trip_bulk_refine_run),
                // Instruction is optional — Apply can also just re-style using the edited options.
                enabled = !isRefining,
                onClick = {
                    onSubmit(text.trim())
                    text = ""
                },
            )
        }
    }
}

@Composable
internal fun TripGradientButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        palette.primary.copy(alpha = alpha),
                        palette.primary.copy(alpha = alpha * 0.85f),
                    ),
                ),
            )
            .border(1.dp, palette.primary.copy(alpha = alpha * 0.5f), RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = palette.onPrimary, modifier = Modifier.size(14.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = palette.onPrimary)
        }
    }
}

@Composable
internal fun DeleteTripDialog(
    onDismiss: () -> Unit,
    onCascade: () -> Unit,
    onKeep: () -> Unit,
) {
    // AlertDialog opens its own window, which severs the locale-overridden context; re-provide
    // the parent's context/configuration in each slot so stringResource honours the app language.
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) { Text(stringResource(R.string.trip_delete_confirm_title)) }
        },
        text = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) { Text(stringResource(R.string.trip_delete_confirm_body)) }
        },
        confirmButton = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                TextButton(onClick = onCascade) {
                    Text(stringResource(R.string.trip_delete_with_outfits))
                }
            }
        },
        dismissButton = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                Row {
                    TextButton(onClick = onKeep) {
                        Text(stringResource(R.string.trip_delete_keep_outfits))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(DsR.string.action_cancel))
                    }
                }
            }
        },
    )
}
