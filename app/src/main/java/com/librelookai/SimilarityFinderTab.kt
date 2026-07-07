package com.librelookai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.librelookai.R
import com.librelookai.shopping.MatchPreviewDialog
import com.librelookai.shopping.MatchRow
import com.librelookai.shopping.ShoppingClosetViewModel
import com.librelookai.shopping.ShoppingHelperUiState
import com.librelookai.shopping.ShoppingHelperViewModel
import com.librelookai.shopping.importQuery
import com.librelookai.settings.ProfileViewModel
import com.librelookai.util.Analytics
import com.librelookai.util.scrollbar
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.WardrobeViewModel
import java.io.File

@Composable
internal fun SimilarityFinderTab(
    shoppingViewModel: ShoppingHelperViewModel,
    shoppingClosetViewModel: ShoppingClosetViewModel,
    wardrobeViewModel: WardrobeViewModel,
    profileViewModel: ProfileViewModel,
    onShowInWardrobe: (DriveImage) -> Unit,
) {
    val state by shoppingViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val profileState by profileViewModel.state.collectAsState()
    val showDebug = profileState.preferences.debugSimilarityPreview

    var previewIndex by remember { mutableStateOf<Int?>(null) }

    // Kick off a catch-up index sync whenever the cross-closet wardrobe snapshot changes.
    LaunchedEffect(wardrobeState.allLocationImages.size) {
        shoppingViewModel.syncIndex(wardrobeState.allLocationImages)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val shoppingListState = rememberLazyListState()
        LazyColumn(
            state = shoppingListState,
            modifier = Modifier.fillMaxSize().scrollbar(shoppingListState),
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
                    MatchRow(match = match, onClick = {
                        Analytics.action("Shopping/Similarity", "open_match_preview", mapOf("index" to idx.toString()))
                        previewIndex = idx
                    })
                }
            } else if (state.queryPath != null && !state.isMatching) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(R.string.shop_no_matches),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val queryPath = state.queryPath
                        if (queryPath != null) {
                            Button(
                                onClick = {
                                    Analytics.action("Shopping/Similarity", "add_query_to_shopping_list")
                                    shoppingClosetViewModel.importQuery(queryPath)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.ShoppingBag, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.shop_add_to_shopping_list))
                            }
                        }
                    }
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
                queryPHash = state.queryPHash,
                showDebug = showDebug,
                onShowInWardrobe = { image ->
                    previewIndex = null
                    onShowInWardrobe(image)
                },
                onAddToShoppingList = {
                    Analytics.action("Shopping/Similarity", "add_match_to_shopping_list")
                    state.queryPath?.let { shoppingClosetViewModel.importQuery(it) }
                    previewIndex = null
                },
                canAddToShoppingList = state.queryPath != null,
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
                onClick = {
                    Analytics.action("Shopping/Similarity", "capture_query")
                    onCapture()
                },
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
                OutlinedButton(onClick = {
                    Analytics.action("Shopping/Similarity", "clear_results")
                    onClear()
                }) {
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

