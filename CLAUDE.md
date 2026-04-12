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
- **Multi-location**: Each "location" corresponds to a Drive subfolder. `LocationViewModel` tracks the active location; `WardrobeViewModel.setLocation(folderId)` switches context.
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

### Repair & Sync

`WardrobeViewModel.startRepairAndRefresh(folderIds)` runs a multi-phase audit across all location folders. A `PARTIAL_WAKE_LOCK` is held throughout so the job survives screen-off (30-minute safety timeout). All actions are logged to logcat under the tag `RepairAndSync`.

1. **Scan**: `DriveRepository.listAllImageFiles()` returns every image (originals, cutouts, raws). Cutouts with wrong names are renamed in-place. Originals whose prefix does not match any cutout's Drive ID are flagged as orphaned. For each cutout that has a sidecar, the sidecar content is downloaded and parsed — if `tags` is null (i.e. the file is `{}` or `{"tags":null}`), the cutout is flagged for re-tagging just like a missing sidecar.
2. **Confirmation**: `WardrobeUiState.auditProgress` enters `awaitingConfirmation`; the UI shows findings and asks the user whether to process.
3. **Process** (`continueRepairProcessing(true)`): orphaned originals get full AI processing (bg removal + tagging + sidecar upload); cutouts missing a sidecar or with empty tags get tagging (from the cutout image) + sidecar upsert.
4. **Refresh**: all local caches are cleared and `loadImages()` reloads from Drive. `auditProgress.isDone` signals completion.

### SAF import

`WardrobeViewModel.importFromFolder(treeUri)` reads images from any OS-accessible folder using `DocumentsContract` + `ContentResolver` (no extra OAuth scopes needed), re-uploads them to the app's Drive folder, and classifies new items with Gemini before writing per-item sidecars.
