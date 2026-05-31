# CLAUDE.md

Compact day-to-day guidance. **Deep architecture, pipelines, rationale, and Dialog/Sheet quirks live in `plan/CLAUDE_ARCHIVE.md`** — consult before changes that touch data flow, pipelines, or window-based UI.

## Repo layout
- `app/` — Android app (Kotlin/Compose), package `com.librelookai`.
- `firebase/` — Cloud Functions (`geminiProxy`, `verifyPurchase`, pricing trigger) + Firestore rules.
- `scripts/` — Python/shell helpers (`add_translations.py`, `translation_status.sh`, `kt_split.py`).
- `plan/` — long-form docs: `CLAUDE_ARCHIVE.md`, `FIN.md`, `TODO.md`, `TRANSLATION.md`.
- `designs/` — design-handoff bundles (HTML mockups, JSX prototypes, per-surface `README.md`).
- `website/` — static marketing site (vanilla HTML/CSS + `i18n.js`/`translations.js` in 12 languages; real-device screenshots in `screenshots/`).
- `README.md` (root) — Firebase / signing / partner-program setup.

## Model & effort
- **Sonnet** for routine work · **Haiku** for trivial lookups, boilerplate, formatting · **Opus** for hard architecture or debugging.
- Use low effort for straightforward tasks to save tokens.

## Working agreement
- **Ask before `git commit`**; never commit without explicit approval.
- **Update CLAUDE.md / `plan/CLAUDE_ARCHIVE.md`** when architecture, data flow, or conventions change — both before executing a plan and after it lands.
- **Multi-language is mandatory.** Every user-facing string goes through `stringResource(...)` + `res/values/strings.xml`, mirrored into every `values-*/strings.xml` in the same change. Never hardcode display text.
  - Bulk insert: `scripts/add_translations.py <batch.json>` writes `{locale: {key: rawValue}}` into each `values-*/strings.xml` (auto XML-escape, idempotent). Audit coverage: `scripts/translation_status.sh`.
  - After either, run `./gradlew :app:assembleDebug` — `mergeDebugResources` validates XML + format specifiers across all 31 locales.
- **Release notes** ship with every Firebase / Play release; bump `versionCode` and refresh notes together.
- Keep tasks small; read files and follow existing patterns before editing.

## File size & splitting
**Aim ≤ 500 lines per `.kt` file (target 300–500).** Soft limit: a *single cohesive* composable that legitimately dominates a file (e.g. `WardrobeGrid.kt`'s `GridContent`) may exceed; a *cluster* of many functions over 500 still gets split. Apply the **Placement rule** to decide where the extracted symbols land.
- **Screens** — keep the entry-point `*Screen` composable in the original file; extract groups into siblings (`<Feature>Cards.kt`, `…Dialogs.kt`, `…Sheets.kt`, `…Viewer.kt`) in the **same package** so `internal` visibility and Robolectric tests keep working.
- **ViewModels** can't be sliced arbitrarily. Move pure data / UI-state to `data/model/` or `<Feature>Models.kt`; extract self-contained workflows (audit, import, fix-scans, dedupe) into same-package `internal fun ViewModel.x()` extensions the VM delegates to. Don't break tested public/`internal` signatures (`*Content` composables, `TagFilterBar`, etc.). Verify each extraction with `./gradlew :app:assembleDebug`.
- Tooling: `scripts/kt_split.py` (`split` / `add_imports` / `qualify`) mechanizes same-package extraction — its docstring is the how-to. Compiler is the source of truth: extract → `compileDebugKotlin` → bump flagged privates to `internal` / add flagged cross-package imports → repeat.

## Build & test
- `./gradlew assembleDebug` — debug APK.
- `./gradlew testDebugUnitTest` — JVM unit tests. Bare `./gradlew test` rejects `--tests`; use the variant task to filter.
- Full release / function deploy → `plan/CLAUDE_ARCHIVE.md` § Release process.
- **JVM tests**: plain JUnit for pure logic (`TagNormalizerTest`, `PHashTest`). **Compose flow tests** run on JVM via Robolectric (`createComposeRule()` + `@RunWith(RobolectricTestRunner)` + `@GraphicsMode(NATIVE)`) — see `wardrobe/TagFilterBarTest`. Test the **`internal`/stateless `*Content` composables** (`TagFilterBar`, `TagEditScreenContent`, …) with **hoisted state**, not whole screens (screens default their ViewModels to `viewModel()` and do real Drive/Gemini/ML I/O). Resolve display text via `getString(R.string.…)`, not literals.
- **JDK gotcha**: Robolectric can't instrument under JDK 25. Tests are pinned to a **Java 21 toolchain** (`tasks.withType<Test>` in `app/build.gradle.kts`; path in `gradle.properties` → `org.gradle.java.installations.paths` → Homebrew `openjdk@21`) — foojay auto-download is incompatible with Gradle 9. `testOptions.unitTests.isIncludeAndroidResources = true` lets `stringResource` resolve.
- **Instrumented tests** (`androidTest/`, needs device/emulator): `./gradlew connectedDebugAndroidTest`. Prefer Robolectric; reserve instrumented for true device-dependent paths. Cross-tab nav can only be exercised by launching `MainActivity` (single `selectedTab`, no NavHost).

## Package layout (under `com.librelookai`)
`MainActivity` at root. Feature packages: `auth/`, `onboarding/` (first-run tour — `OnboardingScreen` + `OnboardingState`), `wardrobe/` (incl. `LocationViewModel`, `CaptureScreen`, `UrlImportPicker`, `WebProductFetcher`, `WardrobeGap*`), `outfit/` (incl. `PredictionSetupScreen`, `OutfitCalendar`), `travel/` (`TripsViewModel`, `TripViewerScreen`), `tryon/`, `shopping/`, `billing/`, `settings/` (single flat package; live UI is `SettingsScreen` + the `*Card.kt` / `*Dialog.kt` / `*Screen.kt` siblings; `ProfileViewModel`, `UserPreferences`, `AppLanguage`, `AppFont`, `UsageScreen`/`UsageCostsTab` also live here. `FixCutoutBgDialog.kt` is still hosted by `MainActivity`). The former `insights/` package was dissolved — see Navigation. Cross-cutting: `data/model/` (pure data), `data/drive/` (`DriveRepository`), `gemini/` (`GeminiRepository`, `PromptStore`, `ApiKeyStore`, `TokenUsage*`, `TagNormalizer`), `ml/` (`EmbeddingService`/`…Repository`/`…Index`, `SegmentationRepository`, `PHash`, `ColorHistogram`), `weather/`, `service/` (`JobForegroundService`), `util/` (`Analytics`, `NetworkUtils`, `Scrollbar`, `AiProcessingOverlay`), `ui/theme/`.

**Placement rule (cohesion over locality).** Put each new or moved file in the package with the **most in-package callers and fewest cross-package callers**.
1. List which existing symbols it calls and which existing files call it.
2. Pick the package that maximises same-package edges and minimises `import com.librelookai.<other>.…` lines.
3. If used by ≥ 2 feature packages with no clear owner, lift to `data/model/` (pure data), `util/` (no-dep helper), or the relevant cross-cutting package. Don't dump in `util/` just to avoid choosing.
4. Avoid creating a new top-level package for a single file — extend an existing one unless ≥ 3 related files justify it.
- Wrong-placement symptom: the new file's `import com.librelookai.…` block is longer than its body, or it adds reverse-direction imports (e.g. `data/model/` importing from a feature package). Move it.

## Core conventions
- **Identity is `folderId`** — closets map to Drive subfolders. `Location.id` is an ephemeral UUID and must never be used for identity comparisons.
- **File naming triplet**: `{cutoutDriveId}_cutout.png`, `{cutoutDriveId}_original.jpg`, `{cutoutDriveId}.json` — sidecar shares the cutout's Drive ID.
- **Shopping closet** lives in `LibreLookAI/_shopping/`; excluded from `Location` lists, included in cross-closet similarity snapshot.
- **Offline gating**: read `LocalIsOffline.current` and either hide write-path UI or set `enabled = !isOffline`. Every Gemini / Drive-write surface must be gated.
- **Gemini calls return `null` on failure** — every caller must degrade gracefully. All calls log via `TokenUsageRepository.recordUsage(...)` with a `UsageCategory`.
- **Prompt wardrobe encoding** is centralised in `DriveImage.toPromptJson(c, includeName)` (`wardrobe/PromptItemJson.kt`); every prompt builder (prediction, composer, gap, replacements, travel packing) routes items through it — don't hand-roll the JSON. Which tag dimensions are emitted is the user's `AiConsiderations.itemTags` "expert" choice (`null` = all, back-compat default; `type`/`category`/`name` always sent). Surfaced as `ExpertTagsCard` in the shared Tune-AI sheet (per-invocation override) and `ExpertTagsStrip` in `ProfileEditScreen` (saved default); gap analysis reads the saved default.

## Navigation
- `MainActivity` is a thin `ComponentActivity` whose `onCreate` just calls `AppContent(activity)` (`AppContent.kt`). The whole composition (auth gate, all VMs, `when(selectedTab)` dispatch, global dialog hosts) lives there. Single `selectedTab: Int`, **no Jetpack Navigation**; sub-tab reset via `navResetTick`.
- **First-run onboarding**: a `showOnboarding` flag (seeded from `onboarding/OnboardingState.isComplete`, persisted in its own SharedPrefs file) gates an opaque fullscreen `OnboardingScreen` overlay drawn above the whole app (incl. nav bar) inside the signed-in branch. A swipeable `HorizontalPager` of value-prop / feature pages + light setup steps (style profile, try-on photo, finish); every step is skippable. Re-launchable from **Settings ▸ More ▸ "Take the tour"** via the `LocalStartTour` CompositionLocal (`MainCompositionLocals.kt`) — re-running does **not** clear the persisted flag.
- **Bottom bar** is a custom `Row` (`AppNavBar`/`NavSlot` in `MainNavBar.kt`, not M3 `NavigationBar`) with a raised center **Try-On AI button** opening `QuickTryOnSheet`. Visible slots: Outfits(0)/Wardrobe(1)/[AI]/Shopping(2)/Travel(3); Settings is 5 (index 4 is dead/unused).
- **Old Insights tab is gone** — pages redistributed:
  - Calendar + most-worn Stats → Outfits sub-tabs (`TabRow` in `OutfitsScreen` → `OutfitCalendarTab` / `OutfitWearStatsTab`).
  - Wardrobe tag-breakdown stats → `BarChart` header icon in `WardrobeScreen` → `WardrobeStatsSheet`.
  - Costs / token usage (`UsageCostsTab`) → Settings ▸ Advanced ▸ "See AI usage & cost charts".
  - There is no `LocalOpenInsights` CompositionLocal or `TrendingUp` header icon any more.

## Settings (`settings/`)
Single scroll page, no tabs. Top-to-bottom cards: "You" hero (→ `ProfileEditScreen`), Your style (try-on photos + style prefs + language picker), Your closets (tap a row to make default — instant, no confirm), How it looks (live theme swatches over `WardrobePalettes`), AI credits (→ `BuyCreditsScreen`), More (Advanced / Help / About). Sub-screens use a **local route back-stack** (`SettingsRoute` enum) inside the Settings composable — still no Jetpack Navigation. Reuses the four existing VMs (`Profile`/`Wardrobe`/`Location`/`Credits`); there is no `SettingsViewModel`.

**`SettingsAdvancedScreen`** hosts every power-user / destructive option:
- Three "Fix AI mistakes" rows — re-tag → `retagAll`, re-remove-bg → `removeAllBackgrounds`, cutout fix → `startCutoutBgFixScan`. Each opens `DestructiveConfirmDialog` (computed item count + time + credit cost; flips to "Buy credits & continue" when short).
- BYOK key (`ApiKeyStore`); dedupe / prefer-on-device-bg / similarity-preview toggles.
- Destructive-op progress and the cutout-fix review (`FixCutoutBgDialog`) are still globally hosted in `MainActivity`/`WardrobeScreen`; Advanced only triggers the entry points.

Design source: `designs/settings_v1/README.md`.

## Header cluster (try-on / outfit-building flow)
Reused via `ViewerHeaderActions(onBeforeNavigate)` (`MainCompositionLocals.kt`) — **only on create/compose surfaces**, not detail viewers.
- Present on: the picker dialogs the Quick sheet routes into (`OutfitPickerDialog`, `TripOutfitPickerDialog` in `tryon/`, shared `AddItemSheet` in `OutfitComposerAddItem.kt`). The Quick-sheet links open the composer with `autoPick`, which immediately layers one of those pickers on top, so the picker is what the user lands on first.
- Deliberately **absent** on: `TryOnComposerScreen` header and the read-only detail viewers (`FullScreenViewer`, `OutfitFullScreenViewer`) — they keep the minimal overlay close button.
- Reads two CompositionLocals (`MainCompositionLocals.kt`) provided high in `AppContent`: `LocalClosetSelector` (`ClosetSelectorContext` — interactive closet dropdown, switches active closet in place) and `LocalOpenSettings`. Settings nav runs `onBeforeNavigate` first (dismiss host Dialog); the opener also calls `tryOnViewModel.close()` + `stylesViewModel.closeComposer()` since those composers are hosted outside `when(selectedTab)`. Each child self-hides when its local is unprovided (closet dropdown also hides below 2 closets).

## Try-On provenance
Every try-on carries a `TryOnSourceKind` (outfit/wardrobe/shopping/travel) + `sourceContext` label, persisted on `TryOn` and surfaced via `SourcePill` / `SourceColors` / `sourceMeta` (`tryon/TryOnDesign.kt`). The hero history feed lives in `TryOnHistoryFeed` (`TryOnHistoryScreen.kt`). Past try-ons are a **dedicated full-screen page** (`TryOnViewModel.openHistoryRoot()` → composer Dialog rendering the feed), reached from the center AI button → Quick sheet ▸ "See past try-ons" — there is **no** Try-Ons sub-tab.

## Shared UI & Wardrobe split
- Shared: `WardrobeGridShared.kt` (`WardrobeTile`, `WardrobeItemGrid`); `TagFilterBar`/`SortButton`/`HideTagsChip` (`WardrobeTagFilterBar.kt`); `FullScreenViewer` (used by Wardrobe / Outfit-item / Shopping / Try-On); `MatchPreviewDialog`/`MatchRow` (`shopping/MatchPreview.kt`, debug viz in `MatchDebug.kt`; Find-by-photo / Similarity / dedupe).
- **Wardrobe screen** (one-screen-per-file): `WardrobeScreen.kt` (entry) · `WardrobeGrid.kt` (`GridContent`) · `WardrobeTagTaxonomy.kt` (tag enums/labels/helpers) · `WardrobeSheets.kt` (URL-import / duplicate-check / find-by-photo / cutout-fix dialogs) · `TagEditScreen.kt` + `TagEditTagTable.kt` · `FullScreenViewerParts.kt` (`ZoomableImage` / `TagsOverlay`).
- **WardrobeViewModel split**: data/UI-state in `WardrobeModels.kt`; big workflows in same-package `internal fun WardrobeViewModel.x()` files — `WardrobeViewModelBgFix.kt`, `…Audit.kt`, `…Upload.kt`, `…Import.kt`, `…Search.kt`. The VM keeps load/cache/queue/sidecar/upload core + setters + tag/move/CRUD. Members shared with extensions are `internal`, not `private`.

## Window quirks (Dialog / Sheet / Popup / AlertDialog)
**Every composable that opens its own window** (`Dialog`, `ModalBottomSheet`, `Popup`, `AlertDialog`, `BasicAlertDialog`, and anything nested inside one) severs the locale-overridden `LocalContext` / `LocalConfiguration` chain and reports `WindowInsets.systemBars` as 0.

Rule of thumb:
1. Capture `parentContext` / `parentConfiguration` **outside** the window-opening call.
2. Re-provide via `CompositionLocalProvider` **inside** the content lambda (for `AlertDialog`, inside **each slot lambda** — the outer wrap doesn't reach slots).
3. Use `LocalSystemBarsPadding` (provided high in `AppContent`) instead of `statusBarsPadding()` / `navigationBarsPadding()` inside Dialogs.
4. Sticky bottom rows in fullscreen Dialogs: `effectiveBottom = max(LocalSystemBarsPadding bottom, view.rootWindowInsets systemBars bottom, 48.dp)`.
5. Fullscreen `Dialog` (not inline composable) is how a detail view overlays the bottom nav bar.

Full rationale + reference implementations (`OutfitComposerScreen` slot-dialog helper, `WeatherPickerSheet`/`ClosetPickerSheet` ModalBottomSheet pattern, `FullScreenViewer` Dialog wrap, `MatchPreviewDialog` action row) → `plan/CLAUDE_ARCHIVE.md` § "Compose Dialog quirks". Read it before adding any new window-based UI.

## Monetization (coins + BYOK)
- **Master switch: `billing/ManagedBilling.enabled`** (← `BuildConfig.MANAGED_BILLING_ENABLED`, set from `managed.billing.enabled` in `local.properties`, **default `false`**). Off = BYOK-only release: every coin/purchase/refinancing surface is hidden (AI-credits card + section, Buy-credits packs, coin cost badges, spend confirms, destructive-dialog coin row), the Gemini proxy is never used (`GeminiRepository.isProxyMode`/`isConfigured`), `CreditRepository.isManagedMode()` is false, and `CreditsViewModel` never connects to Play billing. BYOK key entry stays in **Settings ▸ Advanced** (and the bottom of `BuyCreditsScreen`). Flip the flag (with a deployed proxy + Play products) to light the coin economy back up with no code change. The Play release ships with it off; an onboarding "how to get a Gemini key" step is planned later.
- **Pricing is server-authoritative.** Firestore holds `config/pricing` (private: `multiplier` + raw `costs`) and `config/publicPricing` (auth-readable: post-multiplier coin prices). The `recomputePublicPricing` trigger is the only writer to `publicPricing`. The multiplier never reaches the client.
- **Client reads `config/publicPricing`** via `gemini/PricingClient` (Firestore listener + SharedPrefs cache + fallback to `CreditPack.COST_*`). Started once in `MainActivity.onCreate`.
- **Cost UI**: `billing/CostBadge` is a small leading icon (`🪙 N` managed, `~Nk tokens` BYOK, hidden otherwise) — place it as the first child of any AI-trigger button. `ConfirmSpendDialog` + `requiresSpendConfirm()` gate spends ≥ 20 coins or bulks ≥ 5 items (BYOK never gated).
- **402 → typed exception + global event**: `GeminiRepository.throwIf402` parses `{needed, have}`, emits on `billing/CreditsEvents.topUp`, then raises `billing/InsufficientCreditsException`. A single `InsufficientCreditsDialog` observer in `MainActivity` listens and routes "Buy" → Settings — so individual VMs don't manage dialog state. Per-VM catches only reset loading flags and short-circuit bulk loops via a local `creditsExhausted` flag.
- **Ledger**: every charge/refund/purchase appends to `users/{uid}/ledger/{autoId}` (server-only write, user can read).
- **`verifyPurchase`** validates against the Google Play Developer API before crediting; the service account needs "View financial data" + "Manage orders" in Play Console.
- **Seeding**: `cd firebase/functions && npm run build && node lib/seed.js` writes initial `config/pricing` (defaults in `firebase/functions/src/pricing.ts → DEFAULT_PRICING`); the trigger mirrors to `publicPricing`.
- Deep rationale & decisions (markup security, dropped reservation/commit complexity, RevenueCat tradeoffs, remaining screen wiring) → `plan/FIN.md`.

## Where to find things
- Trips (aggregate of N day-outfits) → archive § "Trips"
- Upload / ingestion / URL import / SAF folder import → archive § "Photo upload & ingestion pipelines"
- Repair & Sync, duplicate detection, audit → archive § "Repair & Sync"
- Visual similarity (embedder, segmenter, scoring, white balance) → archive § "Visual wardrobe search"
- Outfits / composer / Travel / Try-On → archive § "Outfits, Travel, Try-On"
- Shopping closet → archive § "Shopping closet"
- Calendar / wear-Stats, Wardrobe stats sheet, Usage/Costs → archive § "Insights tab" (tab removed; pages redistributed)
- Offline mode internals → archive § "Offline mode (deep notes)"
- Token usage / pricing → archive § "Token usage tracking"
- Analytics → archive § "Analytics"
- Release / signing / keystore / Play / Firebase → archive § "Release process"
- ML model assets (embedder / segmenter) → archive § "ML model assets"
- Design handoffs (HTML mockups, JSX prototypes) → `designs/<surface>/README.md`
- Marketing website (i18n setup, translations, screenshots) → `website/`
