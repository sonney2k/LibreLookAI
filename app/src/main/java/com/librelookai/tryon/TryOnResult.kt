package com.librelookai.tryon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.librelookai.R
import com.librelookai.core.designsystem.R as DsR
import com.librelookai.wardrobe.DriveImage
import java.io.File

@Composable
internal fun TryOnResultContent(
    state: TryOnUiState,
    onSave: () -> Unit,
    onTryAgain: () -> Unit,
    onChangeItems: () -> Unit,
    wardrobeImages: List<DriveImage>,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val context = LocalContext.current
    val items = remember(state.itemIds, wardrobeImages) {
        state.itemIds.mapNotNull { id -> wardrobeImages.firstOrNull { it.driveId == id } }
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.size(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 5f)
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.dp, palette.divider, RoundedCornerShape(22.dp))
                    .background(Color.Black),
            ) {
                ZoomableImage(file = File(state.resultPath!!))
                if (state.sourceKind != TryOnSourceKind.NONE) {
                    SourcePill(
                        kind = state.sourceKind,
                        label = state.sourceContext?.takeIf { it.isNotBlank() }
                            ?: stringResource(sourceMeta(state.sourceKind).labelRes),
                        solid = true,
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                    )
                }
                Text(
                    stringResource(R.string.tryon_result_zoom_hint),
                    color = palette.textMid,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.85f))
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
            if (items.isNotEmpty()) {
                Text(
                    stringResource(R.string.tryon_result_items_worn).uppercase(),
                    color = palette.textMid,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.take(5).forEach { img ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(11.dp))
                                .border(1.dp, palette.divider, RoundedCornerShape(11.dp)),
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(File(img.localPath)).build(),
                                contentDescription = img.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    repeat((5 - items.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.size(4.dp))
        }
        HorizontalDivider()
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSave,
                    enabled = !state.isSaving && !state.isResultSaved,
                    shape = RoundedCornerShape(23.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.tryon_saving))
                    } else if (state.isResultSaved) {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.tryon_saved_to_drive))
                    } else {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.tryon_save_to_drive))
                    }
                }
                OutlinedButton(
                    onClick = onTryAgain,
                    enabled = !state.isGenerating,
                    shape = RoundedCornerShape(23.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(DsR.string.tryon_try_again))
                }
            }
            TextButton(onClick = onChangeItems, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.tryon_change_items))
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** Composer source banner — eyebrow + title + a Swap pill that reopens the Quick sheet. */
@Composable
internal fun SourceBanner(
    kind: TryOnSourceKind,
    title: String,
    onSwap: () -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val meta = sourceMeta(kind)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = palette.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.divider),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(meta.tint.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(meta.icon, null, tint = meta.tint, modifier = Modifier.size(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(meta.labelRes).uppercase(),
                    color = palette.textMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp,
                )
                Text(
                    title,
                    color = palette.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(
                onClick = onSwap,
                shape = RoundedCornerShape(999.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp),
            ) {
                Text(stringResource(R.string.tryon_source_swap), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Inline preview of the user's reference photos so they know what Gemini will work from. */
@Composable
internal fun ReferencePhotosPreview(
    photoPaths: List<String>,
    onEdit: () -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = palette.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.divider),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, null, tint = palette.textMid, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.tryon_reference_photos_title),
                    color = palette.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.tryon_reference_photos_edit),
                    color = palette.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onEdit),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                photoPaths.take(3).forEach { path ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, palette.divider, RoundedCornerShape(10.dp)),
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(File(path)).build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                repeat((3 - photoPaths.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** State 5b — no reference photos uploaded. Presents the upload requirement inline. */
@Composable
internal fun TryOnNoPhotos(onOpenSettings: () -> Unit) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = palette.primaryDim,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
            ) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, null, tint = palette.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.tryon_photos_eyebrow).uppercase(),
                            color = palette.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                        )
                    }
                    Text(
                        stringResource(R.string.tryon_photos_title),
                        color = palette.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.tryon_photos_body),
                        color = palette.textMid,
                        fontSize = 12.sp,
                    )
                }
            }
            Text(
                stringResource(R.string.tryon_photos_section_label).uppercase(),
                color = palette.textMid,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    stringResource(R.string.tryon_slot_front),
                    stringResource(R.string.tryon_slot_side),
                    stringResource(R.string.tryon_slot_back),
                ).forEachIndexed { i, slotLabel ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.5.dp, palette.border, RoundedCornerShape(14.dp))
                            .background(palette.surface)
                            .clickable(onClick = onOpenSettings),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Default.Add, null, tint = palette.textMid, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(slotLabel, color = palette.text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (i == 2) {
                            Text(stringResource(R.string.tryon_photos_optional), color = palette.textMuted, fontSize = 9.sp)
                        }
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = palette.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.divider),
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.AutoAwesome, null, tint = palette.primary, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.tryon_photos_tip),
                        color = palette.textMid,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        HorizontalDivider()
        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            OutlinedButton(
                onClick = onOpenSettings,
                shape = RoundedCornerShape(25.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Text(stringResource(R.string.tryon_photos_open_settings), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// Try-on generation now uses the shared util/AiProcessingOverlay (live upload bar +
// wait estimate + elapsed counter), so the bespoke generating card was removed.

/** One page of the swipeable history detail pager — resolves the source outfit and wires the
 *  per-try-on actions onto [TryOnDetailContent]. */
