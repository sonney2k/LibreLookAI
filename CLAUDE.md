# CLAUDE.md

Compact day-to-day guidance. **Deep architecture, pipelines, rationale, and Dialog/Sheet quirks live in `CLAUDE_ARCHIVE.md`** — consult it before designing changes that touch data flow, pipelines, or window-based UI.

## Model & effort
- Default to **Sonnet** for routine work (functions, small refactors, reviews).
- **Haiku** for trivial lookups / boilerplate / formatting.
- **Opus** only for hard architectural or debugging work.
- Use low effort for straightforward tasks to save tokens.

## Working agreement
- **Ask before `git commit`**; never commit without explicit approval.
- **Update CLAUDE.md / CLAUDE_ARCHIVE.md** when changing architecture, data flow, or conventions — both before executing a plan and after it lands.
- **Multi-language is mandatory**: every user-facing string goes through `stringResource(...)` and `res/values/strings.xml`. Add to default `strings.xml` and mirror in every `values-*/strings.xml` in the same change. Never hardcode display text.
- **Release notes** ship with every Firebase / Play release; bump `versionCode` and refresh notes together.
- Keep tasks small and focused; read files and follow existing patterns before editing.

## Build
- `./gradlew assembleDebug` — debug APK
- `./gradlew test` — unit tests
- Full release / function deploy commands: see `CLAUDE_ARCHIVE.md` → Release process.

## Package layout (under `com.librelookai`)
`MainActivity` at root. Feature packages: `auth/`, `wardrobe/` (incl. `LocationViewModel`, `CaptureScreen`, `UrlImportPicker`, `WebProductFetcher`, `WardrobeGap*`), `outfit/` (incl. `PredictionSetupScreen`), `travel/`, `tryon/`, `shopping/`, `billing/`, `insights/` (incl. `UsageScreen`), `settings/` (incl. `ProfileViewModel`, `UserPreferences`, `AppLanguage`, `AppFont`). Cross-cutting: `data/model/` (pure data classes), `data/drive/` (`DriveRepository`), `gemini/` (`GeminiRepository`, `PromptStore`, `ApiKeyStore`, `TokenUsage*`, `TagNormalizer`), `ml/` (`EmbeddingService`/`Repository`/`Index`, `SegmentationRepository`, `PHash`, `ColorHistogram`), `weather/`, `service/` (`JobForegroundService`), `util/` (`Analytics`, `NetworkUtils`, `Scrollbar`, `AiProcessingOverlay`), `ui/theme/`.

**Placement rule (cohesion over locality):** put each new or moved file in the package where it has the **most in-package callers and the fewest cross-package callers**. Concretely, before adding/moving a file:
1. List which existing symbols it calls and which existing files call it.
2. Pick the package that maximises same-package edges and minimises `import com.librelookai.<other>.…` lines.
3. If a symbol is used by ≥ 2 feature packages and has no clear owner, lift it to `data/model/` (pure data), `util/` (no-dep helper), or the relevant cross-cutting package (`gemini/`, `ml/`, `data/drive/`). Don't dump it in `util/` just to avoid choosing.
4. Avoid creating a new top-level package for a single file — extend an existing one unless ≥ 3 related files justify the split.
Symptom that placement is wrong: the new file's `import com.librelookai.…` block is longer than its own body, or it adds reverse-direction imports (e.g., `data/model/` importing from a feature package). Move it.

## Core conventions
- **Identity is `folderId`**: closets map to Drive subfolders; `Location.id` is an ephemeral UUID and must never be used for identity comparisons.
- **File naming triplet**: `{cutoutDriveId}_cutout.png`, `{cutoutDriveId}_original.jpg`, `{cutoutDriveId}.json` — sidecar shares the cutout's Drive ID.
- **Shopping closet** lives in `LibreLookAI/_shopping/`; excluded from `Location` lists, included in cross-closet similarity snapshot.
- **Offline gating**: read `LocalIsOffline.current` and either hide write-path UI or set `enabled = !isOffline`. Every Gemini / Drive-write surface must be gated.
- **Navigation**: single `selectedTab: Int` in `MainActivity`; no Jetpack Navigation. Sub-tab reset via `navResetTick`.
- **Shared UI**: `WardrobeGridShared.kt` (`WardrobeTile`, `WardrobeItemGrid`, `TagFilterBar`), `FullScreenViewer` (WardrobeScreen.kt, used by Wardrobe / Outfit-item / Shopping / Try-On), `MatchPreviewDialog` (ShoppingHelperScreen.kt, used by Find-by-photo / Similarity / dedupe).
- **Gemini calls return `null` on failure** — every caller must degrade gracefully. All calls get logged via `TokenUsageRepository.recordUsage(...)` with a `UsageCategory`.

## Window quirks (Dialog / Sheet / Popup / AlertDialog)
**Every composable that opens its own window** (`Dialog`, `ModalBottomSheet`, `Popup`, `AlertDialog`, `BasicAlertDialog`, and anything nested inside one) severs the locale-overridden `LocalContext` / `LocalConfiguration` chain and reports `WindowInsets.systemBars` as 0.

Rule of thumb:
1. Capture `parentContext` / `parentConfiguration` **outside** the window-opening call.
2. Re-provide them via `CompositionLocalProvider` **inside** the content lambda (for `AlertDialog`, inside **each slot lambda** — the outer wrap doesn't reach the slots).
3. Use `LocalSystemBarsPadding` (provided by `MainActivity`) instead of `statusBarsPadding()` / `navigationBarsPadding()` inside Dialogs.
4. Sticky bottom rows in fullscreen Dialogs use `effectiveBottom = max(LocalSystemBarsPadding bottom, view.rootWindowInsets systemBars bottom, 48.dp)`.
5. Fullscreen `Dialog` (vs. inline composable) is the way to make a detail view overlay the bottom nav bar.

**Full rationale, code patterns, and reference implementations** (`OutfitComposerScreen` slot-dialog helper, `WeatherPickerSheet` / `ClosetPickerSheet` ModalBottomSheet pattern, `FullScreenViewer` Dialog wrap, `MatchPreviewDialog` action row) are in `CLAUDE_ARCHIVE.md` → "Compose Dialog quirks". Read it before adding any new window-based UI.

## Where to find things (pointers into archive)
- Upload / ingestion / URL import / SAF folder import → archive § "Photo upload & ingestion pipelines"
- Repair & Sync, duplicate detection, audit → archive § "Repair & Sync"
- Visual similarity (embedder, segmenter, scoring, white balance) → archive § "Visual wardrobe search"
- Outfits / composer / Travel / Try-On → archive § "Outfits, Travel, Try-On"
- Shopping closet → archive § "Shopping closet"
- Insights / Calendar / Stats / Costs → archive § "Insights tab"
- Offline mode internals → archive § "Offline mode (deep notes)"
- Token usage / pricing → archive § "Token usage tracking"
- Analytics → archive § "Analytics"
- Release / signing / keystore / Play / Firebase → archive § "Release process"
- ML model assets (embedder / segmenter) → archive § "ML model assets"
