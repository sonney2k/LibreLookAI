package com.librelookai.outfit

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librelookai.R

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ForecastDayPickerSheet(
    todayWeather: com.librelookai.weather.WeatherData?,
    forecasts: List<com.librelookai.data.model.DayForecast>,
    selectedDate: String?,
    weatherMode: com.librelookai.outfit.ComposerWeatherMode,
    onModeChange: (com.librelookai.outfit.ComposerWeatherMode) -> Unit,
    manualSeason: String,
    onManualSeason: (String) -> Unit,
    manualTempC: Int?,
    onManualTempC: (Int?) -> Unit,
    manualPrecip: String,
    onManualPrecip: (String) -> Unit,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.composer_weather_title),
                    style = MaterialTheme.typography.titleMedium,
                )

                // Mode toggle — Forecast (auto/picked day) vs. Manual override.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = weatherMode == com.librelookai.outfit.ComposerWeatherMode.AUTO,
                        onClick = { onModeChange(com.librelookai.outfit.ComposerWeatherMode.AUTO) },
                        label = { Text(stringResource(R.string.composer_weather_auto)) },
                    )
                    androidx.compose.material3.FilterChip(
                        selected = weatherMode == com.librelookai.outfit.ComposerWeatherMode.MANUAL,
                        onClick = { onModeChange(com.librelookai.outfit.ComposerWeatherMode.MANUAL) },
                        label = { Text(stringResource(R.string.composer_weather_manual)) },
                    )
                }

                if (weatherMode == com.librelookai.outfit.ComposerWeatherMode.MANUAL) {
                    ManualWeatherControls(
                        season = manualSeason,
                        onSeason = onManualSeason,
                        tempC = manualTempC,
                        onTempC = onManualTempC,
                        precip = manualPrecip,
                        onPrecip = onManualPrecip,
                    )
                } else {
                    Text(
                        stringResource(R.string.prediction_setup_weather_pick_day),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val today = java.time.LocalDate.now()
                    val todayLabel = stringResource(R.string.prediction_setup_weather_today)
                    val tomorrowLabel = stringResource(R.string.prediction_setup_weather_tomorrow)

                    // "Today" row — uses live current-weather data, not the forecast block.
                    ForecastDayRow(
                        label = todayLabel,
                        sub = todayWeather?.cityName?.takeIf { it.isNotEmpty() } ?: "",
                        emoji = todayWeather?.let { com.librelookai.weather.wmoEmoji(it.weatherCode) } ?: "—",
                        tempLine = todayWeather?.let { "${it.temperatureCelsius.toInt()}°C" } ?: "",
                        selected = selectedDate == null,
                        onClick = { onSelect(null) },
                    )

                    forecasts.take(7).forEach { day ->
                        val date = runCatching { java.time.LocalDate.parse(day.date) }.getOrNull()
                        if (date == null || !date.isAfter(today)) return@forEach
                        val daysAhead = java.time.temporal.ChronoUnit.DAYS.between(today, date).toInt()
                        val label = when (daysAhead) {
                            1 -> tomorrowLabel
                            else -> date.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))
                        }
                        ForecastDayRow(
                            label = label,
                            sub = if (daysAhead != 1) "" else date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d")),
                            emoji = com.librelookai.weather.wmoEmoji(day.weatherCode),
                            tempLine = "${day.minTempC.toInt()}° / ${day.maxTempC.toInt()}°",
                            selected = selectedDate == day.date,
                            onClick = { onSelect(day.date) },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ManualWeatherControls(
    season: String,
    onSeason: (String) -> Unit,
    tempC: Int?,
    onTempC: (Int?) -> Unit,
    precip: String,
    onPrecip: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.composer_weather_season), style = MaterialTheme.typography.labelMedium)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val seasons = listOf(
                "Spring" to R.string.composer_season_spring,
                "Summer" to R.string.composer_season_summer,
                "Fall" to R.string.composer_season_fall,
                "Winter" to R.string.composer_season_winter,
            )
            seasons.forEach { (value, labelRes) ->
                androidx.compose.material3.FilterChip(
                    selected = season == value,
                    onClick = { onSeason(if (season == value) "" else value) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
        Text(stringResource(R.string.composer_weather_temp), style = MaterialTheme.typography.labelMedium)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(-5, 5, 15, 22, 30).forEach { t ->
                androidx.compose.material3.FilterChip(
                    selected = tempC == t,
                    onClick = { onTempC(if (tempC == t) null else t) },
                    label = { Text(stringResource(R.string.composer_temp_value, t)) },
                )
            }
        }
        Text(stringResource(R.string.composer_weather_precip), style = MaterialTheme.typography.labelMedium)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val precips = listOf(
                "None" to R.string.composer_precip_none,
                "Light" to R.string.composer_precip_light,
                "Heavy" to R.string.composer_precip_heavy,
            )
            precips.forEach { (value, labelRes) ->
                androidx.compose.material3.FilterChip(
                    selected = precip == value,
                    onClick = { onPrecip(if (precip == value) "" else value) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
    }
}

@Composable
private fun ForecastDayRow(
    label: String,
    sub: String,
    emoji: String,
    tempLine: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.surface,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(emoji, fontSize = 24.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                if (sub.isNotEmpty()) {
                    Text(sub, style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (tempLine.isNotEmpty()) {
                Text(tempLine, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
