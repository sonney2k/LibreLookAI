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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val MONTH_FORMATTER = DateTimeFormatter.ofPattern("MMMM yyyy")
private val SHEET_DATE_FORMATTER = DateTimeFormatter.ofPattern("EEEE, MMMM d")
// DOW_LABELS is now computed via @Composable dowLabels()
private const val MAX_THUMBNAILS = 4
private val THUMBNAIL_SIZE = 14.dp
private const val TOP_N = 10

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    outfitsViewModel: OutfitsViewModel = viewModel(),
    stylesViewModel: StylesViewModel = viewModel(),
    wardrobeViewModel: WardrobeViewModel = viewModel(),
    locationViewModel: LocationViewModel = viewModel(),
    onEditStyle: (Style) -> Unit = {},
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val outfitsState by outfitsViewModel.state.collectAsState()
    val stylesState by stylesViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val locationState by locationViewModel.state.collectAsState()

    val stylesById = remember(stylesState.styles) { stylesState.styles.associateBy { it.id } }
    val imagesById = remember(wardrobeState.images) { wardrobeState.images.associateBy { it.driveId } }

    // WornItem entries for thumbnail display in day cells
    val wornItems = remember(outfitsState.events, stylesById, imagesById) {
        outfitsState.events.flatMap { event ->
            val style = stylesById[event.styleId] ?: return@flatMap emptyList<WornItem>()
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

    val stylesByDate = remember(outfitsState.events, stylesById) {
        outfitsState.events.mapNotNull { event ->
            val style = stylesById[event.styleId] ?: return@mapNotNull null
            val date = runCatching { LocalDate.parse(event.date) }.getOrNull()
                ?: return@mapNotNull null
            date to style
        }.groupBy({ it.first }, { it.second })
    }

    // Statistics: top styles by wear count
    val topStyles = remember(outfitsState.events, stylesById) {
        outfitsState.events
            .groupBy { it.styleId }
            .mapValues { it.value.size }
            .entries
            .sortedByDescending { it.value }
            .take(TOP_N)
            .mapNotNull { (styleId, count) -> stylesById[styleId]?.let { it to count } }
    }

    // Statistics: top individual items by total appearances across all worn styles
    val topItems = remember(outfitsState.events, stylesById, imagesById) {
        outfitsState.events
            .flatMap { event -> stylesById[event.styleId]?.itemIds ?: emptyList() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(TOP_N)
            .mapNotNull { (itemId, count) -> imagesById[itemId]?.let { it to count } }
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
        AppScreenHeader(
            title = stringResource(R.string.nav_calendar),
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
                text = { Text(stringResource(R.string.calendar_tab_calendar)) },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(stringResource(R.string.calendar_tab_stats)) },
            )
        }

        when (selectedTab) {
            0 -> CalendarContent(
                wornItems = wornItems,
                stylesByDate = stylesByDate,
                imagesById = imagesById,
                onWearAgainToday = { outfitsViewModel.recordOutfit(it) },
                onEditStyle = onEditStyle,
            )
            1 -> StatisticsContent(
                topStyles = topStyles,
                topItems = topItems,
                imagesById = imagesById,
            )
        }
    }
}

// ---------- Calendar tab ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarContent(
    wornItems: List<WornItem>,
    stylesByDate: Map<LocalDate, List<Style>>,
    imagesById: Map<String, DriveImage>,
    onWearAgainToday: (String) -> Unit = {},
    onEditStyle: (Style) -> Unit = {},
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
                        val hasStyles = date != null && stylesByDate[date].orEmpty().isNotEmpty()
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
        val stylesOnDay = stylesByDate[date].orEmpty()
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
                    items(stylesOnDay, key = { it.id }) { style ->
                        StyleSheetRow(
                            style = style,
                            imagesById = imagesById,
                            onWearAgainToday = {
                                onWearAgainToday(style.id)
                                scope.launch { sheetState.hide() }.invokeOnCompletion {
                                    selectedDate = null
                                }
                            },
                            onEditStyle = {
                                scope.launch { sheetState.hide() }.invokeOnCompletion {
                                    selectedDate = null
                                    onEditStyle(style)
                                }
                            },
                        )
                        if (style != stylesOnDay.last()) HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                    }
                }
            }
        }
    }
}

// ---------- Statistics tab ----------

@Composable
private fun StatisticsContent(
    topStyles: List<Pair<Style, Int>>,
    topItems: List<Pair<DriveImage, Int>>,
    imagesById: Map<String, DriveImage>,
) {
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
    style: Style,
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
        // Rank + wear count
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
                    items(styleItems.take(6), key = { it.driveId }) { image ->
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
        // Rank + wear count
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

// ---------- Style row inside the day-detail sheet ----------

@Composable
private fun StyleSheetRow(
    style: Style,
    imagesById: Map<String, DriveImage>,
    onWearAgainToday: () -> Unit = {},
    onEditStyle: () -> Unit = {},
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
                items(styleItems, key = { it.driveId }) { image ->
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
                    onClick = onEditStyle,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_edit))
                }
            }
        }
    }
}

// ---------- Calendar chrome ----------

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

/** Returns a list of weeks, each week a list of 7 nullable LocalDates (null = padding day). */
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
