# LibreLookAI architecture refactor blueprint

## Context

The app works and ships, but its structure shows its growth history: a single Gradle module,
~188 files / ~48k lines, one 1100-line `AppContent.kt` hosting 14 activity-scoped ViewModels
wired together by ~25 `LaunchedEffect` bridges, no DI, no local database (hand-rolled Gson JSON
caches with fragile invariants like the "cache-folder rule"), no navigation library (a
`selectedTab: Int` plus fullscreen Dialogs used as a back-stack — the root cause of the entire
"Window quirks" section in CLAUDE.md), and repositories instantiated ~10 separate times with no
interfaces — which is why only 12 test files exist for 48k lines.

This document is the target architecture plus a strangler migration path. A big-bang rewrite is
explicitly **not** the plan; every phase ships independently through the normal release process.

## Hard constraints (answers to the key questions)

### Drive data format does NOT change
Drive remains the canonical, user-owned store with the exact same layout: `LibreLookAI/` root,
closet subfolders keyed by `folderId`, the `{cutoutDriveId}_cutout.webp` / `_original.webp` /
`{cutoutDriveId}.json` sidecar triplet (legacy `.png`/`.jpg` still readable), `_outfits.json` /
`_tryons.json` per closet, `_shopping/`, `_trends_cache.json`. The refactor changes only the
*client-side* representation: Room becomes a local mirror/index of Drive metadata. Consequences:

- Reinstalls and second devices keep working unchanged.
- Old app versions interoperate with new ones mid-rollout (no lockstep upgrade).
- No user-data migration, ever; rollback of any phase cannot corrupt Drive.
- Optional later optimization: delta sync via the Drive Changes API instead of folder listings —
  also requires no format change.

### No new server-side infrastructure
Everything new is on-device AndroidX: Room (SQLite, bundled in Android), WorkManager, DataStore,
Hilt. No servers, no new Firebase resources. Existing Cloud Functions (`geminiProxy`,
`verifyPurchase`, pricing trigger) and the BYOK path are untouched.

### No user-visible UX breakage
Each phase keeps screen behavior identical and swaps what's underneath:

- Room replaces the JSON cache files *behind the same repository APIs*; on first launch after
  the update, Room is **seeded by importing the existing local JSON caches** — no re-download
  from Drive, no visible restore moment.
- Navigation converts one screen at a time; a NavHost destination can overlay the bottom bar
  exactly like today's fullscreen Dialogs.
- Explicit regression checklist for the flows the current hand-rolled rules protect: offline
  mode, reinstall-restore, closet switching, calendar pick mode, optimistic-write rollback.
- Every phase is independently shippable and revertible.

## What we keep (the product logic is good; the plumbing isn't)

- **Drive as user-owned backend** — the privacy story. It becomes the *remote*, not the source
  of truth.
- Centralization wins: `util/ImageEncoding` (WebP policy), `gemini/TagNormalizer` (closed color
  vocabulary), `DriveImage.toPromptJson` (single wardrobe→prompt encoder),
  `gemini/FashionTrendsCache`, the shared `AppFab` / `SelectionActionBar` / `DetailActionBar`
  system, `WearHistory` / `OutfitsPrompts` prompt builders.
- Optimistic-write UX (instant mutations, Drive syncs behind) — keep the behavior, replace the
  mechanism.
- The Robolectric stateless-`*Content` composable testing pattern.
- Mandatory i18n discipline (all 31 locales per change).

## Target architecture, by layer

### 1. Multi-module Gradle project (enforced boundaries)

```
app/                      thin shell: Application, MainActivity, NavHost, DI graph
core/
  model/                  pure data classes (DriveImage, Outfit, Trip, …) — zero Android deps
  database/               Room: entities, DAOs — the single source of truth
  sync/                   Drive sync engine: remote API + pending-mutation queue + WorkManager
  ai/                     AiClient interface + GeminiClient impl, prompt builders, usage recording
  ml/                     embedder, segmenter, pHash, color histogram
  designsystem/           theme/palettes, AppFab, SelectionActionBar, DetailActionBar, shared grids
  common/                 Result types, dispatchers, time, no-dep utils
feature/
  wardrobe/  outfits/  calendar/  travel/  tryon/  shopping/  settings/  onboarding/  billing/
```

Each `feature/*` depends only on `core/*`, never on another feature. Cross-feature data flows
through `core/database` Flows (both features observe the same tables), not VM-to-VM
`LaunchedEffect` bridges. The compiler then enforces what the CLAUDE.md "Placement rule" prose
currently asks reviewers to police.

### 2. Room as single source of truth; Drive demoted to sync target

The single highest-leverage change. Today: in-memory VM state + per-folder JSON cache files +
prose rules ("key every cache write off the *owning* folderId, never the active filter…") that
are easy to violate and have caused real bugs.

- Entities: `ClothingItem`, `Outfit`, `OutfitEvent`, `Trip`, `TryOn`, `Closet` — keyed by
  `folderId` / Drive ID exactly as today.
- Repositories write to Room first (optimistic, instant UI via Flow) and enqueue a
  `PendingMutation` row. A WorkManager-driven `SyncEngine` drains the queue to Drive, retries
  with backoff, rolls back / surfaces conflicts on failure.
- Offline mode falls out for free: UI always reads Room; the queue drains when back online.
  `LocalIsOffline` gating remains only for AI-trigger buttons.
- Restore-on-reinstall = initial sync pass with progress from the engine (replaces the
  hand-rolled restore-overlay latch/debounce).
- Image bytes stay as files (Coil disk cache + Drive); Room holds metadata only.

### 3. Dependency injection (Hilt) + interfaces at the seams

- One `DriveService`, one `AiClient`, one `SyncEngine`, one `SettingsRepository` — `@Singleton`,
  injected. Kills the ~10 independent `DriveRepository(app, GoogleAuthManager(app))` instances.
- Interfaces for the I/O seams (`AiClient`, `DriveService`, repositories) so ViewModels and
  domain logic are testable with fakes.
- Kills the static-global workarounds: `ImageEncoding.tier` mirror, `GeminiProgress`'s single
  global slot, the `AiRetry.action` holder → injected scoped objects exposing Flows.

### 4. Real navigation (Navigation Compose, type-safe routes)

- `NavHost` with serializable route types: top-level tabs + detail destinations
  (`ItemDetail(id)`, `OutfitDetail(id)`, `TripViewer(id)`, `Composer(args)`, Settings graph…).
- Detail viewers become destinations, not fullscreen `Dialog`s — deleting the entire class of
  Window-quirk bugs (severed `LocalContext` locale chain, zero insets, per-slot-lambda
  re-providing, `effectiveBottom = max(…)` hacks). Dialogs return to being actual dialogs.
- ViewModels scoped to destinations (`hiltViewModel()` per back-stack entry), not 14 VMs hoisted
  for the app's lifetime. Cross-tab flows like "Wear → calendar pick mode" become a navigation
  call with arguments instead of `pendingCalendarWearId` fields polled by another screen.
- Settings' parallel `SettingsRoute` enum back-stack merges into the same graph.

### 5. ViewModel shape: thin, per-screen, over use-cases

- `WardrobeViewModel` (1391 lines + 5 extension files) is grid state + upload pipeline + audit +
  import + bg-fix + search + wake-lock management. Split by *responsibility*: the VM keeps grid
  state; long-running pipelines (upload, re-tag, bg-fix, WebP convert) become injectable
  **use-case classes** run via the SyncEngine / WorkManager — surviving process death without
  hand-rolled wake-lock + foreground-service plumbing inside a ViewModel.
- Per screen: one immutable `data class XUiState` exposed as a single `StateFlow`, sealed
  one-shot events. No `internal var` soup shared across extension files.

### 6. AI layer: typed results, one error path

- Replace "Gemini returns `null` on failure, every caller degrades" with a sealed
  `AiResult<T> = Success | NotConfigured | InsufficientCredits | Failure(reason, retryable)`.
  The compiler forces each caller to handle failure; the existing `AiEvents` / `CreditsEvents`
  global dialogs become one observer of the client's event Flow (already half-true — formalize).
- Usage recording (`TokenUsageRepository.recordUsage`) becomes a decorator around `AiClient`,
  not a call-site obligation.
- Prompt builders stay pure functions (`OutfitsPrompts`, `WearHistory`, `PromptItemJson`) in
  `core/ai` — they're already the cleanest code in the app.

### 7. Settings & flags

- DataStore (typed, Flow-based) replaces scattered SharedPrefs (`UserPreferences`,
  `OnboardingState`, pricing cache); the Gemini API key moves to Keystore-backed encrypted
  storage.
- `FeatureFlags` / `ManagedBilling.enabled` stay BuildConfig-driven but are injected behind an
  interface so a debug screen can toggle them and tests can pin them.

### 8. Testing strategy (the payoff of 2 + 3 + 6)

- `core/model`, prompt builders, normalizers: plain JUnit (as today).
- Repositories + SyncEngine: JVM tests with in-memory Room + a fake `DriveService` — the
  optimistic-write / rollback / conflict rules become *tested invariants* instead of CLAUDE.md
  prose.
- Screens: keep the Robolectric stateless-`*Content` pattern; ViewModels become constructible
  with fakes so their flows are testable too.

## Migration plan (strangler, four phases)

1. **DI first** — introduce Hilt, make `DriveRepository` / `GeminiRepository` injected
   singletons behind interfaces. Mechanical, lowest risk, immediately de-duplicates repository
   instances and unlocks fake-based testing. No behavior change.
   **Status: LANDED (June 2026).** Hilt 2.59.2 + KSP 2.3.9 (AGP 9 built-in Kotlin requires
   Hilt ≥ 2.59 and KSP, not kapt). `LibreLookAIApp` (@HiltAndroidApp) + @AndroidEntryPoint
   MainActivity; `GoogleAuthManager` / `DriveRepository` / `GeminiRepository` are @Singleton
   @Inject; the 12 repo-constructing ViewModels are @HiltViewModel; `AppContent` takes the
   injected `DriveRepository` instead of remembering an Activity-context-built one.
   *Interface extraction was deferred* — it adds value only when the first fake-based tests
   land (phase 2), and extracting it now would churn the 4-file `DriveRepository` split twice.
2. **Room under the repositories** — replace the JSON cache files feature-by-feature (wardrobe
   first, then outfits/events, trips, try-ons, shopping), keeping VM-facing APIs stable. Seed
   Room from the existing JSON caches on first launch (no Drive re-download). Delete each
   cache-folder rule from CLAUDE.md as its feature lands.
   **Status: wardrobe-items slice LANDED (June 2026).** Room 2.8.4 (KSP). The shared
   `wardrobe_cache_{folderId}.json` files — read/written independently by WardrobeViewModel,
   OutfitsViewModel and ShoppingClosetViewModel — are replaced by one `@Singleton`
   `data/local/WardrobeItemStore` (interface) backed by `RoomWardrobeItemStore`/`LocalDatabase`.
   Items are keyed by Drive ID, so "an item lives in exactly one folder" is now a DB invariant
   (the JSON files could disagree during moves). Legacy JSON caches are seeded once per folder on
   first access (no Drive re-download), then ignored; `clearFolder` deletes them. The handful of
   main-thread synchronous cache writes (shopping move/delete, wardrobe saveSidecar /
   moveItemsToLocation / deleteItems) moved into their adjacent background coroutines, ahead of
   the Drive calls — same ordering, off-main. Invariants are tested in
   `data/local/WardrobeItemStoreTest` (8 JVM tests).
   **Outfits slice LANDED (June 2026).** `data/local/OutfitStore` (Room, DB v2 additive
   migration): rows hold the `Outfit` as opaque Gson JSON keyed by outfit id, so an outfit is
   homed in exactly one folder — the legacy `styles_cache_*.json` files duplicated fresh
   empty-`folderId` outfits into every folder's cache (double entries in the all-locations
   Phase-1 read). Reads restore the `@Transient Outfit.folderId` from the row (the legacy files
   lost it entirely); the create/edit save paths now stamp `folderId` (in-memory only — the
   field never reaches Drive JSON). Legacy files seed once per folder, marker-gated, kept as a
   downgrade net. Tests: `data/local/OutfitStoreTest`.
   **Outfit-events slice LANDED (June 2026).** `data/local/OutfitEventStore` (Room, DB v3):
   per-folder calendar-wear rows as opaque Gson JSON keyed by event id; legacy
   `outfit_events_cache_*.json` seeds once (marker-gated, file kept as downgrade net) — incl.
   the pre-rename `styleId` alias. `OutfitEventsViewModel.persist()` keeps its Drive-first
   ordering. Tests: `data/local/OutfitEventStoreTest`.
   **Trips slice LANDED (June 2026).** `data/local/TripStore` (Room, DB v4): the single global
   trips list as opaque Gson JSON keyed by trip id, plus a one-row seed marker (the per-folder
   marker pattern degenerates to a constant key). Legacy `trips_cache.json` (bare array) seeds
   once, kept as downgrade net. `TripsViewModel`'s `mutateTrip` synchronous cache write moved
   into its adjacent `viewModelScope.launch`, ahead of the Drive save — same ordering, off-main.
   Tests: `data/local/TripStoreTest`.
   **Try-ons slice LANDED (June 2026).** `data/local/TryOnStore` (Room, DB v5): the single
   global try-on metadata list as opaque Gson JSON keyed by try-on id, plus a one-row seed
   marker. Image bytes stay files in the Drive cache dir (`tryon_{imageDriveId}.png`) — the
   store holds metadata only, same rule as wardrobe. Legacy `tryons_cache.json` seeds once,
   kept as downgrade net; the ViewModel's `migrateSource` mapping (pre-`sourceKind` entries)
   applies on store reads exactly as on Drive reads. Tests: `data/local/TryOnStoreTest`.
   **Remaining slices**: token-usage/trends caches (deliberately deferred — token-usage has
   its own per-month structure + Drive sync; `trends_cache.json` is the layered
   disk→Drive→Gemini `FashionTrendsCache`).
3. **Navigation Compose** — add the NavHost, convert Dialog-viewers to destinations one at a
   time; delete each Window-quirk workaround as its screen converts. Scope ViewModels to
   destinations as screens convert.
   **Status: NavHost + first destination LANDED (June 2026).** navigation-compose 2.8.5 with
   type-safe `@Serializable` routes (`AppNavigation.kt`): `HomeRoute` wraps the whole pre-existing
   tabbed Scaffold (tabs remain `selectedTab`-driven inside it); `TripViewerRoute(tripId)` replaces
   the hoisted `tripViewerTripId` fullscreen mode that lived inside `TravelScreen` (system back =
   pop, `hideChrome` lost its trip-viewer arm). Three load-bearing transition rules (documented in
   CLAUDE.md § Navigation): global overlay hosts (composer/try-on Dialogs, AI/credits dialogs,
   restore overlay) moved *outside* the NavHost so they survive any destination; tab jumps from
   global hosts go through `goToTab()` (pops overlaying destinations back to Home); destinations
   pin `LocalViewModelStoreOwner provides activity` because a defaulted `viewModel()` inside a
   destination scopes to the back-stack entry and would fork fresh VM instances (~70 call sites
   rely on activity scoping).
   **Travel planner destination LANDED (June 2026).** `TravelPlannerRoute` replaces the hoisted
   `travelPlannerMode` flag (and the `hideChrome` travel arm — `hideChrome` is now purely
   selection-bar driven). The planner-lifecycle effects (packing-list → trip auto-create,
   source-closet seeding, `navigateToTrip` collection) moved from `TravelScreen` into the new
   `travel/TravelPlannerScreen`, because Home — and the Travel tab with it — is not composed
   while a destination overlays it; a planner-created trip pops back to Home before navigating
   to `TripViewerRoute`, so back from the viewer lands on the trips list.
   **Outfit viewer destination LANDED (June 2026).** `OutfitViewerRoute(source, outfitIds,
   initialOutfitId, tripId)` replaced the three `OutfitFullScreenViewer` Dialog hosts (outfit
   list, prediction result, trip day list). Since lambdas can't ride a route, the per-host
   callback wiring moved into `outfit/OutfitViewerDestination`, switched on a source
   discriminator; outfits resolve live from the VMs (deletes/edits during viewing stay
   correct), every prediction-source close path clears the prediction (so the Outfits screen's
   navigate trigger can't re-fire on pop), and the viewer-launched tag dialogs + trip
   wear-date picker are hosted in the destination because the launching surface beneath is
   not composed while overlaid. The viewer itself lost its Dialog wrapper + window plumbing
   (one Window-quirk workaround down). The Outfits screen now flips to its Calendar sub-tab
   via a `pendingCalendarWearId` effect rather than only at call sites. This is the pattern
   for the remaining viewer conversions.
   **Composer destinations LANDED (June 2026).** `TryOnRoute` (composer / result / history feed
   / history detail — the "try-on history root" item ships with it) and `OutfitComposerRoute`
   replaced the two global fullscreen Dialogs. Because their ~15 openers are nav-less VM calls,
   the conversion is **state-mirrored**: the VM open-flags stay the source of truth, an
   `AppContent` observer navigates on the false→true transition, and each destination pops
   itself on true→false; paths that pop by other means (`goToTab`) must also close the VM or
   the next open won't navigate. System back routes through the VM close via in-screen
   `BackHandler`s (try-on gained layered back: detail → feed → composer → close — the old
   Dialog's back closed the whole surface in one step; the outfit composer keeps its
   discard-confirm). Both screens lost their Dialog wrappers + window plumbing; their sibling
   pickers/sheets stay window-based and unchanged. The outfit-viewer destination also gained
   the status-bar inset the Dialog window used to provide.
   **Remaining**: convert `FullScreenViewer` (mostly unblocked now that the composers are
   destinations — but audit its window-based hosts first, e.g. `ComposerSuggestionsViewer` is
   still a Dialog); convert the tabs themselves to destinations; merge Settings'
   `SettingsRoute` enum back-stack; then delete the Window-quirk workarounds +
   per-destination VM scoping.
4. **Modularize last** — once dependencies are sane, moving packages into Gradle modules is
   mechanical (`scripts/kt_split.py`-style, compiler-driven).

## Verification (per phase)

- `./gradlew :app:assembleDebug` + `./gradlew testDebugUnitTest` green at every step.
- New invariant tests per migrated feature: optimistic write → Room → sync queue → fake-Drive
  drain → rollback path.
- Manual regression checklist: offline mode, reinstall-restore, closet switch, calendar pick
  mode, WebP conversion idempotency — the flows the current hand-rolled rules protect.
- Drive-format compatibility check per phase: a build from `main` and the phase branch pointed
  at the same test account must round-trip each other's data.
