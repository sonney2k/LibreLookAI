package com.librelookai.wardrobe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.librelookai.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagFilterBar(
    tagCategories: List<TagCategory>,
    selectedTags: Map<String, Set<String>>,
    onTagsChanged: (Map<String, Set<String>>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedCategory by remember { mutableStateOf<String?>(null) }
    if (tagCategories.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(tagCategories) { category ->
            val catSelected = selectedTags[category.label] ?: emptySet()
            val activeCount = catSelected.size
            Box {
                FilterChip(
                    selected = activeCount > 0,
                    onClick = {
                        expandedCategory = if (expandedCategory == category.label) null else category.label
                    },
                    label = { val displayLabel = tagCategoryDisplayLabel(category.label); Text(if (activeCount > 0) "$displayLabel ($activeCount)" else displayLabel) },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp)) },
                )
                DropdownMenu(
                    expanded = expandedCategory == category.label,
                    onDismissRequest = { expandedCategory = null },
                ) {
                    category.tags.forEach { tag ->
                        val checked = tag in catSelected
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (checked) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    else Spacer(Modifier.size(18.dp))
                                    Text(tag.localizedTagValue())
                                }
                            },
                            onClick = {
                                val updated = if (checked) catSelected - tag else catSelected + tag
                                onTagsChanged(selectedTags + (category.label to updated))
                            },
                        )
                    }
                }
            }
        }
        if (selectedTags.values.any { it.isNotEmpty() }) {
            item { TextButton(onClick = { onTagsChanged(emptyMap()) }) { Text(stringResource(R.string.action_clear)) } }
        }
    }
}

// ---------- Grid ----------

@Composable
internal fun HideTagsChip(
    hideTags: Boolean,
    onToggle: () -> Unit,
) {
    androidx.compose.material3.Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.onBackground,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (hideTags) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                stringResource(if (hideTags) R.string.viewer_show_tags else R.string.viewer_hide_tags),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

// ---------- Sort button ----------

@Composable
internal fun SortButton(
    sortBy: SortOption,
    onSortChanged: (SortOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    // DropdownMenu renders in its own popup window; re-provide LocalContext/LocalConfiguration
    // so stringResource() honors the in-app language toggle.
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.wardrobe_sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                SortOption.values().forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (option == sortBy) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                else Spacer(Modifier.size(18.dp))
                                Text(option.displayLabel())
                            }
                        },
                        onClick = { onSortChanged(option); expanded = false },
                    )
                }
            }
        }
    }
}

