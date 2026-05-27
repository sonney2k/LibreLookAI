package com.librelookai.settings.v2

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.librelookai.R
import com.librelookai.settings.ProfileUiState
import com.librelookai.settings.TryOnSlot
import com.librelookai.util.LocalIsOffline
import java.io.File

/**
 * "Your style" card — try-on reference photos, a style-preferences row, and a
 * language row. See README §"Try-on photos block" and §"Your Style card".
 */
@Composable
fun YourStyleCard(
    state: ProfileUiState,
    onPickPhoto: (TryOnSlot, android.net.Uri) -> Unit,
    onRemovePhoto: (TryOnSlot) -> Unit,
    onEditStyle: () -> Unit,
    onOpenLanguage: () -> Unit,
) {
    val prefs = state.preferences
    SettingsCard {
        Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp)) {
            Text(
                text = stringResource(R.string.settings_tryon_photos_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.settings_tryon_photos_hint),
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TryOnPhotoSlot(
                    slot = TryOnSlot.FRONT,
                    label = stringResource(R.string.settings_tryon_front),
                    localPath = state.tryOnLocalPaths[TryOnSlot.FRONT],
                    uploading = TryOnSlot.FRONT in state.tryOnUploading,
                    onPick = onPickPhoto,
                    onRemove = onRemovePhoto,
                    modifier = Modifier.weight(1f),
                )
                TryOnPhotoSlot(
                    slot = TryOnSlot.SIDE,
                    label = stringResource(R.string.settings_tryon_side),
                    localPath = state.tryOnLocalPaths[TryOnSlot.SIDE],
                    uploading = TryOnSlot.SIDE in state.tryOnUploading,
                    onPick = onPickPhoto,
                    onRemove = onRemovePhoto,
                    modifier = Modifier.weight(1f),
                )
                TryOnPhotoSlot(
                    slot = TryOnSlot.BACK,
                    label = stringResource(R.string.settings_tryon_back),
                    localPath = state.tryOnLocalPaths[TryOnSlot.BACK],
                    uploading = TryOnSlot.BACK in state.tryOnUploading,
                    onPick = onPickPhoto,
                    onRemove = onRemovePhoto,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsRow(
            icon = Icons.Filled.FavoriteBorder,
            label = stringResource(R.string.settings_style_prefs),
            sub = prefs.preferences.takeIf { it.isNotBlank() },
            onClick = onEditStyle,
        )
        SettingsRow(
            icon = Icons.Filled.Language,
            label = stringResource(R.string.settings_language),
            value = prefs.language,
            isLast = true,
            onClick = onOpenLanguage,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TryOnPhotoSlot(
    slot: TryOnSlot,
    label: String,
    localPath: String?,
    uploading: Boolean,
    onPick: (TryOnSlot, android.net.Uri) -> Unit,
    onRemove: (TryOnSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isOffline = LocalIsOffline.current
    var menuOpen by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { onPick(slot, it) } }
    fun pick() {
        if (!isOffline) launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val filled = localPath != null && File(localPath).exists()
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (filled) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                    else Modifier.border(
                        androidx.compose.foundation.BorderStroke(1.5.dp, SolidColor(MaterialTheme.colorScheme.outline)),
                        RoundedCornerShape(10.dp),
                    )
                )
                .combinedClickable(
                    enabled = !isOffline,
                    onClick = { if (filled) menuOpen = true else pick() },
                    onLongClick = { if (filled) menuOpen = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                uploading -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                filled -> AsyncImage(
                    model = File(localPath!!),
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_tryon_replace)) },
                    onClick = { menuOpen = false; pick() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_tryon_remove)) },
                    onClick = { menuOpen = false; onRemove(slot) },
                )
            }
        }
        Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
