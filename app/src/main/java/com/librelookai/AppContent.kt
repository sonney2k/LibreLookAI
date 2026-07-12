package com.librelookai

import android.Manifest
import com.librelookai.core.designsystem.R as DsR
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.librelookai.auth.AuthViewModel
import com.librelookai.auth.SignInScreen
import com.librelookai.billing.CreditsViewModel
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.model.Location
import com.librelookai.data.model.TryOn
import com.librelookai.gemini.TokenUsageRepository
import com.librelookai.onboarding.OnboardingScreen
import com.librelookai.onboarding.OnboardingState
import com.librelookai.outfit.OutfitComposerScreen
import com.librelookai.outfit.OutfitEventsViewModel
import com.librelookai.outfit.OutfitsScreen
import com.librelookai.outfit.OutfitsViewModel
import com.librelookai.outfit.applyTagSuggestions
import com.librelookai.outfit.closeComposer
import com.librelookai.outfit.closeOutfitTagsEditor
import com.librelookai.outfit.dismissTagSuggestions
import com.librelookai.outfit.openComposer
import com.librelookai.outfit.setOutfitTags
import com.librelookai.outfit.startEditingTripOutfit
import com.librelookai.settings.AppLanguage
import com.librelookai.wardrobe.FixCutoutBgDialog
import com.librelookai.settings.ProfileViewModel
import com.librelookai.settings.SettingsScreen
import com.librelookai.shopping.ShoppingClosetViewModel
import com.librelookai.shopping.importQuery
import com.librelookai.shopping.ShoppingHelperViewModel
import com.librelookai.travel.TravelScreen
import com.librelookai.travel.TravelViewModel
import com.librelookai.travel.TripsViewModel
import com.librelookai.tryon.TryOnComposerScreen
import com.librelookai.tryon.TryOnViewModel
import com.librelookai.ui.theme.LibreLookAITheme
import com.librelookai.util.Analytics
import com.librelookai.util.LocalIsOffline
import com.librelookai.util.LocalSystemBarsPadding
import com.librelookai.util.NetworkMonitor
import com.librelookai.util.RestoreCategory
import com.librelookai.util.RestoreProgressOverlay
import com.librelookai.util.StartupGate
import com.librelookai.wardrobe.LocalBgRemovalScreen
import com.librelookai.wardrobe.LocationViewModel
import com.librelookai.wardrobe.WardrobeGapViewModel
import com.librelookai.wardrobe.WardrobeScreen
import com.librelookai.wardrobe.WardrobeViewModel
import com.librelookai.weather.WeatherBadge
import com.librelookai.weather.WeatherViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun AppContent(
    activity: ComponentActivity,
    driveRepository: DriveRepository,
    networkMonitor: NetworkMonitor,
    aiRetry: com.librelookai.gemini.AiRetry,
    geminiProgress: com.librelookai.gemini.GeminiProgress,
    aiEvents: com.librelookai.gemini.AiEvents,
    creditsEvents: com.librelookai.billing.CreditsEvents,
) {
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

                // The injected process-wide monitor (provided in :core:sync, started for the
                // process life — no per-activity register/unregister). The SyncEngine catch-up
                // drain and the wardrobe prefetch retry collect it themselves now
                // (SyncConnectivityCatchUp / the VM's init collector, § 5) — no bridges here.
                val isOnline by networkMonitor.isOnline.collectAsState()
                val isOffline = !isOnline
                val profileViewModel: ProfileViewModel = viewModel()
                // Hoisted above the entry gate so the onboarding branch can react to it too — the
                // tour reloads prefs after Drive sign-in and re-themes live as they land.
                val profileState by profileViewModel.state.collectAsState()
                // Controller of the nested Home tab NavHost. Hoisted above the entry gate so a
                // tour replay (LocalStartTour) doesn't reset the active tab — the controller's
                // saved back stack survives the full-app branch leaving composition.
                val tabNavController = rememberNavController()
                // Tab the nested NavHost should jump to once it's composed — set by surfaces
                // shown while Home isn't (the onboarding tour), consumed by an effect in the
                // full-app branch (navigating before the tab graph is set would throw).
                var pendingHomeTab by rememberSaveable { mutableStateOf<Int?>(null) }
                // Increments on every nav button tap (incl. re-tap and gear icon)
                // so screens with sub-tabs can reset to their default tab.
                var navResetTick by remember { mutableIntStateOf(0) }
                // First-run onboarding tour. Shown until the user completes/skips it; can be
                // re-launched from Settings via LocalStartTour (which just flips this back on).
                var showOnboarding by rememberSaveable {
                    mutableStateOf(!OnboardingState.isComplete(activity))
                }

                // Onboarding / signed-out launches have nothing to restore from Drive, so release the
                // launch splash as soon as we land on one. The signed-in arm releases it later, once
                // its restore has settled (see the restore block below).
                LaunchedEffect(showOnboarding, isSignedIn) {
                    if (showOnboarding || !isSignedIn) StartupGate.contentReady = true
                }

                when {
                    // First-run (or replayed) tour: the outermost gate. Carries Drive sign-in +
                    // background-permission steps, so it renders even when not yet signed in.
                    showOnboarding -> {
                        // Re-theme AND re-localize live from the loaded prefs (overriding the cached
                        // outer theme/locale) so an account with a saved palette/font/language
                        // re-skins and re-languages the tour the moment the post-sign-in prefs reload
                        // lands — same pattern as the main-app branch.
                        val onboardingLanguage = profileState.preferences.language
                        val onboardingBaseContext = LocalContext.current
                        val onboardingContext = remember(onboardingLanguage) {
                            onboardingBaseContext.localizedWrapper(onboardingLanguage)
                        }
                        CompositionLocalProvider(
                            LocalContext provides onboardingContext,
                            LocalConfiguration provides onboardingContext.resources.configuration,
                            // The localizedWrapper keeps the activity reachable through the
                            // context chain, but re-provide the registry/back-dispatcher owners
                            // explicitly anyway — same as the main-app branch — so
                            // OnboardingScreen's rememberLauncherForActivityResult can't regress
                            // if the wrapper ever changes.
                            LocalActivityResultRegistryOwner provides activity,
                            LocalOnBackPressedDispatcherOwner provides activity,
                        ) {
                        LibreLookAITheme(
                            paletteId = profileState.preferences.wardrobeTheme,
                            fontId = profileState.preferences.appFont,
                        ) {
                            OnboardingScreen(
                                profileState = profileViewModel.state,
                                onLoadPreferences = profileViewModel::loadPreferences,
                                onSavePreferences = profileViewModel::savePreferences,
                                onUploadTryOnPhoto = profileViewModel::uploadTryOnPhoto,
                                isSignedIn = isSignedIn,
                                signInErrorCode = authError,
                                onStartSignIn = {
                                    Analytics.action("SignIn", "start_sign_in")
                                    authViewModel.startSignIn()
                                },
                                isOffline = isOffline,
                                onFinish = { goToWardrobe ->
                                    OnboardingState.setComplete(activity, true)
                                    showOnboarding = false
                                    if (goToWardrobe) { pendingHomeTab = 1; navResetTick++ }
                                },
                            )
                        }
                        }
                    }
                    // Returning user who signed out after completing onboarding.
                    !isSignedIn -> {
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
                    }
                    else -> {
                    val usageRepo = remember { TokenUsageRepository.get(activity.application) }
                    val driveRepo = driveRepository
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
                        }
                    }

                    // Active tab derived from the nested tab NavHost (single-entry back stack;
                    // see AppNavigation.kt). selectTab replaces the stack so system back still
                    // exits the app from any tab, like the old `selectedTab` int.
                    val tabBackStackEntry by tabNavController.currentBackStackEntryAsState()
                    val selectedTab = homeTabIndex(tabBackStackEntry?.destination)
                    fun selectTab(tab: Int) {
                        tabNavController.navigate(homeTabRoute(tab)) {
                            popUpTo(tabNavController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                    // Keyed on the back-stack entry too: if the request lands before the nested
                    // NavHost has composed (no graph yet — e.g. the battery-exemption gate is
                    // showing), the effect re-runs and consumes it once the graph exists.
                    LaunchedEffect(pendingHomeTab, tabBackStackEntry) {
                        pendingHomeTab?.let { tab ->
                            if (tabNavController.currentDestination != null) {
                                selectTab(tab)
                                pendingHomeTab = null
                            }
                        }
                    }

                    LaunchedEffect(selectedTab) {
                        val name = when (selectedTab) {
                            0 -> "Outfits"; 1 -> "Wardrobe"; 2 -> "Shopping"
                            3 -> "Travel"; 5 -> "Settings"
                            else -> "Tab$selectedTab"
                        }
                        Analytics.screen(name)
                    }
                    val locationViewModel: LocationViewModel = viewModel()
                    val stylesViewModel: OutfitsViewModel = viewModel()
                    val outfitGenerationViewModel: com.librelookai.outfit.OutfitGenerationViewModel = viewModel()
                    val wardrobeViewModel: WardrobeViewModel = viewModel()
                    val outfitEventsViewModel: OutfitEventsViewModel = viewModel()
                    val weatherViewModel: WeatherViewModel = viewModel()
                    val travelViewModel: TravelViewModel = viewModel()
                    val tripsViewModel: com.librelookai.travel.TripsViewModel = viewModel()
                    val gapViewModel: WardrobeGapViewModel = viewModel()
                    val creditsViewModel: CreditsViewModel = viewModel()
                    val tryOnViewModel: TryOnViewModel = viewModel()
                    val tryOnHistoryViewModel: com.librelookai.tryon.TryOnHistoryViewModel = viewModel()
                    val shoppingViewModel: ShoppingHelperViewModel = viewModel()
                    val shoppingClosetViewModel: ShoppingClosetViewModel = viewModel()
                    val locationState by locationViewModel.state.collectAsState()
                    val weatherState by weatherViewModel.state.collectAsState()
                    val canTryOn = profileState.tryOnLocalPaths.isNotEmpty()

                    // The calendar wear history reaches the outfit/travel prompts as a DB read:
                    // OutfitsViewModel and TravelViewModel collect wearHistoryFlow (the event
                    // store scoped by the closet session) in init — refactor § 5 slice 3; the
                    // old events→styles state mirror here is gone.

                    // Closet topology (locations, active filter, default closet, shopping folder)
                    // flows through the ClosetSessionHolder singleton: LocationViewModel and
                    // ShoppingClosetViewModel publish, the wardrobe/outfits/events VMs collect it
                    // in init (refactor § 5 slice 1 — the old fan-out bridge here is gone).
                    val activeLocationId = locationState.activeLocationId
                    val locationList = locationState.locations
                    val shoppingClosetState by shoppingClosetViewModel.state.collectAsState()

                    // Preference-derived knobs (tagging language, dedupe, bg-removal routing,
                    // similarity debug, encoder tier, segmenter threshold) flow through the
                    // UserPreferencesRepository singleton: ProfileViewModel publishes, the
                    // wardrobe/shopping VMs and StaticPreferenceMirrors collect (refactor § 5
                    // slice 2 — the old per-pref mirrors here are gone). The connectivity
                    // retry (wardrobe prefetch) and the shopping / try-on-history / trips
                    // pre-warm loads live in the owning VMs' `init` now (§ 5 — the old
                    // LaunchedEffect bridges here are gone).

                    // Apply selected language as the Compose context locale
                    val language = profileState.preferences.language
                    val baseContext = LocalContext.current
                    val localizedContext = remember(language) {
                        baseContext.localizedWrapper(language)
                    }

                    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()

                    // Root nav controller — created above the CompositionLocalProvider so the
                    // locals' tab-jump lambdas can pop overlaying destinations too.
                    val navController = rememberNavController()
                    // Tab jumps from globally hosted dialogs/composers must also pop any
                    // full-screen destination overlaying Home, or the target tab would change
                    // invisibly underneath it.
                    fun goToTab(tab: Int) {
                        navController.popBackStack(HomeRoute, inclusive = false)
                        selectTab(tab)
                    }

                    CompositionLocalProvider(
                        LocalContext provides localizedContext,
                        LocalConfiguration provides localizedContext.resources.configuration,
                        LocalActivityResultRegistryOwner provides activity,
                        LocalOnBackPressedDispatcherOwner provides activity,
                        LocalIsOffline provides isOffline,
                        LocalGeminiProgress provides geminiProgress,
                        LocalSystemBarsPadding provides systemBarsPadding,
                        // The Settings opener clears both composers' drafts (their routes pop
                        // via goToTab; close()/closeComposer() are state hygiene, § 5 slice 9).
                        LocalOpenSettings provides {
                            Analytics.action("Toolbar", "open_settings")
                            tryOnViewModel.close(); outfitGenerationViewModel.closeComposer()
                            goToTab(5); navResetTick++
                        },
                        LocalClosetSelector provides ClosetSelectorContext(
                            locations = locationList,
                            activeLocationId = activeLocationId,
                            onSetActiveLocation = locationViewModel::setActiveLocation,
                        ),
                        // PhotoReviewScreen's confirm-cost badge — the badge composable lives in
                        // feature/billing, above core/designsystem, so the shell bridges it.
                        LocalRemoveBgCostBadge provides { width, height ->
                            com.librelookai.billing.CostBadge(
                                com.librelookai.gemini.GeminiActionId.REMOVE_BACKGROUND,
                                tokens = com.librelookai.billing.rememberRemoveBgCostTokens(width, height),
                            )
                        },
                        LocalTryOnCostBadge provides { personPaths, itemPaths ->
                            com.librelookai.billing.CostBadge(
                                com.librelookai.gemini.GeminiActionId.TRY_ON_OUTFIT,
                                tokens = com.librelookai.billing.rememberTryOnCostTokens(
                                    personPaths = personPaths,
                                    itemPaths = itemPaths,
                                ),
                            )
                        },
                        // Travel planner / trip bulk-refine generate-cost badge — same
                        // feature/billing bridge as the try-on badge (feature→feature is forbidden).
                        com.librelookai.travel.LocalTravelCostBadge provides { tokens ->
                            com.librelookai.billing.CostBadge(
                                com.librelookai.gemini.GeminiActionId.GENERATE_TEXT,
                                tokens = tokens,
                            )
                        },
                        // Outfit composer / prediction generate-cost badge — same feature/billing
                        // bridge (feature→feature is forbidden).
                        com.librelookai.outfit.LocalOutfitCostBadge provides { action, bulkCount, tokens ->
                            com.librelookai.billing.CostBadge(
                                action,
                                bulkCount = bulkCount,
                                tokens = tokens,
                            )
                        },
                        // Wardrobe per-item cost badge (bg-removal skip / re-detect tags) — same
                        // feature/billing bridge; the token estimate is derived from the file path.
                        com.librelookai.wardrobe.LocalWardrobeCostBadge provides { action, filePath ->
                            val tokens = if (action == com.librelookai.gemini.GeminiActionId.REMOVE_BACKGROUND)
                                com.librelookai.billing.rememberRemoveBgCostTokens(filePath)
                            else com.librelookai.billing.rememberClassifyCostTokens(filePath)
                            com.librelookai.billing.CostBadge(action, tokens = tokens)
                        },
                        LocalStartTour provides { showOnboarding = true },
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
                                title = { Text(stringResource(DsR.string.battery_exempt_title)) },
                                text = { Text(stringResource(DsR.string.battery_exempt_text)) },
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
                                    ) { Text(stringResource(DsR.string.battery_exempt_action)) }
                                },
                            )
                        } else {
                        // Box so the onboarding tour can draw as an opaque overlay above the whole
                        // app (incl. the bottom nav bar) without restructuring the Scaffold below.
                        Box(Modifier.fillMaxSize()) {

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

                        var showQuickTryOnSheet by remember { mutableStateOf(false) }
                        var showTripTryOnPicker by remember { mutableStateOf(false) }
                        // Hide the nav bar + weather badge while a SelectionActionBar is up
                        // (multi-select) — the selection bar takes the nav bar's place at the
                        // bottom (see ui/components/SelectionBarVisibility).
                        val hideChrome = com.librelookai.ui.components.SelectionBarVisibility.isVisible

                        // Shared by Home's tabs and the trip-viewer destination.
                        val onSettingsClick: () -> Unit = {
                            Analytics.action("Toolbar", "open_settings")
                            goToTab(5)
                            navResetTick++
                        }
                        val runTryOn: (Set<String>, String?) -> Unit = { itemIds, sourceOutfitId ->
                            Analytics.action("TryOn", "open_composer", mapOf("count" to itemIds.size.toString()))
                            tryOnViewModel.openComposer(itemIds, sourceOutfitId)
                            navController.navigate(TryOnRoute) { launchSingleTop = true }
                        }
                        // Try-on a saved outfit, preserving the outfit link so the saved try-on
                        // can jump back to it. Shared by the Outfits tab and the viewer destination.
                        val runOutfitTryOn: (com.librelookai.data.model.Outfit) -> Unit = { style ->
                            stylesViewModel.clearOutfitSelection()
                            runTryOn(style.itemIds.toSet(), style.id)
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
                            navController.navigate(TryOnRoute) { launchSingleTop = true }
                        }

                        NavHost(
                            navController = navController,
                            startDestination = HomeRoute,
                            enterTransition = { fadeIn(tween(220)) },
                            exitTransition = { fadeOut(tween(220)) },
                            popEnterTransition = { fadeIn(tween(220)) },
                            popExitTransition = { fadeOut(tween(220)) },
                        ) {
                        composable<HomeRoute> {
                        // Pin VM resolution to the activity: inside a NavHost destination the
                        // ViewModelStoreOwner is the back-stack entry, so any nested `viewModel()`
                        // default would silently fork a fresh VM instead of the shared activity
                        // instance. Only Home / the tab destinations / the Settings sub-screens
                        // still pin — their subtrees resolve defaulted activity-scoped VMs
                        // (e.g. OutfitsScreen's tripsViewModel, OutfitCalendarTab's
                        // weatherViewModel, UsageScreen's UsageViewModel). The overlay
                        // destinations dropped their pins (§ 5 slice 9): their screens take
                        // every VM as a required parameter, so a defaulted `viewModel()` can't
                        // appear there without a signature change.
                        CompositionLocalProvider(LocalViewModelStoreOwner provides activity) {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            bottomBar = {
                                if (!hideChrome) {
                                    AppNavBar(
                                        selectedTab = selectedTab,
                                        onTabSelected = {
                                            Analytics.action("NavBar", "tab_select", mapOf("index" to it.toString()))
                                            selectTab(it)
                                            navResetTick++
                                        },
                                        onTabReselected = { tab ->
                                            Analytics.action("NavBar", "tab_reselect", mapOf("index" to tab.toString()))
                                            // Re-tapping Settings pops any sub-screen back to
                                            // the root page (the old navResetTick stack reset).
                                            if (tab == 5) {
                                                tabNavController.popBackStack(SettingsTabRoute, inclusive = false)
                                            }
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
                                OfflineBanner(visible = isOffline)

                                // Hide the floating weather badge while the active screen is being
                                // scrolled. The badge is hosted here (over all tabs), not in the
                                // screens, so we sense scrolling at this wrapper: scroll deltas from
                                // any inner Lazy list — or a horizontal pager like the calendar's
                                // month swipe — bubble up to this NestedScrollConnection. A short
                                // debounce after the last delta reveals it again once at rest.
                                var lastScrollNanos by remember { mutableStateOf(0L) }
                                var isScrolling by remember { mutableStateOf(false) }
                                val weatherScrollConn = remember {
                                    object : NestedScrollConnection {
                                        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                                            if (available.x != 0f || available.y != 0f) lastScrollNanos = System.nanoTime()
                                            return Offset.Zero
                                        }
                                    }
                                }
                                LaunchedEffect(lastScrollNanos) {
                                    if (lastScrollNanos == 0L) return@LaunchedEffect
                                    isScrolling = true
                                    delay(600)
                                    isScrolling = false
                                }

                                Box(Modifier.fillMaxSize().nestedScroll(weatherScrollConn)) {
                                    // The tabs are destinations of this nested NavHost (the
                                    // bottom bar lives in the Scaffold around it). Each tab
                                    // re-pins VM resolution to the activity — a nested
                                    // destination's back-stack entry would otherwise fork
                                    // fresh VMs, same rule as the root destinations.
                                    NavHost(
                                        navController = tabNavController,
                                        startDestination = OutfitsTabRoute,
                                        // Tab switches were an instant `when(selectedTab)`
                                        // swap — keep them transition-free.
                                        enterTransition = { androidx.compose.animation.EnterTransition.None },
                                        exitTransition = { androidx.compose.animation.ExitTransition.None },
                                        popEnterTransition = { androidx.compose.animation.EnterTransition.None },
                                        popExitTransition = { androidx.compose.animation.ExitTransition.None },
                                        modifier = Modifier.fillMaxSize(),
                                    ) {
                                        composable<OutfitsTabRoute> {
                                        CompositionLocalProvider(LocalViewModelStoreOwner provides activity) {
                                        val outfitsWardrobeState by wardrobeViewModel.state.collectAsState()
                                        val outfitsTripsState by tripsViewModel.state.collectAsState()
                                        val outfitsTripNames = remember(outfitsTripsState.trips) {
                                            outfitsTripsState.trips.associate { it.id to it.name }
                                        }
                                        OutfitsScreen(
                                            outfitsViewModel = stylesViewModel,
                                            generationViewModel = outfitGenerationViewModel,
                                            onOpenComposer = {
                                                navController.navigate(OutfitComposerRoute) { launchSingleTop = true }
                                            },
                                            outfitEventsViewModel = outfitEventsViewModel,
                                            weatherViewModel = weatherViewModel,
                                            wardrobeImages = outfitsWardrobeState.images,
                                            allLocationImages = outfitsWardrobeState.allLocationImages,
                                            wardrobeIsSyncing = outfitsWardrobeState.isSyncing,
                                            wardrobeIsLoading = outfitsWardrobeState.isLoading,
                                            preferences = profileState.preferences,
                                            locations = locationState.locations,
                                            activeLocationId = locationState.activeLocationId,
                                            activeFolderId = locationViewModel.activeFolderId,
                                            onSetActiveLocation = locationViewModel::setActiveLocation,
                                            tripNamesById = outfitsTripNames,
                                            onFuzzyTextFilter = wardrobeViewModel::fuzzyFilterByText,
                                            onTryOnStyle = runOutfitTryOn,
                                            canTryOn = canTryOn,
                                            onSettingsClick = onSettingsClick,
                                            navResetTick = navResetTick,
                                            onOpenOutfitViewer = { outfitIds, initialOutfitId ->
                                                navController.navigate(
                                                    OutfitViewerRoute(
                                                        source = OutfitViewerRoute.SOURCE_LIST,
                                                        outfitIds = outfitIds,
                                                        initialOutfitId = initialOutfitId,
                                                    ),
                                                ) { launchSingleTop = true }
                                            },
                                            onOpenPredictionViewer = {
                                                navController.navigate(
                                                    OutfitViewerRoute(source = OutfitViewerRoute.SOURCE_PREDICTION),
                                                ) { launchSingleTop = true }
                                            },
                                        )
                                        }
                                        }
                                        composable<WardrobeTabRoute> {
                                        CompositionLocalProvider(LocalViewModelStoreOwner provides activity) {
                                        val wardrobeOutfitEventsState by outfitEventsViewModel.state.collectAsState()
                                        val wardrobeOutfitsState by stylesViewModel.state.collectAsState()
                                        val wardrobeTryOnState by tryOnHistoryViewModel.state.collectAsState()
                                        val wardrobeCreditsState by creditsViewModel.state.collectAsState()
                                        WardrobeScreen(
                                            viewModel = wardrobeViewModel,
                                            outfitEvents = wardrobeOutfitEventsState.events,
                                            outfits = wardrobeOutfitsState.outfits,
                                            onDeleteOutfitsByIds = stylesViewModel::deleteOutfitsByIds,
                                            tryOnHistory = wardrobeTryOnState.history,
                                            onDeleteTryOns = tryOnHistoryViewModel::deleteTryOns,
                                            onImportQuery = shoppingClosetViewModel::importQuery,
                                            preferences = profileState.preferences,
                                            locations = locationState.locations,
                                            activeLocationId = locationState.activeLocationId,
                                            locationLoading = locationState.isLoading,
                                            locationError = locationState.error,
                                            onSetActiveLocation = locationViewModel::setActiveLocation,
                                            creditsBalance = wardrobeCreditsState.balance,
                                            powerFeatures = com.librelookai.util.FeatureFlags.powerFeatures,
                                            onCreateOutfitFromSelection = { itemIds ->
                                                Analytics.action("Wardrobe", "create_outfit_from_selection", mapOf("count" to itemIds.size.toString()))
                                                outfitGenerationViewModel.openComposer(
                                                    seedItemIds = itemIds,
                                                    images      = wardrobeViewModel.state.value.images,
                                                    prefs       = profileViewModel.state.value.preferences,
                                                    // Default to the currently selected closet (null = All).
                                                    defaultSourceFolderId = locationViewModel.activeFolderId,
                                                )
                                                navController.navigate(OutfitComposerRoute) { launchSingleTop = true }
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
                                            onOpenItemViewer = { itemIds, initialItemId ->
                                                navController.navigate(
                                                    ItemViewerRoute(
                                                        source = ItemViewerRoute.SOURCE_WARDROBE,
                                                        itemIds = itemIds,
                                                        initialItemId = initialItemId,
                                                    ),
                                                ) { launchSingleTop = true }
                                            },
                                            onSettingsClick = onSettingsClick,
                                        )
                                        }
                                        }
                                        composable<ShoppingTabRoute> {
                                        CompositionLocalProvider(LocalViewModelStoreOwner provides activity) {
                                        ShoppingHelperScreen(
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
                                                goToTab(1)
                                                navResetTick++
                                            },
                                            onCreateOutfitFromSelection = { itemIds ->
                                                Analytics.action("Shopping", "create_outfit_from_selection", mapOf("count" to itemIds.size.toString()))
                                                outfitGenerationViewModel.openComposer(
                                                    seedItemIds = itemIds,
                                                    images      = wardrobeViewModel.state.value.images +
                                                        shoppingClosetState.items,
                                                    prefs       = profileViewModel.state.value.preferences,
                                                    // Default to the currently selected closet (null = All).
                                                    defaultSourceFolderId = locationViewModel.activeFolderId,
                                                )
                                                navController.navigate(OutfitComposerRoute) { launchSingleTop = true }
                                                shoppingClosetViewModel.clearSelection()
                                            },
                                            onTryOnSelection = { itemIds ->
                                                runTryOn(itemIds, null)
                                                shoppingClosetViewModel.clearSelection()
                                            },
                                            canTryOn = canTryOn,
                                            onOpenItemViewer = { itemIds, initialItemId ->
                                                navController.navigate(
                                                    ItemViewerRoute(
                                                        source = ItemViewerRoute.SOURCE_SHOPPING,
                                                        itemIds = itemIds,
                                                        initialItemId = initialItemId,
                                                    ),
                                                ) { launchSingleTop = true }
                                            },
                                            navResetTick = navResetTick,
                                        )
                                        }
                                        }
                                        composable<TravelTabRoute> {
                                        CompositionLocalProvider(LocalViewModelStoreOwner provides activity) {
                                        // Travel takes data + funnel callbacks, no cross-feature
                                        // VM types (§ 1 slice 6 — the try-on/onboarding precedent).
                                        val travelOutfitsState by stylesViewModel.state.collectAsState()
                                        val travelWardrobeState by wardrobeViewModel.state.collectAsState()
                                        TravelScreen(
                                            travelViewModel = travelViewModel,
                                            tripsViewModel = tripsViewModel,
                                            outfits = travelOutfitsState.outfits,
                                            wardrobeImages = travelWardrobeState.images,
                                            allLocationImages = travelWardrobeState.allLocationImages,
                                            preferences = profileState.preferences,
                                            locations = locationState.locations,
                                            activeLocationId = locationState.activeLocationId,
                                            activeFolderId = locationViewModel.activeFolderId,
                                            onSetActiveLocation = locationViewModel::setActiveLocation,
                                            onEditOutfit = { outfit ->
                                                outfitGenerationViewModel.startEditing(
                                                    outfit,
                                                    travelWardrobeState.images,
                                                    profileState.preferences,
                                                )
                                                navController.navigate(OutfitComposerRoute) { launchSingleTop = true }
                                            },
                                            onDeleteOutfits = { ids -> stylesViewModel.deleteOutfitsByIds(ids) },
                                            onSettingsClick = onSettingsClick,
                                            onOpenPlanner = {
                                                navController.navigate(TravelPlannerRoute) {
                                                    launchSingleTop = true
                                                }
                                            },
                                            onOpenTrip = { tripId ->
                                                navController.navigate(TripViewerRoute(tripId)) {
                                                    launchSingleTop = true
                                                }
                                            },
                                            onEditTrip = { tripId ->
                                                navController.navigate(
                                                    TripViewerRoute(tripId, startInEdit = true),
                                                ) { launchSingleTop = true }
                                            },
                                        )
                                        }
                                        }
                                        composable<SettingsTabRoute> {
                                        CompositionLocalProvider(LocalViewModelStoreOwner provides activity) {
                                        SettingsScreen(
                                            profileViewModel = profileViewModel,
                                            wardrobeViewModel = wardrobeViewModel,
                                            locationViewModel = locationViewModel,
                                            creditsViewModel = creditsViewModel,
                                            onBack = { selectTab(1) },
                                            onOpenProfileEdit = {
                                                tabNavController.navigate(SettingsProfileEditRoute) { launchSingleTop = true }
                                            },
                                            onOpenAdvanced = {
                                                tabNavController.navigate(SettingsAdvancedRoute) { launchSingleTop = true }
                                            },
                                            onOpenAbout = {
                                                tabNavController.navigate(SettingsAboutRoute) { launchSingleTop = true }
                                            },
                                            onOpenBuyCredits = {
                                                tabNavController.navigate(SettingsBuyCreditsRoute) { launchSingleTop = true }
                                            },
                                        )
                                        }
                                        }
                                        settingsDestinations(
                                            activity = activity,
                                            profileViewModel = profileViewModel,
                                            wardrobeViewModel = wardrobeViewModel,
                                            locationViewModel = locationViewModel,
                                            creditsViewModel = creditsViewModel,
                                            navigate = { route ->
                                                tabNavController.navigate(route) { launchSingleTop = true }
                                            },
                                            onBack = { tabNavController.popBackStack() },
                                        )
                                    }

                                    // Weather badge — bottom-left, floating above the nav bar.
                                    // Hidden while a selection bar is up (it takes the bottom edge)
                                    // and while the active screen is being scrolled (fades back in
                                    // shortly after scrolling stops — see weatherScrollConn above).
                                    androidx.compose.animation.AnimatedVisibility(
                                        visible = !hideChrome && !isScrolling && weatherState.data != null,
                                        enter = androidx.compose.animation.fadeIn(),
                                        exit = androidx.compose.animation.fadeOut(),
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(start = 12.dp, bottom = 8.dp),
                                    ) {
                                        weatherState.data?.let { weather ->
                                            WeatherBadge(data = weather)
                                        }
                                    }

                                    // Local background-removal review — fullscreen overlay shown
                                    // when an import is queued for on-device cutout refinement.
                                    // Rendered inside this Box so it stacks on top of content.
                                    LocalBgRemovalScreen(viewModel = wardrobeViewModel)
                                }
                            }
                        }
                        }
                        }

                        composable<TripViewerRoute> { entry ->
                            val route = entry.toRoute<TripViewerRoute>()
                            val tripId = route.tripId
                            LaunchedEffect(Unit) { Analytics.screen("TripViewer") }
                            // Destination-scoped trips VM (§ 5 slice 9): resolved against this
                            // back-stack entry, so its transient viewer state (refine preview,
                            // bulk-refine flags) dies with the destination. Safe to fork because
                            // trips derive from the shared TripsRepository (mutations reach the
                            // Travel tab's instance via store invalidation; the init pre-warm
                            // joins the single-flight reconcile instead of re-listing Drive).
                            val tripViewerTripsViewModel: TripsViewModel =
                                androidx.hilt.navigation.compose.hiltViewModel(entry)
                            // No activity pin here: the screen takes data + funnel callbacks
                            // (§ 1 slice 6 — no cross-feature VM types cross into travel); the
                            // activity-scoped VMs are read/called only in this block.
                            val viewerOutfitsState by stylesViewModel.state.collectAsState()
                            val viewerWardrobeState by wardrobeViewModel.state.collectAsState()
                            val viewerGenerationState by outfitGenerationViewModel.state.collectAsState()
                            // Recreate the environment the viewer had when it rendered inside
                            // Home's chrome-hidden Scaffold: system-bar insets via a plain
                            // Scaffold, plus the offline banner strip above the content.
                            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                                Column(Modifier.fillMaxSize().padding(innerPadding)) {
                                    OfflineBanner(visible = isOffline)
                                    com.librelookai.travel.TripViewerScreen(
                                        tripId = tripId,
                                        startInEdit = route.startInEdit,
                                        justSaved = route.justSaved,
                                        tripsViewModel = tripViewerTripsViewModel,
                                        outfits = viewerOutfitsState.outfits,
                                        wardrobeImages = viewerWardrobeState.images,
                                        allLocationImages = viewerWardrobeState.allLocationImages,
                                        preferences = profileState.preferences,
                                        locations = locationState.locations,
                                        onEditTripOutfit = { trip, outfit ->
                                            outfitGenerationViewModel.startEditingTripOutfit(
                                                trip,
                                                outfit,
                                                viewerWardrobeState.images,
                                                profileState.preferences,
                                            )
                                            navController.navigate(OutfitComposerRoute) { launchSingleTop = true }
                                        },
                                        onDeleteOutfits = { ids -> stylesViewModel.deleteOutfitsByIds(ids) },
                                        onUpdateOutfitsRefined = stylesViewModel::updateOutfitsRefined,
                                        onRecordWear = { outfit, imagesById ->
                                            outfitEventsViewModel.recordOutfit(outfit, imagesById)
                                        },
                                        onClose = { navController.popBackStack() },
                                        canTryOn = canTryOn,
                                        onTryOnOutfit = runTripOutfitTryOn,
                                        onOpenOutfitViewer = { outfit ->
                                            navController.navigate(
                                                OutfitViewerRoute(
                                                    source = OutfitViewerRoute.SOURCE_TRIP,
                                                    initialOutfitId = outfit.id,
                                                    tripId = tripId,
                                                ),
                                            ) { launchSingleTop = true }
                                        },
                                    )
                                }
                            }

                            // Tag-edit / AI tag-suggestion dialogs driven by the generation VM
                            // (launched from the outfit viewer over a trip day) — hosted here
                            // since § 1 slice 6 so travel carries no outfit-VM type.
                            viewerGenerationState.tagEditingOutfitId?.let { editId ->
                                viewerOutfitsState.outfits.find { it.id == editId }?.let { target ->
                                    com.librelookai.outfit.EditOutfitTagsDialog(
                                        initialTags = target.tags,
                                        onDismiss = outfitGenerationViewModel::closeOutfitTagsEditor,
                                        onSave = { newTags ->
                                            outfitGenerationViewModel.setOutfitTags(editId, newTags)
                                        },
                                    )
                                }
                            }
                            viewerGenerationState.tagSuggestion?.let { sugg ->
                                com.librelookai.outfit.SuggestTagsDialog(
                                    state = sugg,
                                    onDismiss = outfitGenerationViewModel::dismissTagSuggestions,
                                    onApply = { selected ->
                                        outfitGenerationViewModel.applyTagSuggestions(sugg.outfitId, selected)
                                    },
                                )
                            }
                        }

                        composable<OutfitViewerRoute> { entry ->
                            val route = entry.toRoute<OutfitViewerRoute>()
                            LaunchedEffect(Unit) { Analytics.screen("OutfitViewer") }
                            // Destination-scoped outfits + trips VMs (§ 5 slice 9): resolved
                            // against this back-stack entry. Safe to fork because both mirror
                            // repo-derived data (OutfitsRepository / TripsRepository); the
                            // cross-tab hand-offs the viewer fires (requestCalendarWear, scroll
                            // one-shots, pendingWear) live on the repos, so they still reach the
                            // tab instances. The generation VM stays activity-scoped — the
                            // prediction source resolves outfits from its shared state, and
                            // composer seeds must survive the pop. No activity pin: every VM in
                            // this subtree is a required parameter, passed explicitly below.
                            val viewerOutfitsViewModel: OutfitsViewModel =
                                androidx.hilt.navigation.compose.hiltViewModel(entry)
                            val viewerTripsViewModel: TripsViewModel =
                                androidx.hilt.navigation.compose.hiltViewModel(entry)
                            // Full-bleed, no Scaffold: the viewer is immersive (edge-to-edge,
                            // like its Dialog predecessor) and handles its own insets.
                            OutfitViewerDestination(
                                source = route.source,
                                routeOutfitIds = route.outfitIds,
                                initialOutfitId = route.initialOutfitId,
                                tripId = route.tripId,
                                outfitsViewModel = viewerOutfitsViewModel,
                                generationViewModel = outfitGenerationViewModel,
                                onOpenComposer = {
                                    navController.navigate(OutfitComposerRoute) { launchSingleTop = true }
                                },
                                wardrobeViewModel = wardrobeViewModel,
                                profileViewModel = profileViewModel,
                                outfitEventsViewModel = outfitEventsViewModel,
                                tripsViewModel = viewerTripsViewModel,
                                locationViewModel = locationViewModel,
                                canTryOn = canTryOn,
                                onClose = { navController.popBackStack() },
                                onTryOnStyle = runOutfitTryOn,
                                onTryOnTripOutfit = runTripOutfitTryOn,
                                onOpenItemViewer = { itemIds, initialItemId ->
                                    navController.navigate(
                                        ItemViewerRoute(
                                            source = ItemViewerRoute.SOURCE_OUTFIT,
                                            itemIds = itemIds,
                                            initialItemId = initialItemId,
                                        ),
                                    ) { launchSingleTop = true }
                                },
                            )
                        }

                        composable<TravelPlannerRoute> {
                            // Full-screen mode within the Travel tab, not a separate tab — report
                            // it as its own screen view for funnel tracking.
                            LaunchedEffect(Unit) { Analytics.screen("TravelPlanner") }
                            // No activity pin: the planner takes data + funnel callbacks
                            // (§ 1 slice 6 — no cross-feature VM types cross into travel).
                            val plannerOutfitsState by stylesViewModel.state.collectAsState()
                            val plannerWardrobeState by wardrobeViewModel.state.collectAsState()
                            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                                Column(Modifier.fillMaxSize().padding(innerPadding)) {
                                    OfflineBanner(visible = isOffline)
                                    com.librelookai.travel.TravelPlannerScreen(
                                        travelViewModel = travelViewModel,
                                        tripsViewModel = tripsViewModel,
                                        outfits = plannerOutfitsState.outfits,
                                        wardrobeImages = plannerWardrobeState.images,
                                        preferences = profileState.preferences,
                                        locations = locationState.locations,
                                        activeFolderId = locationViewModel.activeFolderId,
                                        onAddOutfits = stylesViewModel::addOutfits,
                                        onBack = { navController.popBackStack() },
                                        onOpenTrip = { tripId ->
                                            // Planner-created trip: leave the planner behind so
                                            // back from the viewer returns to the trips list.
                                            // justSaved shows the one-time "saved" confirmation
                                            // (a route arg since § 5 slice 9).
                                            navController.popBackStack(HomeRoute, inclusive = false)
                                            navController.navigate(
                                                TripViewerRoute(tripId, justSaved = true),
                                            ) { launchSingleTop = true }
                                        },
                                    )
                                }
                            }
                        }

                        composable<TryOnRoute> {
                            // Real navigation (§ 5 slice 9): openers seed the try-on draft then
                            // navigate here; the header ✕ / system back clear the draft and pop
                            // through onClose. History feed + detail are sibling destinations.
                            // No activity pin on the three try-on routes: their screens take
                            // every VM as a required parameter, passed explicitly.
                            val tryOnStyles by stylesViewModel.state.collectAsState()
                            TryOnComposerScreen(
                                tryOnViewModel = tryOnViewModel,
                                itemPool = rememberTryOnItemPool(wardrobeViewModel, shoppingClosetViewModel),
                                profileState = profileViewModel.state,
                                tryOnFiles = profileViewModel::tryOnFiles,
                                onTextFilter = wardrobeViewModel::fuzzyFilterByText,
                                findSimilarByPhoto = { file, candidates ->
                                    wardrobeViewModel.findSimilarInCandidates(file, candidates)
                                        .associate { it.driveId to it.score }
                                },
                                outfits = tryOnStyles.outfits,
                                locations = locationState.locations,
                                onStartTryOn = { showQuickTryOnSheet = true },
                                onOpenProfileSettings = {
                                    tryOnViewModel.close()
                                    goToTab(5)
                                    navResetTick++
                                },
                                onClose = {
                                    navController.popBackStack(TryOnRoute, inclusive = true)
                                },
                            )
                        }

                        composable<TryOnHistoryRoute> {
                            com.librelookai.tryon.TryOnHistoryDestination(
                                tryOnViewModel = tryOnViewModel,
                                historyViewModel = tryOnHistoryViewModel,
                                itemPool = rememberTryOnItemPool(wardrobeViewModel, shoppingClosetViewModel),
                                onOpenDetail = { tryOn ->
                                    navController.navigate(
                                        TryOnDetailRoute(imageDriveId = tryOn.imageDriveId),
                                    ) { launchSingleTop = true }
                                },
                                onStartTryOn = { showQuickTryOnSheet = true },
                                onOpenComposer = {
                                    navController.navigate(TryOnRoute) { launchSingleTop = true }
                                },
                                onClose = {
                                    navController.popBackStack(TryOnHistoryRoute, inclusive = true)
                                },
                            )
                        }

                        composable<TryOnDetailRoute> { entry ->
                            val route = entry.toRoute<TryOnDetailRoute>()
                            val tryOnStyles by stylesViewModel.state.collectAsState()
                            com.librelookai.tryon.TryOnDetailDestination(
                                initialImageDriveId = route.imageDriveId,
                                tryOnViewModel = tryOnViewModel,
                                historyViewModel = tryOnHistoryViewModel,
                                itemPool = rememberTryOnItemPool(wardrobeViewModel, shoppingClosetViewModel),
                                outfits = tryOnStyles.outfits,
                                onOpenSourceOutfit = { outfit ->
                                    // Leave the try-on surfaces, jump to Outfits, and ask the
                                    // list to scroll the picked outfit into view + highlight.
                                    stylesViewModel.requestScrollToOutfit(outfit.id)
                                    goToTab(0)
                                    navResetTick++
                                },
                                onOpenComposer = {
                                    navController.navigate(TryOnRoute) { launchSingleTop = true }
                                },
                                onOpenItemViewer = { itemIds, initialItemId ->
                                    navController.navigate(
                                        ItemViewerRoute(
                                            source = ItemViewerRoute.SOURCE_TRYON,
                                            itemIds = itemIds,
                                            initialItemId = initialItemId,
                                        ),
                                    ) { launchSingleTop = true }
                                },
                                onClose = { navController.popBackStack() },
                            )
                        }

                        composable<OutfitComposerRoute> {
                            // Real navigation (§ 5 slice 9): openers seed the generation VM's
                            // draft then navigate here; every close path (X / system back via
                            // the discard-confirm / save success) clears the draft and pops
                            // through onClose. No open-flag mirror any more. No activity pin:
                            // the composer takes every VM as a required parameter.
                            val composerWardrobeState by wardrobeViewModel.state.collectAsState()
                            val composerShoppingState by shoppingClosetViewModel.state.collectAsState()
                            OutfitComposerScreen(
                                generationViewModel = outfitGenerationViewModel,
                                onClose = {
                                    navController.popBackStack(OutfitComposerRoute, inclusive = true)
                                },
                                weatherViewModel  = weatherViewModel,
                                outfitEventsViewModel = outfitEventsViewModel,
                                wardrobeImages = composerWardrobeState.images,
                                allLocationImages = composerWardrobeState.allLocationImages,
                                shoppingItems = composerShoppingState.items,
                                preferences = profileState.preferences,
                                locations = locationState.locations,
                                onTextFilter = wardrobeViewModel::fuzzyFilterByText,
                                findSimilarByPhoto = { file, candidates ->
                                    wardrobeViewModel.findSimilarInCandidates(file, candidates)
                                        .associate { it.driveId to it.score }
                                },
                                onOpenItemViewer = { initialItemId ->
                                    navController.navigate(
                                        ItemViewerRoute(
                                            source = ItemViewerRoute.SOURCE_COMPOSER,
                                            initialItemId = initialItemId,
                                        ),
                                    ) { launchSingleTop = true }
                                },
                            )
                        }

                        composable<ItemViewerRoute> { entry ->
                            val route = entry.toRoute<ItemViewerRoute>()
                            LaunchedEffect(Unit) { Analytics.screen("ItemViewer") }
                            // Destination-scoped wardrobe / shopping / outfits / try-on-history
                            // VMs (§ 5 slice 9): resolved against this back-stack entry. Safe
                            // to fork since the slice-9 load-core extractions — all four mirror
                            // repo-derived data (WardrobeRepository / ShoppingRepository /
                            // OutfitsRepository / TryOnRepository), their init pre-warms are
                            // once-per-process on the repos, and the shopping upload worker is
                            // the ShoppingIngestionQueue singleton. The generation VM stays
                            // activity-scoped (the composer source resolves items from its
                            // shared slots; composer seeds must survive the pop), and the
                            // location VM publishes the closet session. No activity pin: every
                            // VM in this subtree is a required parameter, passed explicitly.
                            val viewerWardrobeViewModel: com.librelookai.wardrobe.WardrobeViewModel =
                                androidx.hilt.navigation.compose.hiltViewModel(entry)
                            val viewerShoppingViewModel: com.librelookai.shopping.ShoppingClosetViewModel =
                                androidx.hilt.navigation.compose.hiltViewModel(entry)
                            val viewerOutfitsViewModel: OutfitsViewModel =
                                androidx.hilt.navigation.compose.hiltViewModel(entry)
                            val viewerTryOnHistoryViewModel: com.librelookai.tryon.TryOnHistoryViewModel =
                                androidx.hilt.navigation.compose.hiltViewModel(entry)
                            // Full-bleed, no Scaffold: the viewer is immersive (edge-to-edge,
                            // like its Dialog predecessor) and handles its own insets.
                            ItemViewerDestination(
                                source = route.source,
                                routeItemIds = route.itemIds,
                                initialItemId = route.initialItemId,
                                wardrobeViewModel = viewerWardrobeViewModel,
                                shoppingClosetViewModel = viewerShoppingViewModel,
                                outfitsViewModel = viewerOutfitsViewModel,
                                generationViewModel = outfitGenerationViewModel,
                                tryOnHistoryViewModel = viewerTryOnHistoryViewModel,
                                locationViewModel = locationViewModel,
                                onCreateOutfitFromSelection = { itemIds ->
                                    when (route.source) {
                                        // Same wiring as the wardrobe / shopping selection
                                        // bars; try-on & composer sources keep their old
                                        // no-op (the hosts never offered this path).
                                        ItemViewerRoute.SOURCE_WARDROBE,
                                        ItemViewerRoute.SOURCE_SHOPPING,
                                        -> {
                                            Analytics.action("ItemViewer", "create_outfit_from_item")
                                            outfitGenerationViewModel.openComposer(
                                                seedItemIds = itemIds,
                                                images      = viewerWardrobeViewModel.state.value.images +
                                                    viewerShoppingViewModel.state.value.items,
                                                prefs       = profileViewModel.state.value.preferences,
                                                defaultSourceFolderId = locationViewModel.activeFolderId,
                                            )
                                            navController.navigate(OutfitComposerRoute) { launchSingleTop = true }
                                        }
                                        else -> {}
                                    }
                                },
                                onClose = { navController.popBackStack() },
                            )
                        }
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
                                            navController.navigate(TryOnRoute) { launchSingleTop = true }
                                        },
                                        onPickWardrobe = {
                                            Analytics.action("TryOn/QuickSheet", "pick_source", mapOf("source" to "wardrobe"))
                                            showQuickTryOnSheet = false
                                            tryOnViewModel.openComposer(
                                                emptySet(), null,
                                                com.librelookai.tryon.TryOnSourceKind.WARDROBE,
                                                autoPick = true,
                                            )
                                            navController.navigate(TryOnRoute) { launchSingleTop = true }
                                        },
                                        onPickShopping = {
                                            Analytics.action("TryOn/QuickSheet", "pick_source", mapOf("source" to "shopping"))
                                            showQuickTryOnSheet = false
                                            tryOnViewModel.openComposer(
                                                emptySet(), null,
                                                com.librelookai.tryon.TryOnSourceKind.SHOPPING,
                                                autoPick = true,
                                            )
                                            navController.navigate(TryOnRoute) { launchSingleTop = true }
                                        },
                                        onPickTravel = {
                                            Analytics.action("TryOn/QuickSheet", "pick_source", mapOf("source" to "travel"))
                                            showQuickTryOnSheet = false
                                            showTripTryOnPicker = true
                                        },
                                        onSeeHistory = {
                                            Analytics.action("TryOn/QuickSheet", "pick_source", mapOf("source" to "history"))
                                            showQuickTryOnSheet = false
                                            navController.navigate(TryOnHistoryRoute) { launchSingleTop = true }
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
                                            navController.navigate(TryOnRoute) { launchSingleTop = true }
                                        },
                                        onDismiss = { showTripTryOnPicker = false },
                                    )
                                }

                                // Prediction-setup dialog — globally hosted so it appears
                                // regardless of which tab is on top (the composer can be opened
                                // from Wardrobe and ask for AI suggestions before the user has
                                // ever visited the Outfits tab).
                                val predictionWardrobeState by wardrobeViewModel.state.collectAsState()
                                com.librelookai.outfit.PredictionSetupDialog(
                                    generationViewModel = outfitGenerationViewModel,
                                    weatherViewModel  = weatherViewModel,
                                    wardrobeImages = predictionWardrobeState.images,
                                    allLocationImages = predictionWardrobeState.allLocationImages,
                                    preferences = profileState.preferences,
                                    locations = locationState.locations,
                                )

                                // Replacements result dialog — opened from Wardrobe selection FAB.
                                ReplacementsResultDialog(gapViewModel = gapViewModel)

                                // Global insufficient-credits handler — listens for 402s emitted
                                // by GeminiRepository.creditsIf402 and shows the dialog regardless
                                // of which ViewModel triggered the call. "Buy" jumps to Settings.
                                var topUpEvent by remember {
                                    mutableStateOf<com.librelookai.billing.InsufficientCreditsException?>(null)
                                }
                                LaunchedEffect(Unit) {
                                    creditsEvents.topUp.collect { topUpEvent = it }
                                }
                                topUpEvent?.let { ex ->
                                    com.librelookai.billing.InsufficientCreditsDialog(
                                        needed = ex.needed,
                                        have = ex.have,
                                        onBuy = {
                                            topUpEvent = null
                                            goToTab(5) // Settings → Credits tab
                                        },
                                        onDismiss = { topUpEvent = null },
                                    )
                                }

                                // Global AI-notice handler — listens for problems emitted by the
                                // GeminiRepository for user-initiated calls. NOT_CONFIGURED offers to
                                // set up a Gemini key (→ Settings ▸ Advanced); FAILED shows the reason
                                // (quota, invalid key, blocked, server…) and, when the originating
                                // action registered one (AiRetry), a one-tap retry.
                                var aiNotice by remember {
                                    mutableStateOf<com.librelookai.gemini.AiNotice?>(null)
                                }
                                var aiRetryAction by remember { mutableStateOf<(() -> Unit)?>(null) }
                                LaunchedEffect(Unit) {
                                    aiEvents.notices.collect { notice ->
                                        aiRetryAction =
                                            if (notice.canRetry) aiRetry.action else null
                                        aiNotice = notice
                                    }
                                }
                                aiNotice?.let { notice ->
                                    val isNotConfigured =
                                        notice.kind == com.librelookai.gemini.AiNoticeKind.NOT_CONFIGURED
                                    val retry = aiRetryAction
                                    AlertDialog(
                                        onDismissRequest = { aiNotice = null },
                                        title = {
                                            Text(stringResource(
                                                if (isNotConfigured) R.string.ai_no_key_title
                                                else R.string.ai_error_title,
                                            ))
                                        },
                                        text = { Text(stringResource(aiErrorMessageRes(notice.reason))) },
                                        confirmButton = {
                                            when {
                                                isNotConfigured -> TextButton(onClick = {
                                                    aiNotice = null
                                                    // Clear both composers' drafts; goToTab pops the routes.
                                                    tryOnViewModel.close()
                                                    outfitGenerationViewModel.closeComposer()
                                                    goToTab(5) // Settings (BYOK key lives in Advanced)
                                                }) { Text(stringResource(R.string.ai_set_up_key)) }
                                                retry != null -> TextButton(onClick = {
                                                    aiNotice = null
                                                    retry()
                                                }) { Text(stringResource(R.string.ai_retry)) }
                                                else -> TextButton(onClick = { aiNotice = null }) {
                                                    Text(stringResource(R.string.ai_dismiss))
                                                }
                                            }
                                        },
                                        dismissButton = if (isNotConfigured || retry != null) {
                                            {
                                                TextButton(onClick = { aiNotice = null }) {
                                                    Text(stringResource(R.string.ai_dismiss))
                                                }
                                            }
                                        } else null,
                                    )
                                }

                                // Cutout-background fix confirmation — globally hosted so it
                                // appears whether the scan was started from Wardrobe header or
                                // Settings → Data.
                                val cutoutBgFix = wardrobeViewModel.cutoutBgFixProgress.collectAsState().value
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

                        // Restore-progress overlay — for returning accounts (reinstall / new
                        // device) the wardrobe, outfits, shopping and trips all reload from Google
                        // Drive the moment the app remounts after onboarding. Surface one
                        // consolidated centered progress card until that initial restore settles,
                        // instead of leaving the user staring at empty screens. It only appears once
                        // real loading activity is observed, so brand-new (empty) accounts never see
                        // it; once settled it never returns (per-process latch).
                        //
                        // Gate on `isLoading`, NOT `isSyncing`: every two-phase loader paints from
                        // the local cache (Phase 1) and clears `isLoading` immediately when that
                        // cache is non-empty, leaving `isLoading` true through the Drive sync only
                        // when there was nothing cached — i.e. a genuine first restore. `isSyncing`,
                        // by contrast, is true on EVERY launch (the routine Phase-2 Drive reconcile
                        // runs even with a warm cache), so counting it would pop the overlay on every
                        // cold start. The 700 ms debounce below absorbs the brief Phase-1 disk-read
                        // blip that warm starts still show.
                        val restoreWardrobe by wardrobeViewModel.state.collectAsState()
                        val restoreOutfits by stylesViewModel.state.collectAsState()
                        val restoreTrips by tripsViewModel.state.collectAsState()
                        var restoreSettled by rememberSaveable { mutableStateOf(false) }
                        var restoreStarted by remember { mutableStateOf(false) }
                        if (!restoreSettled) {
                            LaunchedEffect(Unit) {
                                val anyLoading = {
                                    restoreWardrobe.isLoading ||
                                        restoreOutfits.isLoading ||
                                        shoppingClosetState.isLoading ||
                                        restoreTrips.isLoading
                                }
                                // Wait briefly for the initial loads to kick off; if nothing actually
                                // restores within the window, stay hidden and never show again.
                                val saw = withTimeoutOrNull(5000) {
                                    snapshotFlow(anyLoading).first { it }
                                }
                                // Debounce: a brand-new (empty) account's loads finish almost
                                // instantly. Only surface the card if the restore is still in
                                // progress shortly after — i.e. there's a real amount to bring back.
                                if (saw == true) {
                                    delay(700)
                                    if (anyLoading()) {
                                        restoreStarted = true
                                        snapshotFlow(anyLoading).first { !it }
                                    }
                                }
                                restoreSettled = true
                            }
                        }
                        // Release the launch splash once the initial restore has settled, so a
                        // general cold start stays under the splash until the app is actually ready
                        // (no flash of the restore card). A long reinstall-restore is released by
                        // MainActivity's max-duration cap instead, revealing the progress card.
                        LaunchedEffect(restoreSettled) {
                            if (restoreSettled) StartupGate.contentReady = true
                        }
                        if (restoreStarted && !restoreSettled) {
                            RestoreProgressOverlay(
                                categories = listOf(
                                    RestoreCategory(
                                        label = stringResource(DsR.string.nav_wardrobe),
                                        loading = restoreWardrobe.isLoading,
                                        // Name the sub-step so a fresh restore doesn't appear to hang
                                        // at "196/196" while sidecars download and caches are written.
                                        detail = when (restoreWardrobe.syncPhase) {
                                            com.librelookai.wardrobe.WardrobeSyncPhase.DOWNLOADING ->
                                                if (restoreWardrobe.syncTotal > 0) stringResource(
                                                    R.string.restore_items_progress,
                                                    restoreWardrobe.syncDone, restoreWardrobe.syncTotal,
                                                ) else null
                                            com.librelookai.wardrobe.WardrobeSyncPhase.DETAILS ->
                                                if (restoreWardrobe.syncTotal > 0) stringResource(
                                                    R.string.restore_details_progress,
                                                    restoreWardrobe.syncDone, restoreWardrobe.syncTotal,
                                                ) else stringResource(R.string.restore_finishing)
                                            com.librelookai.wardrobe.WardrobeSyncPhase.FINISHING ->
                                                stringResource(R.string.restore_finishing)
                                            com.librelookai.wardrobe.WardrobeSyncPhase.NONE -> null
                                        },
                                    ),
                                    RestoreCategory(
                                        label = stringResource(DsR.string.nav_styles),
                                        loading = restoreOutfits.isLoading,
                                    ),
                                    RestoreCategory(
                                        label = stringResource(R.string.nav_shopping),
                                        loading = shoppingClosetState.isLoading,
                                    ),
                                    RestoreCategory(
                                        label = stringResource(R.string.nav_travel),
                                        loading = restoreTrips.isLoading,
                                    ),
                                ),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        } // Box
                        }
                    } }
                    } // else -> arm (signed in + onboarding complete)
                } // when
    }
}

/**
 * Locale-overridden context for the `LocalContext` provides. Must stay a [ContextWrapper] whose
 * base chain reaches the activity: `hiltViewModel(entry)` (the destination-scoped VMs) and the
 * activity-result/back-dispatcher lookups all unwrap `LocalContext` to find the activity, and the
 * raw `ContextImpl` returned by `createConfigurationContext` breaks that chain (crash on any
 * viewer/trip destination). Only resources resolve through the localized config context.
 */
private fun Context.localizedWrapper(language: String): Context {
    val config = Configuration(resources.configuration)
    config.setLocale(AppLanguage.toLocale(language))
    val localized = createConfigurationContext(config)
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = localized.resources
    }
}

/**
 * Maps the AI layer's semantic [com.librelookai.gemini.AiErrorReason] to the localized message
 * shown by the global AI-notice dialog. Lives app-side so the gemini package stays free of `R`.
 */
@androidx.annotation.StringRes
private fun aiErrorMessageRes(reason: com.librelookai.gemini.AiErrorReason): Int = when (reason) {
    com.librelookai.gemini.AiErrorReason.NOT_CONFIGURED -> R.string.ai_no_key_message
    com.librelookai.gemini.AiErrorReason.UNAVAILABLE -> R.string.ai_unavailable
    com.librelookai.gemini.AiErrorReason.QUOTA -> R.string.ai_error_quota
    com.librelookai.gemini.AiErrorReason.KEY_INVALID -> R.string.ai_error_key_invalid
    com.librelookai.gemini.AiErrorReason.PERMISSION -> R.string.ai_error_permission
    com.librelookai.gemini.AiErrorReason.BLOCKED -> R.string.ai_error_blocked
    com.librelookai.gemini.AiErrorReason.SERVER -> R.string.ai_error_server
    com.librelookai.gemini.AiErrorReason.GENERIC -> R.string.ai_error_generic
}

/** The wardrobe + shopping item pool the try-on destinations resolve ids/names against — the
 *  try-on FAB is reachable from both tabs, so lookups must succeed regardless of source.
 *  Composed shell-side (§ 1 slice 6) so the try-on feature takes plain data, not the VMs. */
@Composable
private fun rememberTryOnItemPool(
    wardrobeViewModel: WardrobeViewModel,
    shoppingClosetViewModel: ShoppingClosetViewModel,
): List<com.librelookai.wardrobe.DriveImage> {
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val shoppingClosetState by shoppingClosetViewModel.state.collectAsState()
    return remember(wardrobeState.images, shoppingClosetState.items) {
        wardrobeState.images + shoppingClosetState.items
    }
}
