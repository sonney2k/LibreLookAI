package com.librelookai.outfit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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

private const val MONTH_PATTERN = "MMMM yyyy"
private const val SHEET_DATE_PATTERN = "EEEE, MMMM d"
private const val MAX_THUMBNAILS = 4

// The month grid is a HorizontalPager over a large, finite range of months so swiping reveals the
// adjacent month while dragging. The middle page maps to the anchor month captured at first compose.
private const val MONTH_PAGE_COUNT = 2400
private const val MONTH_PAGE_ANCHOR = MONTH_PAGE_COUNT / 2

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
        onMoveEvent = { event, date -> outfitEventsViewModel.moveEvent(event.id, date) },
        onMoveEvents = { ids, date -> outfitEventsViewModel.moveEvents(ids, date) },
        onCopyEvent = { event, date -> outfitEventsViewModel.copyEvent(event.id, date) },
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
    onMoveEvent: (OutfitEvent, LocalDate) -> Unit,
    onMoveEvents: (Set<String>, LocalDate) -> Unit,
    onCopyEvent: (OutfitEvent, LocalDate) -> Unit,
    onEditOutfit: (Outfit) -> Unit,
) {
    val itemsByDate = remember(wornItems) { wornItems.groupBy { it.date } }
    val today = remember { LocalDate.now() }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    // Long-press a day with outfits to start a move; the next tapped day is the destination.
    var movingDate by remember { mutableStateOf<LocalDate?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Swipe between months via a pager so the adjacent month slides in under the finger.
    val anchorMonth = remember { YearMonth.now() }
    val pagerState = rememberPagerState(initialPage = MONTH_PAGE_ANCHOR) { MONTH_PAGE_COUNT }
    val currentMonth = remember(pagerState.currentPage) {
        anchorMonth.plusMonths((pagerState.currentPage - MONTH_PAGE_ANCHOR).toLong())
    }

    Column(modifier = Modifier.fillMaxSize()) {
        MonthHeader(
            yearMonth = currentMonth,
            onPrev = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
            onNext = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
        )
        MoveBanner(active = movingDate != null, onCancel = { movingDate = null })
        DayOfWeekRow()
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { page ->
            val pageMonth = remember(page) {
                anchorMonth.plusMonths((page - MONTH_PAGE_ANCHOR).toLong())
            }
            val pageWeeks = remember(pageMonth) { buildCalendarWeeks(pageMonth) }
            Column(modifier = Modifier.fillMaxSize()) {
                pageWeeks.forEach { week ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        week.forEach { date ->
                            val hasStyles = date != null && outfitsByDate[date].orEmpty().isNotEmpty()
                            val inMoveMode = movingDate != null
                            DayCell(
                                date = date,
                                items = if (date != null) itemsByDate[date].orEmpty() else emptyList(),
                                isCurrentMonth = date?.month == pageMonth.month,
                                isToday = date == today,
                                isMoveSource = date != null && date == movingDate,
                                onClick = when {
                                    date == null -> null
                                    inMoveMode -> {
                                        {
                                            val from = movingDate
                                            if (from != null && date != from) {
                                                val ids = eventsByDate[from].orEmpty().map { it.id }.toSet()
                                                onMoveEvents(ids, date)
                                            }
                                            movingDate = null
                                        }
                                    }
                                    hasStyles -> { { selectedDate = date } }
                                    else -> null
                                },
                                onLongClick = if (hasStyles && movingDate == null) {
                                    { movingDate = date }
                                } else null,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }

    // A ModalBottomSheet opens its own window and severs the locale-overridden context chain, so
    // LocalContext / LocalConfiguration must be re-provided inside (CLAUDE.md → Dialog quirks).
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val locale = parentConfiguration.locales[0]

    // A pending move/copy keyed by the event; non-null shows the date picker (true = copy).
    var pendingDateAction by remember { mutableStateOf<Pair<OutfitEvent, Boolean>?>(null) }

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
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp),
                ) {
                    Text(
                        text = date.format(DateTimeFormatter.ofPattern(SHEET_DATE_PATTERN, locale)),
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
                                onMove = { pendingDateAction = event to false },
                                onCopy = { pendingDateAction = event to true },
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

    pendingDateAction?.let { (event, isCopy) ->
        val sourceDate = runCatching { LocalDate.parse(event.date) }.getOrNull() ?: today
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = sourceDate.toEpochDay() * 86_400_000L,
        )
        DatePickerDialog(
            onDismissRequest = { pendingDateAction = null },
            confirmButton = {
                CompositionLocalProvider(
                    LocalContext provides parentContext,
                    LocalConfiguration provides parentConfiguration,
                ) {
                    TextButton(onClick = {
                        datePickerState.selectedDateMillis?.let {
                            val target = LocalDate.ofEpochDay(it / 86_400_000L)
                            if (isCopy) onCopyEvent(event, target) else onMoveEvent(event, target)
                        }
                        pendingDateAction = null
                        scope.launch { sheetState.hide() }.invokeOnCompletion { selectedDate = null }
                    }) { Text(stringResource(R.string.action_ok)) }
                }
            },
            dismissButton = {
                CompositionLocalProvider(
                    LocalContext provides parentContext,
                    LocalConfiguration provides parentConfiguration,
                ) {
                    TextButton(onClick = { pendingDateAction = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        ) {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                DatePicker(state = datePickerState)
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
    onMove: () -> Unit,
    onCopy: () -> Unit,
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
                var menuOpen by remember { mutableStateOf(false) }
                // A DropdownMenu opens its own Popup window, severing the locale-overridden context
                // chain, so re-provide it inside (CLAUDE.md → Dialog quirks).
                val menuContext = LocalContext.current
                val menuConfiguration = LocalConfiguration.current
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.calendar_more_options),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        CompositionLocalProvider(
                            LocalContext provides menuContext,
                            LocalConfiguration provides menuConfiguration,
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.calendar_move_to_day)) },
                                onClick = { menuOpen = false; onMove() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.calendar_copy_to_day)) },
                                onClick = { menuOpen = false; onCopy() },
                            )
                        }
                    }
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
        val locale = LocalConfiguration.current.locales[0]
        IconButton(onClick = onPrev) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
        }
        Text(
            text = yearMonth.format(DateTimeFormatter.ofPattern(MONTH_PATTERN, locale)),
            style = MaterialTheme.typography.titleMedium,
        )
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
        }
    }
}

/** Instruction bar shown while a day's outfit is armed for a long-press move. */
@Composable
private fun MoveBanner(active: Boolean, onCancel: () -> Unit) {
    if (!active) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.calendar_move_prompt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCancel) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.action_cancel),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayCell(
    date: LocalDate?,
    items: List<WornItem>,
    isCurrentMonth: Boolean,
    isToday: Boolean,
    isMoveSource: Boolean,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(
                        onClick = { onClick?.invoke() },
                        onLongClick = onLongClick,
                    )
                } else Modifier,
            )
            .then(
                if (isMoveSource) Modifier.border(2.dp, MaterialTheme.colorScheme.primary)
                else Modifier,
            )
            .padding(3.dp),
    ) {
        if (date == null) return@Box

        // Worn-item thumbnails fill the (padded) cell behind the day number.
        if (items.isNotEmpty()) {
            DayThumbnails(
                items = items,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(4.dp)),
            )
        }

        // Day number floats on top of the imagery — given a translucent backing pill (or the
        // primary circle when it is today) so it stays legible over the photos behind it.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(2.dp)
                .size(18.dp)
                .then(
                    when {
                        isToday -> Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                        items.isNotEmpty() -> Modifier.background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            CircleShape,
                        )
                        else -> Modifier
                    },
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

        if (items.size > MAX_THUMBNAILS) {
            Text(
                text = "+${items.size - MAX_THUMBNAILS}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .padding(horizontal = 3.dp),
            )
        }
    }
}

/** Lays out up to [MAX_THUMBNAILS] worn-item thumbnails so they fill the whole calendar cell. */
@Composable
private fun DayThumbnails(items: List<WornItem>, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val shown = items.take(MAX_THUMBNAILS)

    @Composable
    fun cell(item: WornItem, cellModifier: Modifier) {
        AsyncImage(
            model = remember(item.imagePath) {
                ImageRequest.Builder(ctx).data(item.imagePath).build()
            },
            contentDescription = item.label.ifEmpty { null },
            modifier = cellModifier,
            contentScale = ContentScale.Crop,
        )
    }

    when (shown.size) {
        1 -> Box(modifier) { cell(shown[0], Modifier.fillMaxSize()) }
        2 -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            cell(shown[0], Modifier.weight(1f).fillMaxHeight())
            cell(shown[1], Modifier.weight(1f).fillMaxHeight())
        }
        else -> Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                cell(shown[0], Modifier.weight(1f).fillMaxHeight())
                cell(shown[1], Modifier.weight(1f).fillMaxHeight())
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                cell(shown[2], Modifier.weight(1f).fillMaxHeight())
                if (shown.size > 3) cell(shown[3], Modifier.weight(1f).fillMaxHeight())
                else Spacer(Modifier.weight(1f))
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

