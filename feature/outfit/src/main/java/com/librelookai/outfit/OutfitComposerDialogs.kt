package com.librelookai.outfit

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.librelookai.feature.outfit.R
import com.librelookai.core.designsystem.R as DsR

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SaveOutfitDialog(
    initialName: String,
    initialDescription: String,
    initialTags: List<String>,
    tagSuggestions: List<String> = emptyList(),
    parentContext: android.content.Context,
    parentConfiguration: android.content.res.Configuration,
    onConfirm: (name: String, description: String, tags: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    var tags by remember(initialTags) { mutableStateOf(initialTags) }
    var newTagInput by remember { mutableStateOf("") }

    val locale: @Composable (@Composable () -> Unit) -> Unit = { content ->
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) { content() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { locale { Text(stringResource(R.string.outfit_save_dialog_title)) } },
        text = {
            locale {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.outfit_save_dialog_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text(stringResource(R.string.outfit_save_dialog_description)) },
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.outfit_save_dialog_tags),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutfitTagsEditor(
                        tags = tags,
                        onAdd = { t -> if (t.isNotBlank()) tags = (tags + t).distinctBy { it.lowercase() } },
                        onRemove = { t -> tags = tags - t },
                    )
                    val unusedSuggestions = remember(tagSuggestions, tags) {
                        val have = tags.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
                        tagSuggestions.filter { it.lowercase(java.util.Locale.ROOT) !in have }
                    }
                    if (unusedSuggestions.isNotEmpty()) {
                        Text(
                            stringResource(R.string.outfit_save_dialog_tag_suggestions),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            unusedSuggestions.forEach { suggestion ->
                                InputChip(
                                    selected = false,
                                    onClick = {
                                        tags = (tags + suggestion).distinctBy { it.lowercase(java.util.Locale.ROOT) }
                                    },
                                    label = { Text(suggestion, style = MaterialTheme.typography.labelSmall) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Add, contentDescription = null,
                                             modifier = Modifier.size(14.dp))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            locale {
                TextButton(onClick = { onConfirm(name.trim(), description.trim(), tags) }) {
                    Text(stringResource(R.string.outfit_save_dialog_confirm))
                }
            }
        },
        dismissButton = {
            locale {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(DsR.string.action_cancel))
                }
            }
        },
    )
}

// ─── Discard changes dialog ──────────────────────────────────────────────────

@Composable
internal fun DiscardChangesDialog(
    parentContext: android.content.Context,
    parentConfiguration: android.content.res.Configuration,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val locale: @Composable (@Composable () -> Unit) -> Unit = { content ->
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) { content() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { locale { Text(stringResource(R.string.outfit_discard_changes_title)) } },
        text = { locale { Text(stringResource(R.string.outfit_discard_changes_body)) } },
        confirmButton = {
            locale {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.outfit_discard_changes_confirm))
                }
            }
        },
        dismissButton = {
            locale {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(DsR.string.action_cancel))
                }
            }
        },
    )
}

// ─── Dashed border helper ───────────────────────────────────────────────────

internal fun Modifier.dashedBorder(color: Color, width: Dp, radius: Dp): Modifier =
    this.then(
        Modifier.drawBehind {
            val strokePx = width.toPx()
            val radiusPx = radius.toPx()
            val pe = PathEffect.dashPathEffect(floatArrayOf(strokePx * 3, strokePx * 3), 0f)
            drawRoundRect(
                color = color,
                cornerRadius = CornerRadius(radiusPx, radiusPx),
                size = Size(size.width - strokePx, size.height - strokePx),
                topLeft = androidx.compose.ui.geometry.Offset(strokePx / 2, strokePx / 2),
                style = Stroke(width = strokePx, pathEffect = pe),
            )
        }
    )


// ─── Existing helpers (unchanged) ──────────────────────────────────────────

