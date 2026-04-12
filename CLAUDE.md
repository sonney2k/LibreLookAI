# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build commands

```bash
# Assemble debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.librelookai.ExampleUnitTest"

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Lint
./gradlew lint

# Clean
./gradlew clean
```

Firebase Cloud Functions (in `firebase/`):
```bash
cd firebase/functions && npm install
cd firebase && firebase deploy --only functions
cd firebase && firebase deploy --only firestore:rules
```

## Required local.properties keys

`local.properties` is never committed. Keys consumed at build time via `BuildConfig`:

```
gemini.api.key=          # default BYOK key (users can override in Settings)
amazon.affiliate.tag=    # e.g. yourstore-20
shopstyle.publisher.id=  # e.g. uid2500-XXXXX-XX
firebase.proxy.url=      # e.g. https://us-central1-PROJECT.cloudfunctions.net
firebase.web.client.id=  # OAuth 2.0 Web Client ID from Firebase Auth
```

Firebase is **opt-in**: `google-services.json` must be present in `app/` for the plugin to be applied (checked in `app/build.gradle.kts`).

## Architecture overview

### Data flow

```
UI Screen (Compose)
    ↕ StateFlow / collectAsState
AndroidViewModel  (e.g. WardrobeViewModel, StylesViewModel)
    ↕ suspend functions
Repository layer
    ├── DriveRepository   — Google Drive REST API (images + JSON metadata files)
    ├── GeminiRepository  — Gemini AI (vision + text)
    ├── WeatherRepository — Open-Meteo API (no key required)
    ├── BillingRepository — Google Play Billing Library 7.x
    └── CreditRepository  — Firestore balance + Firebase Cloud Functions purchase verification
```

All ViewModels extend `AndroidViewModel` and receive `Application` for context. Repositories are instantiated directly inside ViewModels (no DI framework).

### Storage strategy

- **Images**: Uploaded to the user's own Google Drive via `drive.file` scope only (app-private folder `LibreLookAI/`). Cached locally under `context.filesDir/wardrobe/`.
- **Per-item sidecar metadata**: Each wardrobe item has its own `{cutoutDriveId}.json` sidecar stored beside the cutout in the same Drive folder. The sidecar holds `ClothingTags` and `originalDriveId`. Five system JSON files are excluded from sidecar handling: `_wardrobe_metadata.json`, `_styles_metadata.json`, `_outfits_metadata.json`, `_user_preferences.json`, `_locations.json`.
- **File naming convention**: cutout = `{cutoutDriveId}_cutout.png`, original = `{cutoutDriveId}_original.jpg`, sidecar = `{cutoutDriveId}.json`. The Drive-assigned ID of the cutout file is the shared prefix for all three.
- **Legacy fallback**: `_wardrobe_metadata.json` is read as a fallback when no sidecars exist yet; items are migrated to sidecars fire-and-forget on first load.
- **Two-phase loading**: Phase 1 shows the local disk cache instantly (zero network). Phase 2 fetches cutout files + sidecar files from Drive in parallel, then downloads any uncached images and reads sidecar content, also in parallel.
- **Local disk cache**: `wardrobe_cache_{folderId}.json` — a `LocalCache` snapshot of all `DriveImage` entries, rebuilt on every successful Phase 2 sync.
- **Multi-location**: Each "location" corresponds to a Drive subfolder. `LocationViewModel` tracks the active location; `WardrobeViewModel.setLocation(folderId)` switches context. `LocationViewModel.getOrCreateLocation(name, onResult)` finds an existing location by name (case-insensitive) or creates one, returning its folderId via callback.
- **Local prefs**: `ApiKeyStore` (SharedPreferences) for user-supplied Gemini key only.

### Gemini routing

`GeminiRepository.buildRequest()` chooses the call path:
1. **BYOK**: User-entered key (`ApiKeyStore`) or `BuildConfig.GEMINI_API_KEY` → direct `generativelanguage.googleapis.com` call with `?key=`.
2. **Managed/proxy**: No local key + `PROXY_BASE_URL` set → POST to Firebase Cloud Function `geminiProxy` with `Authorization: Bearer <Firebase ID token>` + `X-AI-Action` + `X-Gemini-Model` headers. The function deducts credits from Firestore, calls Gemini with the server-side secret, and refunds on error.

All Gemini calls return `null` on failure; callers must gracefully degrade.

### Credit model

- `CreditPack.kt` — SKU constants (`credits_100/500/2000`) and per-action costs.
- `BillingRepository.kt` — Play Billing wrapper; exposes `purchaseUpdates: SharedFlow`.
- `CreditRepository.kt` — Firestore balance as `Flow<Int>` via `callbackFlow`; `verifyPurchase()` calls the Firebase Cloud Function.
- `CreditsViewModel.kt` — orchestrates billing + credits; processes pending purchases on resume.
- `BuyCreditsScreen.kt` — shown as the Credits tab in Settings when `isManagedMode = true`.

### Styles screen

`StylesScreen` branches on three mutually exclusive states (checked in order):

1. `stylesState.isEditingStyleView` → **`StyleEditingView`** (full-screen, see below)
2. `stylesState.isCreating` → **`StyleItemPicker`** (full-screen grid for manual creation from scratch)
3. otherwise → **`StyleListScreen`** (the list with cards and FABs)

**`StyleEditingView`** is the unified editor used for:
- Editing an existing saved style (`startEditing(style)` sets `isEditingStyleView = true`)
- Reviewing / tweaking an AI-predicted existing style (auto-opened via `LaunchedEffect` when `prediction` arrives)
- Reviewing / tweaking an AI-composed new outfit (auto-opened via `LaunchedEffect` when `newSuggestion` arrives)

It shows: editable name + description, outfit items as 100 dp tappable tiles in a `FlowRow`, an "Add item" `+` tile, and — when opened from a Gemini result — the AI reason text and a `RefinementSection`. Tapping a tile opens **`ItemSwapSheet`**, a `ModalBottomSheet` that filters the wardrobe by the item's category and supports single-selection replacement. The sheet has a "Suggest 10 alternatives" button that calls `StylesViewModel.suggestAlternatives()` and surfaces results as starred tiles sorted to the top. After saving, `pendingWearStyleId` is set and a Snackbar offers "Wear today".

`StyleListScreen` supports **multi-select**: long-press any card → enters selection mode (`selectedStyleIds.isNotEmpty()`). In selection mode: tapping toggles selection, back exits, a selection bar (count / select-all / deselect-all) replaces the sort button, card Edit+Wear buttons hide, and the speed-dial FAB is replaced by two action FABs:
- **Delete** — confirmation dialog → `deleteSelectedStyles()`
- **Combine with AI** (≥ 2 selected) — `combineSelectedStyles()` calls `buildCombinePrompt()`, result surfaces as `newSuggestion` and auto-opens `StyleEditingView`

**Styles ViewModel key state** (`StylesUiState`):
- `isEditingStyleView` / `isCreating` — which full-screen view is open
- `draftItemIds / draftStyleName / draftStyleDescription / editingStyle` — shared draft for both views
- `prediction` / `newSuggestion` — AI results; `LaunchedEffect` in `StylesScreen` opens `StyleEditingView` when either arrives
- `pendingWearStyleId` — set after save; cleared by "Wear today" Snackbar action or dismiss
- `selectedStyleIds` — multi-select set for `StyleListScreen`
- `isLoadingAlternatives / alternativeIds` — per-item swap alternatives from Gemini

**Gemini prompt builders** (all private top-level functions in `StylesViewModel.kt`):
- `buildPredictionPrompt` — pick best existing style for today
- `buildCompositionPrompt` — compose a brand-new outfit from the full wardrobe
- `buildAlternativesPrompt` — suggest up to 10 swap alternatives for one item given the rest of the style as context
- `buildCombinePrompt` — merge N selected styles into one cohesive outfit by choosing the best items from across them

**`StylesViewModel.saveStyleDirectly(name, description, itemIds, onDone)`** saves a style without the draft editing flow. Used by `TravelScreen` to persist packing outfits directly as styles.

### Travel packing screen

`TravelScreen` receives `travelViewModel`, `wardrobeViewModel`, `profileViewModel`, `stylesViewModel`, and `locationViewModel`.

**Packing list generation**: destination + date range → `WeatherRepository.fetchDestinationForecast()` → `buildPackingPrompt()` → Gemini → `PackingList` (list of `PackingOutfit` + `extraItems`). A refinement loop (free-text + preset chips) re-runs `buildPackingPrompt()` with accumulated `feedbackHistory`.

**`PackingOutfit` cards** each show a "Save as style" `InputChip`. Tapping it calls `stylesViewModel.saveStyleDirectly()` and flips the chip to "Saved ✓" (local composable state; not persisted across recompositions).

**"Move all to Travel location" button** appears in the packing list header when there are packed items:
1. Calls `locationViewModel.getOrCreateLocation("Travel")` — finds an existing location named "Travel" (case-insensitive) or creates a new Drive subfolder + updates the locations JSON, then returns the folderId via callback.
2. Calls `wardrobeViewModel.moveItemsToFolder(itemIds, toFolderId)` — moves each item's cutout + original + sidecar files via `DriveRepository.moveFile()` (single PATCH, no re-upload), then drops the moved items from in-memory state.
3. A Snackbar confirms success or failure.

### Navigation

`MainActivity` owns a single `selectedTab: Int` integer. There is no Navigation component — each tab renders its Screen composable directly inside a `when` block. All ViewModels are created once at the `MainActivity` level and passed down as parameters.

`SettingsScreen` internally uses a `TabRow` with three tabs: Profile, Data, Credits (Credits only visible in managed mode).

### Localisation

`AppLanguage.toLocale()` / `AppLanguage.toGeminiName()` convert the stored enum to a `Locale` and a Gemini-friendly language name respectively. The active locale is applied by wrapping the composable tree in a `CompositionLocalProvider(LocalContext provides localizedContext)` — there is no Activity restart on language change.

String resources live in `values/strings.xml` and `values-de/strings.xml`. Add new strings to both files.

### UI shell

All six main screens use `AppScreenHeader` (defined in `MainActivity.kt`) for a consistent top bar: leading icon (optional), `titleMedium/SemiBold` title, trailing slot (optional, e.g. sort button), followed by a `HorizontalDivider`.

### Photo upload flow

1. `WardrobeViewModel.uploadPhoto(rawFile)` uploads the raw JPEG to Drive and enqueues a `PendingJob`.
2. `processQueue()` drains the queue serially: bg removal via Gemini → upload cutout (renamed to `{id}_cutout.png`) → copy local original cache → upload original (renamed to `{cutoutId}_original.jpg`) → delete raw → classify tags → write sidecar.
3. The local `{driveId}_original.jpg` cache copy must happen **before** `deleteFile()` is called, because `DriveRepository.deleteFile()` also deletes the local `_original.jpg` file.
4. State is updated by matching on **either** the raw Drive ID or the cutout Drive ID to handle the race where `loadImages()` may have already placed the item with the cutout ID.

### Background job protection

Long-running wardrobe operations are protected against process death and CPU sleep by two mechanisms managed together in `WardrobeViewModel`:

- **`JobForegroundService`**: started when the first job begins, stopped when the last one ends. Promotes the app to foreground-service priority so Android will not kill the process. Shows a persistent notification (channel `librelookai_jobs`, low importance) while active. Declared with `android:stopWithTask="false"` so it survives the user swiping the app away from recents. Returns `START_STICKY` so Android restarts it if killed under memory pressure.
- **`PARTIAL_WAKE_LOCK`** (`LibreLookAI:Jobs`): keeps the CPU running if the screen turns off mid-job. 30-minute safety timeout.
- **Battery optimization exemption**: on first job start, `acquireJobWakeLock()` checks `PowerManager.isIgnoringBatteryOptimizations()`. If the app is not exempt, `WardrobeUiState.needsBatteryExemption` is set to `true` and `GridContent` shows a one-shot `AlertDialog` prompting the user to open the system battery optimization settings (`Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). This is required on OEM-customized ROMs (Samsung, Xiaomi, etc.) that aggressively kill even foreground services. The prompt is dismissed via `WardrobeViewModel.dismissBatteryExemptionWarning()`.

Both service and wake lock are reference-counted via `acquireJobWakeLock()` / `releaseJobWakeLock()` (using `AtomicInteger`). Covered operations: `processQueue`, `importFromFolder`, `importFromDriveFolder`, `removeAllBackgrounds`, `retagAll`, and both phases of Repair & Sync. Every caller wraps its coroutine body in `try/finally` to guarantee release.

### Repair & Sync

`WardrobeViewModel.startRepairAndRefresh(folderIds)` runs a multi-phase audit across all location folders. A foreground service + `PARTIAL_WAKE_LOCK` are held throughout (via `acquireJobWakeLock`) so the job survives screen-off. All actions are logged to logcat under the tag `RepairAndSync`.

1. **Scan**: `DriveRepository.listAllImageFiles()` returns every image (originals, cutouts, raws). Cutouts with wrong names are renamed in-place. Originals whose prefix does not match any cutout's Drive ID are flagged as orphaned. For each cutout that has a sidecar, the sidecar content is downloaded and parsed — if `tags` is null (i.e. the file is `{}` or `{"tags":null}`), the cutout is flagged for re-tagging just like a missing sidecar.
2. **Confirmation**: `WardrobeUiState.auditProgress` enters `awaitingConfirmation`; the UI shows findings and asks the user whether to process.
3. **Process** (`continueRepairProcessing(true)`): orphaned originals get full AI processing (bg removal + tagging + sidecar upload); cutouts missing a sidecar or with empty tags get tagging (from the cutout image) + sidecar upsert.
4. **Refresh**: all local caches are cleared and `loadImages()` reloads from Drive. `auditProgress.isDone` signals completion.

### SAF import

`WardrobeViewModel.importFromFolder(treeUri)` reads images from any OS-accessible folder using `DocumentsContract` + `ContentResolver` (no extra OAuth scopes needed), re-uploads them to the app's Drive folder, and classifies new items with Gemini before writing per-item sidecars.
