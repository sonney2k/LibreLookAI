# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Working agreement

- **Update CLAUDE.md before executing a plan.** When the user agrees on a plan that changes architecture, data flow, file layout, build inputs, or any of the conventions documented below, edit the relevant section of this file *first* — before writing the code. The doc is the source of truth; code follows.
- **Update CLAUDE.md once done.** After finishing the work, do another pass and capture anything new that future-Claude would need to know: new components, new model assets, renamed concepts, new entry points, behavior changes. Remove or rewrite stale sections rather than letting them rot.
- **Ask for a git commit at the end.** Once the work and the doc updates are both done, explicitly ask the user whether to commit (and, if yes, draft the commit). Do not commit without confirmation.

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

Build release AAB (requires signing config in `local.properties`, see Release process below):
```bash
./gradlew bundleRelease
# Output: app/build/outputs/bundle/release/app-release.aab
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

Signing keys (for release builds, never committed):
```
signing.store.file=/absolute/path/to/librelookai-release.jks
signing.store.password=
signing.key.alias=librelookai
signing.key.password=
```

## Architecture overview

### Data flow

```
UI Screen (Compose)
    ↕ StateFlow / collectAsState
AndroidViewModel  (e.g. WardrobeViewModel, OutfitsViewModel, OutfitEventsViewModel)
    ↕ suspend functions
Repository layer
    ├── DriveRepository   — Google Drive REST API (images + JSON metadata files)
    ├── GeminiRepository  — Gemini AI (vision + text)
    ├── WeatherRepository — Open-Meteo API (no key required)
    ├── BillingRepository — Google Play Billing Library 7.x
    └── CreditRepository  — Firestore balance + Firebase Cloud Functions purchase verification
```

Note: `OutfitsViewModel` handles saved outfits (the list + composer + editor). `OutfitEventsViewModel` is a separate VM that handles calendar wear events (`OutfitEvent` — a timestamped record of which outfit was worn on which date). Both persist per-location JSON files to Drive.

All ViewModels extend `AndroidViewModel` and receive `Application` for context. Repositories are instantiated directly inside ViewModels (no DI framework).

### Storage strategy

- **Images**: Uploaded to the user's own Google Drive via `drive.file` scope only (app-private folder `LibreLookAI/`). Cached locally under `context.filesDir/wardrobe/`.
- **Per-item sidecar metadata**: Each wardrobe item has its own `{cutoutDriveId}.json` sidecar stored beside the cutout in the same Drive folder. The sidecar holds `ClothingTags` and `originalDriveId`. System JSON files excluded from sidecar handling (set `SYSTEM_JSON_NAMES` in `DriveRepository`): `_wardrobe_metadata.json`, `_outfits.json`, `_outfit_events.json`, `_user_preferences.json`, `_locations.json`, plus two read-only legacy names kept for migration fallback — `_styles_metadata.json` (old name for `_outfits.json`) and `_outfits_metadata.json` (old name for `_outfit_events.json`). `DriveRepository.loadOutfitsJson()` / `loadOutfitEventsJson()` read the current filename first and fall back to the legacy one; writes always use the current name.
- **File naming convention**: cutout = `{cutoutDriveId}_cutout.png`, original = `{cutoutDriveId}_original.jpg`, sidecar = `{cutoutDriveId}.json`. The Drive-assigned ID of the cutout file is the shared prefix for all three.
- **Legacy fallback**: `_wardrobe_metadata.json` is read as a fallback when no sidecars exist yet; items are migrated to sidecars fire-and-forget on first load.
- **Two-phase loading**: Phase 1 shows the local disk cache instantly (zero network). Phase 2 fetches cutout files + sidecar files from Drive in parallel, then downloads any uncached images and reads sidecar content, also in parallel.
- **Local disk cache**: `wardrobe_cache_{folderId}.json` — a `LocalCache` snapshot of all `DriveImage` entries, rebuilt on every successful Phase 2 sync.
- **Closets (multi-location)**: Each "closet" corresponds to a Drive subfolder. The UI labels them "Closets" (not "Locations") to separate the organizational concept from geographic locations. Each `Location` data class has a `geoLocation: String` field for an optional city/place name used for weather. `LocationViewModel` tracks the active closet (persisted in SharedPreferences as the Drive `folderId`); `activeLocationId` in `LocationUiState` stores the `folderId` of the selected closet (or `ALL_LOCATIONS_ID`). All location matching throughout the codebase uses `folderId`, never `Location.id` (which is an ephemeral UUID). A `LaunchedEffect` in `MainActivity` reacts to changes and calls `setLocation(folderId)` / `setAllLocations(folderIds)` on `WardrobeViewModel`, `OutfitsViewModel`, and `OutfitEventsViewModel` to reload data. `OutfitsViewModel` also has an independent `updateSaveFolder(folderId)` that controls where newly-saved outfits are written (single-location mode uses the active folder; All Locations mode uses the first configured folder). When `ALL_LOCATIONS_ID` is active, all three ViewModels load and merge data from all folders; the "All" option is only available in the global `LocationButton` header dropdown (not in Settings). `LocationViewModel.getOrCreateLocation(name, onResult)` finds an existing location by name (case-insensitive) or creates one, returning its folderId via callback.
- **Local prefs**: `ApiKeyStore` (SharedPreferences) for user-supplied Gemini key. `ProfileViewModel` caches the UI language in a separate `librelookai_lang` SharedPreferences so the correct locale is available synchronously on app start (before Drive preferences load).

### Gemini routing

`GeminiRepository.buildRequest()` chooses the call path:
1. **BYOK**: User-entered key (`ApiKeyStore`) or `BuildConfig.GEMINI_API_KEY` → direct `generativelanguage.googleapis.com` call with `?key=`.
2. **Managed/proxy**: No local key + `PROXY_BASE_URL` set → POST to Firebase Cloud Function `geminiProxy` with `Authorization: Bearer <Firebase ID token>` + `X-AI-Action` + `X-Gemini-Model` headers. The function deducts credits from Firestore, calls Gemini with the server-side secret, and refunds on error.

All Gemini calls return `null` on failure; callers must gracefully degrade.

**Image resizing**: `GeminiRepository.readAndResizeBase64()` ensures every image sent to Gemini has `max(width, height) ≤ 1280`. Images already within bounds are sent as raw bytes (no re-encode); larger ones are decoded, scaled proportionally, and re-encoded (JPEG 95 for photos, PNG lossless for cutouts). `CaptureScreen` also crops captured photos to match the camera viewfinder aspect ratio and resizes to ≤ 1280 px before saving.

### Credit model

- `CreditPack.kt` — SKU constants (`credits_100/500/2000`) and per-action costs.
- `BillingRepository.kt` — Play Billing wrapper; exposes `purchaseUpdates: SharedFlow`.
- `CreditRepository.kt` — Firestore balance as `Flow<Int>` via `callbackFlow`; `verifyPurchase()` calls the Firebase Cloud Function.
- `CreditsViewModel.kt` — orchestrates billing + credits; processes pending purchases on resume.
- `BuyCreditsScreen.kt` — shown as the Credits tab in Settings when `isManagedMode = true`.

### Outfits screen

**Naming note**: the user-facing term and all new data classes / VMs use "outfit". A few identifiers still read "style" for legacy reasons: the VM parameter name in downstream screens (`stylesViewModel: OutfitsViewModel`), a handful of state fields in `OutfitsUiState` that hold the saved list (`styles`, `newSuggestion.styleId` via `OutfitPrediction.styleId`), and all `values/strings.xml` keys (`styles_empty`, `styles_sort_newest`, …). The JSON field in `OutfitEvent` is serialized as `outfitId` but accepts the legacy `styleId` via `@SerializedName(alternate)`. When adding new code, prefer "outfit"; leave existing legacy identifiers alone until a dedicated cleanup pass.

`OutfitsScreen` branches on three mutually exclusive states (checked in order):

1. `outfitsState.isEditingOutfitView` → **`OutfitEditingView`** (full-screen, see below)
2. `outfitsState.isCreating` → **`OutfitItemPicker`** (full-screen grid for manual creation from scratch)
3. otherwise → **`OutfitListScreen`** (the list with `OutfitCard`s and FABs)

**`OutfitEditingView`** is the unified editor used for:
- Editing an existing saved outfit (`startEditing(outfit)` routes through `openComposer(...)`)
- Reviewing / tweaking an AI-predicted existing outfit (auto-opened via `LaunchedEffect` when `prediction` arrives → `openPredictionInEditView`)
- Reviewing / tweaking an AI-composed new outfit (auto-opened via `LaunchedEffect` when `newSuggestion` arrives → `openSuggestionInEditView`)

It shows: editable name + description, outfit items as 100 dp tappable tiles in a `FlowRow`, an "Add item" `+` tile, and — when opened from a Gemini result — the AI reason text and a `RefinementSection`. Tapping a tile opens **`ItemSwapSheet`**, a `ModalBottomSheet` that filters the wardrobe by the item's category and supports single-selection replacement. The sheet has a "Suggest 10 alternatives" button that calls `OutfitsViewModel.suggestAlternatives()` and surfaces results as starred tiles sorted to the top. After saving, `pendingWearOutfitId` is set and a Snackbar offers "Wear today" (which calls `OutfitEventsViewModel.recordOutfit(id)`).

`OutfitListScreen` supports **multi-select**: long-press any card → enters selection mode (`selectedOutfitIds.isNotEmpty()`). In selection mode: tapping toggles selection, back exits, a selection bar (count / select-all / deselect-all) replaces the sort button, card Edit+Wear buttons hide, and the speed-dial FAB is replaced by two action FABs:
- **Delete** — confirmation dialog → `deleteSelectedOutfits()`. Deletion groups affected outfits by `Outfit.folderId` and saves each folder's `_outfits.json` independently, so it works in both single-location and All Locations mode.
- **Combine with AI** (≥ 2 selected) — `openComposerFromSelectedOutfits()` opens the unified composer seeded with the union of items from the selected outfits, prefilled with a "NameA + NameB" name. The user can hit "Enhance with AI" to let Gemini cull + complete the outfit.

**Outfits ViewModel key state** (`OutfitsUiState` in `OutfitsViewModel.kt`):
- `isEditingOutfitView` / `isCreating` / `isComposerOpen` — which full-screen view is open
- `draftItemIds / draftOutfitName / draftOutfitDescription / editingOutfit` — shared draft for editor + item-picker
- `prediction` / `newSuggestion` — AI results; `LaunchedEffect`s in `OutfitsScreen` open `OutfitEditingView` when either arrives
- `pendingWearOutfitId` — set after save; cleared by "Wear today" Snackbar action or dismiss
- `selectedOutfitIds` — multi-select set for `OutfitListScreen`
- `isLoadingAlternatives / alternativeIds` — per-item swap alternatives from Gemini
- `styles` / `wardrobeImages` — list of saved outfits (field kept as `styles` for legacy reasons) + all wardrobe images across locations for resolving icons

**Gemini prompt builders** (all private top-level functions in `OutfitsViewModel.kt`):
- `buildPredictionPrompt` — pick best existing outfit for today
- `buildCompositionPrompt` — compose a brand-new outfit from the full wardrobe
- `buildAlternativesPrompt` — suggest up to 10 swap alternatives for one item given the rest of the outfit as context
- `buildComposerPrompt` — unified composer prompt (see Unified outfit composer below); also used for the multi-outfit "combine" flow since seeding the composer with the union of items is equivalent.

**`OutfitsViewModel.saveOutfitDirectly(name, description, itemIds, onDone)`** is an internal helper used by `saveComposer()`. No UI code calls it directly.

### Outfit wear events (calendar)

`OutfitEventsViewModel` is a separate VM — it owns the calendar wear history, not `OutfitsViewModel`. It reads `_outfit_events.json` from Drive (falling back to the legacy `_outfits_metadata.json`) per location folder and caches it locally as `outfit_events_cache_{folderId}.json`. The `OutfitEvent` data class has `outfitId` (JSON serialized with alternate `styleId` for backward compat) and an ISO `date`.

- `setLocation(folderId)` / `setAllLocations(folderIds)` — kept in sync by `MainActivity`'s `LaunchedEffect(activeLocationId, locationList)`. In All Locations mode, events are loaded and merged from every folder.
- `recordOutfit(outfitId)` — appends a new event dated today and saves back to Drive. When All Locations is active, the write goes to the first configured folder.
- `CalendarScreen` reads this VM directly (via `outfitEventsViewModel: OutfitEventsViewModel = viewModel()`); `OutfitsScreen` also observes it to compute per-outfit `wearCounts` for sorting.

### Unified outfit composer

`OutfitComposerScreen` (full-screen Dialog) is the single entry point for creating a new outfit from any screen. It replaces the Wardrobe-selection "Create outfit" + "Compose with AI" split, the Outfits-multi-select "Combine with AI" FAB, and the Travel "Save as outfit" chip. `MainActivity` renders it unconditionally on top of the tab content; the composable itself returns early unless `state.isComposerOpen` is true.

**Entry points**:
- `OutfitsViewModel.openComposer(seedItemIds, images, prefs, initialName?, initialDescription?, editingOutfitId?)` — initializes composer state from a seed set of wardrobe items; `state.isComposerOpen` becomes `true`. When `editingOutfitId` is non-null, `saveComposer()` updates that outfit in place instead of creating a new one.
- `OutfitsViewModel.openComposerFromSelectedOutfits(images, prefs)` — seeds with the union of items from `selectedOutfitIds` and prefills a "NameA + NameB" name; also clears the selection.
- `OutfitsViewModel.startEditing(outfit, images, prefs)` — opens the composer seeded with the existing outfit's items, name, and description, with `composerEditingOutfitId` set.

**Sections** (all scrollable, inside a single `LazyColumn`):
1. **Items** — grid of chosen items as 96 dp tiles; tap to remove; `+` tile opens an item picker bottom sheet that lists wardrobe filtered by optional category chips and supports multi-add.
2. **Weather** — toggle chip between `Auto` (reads `WeatherViewModel.state.data`, shown as temp + WMO emoji) and `Manual` (season + temp-range + precip chips).
3. **Vibe** — multi-select chips: Casual, Sporty, Formal, Business, Streetwear, Minimalist, Classic, Elegant.
4. **Composition targets** — per-layer count steppers (Top / Bottom / Footwear / Outerwear / Accessory). Defaults derived from seed item tags.
5. **Preference prompt** — `OutlinedTextField` prefilled from `UserPreferences.preferences`, editable for this composition only (not saved back).
6. **Name + description** — editable fields.
7. **AI enhance** — history chips of prior feedback + preset quick-picks (More casual, More formal, Different colors, Warmer, Lighter, More trendy, Simpler, More bold) + freetext `TextField` + a prominent progress row ("Asking Gemini…" + spinner) + "Enhance with AI" button. Preset taps immediately trigger enhance with that preset as feedback. Accumulates `composerFeedbackHistory`, calls `buildComposerPrompt`, and merges Gemini's item list / name / description / reason into the draft.
8. **Save** — calls `OutfitsViewModel.saveComposer()` → `saveOutfitDirectly`.

**ViewModel additions** (`OutfitsUiState`): `isComposerOpen`, `composerEditingOutfitId`, `composerItemIds`, `composerWeatherMode` (AUTO/MANUAL), `composerManualSeason`, `composerManualTempC`, `composerManualPrecip`, `composerVibes`, `composerTargets` (`ComposerTargets` data class), `composerPrefOverride`, `composerName`, `composerDescription`, `composerFeedback`, `composerFeedbackHistory`, `composerReason`, `isComposerEnhancing`, `composerError`.

**Prompt**: `buildComposerPrompt(...)` — one top-level builder in `OutfitsViewModel.kt`. Asks Gemini to propose an outfit that MUST include the current draft item IDs and add complementary items to meet the target composition counts.

**Migrated entry points**:
- Wardrobe selection mode FAB → `openComposer(selectedIds)`.
- Wardrobe FullScreenViewer long-press sheet → `openComposer(selectedIds)`.
- Outfits list Edit button / `startEditing` → `openComposer(..., editingOutfitId=outfit.id)`.
- Outfits multi-select "Combine with AI" FAB → `openComposerFromSelectedOutfits()`.
- Travel `PackingOutfit` "Save as outfit" chip → `openComposer(outfit.itemIds, initialName=occasion, initialDescription=description)`.

**Not yet migrated**:
- Outfits speed-dial "Compose new outfit" — still uses `triggerComposition` → `newSuggestion` → `OutfitEditingView`.

### Travel packing screen

`TravelScreen` receives `travelViewModel`, `wardrobeViewModel`, `profileViewModel`, `stylesViewModel: OutfitsViewModel`, and `locationViewModel`. The `stylesViewModel` parameter name is legacy; its type is `OutfitsViewModel`.

**Packing list generation**: destination + date range → `WeatherRepository.fetchDestinationForecast()` → `buildPackingPrompt()` → Gemini → `PackingList` (list of `PackingOutfit` + `extraItems`). A refinement loop (free-text + preset chips) re-runs `buildPackingPrompt()` with accumulated `feedbackHistory`.

**`PackingOutfit` cards** each show a "Save as outfit" `InputChip`. Tapping it opens the unified composer (`stylesViewModel.openComposer(...)`) prefilled with the outfit's items, occasion (as name) and description, so the user can tweak + confirm the save there.

**"Move all to Travel location" button** appears in the packing list header when there are packed items:
1. Calls `locationViewModel.getOrCreateLocation("Travel")` — finds an existing location named "Travel" (case-insensitive) or creates a new Drive subfolder + updates the locations JSON, then returns the folderId via callback.
2. Calls `wardrobeViewModel.moveItemsToFolder(itemIds, toFolderId)` — moves each item's cutout + original + sidecar files via `DriveRepository.moveFile()` (single PATCH, no re-upload), then drops the moved items from in-memory state.
3. A Snackbar confirms success or failure.

### Try-on (unified)

`TryOnViewModel` + `TryOnComposerScreen` form the unified Try-on flow. The old `TryOnResultDialog` is gone — both the Wardrobe-selection "Try on" FAB and the single-outfit "Try on" FAB in `OutfitsScreen` now call `tryOnViewModel.openComposer(itemIds)` via the shared `runTryOn` lambda in `MainActivity`. `TryOnComposerScreen` is rendered unconditionally at the `MainActivity` level on top of the tab content; it returns early unless `state.isComposerOpen`.

The composer has three modes, selected in `TryOnComposerScreen` by state check order:
1. **Detail view** (`state.viewingTryOn != null`) — zoomable past try-on image + resolved item chips + Delete button.
2. **History grid** (`state.isHistoryOpen`) — grid of saved try-ons; tap to open detail view.
3. **Compose / Preview** (default) — when no `resultPath`: item grid (tap to remove, `+` tile to add via `ItemPickerSheet`), explanation surface ("All items shown will be worn together…"), and a "Generate try-on" button. Once a result arrives: the preview replaces the compose UI with a pinch-zoom image (`detectTransformGestures`, scale 1f..6f) + "Save to Drive" / "Try again" / "Change items" / "Save to gallery" actions.

**Drive layout**: Generated PNGs live in a `_tryons` subfolder of the root Drive folder (`DriveRepository.getOrCreateTryOnsFolder`). The index `_tryons.json` is stored at the root — it holds `List<TryOn>`. `TryOn` stores both `imageDriveId` (for direct access) and `imageName` (stable handle across Drive folder copies), plus `itemNames` (cutout filenames — resolved back to current Drive IDs at load time by matching against `wardrobeImages`). `TRYONS_FILE_NAME` is added to `SYSTEM_JSON_NAMES` so it is never treated as a sidecar.

**Caching**: Saved try-on PNGs are cached under `cacheDir/tryon_{driveId}.png` and re-downloaded via `DriveRepository.downloadFileTo(...)` on history load if missing. The pre-save result lives in `cacheDir/tryon_results/tryon_{timestamp}.png`; on save it is copied to the stable cached path.

**Save flow** (`TryOnViewModel.saveCurrent`): ensures the root folder, uploads the PNG to `_tryons/`, records a `TryOn` entry, prepends it to the existing `_tryons.json`, and writes the updated list. `lastGeneratedItemIds/Files` captures which items were actually sent to Gemini for the current result — so Save reflects the generation's inputs even if the user edited the composer afterward.

### Offline mode

The app works in view-only mode when offline. `NetworkMonitor` (in `NetworkUtils.kt`) uses `ConnectivityManager.NetworkCallback` to expose a `StateFlow<Boolean>` of real-time connectivity. `MainActivity` provides the state via `LocalIsOffline`, a `CompositionLocal` defined in `NetworkUtils.kt` — any composable reads `LocalIsOffline.current` without parameter threading.

Online status requires **both** `NET_CAPABILITY_INTERNET` **and** `NET_CAPABILITY_VALIDATED`. Checking only `INTERNET` misses the common case where wifi stays associated but the uplink is dead — Android does not fire `onLost` for that, only `onCapabilitiesChanged` with `VALIDATED` dropped. All three callbacks (`onAvailable`, `onLost`, `onCapabilitiesChanged`) funnel through a single `recomputeOnline()` helper that re-reads the active network.

**UI indicator**: An animated banner (`errorContainer` background, `CloudOff` icon, localized text) appears at the top of the content area when offline and disappears when connectivity returns.

**What works offline** (from local disk cache):
- Browsing wardrobe items, outfits, calendar, and previously generated travel packing lists
- Viewing tag overlays and item details

**What is disabled offline** (hidden or greyed out) — everything that writes to Drive or calls Gemini:
- **WardrobeScreen**: upload FABs (camera + gallery) hidden; in selection mode, "Create outfit", "Compose with AI", "Move to closet", and "Delete" FABs all hidden
- **FullScreenViewer**: long-press action sheet does not open (suppresses Create outfit / Compose with AI / Move to / Delete); rotate FAB hidden; "Detect tags" and "Remove background" greyed out in `TagsOverlay`
- **OutfitsScreen**: AI speed-dial items (Suggest, Compose) hidden; per-card Edit+Wear row hidden in `OutfitCard`; "Combine with AI" and "Delete" FABs hidden in multi-select; "Suggest alternatives" hidden in `ItemSwapSheet`; `RefinementSection` hidden in `OutfitEditingView`
- **CalendarScreen**: "Wear again today" and "Edit" buttons hidden in the day-detail sheet row
- **TravelScreen**: Generate button greyed out; refinement section hidden; "Move all to Travel" button greyed out
- **WardrobeGapScreen**: Analyze button greyed out
- **SettingsScreen**: Retag All, Remove All Backgrounds, Repair & Sync, Import buttons greyed out; Try-on photo slots hidden (upload requires Drive write)
- **WardrobeScreen / OutfitsScreen**: Try-on FAB hidden (image generation requires Gemini)

When adding new network-dependent UI actions, read `LocalIsOffline.current` and either hide the action or set `enabled = !isOffline`.

**Language persistence offline**: `ProfileViewModel.loadPreferences()` falls back to `cachedLanguage()` (the `librelookai_lang` SharedPreferences cache) whenever the Drive JSON is null or cannot be parsed, instead of constructing a fresh `UserPreferences()` (which would default to English). This prevents the UI from flipping to English when Drive is unreachable.

### Navigation

`MainActivity` owns a single `selectedTab: Int` integer. There is no Navigation component — each tab renders its Screen composable directly inside a `when` block. All ViewModels are created once at the `MainActivity` level and passed down as parameters. A `LaunchedEffect(activeLocationId, locationList)` in `MainActivity` keeps `WardrobeViewModel`, `OutfitsViewModel`, and `OutfitEventsViewModel` in sync whenever the global location changes, and also calls `OutfitsViewModel.updateSaveFolder(...)` so new outfits save to the right folder.

Tab indices: `0=Outfits, 1=Wardrobe, 2=Calendar, 3=Travel, 4=Gaps, 5=Shop, 6=Settings`. The bottom `AppNavBar` lists 0–5 (6 items); Settings is reachable via the gear icon in each screen's `AppScreenHeader`, not the nav bar.

`SettingsScreen` internally uses a `TabRow` with three tabs: Profile, Data, Credits (Credits only visible in managed mode).

### Localisation

`AppLanguage.toLocale()` / `AppLanguage.toGeminiName()` convert the stored enum to a `Locale` and a Gemini-friendly language name respectively. The active locale is applied by wrapping the composable tree in a `CompositionLocalProvider(LocalContext provides localizedContext)` — there is no Activity restart on language change.

String resources live in `values/strings.xml` and `values-de/strings.xml`. Add new strings to both files.

### UI shell

All six main screens use `AppScreenHeader` (defined in `MainActivity.kt`) for a consistent top bar: leading icon (optional), `titleMedium/SemiBold` title, trailing slot (optional), followed by a `HorizontalDivider`. The trailing slot typically contains a `LocationButton` (closet door icon `DoorSliding` with dropdown, defined in `MainActivity.kt`, only visible when 2+ closets exist) and optionally a sort button. The closet selection is **global** — changing it on any screen updates `LocationViewModel.setActiveLocation()` which triggers the `MainActivity` `LaunchedEffect` to reload wardrobe images, outfits, and wear events for the selected closet(s). `OutfitListScreen` additionally does client-side filtering by `folderId` because outfits always load their items from all locations.

### Wardrobe find-by-photo

Header icon (`Icons.Default.ImageSearch`) on the wardrobe trailing slot opens the camera in `WardrobeView.FIND_BY_PHOTO_CAPTURE` mode (reuses `CaptureScreen` with `showCenterCrosshair = true`). After the user accepts a photo, `WardrobeViewModel.onFindByPhotoCaptured` runs `EmbeddingService.findSimilar` (threshold = -1f so all top-K matches surface regardless of similarity) and populates `WardrobeUiState.findByPhoto`. `GridContent` renders `FindByPhotoResultsSheet` (modal bottom sheet) showing the query thumbnail + a 96 dp adaptive grid of matches with similarity scores. Tapping a match clears active tag filters and sets a `pendingDriveIdToOpen` state; a `LaunchedEffect` keyed on `displayedImages` resolves the index and opens the existing `FullScreenViewer`. Available offline because matching uses local cached cutouts only.

### Photo upload flow

1. `WardrobeViewModel.uploadPhoto(rawFile)` uploads the raw JPEG to Drive and enqueues a `PendingJob`. The initial `DriveImage` must include `folderId = targetFolderId` so the closet label appears immediately in the grid (same for `uploadGalleryPhotos`).
   - **Pre-upload similarity gate**: when `dedupeOnImport` is on (and the embedder model is present), `uploadPhoto` first runs `EmbeddingService.findSimilar(rawFile, threshold=dedupeThreshold, segment=true)`. If any wardrobe item scores above the threshold, the upload pauses and `WardrobeUiState.duplicateCheck` is set, which renders a `DuplicateCheckSheet` (modal bottom sheet) showing the query thumbnail + matches with similarity scores. The user picks `Import anyway` (→ `confirmDuplicateImport`) which proceeds with the original upload, or `Cancel` (→ `cancelDuplicateImport`) which deletes the raw file. The embedding index is synced before the search so freshly-added items aren't missed. Gallery uploads (`uploadGalleryPhotos`) bypass this gate to avoid blocking multi-select imports — folder-import preview (touchpoint #5) handles bulk dedupe instead.
2. `processQueue()` drains the queue serially: bg removal via Gemini → upload cutout (renamed to `{id}_cutout.png`) → copy local original cache → upload original (renamed to `{cutoutId}_original.jpg`) → delete raw → classify tags → write sidecar.
3. The local `{driveId}_original.jpg` cache copy must happen **before** `deleteFile()` is called, because `DriveRepository.deleteFile()` also deletes the local `_original.jpg` file.
4. State is updated by matching on **either** the raw Drive ID or the cutout Drive ID to handle the race where `loadImages()` may have already placed the item with the cutout ID.
5. **Closet picker**: When 2+ closets exist, the target closet can be selected in two ways depending on the import method. **Camera**: `CaptureScreen` shows an inline `ClosetChip` (semi-transparent pill) in the top-right corner of the viewfinder and top-center of the review screen; tapping it opens a `DropdownMenu` to switch closets — no extra dialog step. **Gallery**: tapping the gallery FAB shows an `AlertDialog` with radio buttons to choose the target closet before opening the system photo picker. The default is the active closet from Settings. `WardrobeUiState.importTargetFolderId` exposes the current target; `setDefaultImportFolderId()` updates both the private field and the UI state. With only one closet, both the chip and dialog are hidden.

### Background job protection

Long-running wardrobe operations are protected against process death and CPU sleep by two mechanisms managed together in `WardrobeViewModel`:

- **`JobForegroundService`**: started when the first job begins, stopped when the last one ends. Promotes the app to foreground-service priority so Android will not kill the process. Shows a persistent notification (channel `librelookai_jobs`, low importance) while active. Declared with `android:stopWithTask="false"` so it survives the user swiping the app away from recents. Returns `START_STICKY` so Android restarts it if killed under memory pressure.
- **`PARTIAL_WAKE_LOCK`** (`LibreLookAI:Jobs`): keeps the CPU running if the screen turns off mid-job. 30-minute safety timeout.
- **Battery optimization exemption**: on first job start, `acquireJobWakeLock()` checks `PowerManager.isIgnoringBatteryOptimizations()`. If the app is not exempt, `WardrobeUiState.needsBatteryExemption` is set to `true` and `GridContent` shows a one-shot `AlertDialog` prompting the user to open the system battery optimization settings (`Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). This is required on OEM-customized ROMs (Samsung, Xiaomi, etc.) that aggressively kill even foreground services. The prompt is dismissed via `WardrobeViewModel.dismissBatteryExemptionWarning()`.

Both service and wake lock are reference-counted via `acquireJobWakeLock()` / `releaseJobWakeLock()` (using `AtomicInteger`). Covered operations: `processQueue`, `importFromFolder`, `importFromDriveFolder`, `removeAllBackgrounds`, `retagAll`, and both phases of Repair & Sync. Every caller wraps its coroutine body in `try/finally` to guarantee release.

### Repair & Sync

`WardrobeViewModel.startRepairAndRefresh(folderIds)` runs a multi-phase audit across all location folders. A foreground service + `PARTIAL_WAKE_LOCK` are held throughout (via `acquireJobWakeLock`) so the job survives screen-off. All actions are logged to logcat under the tag `RepairAndSync`.

1. **Scan**: `DriveRepository.listAllImageFiles()` returns every image (originals, cutouts, raws). Cutouts with wrong names are renamed in-place. Originals whose prefix does not match any cutout's Drive ID are flagged as orphaned. For each cutout that has a sidecar, the sidecar content is downloaded and parsed — if `tags` is null (i.e. the file is `{}` or `{"tags":null}`), the cutout is flagged for re-tagging just like a missing sidecar.
1b. **Duplicate detection (Phase 1b)**: After the file scan, `EmbeddingService.syncIndex` embeds any cached cutout that's missing from the index, then `findDuplicateClusters(dedupeThreshold, restrictToIds=current_wardrobe)` returns each cutout that has a peer with cosine similarity ≥ threshold. Each is added as an `AuditFileEntry` with `kind = AuditKind.DUPLICATE`, `similarTo = peer drive IDs`, and `topScore`. Cutouts not in the local cache are skipped (they'll be picked up next time the wardrobe is loaded).
2. **Confirmation**: `WardrobeUiState.auditProgress` enters `awaitingConfirmation` and exposes the full list of findings as `items: List<AuditFileEntry>` plus `selectedAuditIds`. The default selection includes every fix-needed entry (orphaned/raw/needs-sidecar) but excludes `DUPLICATE` entries — selecting a duplicate means "delete it", which is destructive, so the user must explicitly opt in. When at least one finding exists, `SettingsScreen` shows the full-screen `RepairPreviewDialog` — a wardrobe-style `LazyVerticalGrid` (96 dp adaptive cells) split into four sections (Orphaned originals, Raw images, Cutouts needing tags, Possible duplicates). Duplicate tiles get a `Similar` badge in the bottom-left corner and a help line under the section header. The bottom-bar credit cost ignores `DUPLICATE` entries (deletion is free).
3. **Process** (`continueRepairProcessing(true)`): only items whose Drive ID is in `selectedAuditIds` are processed — orphaned originals get full AI processing (bg removal + tagging + sidecar upload); cutouts missing a sidecar or with empty tags get tagging (from the cutout image) + sidecar upsert; selected duplicates have their cutout + original + sidecar deleted from Drive and the embedding index entry is removed. If the user deselects everything, the call is treated as "skip" (cache clear + reload only).
4. **Refresh**: all local caches are cleared and `loadImages()` reloads from Drive. `auditProgress.isDone` signals completion.

### SAF import

`WardrobeViewModel.importFromFolder(treeUri)` reads images from any OS-accessible folder using `DocumentsContract` + `ContentResolver` (no extra OAuth scopes needed), re-uploads them to the app's Drive folder, and classifies new items with Gemini before writing per-item sidecars.

### Visual wardrobe search (Shopping helper)

On-device, zero-network visual similarity search powered by MediaPipe's `ImageEmbedder`. Used by the Shopping helper, capture-time duplicate detection, the Wardrobe "Find item by photo" entry point, Repair & Sync's duplicate detection, and the folder-import preview.

**Entry point (v1)**: Dedicated bottom-nav tab "Shop" (index 5, `Icons.Default.ShoppingBag`). Settings moved from tab 5 → 6.

**Shared service**: `EmbeddingService` is a process-wide `object` that owns the only `EmbeddingRepository`, `SegmentationRepository`, and `EmbeddingIndex` instances. `MainActivity.onCreate` calls `EmbeddingService.init(this)` before `setContent` so all consumers see the same MediaPipe handles and the persistent index file. Public API:

- `syncIndex(images, cacheDir, onProgress)` — embed any cutout that has a local file but isn't in the index; drop entries no longer present.
- `findSimilar(file, threshold, excludeIds, topK, segment=true)` — segment + composite-on-white + crop + embed + filter.
- `findSimilarBitmap(bitmap, ...)` — same but for an in-memory bitmap (no segmentation).
- `embedQuery(file, segment=true)` — embed-only, for callers that want to reuse the vector.
- `searchVector(vec, threshold, excludeIds, topK)` — search with an already-computed embedding.

`ShoppingHelperViewModel` no longer constructs its own embedder/segmenter/index; it consumes `EmbeddingService` directly. Future similarity features should follow the same pattern.

**User preferences** for similarity (`UserPreferences`): `dedupeOnImport: Boolean = true` (gate for capture/import duplicate checks), `dedupeThreshold: Float = 0.85f` (single threshold reused across all touchpoints; slider in Settings → Profile, range 0.7–0.95).

**Pipeline**:

```
Camera → Bitmap
  → MediaPipe InteractiveSegmenter (Magic Touch, seed = image center)
       → composite foreground onto opaque white
  → Center-crop to square
  → MediaPipe ImageEmbedder (MobileNet V3 Small, L2-normalized)
  → Dot product vs. each cached wardrobe embedding
  → Top-N matches sorted by score
```

Cached cutouts (transparent PNGs) skip the segmentation step but go through the same composite-onto-white + center-crop preparation, so query and gallery images share an identical backdrop and aspect ratio before embedding.

**Components**:
- `EmbeddingRepository` — lazy singleton that holds the MediaPipe `ImageEmbedder`. `embedFile(file)` decodes, composites alpha onto white if present, center-crops to a square, and embeds. `embedBitmap(bitmap, compositeAlpha)` is the bitmap entry point used after segmentation. Mutex-guarded; `close()` releases native resources. Embedder is configured with `setL2Normalize(true)` so cosine similarity reduces to a dot product.
- `SegmentationRepository` — lazy singleton holding MediaPipe's `InteractiveSegmenter`. `segmentForegroundOnWhite(bitmap)` runs the model with a center-keypoint seed and returns a new bitmap with foreground pixels preserved and background pixels replaced with opaque white. Returns null if the model asset is missing or the mask covers <2% of pixels (likely garbage); callers fall back to embedding the un-segmented image. The capture UI shows a center crosshair (`showCenterCrosshair = true`) so the user knows the seed point.
- `EmbeddingIndex` — in-memory `Map<cutoutDriveId, FloatArray>` + binary persistence at `filesDir/wardrobe_embeddings.bin` (format: `magic u32 | version u16 | dim u16 | count u32 | [idLen u16 | idUtf8 | float32[dim]]*`). `VERSION = 2`. Old indexes are discarded on load and rebuilt on next sync.
- `ShoppingHelperViewModel` — holds the repos + index. `syncIndex(images)` walks the current wardrobe, embeds any item with a cached cutout that isn't in the index yet, drops orphaned index entries, and persists. `onCapturedFile(file)` runs `embedQuery` (segment → composite → crop → embed) and publishes ranked matches. State exposes `isIndexing` with progress counts, `isMatching`, `query` thumbnail path, `matches: List<Match(driveId, score)>`, and `error`.
- `ShoppingHelperScreen` — three visual states: (a) capture prompt with a big camera FAB, (b) inline `CaptureScreen` (reuses the existing one) with the center crosshair enabled, (c) result — query thumbnail up top + ranked list of wardrobe items with similarity bars. Header shows the `LocationButton` (same as other screens). Everything runs locally, so the shop tab is fully functional offline.

**Indexing strategy**: No hooks in `WardrobeViewModel.processQueue`. Indexing is pull-based — the shop screen syncs the index every time it opens, and before every match. Empirically this takes <1s for ~100 items on modern devices; subsequent opens are near-instant since only new/deleted items cause work.

**Model assets** (both excluded from git):
- `app/src/main/assets/embedder/efficientnet_lite0.tflite` (~4 MB; the filename is legacy — the bundled file is actually MobileNet V3 Small). Download from `https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite` and save under the legacy name. The previous EfficientNet Lite0 GCS path is dead (404); if you change models, also update `EmbeddingRepository.MODEL_PATH`.
- `app/src/main/assets/segmenter/magic_touch.tflite` (~6 MB). Download from `https://storage.googleapis.com/mediapipe-models/interactive_segmenter/magic_touch/float32/1/magic_touch.tflite`.

After downloading, verify each file with `file <path>` — a non-200 response from GCS produces a ~250-byte XML error body that `EmbeddingRepository.isAvailable()` will happily accept (the asset opens fine), and you'll only find out when MediaPipe fails to construct and the Shop screen shows "Could not analyze that photo." Real models are multi-MB binary `data`.

If either is missing, the Shop screen shows a dev-facing warning. The embedder is required; the segmenter is optional — when absent the pipeline falls back to embedding the raw query (with white-bg compositing only).

## Release process (Play Store internal testing)

### One-time setup

1. **Create keystore** (store outside repo):
   ```bash
   keytool -genkey -v -keystore librelookai-release.jks \
     -alias librelookai -keyalg RSA -keysize 2048 -validity 10000
   ```

2. **Add signing config** to `app/build.gradle.kts`:
   ```kotlin
   val signingRelease = android.signingConfigs.create("release") {
       storeFile = file(localProps.getProperty("signing.store.file", ""))
       storePassword = localProps.getProperty("signing.store.password", "")
       keyAlias = localProps.getProperty("signing.key.alias", "")
       keyPassword = localProps.getProperty("signing.key.password", "")
   }
   // inside buildTypes { release { ... } }
   signingConfig = signingRelease
   ```

3. **Add release SHA-1 to Firebase Console** (Project Settings → Your apps → Android app):
   ```bash
   keytool -list -v -keystore librelookai-release.jks -alias librelookai
   ```
   Without this, Google Sign-In → Firebase Auth will fail on release builds.

4. **Create the app in Play Console** (package: `com.librelookai`).

### Per-release checklist

- [ ] Increment `versionCode` in `app/build.gradle.kts`
- [ ] `google-services.json` present in `app/` (Firebase enabled)
- [ ] `firebase.proxy.url` and `firebase.web.client.id` set in `local.properties`
- [ ] `./gradlew bundleRelease` succeeds
- [ ] Firebase Functions deployed: `cd firebase && firebase deploy --only functions`
- [ ] AAB uploaded to Play Console → Testing → Internal testing → Create new release
- [ ] Testers added via email list on the Testers tab; they receive an opt-in link

### Giving test credits to testers (managed mode)

After a tester signs in, their Firebase UID appears in the Firestore `users` collection. Set their balance manually in the Firebase Console or with the Admin SDK:
```
/users/{uid}/credits = 100
```
