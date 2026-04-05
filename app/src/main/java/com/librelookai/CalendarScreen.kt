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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val MONTH_FORMATTER = DateTimeFormatter.ofPattern("MMMM yyyy")
private val SHEET_DATE_FORMATTER = DateTimeFormatter.ofPattern("EEEE, MMMM d")
private val DOW_LABELS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
private const val MAX_THUMBNAILS = 4
private val THUMBNAIL_SIZE = 14.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    outfitsViewModel: OutfitsViewModel = viewModel(),
    stylesViewModel: StylesViewModel = viewModel(),
    wardrobeViewModel: WardrobeViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val outfitsState by outfitsViewModel.state.collectAsState()
    val stylesState by stylesViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()

    val stylesById = remember(stylesState.styles) { stylesState.styles.associateBy { it.id } }
    val imagesById = remember(wardrobeState.images) { wardrobeState.images.associateBy { it.driveId } }

    // WornItem entries for thumbnail display in day cells (one entry per wardrobe item per event)
    val wornItems = remember(outfitsState.events, stylesById, imagesById) {
        outfitsState.events.flatMap { event ->
            val style = stylesById[event.styleId] ?: return@flatMap emptyList<WornItem>()
            val date = runCatching { LocalDate.parse(event.date) }.getOrNull()
                ?: return@flatMap emptyList()
            style.itemIds.mapNotNull { itemId ->
                val image = imagesById[itemId] ?: return@mapNotNull null
                WornItem(date = date, imagePath = image.localPath, label = image.name)
            }
        }
    }

    // Styles grouped by date — used when opening the detail sheet
    val stylesByDate = remember(outfitsState.events, stylesById) {
        outfitsState.events.mapNotNull { event ->
            val style = stylesById[event.styleId] ?: return@mapNotNull null
            val date = runCatching { LocalDate.parse(event.date) }.getOrNull()
                ?: return@mapNotNull null
            date to style
        }.groupBy({ it.first }, { it.second })
    }

    var yearMonth by rememberSaveable { mutableStateOf(YearMonth.now()) }
    val itemsByDate = remember(wornItems) { wornItems.groupBy { it.date } }
    val weeks = remember(yearMonth) { buildCalendarWeeks(yearMonth) }
    val today = remember { LocalDate.now() }

    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(modifier = modifier.fillMaxSize()) {
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

    // Bottom sheet: styles worn on selectedDate
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
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(stylesOnDay, key = { it.id }) { style ->
                        StyleSheetRow(style = style, imagesById = imagesById)
                        if (style != stylesOnDay.last()) HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                    }
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
) {
    val ctx = LocalContext.current
    val styleItems = style.itemIds.mapNotNull { imagesById[it] }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(style.name, style = MaterialTheme.typography.titleSmall)
        if (styleItems.isEmpty()) {
            Text(
                "Items no longer in wardrobe",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                            .size(80.dp)
                            .clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.Crop,
                    )
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
    Row(modifier = Modifier.fillMaxWidth()) {
        DOW_LABELS.forEach { label ->
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
            // Day number with today highlight
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

            // Outfit thumbnails
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
