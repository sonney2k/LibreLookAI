package com.librelookai.outfit

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librelookai.R
import com.librelookai.settings.AiConsiderations

@Composable
internal fun OccasionCard(goal: String, onGoalChange: (String) -> Unit) {
    SectionCard(
        icon = Icons.Default.AutoAwesome,
        title = stringResource(R.string.composer_section_occasion),
        hint = stringResource(R.string.composer_occasion_hint),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            OutlinedTextField(
                value = goal,
                onValueChange = onGoalChange,
                placeholder = {
                    Text(
                        stringResource(R.string.composer_goal_placeholder),
                        fontSize = 14.sp,
                    )
                },
                trailingIcon = {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                },
                minLines = 1,
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
        }
    }
}

// ─── 2) Weather ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WeatherCard(
    weatherMode: ComposerWeatherMode,
    autoWeather: com.librelookai.weather.WeatherData?,
    manualTempC: Int?,
    manualPrecip: String,
    onModeChange: (ComposerWeatherMode) -> Unit,
    onTempChange: (Int?) -> Unit,
    onPrecipChange: (String) -> Unit,
    onOpenForecastPicker: () -> Unit,
) {
    SectionCard(
        icon = Icons.Default.WbSunny,
        title = stringResource(R.string.composer_section_weather),
    ) {
        val autoLabel = remember(autoWeather) {
            buildString {
                append("Auto")
                autoWeather?.cityName?.takeIf { it.isNotEmpty() }?.let { append(" · ").append(it) }
                autoWeather?.temperatureCelsius?.let { append(" ").append(it.toInt()).append("°") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallPillChip(
                label = autoLabel,
                icon = Icons.Default.Refresh,
                active = weatherMode == ComposerWeatherMode.AUTO,
                onClick = {
                    onModeChange(ComposerWeatherMode.AUTO)
                    onTempChange(null)
                    onPrecipChange("")
                },
            )
            SmallPillChip(
                label = stringResource(R.string.composer_weather_manual),
                icon = Icons.Default.Tune,
                active = weatherMode == ComposerWeatherMode.MANUAL,
                onClick = { onModeChange(ComposerWeatherMode.MANUAL) },
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onOpenForecastPicker,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    stringResource(R.string.prediction_setup_weather_pick_day),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (weatherMode == ComposerWeatherMode.MANUAL) {
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.composer_weather_temp).uppercase(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf(-5, 5, 12, 18, 22, 28).forEach { t ->
                            SmallPillChip(
                                label = "${t}°",
                                icon = null,
                                active = manualTempC == t,
                                onClick = { onTempChange(if (manualTempC == t) null else t) },
                            )
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.composer_weather_precip).uppercase(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        val precips = listOf(
                            "None" to R.string.composer_precip_none,
                            "Light" to R.string.composer_precip_light,
                            "Heavy" to R.string.composer_precip_heavy,
                        )
                        precips.forEach { (value, labelRes) ->
                            SmallPillChip(
                                label = stringResource(labelRes),
                                icon = null,
                                active = manualPrecip == value,
                                onClick = { onPrecipChange(if (manualPrecip == value) "" else value) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── 3) Style vibe ───────────────────────────────────────────────────────────

private val TUNE_VIBES: List<Pair<String, Int>> = listOf(
    "Casual" to R.string.composer_vibe_casual,
    "Sporty" to R.string.composer_vibe_sporty,
    "Formal" to R.string.composer_vibe_formal,
    "Business" to R.string.composer_vibe_business,
    "Streetwear" to R.string.composer_vibe_streetwear,
    "Minimalist" to R.string.composer_vibe_minimalist,
    "Classic" to R.string.composer_vibe_classic,
    "Elegant" to R.string.composer_vibe_elegant,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StyleVibeCard(selected: Set<String>, onToggle: (String) -> Unit) {
    SectionCard(
        icon = Icons.Default.AutoAwesome,
        title = stringResource(R.string.composer_section_vibe),
        hint = stringResource(R.string.composer_vibe_count, selected.size),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            TUNE_VIBES.forEach { (value, labelRes) ->
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

// ─── 4) AI considers ─────────────────────────────────────────────────────────

@Composable
internal fun ConsidersCard(
    considerations: AiConsiderations,
    onToggle: ((AiConsiderations) -> AiConsiderations) -> Unit,
) {
    val rows = listOf(
        ConsiderationRow(R.string.ai_consider_weather, Icons.Default.WbSunny, considerations.weather) { onToggle { it.copy(weather = !it.weather) } },
        ConsiderationRow(R.string.ai_consider_location, Icons.Default.Place, considerations.location) { onToggle { it.copy(location = !it.location) } },
        ConsiderationRow(R.string.ai_consider_trends, Icons.AutoMirrored.Filled.TrendingUp, considerations.trends) { onToggle { it.copy(trends = !it.trends) } },
        ConsiderationRow(R.string.ai_consider_gender, Icons.Default.Person, considerations.gender) { onToggle { it.copy(gender = !it.gender) } },
        ConsiderationRow(R.string.ai_consider_age, Icons.Default.Cake, considerations.age) { onToggle { it.copy(age = !it.age) } },
        ConsiderationRow(R.string.ai_consider_preferences, Icons.Default.Favorite, considerations.preferences) { onToggle { it.copy(preferences = !it.preferences) } },
    )
    SectionCard(
        icon = Icons.AutoMirrored.Filled.TrendingUp,
        title = stringResource(R.string.composer_section_considerations),
        hint = stringResource(R.string.composer_considerations_hint),
    ) {
        Column {
            rows.forEachIndexed { index, row ->
                ConsiderationToggleRow(row)
                if (index != rows.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 1.dp,
                    )
                }
            }
        }
    }
}

private data class ConsiderationRow(
    val labelRes: Int,
    val icon: ImageVector,
    val active: Boolean,
    val onToggle: () -> Unit,
)

@Composable
private fun ConsiderationToggleRow(row: ConsiderationRow) {
    val iconBg = if (row.active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val iconFg = if (row.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = if (row.active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = row.onToggle)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(row.icon, contentDescription = null, tint = iconFg, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(row.labelRes),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = labelColor,
            modifier = Modifier.weight(1f),
        )
        MiniSwitch(active = row.active, onToggle = row.onToggle)
    }
}

@Composable
private fun MiniSwitch(active: Boolean, onToggle: () -> Unit) {
    val trackBg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val thumbColor = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .width(34.dp)
            .height(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(trackBg)
            .border(
                if (active) 0.dp else 1.dp,
                if (active) Color.Transparent else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onToggle)
            .padding(2.dp),
        contentAlignment = if (active) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(thumbColor),
        )
    }
}

// ─── Shared small chip ───────────────────────────────────────────────────────

@Composable
private fun SmallPillChip(
    label: String,
    icon: ImageVector?,
    active: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val borderColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
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

// ─── Closet chip row ─────────────────────────────────────────────────────────

@Composable
internal fun ClosetChipRow(
    closetNames: List<String>,
    onClick: () -> Unit,
) {
    val label = closetNames.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        ?: stringResource(R.string.composer_closets_all)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.composer_section_sources),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        SmallPillChip(
            label = label,
            icon = Icons.Default.Place,
            active = closetNames.isNotEmpty(),
            onClick = onClick,
        )
    }
}

@Composable
internal fun SuggestionCountSelector(count: Int, onCountChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.prediction_setup_suggestions),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { if (count > 1) onCountChange(count - 1) },
            enabled = count > 1,
        ) {
            Text("−", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            count.toString(),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.widthIn(min = 28.dp),
        )
        IconButton(
            onClick = { if (count < 10) onCountChange(count + 1) },
            enabled = count < 10,
        ) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
    }
}

