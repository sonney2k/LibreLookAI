# CLAUDE.md

This file provides core guidance when working with code in this repository. 

## Working Agreement
* **Update this file before executing plans** that change architecture, data flow, or conventions.
* **Update this file once done** to capture new components/behaviors and remove stale info.
* **Ask for confirmation before git commits**; never commit without explicit approval.

## Build Commands
* Assemble debug APK: `./gradlew assembleDebug`
* Run unit tests: `./gradlew test`

## Architecture Overview
* **Data Flow**: Compose UI `StateFlow` -> `AndroidViewModel` -> Repositories (Drive, Gemini, Weather, Billing, Credit).
* **Storage**: Images and metadata sidecars (`{cutoutDriveId}.json`) are stored in the user's app-private Google Drive (`LibreLookAI/`). 
* **Local Cache**: Maintained at `context.filesDir/wardrobe/` via `wardrobe_cache_{folderId}.json`.
* **File Naming**: `{cutoutDriveId}_cutout.png`, `{cutoutDriveId}_original.jpg`, and `{cutoutDriveId}.json` share the cutout's Drive ID.
* **Closets (Locations)**: Map to Drive subfolders. Logic relies strictly on `folderId`, never ephemeral `Location.id` UUIDs.
* **Shopping Closet**: Uses a dedicated `_shopping/` folder and is intentionally excluded from standard location lists.
* **Shared Grid (Wardrobe + Shopping)**: `WardrobeGridShared.kt` exposes `WardrobeTile` and `WardrobeItemGrid`, used by both the main Wardrobe grid and the Shopping List tab. Both get the same Coil `memoryCacheKey = "{driveId}_{version}"` (no re-decode on scroll) and identical visuals. Filter (`TagFilterBar`) and sort (`SortButton`, `SortOption`) are reused on shopping; `SortButton` is `internal` for cross-file use.
* **Gemini Routing**: Uses either direct API (BYOK) or a Firebase Cloud Function proxy for managed mode. Images are strictly resized to `max(width, height) ≤ 1280` prior to sending.
* **Offline Mode**: Uses `LocalIsOffline` `CompositionLocal` to instantly hide write-path UI elements (Drive/Gemini).
* **Navigation**: Managed via a single `selectedTab: Int` in `MainActivity`; no Jetpack Navigation component is used.
* **Background Removal**: `SegmentationRepository.foregroundThreshold` is the per-pixel confidence cutoff for the Magic Touch mask. Mirrored from `UserPreferences.bgRemovalThreshold` via a `LaunchedEffect` in `MainActivity`; tuned from Settings → AI tab.
* **Local (On-Device) Background Removal**: `SegmentationRepository.segmentForegroundTransparent(src, seedX, seedY)` produces a transparent-bg cutout for any user-chosen seed point. `LocalBgRemovalScreen` is a full-screen review dialog hosted in `MainActivity`; `WardrobeViewModel.localBgReviewQueue` drives it (FIFO; head item is shown). On Apply, the cutout is **tight-cropped to its alpha bounding box** (matching the framing Gemini's `removeBackground` returns), then `PendingJob.prebuiltCutoutPath` is set and `processQueuedImage` uses the local cutout instead of calling `gemini.removeBackground`. Gated by `UserPreferences.preferLocalBgRemoval` for camera and gallery imports; **URL imports always show the review** regardless of the pref. The crosshair starts at the image center and is draggable/tap-to-place; segmentation re-runs ~150 ms after the user stops dragging. The dialog uses `decorFitsSystemWindows = false` plus `statusBarsPadding()` / `navigationBarsPadding()` so action buttons stay fully visible.
* **Duplicate-Import Review**: When the dedupe gate fires, `DuplicateCheckSheet` (in `WardrobeScreen.kt`) reuses the find-by-photo layout — `MatchRow` list + tap-to-open `MatchPreviewDialog` — alongside the original Cancel / Import-anyway buttons. Keeps the visual language consistent across every similarity-search surface.
* **Camera Back-Press**: `CaptureScreen` installs `BackHandler`s so the system back button has predictable behavior — viewfinder back cancels to the wardrobe, review-step back retakes (returns to viewfinder) instead of discarding the capture.

## Key UI & Workflows
* **Outfits Screen**: Branches exclusively into `OutfitEditingView` (editor), `OutfitItemPicker` (creation), or `OutfitListScreen` (list/try-on tabs).
* **Unified Outfit Composer**: `OutfitComposerScreen` is a full-screen dialog managed at the `MainActivity` level that replaces previous scattered creation flows.
* **Unified Try-On**: `TryOnComposerScreen` acts as the single entry point for previewing and saving generated try-on PNGs (cached locally and in Drive `_tryons/`).
* **Unified Similarity Preview**: `MatchPreviewDialog` (in `ShoppingHelperScreen.kt`) is the shared preview for Wardrobe → Find by photo, Shopping → Similarity Finder, and the wardrobe duplicate-import review. All three render `MatchRow` lists and offer the same "Show in wardrobe" / "Add to shopping list" actions; debug breakdown is gated by `UserPreferences.debugSimilarityPreview`. The dialog uses `decorFitsSystemWindows = false` so its action bar respects insets even when the activity is edge-to-edge. `ZoomableMatchImage` only consumes single-finger drags after the user pinches in — at scale 1 horizontal swipes pass through to the parent `HorizontalPager`. Cross-screen scroll/highlight is plumbed via `WardrobeViewModel.requestScrollToImage` / `consumePendingScroll`.
* **Suggest Replacements**: `ReplacementsResultDialog` is hosted at `MainActivity` and triggered by the "Suggest alternatives" FAB in Wardrobe selection mode. It calls `WardrobeGapViewModel.suggestReplacements`, which reuses the `GapAnalysis` schema and renders results with the existing `GapSuggestionCard`. The prompt (`buildReplacementsPrompt` in `WardrobeGapViewModel.kt`) frames remaining = all − selected so suggestions complement the kept wardrobe.
* **Background Jobs**: Heavy operations are protected by `JobForegroundService` and `PARTIAL_WAKE_LOCK`. OEM battery optimization exemptions are actively requested.
* **Analytics**: `Analytics.kt` wraps Firebase Analytics. `Analytics.init(applicationContext)` runs in `MainActivity.onCreate`. Use `Analytics.screen(name)` for screen entries, `Analytics.action(screen, action, extra)` for taps, and `Analytics.event(name, params)` for custom events. Tab switches log `screen_view` automatically; nav-bar taps additionally log a `ui_action` with `screen=NavBar`. Major flows (sign-in, settings open, try-on composer, suggest replacements, create-outfit-from-selection, show-in-wardrobe) are instrumented in `MainActivity.kt`. To extend coverage, add `Analytics.action(...)` at the call sites of new buttons inside individual screens.