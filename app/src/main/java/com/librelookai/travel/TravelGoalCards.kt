package com.librelookai.travel

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librelookai.R

@Composable
internal fun GoalAiCard(
    goal: String,
    onUpdateGoal: (String) -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(listOf(palette.primaryDim, palette.chipBg)),
            )
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
                    stringResource(R.string.travel_label_about).uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = goal,
                onValueChange = onUpdateGoal,
                placeholder = { Text(stringResource(R.string.travel_goal_placeholder)) },
                trailingIcon = {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        tint = palette.textMuted,
                        modifier = Modifier.size(13.dp),
                    )
                },
                minLines = 1,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun VibeChips(
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${stringResource(R.string.travel_label_vibe).uppercase()} (${selected.size})",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = palette.textMuted,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            items(TRAVEL_VIBES, key = { it.first }) { (value, labelRes) ->
                SmallPillChip(
                    label = stringResource(labelRes),
                    icon = null,
                    active = value in selected,
                    onClick = { onToggle(value) },
                )
            }
        }
    }
}

@Composable
internal fun AiConsidersChips(
    considerations: com.librelookai.settings.AiConsiderations,
    onToggle: ((com.librelookai.settings.AiConsiderations) -> com.librelookai.settings.AiConsiderations) -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.travel_label_ai_considers).uppercase(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = palette.textMuted,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            item {
                SmallPillChip(
                    label = stringResource(R.string.ai_consider_weather),
                    icon = Icons.Default.WbSunny,
                    active = considerations.weather,
                ) { onToggle { it.copy(weather = !it.weather) } }
            }
            item {
                SmallPillChip(
                    label = stringResource(R.string.ai_consider_location),
                    icon = Icons.Default.Place,
                    active = considerations.location,
                ) { onToggle { it.copy(location = !it.location) } }
            }
            item {
                SmallPillChip(
                    label = stringResource(R.string.ai_consider_trends),
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    active = considerations.trends,
                ) { onToggle { it.copy(trends = !it.trends) } }
            }
            item {
                SmallPillChip(
                    label = stringResource(R.string.ai_consider_gender),
                    icon = Icons.Default.Person,
                    active = considerations.gender,
                ) { onToggle { it.copy(gender = !it.gender) } }
            }
            item {
                SmallPillChip(
                    label = stringResource(R.string.ai_consider_age),
                    icon = Icons.Default.Cake,
                    active = considerations.age,
                ) { onToggle { it.copy(age = !it.age) } }
            }
            item {
                SmallPillChip(
                    label = stringResource(R.string.ai_consider_preferences),
                    icon = Icons.Default.Favorite,
                    active = considerations.preferences,
                ) { onToggle { it.copy(preferences = !it.preferences) } }
            }
            item {
                SmallPillChip(
                    label = stringResource(R.string.ai_consider_history),
                    icon = Icons.Default.History,
                    active = considerations.history,
                ) { onToggle { it.copy(history = !it.history) } }
            }
        }
    }
}

/**
 * Expert option mirroring the outfit composer's [com.librelookai.outfit.ExpertTagsCard]: chooses
 * which item tag dimensions ([AiConsiderations.TOGGLEABLE_TAGS], e.g. pattern, material) get fed
 * into the packing prompt. `type`/`category`/`name` are always sent and not shown here. Toggling
 * routes through the same [onToggle] transform as the considerations chips.
 */
@Composable
internal fun AiTagChips(
    considerations: com.librelookai.settings.AiConsiderations,
    onToggle: ((com.librelookai.settings.AiConsiderations) -> com.librelookai.settings.AiConsiderations) -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.composer_section_tags).uppercase(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = palette.textMuted,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            items(com.librelookai.settings.AiConsiderations.TOGGLEABLE_TAGS) { dim ->
                val labelRes = tagDimLabelRes(dim) ?: return@items
                SmallPillChip(
                    label = stringResource(labelRes),
                    icon = null,
                    active = considerations.includesItemTag(dim),
                ) { onToggle { it.toggleItemTag(dim) } }
            }
        }
    }
}

private fun tagDimLabelRes(dim: String): Int? = when (dim) {
    "uses" -> R.string.tag_uses
    "colors" -> R.string.tag_colors
    "seasonality" -> R.string.tag_seasonality
    "aesthetic" -> R.string.tag_aesthetic
    "fit" -> R.string.tag_fit
    "material" -> R.string.tag_material
    "pattern" -> R.string.tag_pattern
    else -> null
}

@Composable
internal fun SmallPillChip(
    label: String,
    icon: ImageVector?,
    active: Boolean,
    onClick: () -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val bg = if (active) palette.primary else palette.chipBg
    val fg = if (active) palette.onPrimary else palette.chipFg
    val borderColor = if (active) palette.primary else palette.border
    val borderWidth = if (active) 1.5.dp else 1.dp
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(borderWidth, borderColor, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
        }
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

@Composable
internal fun GenerateButton(
    hasResult: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    tokens: com.librelookai.gemini.CostTokens? = null,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        palette.primary.copy(alpha = alpha),
                        palette.primary.copy(alpha = alpha * 0.85f),
                    ),
                ),
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Single-call generation: the packing list returns every outfit in one Gemini
            // round-trip, so the cost barely scales with `outfitCount`. Match the
            // OutfitComposer's "Generate with AI" badge and pass bulkCount = 1.
            com.librelookai.billing.CostBadge(
                com.librelookai.gemini.GeminiActionId.GENERATE_TEXT,
                tokens = tokens,
            )
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = palette.onPrimary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                if (hasResult) stringResource(R.string.travel_regenerate) else stringResource(R.string.travel_generate),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = palette.onPrimary,
            )
        }
    }
}
