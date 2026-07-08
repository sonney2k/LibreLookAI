package com.librelookai.outfit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librelookai.feature.outfit.R
import com.librelookai.core.designsystem.R as DsR

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestTagsDialog(
    state: TagSuggestionState,
    onDismiss: () -> Unit,
    onApply: (List<String>) -> Unit,
) {
    var selected by remember(state.suggestions) {
        mutableStateOf(state.suggestions.toSet())
    }
    // Capture parent context/config OUTSIDE the dialog so stringResource honors the
    // in-app language toggle inside the dialog window. (See CLAUDE.md.)
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    AlertDialog(
        onDismissRequest = { if (!state.isSaving) onDismiss() },
        title = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) { Text(stringResource(R.string.outfits_suggest_tags_title)) }
        },
        text = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        state.isLoading -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.outfits_suggest_tags_loading))
                            }
                        }
                        state.suggestions.isEmpty() -> {
                            Text(state.error ?: stringResource(R.string.outfits_suggest_tags_empty))
                        }
                        else -> {
                            Text(
                                stringResource(R.string.outfits_suggest_tags_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                state.suggestions.forEach { tag ->
                                    FilterChip(
                                        selected = tag in selected,
                                        onClick = {
                                            selected = if (tag in selected) selected - tag else selected + tag
                                        },
                                        label = { Text(tag) },
                                        enabled = !state.isSaving,
                                    )
                                }
                            }
                            if (state.error != null) {
                                Text(
                                    state.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                if (state.suggestions.isNotEmpty()) {
                    TextButton(
                        onClick = { onApply(selected.toList()) },
                        enabled = !state.isSaving && !state.isLoading && selected.isNotEmpty(),
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.outfits_suggest_tags_apply))
                        }
                    }
                } else {
                    TextButton(onClick = onDismiss, enabled = !state.isSaving) {
                        Text(stringResource(DsR.string.action_ok))
                    }
                }
            }
        },
        dismissButton = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                if (state.suggestions.isNotEmpty()) {
                    TextButton(onClick = onDismiss, enabled = !state.isSaving) {
                        Text(stringResource(DsR.string.action_cancel))
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditOutfitTagsDialog(
    initialTags: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var working by remember(initialTags) { mutableStateOf(initialTags) }
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) { Text(stringResource(R.string.outfits_tags_label)) }
        },
        text = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                OutfitTagsEditor(
                    tags = working,
                    onAdd = { t ->
                        if (working.none { it.equals(t, ignoreCase = true) }) working = working + t
                    },
                    onRemove = { t -> working = working - t },
                )
            }
        },
        confirmButton = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                TextButton(onClick = { onSave(working) }) {
                    Text(stringResource(DsR.string.action_save))
                }
            }
        },
        dismissButton = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(DsR.string.action_cancel))
                }
            }
        },
    )
}

