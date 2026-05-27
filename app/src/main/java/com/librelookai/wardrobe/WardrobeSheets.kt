package com.librelookai.wardrobe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.librelookai.R
import com.librelookai.data.model.Location
import com.librelookai.gemini.CutoutFixActions
import com.librelookai.shopping.MatchPreviewDialog
import com.librelookai.shopping.MatchRow
import com.librelookai.shopping.ShopMatch
import com.librelookai.util.Analytics

@Composable
internal fun UrlImportDialog(
    locations: List<Location>,
    initialFolderId: String?,
    onSubmit: (url: String, folderId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var selectedFolderId by remember { mutableStateOf(initialFolderId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wardrobe_url_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.wardrobe_url_import_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (locations.size >= 2) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.wardrobe_add_to_closet_title),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    locations.sortedBy { it.name }.forEach { loc ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedFolderId = loc.folderId }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(
                                selected = loc.folderId == selectedFolderId,
                                onClick = { selectedFolderId = loc.folderId },
                            )
                            Text(loc.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank(),
                onClick = {
                    Analytics.action("Wardrobe", "submit_url_import")
                    onSubmit(url.trim(), selectedFolderId)
                },
            ) { Text(stringResource(R.string.action_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

// ---------- Full-screen image viewer ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DuplicateCheckSheet(
    check: DuplicateCheck,
    debugSimilarityPreview: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onShowMatchInWardrobe: (DriveImage) -> Unit,
    onAddQueryToShoppingList: (queryPath: String) -> Unit,
) {
    val context = LocalContext.current
    // Capture parent locale-aware context/configuration: ModalBottomSheet renders in its own
    // window, so without this re-provide stringResource() would fall back to the system locale
    // and ignore the in-app language toggle.
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val shopMatches = remember(check.matches) {
        check.matches.map { ShopMatch(image = it.image, score = it.score) }
    }
    var previewIndex by remember { mutableStateOf<Int?>(null) }

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
      CompositionLocalProvider(
          LocalContext provides parentContext,
          LocalConfiguration provides parentConfiguration,
      ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.dedupe_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.dedupe_dialog_desc, check.matches.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(java.io.File(check.rawFilePath))
                        .crossfade(true)
                        .build(),
                    contentDescription = stringResource(R.string.shop_your_photo),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            ) {
                shopMatches.forEachIndexed { idx, match ->
                    MatchRow(match = match, onClick = { previewIndex = idx })
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dedupe_dialog_cancel))
                }
                androidx.compose.material3.Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dedupe_dialog_import_anyway))
                }
            }
        }
      }
    }

    previewIndex?.let { idx ->
        if (idx in shopMatches.indices) {
            MatchPreviewDialog(
                matches = shopMatches,
                initialIndex = idx,
                queryRawPath = check.rawFilePath,
                queryProcessedPath = check.processedPath,
                querySegmented = check.segmented,
                queryHist = check.hist,
                queryVec = check.vec,
                queryPHash = check.pHash,
                showDebug = debugSimilarityPreview,
                onShowInWardrobe = { image ->
                    previewIndex = null
                    onShowMatchInWardrobe(image)
                },
                onAddToShoppingList = {
                    previewIndex = null
                    onAddQueryToShoppingList(check.rawFilePath)
                },
                canAddToShoppingList = true,
                showActions = false,
                onDismiss = { previewIndex = null },
            )
        }
    }
}

/**
 * Find-by-photo bottom sheet. Shares the [MatchRow] + [MatchPreviewDialog] pattern with the
 * Shopping helper's Similarity Finder so both flows feel like a single feature with two entry
 * points: tapping a row opens the same preview dialog with the same "Show in wardrobe" /
 * "Add to shopping list" actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FindByPhotoResultsSheet(
    findByPhoto: FindByPhoto,
    debugSimilarityPreview: Boolean,
    onPickMatch: (DriveImage) -> Unit,
    onAddToShoppingList: (queryPath: String?) -> Unit,
    onSearchAgain: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isTextQuery = findByPhoto.textQuery != null
    val context = LocalContext.current
    // ModalBottomSheet hosts its content in a separate sub-composition that doesn't always
    // propagate the activity's locale-overridden LocalContext/LocalConfiguration. Capture both
    // out here and re-provide them inside the sheet so stringResource follows the app language.
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val shopMatches = remember(findByPhoto.matches) {
        findByPhoto.matches.map { ShopMatch(image = it.image, score = it.score) }
    }
    var previewIndex by remember { mutableStateOf<Int?>(null) }
    var queryDraft by remember(findByPhoto.textQuery) {
        mutableStateOf(findByPhoto.textQuery.orEmpty())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(
                    if (isTextQuery) R.string.wardrobe_search_text_results
                    else R.string.wardrobe_find_by_photo
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (isTextQuery) {
                val keyboard = LocalSoftwareKeyboardController.current
                OutlinedTextField(
                    value = queryDraft,
                    onValueChange = { queryDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.wardrobe_search_placeholder)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        val q = queryDraft.trim()
                        if (q.isNotEmpty()) {
                            keyboard?.hide()
                            onSearchAgain(q)
                        }
                    }),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val q = queryDraft.trim()
                                if (q.isNotEmpty()) {
                                    keyboard?.hide()
                                    onSearchAgain(q)
                                }
                            },
                            enabled = queryDraft.trim().isNotEmpty(),
                        ) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.wardrobe_search_text_results))
                        }
                    },
                )
            } else {
                val qPath = findByPhoto.queryPath
                if (qPath != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(java.io.File(qPath))
                                .crossfade(true)
                                .build(),
                            contentDescription = stringResource(R.string.shop_your_photo),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            when {
                findByPhoto.isSearching -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.wardrobe_find_by_photo_searching),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                shopMatches.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.wardrobe_find_by_photo_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!isTextQuery && findByPhoto.queryPath != null) {
                        Button(
                            onClick = { onAddToShoppingList(findByPhoto.queryPath) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.ShoppingBag, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.shop_add_to_shopping_list))
                        }
                    }
                }
                else -> Column(
                    modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                ) {
                    shopMatches.forEachIndexed { idx, match ->
                        MatchRow(match = match, onClick = { previewIndex = idx })
                    }
                }
            }
        }
        }
    }

    previewIndex?.let { idx ->
        if (idx in shopMatches.indices) {
            MatchPreviewDialog(
                matches = shopMatches,
                initialIndex = idx,
                queryRawPath = findByPhoto.queryPath,
                queryProcessedPath = findByPhoto.processedPath,
                querySegmented = findByPhoto.segmented,
                queryHist = findByPhoto.hist,
                queryVec = findByPhoto.vec,
                queryPHash = findByPhoto.pHash,
                showDebug = debugSimilarityPreview,
                onShowInWardrobe = { image ->
                    previewIndex = null
                    onPickMatch(image)
                },
                onAddToShoppingList = {},
                canAddToShoppingList = false,
                onDismiss = { previewIndex = null },
            )
        }
    }
}

@Composable
internal fun FixCutoutBgItemDialog(
    onConfirm: (CutoutFixActions) -> Unit,
    onDismiss: () -> Unit,
) {
    var clearAlpha by remember { mutableStateOf(false) }
    var blackToAlpha by remember { mutableStateOf(true) }
    var despillGreen by remember { mutableStateOf(true) }
    var feather by remember { mutableStateOf(true) }
    var tightCrop by remember { mutableStateOf(true) }
    val any = clearAlpha || blackToAlpha || despillGreen || feather || tightCrop
    // AlertDialog renders in its own popup window; re-provide LocalContext/LocalConfiguration
    // so stringResource() honors the in-app language toggle.
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) { Text(stringResource(R.string.wardrobe_fix_cutout_bg)) }
        },
        text = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                Column {
                    @Composable
                    fun Row(labelRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(labelRes), modifier = Modifier.weight(1f))
                            Switch(checked = checked, onCheckedChange = onChange)
                        }
                    }
                    Row(R.string.settings_cutoutfix_action_clearalpha, clearAlpha) { clearAlpha = it }
                    Row(R.string.settings_cutoutfix_action_blackbg, blackToAlpha) { blackToAlpha = it }
                    Row(R.string.settings_cutoutfix_action_greenhalo, despillGreen) { despillGreen = it }
                    Row(R.string.settings_cutoutfix_action_feather, feather) { feather = it }
                    Row(R.string.settings_cutoutfix_action_tightcrop, tightCrop) { tightCrop = it }
                }
            }
        },
        confirmButton = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                TextButton(
                    enabled = any,
                    onClick = {
                        onConfirm(CutoutFixActions(
                            blackToAlpha = blackToAlpha,
                            despillGreen = despillGreen,
                            feather = feather,
                            tightCrop = tightCrop,
                            clearAlpha = clearAlpha,
                        ))
                    },
                ) { Text(stringResource(R.string.action_ok)) }
            }
        },
        dismissButton = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

