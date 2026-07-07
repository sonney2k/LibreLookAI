package com.librelookai.wardrobe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librelookai.R
import com.librelookai.core.designsystem.R as DsR
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.TryOn
import com.librelookai.util.Analytics

/**
 * Cascade-aware delete confirmation for wardrobe items: shows how many outfits / try-ons
 * reference the items about to go (matched by Drive ID *and* by stable cutout filename) and
 * lets the user cascade-delete them too. Shared by the wardrobe grid's selection delete and
 * the item-viewer destination's single delete.
 */
@Composable
internal fun DeleteItemsConfirmDialog(
    ids: Set<String>,
    images: List<DriveImage>,
    outfits: List<Outfit>,
    tryOns: List<TryOn>,
    onDeleteItems: (Set<String>) -> Unit,
    onDeleteOutfits: (List<String>) -> Unit,
    onDeleteTryOns: (List<TryOn>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Items being deleted, by Drive ID and by stable cutout filename — outfits/try-ons
    // reference items by both, so match on either.
    val deletingNames = remember(ids, images) {
        images.filter { it.driveId in ids }.map { it.name }.toSet()
    }
    val affectedOutfits = remember(ids, deletingNames, outfits) {
        outfits.filter { o -> o.itemIds.any { it in ids } || o.itemNames.any { it in deletingNames } }
    }
    val affectedTryOns = remember(ids, deletingNames, tryOns) {
        tryOns.filter { t -> t.itemIds.any { it in ids } || t.itemNames.any { it in deletingNames } }
    }
    var cascadeOutfits by remember(ids) { mutableStateOf(true) }
    var cascadeTryOns by remember(ids) { mutableStateOf(true) }
    // Re-provide parent context/config so the dialog window honours the in-app language
    // toggle (an AlertDialog opens its own window; see CLAUDE.md → Dialog quirks).
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) { Text(stringResource(R.string.wardrobe_delete_title)) }
        },
        text = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                Column {
                    Text(stringResource(R.string.wardrobe_delete_text, ids.size))
                    if (affectedOutfits.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { cascadeOutfits = !cascadeOutfits },
                        ) {
                            Checkbox(checked = cascadeOutfits, onCheckedChange = { cascadeOutfits = it })
                            Text(stringResource(R.string.wardrobe_delete_cascade_outfits, affectedOutfits.size))
                        }
                    }
                    if (affectedTryOns.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { cascadeTryOns = !cascadeTryOns },
                        ) {
                            Checkbox(checked = cascadeTryOns, onCheckedChange = { cascadeTryOns = it })
                            Text(stringResource(R.string.wardrobe_delete_cascade_tryons, affectedTryOns.size))
                        }
                    }
                }
            }
        },
        confirmButton = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                TextButton(
                    onClick = {
                        Analytics.action(
                            "Wardrobe", "confirm_delete_selected",
                            mapOf(
                                "count" to ids.size.toString(),
                                "outfits" to (if (cascadeOutfits) affectedOutfits.size else 0).toString(),
                                "tryons" to (if (cascadeTryOns) affectedTryOns.size else 0).toString(),
                            ),
                        )
                        onDeleteItems(ids)
                        if (cascadeOutfits && affectedOutfits.isNotEmpty()) onDeleteOutfits(affectedOutfits.map { it.id })
                        if (cascadeTryOns && affectedTryOns.isNotEmpty()) onDeleteTryOns(affectedTryOns)
                        onDismiss()
                    }
                ) {
                    Text(stringResource(DsR.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(DsR.string.action_cancel))
                }
            }
        }
    )
}
