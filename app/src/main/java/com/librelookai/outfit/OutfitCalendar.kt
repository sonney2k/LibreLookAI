package com.librelookai.outfit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.librelookai.R
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.OutfitEvent
import com.librelookai.data.model.WornItem
import com.librelookai.util.LocalIsOffline
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.WardrobeViewModel
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val MONTH_FORMATTER = DateTimeFormatter.ofPattern("MMMM yyyy")
private val SHEET_DATE_FORMATTER = DateTimeFormatter.ofPattern("EEEE, MMMM d")
private const val MAX_THUMBNAILS = 4
private val THUMBNAIL_SIZE = 14.dp

// ============================================================================
//  Calendar — monthly grid of worn outfits (former Insights "Calendar" tab).
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutfitCalendarTab(
    outfitEventsViewModel: OutfitEventsViewModel = viewModel(),
    stylesViewModel: OutfitsViewModel = viewModel(),
    wardrobeViewModel: WardrobeViewModel = viewModel(),
    weatherViewModel: com.librelookai.weather.WeatherViewModel = viewModel(),
    onEditOutfit: (Outfit) -> Unit,
) {
    val outfitEventsState by outfitEventsViewModel.state.collectAsState()
    val outfitsState by stylesViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val weatherState by weatherViewModel.state.collectAsState()

    val outfitsById = remember(outfitsState.outfits) { outfitsState.outfits.associateBy { it.id } }
    val imagesById = remember(wardrobeState.images) { wardrobeState.images.associateBy { it.driveId } }

    // One row per logged wear (so the same outfit worn twice in a day shows twice, each with its
    // own "loved" toggle), resolved against the current outfits.
    val eventsByDate = remember(outfitEventsState.events) {
        outfitEventsState.events.mapNotNull { event ->
            val date = runCatching { LocalDate.parse(event.date) }.getOrNull() ?: return@mapNotNull null
            date to event
        }.groupBy({ it.first }, { it.second })
    }

    val wornItems = remember(outfitEventsState.events, outfitsById, imagesById) {
        outfitEventsState.events.flatMap { event ->
            val style = outfitsById[event.outfitId] ?: return@flatMap emptyList<WornItem>()
            val date = runCatching { LocalDate.parse(event.date) }.getOrNull()
                ?: return@flatMap emptyList()
            style.itemIds.mapNotNull { itemId ->
                val image = imagesById[itemId] ?: return@mapNotNull null
                WornItem(
                    date = date,
                    imagePath = image.localPath,
                    label = image.tags?.label?.ifEmpty { null } ?: image.tags?.type?.ifEmpty { null } ?: image.name,
                )
            }
        }
    }

    val outfitsByDate = remember(outfitEventsState.events, outfitsById) {
        outfitEventsState.events.mapNotNull { event ->
            val style = outfitsById[event.outfitId] ?: return@mapNotNull null
            val date = runCatching { LocalDate.parse(event.date) }.getOrNull()
                ?: return@mapNotNull null
            date to style
        }.groupBy({ it.first }, { it.second })
    }

    CalendarContent(
        wornItems = wornItems,
        outfitsByDate = outfitsByDate,
        eventsByDate = eventsByDate,
        outfitsById = outfitsById,
        imagesById = imagesById,
        onWearAgainToday = { outfit ->
            outfitEventsViewModel.recordOutfit(outfit, imagesById, weather = weatherState.data)
        },
        onToggleLoved = { event -> outfitEventsViewModel.setEventLoved(event.id, !event.loved) },
        onEditOutfit = onEditOutfit,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarContent(
    wornItems: List<WornItem>,
    outfitsByDate: Map<LocalDate, List<Outfit>>,
    eventsByDate: Map<LocalDate, List<OutfitEvent>>,
    outfitsById: Map<String, Outfit>,
    imagesById: Map<String, DriveImage>,
    onWearAgainToday: (Outfit) -> Unit,
    onToggleLoved: (OutfitEvent) -> Unit,
    onEditOutfit: (Outfit) -> Unit,
) {
    var yearMonth by rememberSaveable { mutableStateOf(YearMonth.now()) }
    val itemsByDate = remember(wornItems) { wornItems.groupBy { it.date } }
    val weeks = remember(yearMonth) { buildCalendarWeeks(yearMonth) }
    val today = remember { LocalDate.now() }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        MonthHeader(
            yearMonth = yearMonth,
            onPrev = { yearMonth = yearMonth.minusMonths(1) },
            onNext = { yearMonth = yearMonth.plusMonths(1) },
        )
        DayOfWeekRow()
        Column(modifier = Modifier.fillMaxSize()) {
            weeks.forEach { week ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    week.forEach { date ->
                        val hasStyles = date != null && outfitsByDate[date].orEmpty().isNotEmpty()
                        DayCell(
                            date = date,
                            items = if (date != null) itemsByDate[date].orEmpty() else emptyList(),
                            isCurrentMonth = date?.month == yearMonth.month,
                            isToday = date == today,
                            onClick = if (hasStyles) { { selectedDate = date } } else null,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }

    selectedDate?.let { date ->
        // One row per logged wear, pairing the event (for the loved toggle) with its outfit.
        val eventsOnDay = remember(eventsByDate, outfitsById, date) {
            eventsByDate[date].orEmpty().mapNotNull { event ->
                outfitsById[event.outfitId]?.let { event to it }
            }
        }
        ModalBottomSheet(
            onDismissRequest = { selectedDate = null },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    text = date.format(SHEET_DATE_FORMATTER),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                HorizontalDivider()
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    itemsIndexed(eventsOnDay, key = { index, (event, _) -> "${date}_${event.id}_$index" }) { index, (event, style) ->
                        OutfitSheetRow(
                            style = style,
                            loved = event.loved,
                            imagesById = imagesById,
                            onToggleLoved = { onToggleLoved(event) },
                            onWearAgainToday = {
                                onWearAgainToday(style)
                                scope.launch { sheetState.hide() }.invokeOnCompletion {
                                    selectedDate = null
                                }
                            },
                            onEditOutfit = {
                                scope.launch { sheetState.hide() }.invokeOnCompletion {
                                    selectedDate = null
                                    onEditOutfit(style)
                                }
                            },
                        )
                        if (index < eventsOnDay.lastIndex) HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun OutfitSheetRow(
    style: Outfit,
    loved: Boolean,
    imagesById: Map<String, DriveImage>,
    onToggleLoved: () -> Unit,
    onWearAgainToday: () -> Unit,
    onEditOutfit: () -> Unit,
) {
    val ctx = LocalContext.current
    val styleItems = style.itemIds.mapNotNull { imagesById[it] }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                style.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (!LocalIsOffline.current) {
                IconButton(onClick = onToggleLoved) {
                    Icon(
                        imageVector = if (loved) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = stringResource(R.string.calendar_loved),
                        tint = if (loved) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (style.description.isNotEmpty()) {
            Text(
                style.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (styleItems.isEmpty()) {
            Text(
                stringResource(R.string.insights_items_removed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(styleItems, key = { index, image -> "${image.driveId}_${index}" }) { index, image ->
                    AsyncImage(
                        model = remember(image.driveId, image.version) {
                            ImageRequest.Builder(ctx)
                                .data(image.localPath)
                                .memoryCacheKey("${image.driveId}_${image.version}")
                                .build()
                        },
                        contentDescription = image.name,
                        modifier = Modifier
                            .size(120.dp)
                            .clip(MaterialTheme.shapes.medium),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
        if (!LocalIsOffline.current) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onWearAgainToday,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.calendar_wear_again))
                }
                OutlinedButton(
                    onClick = onEditOutfit,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_edit))
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(
    yearMonth: YearMonth,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
        }
        Text(
            text = yearMonth.format(MONTH_FORMATTER),
            style = MaterialTheme.typography.titleMedium,
        )
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
        }
    }
}

@Composable
private fun DayOfWeekRow() {
    val labels = listOf(
        stringResource(R.string.calendar_day_mo),
        stringResource(R.string.calendar_day_tu),
        stringResource(R.string.calendar_day_we),
        stringResource(R.string.calendar_day_th),
        stringResource(R.string.calendar_day_fr),
        stringResource(R.string.calendar_day_sa),
        stringResource(R.string.calendar_day_su),
    )
    Row(modifier = Modifier.fillMaxWidth()) {
        labels.forEach { label ->
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayCell(
    date: LocalDate?,
    items: List<WornItem>,
    isCurrentMonth: Boolean,
    isToday: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    Box(
        modifier = modifier
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        if (date == null) return@Box

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .then(
                        if (isToday) Modifier.background(
                            MaterialTheme.colorScheme.primary,
                            CircleShape,
                        ) else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        isToday -> MaterialTheme.colorScheme.onPrimary
                        !isCurrentMonth -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }

            if (items.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                    maxItemsInEachRow = 2,
                ) {
                    items.take(MAX_THUMBNAILS).forEach { item ->
                        AsyncImage(
                            model = remember(item.imagePath) {
                                ImageRequest.Builder(ctx)
                                    .data(item.imagePath)
                                    .build()
                            },
                            contentDescription = item.label.ifEmpty { null },
                            modifier = Modifier
                                .size(THUMBNAIL_SIZE)
                                .clip(RoundedCornerShape(2.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                if (items.size > MAX_THUMBNAILS) {
                    Text(
                        text = "+${items.size - MAX_THUMBNAILS}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun buildCalendarWeeks(yearMonth: YearMonth): List<List<LocalDate?>> {
    val firstDay = yearMonth.atDay(1)
    val leadingBlanks = firstDay.dayOfWeek.value - 1
    val cells = mutableListOf<LocalDate?>()
    repeat(leadingBlanks) { cells.add(null) }
    for (day in 1..yearMonth.lengthOfMonth()) {
        cells.add(yearMonth.atDay(day))
    }
    while (cells.size % 7 != 0) cells.add(null)
    return cells.chunked(7)
}

// ============================================================================
//  Wear stats — most-worn outfits and items (former Insights "Calendar Stats").
// ============================================================================

