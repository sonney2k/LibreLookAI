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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.librelookai.R
import com.librelookai.data.model.TryOn
import com.librelookai.ui.theme.LocalWardrobePalette
import com.librelookai.util.Analytics
import com.librelookai.wardrobe.DriveImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun TryOn.kind(): TryOnSourceKind = tryOnSourceKindOf(sourceKind)

@Composable
private fun TryOn.provenanceLabel(): String {
    val ctx = sourceContext.takeIf { it.isNotBlank() }
    return ctx ?: stringResource(sourceMeta(kind()).labelRes)
}

private fun resolveItems(tryOn: TryOn, images: List<DriveImage>): List<DriveImage> {
    // Match by an extension-agnostic key so `_cutout.png` itemNames still resolve against
    // `_cutout.webp` items (and vice versa) across the WebP migration.
    val byKey = images.associateBy { com.librelookai.util.ImageEncoding.itemMatchKey(it.name) }
    return tryOn.itemNames.mapNotNull { n -> byKey[com.librelookai.util.ImageEncoding.itemMatchKey(n)] }
}

/**
 * Past try-ons "hero feed": newest try-on as a large hero card, the next two as a 2-column
 * mid row, the rest in a dense 3-column grid. Renders the empty state when [history] is empty.
 *
 * Chrome (back/title) is supplied by the host (the composer Dialog's TopAppBar, or the Outfits
 * sub-tab). [showFab] adds the "New try-on" extended FAB; [onStartTryOn] also backs the empty CTA.
 */
@Composable
fun TryOnHistoryFeed(
    history: List<TryOn>,
    wardrobeImages: List<DriveImage>,
    onOpen: (TryOn) -> Unit,
    onStartTryOn: () -> Unit,
    modifier: Modifier = Modifier,
    showFab: Boolean = true,
    onEditHero: ((TryOn) -> Unit)? = null,
) {
    if (history.isEmpty()) {
        TryOnEmpty(onStartTryOn = onStartTryOn, modifier = modifier)
        return
    }
    val palette = LocalWardrobePalette.current
    val hero = history.first()
    val mid = history.drop(1).take(2)
    val dense = history.drop(3)

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(bottom = 100.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            HeroCard(
                tryOn = hero,
                items = resolveItems(hero, wardrobeImages),
                onClick = {
                    Analytics.action("TryOn/History", "open_hero")
                    onOpen(hero)
                },
                onEdit = onEditHero?.let { { it(hero) } },
            )

            if (mid.isNotEmpty() || dense.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.tryon_history_earlier).uppercase(),
                        color = palette.textMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                    )
                    Text(
                        "· " + stringResource(R.string.tryon_history_more_count, mid.size + dense.size),
                        color = palette.textMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (mid.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    mid.forEachIndexed { i, t ->
                        MidCard(
                            tryOn = t,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                Analytics.action("TryOn/History", "open_card", mapOf("index" to (i + 1).toString()))
                                onOpen(t)
                            },
                        )
                    }
                    if (mid.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            if (dense.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                dense.chunked(3).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowItems.forEach { t ->
                            SmallCard(
                                tryOn = t,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    Analytics.action("TryOn/History", "open_card")
                                    onOpen(t)
                                },
                            )
                        }
                        repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        if (showFab) {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = {
                    Analytics.action("TryOn/History", "open_quick_sheet_from_fab")
                    onStartTryOn()
                },
                containerColor = palette.fabBg,
                contentColor = palette.fabFg,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 22.dp),
            ) {
                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tryon_history_new), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun HeroCard(
    tryOn: TryOn,
    items: List<DriveImage>,
    onClick: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    val palette = LocalWardrobePalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(palette.surface)
            .border(1.dp, palette.divider, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 5f)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)),
        ) {
            TryOnImage(tryOn, ContentScale.Crop, Modifier.fillMaxSize())

            SourcePill(
                kind = tryOn.kind(),
                label = tryOn.provenanceLabel(),
                solid = true,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            )
            onEdit?.let { edit ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.8f))
                        .clickable(onClick = edit),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Edit, null, tint = palette.text, modifier = Modifier.size(14.dp))
                }
            }
            // Bottom gradient + metadata.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.4f))))
                    .padding(start = 14.dp, end = 14.dp, top = 40.dp, bottom = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            heroDateTime(tryOn.createdAt).uppercase(),
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.4.sp,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            tryOn.provenanceLabel(),
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ItemStack(items)
                }
            }
        }
    }
}

/** Three overlapping, slightly-rotated item thumbnails over the hero's bottom-right. */
@Composable
private fun ItemStack(items: List<DriveImage>) {
    if (items.isEmpty()) return
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
        items.take(3).forEachIndexed { i, img ->
            Box(
                modifier = Modifier
                    .graphicsLayer { rotationZ = if (i == 0) 0f else if (i == 1) -4f else 4f }
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .border(2.dp, Color.White, RoundedCornerShape(9.dp)),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(File(img.localPath)).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun MidCard(tryOn: TryOn, modifier: Modifier, onClick: () -> Unit) {
    val palette = LocalWardrobePalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(palette.surface)
            .border(1.dp, palette.divider, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)),
        ) {
            TryOnImage(tryOn, ContentScale.Crop, Modifier.fillMaxSize())
            SourcePill(
                kind = tryOn.kind(),
                label = tryOn.provenanceLabel(),
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            )
        }
        Column(Modifier.padding(start = 9.dp, end = 9.dp, top = 7.dp, bottom = 9.dp)) {
            Text(
                tryOn.provenanceLabel(),
                color = palette.text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(shortDate(tryOn.createdAt), color = palette.textMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun SmallCard(tryOn: TryOn, modifier: Modifier, onClick: () -> Unit) {
    val palette = LocalWardrobePalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(palette.surface)
            .border(1.dp, palette.divider, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
        ) {
            TryOnImage(tryOn, ContentScale.Crop, Modifier.fillMaxSize())
            SourceDot(tryOn.kind(), Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Text(
            shortDay(tryOn.createdAt),
            color = palette.textMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 7.dp),
        )
    }
}

@Composable
private fun TryOnImage(tryOn: TryOn, contentScale: ContentScale, modifier: Modifier) {
    val palette = LocalWardrobePalette.current
    if (tryOn.localPath.isNotEmpty()) {
        val context = LocalContext.current
        AsyncImage(
            model = remember(tryOn.imageDriveId, tryOn.localPath) {
                ImageRequest.Builder(context)
                    .data(File(tryOn.localPath))
                    .memoryCacheKey("tryon_${tryOn.imageDriveId}")
                    .build()
            },
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier,
        )
    } else {
        Box(modifier.background(palette.surface2))
    }
}

/** Empty state (README 5a): illustration tile + title + body + CTA + informational chips. */
@Composable
fun TryOnEmpty(onStartTryOn: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalWardrobePalette.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 170.dp, height = 200.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(palette.surface)
                .border(1.dp, palette.border, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(palette.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.AutoAwesome, null, tint = palette.onPrimary, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.tryon_empty_title),
            color = palette.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.tryon_empty_body),
            color = palette.textMuted,
            fontSize = 13.sp,
            modifier = Modifier.widthIn(max = 260.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        androidx.compose.material3.Button(
            onClick = {
                Analytics.action("TryOn/Empty", "cta_tap")
                onStartTryOn()
            },
            shape = RoundedCornerShape(23.dp),
            modifier = Modifier.height(46.dp),
        ) {
            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.tryon_empty_cta), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoChip(stringResource(R.string.tryon_empty_chip_outfit))
            InfoChip(stringResource(R.string.tryon_empty_chip_wardrobe))
            InfoChip(stringResource(R.string.tryon_empty_chip_shopping))
        }
    }
}

@Composable
private fun InfoChip(label: String) {
    val palette = LocalWardrobePalette.current
    Text(
        label,
        color = palette.textMid,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(palette.surface)
            .border(1.dp, palette.divider, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

private fun heroDateTime(ts: Long): String =
    SimpleDateFormat("MMM d · h:mm a", Locale.getDefault()).format(Date(ts))

private fun shortDate(ts: Long): String =
    SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ts))

private fun shortDay(ts: Long): String =
    SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ts))
