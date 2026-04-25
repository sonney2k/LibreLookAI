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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

@Composable
fun ShoppingHelperScreen(
    shoppingViewModel: ShoppingHelperViewModel = viewModel(),
    wardrobeViewModel: WardrobeViewModel = viewModel(),
    locationViewModel: LocationViewModel = viewModel(),
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by shoppingViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val locationState by locationViewModel.state.collectAsState()

    var previewMatch by remember { mutableStateOf<ShopMatch?>(null) }

    // Kick off a catch-up index sync whenever the wardrobe list changes.
    LaunchedEffect(wardrobeState.images.size) {
        shoppingViewModel.syncIndex(wardrobeState.images)
    }

    if (state.isCapturing) {
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

    Column(modifier = modifier.fillMaxSize()) {
        AppScreenHeader(
            title = stringResource(R.string.shop_title),
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
    val context = LocalContext.current
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
                    ZoomableImage(file = File(match.image.localPath))
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
private fun ZoomableImage(file: File) {
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
