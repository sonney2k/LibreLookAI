package com.librelookai.wardrobe

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librelookai.R
import com.librelookai.gemini.ClothingTags
import com.librelookai.gemini.normalizeColor
import com.librelookai.ui.theme.LocalWardrobePalette

private fun ClothingTags.valuesFor(key: String): List<String> = when (key) {
    "colors"      -> colors
    "uses"        -> uses
    "seasonality" -> seasonality
    "aesthetic"   -> aesthetic
    "fit"         -> fit
    "material"    -> material
    "pattern"     -> pattern
    else          -> emptyList()
}

private fun ClothingTags.toggleValue(key: String, value: String): ClothingTags {
    val current = valuesFor(key)
    val next = if (value in current) current - value else current + value
    return when (key) {
        "colors"      -> copy(colors = next)
        "uses"        -> copy(uses = next)
        "seasonality" -> copy(seasonality = next)
        "aesthetic"   -> copy(aesthetic = next)
        "fit"         -> copy(fit = next)
        "material"    -> copy(material = next)
        "pattern"     -> copy(pattern = next)
        else          -> this
    }
}

private fun ClothingTags.addCustom(key: String, value: String): ClothingTags {
    // Colors are a closed vocabulary with no custom-input affordance; only open
    // dimensions (material, pattern, …) reach this path.
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return this
    val current = valuesFor(key)
    if (trimmed in current) return this
    val next = current + trimmed
    return when (key) {
        "colors"      -> copy(colors = next)
        "uses"        -> copy(uses = next)
        "seasonality" -> copy(seasonality = next)
        "aesthetic"   -> copy(aesthetic = next)
        "fit"         -> copy(fit = next)
        "material"    -> copy(material = next)
        "pattern"     -> copy(pattern = next)
        else          -> this
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagsTableCard(
    tags: ClothingTags,
    openRow: String?,
    onToggleRow: (String) -> Unit,
    onToggleValue: ((ClothingTags) -> ClothingTags) -> Unit,
    suggest: (String, List<String>) -> List<String>,
) {
    val palette = LocalWardrobePalette.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.border),
        modifier = Modifier.clip(RoundedCornerShape(18.dp)),
    ) {
        Column {
            TAG_ROWS.forEachIndexed { idx, spec ->
                val isOpen = openRow == spec.key
                val values = tags.valuesFor(spec.key)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isOpen) palette.surface2 else Color.Transparent)
                        .then(Modifier.animateContentSize(
                            animationSpec = androidx.compose.animation.core.tween(220),
                        )),
                ) {
                    TagRowCollapsed(
                        labelRes = spec.labelRes,
                        values = values,
                        isColor = spec.isColor,
                        isOpen = isOpen,
                        onClick = { onToggleRow(spec.key) },
                    )
                    if (isOpen) {
                        val merged = suggest(spec.sectionLabel, spec.presets)
                        if (spec.isColor) {
                            ColorDrawer(
                                active = values,
                                onToggle = { v -> onToggleValue { it.toggleValue(spec.key, v) } },
                            )
                        } else {
                            ChipDrawer(
                                active = values,
                                options = merged,
                                onToggle = { v -> onToggleValue { it.toggleValue(spec.key, v) } },
                                onAddCustom = { v -> onToggleValue { it.addCustom(spec.key, v) } },
                            )
                        }
                    }
                }
                if (idx != TAG_ROWS.lastIndex) {
                    HorizontalDivider(color = palette.divider, thickness = 1.dp)
                }
            }
        }
    }
}

@Composable
private fun TagRowCollapsed(
    labelRes: Int,
    values: List<String>,
    isColor: Boolean,
    isOpen: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalWardrobePalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(labelRes),
            modifier = Modifier.width(88.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = palette.text,
        )
        Box(modifier = Modifier.weight(1f)) {
            when {
                values.isEmpty() -> Text(
                    stringResource(R.string.wardrobe_tag_not_set),
                    fontSize = 12.sp,
                    color = palette.textMuted,
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                )
                isColor -> ColorSummary(values)
                else -> {
                    val localized = values.take(3).map { it.localizedTagValue() }
                    val shown = localized.joinToString(", ")
                    val rest = values.size - 3
                    val text = if (rest > 0) "$shown +$rest" else shown
                    Text(
                        text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Icon(
            if (isOpen) androidx.compose.material.icons.Icons.Default.KeyboardArrowUp
            else androidx.compose.material.icons.Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = palette.textMuted,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun ColorSummary(values: List<String>) {
    val palette = LocalWardrobePalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            values.take(5).forEachIndexed { i, v ->
                Box(
                    modifier = Modifier
                        .offset(x = ((-6) * i).dp)
                        .size(18.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colorFor(v) ?: palette.chipBg)
                        .border(2.dp, palette.surface, RoundedCornerShape(999.dp)),
                )
            }
        }
        val localized = values.take(2).map { it.localizedTagValue() }
        val named = localized.joinToString(", ")
        val rest = values.size - 2
        val text = if (rest > 0) "$named +$rest" else named
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun colorFor(name: String): Color? = colorSwatchOrNull(name.normalizeColor()) ?: colorSwatchOrNull(name)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorDrawer(
    active: List<String>,
    onToggle: (String) -> Unit,
) {
    val palette = LocalWardrobePalette.current
    // Closed palette: only canonical swatches. `active` is included defensively in case a legacy
    // item still carries a pre-normalization value, so it remains de-selectable.
    val all = (FilterColorKeys + active).distinct()
    Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 14.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            maxItemsInEachRow = 7,
        ) {
            all.forEach { value ->
                val isActive = value in active
                val swatch = colorFor(value) ?: palette.chipBg
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.width(36.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(swatch)
                            .border(
                                width = if (isActive) 2.5.dp else 1.dp,
                                color = if (isActive) palette.primary else palette.border,
                                shape = RoundedCornerShape(999.dp),
                            )
                            .clickable { onToggle(value) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isActive) {
                            val onSwatch = if (value == "white" || value == "cream") Color(0xFF222222) else Color.White
                            Icon(
                                androidx.compose.material.icons.Icons.Default.Check,
                                contentDescription = null,
                                tint = onSwatch,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    Text(
                        text = value.localizedTagValue(),
                        fontSize = 9.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        color = if (isActive) palette.primary else palette.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipDrawer(
    active: List<String>,
    options: List<String>,
    onToggle: (String) -> Unit,
    onAddCustom: (String) -> Unit,
) {
    val palette = LocalWardrobePalette.current
    val all = (active + options).distinct()
    Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 14.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            all.forEach { value ->
                val isActive = value in active
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (isActive) palette.primary else palette.chipBg,
                    border = BorderStroke(
                        width = if (isActive) 1.5.dp else 1.dp,
                        color = if (isActive) palette.primary else palette.border,
                    ),
                    onClick = { onToggle(value) },
                ) {
                    Text(
                        text = value.localizedTagValue(),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isActive) palette.fabFg else palette.chipFg,
                    )
                }
            }
        }
        AddCustomField(onAdd = onAddCustom)
    }
}

@Composable
private fun AddCustomField(onAdd: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val palette = LocalWardrobePalette.current
    Spacer(Modifier.height(8.dp))
    if (!expanded) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, palette.border),
            onClick = { expanded = true },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    androidx.compose.material.icons.Icons.Default.Add,
                    contentDescription = null,
                    tint = palette.textMuted,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    stringResource(R.string.tag_add_custom),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textMuted,
                )
            }
        }
    } else {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.tag_add_custom)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (text.isNotBlank()) onAdd(text.trim())
                text = ""
                expanded = false
            }),
            trailingIcon = {
                IconButton(onClick = {
                    if (text.isNotBlank()) onAdd(text.trim())
                    text = ""
                    expanded = false
                }) { Icon(androidx.compose.material.icons.Icons.Default.Check, contentDescription = null) }
            },
        )
    }
}

@Composable
internal fun DetailTagChip(label: String) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
internal fun TagChip(label: String) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = Color.White.copy(alpha = 0.18f),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

// ---------- Hide-tags chip (shared by item + outfit fullscreen viewers) ----------

