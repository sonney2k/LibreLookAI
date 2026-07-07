package com.librelookai.tryon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librelookai.feature.tryon.R
import com.librelookai.ui.theme.LocalWardrobePalette

/**
 * Per-source accent tints (README "Source tints" table). Kept independent of the active
 * [com.librelookai.ui.theme.WardrobePalette] so a try-on's provenance reads consistently across
 * themes; they are mid-saturation hues that sit acceptably on both light and dark surfaces.
 */
object SourceColors {
    val outfit   = Color(0xFF7BBD6C)
    val wardrobe = Color(0xFFA8C09C)
    val shopping = Color(0xFFC9A65A)
    val travel   = Color(0xFF8AB8D8)

    fun tint(kind: TryOnSourceKind): Color = when (kind) {
        TryOnSourceKind.SHOPPING -> shopping
        TryOnSourceKind.WARDROBE -> wardrobe
        TryOnSourceKind.TRAVEL   -> travel
        else                     -> outfit
    }
}

/** Icon + eyebrow label resource for a source kind, shared by the sheet, banner and history. */
data class SourceMeta(val icon: ImageVector, val labelRes: Int, val tint: Color)

fun sourceMeta(kind: TryOnSourceKind): SourceMeta = when (kind) {
    TryOnSourceKind.WARDROBE -> SourceMeta(Icons.Default.Tune, R.string.tryon_source_label_wardrobe, SourceColors.wardrobe)
    TryOnSourceKind.SHOPPING -> SourceMeta(Icons.Default.ShoppingBag, R.string.tryon_source_label_shopping, SourceColors.shopping)
    TryOnSourceKind.TRAVEL   -> SourceMeta(Icons.Default.FlightTakeoff, R.string.tryon_source_label_travel, SourceColors.travel)
    else                     -> SourceMeta(Icons.Default.Style, R.string.tryon_source_label_outfit, SourceColors.outfit)
}

/**
 * Provenance chip shared across the history feed, composer banner, and result/detail overlays.
 *
 * [solid] = filled variant used over images / in the composer banner (tinted bg + border + icon).
 * Otherwise the subtle history-card variant (surface bg, divider border, a tint dot + muted label).
 */
@Composable
fun SourcePill(
    kind: TryOnSourceKind,
    label: String,
    modifier: Modifier = Modifier,
    solid: Boolean = false,
) {
    val palette = LocalWardrobePalette.current
    val meta = sourceMeta(kind)
    val tint = meta.tint
    val shape = RoundedCornerShape(999.dp)
    if (solid) {
        Row(
            modifier = modifier
                .clip(shape)
                .background(tint.copy(alpha = 0.13f))
                .border(1.dp, tint.copy(alpha = 0.27f), shape)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(meta.icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
            Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    } else {
        Row(
            modifier = modifier
                .clip(shape)
                .background(palette.surface)
                .border(1.dp, palette.divider, shape)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(tint),
            )
            Text(label, color = palette.textMid, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Small colored dot used in the top-right of the dense history "small" cards. */
@Composable
fun SourceDot(kind: TryOnSourceKind, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color.White)
            .padding(2.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(SourceColors.tint(kind)),
    )
}

/** The eyebrow/banner label for a source kind, e.g. "From outfit". */
@Composable
fun sourceEyebrow(kind: TryOnSourceKind): String = stringResource(sourceMeta(kind).labelRes)
