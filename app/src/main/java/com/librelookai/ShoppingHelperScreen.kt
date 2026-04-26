package com.librelookai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

private const val TOP_N = 10

@Composable
fun ShoppingHelperScreen(
    shoppingViewModel: ShoppingHelperViewModel = viewModel(),
    wardrobeViewModel: WardrobeViewModel = viewModel(),
    gapViewModel: WardrobeGapViewModel = viewModel(),
    outfitEventsViewModel: OutfitEventsViewModel = viewModel(),
    stylesViewModel: OutfitsViewModel = viewModel(),
    profileViewModel: ProfileViewModel = viewModel(),
    locationViewModel: LocationViewModel = viewModel(),
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val shoppingState by shoppingViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val locationState by locationViewModel.state.collectAsState()

    // Similarity Finder takes the camera over the whole screen.
    if (shoppingState.isCapturing) {
        CaptureScreen(
            onPhotoTaken = { file ->
                shoppingViewModel.onCapturedFile(file, wardrobeState.images)
            },
            onCancel = shoppingViewModel::cancelCapture,
            locations = locationState.locations,
            importTargetFolderId = null,
            onSetImportTarget = {},
            showCenterCrosshair = true,
            modifier = modifier,
        )
        return
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

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
                text = { Text(stringResource(R.string.insights_tab_similarity)) },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(stringResource(R.string.insights_tab_gaps)) },
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text(stringResource(R.string.insights_tab_wardrobe_stats)) },
            )
            Tab(
                selected = selectedTab == 3,
                onClick = { selectedTab = 3 },
                text = { Text(stringResource(R.string.insights_tab_calendar_stats)) },
            )
        }

        when (selectedTab) {
            0 -> SimilarityFinderTab(
                shoppingViewModel = shoppingViewModel,
                wardrobeViewModel = wardrobeViewModel,
            )
            1 -> IdentifyGapsTab(
                gapViewModel = gapViewModel,
                wardrobeViewModel = wardrobeViewModel,
                profileViewModel = profileViewModel,
            )
            2 -> WardrobeStatsTab(
                wardrobeViewModel = wardrobeViewModel,
            )
            3 -> CalendarStatsTab(
                outfitEventsViewModel = outfitEventsViewModel,
                stylesViewModel = stylesViewModel,
                wardrobeViewModel = wardrobeViewModel,
            )
        }
    }
}

// ============================================================================
//  Tab 1: Similarity Finder (former standalone Shop screen body)
// ============================================================================

@Composable
private fun SimilarityFinderTab(
    shoppingViewModel: ShoppingHelperViewModel,
    wardrobeViewModel: WardrobeViewModel,
) {
    val state by shoppingViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()

    var previewMatch by remember { mutableStateOf<ShopMatch?>(null) }

    // Kick off a catch-up index sync whenever the wardrobe list changes.
    LaunchedEffect(wardrobeState.images.size) {
        shoppingViewModel.syncIndex(wardrobeState.images)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                IntroAndControls(
                    state = state,
                    onCapture = shoppingViewModel::beginCapture,
                    onClear = shoppingViewModel::clearResults,
                )
            }

            if (state.queryPath != null) {
                item { QueryCard(queryPath = state.queryPath!!) }
            }

            if (state.isMatching) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.shop_matching),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (state.matches.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.shop_matches_title, state.matches.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                items(state.matches, key = { it.image.driveId }) { match ->
                    MatchRow(match = match, onClick = { previewMatch = match })
                }
            } else if (state.queryPath != null && !state.isMatching) {
                item {
                    Text(
                        stringResource(R.string.shop_no_matches),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }

    previewMatch?.let { match ->
        MatchPreviewDialog(
            match = match,
            queryPath = state.queryPath,
            onDismiss = { previewMatch = null },
        )
    }
}

@Composable
private fun IntroAndControls(
    state: ShoppingHelperUiState,
    onCapture: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.shop_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!state.modelAvailable) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.shop_model_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onCapture,
                enabled = state.modelAvailable && !state.isMatching,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.hasQuery) stringResource(R.string.shop_take_another)
                    else stringResource(R.string.shop_take_photo),
                )
            }
            if (state.hasQuery) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onClear) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.shop_clear))
                }
            }
        }

        if (state.isIndexing && state.indexTotal > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.shop_indexing, state.indexDone, state.indexTotal),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { (state.indexDone.toFloat() / state.indexTotal).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Text(
                stringResource(R.string.shop_index_size, state.indexedCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.error?.let { err ->
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun QueryCard(queryPath: String) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            stringResource(R.string.shop_your_photo),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(File(queryPath))
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun MatchRow(match: ShopMatch, onClick: () -> Unit) {
    val context = LocalContext.current
    val percent = ((match.score.coerceIn(-1f, 1f) + 1f) / 2f * 100f).toInt()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x1100FF00)),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(File(match.image.localPath))
                    .crossfade(true)
                    .build(),
                contentDescription = match.image.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().aspectRatio(1f),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            val label = match.image.tags?.label?.takeIf { it.isNotBlank() }
                ?: match.image.tags?.type?.takeIf { it.isNotBlank() }
                ?: match.image.name
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { (percent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.shop_match_score, percent),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun MatchPreviewDialog(
    match: ShopMatch,
    queryPath: String?,
    onDismiss: () -> Unit,
) {
    val percent = ((match.score.coerceIn(-1f, 1f) + 1f) / 2f * 100f).toInt()
    val label = match.image.tags?.label?.takeIf { it.isNotBlank() }
        ?: match.image.tags?.type?.takeIf { it.isNotBlank() }
        ?: match.image.name

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.statusBarsPadding())

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            label,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.shop_match_score, percent),
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                if (queryPath != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LabelledThumb(
                            label = stringResource(R.string.shop_your_photo),
                            file = File(queryPath),
                            modifier = Modifier.weight(1f),
                        )
                        LabelledThumb(
                            label = label,
                            file = File(match.image.localPath),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    ZoomableMatchImage(file = File(match.image.localPath))
                }
            }
        }
    }
}

@Composable
private fun LabelledThumb(
    label: String,
    file: File,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x22FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(file).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ZoomableMatchImage(file: File) {
    val context = LocalContext.current
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    AsyncImage(
        model = ImageRequest.Builder(context).data(file).crossfade(true).build(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale <= 1.01f) Offset.Zero
                             else Offset(offset.x + pan.x, offset.y + pan.y)
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

// ============================================================================
//  Tab 2: Identify Gaps (former standalone WardrobeGapScreen body)
// ============================================================================

@Composable
private fun IdentifyGapsTab(
    gapViewModel: WardrobeGapViewModel,
    wardrobeViewModel: WardrobeViewModel,
    profileViewModel: ProfileViewModel,
) {
    val isOffline = LocalIsOffline.current
    val state by gapViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val profileState by profileViewModel.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // ---- Analyze button + description ----
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.gap_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            gapViewModel.analyze(
                                images = wardrobeState.images,
                                prefs  = profileState.preferences,
                            )
                        },
                        enabled = !state.isAnalyzing && wardrobeState.images.isNotEmpty() && !isOffline,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.analysis != null) stringResource(R.string.gap_reanalyze) else stringResource(R.string.gap_analyze))
                    }
                }
            }

            // ---- Summary ----
            state.analysis?.let { analysis ->
                if (analysis.summary.isNotEmpty()) {
                    item {
                        HorizontalDivider()
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                analysis.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                        HorizontalDivider()
                    }
                }

                // ---- Suggestion cards ----
                itemsIndexed(analysis.suggestions) { index, suggestion ->
                    GapSuggestionCard(
                        rank = index + 1,
                        suggestion = suggestion,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }

            // ---- Empty state ----
            if (!state.isAnalyzing && state.analysis == null && state.error == null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 32.dp),
                        ) {
                            Icon(
                                Icons.Default.TipsAndUpdates,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.gap_empty_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.gap_empty_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // AI overlay
        if (state.isAnalyzing) {
            AiProcessingOverlay(
                label = stringResource(R.string.ai_analyzing_wardrobe),
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Error snackbar
        state.error?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = { TextButton(onClick = gapViewModel::clearError) { Text(stringResource(R.string.action_ok)) } },
            ) { Text(msg) }
        }
    }
}

// ============================================================================
//  Tab 3: Wardrobe Stats (former WardrobeStatsSheet)
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

// ============================================================================
//  Tab 4: Calendar Stats (former CalendarScreen.StatisticsContent)
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

