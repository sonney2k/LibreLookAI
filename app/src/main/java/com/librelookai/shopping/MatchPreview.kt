package com.librelookai.shopping

import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.librelookai.R
import com.librelookai.util.LocalSystemBarsPadding
import com.librelookai.wardrobe.DriveImage
import java.io.File

@Composable
internal fun MatchRow(match: ShopMatch, onClick: () -> Unit) {
    val context = LocalContext.current
    val percent = (match.score.coerceIn(0f, 1f) * 100f).toInt()
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
 * Match preview dialog used by both the Similarity Finder and the wardrobe's "Find by photo"
 * entry point. Pages horizontally over [matches] starting at [initialIndex]. The default render
 * shows the match image + score header + action buttons; when [showDebug] is true (Settings →
 * AI tab toggle) the per-channel breakdown is appended above the action buttons.
 */
@Composable
internal fun MatchPreviewDialog(
    matches: List<ShopMatch>,
    initialIndex: Int,
    queryRawPath: String?,
    queryProcessedPath: String?,
    querySegmented: Boolean,
    queryHist: FloatArray?,
    queryVec: FloatArray?,
    queryPHash: Long?,
    showDebug: Boolean,
    onShowInWardrobe: (DriveImage) -> Unit,
    onAddToShoppingList: () -> Unit,
    canAddToShoppingList: Boolean,
    showActions: Boolean = true,
    onDismiss: () -> Unit,
) {
    val barInsets = LocalSystemBarsPadding.current
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
      // Force the dialog window to fill the screen edge-to-edge. Without setDecorFitsSystemWindows
      // the window may be inset above the navigation bar in 3-button nav mode, while our captured
      // barInsets bottom padding still adds nav-bar height — net effect: action row clipped.
      val dialogView = LocalView.current
      SideEffect {
          val window = (dialogView.parent as? DialogWindowProvider)?.window ?: return@SideEffect
          window.setLayout(
              android.view.ViewGroup.LayoutParams.MATCH_PARENT,
              android.view.ViewGroup.LayoutParams.MATCH_PARENT,
          )
          androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
      }
      CompositionLocalProvider(
          LocalContext provides parentContext,
          LocalConfiguration provides parentConfiguration,
      ) {
        val pagerState = rememberPagerState(
            initialPage = initialIndex.coerceIn(0, (matches.size - 1).coerceAtLeast(0)),
            pageCount = { matches.size },
        )

        // Bottom inset for the pager + floating action stack — same pattern as the
        // wardrobe FullScreenViewer so the image fills the screen edge-to-edge while
        // the close X (top-left) and action FABs (bottom-right) overlay it.
        val pagerView = androidx.compose.ui.platform.LocalView.current
        val pagerDensity = androidx.compose.ui.platform.LocalDensity.current
        val rootInsetBottomDp = remember(pagerView) {
            val raw = pagerView.rootWindowInsets
            val bottomPx = if (raw != null) {
                androidx.core.view.WindowInsetsCompat
                    .toWindowInsetsCompat(raw, pagerView)
                    .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    .bottom
            } else 0
            with(pagerDensity) { bottomPx.toDp() }
        }
        val effectiveBottom = maxOf(
            barInsets.calculateBottomPadding(),
            rootInsetBottomDp,
            48.dp,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = effectiveBottom),
            ) { page ->
                val match = matches[page]
                if (showDebug) {
                    MatchDebugPage(
                        match = match,
                        queryRawPath = queryRawPath,
                        queryProcessedPath = queryProcessedPath,
                        querySegmented = querySegmented,
                        queryHist = queryHist,
                        queryVec = queryVec,
                        queryPHash = queryPHash,
                    )
                } else {
                    MatchDefaultPage(match = match)
                }
            }

            // Close X overlay (top-left).
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = barInsets.calculateTopPadding())
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_close),
                    tint = Color.White,
                )
            }

            // Action FABs (bottom-right).
            if (showActions) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = effectiveBottom)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (canAddToShoppingList) {
                        ExtendedFloatingActionButton(
                            onClick = onAddToShoppingList,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            icon = { Icon(Icons.Default.AddShoppingCart, contentDescription = null) },
                            text = { Text(stringResource(R.string.shop_add_to_shopping_list)) },
                        )
                    }
                    ExtendedFloatingActionButton(
                        onClick = { onShowInWardrobe(matches[pagerState.currentPage].image) },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        icon = { Icon(Icons.Default.Checkroom, contentDescription = null) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.shop_show_in_wardrobe))
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                    )
                }
            }
        }
      }
    }
}

@Composable
private fun MatchDefaultPage(match: ShopMatch) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        ZoomableMatchImage(file = File(match.image.localPath))
    }
}

