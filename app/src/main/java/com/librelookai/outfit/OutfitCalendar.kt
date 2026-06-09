package com.librelookai.outfit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FavoriteBorder
import com.librelookai.ui.components.AppFab
import com.librelookai.ui.components.SelectionAction
import com.librelookai.ui.components.SelectionActionBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
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
import com.librelookai.data.model.WearSource
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

/**
 * An in-progress "tap a day in the grid to choose the destination" action. Replaces the old
 * Material date-picker dialogs: while one is active the calendar grid itself is the picker (so the
 * user sees their previously-worn outfits as context), every day cell is tappable, and the day
 * sheet / FAB / selection bar are suppressed. See [CalendarContent].
 */
private sealed interface CalendarPick {
    /** Log a wear of [outfit] on the tapped day. */
    data class Wear(val outfit: Outfit, val source: WearSource) : CalendarPick
    /** Move every event in [ids] (one day's worth, or a single event) to the tapped day. */
    data class MoveEvents(val ids: Set<String>) : CalendarPick
    /** Copy every event in [ids] to the tapped day. */
    data class CopyEvents(val ids: Set<String>) : CalendarPick
}

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

    // A Wear action on the outfit list / detail viewer switches here and asks the calendar to enter
    // "tap a day to wear this outfit" mode (so the day is picked in context). Resolve + hand it off.
    val externalWear = remember(outfitsState.pendingCalendarWearId, outfitsState.pendingCalendarWearSource, outfitsById) {
        outfitsState.pendingCalendarWearId
            ?.let { outfitsById[it] }
            ?.let { it to outfitsState.pendingCalendarWearSource }
    }

    CalendarContent(
        wornItems = wornItems,
        outfitsByDate = outfitsByDate,
        eventsByDate = eventsByDate,
        outfitsById = outfitsById,
        allOutfits = outfitsState.outfits,
        imagesById = imagesById,
        externalWear = externalWear,
        onExternalWearConsumed = stylesViewModel::consumeCalendarWear,
        onAddOutfit = { outfit, date, source ->
            outfitEventsViewModel.recordOutfit(outfit, imagesById, source = source, weather = weatherState.data, date = date)
        },
        onWearAgainToday = { outfit ->
            outfitEventsViewModel.recordOutfit(outfit, imagesById, weather = weatherState.data)
        },
        onToggleLoved = { event -> outfitEventsViewModel.setEventLoved(event.id, !event.loved) },
        onMoveEvents = { ids, date -> outfitEventsViewModel.moveEvents(ids, date) },
        onCopyEvents = { ids, date -> outfitEventsViewModel.copyEvents(ids, date) },
        onDeleteEvent = { event -> outfitEventsViewModel.deleteEvent(event.id) },
        onDeleteEvents = { ids -> outfitEventsViewModel.deleteEvents(ids) },
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
    allOutfits: List<Outfit>,
    imagesById: Map<String, DriveImage>,
    externalWear: Pair<Outfit, WearSource>?,
    onExternalWearConsumed: () -> Unit,
    onAddOutfit: (Outfit, LocalDate, WearSource) -> Unit,
    onWearAgainToday: (Outfit) -> Unit,
    onToggleLoved: (OutfitEvent) -> Unit,
    onMoveEvents: (Set<String>, LocalDate) -> Unit,
    onCopyEvents: (Set<String>, LocalDate) -> Unit,
    onDeleteEvent: (OutfitEvent) -> Unit,
    onDeleteEvents: (Set<String>) -> Unit,
    onEditOutfit: (Outfit) -> Unit,
) {
    val itemsByDate = remember(wornItems) { wornItems.groupBy { it.date } }
    val today = remember { LocalDate.now() }
    val isOffline = LocalIsOffline.current
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    // Long-pressing a day selects it (fabMenuDay) and raises the shared SelectionActionBar; its
    // copy/move/remove then act on that whole day's outfits, and "add" records onto it.
    var fabMenuDay by remember { mutableStateOf<LocalDate?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    // Non-null once a day is picked for deletion — shows the remove-confirm dialog.
    var pendingDeleteDate by remember { mutableStateOf<LocalDate?>(null) }
    // Active "tap a day to wear/move/copy" request — the calendar grid itself becomes the picker.
    var pickMode by remember { mutableStateOf<CalendarPick?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // A ModalBottomSheet / Popup opens its own window and severs the locale-overridden context chain,
    // so LocalContext / LocalConfiguration must be re-provided inside (CLAUDE.md → Dialog quirks).
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val locale = parentConfiguration.locales[0]
    val sheetDateFormatter = remember(locale) { DateTimeFormatter.ofPattern(SHEET_DATE_PATTERN, locale) }

    // Snackbar confirmations shown after a day is tapped to complete a pick (templates take the day).
    val doneWearTemplate = stringResource(R.string.calendar_done_wear)
    val doneMoveTemplate = stringResource(R.string.calendar_done_move)
    val doneCopyTemplate = stringResource(R.string.calendar_done_copy)

    // Completes the active pick on [date]: performs the wear/move/copy, exits pick mode, confirms.
    val completePick: (LocalDate) -> Unit = { date ->
        val template = when (val pick = pickMode) {
            is CalendarPick.Wear -> { onAddOutfit(pick.outfit, date, pick.source); doneWearTemplate }
            is CalendarPick.MoveEvents -> { onMoveEvents(pick.ids, date); doneMoveTemplate }
            is CalendarPick.CopyEvents -> { onCopyEvents(pick.ids, date); doneCopyTemplate }
            null -> null
        }
        pickMode = null
        if (template != null) {
            val dateStr = date.format(sheetDateFormatter)
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(String.format(template, dateStr))
            }
        }
    }

    // A Wear action on the outfit list / detail viewer hands an outfit here to be worn on a tapped day.
    LaunchedEffect(externalWear) {
        externalWear?.let { (outfit, source) ->
            selectedDate = null
            fabMenuDay = null
            pickMode = CalendarPick.Wear(outfit, source)
            onExternalWearConsumed()
        }
    }
    if (pickMode != null) BackHandler { pickMode = null }

    // Swipe between months via a pager so the adjacent month slides in under the finger.
    val anchorMonth = remember { YearMonth.now() }
    val pagerState = rememberPagerState(initialPage = MONTH_PAGE_ANCHOR) { MONTH_PAGE_COUNT }
    val currentMonth = remember(pagerState.currentPage) {
        anchorMonth.plusMonths((pagerState.currentPage - MONTH_PAGE_ANCHOR).toLong())
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        // While a pick is active the grid is the date picker — show a prompt banner with a cancel.
        pickMode?.let { pick ->
            val bannerText = when (pick) {
                is CalendarPick.Wear -> stringResource(R.string.calendar_wear_prompt, pick.outfit.name)
                is CalendarPick.MoveEvents -> stringResource(R.string.calendar_move_prompt)
                is CalendarPick.CopyEvents -> stringResource(R.string.calendar_copy_prompt)
            }
            Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        bannerText,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { pickMode = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
        MonthHeader(
            yearMonth = currentMonth,
            onPrev = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
            onNext = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
        )
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
                            val inPickMode = pickMode != null
                            DayCell(
                                date = date,
                                items = if (date != null) itemsByDate[date].orEmpty() else emptyList(),
                                isCurrentMonth = date?.month == pageMonth.month,
                                isToday = date == today,
                                // In pick mode any day completes the wear/move/copy. Otherwise tap a
                                // day with outfits to inspect it; long-press to raise the shared
                                // selection bar (copy/move/remove) for that day's outfits.
                                onClick = when {
                                    date == null -> null
                                    inPickMode -> { { completePick(date) } }
                                    hasStyles -> { { selectedDate = date } }
                                    else -> null
                                },
                                onLongClick = if (!inPickMode && date != null && hasStyles && !isOffline) {
                                    { fabMenuDay = date }
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

    // Shared create FAB — "Log outfit". A tap adds an outfit to a date of your choosing. The
    // calendar doesn't scroll vertically (it's a month pager), so the pill stays expanded. Hidden
    // offline (every action is a Drive write), while a day is selected for actions, or mid-pick.
    AppFab(
        label = stringResource(R.string.fab_calendar_log),
        icon = Icons.Default.CalendarMonth,
        onClick = { showAddSheet = true },
        expanded = true,
        visible = !isOffline && fabMenuDay == null && pickMode == null,
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
    )

    // Long-press a day → shared selection bar for that day's outfits: Copy (primary) · Move · Remove.
    val daySelected = fabMenuDay
    if (daySelected != null) BackHandler { fabMenuDay = null }
    SelectionActionBar(
        count = daySelected?.let { outfitsByDate[it].orEmpty().size } ?: 0,
        onClear = { fabMenuDay = null },
        actions = if (daySelected != null && !isOffline) {
            listOf(
                SelectionAction(
                    label = stringResource(R.string.sel_copy_to_day),
                    icon = Icons.Default.ContentCopy,
                    kind = SelectionAction.Kind.Primary,
                ) {
                    val ids = eventsByDate[daySelected].orEmpty().map { it.id }.toSet()
                    pickMode = CalendarPick.CopyEvents(ids); fabMenuDay = null
                },
                SelectionAction(
                    label = stringResource(R.string.sel_move),
                    icon = Icons.Default.EditCalendar,
                ) {
                    val ids = eventsByDate[daySelected].orEmpty().map { it.id }.toSet()
                    pickMode = CalendarPick.MoveEvents(ids); fabMenuDay = null
                },
                SelectionAction(
                    label = stringResource(R.string.sel_remove),
                    icon = Icons.Default.Delete,
                    kind = SelectionAction.Kind.Danger,
                ) { pendingDeleteDate = daySelected; fabMenuDay = null },
            )
        } else {
            emptyList()
        },
        visible = daySelected != null && !isOffline,
        modifier = Modifier.align(Alignment.BottomCenter),
    )

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
                                onMove = {
                                    pickMode = CalendarPick.MoveEvents(setOf(event.id))
                                    scope.launch { sheetState.hide() }.invokeOnCompletion { selectedDate = null }
                                },
                                onCopy = {
                                    pickMode = CalendarPick.CopyEvents(setOf(event.id))
                                    scope.launch { sheetState.hide() }.invokeOnCompletion { selectedDate = null }
                                },
                                onDelete = {
                                    onDeleteEvent(event)
                                    // Close the sheet when removing the day's last outfit.
                                    if (eventsOnDay.size <= 1) {
                                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                                            selectedDate = null
                                        }
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

    // FAB ▸ "Add outfit to calendar" — browse saved outfits in the full Outfits picker (filter +
    // sort), pick one, then tap a day on the grid to log it. Reuses the shared OutfitPickerDialog;
    // calendar logging only records a reference, so it shows every outfit (no all-items-loaded gate)
    // and drops the closet/settings header actions that only make sense on try-on/compose surfaces.
    if (showAddSheet) {
        com.librelookai.tryon.OutfitPickerDialog(
            outfits = allOutfits,
            wardrobeImages = remember(imagesById) { imagesById.values.toList() },
            title = stringResource(R.string.calendar_add_pick),
            emptyText = stringResource(R.string.calendar_add_empty),
            requireAllItemsLoaded = false,
            showHeaderActions = false,
            onPick = { outfit ->
                pickMode = CalendarPick.Wear(outfit, WearSource.MANUAL)
                showAddSheet = false
            },
            onDismiss = { showAddSheet = false },
        )
    }

    // FAB ▸ "Remove outfit from calendar" — confirm clearing every wear logged on the picked day.
    pendingDeleteDate?.let { date ->
        AlertDialog(
            onDismissRequest = { pendingDeleteDate = null },
            title = {
                CompositionLocalProvider(
                    LocalContext provides parentContext,
                    LocalConfiguration provides parentConfiguration,
                ) { Text(stringResource(R.string.calendar_delete_confirm_title)) }
            },
            text = {
                CompositionLocalProvider(
                    LocalContext provides parentContext,
                    LocalConfiguration provides parentConfiguration,
                ) {
                    Text(
                        stringResource(
                            R.string.calendar_delete_confirm_msg,
                            date.format(DateTimeFormatter.ofPattern(SHEET_DATE_PATTERN, locale)),
                        ),
                    )
                }
            },
            confirmButton = {
                CompositionLocalProvider(
                    LocalContext provides parentContext,
                    LocalConfiguration provides parentConfiguration,
                ) {
                    TextButton(onClick = {
                        val ids = eventsByDate[date].orEmpty().map { it.id }.toSet()
                        onDeleteEvents(ids)
                        pendingDeleteDate = null
                    }) {
                        Text(
                            stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            dismissButton = {
                CompositionLocalProvider(
                    LocalContext provides parentContext,
                    LocalConfiguration provides parentConfiguration,
                ) {
                    TextButton(onClick = { pendingDeleteDate = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }

    // Confirmation after a tapped-day pick (wear / move / copy). Sits above the nav bar.
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(16.dp),
    ) { data -> Snackbar(snackbarData = data) }
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
    onDelete: () -> Unit,
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
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.calendar_remove),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = { menuOpen = false; onDelete() },
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

