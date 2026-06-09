package com.librelookai.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.auth.FirebaseAuth
import com.librelookai.AppScreenHeader
import com.librelookai.R
import com.librelookai.billing.BuyCreditsScreen
import com.librelookai.billing.CreditsViewModel
import com.librelookai.data.model.Location
import com.librelookai.gemini.ApiKeyStore
import com.librelookai.util.ImageEncoding
import com.librelookai.settings.ProfileViewModel
import com.librelookai.settings.UsageCostsTab
import com.librelookai.util.Analytics
import com.librelookai.util.LocalIsOffline
import com.librelookai.wardrobe.LocationViewModel
import com.librelookai.wardrobe.WardrobeViewModel
import com.librelookai.wardrobe.convertImagesToWebp
import com.librelookai.wardrobe.startCutoutBgFixScan

/** Internal destinations within the Settings surface (no Jetpack Navigation). */
private enum class SettingsRoute { MAIN, PROFILE_EDIT, ADVANCED, ABOUT, USAGE, BUY_CREDITS }

/**
 * Redesigned (V1) Settings — a single calmly-grouped scroll page in the iOS-Settings
 * idiom, with an "Advanced" door at the bottom for power-user/destructive options.
 * Reuses the four existing ViewModels; navigation between sub-screens is a local
 * back-stack since the app has no NavHost. See `designs/settings_v1/README.md`.
 */
@Composable
fun SettingsScreen(
    profileViewModel: ProfileViewModel = viewModel(),
    wardrobeViewModel: WardrobeViewModel = viewModel(),
    locationViewModel: LocationViewModel = viewModel(),
    creditsViewModel: CreditsViewModel = viewModel(),
    onBack: () -> Unit = {},
    navResetTick: Int = 0,
    modifier: Modifier = Modifier,
) {
    val profileState by profileViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val locationState by locationViewModel.state.collectAsState()
    val creditsState by creditsViewModel.state.collectAsState()

    val context = LocalContext.current
    var currentApiKey by remember { mutableStateOf(ApiKeyStore.get(context)) }

    var stack by remember { mutableStateOf(listOf(SettingsRoute.MAIN)) }
    val route = stack.last()
    fun push(r: SettingsRoute) { stack = stack + r }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) else onBack() }
    LaunchedEffect(navResetTick) { stack = listOf(SettingsRoute.MAIN) }
    LaunchedEffect(route) { Analytics.screen("Settings/${route.name}") }

    // Destructive-action confirm + closet/language dialog state.
    var pendingAction by remember { mutableStateOf<DestructiveAction?>(null) }
    var showFeedback by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    var showAddCloset by remember { mutableStateOf(false) }
    var editingCloset by remember { mutableStateOf<Location?>(null) }

    val displayName = rememberDisplayName()

    when (route) {
        SettingsRoute.MAIN -> SettingsMain(
            displayName = displayName,
            profileViewModel = profileViewModel,
            profileState = profileState,
            closets = locationState.locations,
            defaultFolderId = locationState.defaultClosetFolderId,
            itemCounts = closetItemCounts(wardrobeState.allLocationImages, wardrobeState.images),
            balance = creditsState.balance,
            selectedThemeId = profileState.preferences.wardrobeTheme,
            onSetDefaultCloset = locationViewModel::setDefaultClosetFolderId,
            onSelectTheme = { id ->
                profileViewModel.savePreferences(profileState.preferences.copy(wardrobeTheme = id))
            },
            onEditProfile = { push(SettingsRoute.PROFILE_EDIT) },
            onOpenLanguage = { showLanguage = true },
            onAddCloset = { showAddCloset = true },
            onEditCloset = { editingCloset = it },
            onGetMore = { push(SettingsRoute.BUY_CREDITS) },
            onConvertWebp = { pendingAction = DestructiveAction.CONVERT_WEBP },
            onAdvanced = { push(SettingsRoute.ADVANCED) },
            onAbout = { push(SettingsRoute.ABOUT) },
            onSendFeedback = { showFeedback = true },
            onBack = onBack,
            modifier = modifier,
        )
        SettingsRoute.PROFILE_EDIT -> ProfileEditScreen(
            displayName = displayName,
            preferences = profileState.preferences,
            onSave = profileViewModel::savePreferences,
            onBack = ::pop,
        )
        SettingsRoute.ADVANCED -> SettingsAdvancedScreen(
            preferences = profileState.preferences,
            currentApiKey = currentApiKey,
            onSaveApiKey = { key -> ApiKeyStore.set(context, key); currentApiKey = key },
            onDestructive = { pendingAction = it },
            onToggleDedupe = { profileViewModel.savePreferences(profileState.preferences.copy(dedupeOnImport = it)) },
            onTogglePreferLocalBg = { profileViewModel.savePreferences(profileState.preferences.copy(preferLocalBgRemoval = it)) },
            onToggleSimilarityPreview = { profileViewModel.savePreferences(profileState.preferences.copy(debugSimilarityPreview = it)) },
            onSelectImageQuality = { profileViewModel.savePreferences(profileState.preferences.copy(imageQuality = it)) },
            onOpenUsage = { push(SettingsRoute.USAGE) },
            onBack = ::pop,
        )
        SettingsRoute.ABOUT -> AboutScreen(
            onOpenUsage = { push(SettingsRoute.USAGE) },
            onBack = ::pop,
        )
        SettingsRoute.USAGE -> SubScreen(title = stringResource(R.string.settings_usage_charts_row), onBack = ::pop) {
            UsageCostsTab()
        }
        SettingsRoute.BUY_CREDITS -> SubScreen(title = stringResource(R.string.settings_section_ai_credits), onBack = ::pop) {
            BuyCreditsScreen(
                creditsViewModel = creditsViewModel,
                currentApiKey = currentApiKey,
                onSaveApiKey = { key -> ApiKeyStore.set(context, key); currentApiKey = key },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    // ----- Overlays -----
    pendingAction?.let { action ->
        val itemCount = when (action) {
            // Estimate from legacy (non-WebP) cutouts across all closets.
            DestructiveAction.CONVERT_WEBP ->
                wardrobeState.allLocationImages.count { it.name.endsWith(ImageEncoding.CUTOUT_SUFFIX_LEGACY) }
            else -> wardrobeState.images.size
        }
        DestructiveConfirmDialog(
            action = action,
            itemCount = itemCount,
            balance = creditsState.balance,
            onConfirm = {
                when (action) {
                    DestructiveAction.RETAG -> wardrobeViewModel.retagAll()
                    DestructiveAction.REMOVE_BG -> wardrobeViewModel.removeAllBackgrounds()
                    DestructiveAction.CUTOUT_FIX ->
                        wardrobeViewModel.startCutoutBgFixScan(locationState.locations.map { it.folderId })
                    DestructiveAction.CONVERT_WEBP ->
                        wardrobeViewModel.convertImagesToWebp(locationState.locations.map { it.folderId })
                }
                pendingAction = null
            },
            onBuyCredits = { pendingAction = null; push(SettingsRoute.BUY_CREDITS) },
            onDismiss = { pendingAction = null },
        )
    }
    if (showFeedback) {
        FeedbackDialog(
            appState = buildFeedbackAppState(
                locations = locationState.locations,
                defaultFolderId = locationState.defaultClosetFolderId,
                itemCounts = closetItemCounts(wardrobeState.allLocationImages, wardrobeState.images),
                totalItems = wardrobeState.allLocationImages.ifEmpty { wardrobeState.images }.size,
                pendingJobs = wardrobeState.pendingJobs,
                balance = creditsState.balance,
                prefs = profileState.preferences,
                isOffline = LocalIsOffline.current,
            ),
            onDismiss = { showFeedback = false },
        )
    }
    if (showLanguage) {
        LanguagePickerDialog(
            current = profileState.preferences.language,
            onSelect = { lang -> profileViewModel.savePreferences(profileState.preferences.copy(language = lang)) },
            onDismiss = { showLanguage = false },
        )
    }
    if (showAddCloset) {
        ClosetEditDialog(
            existing = null,
            onSave = { name, city -> locationViewModel.addLocation(name, city) },
            onDelete = null,
            onDismiss = { showAddCloset = false },
        )
    }
    editingCloset?.let { closet ->
        ClosetEditDialog(
            existing = closet,
            onSave = { name, city -> locationViewModel.renameLocation(closet.folderId, name, city) },
            onDelete = if (locationState.locations.size > 1) {
                { locationViewModel.deleteLocation(closet.folderId) }
            } else null,
            onDismiss = { editingCloset = null },
        )
    }
}

@Composable
private fun SettingsMain(
    displayName: String,
    profileViewModel: ProfileViewModel,
    profileState: com.librelookai.settings.ProfileUiState,
    closets: List<Location>,
    defaultFolderId: String?,
    itemCounts: Map<String, Int>,
    balance: Int,
    selectedThemeId: String,
    onSetDefaultCloset: (String) -> Unit,
    onSelectTheme: (String) -> Unit,
    onEditProfile: () -> Unit,
    onOpenLanguage: () -> Unit,
    onAddCloset: () -> Unit,
    onEditCloset: (Location) -> Unit,
    onGetMore: () -> Unit,
    onConvertWebp: () -> Unit,
    onAdvanced: () -> Unit,
    onAbout: () -> Unit,
    onSendFeedback: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        AppScreenHeader(
            title = stringResource(R.string.nav_settings),
            trailingContent = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 18.dp)) {
            item {
                HeroCard(
                    displayName = displayName,
                    preferences = profileState.preferences,
                    onEdit = onEditProfile,
                )
            }
            item { SecLabel(stringResource(R.string.settings_section_your_style)) }
            item {
                YourStyleCard(
                    state = profileState,
                    onPickPhoto = profileViewModel::uploadTryOnPhoto,
                    onRemovePhoto = profileViewModel::deleteTryOnPhoto,
                    onEditStyle = onEditProfile,
                    onOpenLanguage = onOpenLanguage,
                )
            }
            item {
                SecLabel(
                    stringResource(R.string.settings_section_your_closets),
                    hint = stringResource(R.string.settings_closets_hint),
                )
            }
            item {
                YourClosetsCard(
                    closets = closets,
                    defaultFolderId = defaultFolderId,
                    itemCounts = itemCounts,
                    onSetDefault = onSetDefaultCloset,
                    onEditCloset = onEditCloset,
                    onAddCloset = onAddCloset,
                )
            }
            item { SecLabel(stringResource(R.string.settings_section_how_it_looks)) }
            item { HowItLooksCard(selectedThemeId = selectedThemeId, onSelectTheme = onSelectTheme) }
            if (com.librelookai.billing.ManagedBilling.enabled) {
                item { SecLabel(stringResource(R.string.settings_section_ai_credits)) }
                item { AiCreditsCard(balance = balance, onGetMore = onGetMore) }
            }
            item { SecLabel(stringResource(R.string.settings_section_help)) }
            item {
                HelpCard(
                    onConvertWebp = onConvertWebp,
                    onSendFeedback = onSendFeedback,
                    onAbout = onAbout,
                )
            }
            // "Advanced" door pinned to the very bottom, in its own padded section.
            item { SecLabel(stringResource(R.string.settings_more_advanced)) }
            item { AdvancedCard(onAdvanced = onAdvanced) }
            item {
                Text(
                    text = stringResource(R.string.settings_footer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 22.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Wraps a parameter-free child screen (Usage / Buy credits) with a back header. */
@Composable
private fun SubScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        AppScreenHeader(
            title = title,
            trailingContent = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
        )
        content()
    }
}

@Composable
private fun rememberDisplayName(): String {
    val fallback = stringResource(R.string.settings_hero_default_name)
    return remember {
        val user = FirebaseAuth.getInstance().currentUser
        user?.displayName?.takeIf { it.isNotBlank() }
            ?: user?.email?.substringBefore('@')?.takeIf { it.isNotBlank() }
            ?: fallback
    }
}

/** Item counts keyed by closet folderId, preferring the cross-closet snapshot. */
private fun closetItemCounts(
    allLocationImages: List<com.librelookai.wardrobe.DriveImage>,
    images: List<com.librelookai.wardrobe.DriveImage>,
): Map<String, Int> {
    val source = allLocationImages.ifEmpty { images }
    return source.groupingBy { it.folderId }.eachCount()
}

/**
 * Human-readable wardrobe + settings snapshot appended to the feedback diagnostics report
 * (see [buildDiagnostics]). Deliberately excludes any free-text style/preferences prose and
 * account identity — just structural facts useful for triage.
 */
private fun buildFeedbackAppState(
    locations: List<Location>,
    defaultFolderId: String?,
    itemCounts: Map<String, Int>,
    totalItems: Int,
    pendingJobs: Int,
    balance: Int,
    prefs: UserPreferences,
    isOffline: Boolean,
): String = buildString {
    val defaultName = locations.firstOrNull { it.folderId == defaultFolderId }?.name ?: "—"
    appendLine("[Wardrobe]")
    appendLine("  Closets: ${locations.size} (default: $defaultName)")
    appendLine("  Total items: $totalItems")
    for (loc in locations) {
        appendLine("  • ${loc.name}: ${itemCounts[loc.folderId] ?: 0}")
    }
    appendLine("  Pending background jobs: $pendingJobs")
    appendLine("  Offline: $isOffline")
    appendLine()

    val tryOn = listOf(
        "front" to prefs.tryOnFrontDriveId,
        "side" to prefs.tryOnSideDriveId,
        "back" to prefs.tryOnBackDriveId,
    ).joinToString(" ") { (k, v) -> "$k=${if (v.isNotBlank()) "set" else "–"}" }
    val ai = prefs.aiConsiderations
    val considers = listOfNotNull(
        "weather".takeIf { ai.weather }, "location".takeIf { ai.location },
        "trends".takeIf { ai.trends }, "gender".takeIf { ai.gender },
        "age".takeIf { ai.age }, "preferences".takeIf { ai.preferences },
        "history".takeIf { ai.history },
    ).joinToString(",").ifBlank { "none" }
    appendLine("[Preferences]")
    appendLine("  Theme: ${prefs.wardrobeTheme} · Font: ${prefs.appFont} · Language: ${prefs.language}")
    appendLine("  Image quality: ${prefs.imageQuality}")
    appendLine("  Gender: ${prefs.gender.ifBlank { "—" }} · Birth year: ${prefs.yearOfBirth ?: "—"}")
    appendLine("  Dedupe on import: ${prefs.dedupeOnImport} (threshold ${prefs.dedupeThreshold})")
    appendLine("  Prefer on-device bg removal: ${prefs.preferLocalBgRemoval}")
    appendLine("  BG-removal threshold: ${prefs.bgRemovalThreshold}")
    appendLine("  Try-on photos: $tryOn")
    appendLine("  AI considerations: $considers")
    appendLine("  Expert item tags: ${ai.itemTags?.joinToString(",")?.ifBlank { "(none)" } ?: "all"}")
    appendLine("  Credit balance: $balance")
}

