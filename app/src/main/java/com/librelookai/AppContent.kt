package com.librelookai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.librelookai.auth.AuthViewModel
import com.librelookai.auth.GoogleAuthManager
import com.librelookai.auth.SignInScreen
import com.librelookai.billing.CreditsViewModel
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.model.Location
import com.librelookai.data.model.TryOn
import com.librelookai.gemini.TokenUsageRepository
import com.librelookai.ml.EmbeddingService
import com.librelookai.outfit.OutfitComposerScreen
import com.librelookai.outfit.OutfitEventsViewModel
import com.librelookai.outfit.OutfitsScreen
import com.librelookai.outfit.OutfitsViewModel
import com.librelookai.settings.AppLanguage
import com.librelookai.settings.FixCutoutBgDialog
import com.librelookai.settings.ProfileViewModel
import com.librelookai.settings.v2.SettingsScreen
import com.librelookai.shopping.ShoppingClosetViewModel
import com.librelookai.shopping.ShoppingHelperScreen
import com.librelookai.shopping.ShoppingHelperViewModel
import com.librelookai.travel.TravelScreen
import com.librelookai.travel.TravelViewModel
import com.librelookai.tryon.TryOnComposerScreen
import com.librelookai.tryon.TryOnViewModel
import com.librelookai.ui.theme.LibreLookAITheme
import com.librelookai.util.Analytics
import com.librelookai.util.LocalIsOffline
import com.librelookai.util.LocalSystemBarsPadding
import com.librelookai.util.NetworkMonitor
import com.librelookai.wardrobe.LocalBgRemovalScreen
import com.librelookai.wardrobe.LocationViewModel
import com.librelookai.wardrobe.ReplacementsResultDialog
import com.librelookai.wardrobe.WardrobeGapViewModel
import com.librelookai.wardrobe.WardrobeScreen
import com.librelookai.wardrobe.WardrobeViewModel
import com.librelookai.wardrobe.continueCutoutBgFix
import com.librelookai.wardrobe.fetchCutoutFixThumbnail
import com.librelookai.wardrobe.setCutoutFixAction
import com.librelookai.wardrobe.setCutoutFixSelection
import com.librelookai.wardrobe.setCutoutFixShowAll
import com.librelookai.wardrobe.toggleCutoutFixSelection
import com.librelookai.weather.WeatherBadge
import com.librelookai.weather.WeatherViewModel
import kotlinx.coroutines.launch

@Composable
internal fun AppContent(activity: ComponentActivity) {
    LibreLookAITheme(
        paletteId = ProfileViewModel.cachedTheme(activity),
        fontId = ProfileViewModel.cachedFont(activity),
    ) {
                val authViewModel: AuthViewModel = viewModel()
                val isSignedIn by authViewModel.isSignedIn.collectAsState()
                val authError by authViewModel.signInErrorCode.collectAsState()
                val pendingAuthIntent by authViewModel.pendingAuthIntent.collectAsState()

                val authorizationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartIntentSenderForResult(),
                ) { result -> authViewModel.onAuthorizationResult(result.data) }

                LaunchedEffect(pendingAuthIntent) {
                    pendingAuthIntent?.let { intent ->
                        authorizationLauncher.launch(
                            IntentSenderRequest.Builder(intent.intentSender).build()
                        )
                    }
                }

                LaunchedEffect(isSignedIn) {
                    if (isSignedIn) {
                        authViewModel.attemptFirebaseSignIn(activity)
                        Analytics.event("sign_in_success")
                    }
                }

                if (!isSignedIn) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        SignInScreen(
                            onSignIn = {
                                Analytics.action("SignIn", "start_sign_in")
                                authViewModel.startSignIn()
                            },
                            signInErrorCode = authError,
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                } else {
                    val networkMonitor = remember { NetworkMonitor(activity) }
                    val usageRepo = remember { TokenUsageRepository.get(activity.application) }
                    val driveRepo = remember { DriveRepository(activity, GoogleAuthManager(activity)) }
                    val usageScope = rememberCoroutineScope()
                    DisposableEffect(networkMonitor) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                networkMonitor.recheck()
                                usageScope.launch { usageRepo.syncWithDrive(driveRepo) }
                            } else if (event == Lifecycle.Event.ON_PAUSE) {
                                usageScope.launch { usageRepo.flushToDrive(driveRepo) }
                            }
                        }
                        activity.lifecycle.addObserver(observer)
                        onDispose {
                            activity.lifecycle.removeObserver(observer)
                            networkMonitor.unregister()
                        }
                    }
                    val isOnline by networkMonitor.isOnline.collectAsState()
                    val isOffline = !isOnline

                    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
                    LaunchedEffect(selectedTab) {
                        val name = when (selectedTab) {
                            0 -> "Outfits"; 1 -> "Wardrobe"; 2 -> "Shopping"
                            3 -> "Travel"; 5 -> "Settings"
                            else -> "Tab$selectedTab"
                        }
                        Analytics.screen(name)
                    }
                    // Increments on every nav button tap (incl. re-tap and gear icon)
                    // so screens with sub-tabs can reset to their default tab.
                    var navResetTick by remember { mutableIntStateOf(0) }
                    val locationViewModel: LocationViewModel = viewModel()
                    val stylesViewModel: OutfitsViewModel = viewModel()
                    val wardrobeViewModel: WardrobeViewModel = viewModel()
                    val outfitEventsViewModel: OutfitEventsViewModel = viewModel()
                    val profileViewModel: ProfileViewModel = viewModel()
                    val weatherViewModel: WeatherViewModel = viewModel()
                    val travelViewModel: TravelViewModel = viewModel()
                    val tripsViewModel: com.librelookai.travel.TripsViewModel = viewModel()
                    val gapViewModel: WardrobeGapViewModel = viewModel()
                    val creditsViewModel: CreditsViewModel = viewModel()
                    val tryOnViewModel: TryOnViewModel = viewModel()
                    val shoppingViewModel: ShoppingHelperViewModel = viewModel()
                    val shoppingClosetViewModel: ShoppingClosetViewModel = viewModel()
                    val locationState by locationViewModel.state.collectAsState()
                    val weatherState by weatherViewModel.state.collectAsState()
                    val profileState by profileViewModel.state.collectAsState()
                    val canTryOn = profileState.tryOnLocalPaths.isNotEmpty()

                    // Reload wardrobe/styles/outfits whenever the active location changes
                    val activeLocationId = locationState.activeLocationId
                    val locationList = locationState.locations
                    val activeFolderId = if (activeLocationId != LocationViewModel.ALL_LOCATIONS_ID && activeLocationId.isNotEmpty())
                        locationList.find { it.folderId == activeLocationId }?.folderId else null
                    val shoppingClosetState by shoppingClosetViewModel.state.collectAsState()
                    LaunchedEffect(activeLocationId, locationList, locationState.defaultClosetFolderId, shoppingClosetState.folderId) {
                        val closetFolderIds = locationList.map { it.folderId }
                        // Always tell the wardrobe VM about every configured closet — and the
                        // shopping closet — so the cross-closet snapshot (used by all
                        // similarity-search call sites) covers wishlist items too. The shopping
                        // folder is never a Location and never appears in `closetFolderIds`.
                        val folderIds = closetFolderIds
                        val snapshotFolderIds = shoppingClosetState.folderId
                            ?.let { closetFolderIds + it } ?: closetFolderIds
                        wardrobeViewModel.setAllConfiguredLocations(snapshotFolderIds)
                        // Styles always loads from ALL locations — never filtered by the settings default.
                        stylesViewModel.setAllLocations(folderIds)
                        // Track which folder new styles should be saved to.
                        val saveTarget = activeFolderId ?: folderIds.firstOrNull()
                        if (saveTarget != null) stylesViewModel.updateSaveFolder(saveTarget)
                        if (activeLocationId == LocationViewModel.ALL_LOCATIONS_ID) {
                            wardrobeViewModel.setAllLocations(folderIds)
                            outfitEventsViewModel.setAllLocations(folderIds)
                        } else {
                            activeFolderId?.let { folderId ->
                                wardrobeViewModel.setLocation(folderId)
                                outfitEventsViewModel.setLocation(folderId)
                            }
                        }
                        // Imports go to the persisted default closet (Settings → Data/Closets),
                        // independent of the closet filter. Falls back to the first location.
                        val defaultClosetId = locationState.defaultClosetFolderId
                            ?.takeIf { id -> folderIds.contains(id) }
                            ?: folderIds.firstOrNull()
                        wardrobeViewModel.setDefaultImportFolderId(defaultClosetId)
                    }

                    // Keep wardrobe tagging language in sync with the profile language
                    val geminiLanguage = AppLanguage.toGeminiName(profileState.preferences.language)
                    LaunchedEffect(geminiLanguage) {
                        wardrobeViewModel.setLanguage(geminiLanguage)
                        shoppingClosetViewModel.setLanguage(geminiLanguage)
                    }

                    // Pre-warm the Shopping wishlist and Try-On history at app start so that
                    // tapping those tabs paints from the local cache immediately instead of
                    // kicking off the load on first composition. Both view models are
                    // two-phase (local cache → Drive refresh), so this is cheap.
                    LaunchedEffect(Unit) {
                        shoppingClosetViewModel.loadItems()
                        tryOnViewModel.loadHistory()
                        tripsViewModel.loadTrips()
                    }

                    // Mirror similarity-check preferences into the wardrobe VM
                    val dedupeOnImport = profileState.preferences.dedupeOnImport
                    val dedupeThreshold = profileState.preferences.dedupeThreshold
                    LaunchedEffect(dedupeOnImport, dedupeThreshold) {
                        wardrobeViewModel.setDedupeSettings(dedupeOnImport, dedupeThreshold)
                    }

                    // Mirror the bg-removal threshold into the live segmenter so changes from the
                    // AI tab take effect immediately on the next capture/import.
                    val bgRemovalThreshold = profileState.preferences.bgRemovalThreshold
                    LaunchedEffect(bgRemovalThreshold) {
                        runCatching { EmbeddingService.segmenter.foregroundThreshold = bgRemovalThreshold }
                    }

                    // Mirror the local-bg-removal pref into the wardrobe VM so camera/gallery
                    // imports route through the on-device segmenter review when enabled.
                    val preferLocalBg = profileState.preferences.preferLocalBgRemoval
                    LaunchedEffect(preferLocalBg) {
                        wardrobeViewModel.setPreferLocalBgRemoval(preferLocalBg)
                    }

                    val debugSimilarityPreview = profileState.preferences.debugSimilarityPreview
                    LaunchedEffect(debugSimilarityPreview) {
                        wardrobeViewModel.setDebugSimilarityPreview(debugSimilarityPreview)
                    }

                    // Apply selected language as the Compose context locale
                    val language = profileState.preferences.language
                    val baseContext = LocalContext.current
                    val localizedContext = remember(language) {
                        val locale = AppLanguage.toLocale(language)
                        val config = Configuration(baseContext.resources.configuration)
                        config.setLocale(locale)
                        baseContext.createConfigurationContext(config)
                    }

                    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()

                    CompositionLocalProvider(
                        LocalContext provides localizedContext,
                        LocalConfiguration provides localizedContext.resources.configuration,
                        LocalActivityResultRegistryOwner provides activity,
                        LocalOnBackPressedDispatcherOwner provides activity,
                        LocalIsOffline provides isOffline,
                        LocalSystemBarsPadding provides systemBarsPadding,
                        // The Settings opener also closes the try-on + outfit composers (both hosted
                        // outside `when(selectedTab)`, so a tab switch alone wouldn't dismiss them or
                        // their picker dialogs).
                        LocalOpenSettings provides {
                            Analytics.action("Toolbar", "open_settings")
                            tryOnViewModel.close(); stylesViewModel.closeComposer()
                            selectedTab = 5; navResetTick++
                        },
                        LocalClosetSelector provides ClosetSelectorContext(
                            locations = locationList,
                            activeLocationId = activeLocationId,
                            onSetActiveLocation = locationViewModel::setActiveLocation,
                        ),
                    ) { LibreLookAITheme(
                        paletteId = profileState.preferences.wardrobeTheme,
                        fontId = profileState.preferences.appFont,
                    ) {

                        // Battery optimization exemption — block all interactions until granted
                        val pm = remember {
                            activity.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
                        }
                        val pkgName = activity.packageName
                        var isBatteryExempt by remember {
                            mutableStateOf(pm.isIgnoringBatteryOptimizations(pkgName))
                        }
                        // Re-check when the user comes back from system settings
                        DisposableEffect(Unit) {
                            val observer = LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_RESUME) {
                                    isBatteryExempt = pm.isIgnoringBatteryOptimizations(pkgName)
                                }
                            }
                            activity.lifecycle.addObserver(observer)
                            onDispose { activity.lifecycle.removeObserver(observer) }
                        }

                        if (!isBatteryExempt) {
                            val batteryContext = LocalContext.current
                            AlertDialog(
                                onDismissRequest = { /* non-dismissable */ },
                                title = { Text(stringResource(R.string.battery_exempt_title)) },
                                text = { Text(stringResource(R.string.battery_exempt_text)) },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            fun launchIntent(vararg intents: Intent) {
                                                for (intent in intents) {
                                                    try {
                                                        batteryContext.startActivity(intent)
                                                        return
                                                    } catch (_: Exception) { }
                                                }
                                            }
                                            launchIntent(
                                                Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                    data = Uri.parse("package:$pkgName")
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                },
                                                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                    data = Uri.parse("package:$pkgName")
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                },
                                                Intent(AndroidSettings.ACTION_SETTINGS).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                },
                                            )
                                        }
                                    ) { Text(stringResource(R.string.battery_exempt_action)) }
                                },
                            )
                        } else {

                        // Location permission — request once; refresh weather when granted
                        var hasLocationPermission by remember {
                            mutableStateOf(
                                ContextCompat.checkSelfPermission(
                                    activity, Manifest.permission.ACCESS_COARSE_LOCATION,
                                ) == PackageManager.PERMISSION_GRANTED,
                            )
                        }
                        val locationPermLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestPermission(),
                        ) { granted ->
                            hasLocationPermission = granted
                            if (granted) weatherViewModel.refresh()
                        }
                        LaunchedEffect(Unit) {
                            if (!hasLocationPermission) {
                                locationPermLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                            }
                        }

                        var dismissWardrobeViewerTrigger by remember { mutableIntStateOf(0) }
                        var showQuickTryOnSheet by remember { mutableStateOf(false) }
                        var showTripTryOnPicker by remember { mutableStateOf(false) }
                        var travelPlannerMode by rememberSaveable { mutableStateOf(false) }
                        var tripViewerTripId: String? by rememberSaveable { mutableStateOf(null) }
                        val hideChrome = selectedTab == 3 && (travelPlannerMode || tripViewerTripId != null)

                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            bottomBar = {
                                if (!hideChrome) {
                                    AppNavBar(
                                        selectedTab = selectedTab,
                                        onTabSelected = {
                                            Analytics.action("NavBar", "tab_select", mapOf("index" to it.toString()))
                                            selectedTab = it
                                            navResetTick++
                                        },
                                        onTabReselected = { tab ->
                                            Analytics.action("NavBar", "tab_reselect", mapOf("index" to tab.toString()))
                                            if (tab == 1) dismissWardrobeViewerTrigger++
                                            navResetTick++
                                        },
                                        onCenterClick = {
                                            Analytics.action("TryOn", "nav_button_tap", mapOf("from" to selectedTab.toString()))
                                            showQuickTryOnSheet = true
                                        },
                                    )
                                }
                            },
                        ) { innerPadding ->
                            Column(Modifier.fillMaxSize().padding(innerPadding)) {
                                // Offline banner
                                androidx.compose.animation.AnimatedVisibility(visible = isOffline) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.errorContainer)
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        Icon(
                                            Icons.Default.CloudOff,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            stringResource(R.string.offline_banner),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                    }
                                }

                                Box(Modifier.fillMaxSize()) {
                                    val onSettingsClick: () -> Unit = {
                                        Analytics.action("Toolbar", "open_settings")
                                        selectedTab = 5
                                        navResetTick++
                                    }
                                    val runTryOn: (Set<String>, String?) -> Unit = { itemIds, sourceOutfitId ->
                                        Analytics.action("TryOn", "open_composer", mapOf("count" to itemIds.size.toString()))
                                        tryOnViewModel.openComposer(itemIds, sourceOutfitId)
                                    }
                                    // Try-on a trip's outfit from the trip viewer — tagged TRAVEL so
                                    // provenance reads "{trip} · Day {n}", matching the Quick-sheet path.
                                    val tripTryOnCtx = LocalContext.current
                                    val runTripOutfitTryOn: (com.librelookai.data.model.Trip, com.librelookai.data.model.Outfit) -> Unit = { trip, outfit ->
                                        Analytics.action("TryOn", "open_composer", mapOf("source" to "travel_trip"))
                                        val day = trip.outfitIds.indexOf(outfit.id) + 1
                                        tryOnViewModel.openComposer(
                                            outfit.itemIds.toSet(),
                                            outfit.id,
                                            com.librelookai.tryon.TryOnSourceKind.TRAVEL,
                                            tripTryOnCtx.getString(R.string.tryon_trip_context, trip.name, day),
                                        )
                                    }
                                    when (selectedTab) {
                                        0 -> OutfitsScreen(
                                            outfitsViewModel = stylesViewModel,
                                            wardrobeViewModel = wardrobeViewModel,
                                            outfitEventsViewModel = outfitEventsViewModel,
                                            profileViewModel = profileViewModel,
                                            weatherViewModel = weatherViewModel,
                                            locationViewModel = locationViewModel,
                                            onTryOnStyle = { style ->
                                                stylesViewModel.clearOutfitSelection()
                                                // Preserve the outfit link so the saved try-on
                                                // can jump back here from the detail view.
                                                runTryOn(style.itemIds.toSet(), style.id)
                                            },
                                            canTryOn = canTryOn,
                                            onSettingsClick = onSettingsClick,
                                            navResetTick = navResetTick,
                                        )
                                        1 -> WardrobeScreen(
                                            viewModel = wardrobeViewModel,
                                            outfitEventsViewModel = outfitEventsViewModel,
                                            stylesViewModel = stylesViewModel,
                                            locationViewModel = locationViewModel,
                                            shoppingClosetViewModel = shoppingClosetViewModel,
                                            profileViewModel = profileViewModel,
                                            tryOnViewModel = tryOnViewModel,
                                            onCreateOutfitFromSelection = { itemIds ->
                                                Analytics.action("Wardrobe", "create_outfit_from_selection", mapOf("count" to itemIds.size.toString()))
                                                stylesViewModel.openComposer(
                                                    seedItemIds = itemIds,
                                                    images      = wardrobeViewModel.state.value.images,
                                                    prefs       = profileViewModel.state.value.preferences,
                                                    // Default to the currently selected closet (null = All).
                                                    defaultSourceFolderId = locationViewModel.activeFolderId,
                                                )
                                                wardrobeViewModel.clearSelection()
                                            },
                                            onTryOnSelection = { itemIds ->
                                                runTryOn(itemIds, null)
                                                wardrobeViewModel.clearSelection()
                                            },
                                            onSuggestReplacements = { itemIds ->
                                                Analytics.action("Wardrobe", "suggest_replacements", mapOf("count" to itemIds.size.toString()))
                                                val all = wardrobeViewModel.state.value.images
                                                val selected = all.filter { it.driveId in itemIds }
                                                gapViewModel.suggestReplacements(
                                                    selected  = selected,
                                                    allImages = all,
                                                    prefs     = profileViewModel.state.value.preferences,
                                                )
                                                wardrobeViewModel.clearSelection()
                                            },
                                            canTryOn = canTryOn,
                                            dismissViewerTrigger = dismissWardrobeViewerTrigger,
                                            onSettingsClick = onSettingsClick,
                                        )
                                        2 -> ShoppingHelperScreen(
                                            shoppingViewModel = shoppingViewModel,
                                            shoppingClosetViewModel = shoppingClosetViewModel,
                                            wardrobeViewModel = wardrobeViewModel,
                                            gapViewModel = gapViewModel,
                                            profileViewModel = profileViewModel,
                                            locationViewModel = locationViewModel,
                                            onSettingsClick = onSettingsClick,
                                            onShowInWardrobe = { image ->
                                                Analytics.action("Shopping", "show_in_wardrobe")
                                                val matchFolder = image.folderId
                                                val viewingAll = locationState.activeLocationId == LocationViewModel.ALL_LOCATIONS_ID
                                                if (!viewingAll && matchFolder.isNotEmpty()
                                                    && matchFolder != locationState.activeLocationId
                                                ) {
                                                    locationViewModel.setActiveLocation(matchFolder)
                                                }
                                                wardrobeViewModel.requestScrollToImage(image.driveId)
                                                selectedTab = 1
                                                navResetTick++
                                            },
                                            onCreateOutfitFromSelection = { itemIds ->
                                                Analytics.action("Shopping", "create_outfit_from_selection", mapOf("count" to itemIds.size.toString()))
                                                stylesViewModel.openComposer(
                                                    seedItemIds = itemIds,
                                                    images      = wardrobeViewModel.state.value.images +
                                                        shoppingClosetState.items,
                                                    prefs       = profileViewModel.state.value.preferences,
                                                    // Default to the currently selected closet (null = All).
                                                    defaultSourceFolderId = locationViewModel.activeFolderId,
                                                )
                                                shoppingClosetViewModel.clearSelection()
                                            },
                                            onTryOnSelection = { itemIds ->
                                                runTryOn(itemIds, null)
                                                shoppingClosetViewModel.clearSelection()
                                            },
                                            canTryOn = canTryOn,
                                            navResetTick = navResetTick,
                                        )
                                        3 -> TravelScreen(
                                            travelViewModel = travelViewModel,
                                            tripsViewModel = tripsViewModel,
                                            wardrobeViewModel = wardrobeViewModel,
                                            profileViewModel = profileViewModel,
                                            stylesViewModel = stylesViewModel,
                                            locationViewModel = locationViewModel,
                                            onSettingsClick = onSettingsClick,
                                            plannerMode = travelPlannerMode,
                                            onPlannerModeChange = { travelPlannerMode = it },
                                            tripViewerTripId = tripViewerTripId,
                                            onOpenTrip = { tripViewerTripId = it },
                                            onCloseTripViewer = { tripViewerTripId = null },
                                            canTryOn = canTryOn,
                                            onTryOnTripOutfit = runTripOutfitTryOn,
                                        )
                                        5 -> SettingsScreen(
                                            profileViewModel = profileViewModel,
                                            wardrobeViewModel = wardrobeViewModel,
                                            locationViewModel = locationViewModel,
                                            creditsViewModel = creditsViewModel,
                                            onBack = { selectedTab = 1 },
                                            navResetTick = navResetTick,
                                        )
                                    }

                                    // Weather badge — bottom-left, floating above the nav bar.
                                    // Hidden in the travel-planner mode because that screen pins its
                                    // own Generate button to the bottom and the badge would overlap.
                                    if (!hideChrome) {
                                        weatherState.data?.let { weather ->
                                            WeatherBadge(
                                                data = weather,
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .padding(start = 12.dp, bottom = 8.dp),
                                            )
                                        }
                                    }

                                    // Local background-removal review — fullscreen overlay shown
                                    // when an import is queued for on-device cutout refinement.
                                    // Rendered inside this Box so it stacks on top of content.
                                    LocalBgRemovalScreen(viewModel = wardrobeViewModel)
                                }

                                val stylesState by stylesViewModel.state.collectAsState()
                                val tripsUiState by tripsViewModel.state.collectAsState()
                                val wardrobeImagesForTryOn = wardrobeViewModel.state.collectAsState().value.images
                                // Travel try-on is offered only when a trip has at least one day
                                // whose outfit still resolves to loaded wardrobe items.
                                val travelTryOnAvailable = remember(tripsUiState.trips, stylesState.outfits, wardrobeImagesForTryOn) {
                                    val outfitsById = stylesState.outfits.associateBy { it.id }
                                    val knownItemIds = wardrobeImagesForTryOn.mapTo(HashSet()) { it.driveId }
                                    tripsUiState.trips.any { trip ->
                                        trip.outfitIds.any { oid ->
                                            outfitsById[oid]?.let { o ->
                                                o.itemIds.isNotEmpty() && o.itemIds.all { it in knownItemIds }
                                            } == true
                                        }
                                    }
                                }
                                val tryOnContext = LocalContext.current
                                TryOnComposerScreen(
                                    tryOnViewModel   = tryOnViewModel,
                                    wardrobeViewModel = wardrobeViewModel,
                                    profileViewModel  = profileViewModel,
                                    shoppingClosetViewModel = shoppingClosetViewModel,
                                    onShowItemInWardrobe = { image ->
                                        val matchFolder = image.folderId
                                        val viewingAll = locationState.activeLocationId == LocationViewModel.ALL_LOCATIONS_ID
                                        if (!viewingAll && matchFolder.isNotEmpty()
                                            && matchFolder != locationState.activeLocationId
                                        ) {
                                            locationViewModel.setActiveLocation(matchFolder)
                                        }
                                        wardrobeViewModel.requestScrollToImage(image.driveId)
                                        selectedTab = 1
                                        navResetTick++
                                    },
                                    outfits = stylesState.outfits,
                                    locations = locationState.locations,
                                    onOpenSourceOutfit = { outfit ->
                                        // Close the Try-On dialog, jump to Outfits, and ask the
                                        // list to scroll the picked outfit into view + highlight.
                                        tryOnViewModel.close()
                                        stylesViewModel.requestScrollToOutfit(outfit.id)
                                        selectedTab = 0
                                        navResetTick++
                                    },
                                    onStartTryOn = { showQuickTryOnSheet = true },
                                    onOpenProfileSettings = {
                                        tryOnViewModel.close()
                                        selectedTab = 5
                                        navResetTick++
                                    },
                                )

                                // Quick Try-On entry sheet — opened from the center nav button,
                                // the history FAB, and the empty-state CTA. Hosted here so it
                                // floats above whatever destination / dialog is on top.
                                if (showQuickTryOnSheet) {
                                    LaunchedEffect(Unit) { Analytics.action("TryOn/QuickSheet", "shown") }
                                    com.librelookai.tryon.QuickTryOnSheet(
                                        onPickOutfit = {
                                            Analytics.action("TryOn/QuickSheet", "pick_source", mapOf("source" to "outfit"))
                                            showQuickTryOnSheet = false
                                            tryOnViewModel.openComposer(
                                                emptySet(), null,
                                                com.librelookai.tryon.TryOnSourceKind.OUTFIT,
                                                autoPick = true,
                                            )
                                        },
                                        onPickWardrobe = {
                                            Analytics.action("TryOn/QuickSheet", "pick_source", mapOf("source" to "wardrobe"))
                                            showQuickTryOnSheet = false
                                            tryOnViewModel.openComposer(
                                                emptySet(), null,
                                                com.librelookai.tryon.TryOnSourceKind.WARDROBE,
                                                autoPick = true,
                                            )
                                        },
                                        onPickShopping = {
                                            Analytics.action("TryOn/QuickSheet", "pick_source", mapOf("source" to "shopping"))
                                            showQuickTryOnSheet = false
                                            tryOnViewModel.openComposer(
                                                emptySet(), null,
                                                com.librelookai.tryon.TryOnSourceKind.SHOPPING,
                                                autoPick = true,
                                            )
                                        },
                                        onPickTravel = {
                                            Analytics.action("TryOn/QuickSheet", "pick_source", mapOf("source" to "travel"))
                                            showQuickTryOnSheet = false
                                            showTripTryOnPicker = true
                                        },
                                        onSeeHistory = {
                                            Analytics.action("TryOn/QuickSheet", "pick_source", mapOf("source" to "history"))
                                            showQuickTryOnSheet = false
                                            tryOnViewModel.openHistoryRoot()
                                        },
                                        onDismiss = {
                                            Analytics.action("TryOn/QuickSheet", "dismiss")
                                            showQuickTryOnSheet = false
                                        },
                                        showTravel = travelTryOnAvailable,
                                    )
                                }

                                // Trip-outfit picker — opened from the Quick sheet's travel row.
                                // Picking a day feeds that day's outfit into the composer tagged
                                // with the TRAVEL source so provenance reads "{trip} · Day {n}".
                                if (showTripTryOnPicker) {
                                    com.librelookai.tryon.TripOutfitPickerDialog(
                                        trips = tripsUiState.trips,
                                        outfits = stylesState.outfits,
                                        wardrobeImages = wardrobeImagesForTryOn,
                                        onPick = { tripName, day, outfit ->
                                            Analytics.action("TryOn/TripPicker", "pick_day", mapOf("day" to day.toString()))
                                            showTripTryOnPicker = false
                                            tryOnViewModel.openComposer(
                                                outfit.itemIds.toSet(),
                                                outfit.id,
                                                com.librelookai.tryon.TryOnSourceKind.TRAVEL,
                                                tryOnContext.getString(R.string.tryon_trip_context, tripName, day),
                                            )
                                        },
                                        onDismiss = { showTripTryOnPicker = false },
                                    )
                                }

                                // Unified style composer — opened from any screen that seeds items.
                                OutfitComposerScreen(
                                    stylesViewModel   = stylesViewModel,
                                    wardrobeViewModel = wardrobeViewModel,
                                    profileViewModel  = profileViewModel,
                                    weatherViewModel  = weatherViewModel,
                                    shoppingClosetViewModel = shoppingClosetViewModel,
                                    locationViewModel = locationViewModel,
                                )

                                // Prediction-setup dialog — globally hosted so it appears
                                // regardless of which tab is on top (the composer can be opened
                                // from Wardrobe and ask for AI suggestions before the user has
                                // ever visited the Outfits tab).
                                com.librelookai.outfit.PredictionSetupDialog(
                                    outfitsViewModel  = stylesViewModel,
                                    profileViewModel  = profileViewModel,
                                    weatherViewModel  = weatherViewModel,
                                    locationViewModel = locationViewModel,
                                    wardrobeViewModel = wardrobeViewModel,
                                )

                                // Replacements result dialog — opened from Wardrobe selection FAB.
                                ReplacementsResultDialog(gapViewModel = gapViewModel)

                                // Global insufficient-credits handler — listens for 402s emitted
                                // by GeminiRepository.throwIf402 and shows the dialog regardless
                                // of which ViewModel triggered the call. "Buy" jumps to Settings.
                                var topUpEvent by remember {
                                    mutableStateOf<com.librelookai.billing.InsufficientCreditsException?>(null)
                                }
                                LaunchedEffect(Unit) {
                                    com.librelookai.billing.CreditsEvents.topUp.collect { topUpEvent = it }
                                }
                                topUpEvent?.let { ex ->
                                    com.librelookai.billing.InsufficientCreditsDialog(
                                        needed = ex.needed,
                                        have = ex.have,
                                        onBuy = {
                                            topUpEvent = null
                                            selectedTab = 5 // Settings → Credits tab
                                        },
                                        onDismiss = { topUpEvent = null },
                                    )
                                }

                                // Cutout-background fix confirmation — globally hosted so it
                                // appears whether the scan was started from Wardrobe header or
                                // Settings → Data.
                                val cutoutBgFix = wardrobeViewModel.state.collectAsState().value.cutoutBgFix
                                if (cutoutBgFix?.awaitingConfirmation == true) {
                                    FixCutoutBgDialog(
                                        state = cutoutBgFix,
                                        onToggleSelection = wardrobeViewModel::toggleCutoutFixSelection,
                                        onSetSelection = wardrobeViewModel::setCutoutFixSelection,
                                        onSetShowAll = wardrobeViewModel::setCutoutFixShowAll,
                                        onSetAction = wardrobeViewModel::setCutoutFixAction,
                                        fetchThumbnail = wardrobeViewModel::fetchCutoutFixThumbnail,
                                        onFix = { wardrobeViewModel.continueCutoutBgFix(true) },
                                        onCancel = { wardrobeViewModel.continueCutoutBgFix(false) },
                                    )
                                }
                            }
                        }
                        }
                    } }
                }
    }
}
