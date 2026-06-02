package com.librelookai.outfit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librelookai.R
import com.librelookai.weather.WeatherData

@Composable
internal fun ComposerHeader(
    filledSlots: Int,
    totalSlots: Int,
    onClose: () -> Unit,
    onOpenFullscreen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
        }
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.composer_title),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                stringResource(R.string.composer_header_subtitle, filledSlots),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onOpenFullscreen) {
            Text(
                stringResource(R.string.composer_mode_fullscreen),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}


// ─── Context strip ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextStrip(
    weatherMode: ComposerWeatherMode,
    autoWeather: WeatherData?,
    manualTempC: Int?,
    closetNames: List<String>,
    closetPickerAvailable: Boolean,
    selectedVibes: Set<String>,
    onToggleVibe: (String) -> Unit,
    onClickWeather: () -> Unit,
    onClickCloset: () -> Unit,
    forecastDate: String? = null,
    forecastDayPreview: com.librelookai.data.model.DayForecast? = null,
) {
    val weatherLabel: String = when {
        forecastDayPreview != null -> {
            val date = runCatching { java.time.LocalDate.parse(forecastDayPreview.date) }.getOrNull()
            val label = date?.format(java.time.format.DateTimeFormatter.ofPattern("MMM d")) ?: ""
            "$label · ${forecastDayPreview.maxTempC.toInt()}°"
        }
        forecastDate != null -> {
            // Forecast picked but preview data not yet loaded
            val date = runCatching { java.time.LocalDate.parse(forecastDate) }.getOrNull()
            date?.format(java.time.format.DateTimeFormatter.ofPattern("MMM d")) ?: forecastDate
        }
        weatherMode == ComposerWeatherMode.AUTO -> autoWeather?.let { "${it.temperatureCelsius.toInt()}°" }
            ?: stringResource(R.string.composer_factor_weather_auto)
        // Manual mode always identifies itself as "Manual" so users can tell their override is in
        // effect; if a temp is set we append it ("Manual · 22°").
        else -> {
            val base = stringResource(R.string.composer_weather_manual)
            manualTempC?.let { "$base · ${it}°" } ?: base
        }
    }
    val vibes = listOf(
        "Casual" to R.string.composer_vibe_casual,
        "Business" to R.string.composer_vibe_business,
        "Formal" to R.string.composer_vibe_formal,
        "Streetwear" to R.string.composer_vibe_streetwear,
        "Minimalist" to R.string.composer_vibe_minimalist,
        "Sporty" to R.string.composer_vibe_sporty,
        "Elegant" to R.string.composer_vibe_elegant,
        "Classic" to R.string.composer_vibe_classic,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContextChip(
            label = weatherLabel,
            icon = Icons.Default.WbSunny,
            active = true,
            onClick = onClickWeather,
        )
        if (closetPickerAvailable) {
            ContextChip(
                label = closetNames.takeIf { it.isNotEmpty() }?.joinToString(" · ")
                    ?: stringResource(R.string.composer_closets_all),
                icon = Icons.Default.Place,
                active = closetNames.isNotEmpty(),
                onClick = onClickCloset,
            )
        }
        vibes.forEach { (value, labelRes) ->
            ContextChip(
                label = stringResource(labelRes),
                icon = null,
                active = value in selectedVibes,
                onClick = { onToggleVibe(value) },
            )
        }
    }
}

@Composable
private fun ContextChip(
    label: String,
    icon: ImageVector?,
    active: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val borderColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(if (active) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}




// ─── Bottom bar (edit mode) ──────────────────────────────────────────────────

@Composable
internal fun ComposerEditBottomBar(
    saveEnabled: Boolean,
    aiEnabled: Boolean,
    isOffline: Boolean,
    onGenerateWithAi: () -> Unit,
    onSave: () -> Unit,
    bottomPadding: Dp,
    aiTokens: com.librelookai.gemini.CostTokens? = null,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
            .padding(bottom = bottomPadding)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isOffline) {
            OutlinedButton(
                onClick = onGenerateWithAi,
                enabled = aiEnabled,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(24.dp),
            ) {
                com.librelookai.billing.CostBadge(
                    com.librelookai.gemini.GeminiActionId.OUTFIT_SUGGESTION,
                    tokens = aiTokens,
                )
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.outfit_generate_with_ai),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        androidx.compose.material3.FilledTonalButton(
            onClick = onSave,
            enabled = saveEnabled,
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            modifier = Modifier.height(48.dp),
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.composer_save),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

// ─── Stacked composer view (overlapping tiles with drop shadows) ────────────

