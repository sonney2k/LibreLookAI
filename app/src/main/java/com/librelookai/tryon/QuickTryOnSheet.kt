package com.librelookai.tryon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librelookai.R
import com.librelookai.ui.theme.LocalWardrobePalette

/**
 * Modal bottom sheet opened from the center AI button. Disambiguates where the user wants to
 * start a try-on (outfit / wardrobe / shopping / travel) and offers a shortcut into history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickTryOnSheet(
    onPickOutfit: () -> Unit,
    onPickWardrobe: () -> Unit,
    onPickShopping: () -> Unit,
    onPickTravel: () -> Unit,
    onSeeHistory: () -> Unit,
    onDismiss: () -> Unit,
    showTravel: Boolean = false,
) {
    val palette = LocalWardrobePalette.current
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.bg,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
    ) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 24.dp),
            ) {
                Text(
                    stringResource(R.string.tryon_quick_sheet_title),
                    fontSize = 27.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = palette.text,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.tryon_quick_sheet_subtitle),
                    fontSize = 18.sp,
                    color = palette.textMuted,
                )
                Spacer(Modifier.height(14.dp))

                SourceOptionRow(
                    kind = TryOnSourceKind.OUTFIT,
                    label = stringResource(R.string.tryon_quick_source_outfit),
                    sub = stringResource(R.string.tryon_quick_source_outfit_sub),
                    onClick = onPickOutfit,
                )
                Spacer(Modifier.height(8.dp))
                SourceOptionRow(
                    kind = TryOnSourceKind.WARDROBE,
                    label = stringResource(R.string.tryon_quick_source_wardrobe),
                    sub = stringResource(R.string.tryon_quick_source_wardrobe_sub),
                    onClick = onPickWardrobe,
                )
                Spacer(Modifier.height(8.dp))
                SourceOptionRow(
                    kind = TryOnSourceKind.SHOPPING,
                    label = stringResource(R.string.tryon_quick_source_shopping),
                    sub = stringResource(R.string.tryon_quick_source_shopping_sub),
                    onClick = onPickShopping,
                )
                // Travel row is hidden entirely when the user has no trips with outfits yet.
                if (showTravel) {
                    Spacer(Modifier.height(8.dp))
                    SourceOptionRow(
                        kind = TryOnSourceKind.TRAVEL,
                        label = stringResource(R.string.tryon_quick_source_travel),
                        sub = stringResource(R.string.tryon_quick_source_travel_avail),
                        onClick = onPickTravel,
                    )
                }

                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onSeeHistory,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Icon(Icons.Default.History, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tryon_quick_see_history), fontSize = 21.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceOptionRow(
    kind: TryOnSourceKind,
    label: String,
    sub: String,
    onClick: () -> Unit,
) {
    val palette = LocalWardrobePalette.current
    val meta = sourceMeta(kind)
    val tint = meta.tint
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.surface)
            .border(1.dp, palette.divider, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(tint.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(meta.icon, null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = palette.text)
            Spacer(Modifier.height(1.dp))
            Text(sub, fontSize = 17.sp, color = palette.textMuted)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            null,
            tint = palette.textMuted,
            modifier = Modifier.size(16.dp),
        )
    }
}
