package com.librelookai.tryon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.librelookai.R
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.Trip
import com.librelookai.ui.theme.LocalWardrobePalette
import com.librelookai.util.rememberDialogBottomInset
import com.librelookai.wardrobe.DriveImage
import java.io.File

/** A trip's day-outfit resolved for the picker: day number (1-based) + the backing [Outfit]. */
private data class TripDay(val day: Int, val outfit: Outfit)
private data class PickableTrip(val trip: Trip, val days: List<TripDay>)

/**
 * Fullscreen picker for starting a try-on from a planned trip. Lists each trip whose day-outfits
 * still resolve to wardrobe items, with a row of day cards; tapping a day picks that day's outfit.
 * Sits above the Quick sheet as its own Dialog (same pattern as [com.librelookai.tryon] OutfitPicker).
 */
@Composable
fun TripOutfitPickerDialog(
    trips: List<Trip>,
    outfits: List<Outfit>,
    wardrobeImages: List<DriveImage>,
    onPick: (tripName: String, day: Int, outfit: Outfit) -> Unit,
    onDismiss: () -> Unit,
) {
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    // Captured OUTSIDE the Dialog so the nav-bar inset is real (see rememberDialogBottomInset).
    val effectiveBottom = rememberDialogBottomInset()
    val outfitsById = remember(outfits) { outfits.associateBy { it.id } }
    val itemsById = remember(wardrobeImages) { wardrobeImages.associateBy { it.driveId } }
    // Keep only trips with at least one day whose outfit is fully loaded — try-on needs the cutouts.
    val pickable = remember(trips, outfitsById, itemsById) {
        trips.mapNotNull { trip ->
            val days = trip.outfitIds.mapIndexedNotNull { idx, oid ->
                val o = outfitsById[oid] ?: return@mapIndexedNotNull null
                if (o.itemIds.isNotEmpty() && o.itemIds.all { it in itemsById }) TripDay(idx + 1, o) else null
            }
            if (days.isEmpty()) null else PickableTrip(trip, days)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
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
                Column(Modifier.fillMaxSize().padding(bottom = effectiveBottom)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                        Text(
                            stringResource(R.string.tryon_trip_picker_title),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        com.librelookai.ViewerHeaderActions(onBeforeNavigate = onDismiss)
                    }
                    HorizontalDivider()
                    if (pickable.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.tryon_trip_picker_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 32.dp),
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            items(pickable, key = { it.trip.id }) { pt ->
                                TripPickerCard(
                                    pickable = pt,
                                    itemsById = itemsById,
                                    onPickDay = { td -> onPick(pt.trip.name, td.day, td.outfit) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TripPickerCard(
    pickable: PickableTrip,
    itemsById: Map<String, DriveImage>,
    onPickDay: (TripDay) -> Unit,
) {
    val palette = LocalWardrobePalette.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = palette.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.divider),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                pickable.trip.name.ifBlank { pickable.trip.destination.ifBlank { stringResource(R.string.trip_viewer_title) } },
                color = palette.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pickable.days, key = { it.day }) { td ->
                    DayCard(td = td, itemsById = itemsById, onClick = { onPickDay(td) })
                }
            }
        }
    }
}

@Composable
private fun DayCard(
    td: TripDay,
    itemsById: Map<String, DriveImage>,
    onClick: () -> Unit,
) {
    val palette = LocalWardrobePalette.current
    val context = LocalContext.current
    val firstItem = td.outfit.itemIds.firstNotNullOfOrNull { itemsById[it] }
    Column(
        modifier = Modifier
            .size(width = 78.dp, height = 112.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.surface2)
            .border(1.dp, palette.divider, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (firstItem != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(File(firstItem.localPath)).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            SourceDot(TryOnSourceKind.TRAVEL, Modifier.align(Alignment.TopEnd).padding(5.dp))
        }
        Text(
            stringResource(R.string.travel_day, td.day),
            color = palette.text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 5.dp),
        )
    }
}
