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
- **Cross-closet snapshot for similarity search**: `WardrobeViewModel.setAllConfiguredLocations(folderIds)` (called by the same `MainActivity` `LaunchedEffect`) registers every configured closet's `folderId` and refreshes `WardrobeUiState.allLocationImages` — a merged `List<DriveImage>` read directly from each per-folder `wardrobe_cache_{folderId}.json` (cached cutouts only, no network). `allLocationImages` is independent of the active-closet filter applied to `state.images`, and is also re-derived after every `saveLocalCache` write so the snapshot tracks new/removed/retagged items. **The shopping-closet folder is included in this list** (so similarity search finds wishlist items too), even though it is never a regular `Location` and never appears in `LocationButton`. All similarity-search call sites (Wardrobe Find by photo, capture-time and folder-import dedupe checks, Shopping → Similarity Finder, Repair & Sync's duplicate detection) read from this snapshot so matches always span every wardrobe regardless of which closet is currently displayed.
- **Shopping closet** (separate from regular closets): the dedicated Drive folder `LibreLookAI/_shopping/` holds wishlist items the user is considering buying. It is **not** a `Location` — it never appears in `LocationViewModel.locations`, `LocationButton`, Settings → Closets, Outfits, Calendar, or Insights. The folder layout matches a regular closet (cutout / original / sidecar triplet using the same `{cutoutDriveId}_*` naming) so the existing `ShoppingClosetViewModel` workflow + `DriveRepository` helpers (and Repair & Sync, if extended later) work without special-casing. Local cache file `wardrobe_cache_{shoppingFolderId}.json` follows the standard pattern; this is what folds shopping items into the cross-closet snapshot above. `DriveRepository.getOrCreateShoppingFolder(rootId)` returns the folder ID; `SHOPPING_FOLDER_NAME = "_shopping"` is added to the set of root-level subfolder names that import flows must skip when listing closets.
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


`OutfitsScreen` branches on three mutually exclusive states (checked in order):

1. `outfitsState.isEditingOutfitView` → **`OutfitEditingView`** (full-screen, see below)
2. `outfitsState.isCreating` → **`OutfitItemPicker`** (full-screen grid for manual creation from scratch)
3. otherwise → **`OutfitListScreen`** — wraps two sub-tabs under the screen header: **Outfits** (the existing list with `OutfitCard`s, sort button, tag filters, selection mode and FABs) and **Try-Ons** (a grid of saved `TryOn` images sourced from `tryOnViewModel.state.history`; tap a tile to open the unified composer dialog directly into history-detail view via `tryOnViewModel.openHistoryDetail(t)`). The sort button, tag filters, selection bar and speed-dial FAB only render on the Outfits sub-tab. `tryOnViewModel.loadHistory()` is invoked whenever the Try-Ons sub-tab is selected. `OutfitsScreen` therefore now also receives `tryOnViewModel: TryOnViewModel` from `MainActivity`.

**`OutfitEditingView`** is the unified editor used for:
- Editing an existing saved outfit (`startEditing(outfit)` routes through `openComposer(...)`)
- Reviewing / tweaking an AI-predicted existing outfit (auto-opened via `LaunchedEffect` when `prediction` arrives → `openPredictionInEditView`)
- Reviewing / tweaking an AI-composed new outfit (auto-opened via `LaunchedEffect` when `newSuggestion` arrives → `openSuggestionInEditView`)

**Gemini prompt builders** (all private top-level functions in `OutfitsViewModel.kt`):
- `buildPredictionPrompt` — pick best existing outfit for today
- `buildCompositionPrompt` — compose a brand-new outfit from the full wardrobe
- `buildAlternativesPrompt` — suggest up to 10 swap alternatives for one item given the rest of the outfit as context
- `buildComposerPrompt` — unified composer prompt (see Unified outfit composer below); also used for the multi-outfit "combine" flow since seeding the composer with the union of items is equivalent.

**`OutfitsViewModel.saveOutfitDirectly(name, description, itemIds, onDone)`** is an internal helper used by `saveComposer()`. No UI code calls it directly.

### Outfit wear events (calendar)

`OutfitEventsViewModel` is a separate VM — it owns the calendar wear history, not `OutfitsViewModel`. It reads `_outfit_events.json` from Drive (falling back to the legacy `_outfits_metadata.json`) per location folder and caches it locally as `outfit_events_cache_{folderId}.json`. The `OutfitEvent` data class has `outfitId` (JSON serialized with alternate `styleId` for backward compat) and an ISO `date`.

### Unified outfit composer

`OutfitComposerScreen` (full-screen Dialog) is the single entry point for creating a new outfit from any screen. It replaces the Wardrobe-selection "Create outfit" + "Compose with AI" split, the Outfits-multi-select "Combine with AI" FAB, and the Travel "Save as outfit" chip. `MainActivity` renders it unconditionally on top of the tab content; the composable itself returns early unless `state.isComposerOpen` is true.

### Travel packing screen

`TravelScreen` receives `travelViewModel`, `wardrobeViewModel`, `profileViewModel`, `stylesViewModel: OutfitsViewModel`, and `locationViewModel`. The `stylesViewModel` parameter name is legacy; its type is `OutfitsViewModel`.

**Packing list generation**: destination + date range → `WeatherRepository.fetchDestinationForecast()` → `buildPackingPrompt()` → Gemini → `PackingList` (list of `PackingOutfit` + `extraItems`). A refinement loop (free-text + preset chips) re-runs `buildPackingPrompt()` with accumulated `feedbackHistory`.


### Try-on (unified)

`TryOnViewModel` + `TryOnComposerScreen` form the unified Try-on flow. The old `TryOnResultDialog` is gone — both the Wardrobe-selection "Try on" FAB and the single-outfit "Try on" FAB in `OutfitsScreen` now call `tryOnViewModel.openComposer(itemIds)` via the shared `runTryOn` lambda in `MainActivity`. `TryOnComposerScreen` is rendered unconditionally at the `MainActivity` level on top of the tab content; it returns early unless `state.isComposerOpen`.

The composer has three modes, selected in `TryOnComposerScreen` by state check order:
1. **Detail view** (`state.viewingTryOn != null`) — zoomable past try-on image + resolved item chips + Delete button.
2. **History grid** (`state.isHistoryOpen`) — grid of saved try-ons; tap to open detail view.
3. **Compose / Preview** (default) — when no `resultPath`: item grid (tap to remove, `+` tile to add via `ItemPickerSheet`), explanation surface ("All items shown will be worn together…"), and a "Generate try-on" button. Once a result arrives: the preview replaces the compose UI with a pinch-zoom image (`detectTransformGestures`, scale 1f..6f) + "Save to Drive" / "Try again" / "Change items" / "Save to gallery" actions.

**Entry points**:
- Wardrobe selection → "Try on" FAB → `runTryOn(itemIds)` → `openComposer(itemIds)` → mode 3 (compose).
- Outfits list single-selection → "Try on" FAB → same path.
- Outfits → **Try-Ons sub-tab** → tap a saved try-on tile → `openHistoryDetail(tryOn)` → opens the composer dialog with `isHistoryOpen = true` and `viewingTryOn = tryOn` so dismissing detail returns to the in-dialog history grid; closing the dialog returns to the sub-tab.

`TryOnHistoryGrid` is `internal` so it can be reused inline by the Outfits → Try-Ons sub-tab.

**Drive layout**: Generated PNGs live in a `_tryons` subfolder of the root Drive folder (`DriveRepository.getOrCreateTryOnsFolder`). The index `_tryons.json` is stored at the root — it holds `List<TryOn>`. `TryOn` stores both `imageDriveId` (for direct access) and `imageName` (stable handle across Drive folder copies), plus `itemNames` (cutout filenames — resolved back to current Drive IDs at load time by matching against `wardrobeImages`). `TRYONS_FILE_NAME` is added to `SYSTEM_JSON_NAMES` so it is never treated as a sidecar.

**Caching**: Saved try-on PNGs are cached under `cacheDir/tryon_{driveId}.png` and re-downloaded via `DriveRepository.downloadFileTo(...)` on history load if missing. The pre-save result lives in `cacheDir/tryon_results/tryon_{timestamp}.png`; on save it is copied to the stable cached path.

**Save flow** (`TryOnViewModel.saveCurrent`): ensures the root folder, uploads the PNG to `_tryons/`, records a `TryOn` entry, prepends it to the existing `_tryons.json`, and writes the updated list. `lastGeneratedItemIds/Files` captures which items were actually sent to Gemini for the current result — so Save reflects the generation's inputs even if the user edited the composer afterward.

### Offline mode

The app works in view-only mode when offline. `NetworkMonitor` (in `NetworkUtils.kt`) uses `ConnectivityManager.registerDefaultNetworkCallback` and exposes a `StateFlow<Boolean>` of real-time connectivity. The callback trusts its own `Network` / `NetworkCapabilities` arguments rather than re-querying `cm.activeNetwork` (which races during the offline transition and can leave the flow stuck at `true`). `MainActivity` instantiates the monitor with `remember { ... }` and pairs it with a `DisposableEffect` that calls `networkMonitor.unregister()` on dispose so the system callback is released across activity recreation. State is provided to composables via `LocalIsOffline`, a `CompositionLocal` defined in `NetworkUtils.kt` — any composable reads `LocalIsOffline.current` without parameter threading. When `isOffline` flips to true, screens hide write-path UI (e.g. the Wardrobe gallery + camera FABs gate on `!isOffline`).

### Navigation

`MainActivity` owns a single `selectedTab: Int` integer. There is no Navigation component — each tab renders its Screen composable directly inside a `when` block. All ViewModels are created once at the `MainActivity` level and passed down as parameters. A `LaunchedEffect(activeLocationId, locationList)` in `MainActivity` keeps `WardrobeViewModel`, `OutfitsViewModel`, and `OutfitEventsViewModel` in sync whenever the global location changes, and also calls `OutfitsViewModel.updateSaveFolder(...)` so new outfits save to the right folder.

Tab indices: `0=Outfits, 1=Wardrobe, 2=Shopping, 3=Travel, 4=Insights, 5=Settings`. The bottom `AppNavBar` lists 0–4 (5 items); Settings is reachable via the gear icon in each screen's `AppScreenHeader`, not the nav bar. The Calendar grid is no longer a top-level tab — it's the first sub-tab inside Insights.

`SettingsScreen` internally uses a `TabRow` with three tabs: Profile, Data, Credits (Credits only visible in managed mode).

### Localisation

`AppLanguage.toLocale()` / `AppLanguage.toGeminiName()` convert the stored enum to a `Locale` and a Gemini-friendly language name respectively. The active locale is applied by wrapping the composable tree in a `CompositionLocalProvider(LocalContext provides localizedContext)` — there is no Activity restart on language change.

String resources live in `values/strings.xml` and `values-de/strings.xml`. Add new strings to both files.

### UI shell

All six main screens use `AppScreenHeader` (defined in `MainActivity.kt`) for a consistent top bar: leading icon (optional), `titleMedium/SemiBold` title, trailing slot (optional), followed by a `HorizontalDivider`. The trailing slot typically contains a `LocationButton` (closet door icon `DoorSliding` with dropdown, defined in `MainActivity.kt`, only visible when 2+ closets exist) and optionally a sort button. The closet selection is **global** — changing it on any screen updates `LocationViewModel.setActiveLocation()` which triggers the `MainActivity` `LaunchedEffect` to reload wardrobe images, outfits, and wear events for the selected closet(s). `OutfitListScreen` additionally does client-side filtering by `folderId` because outfits always load their items from all locations.

### Wardrobe find-by-photo

Header icon (`Icons.Default.ImageSearch`) on the wardrobe trailing slot opens the camera in `WardrobeView.FIND_BY_PHOTO_CAPTURE` mode (reuses `CaptureScreen` with `showCenterCrosshair = true`). After the user accepts a photo, `WardrobeViewModel.onFindByPhotoCaptured` runs `EmbeddingService.findSimilar` against the cross-location snapshot (`state.allLocationImages`, see Closets section) so matches are returned from every wardrobe regardless of which closet is currently displayed. Threshold is `-1f` so all top-K matches surface regardless of similarity. Results land in `WardrobeUiState.findByPhoto`; `GridContent` renders `FindByPhotoResultsSheet` (modal bottom sheet) with the query thumbnail + a 96 dp adaptive grid of matches with similarity scores. Tapping a match clears active tag filters and — if the match lives in a different closet — switches the active closet to the match's folder via `LocationViewModel.setActiveLocation(...)`. The match's drive ID is then stashed in a `pendingScrollDriveId` state; a `LaunchedEffect` keyed on `displayedImages` calls `gridState.animateScrollToItem(idx)` and pulses a brief primary-coloured ring around the matched tile (cleared after ~2 s). The full-screen viewer is *not* opened — the user lands on the matched item in the grid and chooses what to do next. Available offline because matching uses local cached cutouts only.

### Photo upload flow

1. `WardrobeViewModel.uploadPhoto(rawFile)` uploads the raw JPEG to Drive and enqueues a `PendingJob`. The initial `DriveImage` must include `folderId = targetFolderId` so the closet label appears immediately in the grid (same for `uploadGalleryPhotos`).
   - **Pre-upload similarity gate**: when `dedupeOnImport` is on (and the embedder model is present), `uploadPhoto` first runs `EmbeddingService.findSimilar(rawFile, threshold=dedupeThreshold, segment=true)`. If any wardrobe item scores above the threshold, the upload pauses and `WardrobeUiState.duplicateCheck` is set, which renders a `DuplicateCheckSheet` (modal bottom sheet) showing the query thumbnail + matches with similarity scores. The user picks `Import anyway` (→ `confirmDuplicateImport`) which proceeds with the original upload, or `Cancel` (→ `cancelDuplicateImport`) which deletes the raw file. The embedding index is synced before the search so freshly-added items aren't missed. Gallery uploads (`uploadGalleryPhotos`) bypass this gate to avoid blocking multi-select imports — folder-import preview (touchpoint #5) handles bulk dedupe instead.
2. `processQueue()` drains the queue serially: bg removal via Gemini → upload cutout (renamed to `{id}_cutout.png`) → copy local original cache → upload original (renamed to `{cutoutId}_original.jpg`) → delete raw → classify tags → write sidecar.
3. The local `{driveId}_original.jpg` cache copy must happen **before** `deleteFile()` is called, because `DriveRepository.deleteFile()` also deletes the local `_original.jpg` file.
4. State is updated by matching on **either** the raw Drive ID or the cutout Drive ID to handle the race where `loadImages()` may have already placed the item with the cutout ID.
5. **Closet picker**: When 2+ closets exist, the target closet can be selected in two ways depending on the import method. **Camera**: `CaptureScreen` shows an inline `ClosetChip` (semi-transparent pill) in the top-right corner of the viewfinder and top-center of the review screen; tapping it opens a `DropdownMenu` to switch closets — no extra dialog step. **Gallery / URL**: the FAB shows an `AlertDialog` with radio buttons to choose the target closet (and, for URL, also captures the URL string in the same dialog) before kicking off the import. The default is the active closet from Settings. `WardrobeUiState.importTargetFolderId` exposes the current target; `setDefaultImportFolderId()` updates both the private field and the UI state. With only one closet, both the chip and dialog are hidden.
6. **URL import**: third FAB (`Icons.Default.Link`) on the wardrobe → `UrlImportDialog` → on submit, the dialog calls `setDefaultImportFolderId(...)` (when 2+ closets) and then `WardrobeViewModel.importFromUrl(url)`. The VM resolves the target folder from `defaultImportFolderId ?? folderId`, fetches the image via `WebProductFetcher`, and calls `uploadPhoto(image)` — so the URL-imported file goes through the same dedupe gate + bg removal + tagging + sidecar pipeline as a captured photo. Shopping List has the same FAB but routes through `ShoppingClosetViewModel.addFromUrl(url)` (its own pipeline, separate folder). See "URL import" below for the parser.

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
3. **Process** (`continueRepairProcessing(process, clearCache)`): only items whose Drive ID is in `selectedAuditIds` are processed — orphaned originals get full AI processing (bg removal + tagging + sidecar upload); cutouts missing a sidecar or with empty tags get tagging (from the cutout image) + sidecar upsert; selected duplicates have their cutout + original + sidecar deleted from Drive and the embedding index entry is removed. If the user deselects everything, the call is treated as "skip" (reload only).
4. **Refresh**: `loadImages()` reloads from Drive. The local image cache is wiped (forcing every cutout/original to re-download) **only when the user ticks "Clear local cache"** in the preview dialog — default off. `auditProgress.isDone` signals completion.

### SAF import

`WardrobeViewModel.importFromFolder(treeUri)` reads images from any OS-accessible folder using `DocumentsContract` + `ContentResolver` (no extra OAuth scopes needed), re-uploads them to the app's Drive folder, and classifies new items with Gemini before writing per-item sidecars. `importFromDriveFolder(sourceFolderId)` mirrors this for a Drive source.

Both go through a shared three-stage pipeline:

1. **Enumerate** (`enumerateSafSources` / `enumerateDriveSources`): produce a list of `ImportPreviewEntry` records, one per source image, each with a stable per-entry cache file path the source has already been copied/downloaded into. SAF entries also carry `srcMetaTags` / `srcMetaOriginalDriveId` from any sibling `_wardrobe_metadata.json`.
2. **Branch** (`kickoffImport`): when `dedupeOnImport` is on and the embedder is available, sync the index, run `EmbeddingService.findSimilar(threshold=dedupeThreshold)` over each entry, and pause with `WardrobeUiState.importPreview = ImportPreview(...)` for `ImportPreviewDialog` to render. Entries without a similarity hit start selected by default; entries with hits start unticked. When `dedupeOnImport` is off, the function jumps straight to step 3.
3. **Run** (`runImportEntries`): the per-entry upload / bg-removal / tagging loop, operating on the already-cached files. Reused by both the fast path (full list of entries) and the preview path (only selected entries). `confirmImportPreview()` invokes it on selected entries; `cancelImportPreview()` skips the run entirely. Both also delete the unused cache files and release the foreground-service wake lock acquired by the original `importFromFolder` call.

The Repair & Sync wake lock is released by the `importFromFolder` `finally` only when `importPreview == null` (i.e. the fast path completed synchronously); the preview path keeps it alive until confirm/cancel so the foreground service notification stays up while the user reviews.

### Insights tab (Calendar + Stats)

The bottom-nav "Insights" tab (`Icons.Default.Insights`, tab index 4) is a hub of read-only review tools. `InsightsScreen.kt` owns the screen and renders an `AppScreenHeader` followed by a `TabRow` with three sub-tabs:

1. **Calendar** — month-grid view of past wear events. The grid composable, day-cell layout, day-detail bottom sheet, and `OutfitSheetRow` all live inline in `InsightsScreen.kt` (there is no longer a separate `CalendarScreen.kt`). Tapping "Edit" on an outfit in the day sheet calls back to `MainActivity`, which switches to tab 0 (Outfits) and opens the editor via `stylesViewModel.startEditing(...)`.
2. **Calendar Stats** — top-N most-worn outfits and most-worn items. Computes from `OutfitEventsViewModel` + `OutfitsViewModel` + `WardrobeViewModel` state in the screen body.
3. **Wardrobe Stats** — per-tag-category counts and untagged tally (formerly the BarChart sheet on the Wardrobe header). Uses the `internal` `tagCategoryCounts()` / `tagCategoryDisplayLabel()` helpers from `WardrobeScreen.kt`.

`InsightsScreen` accepts `wardrobeViewModel`, `outfitEventsViewModel`, `stylesViewModel`, `locationViewModel`, plus an `onEditOutfit: (Outfit) -> Unit` callback. The selected sub-tab is `rememberSaveable`. The `LocationButton` lives on the `AppScreenHeader` (above the TabRow) and applies globally as elsewhere.

### Shopping Helper tab

The bottom-nav "Shopping" tab (`Icons.Default.ShoppingBag`, tab index 2) bundles the wishlist + the shopping-adjacent analysis tools. `ShoppingHelperScreen.kt` renders an `AppScreenHeader` + `TabRow` with three sub-tabs:

1. **Shopping List** — the user's wishlist. Backed by `ShoppingClosetViewModel` (separate from `ShoppingHelperViewModel`). Renders a wardrobe-style grid with three add FABs (camera, gallery, URL — see "URL import" below) and selection-mode FABs to **Move to closet…** (promotes a wishlist item to a real wardrobe item by Drive-moving the cutout + original + sidecar from `_shopping/` into a regular closet — tags are preserved, no re-tagging) or **Delete**. The `LocationButton` is hidden on this sub-tab because the shopping list is location-independent.
2. **Similarity Finder** — on-device visual similarity search. Camera capture → embed → match against the cross-closet wardrobe snapshot (which includes shopping items). Powered by `ShoppingHelperViewModel` + `EmbeddingService`. See "Visual wardrobe search" below for the full pipeline. The `MatchPreviewDialog` debug view (paged per-match score breakdown + processed thumbnails + hue histograms) lives here.
3. **Identify Gaps** — wardrobe gap analysis powered by `WardrobeGapViewModel`; renders the existing `GapSuggestionCard` composable (defined in `WardrobeGapScreen.kt`, kept there as a reusable card — the standalone screen entry point is gone). Greyed out offline.

`ShoppingHelperScreen` accepts `shoppingViewModel`, `shoppingClosetViewModel`, `wardrobeViewModel`, `gapViewModel`, `profileViewModel`, and `locationViewModel`. The `ShoppingHelperViewModel` name reflects its actual job (the similarity/embedding engine); `ShoppingClosetViewModel` owns the wishlist storage.

### Shopping closet (wishlist storage)

`ShoppingClosetViewModel` is the wishlist counterpart of `WardrobeViewModel`. It owns the `LibreLookAI/_shopping/` Drive folder, persists `wardrobe_cache_{shoppingFolderId}.json` in the same format as a regular closet, and pushes the shopping `folderId` into `WardrobeViewModel.setAllConfiguredLocations(...)` (alongside the configured closets) from `MainActivity` so similarity search and the cross-closet snapshot pick up wishlist items automatically.

The VM mirrors `WardrobeViewModel`'s upload pipeline at a smaller scale — it shares `DriveRepository`, `GeminiRepository`, and `EmbeddingService`, but maintains its own work queue, state (`List<DriveImage>` of shopping items), and per-folder cache. Operations:

- `addFromCamera(rawFile)` / `addFromGallery(uris)` / `addFromUrl(url)` — upload + bg removal + tagging + sidecar, identical to wardrobe.
- `moveToCloset(driveIds, targetFolderId)` — Drive-moves cutout + original + sidecar from `_shopping/` into the target closet folder. Tags are preserved verbatim (no re-tagging on promotion). After the move, the shopping cache is rewritten and `WardrobeViewModel.loadImages()` is asked to refresh the destination closet so the items appear in the regular wardrobe immediately.
- `deleteItems(driveIds)` — same delete path as wardrobe (`DriveRepository.deleteFile`).

No `JobForegroundService` integration in this first pass — wishlist imports are typically one-off and don't need wake-lock protection. If batch URL imports become common we'll wire it up.

The Shopping List sub-tab has its own camera-capture render path: a local `isClosetCapturing: Boolean` flag (not on the VM — `ShoppingClosetUiState` does not duplicate `isCapturing`) gates a `CaptureScreen` render that calls `shoppingClosetViewModel.addFromCamera(file)` on photo-taken. That early-return sits *after* `ShoppingHelperViewModel`'s own `isCapturing` early-return so the two camera paths never collide.

The `ItemSidecar` data class in `WardrobeViewModel.kt` is `internal` (not `private`) so `ShoppingClosetViewModel` can read/write the same sidecar JSON shape — both VMs reuse the wardrobe sidecar format unchanged.

### URL import (Wardrobe + Shopping List)

`WebProductFetcher` (top-level helper, no class) fetches the hero image of a shopping page from a pasted URL. Used by both `WardrobeViewModel.importFromUrl(url, targetFolderId)` and `ShoppingClosetViewModel.addFromUrl(url)`:

1. GET the page with a desktop browser `User-Agent` (some retailers 403 the Java default).
2. Resolve the product image in this priority order: `<meta property="og:image">` → `<meta name="twitter:image">` → JSON-LD `Product.image` (first array element if multiple).
3. Resolve the image URL against the page URL (handles relative `/foo.jpg` and protocol-relative `//cdn/foo.jpg`).
4. GET the image, save to `cacheDir/url_import_{timestamp}.jpg`, return `File`.
5. The caller then runs the standard pipeline (`proceedWithCameraUpload(...)` for wardrobe / equivalent in `ShoppingClosetViewModel`) so the URL-imported item goes through bg removal + tagging + sidecar exactly like a camera capture.

**Coverage tradeoff**: og:image is reliable on Amazon, Zalando, About You, Uniqlo, etc. Pages that render product imagery client-side via JS (some Shopify themes, certain SPAs) won't have meaningful HTML — the fetcher returns `null` and the user sees "Could not find a product image at this URL."

**No HTML library dependency**: parsing is regex-based for og/twitter meta tags and naive substring extraction for JSON-LD. Good enough for the static metadata we need; if this grows we can pull in jsoup later.


### Visual wardrobe search (Similarity Finder)

On-device, zero-network visual similarity search powered by MediaPipe's `ImageEmbedder`. Used by the Shopping → Similarity Finder tab, capture-time duplicate detection, the Wardrobe "Find item by photo" entry point, Repair & Sync's duplicate detection, and the folder-import preview. **Every call site feeds the index with `WardrobeUiState.allLocationImages`** (the cross-closet snapshot — see Closets section) rather than the location-filtered `state.images`, so similarity matches always cover all wardrobes regardless of which closet is active in the UI.

**Entry point**: Shopping tab → Similarity Finder sub-tab (`ShoppingHelperScreen.kt`).

**Shared service**: `EmbeddingService` is a process-wide `object` that owns the only `EmbeddingRepository`, `SegmentationRepository`, and `EmbeddingIndex` instances. `MainActivity.onCreate` calls `EmbeddingService.init(this)` before `setContent` so all consumers see the same MediaPipe handles and the persistent index file. Public API:

- `syncIndex(images, cacheDir, onProgress)` — embed any cutout that has a local file but isn't in the index; drop entries no longer present.
- `findSimilar(file, threshold, excludeIds, topK, segment=true)` — segment + composite-on-white + crop + embed + filter.
- `findSimilarBitmap(bitmap, ...)` — same but for an in-memory bitmap (no segmentation).
- `embedQuery(file, segment=true)` — embed-only, for callers that want to reuse the vector.
- `searchVector(vec, threshold, excludeIds, topK)` — search with an already-computed embedding.

`ShoppingHelperViewModel` no longer constructs its own embedder/segmenter/index; it consumes `EmbeddingService` directly. Future similarity features should follow the same pattern.

**User preferences** for similarity (`UserPreferences`): `dedupeOnImport: Boolean = true` (gate for capture/import duplicate checks), `dedupeThreshold: Float = 0.88f` (single threshold reused across all touchpoints; slider in Settings → Profile, range 0.3–0.95). The default is tuned for the **combined embedding + color-histogram score** (see Scoring below); pure-embedding cosine of 0.85 corresponds roughly to combined 0.88 once the histogram channel agrees.

**Pipeline**:

```
Camera → Bitmap
  → MediaPipe InteractiveSegmenter (Magic Touch, seed = image center)
       → gray-world white-balance correction (background pixels as neutral reference)
       → composite foreground onto opaque white
  → Center-crop to square
  → MediaPipe ImageEmbedder (L2-normalized) ─┐
  → HSV histogram (12×4×4 = 192 bins, H-smoothed) ─┘
  → Combined score: α·cos(emb) + (1−α)·cos(hist), α = 0.65
  → Top-N matches sorted by combined score
```

Cached cutouts (transparent PNGs) skip the segmentation step but go through the same composite-onto-white + center-crop preparation, so query and gallery images share an identical backdrop and aspect ratio before embedding. Gray-world WB only applies at capture time (when a background is available); already-cached cutouts use whatever WB the camera picked when they were originally taken.

**Scoring**: pure CNN embeddings tend to collapse near-uniform garments to texture vectors and wash out hue, so red and black items often look similar. The HSV histogram channel — computed over non-transparent, non-near-white pixels and L1-normalized, then smoothed by a circular Gaussian along the H axis (σ ≈ 1 bin) so adjacent hues bleed together — is mixed in with weight `1 − α = 0.35`. `ColorHistogram.kt` owns the compute/smooth/cosine helpers.

**White balance (gray-world)**: `SegmentationRepository.segmentForegroundOnWhite` uses the segmentation mask twice — once to identify foreground for the composite, once to identify *background* pixels as a neutral-color reference. It computes the per-channel mean of background pixels, derives gains `gainC = meanY / meanC` (where `meanY` is the average of the three channel means), clamps each gain to `[0.5, 2.0]`, and applies them to foreground pixels before compositing on white. Skipped (no correction) if the background covers <5% of the frame, or if the background mean luma is outside `[40, 220]` (too dark or blown out — unreliable reference).

**Components**:
- `EmbeddingRepository` — lazy singleton that holds the MediaPipe `ImageEmbedder`. `embedFile(file)` and `embedBitmap(bitmap)` both return `EmbedResult(vec, hist)` — the L2-normalized embedding and the L1-normalized HSV histogram, computed on the same prepared bitmap so query and gallery preparation match exactly. `prepareForEmbedding(src)` always composites the source onto opaque white before center-cropping — `Bitmap.hasAlpha()` is unreliable across PNG encode/decode cycles, so the composite step runs unconditionally (it's idempotent for fully-opaque inputs). Mutex-guarded; `close()` releases native resources. Embedder is configured with `setL2Normalize(true)` so cosine similarity reduces to a dot product.
- `SegmentationRepository` — lazy singleton holding MediaPipe's `InteractiveSegmenter`. `segmentForegroundOnWhite(bitmap)` runs the model with a center-keypoint seed, infers polarity by picking the *minority* class as foreground (the seeded clothing item should never cover the majority of a centered photo, and trusting the center pixel's value silently fails when the model returns "all background" — the center then inherits the background class and gets misread as foreground, leaving the entire frame untouched), applies gray-world WB to foreground pixels using the background mean as reference, and returns a new bitmap with foreground pixels preserved (and corrected) and background pixels set to opaque white. Returns null if the model asset is missing, the mask is single-class, or the foreground covers <2% of pixels (likely garbage); callers fall back to embedding the un-segmented image. The capture UI shows a center crosshair (`showCenterCrosshair = true`) so the user knows the seed point.
- `ColorHistogram` — pure helper in `ColorHistogram.kt` (top-level functions in `com.librelookai`). `compute(bitmap)` returns a 192-float L1-normalized HSV histogram (skipping alpha < 200 and near-white pixels). `smoothH(hist, sigma)` convolves the H axis with a circular Gaussian. `cosine(a, b)` is the histogram similarity score. Shared by `EmbeddingRepository` and `EmbeddingIndex`.
- `EmbeddingIndex` — in-memory `Map<cutoutDriveId, IndexEntry(vec, hist)>` + binary persistence at `filesDir/wardrobe_embeddings.bin` (format: `magic u32 | version u16 | dim u16 | histDim u16 | count u32 | [idLen u16 | idUtf8 | float32[dim] | float32[histDim]]*`). `VERSION = 3`. Old indexes are discarded on load and rebuilt on next sync. `search(qvec, qhist, topK)` returns matches by the combined score with α = `EMBED_WEIGHT` (0.65).
- `ShoppingHelperViewModel` — holds the repos + index. `syncIndex(images)` walks the current wardrobe, embeds any item with a cached cutout that isn't in the index yet, drops orphaned index entries, and persists. `onCapturedFile(file)` runs the prep pipeline manually (segment → composite-on-white → center-crop) so the post-segmentation bitmap can be saved to `cacheDir/shop_queries/query_processed_*.png` for the debug preview, then embeds the prepared bitmap and searches the index. State exposes `isIndexing` with progress counts, `isMatching`, `queryPath` (raw camera shot), `queryProcessedPath` (the segmented + composited + cropped pixels we actually fed to the embedder), `queryHist` / `queryVec` (the live query's histogram + embedding so the debug view can recompute per-match score breakdowns), `matches: List<ShopMatch(image, score)>`, and `error`. `clearResults()` deletes both the raw and processed query files.
- `ShoppingHelperScreen` — three visual states: (a) capture prompt with a big camera FAB, (b) inline `CaptureScreen` (reuses the existing one) with the center crosshair enabled, (c) result — query thumbnail up top + ranked list of wardrobe items with similarity bars. Header shows the `LocationButton` (same as other screens). Everything runs locally, so the shop tab is fully functional offline.
- `MatchPreviewDialog` (debug preview) — opened by tapping any match row. Pages horizontally over the full match list (`HorizontalPager`); on each page shows: the **combined score** with the embedding-cosine and histogram-cosine broken out, the query side-by-side as **raw** vs. **processed** (segmented + composited-on-white + center-cropped), the match side-by-side as **raw cutout** (transparent PNG) vs. **processed** (composited-on-white + center-cropped — the exact transform `EmbeddingService.syncIndex` applied when generating the embedding), the **query and match hue histograms** (12 H bins, summed across S × V, colored by hue) so it's obvious when a high embedding score sits on a wildly different color, and a zoomable copy of the cutout. Match histograms are pulled lazily from `EmbeddingService.index.entry(driveId)?.hist` via `produceState` per page; match processed bitmaps are computed on the fly from `EmbeddingRepository.prepareForEmbedding(cutout, compositeAlpha=true)`. Swipe left/right to step through matches.

**Indexing strategy**: No hooks in `WardrobeViewModel.processQueue`. Indexing is pull-based — the shop screen syncs the index every time it opens, and before every match. Empirically this takes <1s for ~100 items on modern devices; subsequent opens are near-instant since only new/deleted items cause work.

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
