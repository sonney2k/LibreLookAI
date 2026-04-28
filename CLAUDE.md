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
* **Gemini Routing**: Uses either direct API (BYOK) or a Firebase Cloud Function proxy for managed mode. Images are strictly resized to `max(width, height) ≤ 1280` prior to sending.
* **Offline Mode**: Uses `LocalIsOffline` `CompositionLocal` to instantly hide write-path UI elements (Drive/Gemini).
* **Navigation**: Managed via a single `selectedTab: Int` in `MainActivity`; no Jetpack Navigation component is used.
* **Background Removal**: `SegmentationRepository.foregroundThreshold` is the per-pixel confidence cutoff for the Magic Touch mask. Mirrored from `UserPreferences.bgRemovalThreshold` via a `LaunchedEffect` in `MainActivity`; tuned from Settings → AI tab.

## Key UI & Workflows
* **Outfits Screen**: Branches exclusively into `OutfitEditingView` (editor), `OutfitItemPicker` (creation), or `OutfitListScreen` (list/try-on tabs).
* **Unified Outfit Composer**: `OutfitComposerScreen` is a full-screen dialog managed at the `MainActivity` level that replaces previous scattered creation flows.
* **Unified Try-On**: `TryOnComposerScreen` acts as the single entry point for previewing and saving generated try-on PNGs (cached locally and in Drive `_tryons/`).
* **Unified Similarity Preview**: `MatchPreviewDialog` (in `ShoppingHelperScreen.kt`) is the shared preview for both Wardrobe → Find by photo and Shopping → Similarity Finder. Both flows render `MatchRow` lists and offer the same "Show in wardrobe" / "Add to shopping list" actions; debug breakdown is gated by `UserPreferences.debugSimilarityPreview`. Cross-screen scroll/highlight is plumbed via `WardrobeViewModel.requestScrollToImage` / `consumePendingScroll`.
* **Suggest Replacements**: `ReplacementsResultDialog` is hosted at `MainActivity` and triggered by the "Suggest alternatives" FAB in Wardrobe selection mode. It calls `WardrobeGapViewModel.suggestReplacements`, which reuses the `GapAnalysis` schema and renders results with the existing `GapSuggestionCard`. The prompt (`buildReplacementsPrompt` in `WardrobeGapViewModel.kt`) frames remaining = all − selected so suggestions complement the kept wardrobe.
* **Background Jobs**: Heavy operations are protected by `JobForegroundService` and `PARTIAL_WAKE_LOCK`. OEM battery optimization exemptions are actively requested.