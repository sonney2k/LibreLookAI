package com.librelookai

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val TOP_N = 10
private val MONTH_FORMATTER = DateTimeFormatter.ofPattern("MMMM yyyy")
private val SHEET_DATE_FORMATTER = DateTimeFormatter.ofPattern("EEEE, MMMM d")
private const val MAX_THUMBNAILS = 4
private val THUMBNAIL_SIZE = 14.dp

@Composable
fun InsightsScreen(
    wardrobeViewModel: WardrobeViewModel = viewModel(),
    outfitEventsViewModel: OutfitEventsViewModel = viewModel(),
    stylesViewModel: OutfitsViewModel = viewModel(),
    tryOnViewModel: TryOnViewModel = viewModel(),
    locationViewModel: LocationViewModel = viewModel(),
    onEditOutfit: (Outfit) -> Unit = {},
    onSettingsClick: () -> Unit = {},
    navResetTick: Int = 0,
    modifier: Modifier = Modifier,
) {
    val locationState by locationViewModel.state.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(navResetTick) { selectedTab = 0 }

    Column(modifier = modifier.fillMaxSize()) {
        AppScreenHeader(
            title = stringResource(R.string.insights_title),
            leadingIcon = Icons.Default.Insights,
            trailingContent = {
                LocationButton(
                    locations = locationState.locations,
                    activeLocationId = locationState.activeLocationId,
                    onSetActiveLocation = locationViewModel::setActiveLocation,
                )
            },
            onSettingsClick = onSettingsClick,
        )

        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text(stringResource(R.string.insights_tab_calendar)) },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(stringResource(R.string.insights_tab_calendar_stats)) },
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text(stringResource(R.string.insights_tab_wardrobe_stats)) },
            )
            Tab(
                selected = selectedTab == 3,
                onClick = { selectedTab = 3 },
                text = { Text(stringResource(R.string.insights_tab_costs)) },
            )
        }

        when (selectedTab) {
            0 -> CalendarTab(
                outfitEventsViewModel = outfitEventsViewModel,
                stylesViewModel = stylesViewModel,
                wardrobeViewModel = wardrobeViewModel,
                onEditOutfit = onEditOutfit,
            )
            1 -> CalendarStatsTab(
                outfitEventsViewModel = outfitEventsViewModel,
                stylesViewModel = stylesViewModel,
                wardrobeViewModel = wardrobeViewModel,
            )
            2 -> WardrobeStatsTab(wardrobeViewModel = wardrobeViewModel)
            3 -> CostsTab(
                wardrobeViewModel = wardrobeViewModel,
                stylesViewModel = stylesViewModel,
                outfitEventsViewModel = outfitEventsViewModel,
                tryOnViewModel = tryOnViewModel,
            )
        }
    }
}

// ============================================================================
//  Tab 4: Costs (token usage + activity counts)
// ============================================================================

@Composable
private fun CostsTab(
    wardrobeViewModel: WardrobeViewModel,
    stylesViewModel: OutfitsViewModel,
    outfitEventsViewModel: OutfitEventsViewModel,
    tryOnViewModel: TryOnViewModel,
) {
    val ctx = LocalContext.current
    val usageVm: UsageViewModel = viewModel()
    val wardrobe by wardrobeViewModel.state.collectAsState()
    val outfits by stylesViewModel.state.collectAsState()
    val outfitEvents by outfitEventsViewModel.state.collectAsState()
    val tryOnState by tryOnViewModel.state.collectAsState()
    val usageEvents by usageVm.events.collectAsState()

    val days = 14
    val outfitsDaily = remember(outfits.outfits, days) {
        dailyCounts(outfits.outfits.map { it.createdAt }, days)
    }
    val tryOnsDaily = remember(tryOnState.history, days) {
        dailyCounts(tryOnState.history.map { it.createdAt }, days)
    }
    val wearsDaily = remember(outfitEvents.events, days) {
        dailyCounts(
            outfitEvents.events.mapNotNull { e ->
                runCatching {
                    LocalDate.parse(e.date)
                        .atStartOfDay(ZoneId.systemDefault())
                        .toInstant().toEpochMilli()
                }.getOrNull()
            },
            days,
        )
    }
    val importsDaily = remember(usageEvents, days) {
        dailyCounts(
            usageEvents.filter { it.category == UsageCategory.BG_REMOVAL }.map { it.timestampMs },
            days,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CostsCountCard(stringResource(R.string.costs_count_items), wardrobe.images.size, Modifier.weight(1f))
            CostsCountCard(stringResource(R.string.costs_count_outfits), outfits.outfits.size, Modifier.weight(1f))
            CostsCountCard(stringResource(R.string.costs_count_tryons), tryOnState.history.size, Modifier.weight(1f))
            CostsCountCard(stringResource(R.string.costs_count_wears), outfitEvents.events.size, Modifier.weight(1f))
        }

        Text(
            stringResource(R.string.costs_activity_14d),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        ActivityRow(stringResource(R.string.costs_count_imports), importsDaily, MaterialTheme.colorScheme.primary)
        ActivityRow(stringResource(R.string.costs_count_outfits), outfitsDaily, MaterialTheme.colorScheme.tertiary)
        ActivityRow(stringResource(R.string.costs_count_tryons), tryOnsDaily, MaterialTheme.colorScheme.secondary)
        ActivityRow(stringResource(R.string.costs_count_wears), wearsDaily, MaterialTheme.colorScheme.primary)

        UsageSection(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun CostsCountCard(label: String, count: Int, modifier: Modifier = Modifier) {
    OutlinedCard(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ActivityRow(label: String, daily: List<Pair<Long, Int>>, color: Color) {
    val total = daily.sumOf { it.second }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(80.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        MiniBarChart(
            daily = daily,
            color = color,
            modifier = Modifier
                .weight(1f)
                .height(36.dp),
        )
        Text(
            total.toString(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun MiniBarChart(daily: List<Pair<Long, Int>>, color: Color, modifier: Modifier = Modifier) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val maxVal = (daily.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    Canvas(modifier = modifier) {
        val bars = daily.size
        if (bars == 0) return@Canvas
        val gap = 2.dp.toPx()
        val barW = (size.width - gap * (bars - 1)) / bars
        drawRect(
            color = gridColor,
            topLeft = Offset(0f, size.height - 1f),
            size = Size(size.width, 1f),
        )
        daily.forEachIndexed { i, (_, v) ->
            val h = (v.toFloat() / maxVal) * (size.height - 2f)
            val x = i * (barW + gap)
            val y = size.height - h
            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barW, h.coerceAtLeast(0.5f)),
                cornerRadius = CornerRadius(2f, 2f),
            )
        }
    }
}

private fun dailyCounts(timestampsMs: List<Long>, days: Int): List<Pair<Long, Int>> {
    val cal = java.util.Calendar.getInstance()
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    val todayStart = cal.timeInMillis
    val dayMs = TimeUnit.DAYS.toMillis(1)
    val windowStart = todayStart - (days - 1) * dayMs
    val buckets = IntArray(days)
    for (ts in timestampsMs) {
        if (ts < windowStart) continue
        val idx = ((ts - windowStart) / dayMs).toInt()
        if (idx in 0 until days) buckets[idx] += 1
    }
    return (0 until days).map { i -> (windowStart + i * dayMs) to buckets[i] }
}

// ============================================================================
//  Tab 1: Calendar (former standalone CalendarScreen body)
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarTab(
    outfitEventsViewModel: OutfitEventsViewModel,
    stylesViewModel: OutfitsViewModel,
    wardrobeViewModel: WardrobeViewModel,
    onEditOutfit: (Outfit) -> Unit,
) {
    val outfitEventsState by outfitEventsViewModel.state.collectAsState()
    val outfitsState by stylesViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()

    val outfitsById = remember(outfitsState.outfits) { outfitsState.outfits.associateBy { it.id } }
    val imagesById = remember(wardrobeState.images) { wardrobeState.images.associateBy { it.driveId } }

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
        imagesById = imagesById,
        onWearAgainToday = { outfitEventsViewModel.recordOutfit(it) },
        onEditOutfit = onEditOutfit,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarContent(
    wornItems: List<WornItem>,
    outfitsByDate: Map<LocalDate, List<Outfit>>,
    imagesById: Map<String, DriveImage>,
    onWearAgainToday: (String) -> Unit,
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
        val stylesOnDay = outfitsByDate[date].orEmpty()
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
                    itemsIndexed(stylesOnDay, key = { index, style -> "${date}_${style.id}_$index" }) { index, style ->
                        OutfitSheetRow(
                            style = style,
                            imagesById = imagesById,
                            onWearAgainToday = {
                                onWearAgainToday(style.id)
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
                        if (index < stylesOnDay.lastIndex) HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun OutfitSheetRow(
    style: Outfit,
    imagesById: Map<String, DriveImage>,
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
        Text(style.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                "Items no longer in wardrobe",
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
//  Tab 2: Calendar Stats
// ============================================================================

@Composable
private fun CalendarStatsTab(
    outfitEventsViewModel: OutfitEventsViewModel,
    stylesViewModel: OutfitsViewModel,
    wardrobeViewModel: WardrobeViewModel,
) {
    val outfitEventsState by outfitEventsViewModel.state.collectAsState()
    val outfitsState by stylesViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()

    val outfitsById = remember(outfitsState.outfits) { outfitsState.outfits.associateBy { it.id } }
    val imagesById = remember(wardrobeState.images) { wardrobeState.images.associateBy { it.driveId } }

    val topStyles = remember(outfitEventsState.events, outfitsById) {
        outfitEventsState.events
            .groupBy { it.outfitId }
            .mapValues { it.value.size }
            .entries
            .sortedByDescending { it.value }
            .take(TOP_N)
            .mapNotNull { (outfitId, count) -> outfitsById[outfitId]?.let { it to count } }
    }

    val topItems = remember(outfitEventsState.events, outfitsById, imagesById) {
        outfitEventsState.events
            .flatMap { event -> outfitsById[event.outfitId]?.itemIds ?: emptyList() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(TOP_N)
            .mapNotNull { (itemId, count) -> imagesById[itemId]?.let { it to count } }
    }

    if (topStyles.isEmpty() && topItems.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.calendar_empty), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.calendar_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        if (topStyles.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.calendar_stats_styles),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                HorizontalDivider()
            }
            itemsIndexed(topStyles) { index, (style, count) ->
                StyleStatRow(rank = index + 1, style = style, wearCount = count, imagesById = imagesById)
                if (index < topStyles.lastIndex) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }

        if (topItems.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.calendar_stats_items),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                HorizontalDivider()
            }
            itemsIndexed(topItems) { index, (image, count) ->
                ItemStatRow(rank = index + 1, image = image, wearCount = count)
                if (index < topItems.lastIndex) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun StyleStatRow(
    rank: Int,
    style: Outfit,
    wearCount: Int,
    imagesById: Map<String, DriveImage>,
) {
    val ctx = LocalContext.current
    val styleItems = style.itemIds.mapNotNull { imagesById[it] }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(36.dp),
        ) {
            Text(
                "#$rank",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${wearCount}×",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                style.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (styleItems.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(styleItems, key = { index, image -> "${image.driveId}_${index}" }) { _, image ->
                        AsyncImage(
                            model = remember(image.driveId, image.version) {
                                ImageRequest.Builder(ctx)
                                    .data(image.localPath)
                                    .memoryCacheKey("${image.driveId}_${image.version}")
                                    .build()
                            },
                            contentDescription = image.name,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(MaterialTheme.shapes.small),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            } else {
                Text(
                    "Items no longer in wardrobe",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun ItemStatRow(
    rank: Int,
    image: DriveImage,
    wearCount: Int,
) {
    val ctx = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(36.dp),
        ) {
            Text(
                "#$rank",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${wearCount}×",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        AsyncImage(
            model = remember(image.driveId, image.version) {
                ImageRequest.Builder(ctx)
                    .data(image.localPath)
                    .memoryCacheKey("${image.driveId}_${image.version}")
                    .build()
            },
            contentDescription = image.name,
            modifier = Modifier
                .size(56.dp)
                .clip(MaterialTheme.shapes.small),
            contentScale = ContentScale.Crop,
        )

        Column(modifier = Modifier.weight(1f)) {
            val displayName = image.tags?.label?.ifEmpty { null }
                ?: image.tags?.type?.ifEmpty { null }
                ?: image.name
            Text(
                displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            image.tags?.let { t ->
                val subtitle = listOfNotNull(
                    t.type.takeIf { it.isNotEmpty() && t.label.isNotEmpty() },
                    t.category.takeIf { it.isNotEmpty() },
                ).joinToString(" · ")
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ============================================================================
//  Tab 3: Wardrobe Stats
// ============================================================================

@Composable
private fun WardrobeStatsTab(
    wardrobeViewModel: WardrobeViewModel,
) {
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val images = wardrobeState.images
    val counts = remember(images) { images.tagCategoryCounts() }
    val untagged = remember(images) {
        images.count { img ->
            val t = img.tags
            t == null || (t.type.isBlank() && t.category.isBlank())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.wardrobe_stats_total, images.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (images.isNotEmpty() && counts.isEmpty()) {
            Text(
                stringResource(R.string.wardrobe_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        counts.forEach { categoryCounts ->
            val maxCount = categoryCounts.counts.maxOf { it.count }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    tagCategoryDisplayLabel(categoryCounts.label),
                    style = MaterialTheme.typography.titleSmall,
                )
                categoryCounts.counts.forEach { tc ->
                    StatsBarRow(
                        label = tc.value.localizedTagValue(),
                        count = tc.count,
                        fraction = tc.count.toFloat() / maxCount.toFloat(),
                    )
                }
            }
        }
        if (untagged > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.wardrobe_stats_untagged),
                    style = MaterialTheme.typography.titleSmall,
                )
                StatsBarRow(
                    label = stringResource(R.string.wardrobe_stats_untagged),
                    count = untagged,
                    fraction = untagged.toFloat() / images.size.toFloat(),
                )
            }
        }
    }
}

@Composable
private fun StatsBarRow(label: String, count: Int, fraction: Float) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(110.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .weight(1f)
                .height(6.dp),
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(28.dp),
        )
    }
}
