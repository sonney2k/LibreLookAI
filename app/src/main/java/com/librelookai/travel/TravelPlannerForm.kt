package com.librelookai.travel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librelookai.R
import com.librelookai.core.designsystem.R as DsR
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PlanTripSection(
    state: TravelUiState,
    considerations: com.librelookai.settings.AiConsiderations,
    geoLanguage: String,
    onUpdateDestination: (String, String) -> Unit,
    onPickSuggestion: (com.librelookai.weather.DestinationSuggestion) -> Unit,
    onClearDestinationSuggestions: () -> Unit,
    onUpdateDays: (Int) -> Unit,
    onUpdateStartDate: (LocalDate) -> Unit,
    onUpdateOutfitCount: (Int?) -> Unit,
    onUpdateGoal: (String) -> Unit,
    onToggleVibe: (String) -> Unit,
    onToggleConsideration: ((com.librelookai.settings.AiConsiderations) -> com.librelookai.settings.AiConsiderations) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SkyHero(
            destination = state.destination,
            suggestions = state.destinationSuggestions,
            geoLanguage = geoLanguage,
            onUpdateDestination = onUpdateDestination,
            onPickSuggestion = onPickSuggestion,
            onClearSuggestions = onClearDestinationSuggestions,
        )
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DatesAndDaysCard(
                startDate = state.startDate,
                days = state.days,
                onUpdateDays = onUpdateDays,
                onUpdateStartDate = onUpdateStartDate,
            )
            OutfitCountCard(
                days = state.days,
                outfitCount = state.outfitCount,
                onUpdateOutfitCount = onUpdateOutfitCount,
            )
            GoalAiCard(
                goal = state.goal,
                onUpdateGoal = onUpdateGoal,
            )
            VibeChips(
                selected = state.vibes,
                onToggle = onToggleVibe,
            )
            AiConsidersChips(
                considerations = considerations,
                onToggle = onToggleConsideration,
            )
            AiTagChips(
                considerations = considerations,
                onToggle = onToggleConsideration,
            )
}
    }
}

// Mirrors the OutfitComposer header style: close button + headlineSmall title + subtitle.
@Composable
internal fun PlannerHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Close, contentDescription = stringResource(DsR.string.action_close))
        }
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.travel_plan_title),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                stringResource(R.string.travel_plan_eyebrow),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkyHero(
    destination: String,
    suggestions: List<com.librelookai.weather.DestinationSuggestion>,
    geoLanguage: String,
    onUpdateDestination: (String, String) -> Unit,
    onPickSuggestion: (com.librelookai.weather.DestinationSuggestion) -> Unit,
    onClearSuggestions: () -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val keyboardController = LocalSoftwareKeyboardController.current
    Box(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            var suggestionsExpanded by remember { mutableStateOf(false) }
            val showSuggestions = suggestionsExpanded && suggestions.isNotEmpty()
            var editing by remember { mutableStateOf(destination.isBlank()) }

            // Glass destination card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.surface.copy(alpha = 0.87f))
                    .border(1.dp, palette.divider.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Place,
                        contentDescription = null,
                        tint = palette.textMuted,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.travel_label_destination).uppercase(),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textMuted,
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (editing || destination.isBlank()) {
                    ExposedDropdownMenuBox(
                        expanded = showSuggestions,
                        onExpandedChange = { suggestionsExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = destination,
                            onValueChange = {
                                onUpdateDestination(it, geoLanguage)
                                suggestionsExpanded = true
                            },
                            placeholder = { Text(stringResource(R.string.travel_destination_placeholder)) },
                            trailingIcon = if (destination.isNotEmpty()) {
                                { IconButton(onClick = {
                                    onUpdateDestination("", geoLanguage)
                                    suggestionsExpanded = false
                                }) { Icon(Icons.Default.Close, contentDescription = "Clear") } }
                            } else null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryEditable, enabled = true),
                        )
                        ExposedDropdownMenu(
                            expanded = showSuggestions,
                            onDismissRequest = { suggestionsExpanded = false },
                        ) {
                            suggestions.forEach { sug ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(sug.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                            val sub = listOf(sug.admin1, sug.country).filter { it.isNotBlank() }.joinToString(", ")
                                            if (sub.isNotEmpty()) {
                                                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    },
                                    onClick = {
                                        onPickSuggestion(sug)
                                        suggestionsExpanded = false
                                        editing = false
                                        keyboardController?.hide()
                                    },
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        destination,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                editing = true
                                onClearSuggestions()
                            }
                            .padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.surface)
            .border(1.dp, palette.divider, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatesAndDaysCard(
    startDate: LocalDate,
    days: Int,
    onUpdateDays: (Int) -> Unit,
    onUpdateStartDate: (LocalDate) -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    var showDialog by remember { mutableStateOf(false) }
    val endDate = startDate.plusDays((days - 1).toLong())
    val rangeFmt = DateTimeFormatter.ofPattern("MMM d")
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { showDialog = true },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CalendarMonth,
                        contentDescription = null,
                        tint = palette.textMuted,
                        modifier = Modifier.size(11.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.travel_label_dates).uppercase(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textMuted,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${startDate.format(rangeFmt)} → ${endDate.format(rangeFmt)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.text,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(32.dp)
                    .background(palette.divider),
            )
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.travel_label_days).uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textMuted,
                )
                Spacer(Modifier.height(4.dp))
                StepperRow(
                    value = days,
                    onDec = { onUpdateDays(days - 1) },
                    onInc = { onUpdateDays(days + 1) },
                    canDec = days > 1,
                    canInc = days < 21,
                    buttonSize = 24.dp,
                )
            }
        }
    }
    if (showDialog) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = startDate.toEpochDay() * 86_400_000L,
        )
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        onUpdateStartDate(LocalDate.ofEpochDay(it / 86_400_000L))
                    }
                    showDialog = false
                }) { Text(stringResource(DsR.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text(stringResource(DsR.string.action_cancel)) }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun OutfitCountCard(
    days: Int,
    outfitCount: Int?,
    onUpdateOutfitCount: (Int?) -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val effective = outfitCount ?: days
    val daysPerOutfit = if (effective > 0) {
        val perOutfit = days.toFloat() / effective.toFloat()
        if (perOutfit >= 1f) String.format("%.1f", perOutfit).trimEnd('0').trimEnd('.')
        else String.format("%.1f", perOutfit)
    } else "1"
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.primaryDim),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Checkroom,
                    contentDescription = null,
                    tint = palette.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.travel_label_outfits).uppercase(),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textMuted,
                )
                Text(
                    stringResource(R.string.travel_outfit_count_summary, effective, daysPerOutfit),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.text,
                )
            }
            StepperRow(
                value = effective,
                onDec = { onUpdateOutfitCount(effective - 1) },
                onInc = { onUpdateOutfitCount(effective + 1) },
                canDec = effective > 1,
                canInc = effective < 21,
                buttonSize = 28.dp,
            )
        }
    }
}

@Composable
private fun StepperRow(
    value: Int,
    onDec: () -> Unit,
    onInc: () -> Unit,
    canDec: Boolean,
    canInc: Boolean,
    buttonSize: androidx.compose.ui.unit.Dp,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StepperButton(symbol = "−", enabled = canDec, onClick = onDec, size = buttonSize)
        Text(
            value.toString(),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = palette.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 20.dp),
        )
        StepperButton(symbol = "+", enabled = canInc, onClick = onInc, size = buttonSize)
    }
}

@Composable
private fun StepperButton(
    symbol: String,
    enabled: Boolean,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(palette.surface2.copy(alpha = alpha))
            .border(1.dp, palette.border.copy(alpha = alpha), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = palette.textMid.copy(alpha = alpha),
        )
    }
}

