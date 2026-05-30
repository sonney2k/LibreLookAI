package com.librelookai.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.librelookai.R
import com.librelookai.settings.ProfileViewModel
import com.librelookai.settings.TryOnSlot
import com.librelookai.util.LocalSystemBarsPadding
import kotlinx.coroutines.launch

private data class InfoPage(val icon: ImageVector, val titleRes: Int, val bodyRes: Int)

/**
 * First-run (and re-runnable) walkthrough. A swipeable [HorizontalPager] of value-prop / feature
 * pages followed by three light setup steps (style profile, try-on photo, finish). Every step is
 * skippable: "Skip" is always available until the final page, whose CTAs end the tour.
 *
 * Rendered as an opaque fullscreen overlay above the whole app by [com.librelookai.AppContent].
 * It does no navigation of its own — [onFinish] reports whether the user asked to jump straight to
 * the wardrobe to add clothes (`goToWardrobe = true`) or just explore.
 */
@Composable
fun OnboardingScreen(
    profileViewModel: ProfileViewModel,
    isOffline: Boolean,
    onFinish: (goToWardrobe: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val infoPages = listOf(
        InfoPage(Icons.Filled.AutoAwesome, R.string.onboarding_welcome_title, R.string.onboarding_welcome_body),
        InfoPage(Icons.Filled.Checkroom, R.string.onboarding_wardrobe_title, R.string.onboarding_wardrobe_body),
        InfoPage(Icons.Filled.Style, R.string.onboarding_outfits_title, R.string.onboarding_outfits_body),
        InfoPage(Icons.Filled.Face, R.string.onboarding_tryon_title, R.string.onboarding_tryon_body),
        InfoPage(Icons.Filled.Luggage, R.string.onboarding_travel_title, R.string.onboarding_travel_body),
        InfoPage(Icons.Filled.ShoppingBag, R.string.onboarding_shopping_title, R.string.onboarding_shopping_body),
    )
    val profilePage = infoPages.size
    val photoPage = infoPages.size + 1
    val finishPage = infoPages.size + 2
    val totalPages = infoPages.size + 3

    val state by profileViewModel.state.collectAsState()
    val prefs = state.preferences

    // Seeded from the loaded preferences (re-seeds once when the Drive load lands).
    var gender by remember(prefs) { mutableStateOf(prefs.gender) }
    var yearText by remember(prefs) { mutableStateOf(prefs.yearOfBirth?.toString() ?: "") }
    var style by remember(prefs) { mutableStateOf(prefs.preferences) }

    fun finish(goToWardrobe: Boolean) {
        // Persist the style profile only when the user reaches the end deliberately and we're online
        // (avoids overwriting Drive prefs with defaults if they were still loading / offline).
        if (!isOffline) {
            profileViewModel.savePreferences(
                prefs.copy(
                    gender = gender.trim(),
                    yearOfBirth = yearText.toIntOrNull(),
                    preferences = style.trim(),
                )
            )
        }
        onFinish(goToWardrobe)
    }

    val pagerState = rememberPagerState(pageCount = { totalPages })
    val scope = rememberCoroutineScope()

    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(LocalSystemBarsPadding.current),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Skip — top-right; hidden on the final page where the CTAs take over.
            Box(
                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (pagerState.currentPage < finishPage) {
                    TextButton(onClick = { finish(false) }) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { page ->
                when (page) {
                    profilePage -> ProfilePage(
                        gender = gender, onGender = { gender = it },
                        yearText = yearText, onYear = { yearText = it },
                        style = style, onStyle = { style = it },
                    )
                    photoPage -> PhotoPage(
                        photoSet = state.tryOnLocalPaths.containsKey(TryOnSlot.FRONT),
                        uploading = state.tryOnUploading.contains(TryOnSlot.FRONT),
                        isOffline = isOffline,
                        onPick = { uri -> profileViewModel.uploadTryOnPhoto(TryOnSlot.FRONT, uri) },
                    )
                    finishPage -> FinishPage(
                        onAddItems = { finish(true) },
                        onExplore = { finish(false) },
                    )
                    else -> InfoPageContent(infoPages[page])
                }
            }

            // Page-indicator dots.
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(totalPages) { i ->
                    val selected = i == pagerState.currentPage
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (selected) 9.dp else 7.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                            ),
                    )
                }
            }

            // Back / Next — Next is hidden on the final page (its own CTAs finish the tour).
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pagerState.currentPage > 0) {
                    TextButton(onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    }) { Text(stringResource(R.string.onboarding_back)) }
                }
                Spacer(Modifier.weight(1f))
                if (pagerState.currentPage < finishPage) {
                    Button(onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }) { Text(stringResource(R.string.onboarding_next)) }
                }
            }
        }
    }
}

@Composable
private fun InfoPageContent(page: InfoPage) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(page.icon, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(32.dp))
        Text(
            stringResource(page.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(page.bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ProfilePage(
    gender: String, onGender: (String) -> Unit,
    yearText: String, onYear: (String) -> Unit,
    style: String, onStyle: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_profile_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.onboarding_profile_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = style, onValueChange = onStyle,
            label = { Text(stringResource(R.string.settings_style_prefs)) },
            minLines = 2, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = gender, onValueChange = onGender,
            label = { Text(stringResource(R.string.settings_profile_gender)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = yearText,
            onValueChange = { v -> onYear(v.filter { it.isDigit() }.take(4)) },
            label = { Text(stringResource(R.string.settings_profile_year)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PhotoPage(
    photoSet: Boolean,
    uploading: Boolean,
    isOffline: Boolean,
    onPick: (android.net.Uri) -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(onPick)
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            if (photoSet) Icons.Filled.CheckCircle else Icons.Filled.AddAPhoto,
            null,
            Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(32.dp))
        Text(
            stringResource(R.string.onboarding_photo_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.onboarding_photo_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        when {
            uploading -> CircularProgressIndicator()
            photoSet -> Text(
                stringResource(R.string.onboarding_photo_added),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            else -> Button(
                onClick = { launcher.launch("image/*") },
                enabled = !isOffline,
            ) { Text(stringResource(R.string.onboarding_photo_add)) }
        }
        if (isOffline && !photoSet) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.onboarding_photo_offline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FinishPage(
    onAddItems: () -> Unit,
    onExplore: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Checkroom, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(32.dp))
        Text(
            stringResource(R.string.onboarding_closet_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.onboarding_closet_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onAddItems, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_closet_add_items))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onExplore, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_closet_explore))
        }
    }
}
