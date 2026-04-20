package com.librelookai

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

/**
 * Full-screen modal shown while Gemini generates the Try-on image and after it returns.
 * Shows a progress indicator during generation, then the result image with a
 * Save-to-gallery action (API 29+ only — older devices can screenshot).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TryOnResultDialog(
    state: TryOnUiState,
    onDismiss: () -> Unit,
    onClearError: () -> Unit,
) {
    val context = LocalContext.current
    var saveMessage by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            when {
                state.isGenerating -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        stringResource(R.string.tryon_generating),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                state.resultPath != null -> {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(File(state.resultPath))
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            TextButton(onClick = {
                                val ok = saveImageToGallery(context, File(state.resultPath))
                                saveMessage = if (ok) context.getString(R.string.tryon_saved)
                                              else context.getString(R.string.tryon_save_failed)
                            }) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text(stringResource(R.string.tryon_save_to_gallery), color = Color.White)
                            }
                        }
                    }
                }

                state.error != null -> Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.tryon_error),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        state.error,
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { onClearError(); onDismiss() }) {
                        Text(stringResource(R.string.action_dismiss), color = Color.White)
                    }
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_dismiss), tint = Color.White)
            }

            saveMessage?.let { msg ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = { TextButton(onClick = { saveMessage = null }) { Text(stringResource(R.string.action_ok)) } },
                ) { Text(msg) }
                LaunchedEffect(msg) {
                    kotlinx.coroutines.delay(2500)
                    saveMessage = null
                }
            }
        }
    }
}

/**
 * Copies [file] into the device's Pictures/LibreLookAI/ MediaStore collection. API 29+ only
 * so no runtime storage permission is needed.
 */
private fun saveImageToGallery(context: Context, file: File): Boolean {
    return try {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "librelookai_tryon_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LibreLookAI")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        val wroteBytes = resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
            true
        } ?: false
        if (!wroteBytes) return false
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (_: Exception) {
        false
    }
}
