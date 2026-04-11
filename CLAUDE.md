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
- **Metadata**: Five JSON sidecar files stored in the same Drive folder: `_wardrobe_metadata.json`, `_styles_metadata.json`, `_outfits_metadata.json`, `_user_preferences.json`, `_locations.json`.
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

### SAF import

`WardrobeViewModel.importFromFolder(treeUri)` reads images from any OS-accessible folder using `DocumentsContract` + `ContentResolver` (no extra OAuth scopes needed), re-uploads them to the app's Drive folder, and re-uses existing `_wardrobe_metadata.json` tags or classifies new items with Gemini.
