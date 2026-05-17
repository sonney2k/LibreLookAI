# CLAUDE_ARCHIVE.md

Detailed architecture, pipelines, and design decisions for LibreLookAI. The compact day-to-day guidance lives in `CLAUDE.md`; this file holds the deeper "why" — rationale, fallbacks, and non-obvious behaviors that aren't visible from the code alone.

---

## Architecture rationale

### ViewModel / Repository layout

```
UI (Compose) ↕ StateFlow ↕ AndroidViewModel ↕ Repository
```

Repositories used: `DriveRepository`, `GeminiRepository`, `WeatherRepository` (Open-Meteo, no key), `BillingRepository` (Play Billing 7.x), `CreditRepository` (Firestore + Cloud Functions). All ViewModels extend `AndroidViewModel`; repositories are constructed inline (no DI framework).

`OutfitsViewModel` owns saved outfits + composer + editor. `OutfitEventsViewModel` is a separate VM owning calendar wear events (`OutfitEvent` — timestamped record of which outfit was worn). Both persist per-location JSON to Drive and are kept in sync by a single `LaunchedEffect(activeLocationId, locationList)` in `MainActivity`.

### Storage strategy

- **Scope**: `drive.file` only (app-private folder `LibreLookAI/`). Local cache at `context.filesDir/wardrobe/`.
- **Sidecars**: each item has `{cutoutDriveId}.json` next to its cutout, holding `ClothingTags` + `originalDriveId`. `SYSTEM_JSON_NAMES` in `DriveRepository` excludes well-known files from sidecar handling: `_wardrobe_metadata.json`, `_outfits.json`, `_outfit_events.json`, `_user_preferences.json`, `_locations.json`, `_tryons.json`, `_token_usage.jsonl`, plus legacy fallbacks `_styles_metadata.json` (old `_outfits.json`) and `_outfits_metadata.json` (old `_outfit_events.json`). Loaders read the current name first, fall back to the legacy one; writes always use the current name.
- **Two-phase load**: Phase 1 shows the local cache instantly (zero network). Phase 2 fetches Drive listing + sidecars in parallel and rebuilds `wardrobe_cache_{folderId}.json`. `_wardrobe_metadata.json` is read as a one-time fallback when no sidecars exist; items are migrated fire-and-forget on first load.
- **Closets**: each closet is a Drive subfolder. The UI label is "Closets" (not "Locations") to separate organization from geography. `Location.geoLocation` carries an optional city/place for weather. All closet matching uses `folderId`; `Location.id` is an ephemeral UUID and must never be used for identity. `LocationViewModel` persists the active `folderId` in SharedPreferences. `ALL_LOCATIONS_ID` toggles cross-folder load+merge in all three VMs and is only available in the global header dropdown (not in Settings).
- **Cross-closet snapshot**: `WardrobeUiState.allLocationImages` is a merged `List<DriveImage>` read directly from every per-folder cache (cached cutouts only, no network), independent of the active-closet filter. **The `_shopping/` folder is included** so similarity search finds wishlist items too. Re-derived after every `saveLocalCache` write so it tracks new/removed/retagged items. Every similarity-search call site (Find by photo, capture/import dedupe, Similarity Finder, Repair duplicate detection) reads from this snapshot.
- **Shopping closet**: `LibreLookAI/_shopping/` mirrors a regular closet's layout (cutout/original/sidecar triplet, same naming) so existing helpers work without special-casing. It is **not** a `Location` — never appears in `LocationButton`, Outfits, Calendar, Insights, or Settings → Closets. `DriveRepository.getOrCreateShoppingFolder(rootId)` returns the folder; `SHOPPING_FOLDER_NAME = "_shopping"` must be skipped when listing closets.
- **Local prefs**: `ApiKeyStore` (SharedPreferences) holds the user-supplied Gemini key. `ProfileViewModel` caches the UI language in a separate `librelookai_lang` SharedPreferences so the locale is available synchronously on app start, **before** Drive preferences load — this prevents an English flash on launch and keeps the UI in the correct language while offline.

### Gemini routing

`GeminiRepository.buildRequest()`:
1. **BYOK**: user key (`ApiKeyStore`) or `BuildConfig.GEMINI_API_KEY` → direct `generativelanguage.googleapis.com` with `?key=`.
2. **Managed/proxy**: no local key + `PROXY_BASE_URL` set → POST to Cloud Function `geminiProxy` with `Authorization: Bearer <Firebase ID token>` + `X-AI-Action` + `X-Gemini-Model`. The function deducts credits, calls Gemini server-side, refunds on error.

All Gemini calls return `null` on failure — every caller must degrade gracefully.

**Image resizing**: `readAndResizeBase64()` enforces `max(width, height) ≤ 1280`. Within bounds → raw bytes (no re-encode). Larger → decode, scale, re-encode (JPEG 95 for photos, PNG lossless for cutouts). `CaptureScreen` also crops to the viewfinder aspect ratio before saving.

### Credit model (managed mode)

- `CreditPack.kt` — SKU constants (`credits_100/500/2000`) and per-action costs.
- `BillingRepository` — Play Billing wrapper; exposes `purchaseUpdates: SharedFlow`.
- `CreditRepository` — Firestore balance via `callbackFlow` (`Flow<Int>`); `verifyPurchase()` calls the Cloud Function.
- `CreditsViewModel` — orchestrates billing + credits; processes pending purchases on resume.
- `BuyCreditsScreen` — Settings → Credits tab when `isManagedMode = true`.

### Navigation shell

Single `selectedTab: Int` in `MainActivity`; no Navigation component. Tabs: `0=Outfits, 1=Wardrobe, 2=Shopping, 3=Travel, 4=Insights`. Settings (tab 5) is reachable only via the gear in `AppScreenHeader`. Calendar is a sub-tab inside Insights, not a top-level tab.

**Sub-tab reset on nav**: every nav action (bottom-nav tap, re-tap, gear) increments `navResetTick: Int` in `MainActivity`. Screens with sub-tabs (`OutfitListScreen`, `ShoppingHelperScreen`, `InsightsScreen`, `SettingsScreen`) accept it and `LaunchedEffect(navResetTick) { selectedSubTab = 0 }` so any nav-button tap lands on the screen's default sub-tab regardless of where the user left it.

**Header**: every main screen uses `AppScreenHeader` (in `MainActivity.kt`) — leading icon, `titleMedium/SemiBold`, optional trailing slot (typically `LocationButton` — `DoorSliding` icon, only visible with 2+ closets), `HorizontalDivider`. Closet selection is global; `OutfitListScreen` does additional client-side filtering by `folderId` because outfits always load from all locations.

### Localization

`AppLanguage.toLocale()` / `toGeminiName()` convert the stored enum. The active locale is applied by wrapping the tree in `CompositionLocalProvider(LocalContext provides localizedContext)` — no Activity restart on language change. Strings live in `values/strings.xml` and `values-de/strings.xml`; new strings must be added to both at the same time.

---

## Photo upload & ingestion pipelines

### Wardrobe upload flow

1. `WardrobeViewModel.uploadPhoto(rawFile)` uploads the raw JPEG and enqueues a `PendingJob`. The initial `DriveImage` must include `folderId = targetFolderId` so the closet label appears immediately in the grid (same for `uploadGalleryPhotos`).
2. **Pre-upload similarity gate**: if `dedupeOnImport` is on and the embedder is present, `EmbeddingService.findSimilar(rawFile, threshold=dedupeThreshold, segment=true)` runs first. Hits → upload pauses, `WardrobeUiState.duplicateCheck` set, `DuplicateCheckSheet` opens. User picks **Import anyway** (`confirmDuplicateImport`) or **Cancel** (`cancelDuplicateImport`, deletes the raw file). The index is synced before the search so freshly-added items aren't missed. Gallery uploads bypass this gate (multi-select would block too aggressively); folder-import preview handles bulk dedupe instead.
3. `processQueue()` drains serially: bg removal (Gemini or local cutout via `prebuiltCutoutPath`) → upload cutout (renamed `{id}_cutout.png`) → copy local original cache → upload original (renamed `{cutoutId}_original.jpg`) → delete raw → classify tags → write sidecar.
4. The local `{driveId}_original.jpg` cache copy must happen **before** `deleteFile()` — `DriveRepository.deleteFile()` also wipes the local `_original.jpg`.
5. State updates match on **either** the raw or cutout Drive ID to handle the race where `loadImages()` already placed the item with the cutout ID.
6. **Closet picker**: with 2+ closets, target is chosen differently per import method. **Camera**: `CaptureScreen` shows an inline `ClosetChip` (semi-transparent pill) in the top-right of the viewfinder and top-center of review; tap → `DropdownMenu`, no extra dialog step. **Gallery / URL**: FAB shows an `AlertDialog` with radio buttons (and the URL field for URL imports). `WardrobeUiState.importTargetFolderId` exposes the current target; `setDefaultImportFolderId()` updates both the private field and UI state. With one closet, both UIs hide.
7. **URL import**: third FAB (`Icons.Default.Link`) → `UrlImportDialog` → `setDefaultImportFolderId(...)` (when 2+ closets) → `WardrobeViewModel.importFromUrl(url)`. The VM resolves the target folder from `defaultImportFolderId ?? folderId`, fetches the image via `WebProductFetcher`, then calls `uploadPhoto(image)` so the URL-imported file goes through the same dedupe + bg-removal + tagging + sidecar pipeline as a captured photo. Shopping List has the same FAB but routes through `ShoppingClosetViewModel.addFromUrl(url)`.

### URL fetching (`WebProductFetcher`)

Top-level helper, no class. Used by both wardrobe and shopping URL imports.

1. GET with a desktop-browser `User-Agent` (some retailers 403 the JVM default).
2. Resolve the product image in priority order: `<meta property="og:image">` → `<meta name="twitter:image">` → JSON-LD `Product.image` (first array element if multiple).
3. Resolve relative URLs (`/foo.jpg`) and protocol-relative URLs (`//cdn/foo.jpg`) against the page URL.
4. GET the image, save to `cacheDir/url_import_{ts}.jpg`, return the `File`.
5. Caller runs the standard pipeline so the URL-imported item gets bg removal + tagging + sidecar exactly like a camera capture.

**Coverage tradeoff**: og:image is reliable on Amazon, Zalando, About You, Uniqlo, etc. Pages that render imagery client-side (some Shopify themes, certain SPAs) won't have meaningful HTML — fetcher returns `null` and the user sees "Could not find a product image at this URL."

**No HTML library**: regex-based for og/twitter meta tags, naive substring extraction for JSON-LD. Good enough for static metadata; if it grows, pull in jsoup later.

### SAF / Drive folder import

`importFromFolder(treeUri)` (SAF) and `importFromDriveFolder(sourceFolderId)` share a three-stage pipeline:

1. **Enumerate** (`enumerateSafSources` / `enumerateDriveSources`) — produce `ImportPreviewEntry` records, one per source image, with a stable per-entry cache file path. SAF entries also carry `srcMetaTags` / `srcMetaOriginalDriveId` from any sibling `_wardrobe_metadata.json`.
2. **Branch** (`kickoffImport`) — when `dedupeOnImport` is on, sync the index, run `findSimilar` per entry, and pause with `WardrobeUiState.importPreview` set so `ImportPreviewDialog` can render. Entries without a hit start selected by default; entries with hits start unticked. Off → straight to step 3.
3. **Run** (`runImportEntries`) — the per-entry upload + bg-removal + tagging loop on already-cached files. `confirmImportPreview()` runs selected entries; `cancelImportPreview()` skips. Both delete the unused cache files and release the foreground-service wake lock.

The Repair & Sync wake lock is released by `importFromFolder`'s `finally` only when `importPreview == null` (fast path); the preview path keeps it alive until confirm/cancel so the foreground notification stays up while the user reviews.

### Background job protection

Long-running wardrobe ops are protected by two reference-counted mechanisms in `WardrobeViewModel`:

- **`JobForegroundService`** — started when the first job begins, stopped when the last ends. Promotes the app to foreground priority. Notification channel `librelookai_jobs` (low importance). `android:stopWithTask="false"` survives swipe-from-recents; returns `START_STICKY` so Android restarts it under memory pressure.
- **`PARTIAL_WAKE_LOCK`** (`LibreLookAI:Jobs`) — keeps CPU running with screen off. 30-minute safety timeout.
- **Battery optimization exemption** — on first job, `acquireJobWakeLock()` checks `PowerManager.isIgnoringBatteryOptimizations()`. If not exempt, `WardrobeUiState.needsBatteryExemption = true` and `GridContent` shows a one-shot `AlertDialog` linking to `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Required on Samsung/Xiaomi/etc. that aggressively kill foreground services.

Both service and wake lock use `AtomicInteger` ref counts via `acquireJobWakeLock()` / `releaseJobWakeLock()`. Covered ops: `processQueue`, `importFromFolder`, `importFromDriveFolder`, `removeAllBackgrounds`, `retagAll`, both phases of Repair & Sync. Every caller wraps its body in `try/finally` to guarantee release.

---

## Repair & Sync

`WardrobeViewModel.startRepairAndRefresh(folderIds)` runs a multi-phase audit across all closet folders. Foreground service + wake lock held throughout. All actions logged to logcat under tag `RepairAndSync`.

1. **File scan** — `DriveRepository.listAllImageFiles()` returns every image. Cutouts with wrong names are renamed in-place. Originals whose prefix doesn't match a cutout's Drive ID are flagged orphaned. For each cutout that has a sidecar, its content is parsed — if `tags` is null (`{}` or `{"tags":null}`), the cutout is flagged for re-tagging just like a missing sidecar.
2. **Duplicate detection** — `EmbeddingService.syncIndex` embeds any cached cutout missing from the index, then `findDuplicateClusters(dedupeThreshold, restrictToIds=current_wardrobe)` returns each cutout that has a peer with cosine ≥ threshold. Each becomes an `AuditFileEntry` with `kind = AuditKind.DUPLICATE`, `similarTo`, `topScore`. Cutouts not in the local cache are skipped (next wardrobe load picks them up).
3. **Confirmation** — `WardrobeUiState.auditProgress` enters `awaitingConfirmation` and exposes `items: List<AuditFileEntry>` + `selectedAuditIds`. Default selection includes every fix-needed entry (orphaned/raw/needs-sidecar) but **excludes `DUPLICATE`** — selecting a duplicate means delete-it (destructive), so the user must opt in. `RepairPreviewDialog` (Settings) renders a wardrobe-style 96 dp adaptive grid in four sections (Orphaned originals / Raw images / Cutouts needing tags / Possible duplicates). Duplicates get a `Similar` badge; the credit-cost line ignores duplicates (deletion is free).
4. **Process** (`continueRepairProcessing(process, clearCache)`) — only items in `selectedAuditIds`: orphaned originals get full AI processing (bg removal + tagging + sidecar); cutouts missing/empty sidecar get tagging from the cutout image + sidecar upsert; selected duplicates have cutout + original + sidecar deleted from Drive and the embedding index entry removed. Deselect-all is treated as "skip" (reload only).
5. **Refresh** — `loadImages()` reloads. The local cache wipe (forcing every cutout/original to re-download) only fires when "Clear local cache" is ticked in the preview dialog (default off). `auditProgress.isDone` signals completion.

---

## Visual wardrobe search (Similarity Finder)

On-device, zero-network visual similarity search powered by MediaPipe's `ImageEmbedder`. Used by the Shopping → Similarity Finder tab, capture-time + import-time dedupe, Wardrobe → Find by photo, Repair duplicate detection.

Every call site feeds the index from `WardrobeUiState.allLocationImages` (the cross-closet snapshot, including shopping items) so matches always span every wardrobe regardless of which closet is active.

### Shared service

`EmbeddingService` is a process-wide `object` owning the only `EmbeddingRepository`, `SegmentationRepository`, and `EmbeddingIndex` instances. `MainActivity.onCreate` calls `EmbeddingService.init(this)` before `setContent` so all consumers see the same MediaPipe handles and the persistent index file. Public API:

- `syncIndex(images, cacheDir, onProgress)` — embed any cutout that has a local file but isn't in the index; drop entries no longer present.
- `findSimilar(file, threshold, excludeIds, topK, segment=true)` — segment + composite-on-white + crop + embed + filter.
- `findSimilarBitmap(bitmap, ...)` — same, in-memory, no segmentation.
- `embedQuery(file, segment=true)` — embed-only.
- `searchVector(vec, threshold, excludeIds, topK)` — search with a precomputed embedding.
- `findDuplicateClusters(threshold, restrictToIds)` — used by Repair.

Future similarity features should consume `EmbeddingService` directly (do not re-instantiate the embedder/segmenter/index).

### Pipeline

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

Cached cutouts (transparent PNGs) skip segmentation but go through the same composite-on-white + center-crop, so query and gallery share an identical backdrop and aspect ratio before embedding. Gray-world WB only applies at capture time; cached cutouts use whatever WB the camera picked when originally taken.

### Scoring rationale

Pure CNN embeddings collapse near-uniform garments to texture vectors and wash out hue — red and black items often look similar. The HSV histogram channel (computed over non-transparent, non-near-white pixels, L1-normalized, then smoothed by a circular Gaussian along H with σ ≈ 1 bin so adjacent hues bleed) is mixed in with weight `1 − α = 0.35`. `ColorHistogram.kt` owns the helpers.

Default threshold `dedupeThreshold = 0.88` is tuned for the **combined** score; pure-embedding cosine of 0.85 corresponds roughly to combined 0.88 once the histogram channel agrees. Slider in Settings → Profile, range 0.3–0.95.

### White balance (gray-world)

`SegmentationRepository.segmentForegroundOnWhite` uses the segmentation mask twice — once to identify foreground for the composite, once to identify *background* pixels as a neutral-color reference. Per-channel mean of background → gains `gainC = meanY / meanC` (where `meanY` is the average of the three channel means) → clamp `[0.5, 2.0]` → apply to foreground before compositing on white.

Skipped when background covers <5% of the frame, or background mean luma is outside `[40, 220]` (too dark or blown out — unreliable reference).

### Polarity inference

The seed-pixel approach silently fails when the model returns "all background" — the center pixel inherits the background class and gets misread as foreground, leaving the whole frame "untouched." Instead, polarity is inferred by picking the **minority class** as foreground: a centered clothing item should never cover the majority of the frame. Returns null if the mask is single-class or the foreground covers <2% of pixels (likely garbage); callers fall back to embedding the un-segmented image.

### Components

- `EmbeddingRepository` — lazy singleton holding `ImageEmbedder`. `embedFile(file)` and `embedBitmap(bitmap)` both return `EmbedResult(vec, hist)` computed on the same prepared bitmap so query/gallery preparation match exactly. `prepareForEmbedding(src)` always composites onto opaque white before center-crop — `Bitmap.hasAlpha()` is unreliable across PNG encode/decode cycles, so the composite runs unconditionally (idempotent for opaque inputs). Mutex-guarded; `close()` releases native resources. Embedder configured with `setL2Normalize(true)` so cosine reduces to a dot product.
- `SegmentationRepository` — lazy singleton holding `InteractiveSegmenter`. `segmentForegroundOnWhite(bitmap)` runs the model with a center-keypoint seed, applies the polarity rule, applies gray-world WB, returns a new bitmap with foreground preserved (and corrected) and background opaque white. Capture UI shows a center crosshair (`showCenterCrosshair = true`) so the user sees the seed point.
- `ColorHistogram` — pure helper, top-level. `compute(bitmap)` → 192-float L1-normalized HSV histogram (skipping alpha < 200 and near-white). `smoothH(hist, sigma)` convolves H with a circular Gaussian. `cosine(a, b)` for similarity.
- `EmbeddingIndex` — in-memory `Map<cutoutDriveId, IndexEntry(vec, hist)>` + binary persistence at `filesDir/wardrobe_embeddings.bin`. Format: `magic u32 | version u16 | dim u16 | histDim u16 | count u32 | [idLen u16 | idUtf8 | float32[dim] | float32[histDim]]*`. `VERSION = 3`. Old indexes are discarded on load and rebuilt on next sync.
- `MatchPreviewDialog` (debug) — `HorizontalPager` over the full match list. Each page shows: combined score with embedding-cosine and histogram-cosine broken out; query side-by-side as **raw** vs. **processed**; match side-by-side as **raw cutout** vs. **processed**; query and match hue histograms (12 H bins, summed across S × V, colored by hue) so it's obvious when a high embedding score sits on a wildly different color; a zoomable cutout copy. Match histograms pulled lazily from `EmbeddingService.index.entry(driveId)?.hist` via `produceState`. Gated by `UserPreferences.debugSimilarityPreview`.

**Indexing strategy**: pull-based — no hooks in `processQueue`. The shop screen (and any caller) syncs the index on demand. Empirically <1 s for ~100 items on modern devices; subsequent syncs are near-instant since only new/deleted items cause work.

### Wardrobe Find-by-photo

Header icon (`Icons.Default.ImageSearch`) opens the camera in `WardrobeView.FIND_BY_PHOTO_CAPTURE` mode (reuses `CaptureScreen` with the center crosshair). After accept, `onFindByPhotoCaptured` runs `EmbeddingService.findSimilar` against `state.allLocationImages` with threshold `-1f` so all top-K matches surface regardless of similarity. Results land in `WardrobeUiState.findByPhoto`; `GridContent` renders `FindByPhotoResultsSheet` (modal bottom sheet) with the query thumbnail + adaptive grid.

Tapping a match clears active tag filters and — if the match lives in a different closet — switches the active closet. The Drive ID is stashed in `pendingScrollDriveId`; a `LaunchedEffect` keyed on `displayedImages` calls `gridState.animateScrollToItem(idx)` and pulses a brief primary-coloured ring around the matched tile (cleared after ~2 s). The full-screen viewer is **not** opened — the user lands on the matched item in the grid and chooses what to do next. Available offline (cached cutouts only).

---

## Outfits, Travel, Try-On

### Outfits screen

`OutfitsScreen` renders `OutfitListScreen` directly (sub-tabs: **Outfits** with `OutfitCard`, sort, tag filters, selection mode, FABs; **Try-Ons** grid from `tryOnViewModel.state.history`). All outfit view/edit/create is funneled through the unified composer (see below). Tap an outfit → `startEditing` → composer in `VIEW` mode. FAB → `onOpenCreateComposer` → `openComposer(seedItemIds=empty)` → composer in `EDIT` mode with 5 empty default slots.

`OutfitListScreen` supports multi-select: long-press → `selectedOutfitIds.isNotEmpty()`. Selection bar (count / select-all / deselect) replaces sort; card Edit+Wear hide; the speed-dial FAB is replaced by:
- **Delete** — confirmation → `deleteSelectedOutfits()`. Groups by `Outfit.folderId` and saves each `_outfits.json` independently.
- **Combine with AI** (≥ 2 selected) — `openComposerFromSelectedOutfits()` opens the composer in `EDIT` mode seeded with the union, prefilled with a "NameA + NameB" name.

The "Find with AI" prediction flow (`openPredictionSetup(source = OUTFITS_LIST)`) shows its 1–3 ranked picks in `OutfitFullScreenViewer` overlaid on the list; tapping Edit there clears the prediction and opens the composer in VIEW mode for that outfit.

### Unified outfit composer

`OutfitComposerScreen` (full-screen Dialog hosted at `MainActivity`) is the single surface for viewing, editing, and creating outfits. Renders unconditionally; returns early unless `state.isComposerOpen`. Dual-mode via `composerMode: ComposerMode { VIEW, EDIT }`.

**Slot model** (UI-only, never persisted):
- `OutfitSlot { id: UUID, category: Layer, selectedItemId: String?, isLocked: Boolean, aiReason: String? }` in `outfit/OutfitSlot.kt`.
- `Layer` enum (Outerwear / Top / Bottom / Footwear / Accessory) lives in the same file; `layerFor(DriveImage)` classifies items by tag category regex.
- `openComposer` seeds slots: empty seed → 5 default slots (one per Layer, empty, unlocked); non-empty seed → one slot per item, `isLocked = true` (manual picks auto-lock).
- Editing an existing outfit (`editingStyleId != null`) → opens in `VIEW`; everything else → `EDIT`.
- On save, slots flatten to `Outfit.itemIds = slots.mapNotNull { it.selectedItemId }`. `Outfit` model unchanged.

**View mode**: read-only `SlotCard` list (silhouette `Layer.iconRes` for empty, image otherwise). Header has Edit toggle.

**Edit mode** (per `SlotCard`): lock toggle on filled slots, exchange button (opens `AddItemSheet` filtered by `slot.category`), remove button. "+ Add slot" at bottom opens `CategoryPickerSheet`. Sticky bottom bar: **Generate with AI** (opens `PredictionSetupDialog` with `source = COMPOSER`) + **Save** (calls `prepareSave()`).

**AI generation**: `enhanceComposerWithAi` passes a per-slot constraint list to Gemini. Locked-and-filled slots are passed as fixed (`requiredItemId`); empty or unlocked slots are open. Gemini returns `{slots: [{slotId, itemId}], name, description, tags, reason}`. The viewmodel applies returned `itemId` only to slots that are empty OR unlocked; locked-filled slots are untouched. Suggested name/description/tags are stashed in `composerAiSuggestedName/Description/Tags` for the Save dialog pre-fill.

**Deferred save**: `prepareSave()` opens `SaveOutfitDialog` (AlertDialog with name / description / tags chip editor). Pre-fills: editing → existing values; AI-generated → AI suggestions; manual → empty. Confirm → `commitOutfit(name, desc, tags)` writes to Drive and closes the composer. Cancel returns to edit mode with state intact. Dirty back tap shows `DiscardChangesDialog`.

**Reused composables** (kept in `OutfitComposerScreen.kt`, internal so other screens can import): `ContextStrip`, `WeatherPickerSheet`, `ClosetPickerSheet`, `AddItemSheet`, `OutfitTagsEditor`. `PredictionSetupDialog` (`PredictionSetupScreen.kt`) imports these and is reused both for "Find with AI" on the Outfits list and "Generate with AI" inside the composer — the `predictionSetupSource` state field routes the submit.

**ViewModel state** (`OutfitsUiState`, new + relevant fields): `isComposerOpen`, `composerMode`, `composerEditingOutfitId`, `composerSlots`, `composerItemIds` (legacy mirror of slot itemIds; some external call sites still read it), `composerWeatherMode`, `composerManualSeason/TempC/Precip`, `composerVibes`, `composerName/Description/Tags`, `composerAiSuggestedName/Description/Tags`, `composerFeedback/History`, `composerReason`, `composerSourceFolderIds`, `isComposerEnhancing`, `composerError`, `isSaveDialogOpen`, `isPredictionSetupOpen`, `predictionSetupSource: PredictionSetupSource { OUTFITS_LIST, COMPOSER }`.

**Prompt builders** (private top-level in `OutfitsViewModel.kt`):
- `buildPredictionPrompt` — pick best existing outfit for today.
- `buildCompositionPrompt` — compose a brand-new outfit from the wardrobe.
- `buildAlternativesPrompt` — up to 10 swap alternatives for one item, with the rest of the outfit as context.
- `buildComposerPrompt` — unified composer prompt; the multi-outfit "combine" flow reuses this (seeding with the union of items is equivalent).
- `buildReplacementsPrompt` (in `WardrobeGapViewModel`) — frames `remaining = all − selected` so suggestions complement the kept wardrobe.

`saveOutfitDirectly(name, description, itemIds, onDone)` is internal; only `saveComposer()` calls it.

### Naming legacy ("style" → "outfit")

User-facing terminology and new code use "outfit". Identifiers still reading "style" for legacy reasons: VM parameter name in downstream screens (`stylesViewModel: OutfitsViewModel`), state fields holding the saved list (`styles`, `OutfitPrediction.styleId`), and all `values/strings.xml` keys (`styles_empty`, `styles_sort_newest`, …). `OutfitEvent.outfitId` is serialized but accepts the legacy `styleId` via `@SerializedName(alternate)`. Prefer "outfit" in new code; leave existing legacy identifiers alone until a dedicated cleanup pass.

### Outfit wear events

`OutfitEventsViewModel` reads `_outfit_events.json` per location (legacy fallback `_outfits_metadata.json`) and caches as `outfit_events_cache_{folderId}.json`. `OutfitEvent.outfitId` (JSON `outfitId`, alternate `styleId`) + ISO `date`.
- `setLocation(folderId)` / `setAllLocations(folderIds)` — synced by `MainActivity`'s `LaunchedEffect`. All Locations mode loads + merges from every folder.
- `recordOutfit(outfitId)` — appends an event dated today. In All Locations mode, the write goes to the first configured folder.

### Travel packing

`TravelScreen` receives `travelViewModel`, `wardrobeViewModel`, `profileViewModel`, `stylesViewModel: OutfitsViewModel` (legacy parameter name), `locationViewModel`.

destination + date range → `WeatherRepository.fetchDestinationForecast()` → `buildPackingPrompt()` → Gemini → `PackingList` (list of `PackingOutfit` + `extraItems`). Refinement loop (free-text + preset chips) re-runs `buildPackingPrompt()` with accumulated `feedbackHistory`.

**`PackingOutfit` cards** show a "Save as outfit" `InputChip` → opens the unified composer prefilled with the outfit's items, occasion (as name), and description.

**"Move all to Travel location"** appears in the packing-list header when there are packed items:
1. `locationViewModel.getOrCreateLocation("Travel")` — finds an existing Travel location (case-insensitive) or creates a new Drive subfolder + updates the locations JSON, returns the folderId via callback.
2. `wardrobeViewModel.moveItemsToFolder(itemIds, toFolderId)` — moves each item's cutout + original + sidecar via `DriveRepository.moveFile()` (single PATCH, no re-upload), drops moved items from in-memory state.
3. Snackbar confirms.

### Try-on (unified)

`TryOnViewModel` + `TryOnComposerScreen` form the unified flow. The old `TryOnResultDialog` is gone — Wardrobe-selection "Try on" FAB and the single-outfit "Try on" FAB both call `tryOnViewModel.openComposer(itemIds)` via the shared `runTryOn` lambda in `MainActivity`.

Three modes (`TryOnComposerScreen` checks in order):
1. **Detail view** (`state.viewingTryOn != null`) — zoomable past try-on + resolved item chips + Delete.
2. **History grid** (`state.isHistoryOpen`) — saved try-ons; tap → detail.
3. **Compose / Preview** (default) — no `resultPath`: item grid (tap to remove, `+` via `ItemPickerSheet`), explanation surface, "Generate try-on" button. With result: pinch-zoom preview (`detectTransformGestures`, scale 1f..6f) + Save to Drive / Try again / Change items / Save to gallery.

**Drive layout**: PNGs in `_tryons/` subfolder of the root. Index `_tryons.json` at the root holds `List<TryOn>`. `TryOn` stores `imageDriveId` (direct access) + `imageName` (stable handle across Drive folder copies), plus `itemNames` (cutout filenames — resolved back to current Drive IDs at load time by matching against `wardrobeImages`). `TRYONS_FILE_NAME` is in `SYSTEM_JSON_NAMES`.

**Caching**: saved try-on PNGs at `cacheDir/tryon_{driveId}.png`, re-downloaded via `DriveRepository.downloadFileTo(...)` on history load if missing. Pre-save result lives at `cacheDir/tryon_results/tryon_{ts}.png`; on save it's copied to the stable cached path.

**Save flow** (`saveCurrent`): ensures root, uploads PNG to `_tryons/`, records a `TryOn`, prepends it to `_tryons.json`, writes the updated list. `lastGeneratedItemIds/Files` captures which items were actually sent to Gemini for the current result, so Save reflects the generation's inputs even if the user edited the composer afterward.

`TryOnHistoryGrid` is `internal` so it can be reused inline by the Outfits → Try-Ons sub-tab.

---

## Shopping closet (wishlist storage)

`ShoppingClosetViewModel` is the wishlist counterpart of `WardrobeViewModel`. Owns the `LibreLookAI/_shopping/` folder, persists `wardrobe_cache_{shoppingFolderId}.json` in the same format, and pushes the shopping `folderId` into `WardrobeViewModel.setAllConfiguredLocations(...)` (alongside configured closets) from `MainActivity` so similarity search and the cross-closet snapshot pick up wishlist items.

Mirrors the wardrobe upload pipeline at smaller scale — shares `DriveRepository`, `GeminiRepository`, `EmbeddingService`. Operations:
- `addFromCamera(rawFile)` / `addFromGallery(uris)` / `addFromUrl(url)` — upload + bg removal + tagging + sidecar.
- `moveToCloset(driveIds, targetFolderId)` — Drive-moves cutout + original + sidecar from `_shopping/` into the target. Tags preserved verbatim (no re-tagging on promotion). After move, shopping cache is rewritten and `WardrobeViewModel.loadImages()` refreshes the destination so items appear immediately.
- `deleteItems(driveIds)` — same delete path as wardrobe.

No `JobForegroundService` integration in this first pass — wishlist imports are typically one-off. Wire up if batch URL imports become common.

The Shopping List sub-tab has its own camera-capture render path: a local `isClosetCapturing: Boolean` (not on the VM — `ShoppingClosetUiState` does **not** duplicate `isCapturing`) gates a `CaptureScreen` that calls `shoppingClosetViewModel.addFromCamera(file)`. This early-return sits **after** `ShoppingHelperViewModel`'s own `isCapturing` early-return so the two camera paths never collide.

The `ItemSidecar` data class in `WardrobeViewModel.kt` is `internal` (not `private`) so `ShoppingClosetViewModel` can read/write the same sidecar JSON shape — both VMs reuse the wardrobe sidecar format unchanged.

---

## Insights tab (Calendar + Stats)

`InsightsScreen.kt` (tab 4, `Icons.Default.Insights`) renders `AppScreenHeader` + `TabRow` with four sub-tabs:

1. **Calendar** — month-grid of past wear events. Grid composable, day-cell layout, day-detail bottom sheet, `OutfitSheetRow` all live inline (no separate `CalendarScreen.kt`). "Edit" on an outfit calls back to `MainActivity`, which switches to tab 0 and opens the editor via `stylesViewModel.startEditing(...)`.
2. **Calendar Stats** — top-N most-worn outfits + most-worn items, computed from `OutfitEventsViewModel` + `OutfitsViewModel` + `WardrobeViewModel` state.
3. **Wardrobe Stats** — per-tag-category counts and untagged tally (formerly the BarChart sheet on the Wardrobe header). Uses `internal` `tagCategoryCounts()` / `tagCategoryDisplayLabel()` from `WardrobeScreen.kt`.
4. **Costs** — `UsageSection` (token spend) + headline activity counts + 14-day daily count bar charts for imports (≈ BG_REMOVAL calls), outfits created (`Outfit.createdAt`), try-ons (`TryOn.createdAt`), wear events.

Selected sub-tab is `rememberSaveable`. `LocationButton` lives on the header (above `TabRow`) and applies globally.

---

## Offline mode (deep notes)

`NetworkMonitor` (in `NetworkUtils.kt`) tracks the **set** of networks reporting `INTERNET + VALIDATED` via `registerNetworkCallback(NetworkRequest)`. `isOnline` is `true` iff the set is non-empty. Three callbacks (`onAvailable`, `onLost`, `onCapabilitiesChanged`) funnel through one `recomputeOnline()` that re-reads connectivity rather than re-querying `cm.activeNetwork` (which races during the offline transition and can leave the flow stuck at `true`).

Online status requires **both** `NET_CAPABILITY_INTERNET` **and** `NET_CAPABILITY_VALIDATED` — checking only `INTERNET` misses the common case where wifi stays associated but the uplink is dead (Android does not fire `onLost` for that, only `onCapabilitiesChanged` with `VALIDATED` dropped).

`MainActivity` instantiates the monitor with `remember { ... }` and pairs it with a `DisposableEffect` that calls `networkMonitor.unregister()` on dispose. State is provided via `LocalIsOffline` (a `CompositionLocal` in `NetworkUtils.kt`) — no parameter threading. `MainActivity` also calls `networkMonitor.recheck()` on `Lifecycle.Event.ON_RESUME` as a backstop for callbacks the framework may drop while backgrounded (flight-mode toggles etc.).

**UI indicator**: animated banner (`errorContainer`, `CloudOff`, localized) at top of content when offline; disappears on reconnect.

**What's disabled offline** (everything that writes to Drive or calls Gemini):
- WardrobeScreen: upload FABs (camera/gallery/URL); selection-mode FABs (Create outfit, Compose with AI, Move to closet, Delete, Try on); `FullScreenViewer` long-press sheet (suppressed); rotate FAB; "Detect tags" / "Remove background" greyed in `TagsOverlay`.
- OutfitsScreen: AI speed-dial items; per-card Edit+Wear row; "Combine with AI" / "Delete" multi-select FABs; "Suggest alternatives" in `ItemSwapSheet`; `RefinementSection` in `OutfitEditingView`.
- Calendar (Insights): "Wear again today" / "Edit" in day-detail.
- TravelScreen: Generate greyed; refinement hidden; "Move all to Travel" greyed.
- WardrobeGap: Analyze greyed.
- SettingsScreen: Retag All / Remove All Backgrounds / Repair & Sync / Import greyed; try-on photo slots hidden.
- WardrobeScreen / OutfitsScreen: Try-on FAB hidden.

When adding new network-dependent UI, read `LocalIsOffline.current` and either hide or set `enabled = !isOffline`.

---

## Compose Dialog quirks (insets + locale)

Fullscreen `Dialog`s (`usePlatformDefaultWidth = false`, `decorFitsSystemWindows = false`) suffer two reproducible bugs on real devices:

1. **Insets**: `WindowInsets.systemBars` (and modifiers like `navigationBarsPadding()`) report 0 inside the Dialog window because the OS only dispatches insets to the activity's window — bottom buttons get clipped by gesture/nav bar.
2. **Locale**: `LocalContext` / `LocalConfiguration` provided in the parent composition are sometimes resolved against the dialog's window context instead of the localized override, so `stringResource` falls back to English.

Fix (used by `LocalBgRemovalScreen`, `MatchPreviewDialog`):
- `MainActivity` captures `WindowInsets.systemBars.asPaddingValues()` once and provides it via `LocalSystemBarsPadding` (defined in `NetworkUtils.kt`).
- Each Dialog content block re-provides `LocalContext` / `LocalConfiguration` from values captured **outside** the `Dialog { ... }` lambda.

Apply this pattern to every new fullscreen Dialog whose action bar must clear the gesture/nav bar or whose content must respect the in-app language toggle.

**Applies to every window-opening composable, not just Dialog**: `ModalBottomSheet`, `Popup`, `AlertDialog`, `BasicAlertDialog`, and any nested sheets/dialogs launched from inside one of those. Each new window severs the locale-overridden `LocalContext`/`LocalConfiguration` chain. Capture `parentContext` / `parentConfiguration` at the parent screen's top level (outside the `Dialog { ... }` / `ModalBottomSheet { ... }` lambda) and re-provide them inside the content. Symptom when missed: strings inside the sheet/dialog render in the device locale instead of the in-app language.

**Slot-based dialogs (`AlertDialog`, `BasicAlertDialog`)** need extra care: wrapping the *outer* `AlertDialog(...)` call with `CompositionLocalProvider` does **not** work — the slot lambdas (`title`, `text`, `confirmButton`, `dismissButton`) are composed *inside* the dialog's own window, which re-derives `LocalContext`/`LocalConfiguration` from the dialog's host view. The provider must live **inside each slot lambda**. Pattern: define a local helper

```kotlin
val locale: @Composable (@Composable () -> Unit) -> Unit = { c ->
    CompositionLocalProvider(
        LocalContext provides parentContext,
        LocalConfiguration provides parentConfiguration,
    ) { c() }
}
```

and wrap every slot's body with `locale { ... }`. Reference: `OutfitComposerScreen.kt` discard / add-tag dialogs.

**`ModalBottomSheet` content lambda** also opens its own window — wrapping the call site doesn't reach inside. Capture context/config inside the sheet composable (before the `ModalBottomSheet { ... }` call) and re-provide them as the first thing inside the content. Reference: `WeatherPickerSheet`, `ClosetPickerSheet` in `OutfitComposerScreen.kt`.

**Bottom action bars / sticky buttons inside fullscreen dialogs** must use the canonical pattern

```kotlin
val effectiveBottom = max(
    LocalSystemBarsPadding.current.calculateBottomPadding(),
    view.rootWindowInsets via WindowInsetsCompat → systemBars().bottom (dp),
    48.dp,
)
```

— `LocalSystemBarsPadding` alone has been observed to report 0 on some devices, and the 48dp floor guarantees the row clears 3-button nav bars. Reference implementations: `OutfitComposerScreen.kt`, `ShoppingHelperScreen.kt` (`MatchPreviewDialog` action row), `LocalBgRemovalScreen`. Apply to every new fullscreen dialog with header or footer chrome.

**Fullscreen `Dialog` overlays the bottom nav bar** — the Scaffold's `bottomBar` is part of the activity window, the Dialog is a separate window. Use this pattern (vs. inline composable) for any "detail viewer" that should consume the full screen. `FullScreenViewer` in `WardrobeScreen.kt` is wrapped in such a Dialog so wardrobe/outfit-item/shopping/try-on item views all overlay the nav bar consistently.

**Scaffold inside a fullscreen Dialog: the double-inset trap.** If you wrap a `Scaffold` with `.padding(LocalSystemBarsPadding.current)` *and* leave its `contentWindowInsets` / TopAppBar `windowInsets` at their defaults, the bottom inset is applied twice (Scaffold body padding + outer wrap) and the top inset is applied twice (TopAppBar's own statusBars padding + outer wrap). Net effect: bottom action row pushed off-screen by the duplicate amount. Fix used by `TryOnComposerScreen`: keep the `.padding(barInsets)` wrap, then pass `contentWindowInsets = WindowInsets(0)` to `Scaffold` and `windowInsets = WindowInsets(0)` to `TopAppBar`. (The alternative — drop the outer wrap and rely on Scaffold's defaults — only works if the dialog reliably dispatches insets to its inner composition, which varies by Android version / nav mode.)

`MatchPreviewDialog`-specific note: the inner Column / `MatchActionBar` use `LocalSystemBarsPadding`, but the outer Box is unpadded so the black background still extends edge-to-edge. `ZoomableMatchImage` only consumes single-finger drags after the user pinches in — at scale 1, horizontal swipes pass through to the parent `HorizontalPager`.

`LocalBgRemovalScreen`-specific note: the dialog is hosted in `MainActivity`; `WardrobeViewModel.localBgReviewQueue` drives it (FIFO; head item shown). On Apply, the cutout is **tight-cropped to its alpha bounding box** (matching the framing Gemini's `removeBackground` returns), then `PendingJob.prebuiltCutoutPath` is set and `processQueuedImage` uses the local cutout instead of calling `gemini.removeBackground`. Crosshair starts at the image center, draggable/tap-to-place; segmentation re-runs ~150 ms after the user stops dragging.

---

## Token usage tracking (BYOK)

`TokenUsageRepository` (singleton, `TokenUsage.kt`) records one `UsageEvent` per Gemini call from the `usageMetadata` field on the response. `GeminiRepository.recordUsage(...)` runs on each success path:

- Image methods (`removeBackground`, `tryOnOutfit`, `classifyClothing`) hardcode their `UsageCategory`.
- `generateText` / `searchFashionTrends` take a `UsageCategory` parameter; call sites in `OutfitsViewModel` / `WardrobeGapViewModel` / `TravelViewModel` attribute spend (OUTFIT_PREDICT, OUTFIT_COMPOSE, GAP_ANALYSIS, REPLACEMENTS, TRAVEL, TRENDS).

**Storage**: append-only JSONL at `filesDir/usage/usage.jsonl`; Drive copy at `LibreLookAI/_token_usage.jsonl`, synced (merge-on-pull, write-on-pause) from the lifecycle observer in `MainActivity`.

**Pricing**: hardcoded in `GeminiPricing` (USD per token, with a constant `USD_TO_EUR` for display).

**UI**: `UsageSection` (in `UsageScreen.kt`) — Today / 7-day / Total summary cards, 14-day daily-token Canvas bar chart, per-category breakdown — rendered in **Insights → Costs** (no longer Settings → Credits).

---

## Analytics

`Analytics.kt` wraps Firebase Analytics. `Analytics.init(applicationContext)` runs in `MainActivity.onCreate`. Use:
- `Analytics.screen(name)` — screen entries.
- `Analytics.action(screen, action, extra)` — taps.
- `Analytics.event(name, params)` — custom events.

Tab switches auto-log `screen_view`; nav-bar taps additionally log a `ui_action` with `screen=NavBar`. Major flows instrumented in `MainActivity.kt`: sign-in, settings open, try-on composer, suggest replacements, create-outfit-from-selection, show-in-wardrobe. Extend coverage by adding `Analytics.action(...)` at new call sites.

---

## Build commands

```bash
./gradlew assembleDebug                                         # Debug APK
./gradlew test                                                  # Unit tests
./gradlew test --tests "com.librelookai.ExampleUnitTest"        # Single test class
./gradlew connectedAndroidTest                                  # Instrumented (needs device)
./gradlew lint
./gradlew clean
./gradlew bundleRelease                                         # Release AAB → app/build/outputs/bundle/release/
```

Firebase Cloud Functions:

```bash
cd firebase/functions && npm install
cd firebase && firebase deploy --only functions
cd firebase && firebase deploy --only firestore:rules
```

## Required `local.properties` keys

`local.properties` is never committed. Consumed at build time via `BuildConfig`:

```
gemini.api.key=          # default BYOK key (users override in Settings)
amazon.affiliate.tag=    # e.g. yourstore-20
shopstyle.publisher.id=  # e.g. uid2500-XXXXX-XX
firebase.proxy.url=      # e.g. https://us-central1-PROJECT.cloudfunctions.net
firebase.web.client.id=  # OAuth 2.0 Web Client ID from Firebase Auth
```

Firebase is **opt-in**: `google-services.json` must be present in `app/` for the plugin to be applied (checked in `app/build.gradle.kts`).

Signing keys (release builds, never committed):

```
signing.store.file=/absolute/path/to/librelookai-release.jks
signing.store.password=
signing.key.alias=librelookai
signing.key.password=
```

---

## ML model assets (excluded from git)

- `app/src/main/assets/embedder/efficientnet_lite0.tflite` (~18 MB EfficientNet Lite0). The MediaPipe-hosted EfficientNet GCS path returns 404; the bundled file came from a TFLite-compatible mirror. Larger than MobileNet V3 Small but noticeably stronger on texture/material similarity. If you change models, also update `EmbeddingRepository.MODEL_PATH`.
- `app/src/main/assets/segmenter/magic_touch.tflite` (~6 MB). Source: `https://storage.googleapis.com/mediapipe-models/interactive_segmenter/magic_touch/float32/1/magic_touch.tflite`.

After downloading, **verify each file with `file <path>`** — a non-200 response from GCS produces a ~250-byte XML error body that `EmbeddingRepository.isAvailable()` will happily accept (the asset opens fine), and you'll only find out when MediaPipe fails to construct and the screen shows "Could not analyze that photo." Real models are multi-MB binary `data`.

If either is missing, the Shop screen shows a dev-facing warning. Embedder is required; segmenter is optional — when absent the pipeline falls back to embedding the raw query (with white-bg compositing only).

---

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
   Without this, Google Sign-In → Firebase Auth fails on release builds.
4. **Create the app in Play Console** (package: `com.librelookai`).

### Per-release checklist

- [ ] Increment `versionCode` in `app/build.gradle.kts`
- [ ] **Update release notes** (always — required for Firebase App Distribution and Play tracks)
- [ ] `google-services.json` present in `app/`
- [ ] `firebase.proxy.url` and `firebase.web.client.id` set in `local.properties`
- [ ] `./gradlew bundleRelease` succeeds
- [ ] Firebase Functions deployed: `cd firebase && firebase deploy --only functions`
- [ ] AAB uploaded to Play Console → Testing → Internal testing → Create new release
- [ ] Testers added via the Testers tab; opt-in link sent

### Giving test credits to testers (managed mode)

After a tester signs in, their Firebase UID appears in the Firestore `users` collection. Set the balance manually in the Firebase Console or via Admin SDK:

```
/users/{uid}/credits = 100
```
