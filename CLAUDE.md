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
- **Multi-language is mandatory**: every user-facing string goes through `stringResource(...)` and `res/values/strings.xml`. Add to default `strings.xml` and mirror in every `values-*/strings.xml` in the same change. Never hardcode display text. **Tooling**: `scripts/add_translations.py <batch.json>` bulk-inserts a `{locale: {key: rawValue}}` JSON into each `values-*/strings.xml` (auto XML-escaping, idempotent, skips dirs without a `strings.xml`); `scripts/translation_status.sh` audits coverage. After either, run `./gradlew :app:assembleDebug` — `mergeDebugResources` validates XML + format specifiers across all locales.
- **Release notes** ship with every Firebase / Play release; bump `versionCode` and refresh notes together.
- Keep tasks small and focused; read files and follow existing patterns before editing.

## Build
- `./gradlew assembleDebug` — debug APK
- `./gradlew testDebugUnitTest` — JVM unit tests (note: bare `./gradlew test` rejects `--tests`; use the variant task to filter)
- Full release / function deploy commands: see `CLAUDE_ARCHIVE.md` → Release process.

## Testing
- **Pure-logic JVM tests** (plain JUnit): e.g. `TagNormalizerTest`, `PHashTest` (bit-math only). No Android runtime.
- **Compose UI flow tests run on the JVM via Robolectric** (`createComposeRule()` + `@RunWith(RobolectricTestRunner)` + `@GraphicsMode(NATIVE)`) — see `wardrobe/TagFilterBarTest`. Test the **`internal`/stateless `*Content` composables** (e.g. `TagFilterBar`, `TagEditScreenContent`) with **hoisted state**, not whole screens — screens default their ViewModels to `viewModel()` and do real Drive/Gemini/ML I/O. Resolve display text via `getString(R.string.…)` (locale-independent), not hardcoded strings.
- **JDK gotcha**: Robolectric can't instrument under the machine-default **JDK 25**. Unit tests are pinned to a **Java 21 toolchain** (`tasks.withType<Test>` in `app/build.gradle.kts`), with the path declared in `gradle.properties` (`org.gradle.java.installations.paths` → Homebrew `openjdk@21`) because foojay auto-download is incompatible with Gradle 9. `testOptions.unitTests.isIncludeAndroidResources = true` lets `stringResource` resolve.
- **Instrumented tests** (`androidTest/`, needs device/emulator): `./gradlew connectedDebugAndroidTest`. Prefer Robolectric for flow tests; reserve instrumented for true device-dependent paths. Cross-tab nav can only be tested by launching `MainActivity` (single `selectedTab`, no NavHost).

## Package layout (under `com.librelookai`)
`MainActivity` at root. Feature packages: `auth/`, `wardrobe/` (incl. `LocationViewModel`, `CaptureScreen`, `UrlImportPicker`, `WebProductFetcher`, `WardrobeGap*`), `outfit/` (incl. `PredictionSetupScreen`, `OutfitCalendar` → the Calendar + wear-Stats sub-tabs), `travel/` (incl. `TripsViewModel`, `TripViewerScreen`), `tryon/`, `shopping/`, `billing/`, `settings/` (incl. `ProfileViewModel`, `UserPreferences`, `AppLanguage`, `AppFont`, `UsageScreen` → `UsageCostsTab` + `UsageSection`; **the live Settings UI is the redesigned `settings/v2/` package** — see Navigation; the legacy 3.2k-line `settings/SettingsScreen.kt` is kept compiled-but-unreferenced as a one-release fallback). (The former `insights/` package was dissolved — see Navigation.) Cross-cutting: `data/model/` (pure data classes), `data/drive/` (`DriveRepository`), `gemini/` (`GeminiRepository`, `PromptStore`, `ApiKeyStore`, `TokenUsage*`, `TagNormalizer`), `ml/` (`EmbeddingService`/`Repository`/`Index`, `SegmentationRepository`, `PHash`, `ColorHistogram`), `weather/`, `service/` (`JobForegroundService`), `util/` (`Analytics`, `NetworkUtils`, `Scrollbar`, `AiProcessingOverlay`), `ui/theme/`.

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
- **Navigation**: single `selectedTab: Int` in `MainActivity`; no Jetpack Navigation. Sub-tab reset via `navResetTick`. The bottom bar is a **custom `Row`** (not M3 `NavigationBar`) with a raised center **Try-On AI button** that opens `QuickTryOnSheet`; visible slots are Outfits(0)/Wardrobe(1)/[AI]/Shopping(2)/Travel(3); Settings is 5 (index 4 is now dead/unused). **The old Insights tab was dissolved**: its pages were redistributed to their natural homes — **Calendar + most-worn Stats are Outfits sub-tabs** (`TabRow` in `OutfitsScreen`: Outfits/Calendar/Stats → `OutfitCalendarTab`/`OutfitWearStatsTab`); **Wardrobe tag-breakdown stats** is a `BarChart` header icon in `WardrobeScreen` opening `WardrobeStatsSheet`; **Costs/token usage** (`UsageCostsTab`) is reached from Settings ▸ Advanced ▸ "See AI usage & cost charts". There is no longer a `LocalOpenInsights` CompositionLocal or `TrendingUp` header icon.
- **Settings is a single scroll page** (`settings/v2/SettingsScreen`), no tabs. Top-to-bottom cards: "You" hero (→ `ProfileEditScreen`), Your style (try-on photos + style prefs + language picker), Your closets (tap a row to make default, instant/no-confirm), How it looks (live theme swatches over `WardrobePalettes`), AI credits (→ `BuyCreditsScreen`), More (Advanced / Help / About). Sub-screens are reached via a **local route back-stack inside the Settings composable** (`SettingsRoute` enum) — still no Jetpack Navigation. It reuses the four existing VMs (`Profile`/`Wardrobe`/`Location`/`Credits`); there is no separate `SettingsViewModel`. **All power-user/destructive options live in `SettingsAdvancedScreen`**: the three "Fix AI mistakes" rows (re-tag → `retagAll`, re-remove-bg → `removeAllBackgrounds`, cutout fix → `startCutoutBgFixScan`) each open `DestructiveConfirmDialog` (computed item count + time + credit cost, flips to "Buy credits & continue" when short); BYOK key (`ApiKeyStore`); and the dedupe / prefer-on-device-bg / similarity-preview toggles. Destructive-op progress and the cutout-fix review (`FixCutoutBgDialog`) are still globally hosted in `MainActivity`/`WardrobeScreen`, so Advanced only triggers the entry points. New user-facing strings are in default `values/strings.xml` and mirrored into all 31 translated locales (every `values-*` that has a `strings.xml`; the empty `values-ru` dir is vestigial — Russian is not an `AppLanguage` option).
- **Header cluster in the try-on / outfit-building flow**: the closet selector + Settings cluster is reused via `ViewerHeaderActions(onBeforeNavigate)` (MainActivity.kt) — but **only on the create/compose surfaces**, not the detail viewers. It lives **only on the picker dialogs the Quick sheet routes into** (`OutfitPickerDialog`, `TripOutfitPickerDialog` in `tryon/`, and the shared `AddItemSheet` in `OutfitComposerScreen.kt`) — the Quick-sheet links open the composer with `autoPick`, which immediately layers one of those pickers on top, so the picker is what the user lands on first. It is deliberately **absent** from the try-on composer header (`TryOnComposerScreen`) and from the read-only detail viewers (`FullScreenViewer`, `OutfitFullScreenViewer` — wardrobe item / outfit / travel-outfit views), which keep their minimal overlay close button. It reads two CompositionLocals provided at the `MainActivity` top level: `LocalClosetSelector` (`ClosetSelectorContext` — interactive closet dropdown, switches the active closet in place) and `LocalOpenSettings`. Settings nav runs `onBeforeNavigate` first (dismiss the host Dialog); the opener also calls `tryOnViewModel.close()` + `stylesViewModel.closeComposer()` since those composers are hosted outside `when(selectedTab)`. Each child self-hides when its local is unprovided (closet dropdown also hides below 2 closets).
- **Try-On provenance**: every try-on carries a `TryOnSourceKind` (outfit/wardrobe/shopping/travel) + `sourceContext` label, persisted on `TryOn` and surfaced via `SourcePill`/`SourceColors`/`sourceMeta` (`tryon/TryOnDesign.kt`). The hero history feed lives in `TryOnHistoryFeed` (`TryOnHistoryScreen.kt`). Past try-ons are a **dedicated full-screen page** (`TryOnViewModel.openHistoryRoot()` → the composer Dialog rendering the feed), reached from the center AI button → Quick sheet ▸ "See past try-ons" — there is **no** Try-Ons sub-tab under Outfits anymore.
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

## Monetization (coins + BYOK)
- **Pricing is server-authoritative.** Firestore holds `config/pricing` (private: `multiplier` + raw `costs`) and `config/publicPricing` (auth-readable: post-multiplier coin prices). The `recomputePublicPricing` trigger is the only writer to `publicPricing`. The multiplier never reaches the client.
- **Client reads `config/publicPricing`** via `gemini/PricingClient` (Firestore listener + SharedPrefs cache + fallback to `CreditPack.COST_*`). Started once in `MainActivity.onCreate`.
- **Cost UI**: `billing/CostBadge` is a small leading icon (`🪙 N` in managed mode, `~Nk tokens` in BYOK, hidden otherwise) — place it as the first child of any AI-trigger button. `ConfirmSpendDialog` + `requiresSpendConfirm()` gate spends ≥ 20 coins or bulks ≥ 5 items (BYOK is never gated).
- **402 → typed exception + global event**: `GeminiRepository.throwIf402` parses `{needed, have}`, emits on the `billing/CreditsEvents.topUp` SharedFlow, then raises `billing/InsufficientCreditsException`. A single `InsufficientCreditsDialog` observer in `MainActivity` listens to the flow and routes "Buy" → Settings tab — so individual VMs don't need to manage dialog state. Per-VM catches only reset loading flags and short-circuit bulk loops via a local `creditsExhausted` flag (prevents N pointless proxy calls on a depleted balance).
- **Ledger**: every charge/refund/purchase appends to `users/{uid}/ledger/{autoId}` (server-only write, user can read).
- **`verifyPurchase`** validates against the Google Play Developer API before crediting; service account must be granted "View financial data" + "Manage orders" in Play Console.
- **Seeding**: `cd firebase/functions && npm run build && node lib/seed.js` writes initial `config/pricing` (defaults in `firebase/functions/src/pricing.ts → DEFAULT_PRICING`). The trigger mirrors to `publicPricing`.
- **Deep rationale & decisions** (markup security, why we dropped reservation/commit complexity, RevenueCat tradeoffs, remaining screen wiring) → `FIN.md`.

## Where to find things (pointers into archive)
- Trips (aggregate of N day-outfits) → archive § "Trips"
- Upload / ingestion / URL import / SAF folder import → archive § "Photo upload & ingestion pipelines"
- Repair & Sync, duplicate detection, audit → archive § "Repair & Sync"
- Visual similarity (embedder, segmenter, scoring, white balance) → archive § "Visual wardrobe search"
- Outfits / composer / Travel / Try-On → archive § "Outfits, Travel, Try-On"
- Shopping closet → archive § "Shopping closet"
- Calendar / wear-Stats (Outfits sub-tabs), Wardrobe stats sheet, Usage/Costs (Settings ▸ Advanced) → archive § "Insights tab" (pages redistributed; Insights tab removed)
- Settings redesign (single scroll page, `settings/v2/`, Advanced sub-screen, destructive confirm) → `design_handoff_settings_v1/README.md`
- Offline mode internals → archive § "Offline mode (deep notes)"
- Token usage / pricing → archive § "Token usage tracking"
- Analytics → archive § "Analytics"
- Release / signing / keystore / Play / Firebase → archive § "Release process"
- ML model assets (embedder / segmenter) → archive § "ML model assets"
