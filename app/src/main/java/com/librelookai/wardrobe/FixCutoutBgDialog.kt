package com.librelookai.wardrobe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import com.librelookai.R
import com.librelookai.core.designsystem.R as DsR
import com.librelookai.util.LocalSystemBarsPadding
import java.io.File

@Composable
internal fun FixCutoutBgDialog(
    state: CutoutBgFixProgress,
    onToggleSelection: (String) -> Unit,
    onSetSelection: (Set<String>) -> Unit,
    onSetShowAll: (Boolean) -> Unit,
    onSetAction: (Boolean?, Boolean?, Boolean?, Boolean?) -> Unit,
    fetchThumbnail: suspend (CutoutFixEntry) -> File?,
    onFix: () -> Unit,
    onCancel: () -> Unit,
) {
    val visible = remember(state.items, state.showAll, state.flaggedIds) {
        if (state.showAll) state.items else state.items.filter { it.driveId in state.flaggedIds }
    }
    val visibleIds = remember(visible) { visible.map { it.driveId }.toSet() }
    val selectedVisible = state.selectedIds.intersect(visibleIds)

    val barInsets = LocalSystemBarsPadding.current
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val view = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rootInsetBottomDp = remember(view) {
        val raw = view.rootWindowInsets
        val bottomPx = if (raw != null) {
            androidx.core.view.WindowInsetsCompat
                .toWindowInsetsCompat(raw, view)
                .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                .bottom
        } else 0
        with(density) { bottomPx.toDp() }
    }
    val effectiveBottom = maxOf(
        barInsets.calculateBottomPadding(),
        rootInsetBottomDp,
        48.dp,
    )

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
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
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = barInsets.calculateTopPadding()),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(DsR.string.action_close))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_cutoutfix_dialog_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.settings_cutoutfix_dialog_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()

                @Composable
                fun ActionToggleRow(labelRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(labelRes),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = checked, onCheckedChange = onChange)
                    }
                }
                ActionToggleRow(R.string.settings_cutoutfix_action_blackbg, state.applyBlackToAlpha) {
                    onSetAction(it, null, null, null)
                }
                ActionToggleRow(R.string.settings_cutoutfix_action_greenhalo, state.applyDespillGreen) {
                    onSetAction(null, it, null, null)
                }
                ActionToggleRow(R.string.settings_cutoutfix_action_feather, state.applyFeather) {
                    onSetAction(null, null, it, null)
                }
                ActionToggleRow(R.string.settings_cutoutfix_action_tightcrop, state.applyTightCrop) {
                    onSetAction(null, null, null, it)
                }
                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.settings_cutoutfix_show_all),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.showAll,
                        onCheckedChange = onSetShowAll,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(
                            R.string.settings_cutoutfix_selected,
                            selectedVisible.size,
                            visible.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (selectedVisible.size < visible.size) {
                        TextButton(
                            onClick = { onSetSelection(state.selectedIds + visibleIds) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(stringResource(R.string.settings_repair_preview_select_all))
                        }
                    }
                    if (selectedVisible.isNotEmpty()) {
                        TextButton(
                            onClick = { onSetSelection(state.selectedIds - visibleIds) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(stringResource(R.string.settings_repair_preview_deselect_all))
                        }
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp),
                ) {
                    items(visible, key = { it.driveId }) { entry ->
                        FixCutoutBgTile(
                            entry = entry,
                            isSelected = entry.driveId in state.selectedIds,
                            onToggle = { onToggleSelection(entry.driveId) },
                            fetchThumbnail = fetchThumbnail,
                        )
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = effectiveBottom)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel) {
                        Text(stringResource(DsR.string.action_cancel))
                    }
                    Spacer(Modifier.size(4.dp))
                    val anyAction = state.applyBlackToAlpha || state.applyDespillGreen ||
                        state.applyFeather || state.applyTightCrop
                    Button(
                        onClick = onFix,
                        enabled = state.selectedIds.isNotEmpty() && anyAction,
                    ) {
                        Text(stringResource(R.string.settings_cutoutfix_fix_n, state.selectedIds.size))
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun FixCutoutBgTile(
    entry: CutoutFixEntry,
    isSelected: Boolean,
    onToggle: () -> Unit,
    fetchThumbnail: suspend (CutoutFixEntry) -> File?,
) {
    val thumbFile by produceState<File?>(initialValue = null, entry.driveId) {
        value = fetchThumbnail(entry)
    }
    Box(
        modifier = Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onToggle),
    ) {
        if (thumbFile != null) {
            AsyncImage(
                model = thumbFile,
                contentDescription = entry.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.3f)),
                contentAlignment = Alignment.TopEnd,
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(4.dp),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.TopEnd,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(4.dp).size(18.dp),
                )
            }
        }
        // Issue badges in bottom-start corner.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (entry.hasBlackBackground) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        stringResource(R.string.settings_cutoutfix_badge_blackbg),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (entry.hasGreenHalo) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        stringResource(R.string.settings_cutoutfix_badge_greenhalo),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
