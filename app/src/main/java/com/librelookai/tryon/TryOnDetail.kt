package com.librelookai.tryon

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.librelookai.R
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.TryOn
import com.librelookai.util.Analytics
import com.librelookai.wardrobe.DriveImage
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
internal fun TryOnDetailPage(
    tryOn: TryOn,
    combinedImages: List<DriveImage>,
    outfits: List<Outfit>,
    onOpenSourceOutfit: ((Outfit) -> Unit)?,
    onItemTap: (DriveImage) -> Unit,
    tryOnViewModel: TryOnViewModel,
    /** Navigate to the composer destination after Regenerate seeds the draft (§ 5 slice 9). */
    onOpenComposer: () -> Unit = {},
) {
    val sourceOutfit = remember(tryOn.sourceOutfitId, outfits) {
        tryOn.sourceOutfitId?.let { id -> outfits.firstOrNull { it.id == id } }
    }
    TryOnDetailContent(
        tryOn = tryOn,
        wardrobeImages = combinedImages,
        sourceOutfit = sourceOutfit,
        onOpenSourceOutfit = onOpenSourceOutfit?.let { open -> { sourceOutfit?.let(open) } },
        onDelete = { tryOnViewModel.deleteTryOn(tryOn) },
        onItemTap = onItemTap,
        onRegenerate = {
            Analytics.action("TryOn/Detail", "regenerate")
            val byKey = combinedImages.associateBy { com.librelookai.util.ImageEncoding.itemMatchKey(it.name) }
            val ids = tryOn.itemNames
                .mapNotNull { n -> byKey[com.librelookai.util.ImageEncoding.itemMatchKey(n)] }
                .map { it.driveId }.toSet()
            tryOnViewModel.openComposer(
                ids, tryOn.sourceOutfitId,
                tryOnSourceKindOf(tryOn.sourceKind),
                tryOn.sourceContext.takeIf { it.isNotBlank() },
            )
            onOpenComposer()
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TryOnDetailContent(
    tryOn: TryOn,
    wardrobeImages: List<DriveImage>,
    sourceOutfit: Outfit?,
    onOpenSourceOutfit: (() -> Unit)?,
    onDelete: () -> Unit,
    onItemTap: (DriveImage) -> Unit,
    onRegenerate: () -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    var confirmDelete by remember { mutableStateOf(false) }
    val kind = tryOnSourceKindOf(tryOn.sourceKind)
    val items = remember(tryOn.itemNames, wardrobeImages) {
        val byKey = wardrobeImages.associateBy { com.librelookai.util.ImageEncoding.itemMatchKey(it.name) }
        tryOn.itemNames.mapNotNull { n -> byKey[com.librelookai.util.ImageEncoding.itemMatchKey(n)] }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.size(0.dp))
            // Image card.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 5f)
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.dp, palette.divider, RoundedCornerShape(22.dp))
                    .background(Color.Black),
            ) {
                if (tryOn.localPath.isNotEmpty()) {
                    ZoomableImage(file = File(tryOn.localPath))
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
                SourcePill(
                    kind = kind,
                    label = tryOn.sourceContext.takeIf { it.isNotBlank() }
                        ?: stringResource(sourceMeta(kind).labelRes),
                    solid = true,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                )
            }
            // Metadata card.
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = palette.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.divider),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text(
                            stringResource(R.string.tryon_detail_generated).uppercase(),
                            color = palette.textMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.4.sp, modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(R.string.tryon_detail_source).uppercase(),
                            color = palette.textMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.4.sp,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(tryOn.createdAt)),
                            color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        if (sourceOutfit != null && onOpenSourceOutfit != null) {
                            Text(
                                stringResource(R.string.tryon_view_source_outfit),
                                color = palette.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    Analytics.action("TryOn/Detail", "open_source_outfit")
                                    onOpenSourceOutfit()
                                },
                            )
                        } else {
                            Text(
                                tryOn.sourceContext.takeIf { it.isNotBlank() }
                                    ?: stringResource(sourceMeta(kind).labelRes),
                                color = palette.textMid, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            // Items worn — stacked overlapping cutouts, matching the composer. Tap to open.
            Text(
                stringResource(R.string.tryon_history_items_title),
                color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
            if (items.isEmpty()) {
                Text(
                    stringResource(R.string.tryon_history_items_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textMuted,
                )
            } else {
                TryOnItemStack(
                    images = items,
                    modifier = Modifier.fillMaxWidth(),
                    onItemClick = { img ->
                        Analytics.action("TryOn/Detail", "view_item")
                        onItemTap(img)
                    },
                )
            }
            Spacer(Modifier.size(0.dp))
        }
        // Sticky action row — pinned so Regenerate / Delete are always on screen.
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onRegenerate,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.weight(1f).height(44.dp),
            ) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.tryon_detail_regenerate), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = {
                    Analytics.action("TryOn/Detail", "open_delete_dialog")
                    confirmDelete = true
                },
                shape = RoundedCornerShape(22.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = palette.error),
                modifier = Modifier.weight(1f).height(44.dp),
            ) {
                Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_delete), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.tryon_delete_confirm_title)) },
            text  = { Text(stringResource(R.string.tryon_delete_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    Analytics.action("TryOn/Detail", "confirm_delete")
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

}

/** Pinch-zoom & pan image, saturating at [1x .. 6x]. */
@Composable
internal fun ZoomableImage(file: File) {
    val context = LocalContext.current
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    AsyncImage(
        model = ImageRequest.Builder(context).data(file).build(),
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

