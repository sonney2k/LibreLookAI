package com.librelookai

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingBag
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ShoppingHelperScreen(
    shoppingViewModel: ShoppingHelperViewModel = viewModel(),
    wardrobeViewModel: WardrobeViewModel = viewModel(),
    gapViewModel: WardrobeGapViewModel = viewModel(),
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
                // Search across every configured closet, not just the active one.
                shoppingViewModel.onCapturedFile(file, wardrobeState.allLocationImages)
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
            title = stringResource(R.string.shopping_title),
            leadingIcon = Icons.Default.ShoppingBag,
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
                text = { Text(stringResource(R.string.shopping_tab_similarity)) },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(stringResource(R.string.shopping_tab_gaps)) },
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
        }
    }
}

// ============================================================================
//  Tab 1: Similarity Finder
// ============================================================================

@Composable
private fun SimilarityFinderTab(
    shoppingViewModel: ShoppingHelperViewModel,
    wardrobeViewModel: WardrobeViewModel,
) {
    val state by shoppingViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()

    var previewIndex by remember { mutableStateOf<Int?>(null) }

    // Kick off a catch-up index sync whenever the cross-closet wardrobe snapshot changes.
    LaunchedEffect(wardrobeState.allLocationImages.size) {
        shoppingViewModel.syncIndex(wardrobeState.allLocationImages)
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
                itemsIndexed(state.matches, key = { _, m -> m.image.driveId }) { idx, match ->
                    MatchRow(match = match, onClick = { previewIndex = idx })
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

    previewIndex?.let { idx ->
        if (idx in state.matches.indices) {
            MatchPreviewDialog(
                matches = state.matches,
                initialIndex = idx,
                queryRawPath = state.queryPath,
                queryProcessedPath = state.queryProcessedPath,
                querySegmented = state.querySegmented,
                queryHist = state.queryHist,
                queryVec = state.queryVec,
                onDismiss = { previewIndex = null },
            )
        }
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

/**
 * Debug-oriented match preview. Pages horizontally over [matches] starting at [initialIndex],
 * and on each page shows:
 *  - the raw query photo and its post-segmentation, composited-on-white, center-cropped
 *    counterpart (the exact pixels we hand to the embedder);
 *  - the match's raw cutout (transparent PNG) and the same composited+cropped variant we
 *    embed when the wardrobe is indexed;
 *  - the per-channel score breakdown (combined / embedding cosine / histogram cosine);
 *  - the H-axis (12 hue bins) of the query and match histograms side by side.
 */
@Composable
private fun MatchPreviewDialog(
    matches: List<ShopMatch>,
    initialIndex: Int,
    queryRawPath: String?,
    queryProcessedPath: String?,
    querySegmented: Boolean,
    queryHist: FloatArray?,
    queryVec: FloatArray?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val pagerState = rememberPagerState(
            initialPage = initialIndex.coerceIn(0, (matches.size - 1).coerceAtLeast(0)),
            pageCount = { matches.size },
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.statusBarsPadding())

                val current = matches[pagerState.currentPage]
                val label = current.image.tags?.label?.takeIf { it.isNotBlank() }
                    ?: current.image.tags?.type?.takeIf { it.isNotBlank() }
                    ?: current.image.name

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            label,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${pagerState.currentPage + 1} / ${matches.size}",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    MatchDebugPage(
                        match = matches[page],
                        queryRawPath = queryRawPath,
                        queryProcessedPath = queryProcessedPath,
                        querySegmented = querySegmented,
                        queryHist = queryHist,
                        queryVec = queryVec,
                    )
                }
            }
        }
    }
}

@Composable
private fun MatchDebugPage(
    match: ShopMatch,
    queryRawPath: String?,
    queryProcessedPath: String?,
    querySegmented: Boolean,
    queryHist: FloatArray?,
    queryVec: FloatArray?,
) {
    // Pull the match's stored embedding + histogram from the persistent index. produceState
    // re-fetches whenever the visible page changes so HorizontalPager works smoothly.
    val matchEntry by produceState<EmbeddingIndex.Entry?>(initialValue = null, match.image.driveId) {
        value = withContext(Dispatchers.IO) { EmbeddingService.index.entry(match.image.driveId) }
    }

    val combinedPercent = ((match.score.coerceIn(-1f, 1f) + 1f) / 2f * 100f).toInt()
    val embCos = remember(queryVec, matchEntry) {
        val q = queryVec; val e = matchEntry?.vec
        if (q != null && e != null && q.size == e.size) dot(q, e) else null
    }
    val histCos = remember(queryHist, matchEntry) {
        val q = queryHist; val h = matchEntry?.hist
        if (q != null && h != null) ColorHistogram.cosine(q, h) else null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            color = Color(0x22FFFFFF),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    "Combined match: $combinedPercent%",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "embedding cos = ${formatCos(embCos)}   ·   histogram cos = ${formatCos(histCos)}",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        val processedLabel = if (querySegmented) {
            "Query  (raw → segmented + composited + cropped)"
        } else {
            "Query  (raw → cropped — segmentation FAILED, embedded raw frame)"
        }
        Text(
            processedLabel,
            color = if (querySegmented) Color.White.copy(alpha = 0.85f)
                    else Color(0xFFFFB4A0),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DebugThumbAsync(
                label = "raw",
                path = queryRawPath,
                modifier = Modifier.weight(1f),
            )
            DebugThumbAsync(
                label = "processed",
                path = queryProcessedPath,
                fallbackText = "(segmentation failed)",
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            "Match  (cutout → composited + cropped)",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DebugThumbAsync(
                label = "raw",
                path = match.image.localPath,
                modifier = Modifier.weight(1f),
            )
            ProcessedCutoutThumb(
                label = "processed",
                file = File(match.image.localPath),
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            "Hue histograms  (12 bins, summed across S × V)",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HueHistogramPanel(
                title = "query",
                hist = queryHist,
                modifier = Modifier.weight(1f),
            )
            HueHistogramPanel(
                title = "match",
                hist = matchEntry?.hist,
                modifier = Modifier.weight(1f),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x11FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            ZoomableMatchImage(file = File(match.image.localPath))
        }
    }
}

@Composable
private fun DebugThumbAsync(
    label: String,
    path: String?,
    modifier: Modifier = Modifier,
    fallbackText: String? = null,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0x22FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            if (path != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(File(path)).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (fallbackText != null) {
                Text(
                    fallbackText,
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun ProcessedCutoutThumb(
    label: String,
    file: File,
    modifier: Modifier = Modifier,
) {
    val processed by produceState<android.graphics.Bitmap?>(initialValue = null, file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            val src = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                ?: return@withContext null
            try {
                EmbeddingRepository.prepareForEmbedding(src)
            } finally {
                if (!src.isRecycled) src.recycle()
            }
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            val bm = processed
            if (bm != null) {
                Image(
                    bitmap = bm.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun HueHistogramPanel(
    title: String,
    hist: FloatArray?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0x22FFFFFF))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (hist != null && hist.size == HIST_DIM) {
                HueHistogramBars(hist = hist)
            } else {
                Text(
                    "(none)",
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun HueHistogramBars(hist: FloatArray) {
    val hueWeights = remember(hist) {
        val out = FloatArray(HIST_H_BINS)
        for (hb in 0 until HIST_H_BINS) {
            var sum = 0f
            for (sb in 0 until HIST_S_BINS) {
                for (vb in 0 until HIST_V_BINS) {
                    sum += hist[(hb * HIST_S_BINS + sb) * HIST_V_BINS + vb]
                }
            }
            out[hb] = sum
        }
        out
    }
    val maxW = hueWeights.maxOrNull() ?: 0f
    val safeMax = if (maxW <= 0f) 1f else maxW
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (hb in 0 until HIST_H_BINS) {
            val hue = (hb + 0.5f) / HIST_H_BINS * 360f
            val color = Color.hsv(hue, 0.85f, 0.95f)
            val frac = (hueWeights[hb] / safeMax).coerceIn(0f, 1f)
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(if (frac < 0.02f) 0.02f else frac)
                        .background(color, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)),
                )
            }
        }
    }
}

@Composable
private fun ZoomableMatchImage(file: File) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
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

private fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}

private fun formatCos(value: Float?): String =
    if (value == null) "?" else String.format("%.3f", value)

// ============================================================================
//  Tab 2: Identify Gaps
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

                itemsIndexed(analysis.suggestions) { index, suggestion ->
                    GapSuggestionCard(
                        rank = index + 1,
                        suggestion = suggestion,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }

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

        if (state.isAnalyzing) {
            AiProcessingOverlay(
                label = stringResource(R.string.ai_analyzing_wardrobe),
                modifier = Modifier.fillMaxSize(),
            )
        }

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
