package com.librelookai.settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librelookai.R
import com.librelookai.core.designsystem.R as DsR

/**
 * FlowRow of FilterChips for the five Settings → AI "standard criteria" toggles. Used by any
 * screen that wants to expose per-invocation overrides of the user's saved considerations
 * (Find/Create with AI, Travel planner). Weather is intentionally excluded — every callsite so
 * far has a dedicated weather picker.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiConsiderationsStrip(
    considerations: AiConsiderations,
    onToggle: ((AiConsiderations) -> AiConsiderations) -> Unit,
    titleRes: Int = R.string.prediction_setup_considerations,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ConsiderationChip(DsR.string.ai_consider_location, considerations.location) {
                onToggle { it.copy(location = !it.location) }
            }
            ConsiderationChip(DsR.string.ai_consider_trends, considerations.trends) {
                onToggle { it.copy(trends = !it.trends) }
            }
            ConsiderationChip(DsR.string.ai_consider_gender, considerations.gender) {
                onToggle { it.copy(gender = !it.gender) }
            }
            ConsiderationChip(DsR.string.ai_consider_age, considerations.age) {
                onToggle { it.copy(age = !it.age) }
            }
            ConsiderationChip(DsR.string.ai_consider_preferences, considerations.preferences) {
                onToggle { it.copy(preferences = !it.preferences) }
            }
        }
    }
}

/**
 * Expert option: a FlowRow of FilterChips choosing which item tag dimensions
 * ([AiConsiderations.TOGGLEABLE_TAGS]) get fed into outfit-creation and gap-analysis prompts.
 * `type`/`category`/`name` are always sent and not shown here. Reuses the existing per-dimension
 * tag labels (`tag_uses`, `tag_colors`, …) so no new translations are needed for the chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpertTagsStrip(
    considerations: AiConsiderations,
    onToggleTag: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(DsR.string.composer_section_tags),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(DsR.string.composer_tags_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AiConsiderations.TOGGLEABLE_TAGS.forEach { dim ->
                val labelRes = tagDimLabelRes(dim) ?: return@forEach
                ConsiderationChip(labelRes, considerations.includesItemTag(dim)) { onToggleTag(dim) }
            }
        }
    }
}

private fun tagDimLabelRes(dim: String): Int? = when (dim) {
    "uses" -> DsR.string.tag_uses
    "colors" -> DsR.string.tag_colors
    "seasonality" -> DsR.string.tag_seasonality
    "aesthetic" -> DsR.string.tag_aesthetic
    "fit" -> DsR.string.tag_fit
    "material" -> DsR.string.tag_material
    "pattern" -> DsR.string.tag_pattern
    else -> null
}

@Composable
private fun ConsiderationChip(labelRes: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(stringResource(labelRes)) },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
        } else null,
    )
}
