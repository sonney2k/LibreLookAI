# CLAUDE.md

Core guidance for working in this repo. Detailed/historical notes live in `CLAUDE_ARCHIVE.md`.

## Model Usage Guidelines
- Default to **Sonnet** for routine tasks: writing functions, small refactors, reviewing files, and general development work.
- Use **Haiku** for simple queries: quick lookups, generating boilerplate, or formatting tasks.
- Switch to **Opus** only for highly complex architectural changes or deep debugging.

## Operational Best Practices
- Keep tasks small and focused.
- Read files and follow existing patterns before modifying code.
- Use low effort settings for straightforward tasks to save tokens.

## Working Agreement
* **Update this file before executing plans** that change architecture, data flow, or conventions.
* **Update this file once done** to capture new components/behaviors and remove stale info.
* **Ask for confirmation before git commits**; never commit without explicit approval.
* **Always create release notes when distributing via Firebase** (App Distribution releases must ship with updated release notes; bump version + refresh notes together).
* **Always implement with multi-language support**: every user-facing string must go through `stringResource(...)` / `res/values/strings.xml` (and localized `values-*/strings.xml` variants). Never hardcode display text in Kotlin/Compose. New strings must be added to the default `strings.xml` and mirrored in all existing locale variants at the same time.

## Build Commands
* Assemble debug APK: `./gradlew assembleDebug`
* Run unit tests: `./gradlew test`

## Architecture Overview
* **Data Flow**: Compose UI `StateFlow` -> `AndroidViewModel` -> Repositories (Drive, Gemini, Weather, Billing, Credit).
* **Storage**: Images and metadata sidecars (`{cutoutDriveId}.json`) live in app-private Google Drive (`LibreLookAI/`). Local cache at `context.filesDir/wardrobe/` via `wardrobe_cache_{folderId}.json`.
* **File Naming**: `{cutoutDriveId}_cutout.png`, `{cutoutDriveId}_original.jpg`, `{cutoutDriveId}.json` share the cutout's Drive ID.
* **Closets (Locations)**: Map to Drive subfolders. Logic relies strictly on `folderId`, never ephemeral `Location.id` UUIDs.
* **Shopping Closet**: Dedicated `_shopping/` folder, excluded from standard location lists. Selection-mode actions write to Drive, so all FABs are gated on `!isOffline`. Tiles open the shared `FullScreenViewer` with `writeMode = false`. `OutfitComposerScreen` / `TryOnComposerScreen` merge `wardrobe.images + shoppingClosetState.items` so seed IDs from either screen resolve.
* **Shared Grid**: `WardrobeGridShared.kt` (`WardrobeTile`, `WardrobeItemGrid`) used by both Wardrobe and Shopping List with Coil `memoryCacheKey = "{driveId}_{version}"`. `FullScreenViewer` is `internal`, reused by Shopping with `writeMode = false`. `TagFilterBar` is shared.
* **Gemini Routing**: Direct API (BYOK) or Firebase Cloud Function proxy (managed). Images resized to `max(width, height) ≤ 1280` before sending.
* **Offline Mode**: `LocalIsOffline` `CompositionLocal` hides write-path UI. Backed by `NetworkMonitor` (`NetworkUtils.kt`) tracking `INTERNET+VALIDATED` networks; `MainActivity` calls `recheck()` on `ON_RESUME` as a backstop.
* **Navigation**: Single `selectedTab: Int` in `MainActivity`; no Jetpack Navigation.
* **Background Removal**: `SegmentationRepository.foregroundThreshold` mirrors `UserPreferences.bgRemovalThreshold` (Settings → AI). Local on-device path via `segmentForegroundTransparent(src, seedX, seedY)` + `LocalBgRemovalScreen` review dialog (gated by `preferLocalBgRemoval`; URL imports always review). On Apply, cutout is tight-cropped to alpha bbox and `PendingJob.prebuiltCutoutPath` skips `gemini.removeBackground`.
* **Cutout BG Fix**: Settings → Data → "Fix cutout backgrounds" scans existing cutouts, flags black-background / green-halo / interior-hole issues (`detectCutoutIssues` in `GeminiRepository.kt`), and reapplies `blackBackgroundToAlphaInPlace`/`despillGreenInPlace`/`fillInteriorAlphaHolesInPlace`/`featherAlphaEdgesInPlace`/`cropAndCap` via `fixCutoutBackground(input, output, CutoutFixActions(...))`, re-uploading with `drive.updateImage` (preserves Drive ID). Each pass is independently gated by a global toggle in the dialog; defaults follow detection (any flagged → ON) for the three repair passes, feather + tight-crop default ON. Pre-selects flagged items; toggle "show all" to include clean cutouts. The wardrobe single-item "fix" action forces all five passes ON.
* **Background Jobs**: Heavy ops protected by `JobForegroundService` + `PARTIAL_WAKE_LOCK`; OEM battery exemptions requested.
* **Fuzzy Text Search**: `WardrobeViewModel.searchByText` matches the query against each item's flattened tag list (label, type, category, uses, colors, seasonality, aesthetic, fit, material, pattern). Tokens are split on non-alphanumerics; each token must hit some tag via substring match (score 0.9, whole-tag exact = 1.0) or Levenshtein ≤ tol (tol = 0 for ≤3 chars, 1 for ≤5, 2 otherwise; score 0.75 − 0.1·d). All tokens must match; final score is the mean per-token best, sorted desc. Entry point: wardrobe header's `ImageSearch` icon opens `WardrobeSearchChooserDialog` — text routes through `WardrobeViewModel.searchByText`, "Search by photo" falls back to the legacy capture flow. Results render in the existing `FindByPhotoResultsSheet`.

## Key UI & Workflows
* **Outfits**: `OutfitEditingView` (AI editor) or `OutfitListScreen` (list/try-on tabs). Single `+` FAB opens `OutfitComposerScreen` — manual create, "Suggest existing", "Compose new with AI" all live inside.
* **Try-On**: `TryOnComposerScreen` is the single entry point; past try-ons live under Outfits → Try-Ons (calls `tryOnViewModel.openHistoryDetail`). Try-on PNGs cached locally and in Drive `_tryons/`.
* **Similarity Preview**: `MatchPreviewDialog` (`ShoppingHelperScreen.kt`) is shared for Find-by-photo, Similarity Finder, and duplicate-import review (`DuplicateCheckSheet` in `WardrobeScreen.kt`). Debug breakdown gated by `UserPreferences.debugSimilarityPreview`. Cross-screen scroll plumbed via `WardrobeViewModel.requestScrollToImage` / `consumePendingScroll`.
* **Suggest Replacements**: `ReplacementsResultDialog` (hosted in `MainActivity`) calls `WardrobeGapViewModel.suggestReplacements`; prompt frames remaining = all − selected.
* **Compose Dialog Quirks (Insets + Locale)**: Fullscreen `Dialog`s (`usePlatformDefaultWidth = false`, `decorFitsSystemWindows = false`) need (a) padding from `LocalSystemBarsPadding` (provided in `MainActivity` from `NetworkUtils.kt`) because `WindowInsets.systemBars` reports 0 inside the dialog window — so `Modifier.statusBarsPadding()` / `.navigationBarsPadding()` / `WindowInsets.systemBars.asPaddingValues()` are **all no-ops here**. Never use them inside a fullscreen `Dialog`. (b) `LocalContext` / `LocalConfiguration` re-provided from values captured **outside** the `Dialog { ... }` lambda so `stringResource` honors the in-app language toggle. **This applies to every composable that opens its own window** — not just `Dialog`, but also `ModalBottomSheet`, `Popup`, `AlertDialog`, `BasicAlertDialog`, and any nested sheets/dialogs launched from inside one of those. Each new window severs the locale-overridden `LocalContext`/`LocalConfiguration` chain, so wrap the content with `CompositionLocalProvider(LocalContext provides parentContext, LocalConfiguration provides parentConfiguration)` using values captured at the parent screen's top level. Symptom when missed: strings inside the sheet/dialog render in the device locale instead of the in-app language. **Bottom action bars / sticky buttons inside fullscreen dialogs** must use the canonical `effectiveBottom = max(LocalSystemBarsPadding.bottom, view.rootWindowInsets bottom via WindowInsetsCompat, 48.dp)` pattern — `LocalSystemBarsPadding` alone has been observed to report 0 on some devices, and the 48dp floor guarantees the row clears 3-button nav bars. Reference implementations: `OutfitComposerScreen.kt`, `ShoppingHelperScreen.kt` (`MatchPreviewDialog` action row), `LocalBgRemovalScreen`. Apply this pattern to every new fullscreen dialog with header or footer chrome.
* **Token Usage (BYOK)**: `TokenUsageRepository` (`TokenUsage.kt`) records one `UsageEvent` per Gemini call from `usageMetadata`. `GeminiRepository.recordUsage(...)` on success; text methods take a `UsageCategory`. Local JSONL at `filesDir/usage/usage.jsonl`, Drive copy at `LibreLookAI/_token_usage.jsonl` (merge-on-pull, write-on-pause from `MainActivity`). Pricing in `GeminiPricing`. UI: `UsageSection` rendered in **Insights → Costs** (not Settings).
* **Analytics**: `Analytics.kt` wraps Firebase Analytics; init in `MainActivity.onCreate`. Use `Analytics.screen(name)` / `action(screen, action, extra)` / `event(name, params)`. Tab switches auto-log `screen_view`. Add `Analytics.action(...)` at new call sites.
