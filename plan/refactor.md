# LibreLookAI architecture refactor blueprint

## Status at a glance (June 2026)

The four *migration phases* below have all landed their mechanically-possible slices, but the
real checklist is the eight **Target architecture** sections — several are deliberately
deferred and inter-blocking:

| § | Layer | Status |
|---|-------|--------|
| 1 | Multi-module Gradle | **Partial** — `:core:model`/`:core:common`/`:core:database`/`:core:ml`/`:core:sync`/`:core:ai` landed (phase 4); `core/designsystem` blocked on splitting the 31-locale `res/`, `feature/*` on § 5 |
| 2 | Room as source of truth + SyncEngine | **Operationally complete** (June 2026) — all five JSON-cache slices landed (phase 2) as opaque-JSON cache rows; the `PendingMutation` queue + `SyncEngine` drain **every converted metadata write**: wardrobe (sidecar/delete/move), shopping, outfits (`outfit.syncFolder`), calendar wears (`outfitEvent.syncFolder`), trips (`trip.save`/`trip.delete`), plus the WorkManager process-death backstop (see phase 2 status). The real-entity / Flow-read conversion is deliberately carried into § 5 |
| 3 | DI + interfaces at the seams | **Partial** — Hilt landed (phase 1); the gemini↔drive↔billing package cycles are broken (June 2026, see phase 1 status), making the layering acyclic; interface extraction at the I/O seams pending; static globals (`ImageEncoding.tier`, `GeminiProgress` slot, `AiRetry.action`) still alive |
| 4 | Navigation Compose | **Done** (phase 3 complete; per-destination VM scoping deferred to § 5) |
| 5 | Thin VMs over use-cases | **Planned** (June 2026) — the keystone blocker: gates `feature/*` modules, destination-scoped VMs, and removal of the cross-VM `LaunchedEffect` bridges. Execution plan below (phase 5) |
| 6 | Typed `AiResult` | **Started** — the notice path carries a semantic `AiErrorReason` enum instead of `R.string` ids (June 2026, the `:core:ai` enabler); the sealed `AiResult<T>` return type is not started — Gemini still returns `null` on failure |
| 7 | DataStore + Keystore settings | **Started** — the BYOK Gemini key is AES-GCM-encrypted at rest via Android Keystore (June 2026; `ApiKeyStore` API unchanged, plaintext pref migrates on first read, graceful plaintext fallback if Keystore is unavailable); DataStore for `UserPreferences`/`OnboardingState`/pricing cache and the flag interface are not started |
| 8 | Testing strategy | **Partial** — store invariant tests landed; fake-based repository/VM tests blocked on § 3 interfaces |

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
   **Seam inversion started (June 2026).** The wrong-direction package edges that kept
   `gemini`/`data/drive`/`billing` mutually cyclic are gone: `DriveRepository`'s
   `TokenUsageRepository` import was dead (deleted); the token-usage Drive file name is now a
   drive-side constant (`TOKEN_USAGE_DRIVE_FILE_NAME` in `DriveRepositoryDocs.kt`) that
   `TokenUsageRepository.DRIVE_FILE_NAME` aliases — so the sync layer never imports from
   `gemini/`; the OkHttp `Call.await()` bridge moved to `:core:common` (it was the *only*
   `data.drive` symbol gemini/auth/weather imported); and `CreditPacks` (pure product/price
   constants) moved to `:core:model`, removing the `gemini → billing` edge under
   `UsageCategory`. Layering is now acyclic: sync (`data/drive`) sits below ai (`gemini`),
   which keeps its legitimate `TokenUsage`/`FashionTrendsCache` → drive dependencies.
   Remaining knots for a future `core/ai`/`core/sync` extraction: `GeminiRepository`'s
   `R.string` notice ids + `BuildConfig` key/proxy fields + the fully-qualified
   `billing.ManagedBilling.enabled` read (all § 7-shaped: config/flags behind an interface),
   and the `internal` drive extension functions that would cross a module boundary.
   *(All three knots were cut in June 2026 — see the `:core:ai` extraction in phase 4.)*
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
   **SyncEngine slice 0 LANDED dark (June 2026).** The queue + engine core exist with zero
   behavior change — nothing enqueues yet:
   - `pending_mutations` table (DB v6, additive) + `PendingMutationStore` in `:core:database`
     (`data/local/PendingMutationDb.kt` / `PendingMutationStore.kt`): strict global FIFO of
     Drive-bound writes; `payload`/`rollback` are opaque JSON owned by the handler (the
     cache-row precedent), `targetId`/`folderId` carry identity per the cache-folder rule.
   - `SyncEngine` + `MutationHandler` in `:core:sync` (`data/drive/SyncEngine.kt`): handlers
     register feature-side via Hilt `@IntoSet` (a `@Multibinds` declaration permits the
     current empty set); the engine drains oldest-first under a single-flight mutex. Drain
     rules (encoded in `SyncEngineTest`, plain JUnit over a fake store — the engine only sees
     the store *interface*, the first § 3-style seam paying off): `Retry` halts the drain
     (FIFO — later writes can't overtake), `Permanent` and attempt-exhaustion (8) roll back
     via the handler and continue, an unknown `kind` (downgrade racing a newer queue row)
     halts without deleting. `:core:sync` now `api`-depends on `:core:database`.
   - Tests: `PendingMutationStoreTest` (4, Robolectric/Room) + `SyncEngineTest` (6, JUnit).
   **Slice 1 LANDED (June 2026): wardrobe sidecar saves go through the queue.**
   `WardrobeViewModel.saveSidecar` (every tag edit, AI re-tag and bulk re-tag funnels through
   it) now writes Room first and enqueues a `wardrobe.sidecarSync` mutation instead of calling
   `drive.upsertSidecar` inline. The pattern pieces:
   - **Payload-free mutations — Room is the source of truth.** The handler
     (`wardrobe/WardrobeSidecarSync.kt`, registered `@Binds @IntoSet`) re-reads the item's
     *current* tags and owning folder from `WardrobeItemStore.find(driveId)` at apply time, so
     a deferred drain never resurrects stale tags and a move between enqueue and drain lands
     the sidecar in the new folder. Item gone → `Permanent` (deleted; nothing to undo); any
     Drive failure → `Retry` (Room already holds the edit, so there is no rollback state —
     a transient failure now *retries* instead of silently losing the Drive write, and the
     row survives process death).
   - **Post-apply feedback**: the handler stamps the new sidecar id onto the Room row
     (`WardrobeItemStore.setSidecarId`) and emits on its `sidecarSaved` flow; the VM collects
     it to patch in-memory `sidecarDriveId` (move/delete use it to relocate/remove the
     sidecar file).
   - **Drain triggers**: inline after each enqueue (the engine mutex makes overlap safe) +
     a catch-up `LaunchedEffect(isOnline)` in `AppContent` on app start and every
     offline→online transition + the engine's own backoff re-drain (June 2026): a drain
     halted by a transient `Retry` self-schedules another drain with exponential backoff
     (30s → 10min cap) — otherwise a queue stalled by one 503 while the device *stays*
     online would wait for the next app start. Unknown-kind halts don't self-retry (only
     an upgrade resolves them).
   - Deliberate behavior change: the immediate `error_tag_save_failed` snackbar on a failed
     sidecar PATCH is gone — transient failures retry silently; after 8 attempts the queue
     gives up (the edit stays local and re-syncs with the next save/load).
   **Slice 2 LANDED (June 2026): wardrobe item deletes go through the queue.**
   `WardrobeViewModel.deleteItems` keeps its optimistic local update (state + cache vanish
   first) but replaces the old fire-and-forget per-file `runCatching { drive.deleteFile }`
   — which silently orphaned the Drive triplet whenever a delete failed or the user was
   offline — with one `wardrobe.deleteItem` mutation per item. Unlike the sidecar sync this
   is **payload-carrying** (`{fileIds:[cutout, original?, sidecar?]}`): the Room row is
   removed before the drain, so there is nothing to re-read at apply time. Handler
   (`wardrobe/WardrobeDeleteSync.kt`): network failure → `Retry` (re-running is safe —
   Drive DELETE of an already-deleted file is an ignored 404); no rollback (the deletion is
   locally committed, exactly like the old best-effort path). Note `deleteFile` ignores
   HTTP-level errors by design (matching the old semantics); response-code-aware retries
   can come with the § 3 `DriveService` interface.
   **Ordering note** (why FIFO matters here): a pending `sidecarSync` enqueued before a
   delete of the same item drains first, finds the row gone, and skips as `Permanent` —
   it can never recreate a sidecar for a deleted item.
   **Slice 3 LANDED (June 2026): wardrobe item moves go through the queue — the first
   mutation with a real rollback.** `moveItemsToLocation` keeps its optimistic re-home
   (state + recently-moved markers + Room, exactly as before) and enqueues one
   `wardrobe.moveItem` per item carrying `{source, target, originalDriveId?,
   sidecarDriveId?}` (the from→to pair must ride along: Drive's PATCH needs the parent
   being removed, and FIFO makes chained moves correct — A→B always drains before B→C).
   Handler (`wardrobe/WardrobeMoveSync.kt`): the cutout PATCH decides the outcome,
   original/sidecar stay best-effort `runCatching` (old semantics); re-running after a
   client timeout is safe (Drive ignores stale `removeParents`); row gone → `Permanent`
   (the delete mutation queued behind it removes the files wherever they live). On attempt
   exhaustion the handler's `rollback` re-homes the Room row back to the source — only if
   it still sits in *this* mutation's target, so a later move supersedes — and emits
   `moveRolledBack`; a VM collector undoes the UI half (marker, splice back into view,
   `wardrobe_move_failed` banner). Two deliberate changes: transient failures now retry
   for ~8 attempts before rolling back (the old path rolled back on the *first* error),
   and a pending sidecar of a moved item materializes directly in the target folder (the
   payload-free sidecarSync re-reads the re-homed row). The composition is the payoff:
   sidecar→move→delete of one item drain in enqueue order, each handler re-checking
   reality first. Also deleted the unused Drive-first `moveItemsToFolder` (zero callers).
   **Slice 4 LANDED (June 2026): the shopping closet's move/delete reuse the same
   mutations.** `ShoppingClosetViewModel.deleteItems` enqueues `wardrobe.deleteItem`;
   `moveToCloset` enqueues `wardrobe.moveItem` (same handlers — shopping items live in the
   same `WardrobeItemStore` under the `_shopping` folder). The interesting change is the
   failure path: the old `onMoveFailed` callback chain (`ShoppingListTab` →
   `wardrobeViewModel::undoItemsMovedTo`) **cannot survive a queued retry that may complete
   in a later session**, so it became event-driven — `MoveRollback` now carries
   `(driveId, source, target)` and *both* VMs collect `moveRolledBack`: the shopping VM
   splices the item back into the wishlist + shows the error when the source is the
   shopping folder; the wardrobe VM drops the optimistic target-homed copy from its view,
   removes the recently-moved marker and (for wardrobe-internal moves) shows the error.
   `undoItemsMovedTo` and the `onItemsMoveFailed` param chain were deleted.
   **Slice 5 LANDED (June 2026): every outfit `_outfits.json` save goes through the queue —
   the first whole-file (not per-item) conversion.** All 11 Drive-first save sites (9 in
   `OutfitsViewModel`, 2 in `OutfitsTags.kt`) flipped to local-first: update state →
   `persistOutfitFolders(affected)` → `onDone(true)`. The helper (which *replaced*
   `refreshOutfitsLocalCache()`) mirrors the outfit list into the per-folder `OutfitStore`
   rows — view scope ∪ affected, so an out-of-scope save target is cached too — then
   enqueues one payload-free `outfit.syncFolder` mutation per affected folder and drains.
   - Handler (`outfit/OutfitFolderSync.kt`): re-reads `OutfitStore.outfitsFor(fid)` at apply
     time and writes `gson.toJson(list)` (`Outfit.folderId` is `@Transient`, so store-row
     serialization ≡ Drive-file serialization). Back-to-back edits coalesce into the latest
     snapshot; duplicate queued mutations for one folder are idempotent re-writes.
   - **Wipe guard**: the `outfit_folders` marker is exposed as `OutfitStore.hasFolder()`;
     the handler refuses (`Permanent`) to write a never-cached folder — an empty read there
     means "unknown", not "no outfits". Guard tests in `OutfitStoreTest`.
   - Accepted semantic flips: `onDone(true)` now means *locally committed + queued* (the
     trip auto-create on `addOutfits` and trip-delete cascade on `deleteOutfitsByIds`
     proceed on local commit); `setOutfitLoved`'s optimistic-flip rollback and the per-site
     `error = e.message` surfaces are gone (transient failures retry silently — slice 1's
     deliberate change); `applyTagSuggestions`' interim `isSaving` state dropped (the save
     is local-now); `commitOutfit`'s `isComposerSaving` overlay now only spans the
     pre-commit `listFiles` name resolution.
   - Accepted filter unification: the handler writes store content, so the loved/tags
     "rewrite every folder" loops collapsed to the outfit's home folder only (an outfit
     lives in exactly one folder — no other folder's file changes), and a legacy
     empty-`folderId` outfit now lands in exactly one folder's Drive file (its store home)
     instead of being duplicated into every affected file.
   - Unchanged: pre-commit name resolution (`idToNameAllFolders` / `drive.listFiles`) stays
     a live Drive read before the local commit — offline-gated surfaces mean it runs online,
     and converting it to a store read is a separate decision. Display order converges
     (handler writes store order, which is the state order the helper just wrote).
   **Slice 6 LANDED (June 2026): calendar wear saves go through the queue.** The events twin
   of slice 5, and the cheapest conversion yet — `OutfitEventsViewModel.persist()` is the
   single funnel for all 8 calendar mutations (record/move/copy/delete wears, loved toggle).
   It now updates state + the active folder's `OutfitEventStore` rows first, then enqueues a
   payload-free `outfitEvent.syncFolder` (handler `outfit/OutfitEventSync.kt`, registered in
   `OutfitSyncModule`; `OutfitEventStore.hasFolder()` wipe guard, tests in
   `OutfitEventStoreTest`). Kept the legacy single-target rule: the whole merged list
   persists into the active folder (or the first of All locations) — same file the old
   Drive-first write targeted. The old "state updates only on Drive success" ordering flipped
   to optimistic, matching every other converted path.
   **Slice 7 LANDED (June 2026): trip saves/deletes go through the queue.** Trips differ
   structurally — each trip is its own name-addressed Drive file (`{tripId}.json` in
   `_trips/`), so the mutations are per-trip, both payload-free (`travel/TripSync.kt`,
   `TravelSyncModule`):
   - `trip.save` (targetId = tripId): the handler re-reads the trip's current `TripStore` row
     at apply time (gone → `Permanent`; the delete queued behind it owns the file) and
     resolves the trips folder itself via `getOrCreateTripsFolder(getOrCreateFolder())` — so
     `upsertTrip`/`mutateTrip` no longer need Phase 2's `folderId` to have loaded (the old
     `error_trips_folder_not_ready` guard and `mutateTrip`'s silent skip are gone; a trip
     created before the folder resolves still syncs). No per-trip wipe guard needed: a
     missing row skips, it can never write an empty list over anything.
   - `trip.delete` (targetId = tripId): resolves the file by stable trip id
     (`findTripFileId`, new public wrapper over the internal `findFileIdByName`) instead of
     the session-local `driveIdsByTripId` map — which is now deleted entirely, fixing the old
     quirk where deleting a trip before Phase 2 load populated the map orphaned the Drive
     file. Idempotent (file already gone → `Success`).
   `upsertTrip`/`deleteTrip(s)` flip to local-first (state + `TripStore` + enqueue + drain →
   `onDone(true)`/`onDeleted(...)`, same locally-committed-means-done semantics as slice 5);
   the trip-delete cascade thus proceeds on local commit.
   **Queue conversion COMPLETE for all § 2 entities** — wardrobe items (sidecar/delete/move),
   shopping, outfits, calendar wears, trips. Try-on metadata writes and the token-usage /
   trends caches stay on their existing paths (deliberate: try-ons are created by an
   online-only Gemini call and saved Drive-first with image bytes; the caches are
   server-reconstructible).
   **WorkManager drain backstop LANDED (June 2026) — § 2's operational scope is complete.**
   `data/drive/SyncWork.kt` (`:core:sync`, work-runtime-ktx 2.10.0): the engine now takes a
   `DrainScheduler` seam (so `SyncEngineTest` stays plain JUnit over a fake; two new tests
   encode the contract) and calls `ensureScheduled()` whenever a drain starts over a
   non-empty queue — registering one unique, network-constrained `SyncDrainWorker`
   (exponential backoff 30s) as the *process-death* path: if the app dies mid-queue, the OS
   re-runs the drain instead of waiting for the next app start. Design choices: the engine
   never *cancels* the work (a drain that empties the queue in-process would otherwise
   cancel the very worker running it — the leftover no-op run is the accepted cost); the
   worker is a plain `CoroutineWorker` reaching the Hilt graph via an application
   `EntryPoint` (default reflective factory, no androidx.hilt-work); it returns retry while
   the queue is non-empty, so a post-downgrade unknown-kind halt rides WorkManager's capped
   backoff until an upgrade resolves it. Scheduling is best-effort (`runCatching`) — the
   in-process triggers remain primary.
   Remaining § 2 follow-up (deliberately coupled to § 5): the real-entity / Flow-read
   conversion — replacing the opaque-JSON store rows with queryable entities and the VMs'
   poll-style reads with Room Flows requires restructuring the VMs, i.e. § 5's use-case
   extraction. Carry it there.
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
   **Item viewer destination LANDED (June 2026).** `ItemViewerRoute(source, itemIds,
   initialItemId)` replaced the five `FullScreenViewer` Dialog hosts (wardrobe grid, shopping
   list, try-on detail, outfit-composer stack, and the outfit viewer's per-item tap — the
   audit found that fifth host inside `OutfitFullScreenViewer`). `wardrobe/ItemViewerDestination`
   resolves items live per source (wardrobe/shopping = route-id snapshot over the owning VM's
   list; tryon = combined wardrobe+shopping pool; outfit = the outfit viewer's item pool;
   composer = live from the slots, so a slot swap stays in sync) and hosts the cascade-aware
   delete confirm, extracted to `wardrobe/WardrobeDeleteDialog.kt` and shared with the grid's
   selection delete. Two small deliberate behavior changes: a wardrobe single-item delete now
   confirms *over* the open viewer instead of closing it first (the dialog lives in the
   destination, which a pop would unmount), and the nav-bar re-tap viewer dismissal
   (`dismissWardrobeViewerTrigger`) is gone (the bar isn't reachable under a destination).
   The viewer lost its Dialog wrapper + window plumbing.
   **Tabs as destinations LANDED (June 2026).** The `when(selectedTab)` dispatch inside
   `HomeRoute` became a nested tab NavHost (`OutfitsTabRoute`…`SettingsTabRoute`); the bottom
   bar stays in the surrounding Scaffold. Semantics were preserved exactly: the nested back
   stack is single-entry at tab level (`selectTab` replaces via `popUpTo(graph.id){inclusive}`),
   so system back still exits from any tab; tab switches are transition-free; `selectedTab` is
   now *derived* from the current nested destination (`homeTabIndex`); `navResetTick` still
   drives sub-tab resets. The controller is hoisted above the entry gate (tour replay keeps the
   tab — replacing the old hoisted `rememberSaveable` int), and onboarding's "go to wardrobe"
   finish sets a `pendingHomeTab` consumed once the graph exists. Each nested tab destination
   re-pins `LocalViewModelStoreOwner provides activity`.
   **Settings merge LANDED (June 2026).** The local `SettingsRoute` enum back-stack inside
   `SettingsScreen` is gone: Profile edit / Advanced / About / Usage / Buy credits are nested
   tab-graph destinations pushed over `SettingsTabRoute` (`settings/SettingsNavGraph.kt`'s
   `settingsDestinations`), so system back pops them (it previously exited the app), the bar
   keeps Settings highlighted (`homeTabIndex` maps sub-routes to slot 5), and a Settings tab
   re-tap pops to the root page (the old `navResetTick` stack reset). The destructive-op
   confirm wiring is shared via `SettingsDestructiveConfirmHost` (main page WebP convert +
   Advanced fix rows; the buy-credits CTA navigates).
   **Workaround cleanup + VM scoping settled (June 2026).** The Dialog-era workarounds died
   with each conversion (Dialog wrappers, window `SideEffect`s, in-window context re-providing
   on converted screens); the last stragglers — `OutfitComposerScreen`'s caller-side
   `CompositionLocalProvider(parentContext…)` wraps around its child sheets, no-ops once the
   composer became a destination — were deleted. What remains window-based is so by design
   (pickers, sheets, `MatchPreviewDialog`, `ComposerSuggestionsViewer`, `TagEditScreen`,
   AlertDialogs) and still needs the CLAUDE.md window rules; `rememberDialogBottomInset`'s
   48.dp floor also survives in converted screens as the design's minimum bottom clearance.
   **Per-destination VM scoping was evaluated and deliberately deferred**: every ViewModel is
   shared across multiple surfaces (tabs + overlay destinations + global hosts + the cross-VM
   `LaunchedEffect` bridges in AppContent), so no destination-local candidate exists until the
   use-case extraction of § 5 breaks those dependencies. Destinations therefore pin the
   activity as ViewModelStoreOwner, explicitly and uniformly.
   **Phase 3 COMPLETE.**
4. **Modularize last** — once dependencies are sane, moving packages into Gradle modules is
   mechanical (`scripts/kt_split.py`-style, compiler-driven). *(A fifth phase — § 5's thin-VM
   conversion — was added in June 2026; its execution plan is the dedicated section below.)*
   **Status: core extraction LANDED (June 2026).** First four library modules: `:core:model`,
   `:core:common`, `:core:database`, `:core:ml` (namespaces `com.librelookai.core.*`). The key
   mechanic that made this churn-free: **moved code keeps its original Kotlin package** — only
   `R`/`BuildConfig` references are namespace-bound, and every moved file has none, so the
   ~hundreds of existing import sites stayed untouched and the diff is almost pure `git mv`.
   - `:core:model` — `data/model/*` plus three knot-cutting moves of pure data out of feature
     files (original packages kept): `ClothingTags`/`FashionTrends` out of
     `gemini/GeminiModels.kt` (the `internal` response DTOs stayed), `AiConsiderations` out of
     `settings/UserPreferences.kt`, and `DriveImage` out of `wardrobe/WardrobeModels.kt`.
     Exposes Gson as `api` (consumers' Gson reads the moved classes' annotations at runtime).
   - `:core:database` — all of `data/local` (Room stores + Hilt modules) and its five
     Robolectric store-test suites; the app module's Java-21 test-toolchain pin is replicated
     in the module. Room + its KSP compiler left `:app`'s dependencies entirely.
   - `:core:common` — `util/`'s R-free, dependency-free helpers: `ImageEncoding` (incl.
     `ImageQuality`), `NetworkUtils` (`NetworkMonitor`, `LocalIsOffline`,
     `LocalSystemBarsPadding`, `rememberDialogBottomInset`), `Scrollbar`, `StartupGate`, plus
     `ImageEncodingTest`. The R-/feature-coupled utils (`AiProcessingOverlay`,
     `RestoreProgressOverlay`, `LocaleContext`, `FeatureFlags`, `Analytics`) stay in `:app`.
   - `:core:ml` — the whole `ml/` package + `PHashTest`; its cross-package imports were
     KDoc-only except `DriveImage` (now in `:core:model`), so the move was pure. The mediapipe
     dependency moved with it. The `.tflite` assets deliberately **stay in `app/`** so the
     `androidResources.noCompress("tflite")` mmap guarantee keeps applying at the one place
     that packages the APK.
   - Only behavioral-code change: a smart-cast in `ShoppingClosetViewModel.ensureOriginalCached`
     became a local `val` (smart casts don't cross module boundaries on public properties).
   **`:core:sync` extraction LANDED (June 2026)**, enabled by the seam inversion (phase 1
   status): all of `data/drive` (`DriveRepository` 4-file split + `DriveMigration`) plus the
   non-UI half of `auth/` (`GoogleAuthManager`, `AuthEvents` — `SignInScreen`/`AuthViewModel`
   stay in `:app`; same-package split, like `settings/`). The two `BuildConfig` switches the
   moved code reads (`DRIVE_FULL_SCOPE`, `FIREBASE_WEB_CLIENT_ID`) became module-own
   `buildConfigField`s read from the same `local.properties`, so values can't drift; the
   moved files import `com.librelookai.core.sync.BuildConfig`. The drive extension functions'
   `internal` visibility (which in a single-module app meant "public") was bumped to public
   where the compiler flagged cross-module callers. Exposes `:core:model` as `api`
   (`DriveRepository` signatures carry `Location`/`Outfit`).
   **`:core:ai` extraction LANDED (June 2026)**, enabled by cutting the three documented
   knots first:
   - *`R.string` notice ids → semantic reasons (a § 6 slice).* `AiNotice` /
     `AiUnavailableException` now carry an `AiErrorReason` enum (`NOT_CONFIGURED` /
     `UNAVAILABLE` / `QUOTA` / `KEY_INVALID` / `PERMISSION` / `BLOCKED` / `SERVER` /
     `GENERIC`); `GeminiRepository.emitFailure` maps HTTP codes to reasons, and the single
     consumer — the global AI-notice dialog in `AppContent` — maps reasons to localized
     strings app-side (`aiErrorMessageRes`). The gemini package is `R`-free.
   - *`BuildConfig` key/proxy fields → module-own `buildConfigField`s* (`GEMINI_API_KEY`,
     `PROXY_BASE_URL`, `MANAGED_BILLING_ENABLED`), read from the same `local.properties`
     keys as `:app`'s — the `:core:sync` pattern, values can't drift. `:app` dropped its
     now-unread `GEMINI_API_KEY` / `MANAGED_BILLING_ENABLED` fields but keeps
     `PROXY_BASE_URL` (`CreditRepository` / `CostBadge` still read it).
   - *The `billing` seam classes moved below the AI layer* (original
     `com.librelookai.billing` package kept): `ManagedBilling` + `CreditsEvents` into
     `:core:ai` (the repository is `CreditsEvents`' only emitter, and `ManagedBilling` now
     reads the module's own BuildConfig), `InsufficientCreditsException` into `:core:model`
     (pure data, the `CreditPacks` precedent).
   Then the whole `gemini/` package moved (original Kotlin package kept, imports untouched).
   Module deps: `api(:core:model)` + `api(:core:sync)` (public signatures expose
   `ClothingTags`/`DriveRepository`), `:core:common`, OkHttp, Firebase auth/firestore, Hilt.
   The compiler-flagged `internal` extension symbols crossing the new boundary were bumped
   to public (`generateText`, `classifyClothing`, `tryOnOutfit`, `buildTryOnPrompt`,
   `CutoutIssues`, `detectCutoutIssues`, `fixCutoutBackground`); `searchFashionTrends`
   deliberately stays `internal` (callers must go through `FashionTrendsCache`).
   `UsageDeviationTest` moved with the module; `TagNormalizerTest` stays in `:app` *by
   design* — it asserts the canonical-color set stays in sync with the app-side
   `wardrobe/FilterColorKeys` swatch list, a deliberate cross-layer invariant. Two dead
   upward imports were deleted en route (`TokenUsage` → `MainActivity`, `GeminiImaging` →
   `R`).
   **What stays in `:app`, and why (the honest remainder):**
   - `billing` (minus the three moved seam classes) is feature-shaped — Play-billing UI,
     `CreditRepository`, `CostBadge` — and waits for `feature/*`.
   - `core/designsystem` is blocked on resources: `ui/theme` / `ui/components` reference
     `R.string`/`R.font` from the app's single `res/` (31 locales); splitting strings per
     module is its own decision, not a mechanical move.
   - `feature/*` modules are blocked on § 5: every ViewModel is still activity-scoped and
     shared across features (travel reads `OutfitsViewModel`, try-on reads wardrobe+shopping,
     ~25 cross-VM bridges in `AppContent`), so feature boundaries would be cyclic today.
   Verification: `./gradlew assembleDebug testDebugUnitTest` green — 99 tests
   (52 app / 8 ai / 25 database / 7 common / 7 ml), 0 failures.

## Phase 5 execution plan: thin ViewModels over use-cases (§ 5)

### Ground truth (June 2026 — what this phase is actually up against)

15 ViewModels, all activity-scoped, ~7.3k lines total. `WardrobeViewModel` is 1326 lines plus
five same-package `internal fun ViewModel.x()` extension files (Audit 460 / Import 388 /
Upload 338 / BgFix 255 / Search 221 / Convert 187) ≈ 3.2k lines orbiting one `_state`
`MutableStateFlow` and ~15 `internal var`s (`folderId`, `geminiLanguage`, `dedupeOnImport`,
`pendingAudit`, the `workQueue` Channel, a wake-lock, …). `WardrobeUiState` carries ~9
independent pipeline-progress clusters (`isRetagging`/`retagDone`/`retagTotal`,
`isImporting`/…, `isRemovingAllBg`/…, `isConverting`/…, sync phase, audit, cutout-fix, batch,
pendingJobs) beside the actual grid state.

The wiring lives in `AppContent` as ~25 `LaunchedEffect` bridges, falling into families:

- **Closet-topology fan-out** (the big one): one effect keyed on
  `activeLocationId/locationList/defaultClosetFolderId/shoppingFolderId` pushing 7 setter calls
  into 3 VMs (`setAllConfiguredLocations`, `setAllLocations`, `setLocation`,
  `updateSaveFolder`, `setDefaultImportFolderId`).
- **Preference mirrors** (6): language → wardrobe + shopping; dedupe pair, `preferLocalBg`,
  `debugSimilarityPreview` → wardrobe; `bgRemovalThreshold` → the
  `EmbeddingService.segmenter` static; `imageQuality` → the `ImageEncoding.tier` static.
- **Data mirrors**: `outfitEventsState.events` → `stylesViewModel.setWearHistory` (the
  taste-signal copy).
- **Pre-warm loads**: `loadItems()` / `loadHistory()` / `loadTrips()` at start.
- **Connectivity**: `retryPrefetchIfNeeded` + the sync-engine catch-up drain.
- **Composer open-flag ↔ navigation mirrors** (by design from phase 3; they die last).
- **Restore-overlay aggregation** polling four VMs' `isLoading`.

On top of the bridges, screens and call sites read *other* VMs' state imperatively:
`openComposer(images = wardrobeVM.state.value.images + shoppingState.items, prefs =
profileVM.state.value.preferences)`, `travelTryOnAvailable` derived from trips + outfits +
wardrobe images, `TripViewerScreen` / `TravelPlannerScreen` / `TryOnComposerScreen` each taking
5–7 VM parameters.

The root cause is single: **wardrobe items, outfits, events and trips live in VM memory; the
Room stores are poll-read (suspend), never observed.** That is exactly the § 2 carry-over
("real-entity / Flow-read conversion"), which is why it executes here.

### Design decisions (recorded up front)

1. **Flow reads do NOT require the real-entity conversion.** Room invalidation is per-table; a
   DAO can return `Flow<List<row>>` over the existing opaque-JSON rows, with the Gson mapping
   staying in the store impl. Queryable columns get added later, per column, only when an
   actual query needs them — not as a precondition. This unblocks all of § 5 without an entity
   migration or DB version bump.
2. **Use-cases are plain `@Singleton` classes** with suspend entry points and a `StateFlow`
   progress/state property, injected into VMs. No `UseCase<P, R>` base class, no framework.
   They write to the stores (+ enqueue sync mutations), never back into a VM's state — the UI
   updates via Room invalidation.
3. **App-scope session state gets one home**: a new `data/session` package in `:app`
   (`ClosetSession`, `UserPreferencesRepository`). Module placement is decided at
   feature-extraction time, not now (the phase-4 "modularize last" rule).
4. **Pipelines move first, persist second** — the § 2 slice pattern. Each pipeline is first
   extracted *as-is* into a singleton (keeping `JobForegroundService` + wake-lock semantics,
   zero behavior change), and only then optionally gets WorkManager process-death persistence.
5. **Each bridge dies when its consumer stops needing it**, inside the slice that converts the
   consumer — never as a separate "cleanup" pass that could drift from the conversions.
6. **Small cohesive VMs are out of scope**: `AuthViewModel`, `WeatherViewModel`,
   `CreditsViewModel`, `ShoppingHelperViewModel`, `WardrobeGapViewModel`,
   `DriveMigrationViewModel` stay as they are (converted only opportunistically if a slice
   touches them anyway).
7. **Don't break tested signatures**: the Robolectric `*Content` composable contracts and the
   store invariant suites are the regression net; splits stay same-package (the CLAUDE.md
   file-splitting rules).

### Slices (each independently shippable, in order)

**Slice 0 — observable stores (dark).** Add `observe*` Flow APIs to the five stores
(`WardrobeItemStore.observeItems(folderIds)`, `OutfitStore.observeFolders(folderIds)`,
`OutfitEventStore`, `TripStore`, `TryOnStore`): DAO returns `Flow` over the existing rows, the
store maps rows → models exactly like the suspend reads. Nothing collects yet — zero behavior
change. Tests: extend each store suite with first-emission + write-invalidation assertions
(Robolectric/Room, like the existing tests).
**Status: LANDED (June 2026).** Wardrobe items observe as `Flow<Map<folderId,
List<CachedWardrobeItem>>>` (consumers need folder attribution); outfits/events observe as
merged lists across the requested folders (outfits with `folderId` restored); trips/try-ons
observe the global list. Each store flow runs the one-time legacy seed for the requested
folders before its first emission, short-circuits an empty folder list (no SQL `IN ()`), and
is `distinctUntilChanged`. +7 store tests (first emission, push on write while collecting,
seed-through-observe).

**Slice 1 — `ClosetSession`.** A `@Singleton` exposing `StateFlow<ClosetSession>` (locations,
`activeLocationId`, `activeFolderId`, all closet folder ids, `defaultImportFolderId`,
shopping folder id, the derived similarity-snapshot id set). `LocationViewModel` and
`ShoppingClosetViewModel` *publish* into it (they stay the owners of how the values are
computed); `WardrobeViewModel` / `OutfitsViewModel` / `OutfitEventsViewModel` collect it in
`init` instead of being pushed. Deletes the closet-topology fan-out bridge and all 7 setter
methods. Risk: VMs are created lazily per tab, so collectors must tolerate a not-yet-populated
session (emit-on-subscribe with the empty default, exactly like today's pre-fan-out window).
Acceptance: closet switch, All-locations mode, default-closet import targeting, and the
shopping folder's inclusion in the similarity snapshot behave identically.
**Status: LANDED (June 2026).** `data/session/ClosetSession.kt` (`:app`): the data class owns
the derived rules the bridge computed inline (`activeFolderId` — null for All/unknown ids,
`snapshotFolderIds` = closets + shopping, `defaultImportFolderId` / `saveFolderId` fallbacks),
unit-tested in `ClosetSessionTest` (5). `LocationViewModel` mirrors its whole state into the
holder from `init` (covers add/rename/delete/default changes); `ShoppingClosetViewModel`
publishes `state.folderId` distinct-until-changed. Consumers collect in `init` with the same
call order and no-op guards the bridge relied on; the bridge's silent arm (active id neither
"All" nor a known closet → keep current scope) is preserved explicitly. The 7 setters:
`setLocation`/`setAllLocations`/`setAllConfiguredLocations` (wardrobe), `setAllLocations`
(outfits, events), `setLocation` (events) went `private`; `OutfitsViewModel.setLocation` was
dead (never called) and `updateSaveFolder` folded into the collector — both deleted.
`WardrobeViewModel.setDefaultImportFolderId` stays public (the capture-screen import-target
picker calls it). `ALL_LOCATIONS_ID` now lives on `ClosetSession`; `LocationViewModel`'s
constant aliases it so call sites stay valid.

**Slice 2 — `UserPreferencesRepository` (read path).** A `@Singleton`
`StateFlow<UserPreferences>`; `ProfileViewModel` writes through it (it remains the only
writer — full DataStore conversion stays § 7). Consumers collect instead of being mirrored:
wardrobe (language, dedupe pair, `preferLocalBg`, `debugSimilarityPreview`), shopping
(language). The two static mirrors (`ImageEncoding.tier`,
`EmbeddingService.segmenter.foregroundThreshold`) move out of composition into one collector
started at app startup — the statics themselves survive until § 3, but the *bridges* die.
Kills the six preference-mirror effects; `openComposer`'s `prefs` parameter goes once its VM
injects the repository.
**Status: LANDED (June 2026).** `data/session/UserPreferencesRepository` (`:app`):
`ProfileViewModel` mirrors `state.preferences` (initial cached language/theme/font, the Drive
load, every save and try-on-photo update) distinct-until-changed into the singleton; it stays
the only writer. Wardrobe collects language (the `AppLanguage.toGeminiName` conversion moved
into the VM) + dedupe pair + `preferLocalBgRemoval` + `debugSimilarityPreview`, shopping
collects language; the five push-setters (`setLanguage`×2, `setDedupeSettings`,
`setPreferLocalBgRemoval`, `setDebugSimilarityPreview`) are deleted.
`data/session/StaticPreferenceMirrors` (started once in `LibreLookAIApp.onCreate`, which
gained its first `onCreate` body) owns the two legacy-static writes (`ImageEncoding.tier`,
`EmbeddingService.segmenter.foregroundThreshold` — keeping the old `runCatching` guard).
Because `ProfileViewModel` is created above the entry gate, the repository is populated
before the consuming VMs even exist. The `openComposer` `prefs` parameter was left for the
slice-8/9 composer work (it is part of the open-call contract, not a mirror).

**Slice 3 — prompt inputs become DB reads.** `OutfitsViewModel` derives `wearHistory` by
collecting `OutfitEventStore.observe*` (scoped via `ClosetSession`) instead of
`setWearHistory`; `TravelViewModel` reads outfits/events through the stores instead of having
`OutfitsUiState` threaded into the planner. Correctness note: the events VM persists
locally-first (§ 2 slice 6), so Room is already current at prompt-build time — the mirror was
pure plumbing. Kills the events→styles data mirror and the travel↔outfits VM coupling.
**Status: LANDED (June 2026)** for the events half. One shared `wearHistoryFlow(session,
eventStore)` in `outfit/WearHistory.kt` (the file that owns wear-history logic) maps the
closet session to the events scope with the same arms `OutfitEventsViewModel` uses — incl.
the silent unknown-active arm, encoded in `WearHistoryFlowTest` (3, over a fake store) —
and `flatMapLatest`s into the store. `OutfitsViewModel` collects it into
`OutfitsUiState.wearHistory` (`setWearHistory` deleted, AppContent mirror gone);
`TravelViewModel` exposes its own `wearHistory: StateFlow` (stateIn Eagerly) and the
`wearHistory` parameters dropped out of `generate`/`estimatePackingTokens`/`doGenerate` —
the planner UI now collects the travel VM's flow only as a recompute key for the cost
estimate. Two accepted improvements: wear history now paints from the store without waiting
for the events VM's Drive phase, and an event duplicated across folder caches by the legacy
single-target persist rule counts once (single home, the outfit-store precedent). **The
outfits half of the travel decoupling (`sourceStyles` from `OutfitsUiState.outfits`) is
deliberately deferred to slice 4b**: store-read outfits lack the load-time
`itemNames`→`itemIds` resolution against the live wardrobe, which is exactly the read-path
conversion slice 4 owns.

**Slice 4 — wardrobe read path on Room Flows (the § 2 carry-over, biggest slice).**
`WardrobeUiState.images` becomes *derived*: the store Flow for the active scope (from
`ClosetSession`) combined with filters/sort; `allLocationImages` likewise derives from the
snapshot-id-set Flow. The two-phase load becomes "paint from the Room Flow; the Drive
reconcile mutates Room; UI follows via invalidation" — the manual `persistItemToCache` +
state-splice pairs collapse into plain store writes (most of the optimistic-splice code
*deletes*, since Room is already written first everywhere after § 2). Sub-slices, each
shippable: **4a** wardrobe items, **4b** outfits, **4c** shopping, **4d** events/trips/try-ons.
Consequences: the pre-warm bridge dies (first composition collects from Room instantly; the
Drive reconcile triggers on first subscription); the connectivity bridges
(`retryPrefetchIfNeeded`, catch-up drain) move into the engine/prefetch logic itself via an
injected `NetworkMonitor`; cross-VM image reads (`openComposer` images, `travelTryOnAvailable`,
gap-analysis input, the trip viewer's item resolution) become store reads; the CLAUDE.md
"cache-folder rule" prose reduces to "write the store; UI follows". Risk: the recently-moved
markers and rollback collectors must reconcile with Flow emissions — verify against
`WardrobeItemStoreTest` invariants plus the manual move/offline checklist.
**Status: 4a LANDED (June 2026).** Two store-observing collectors in `WardrobeViewModel.init`
are now the only writers of `WardrobeUiState.images` / `allLocationImages`
(`viewScope`/`configuredScope` StateFlows → `observeItems` → `combine` with two overlays →
`flowOn(IO)`); every state splice across the VM + its five extension files became a store
write, and `refreshAllLocationImagesState`/`snapshotJob`, the `sidecarSaved` collector and
the `moveRolledBack` splice/restore logic were deleted (the handlers' Room writes reach the
view via invalidation). Two overlays ride the derivation: `transientItems` (import
placeholders with synthetic ids — no Drive file, so no store row; filtered to the view
scope) and `imageVersions` (the Coil cache-buster `DriveImage.version` used to live in
spliced state — bumped on rotate/reprocess/cutout-fix/convert). Key conversions:
`saveSidecar` no longer rebuilds folder rows from `state.images` (callers write the row
first — `persistItemTags` — then `enqueueSidecarSync`); `processQueuedImage` builds the
finished item locally and upserts it (keeping the raw row's `createdTimeMs` for sort
stability); upload paths upsert raw placeholder rows instead of full-folder rebuilds —
which also fixes a latent cache-folder-rule violation (uploading from All-locations used
to `replaceFolder(target, whole view)`, re-homing every visible item into the import
folder); import's `replaceExisting` drops rows per owning folder; the All-locations
reconcile now persists raw in-flight rows too (the old cutout-only filter would have
erased them from the derived view). Phase-1 cache reads survive only as cold-load probes
for the `isLoading`/restore flags; Phase-2 store writes land *before* the flags clear so a
cold load never flashes empty. Accepted divergences: tag edits and rollback restores reach
the grid via invalidation (a few ms later than the old synchronous splices); transient
Drive-listing races during the reconcile behave as before via `recentlyMovedItems`, which
now guards only the store writes. Manual regression checklist before release: capture +
batch import (placeholder → cutout swap), closet switch mid-processing, move + offline
rollback, rotate/reprocess Coil refresh, reinstall-restore progress.
**Status: 4b LANDED (June 2026).** `OutfitsUiState.outfits` and `.wardrobeImages` derive from
`outfitsScope` (the all-closets list) flatMapped into `OutfitStore.observeFolders` /
`WardrobeItemStore.observeItems` — viable without an entity migration because store rows
carry the resolved `itemIds` (only `Outfit.folderId` is `@Transient`; the slice-3 concern
applied to *legacy-seeded* rows, which the next Phase-2 reconcile re-resolves anyway).
`persistOutfitFolders(updated, affected)` now takes the mutation's already-updated list
(it no longer reads `state.outfits` back) and is mutex-serialized; all 11 mutation sites
dropped their `copy(outfits = …)` splices and `loadOutfits` keeps flags only —
`loadOutfitsFromFolder`'s per-folder `replaceFolder` was already the reconcile's store
write. `refreshWardrobeImages()` and its after-sync `LaunchedEffect` in `OutfitsScreen`
deleted (invalidation covers it). Travel's `sourceStyles` read is thereby store-backed
data (closing slice 3's deferral); the remaining `stylesViewModel` parameter threading is
write-path coupling (`addOutfits`, `updateOutfitsRefined`) that stays until the use-case
extraction (slices 5–8). Accepted divergence: mutations reach the list via invalidation
(ms), and concurrent mutators read `state.outfits` as their base exactly as before — the
pre-existing read-then-persist race window grows by that lag but gains the persist mutex.
**Status: 4c LANDED (June 2026).** `ShoppingClosetUiState.items` derives from a `folderScope`
StateFlow (fed by the same `state.folderId` collector that publishes into `ClosetSession`)
flatMapped into `WardrobeItemStore.observeItems`, combined with an `imageVersions` Coil
overlay (rotate/reprocess) and sorted `createdTimeMs`-desc to match Drive's listing order;
the only writer of `items` is the derivation collector. All splices became store writes:
`uploadRaw` upserts the raw placeholder row, `processQueuedItem` upserts the finished cutout
under the raw row's `createdTimeMs` (stale raw row dropped via `upsert`'s `staleDriveId`) and
stamps the sidecar id with `setSidecarId`; `tagImage`/`updateTags` go through a shopping-local
`persistItemTags`, and `saveSidecar` now reads the *store row* (not state) before its direct
Drive write — fixing a latent stale-read race — though it still doesn't ride the § 2 queue
(possible follow-up: reuse `wardrobe.sidecarSync`, whose handler is already folder-agnostic).
`moveToCloset` re-homes the rows into the target folder itself (`addAll`; `onMoved` keeps
feeding the wardrobe VM's recently-moved markers, its own `addAll` an idempotent repeat),
`deleteItems` drops rows via `remove`, and the `moveRolledBack` collector shrank to the error
banner (the handler's re-home restores the wishlist via invalidation). Phase 1 of `loadItems`
is a cold-load probe for `isLoading` only; Phase 2's `replaceFolder` lands before the flags
clear.
**Status: 4d LANDED (June 2026) — slice 4 complete.** The three remaining list views derive
from their stores: `OutfitEventsUiState.events` from a session-driven scope flatMapped into
`OutfitEventStore.observeFolders` (`persist()` keeps the legacy single-target rule — store
write + queue, no state splice; the event id being the primary key makes the All-locations
re-home atomic); `TripsUiState.trips` from `TripStore.observeTrips()` sorted
`createdAt`-desc (`upsertTrip`/`mutateTrip`/`deleteTrip(s)` compute the updated list and
`replaceAll` it — accepted divergence: an edited trip no longer jumps to the top of the
list, which only ever held until the next reload anyway); `TryOnUiState.history` from
`TryOnStore.observeTryOns()` mapped through `migrateSource` + a cached-image-file filter,
sorted `createdAt`-desc — `saveCurrent` and the singular `deleteTryOn` now write the store
(previously only `deleteTryOns`/Phase 2 did, leaving it stale until the next `loadHistory`),
and `loadHistory`'s Phase 1 deleted outright (the derivation paints on subscription; Phase 2
downloads missing image files then `replaceAll`s, whose invalidation re-runs the file
filter). Accepted divergences: mutators still read `state` as their base (same ms-lag window
as 4a–c), and a try-on whose image download failed is skipped instead of shown with an empty
`localPath`. Try-on saves remain Drive-first (not queued) — converting them is § 2 leftover
work, not slice 4's.

**Slice 5 — ingestion pipeline use-case.** The `PendingJob` Channel, `drainWorkQueue`,
dedupe gate, local-bg review queue, wake-lock and `JobForegroundService` acquire/release move
from `WardrobeViewModel`(+`…Upload.kt`) into a `@Singleton` `wardrobe/ItemIngestionPipeline`
exposing `StateFlow<IngestionProgress>` (`pendingJobs`, `batchDone/Total`,
`processingImageId`, duplicate-check / bg-review pauses). The VM delegates enqueues and
re-exposes the progress Flow; completed items land in the store (slice 4), so the grid updates
without VM involvement. Zero behavior change (decision 4). **5b (optional, separate release):**
persist job specs in a Room table (the captured file is already on disk) and drain via
WorkManager expedited work — retiring the wake-lock + foreground service for ingestion, the
same pattern as § 2's `SyncDrainWorker` backstop.
**Status: 5 LANDED (June 2026).** `wardrobe/ItemIngestionPipeline` (@Singleton, own
process-long scope — the foreground service + wake lock already promised that lifetime, now
the coroutines deliver it) owns the dedupe gate, local-bg review queue, raw upload, gallery
batch and the serial worker queue, exposing `StateFlow<IngestionProgress>` plus a nullable
`errors` SharedFlow (null = the old `error = null` clears). The dedupe snapshot became a
store read over `session.snapshotFolderIds` (no VM state). The wake-lock/foreground-service
refcount moved to a shared `service/JobLock` @Singleton (with the battery-exemption
StateFlow); the VM's `acquireJobWakeLock`/`releaseJobWakeLock` are thin delegates so the
audit/bg-fix/convert/import extensions are untouched until slice 6. The VM mirrors progress
into `WardrobeUiState` in `init`: pipeline-owned fields copy through; the shared
`isUploading`/`processingImageId` apply *transitions only* (replicating the old guarded
swap/clear writes — per-item ops keep last-writer-wins), and a `gridReturnTick` reproduces
the exact old `view = GRID` flip sites (dedupe pass / upload start — NOT gallery batches,
which may run under the capture screen). `…Upload.kt` shrank to folder-resolving delegates +
the URL-import picker; `uploadAsCutout`/`uploadAsOriginal` became `DriveRepository`
extensions (in the pipeline file) shared with audit/import; `QUERY_DEBUG_DIR` is a
top-level const. UI API unchanged (screens still call the VM extensions).

**Slice 6 — bulk maintenance use-cases.** One per extension file, same recipe as slice 5:
`RetagAllUseCase`, `RemoveAllBackgroundsUseCase`, `CutoutBgFixUseCase` (the
scan → review → apply state machine behind `FixCutoutBgDialog`), `WebpConvertUseCase`,
`RepairAuditUseCase` (owns `pendingAudit`), `FolderImportUseCase`. Each owns its progress/
confirmation `StateFlow`; the globally-hosted dialogs/overlays read it through a thin VM
passthrough (or directly via `hiltViewModel` once slice 9 lands). Similarity *search* stays
interactive VM logic (`…Search.kt` shrinks but isn't a pipeline). This deletes the
`internal var` soup and most of the extension-file pattern; `WardrobeViewModel` lands at grid
state + selection + thin delegations.
**Status: LANDED (June 2026).** Four live use-cases extracted (`RetagAllUseCase` +
`RemoveAllBackgroundsUseCase` in `WardrobeBulkAiUseCases.kt`, `WebpConvertUseCase`,
`CutoutBgFixUseCase`), each a `@Singleton` with its own progress `StateFlow` mirrored into
`WardrobeUiState` in the VM's `init`; the VM/extension entry points are thin delegates so screen
wiring is unchanged. The single-item `fixCutoutBgForItem` stays on the VM (writes the shared
`processingImageId`/`error`). Shared singletons moved to `ItemVersions.kt`: `ItemVersions` (the
Coil cache-buster, ex-`imageVersions`) + `SidecarSyncQueue`. **`RepairAuditUseCase` and
`FolderImportUseCase` were NOT extracted — the Repair & Sync audit and the SAF/Drive folder
import they wrapped turned out to be dead code with no UI host anywhere (`startRepairAndRefresh`/
`importFromFolder` were never wired into any screen). Per the user's call they were deleted
instead**: `WardrobeViewModelAudit.kt`/`…Import.kt`, the `AuditProgress`/`ImportPreview` model
clusters + UiState fields, `pendingAudit`, the now-writerless transient-placeholder overlay
(`TransientItems`), the `REPAIR_DUPE_THRESHOLD`/`SIDECAR_*` consts, and the
dedupe/`preferLocalBgRemoval` VM pref-mirrors (the pipeline owns its own copies). `…Convert.kt`
shrank to a one-line delegate; `…Bgfix.kt` to single-item fix + delegates. The VM is ~1126 lines
(load/cache/sidecar core + search + setters/CRUD remain for slice 8's per-screen split).
`./gradlew :app:assembleDebug testDebugUnitTest` green.

**Slice 7 — UiState slimming + sealed events.** Prune the migrated pipeline-progress clusters
out of `WardrobeUiState`; introduce per-screen `sealed interface XEvent` one-shots for the
imperative request fields (`pendingScrollDriveId`, `requestScrollToOutfit`,
`pendingCalendarWearId` stays — it's navigation state by design). The restore overlay
aggregates engine/use-case sync state instead of polling four VMs' `isLoading` — delivering
§ 2's "restore = initial sync pass with progress from the engine" and deleting the
latch/debounce effect.
**Status: sealed-events part LANDED (June 2026); the other two parts deferred.**
- *Sealed one-shot events (done):* `pendingScrollDriveId` → `WardrobeEvent.ScrollToItem`
  (wardrobe) and `pendingScrollOutfitId` → `OutfitsEvent.ScrollToOutfit` (outfits) are now
  buffered `Channel`/`receiveAsFlow()` one-shots the grid/list collect into a local var (which
  the existing retry-on-`displayedImages`/`displayedStyles` effect drains once the target is in
  view). The `Channel` buffer is what makes them safe for the **cross-tab** callers
  (`requestScrollTo…` then `goToTab`, where the target screen's collector subscribes *after* the
  emit): the event waits in the buffer, is consumed exactly once, and never re-fires on tab
  revisit — so the old state-field + `consumePendingScroll*` + the `setLocation`/`setAllLocations`
  preservation hack are all gone. (`pendingCalendarWearId` stays state, per plan.)
- *UiState progress prune — LANDED (July 2026, executed as the slice-9 fold-in).* The pruned
  clusters left `WardrobeUiState` entirely: retag / remove-bg / convert progress, `pendingJobs`,
  `batchDone`/`batchTotal`, `duplicateCheck`, `localBgReviewQueue`, `cutoutBgFix`, plus the dead
  `isImporting`/`importDone`/`importTotal` (leftovers of the slice-6 folder-import deletion) and
  the never-read `isRemovingAllBg` cluster. Instead of relocating consumers to `hiltViewModel()`
  reads, the VM exposes **passthrough properties** (`ingestionProgress`, `retagProgress`,
  `convertProgress`, `cutoutBgFixProgress` — the owning singletons' StateFlows, no copy, no
  collectors), which is the "thin VM passthrough" option the slice text named. Consumers:
  `WardrobeScreen` collects all three and hands snapshots into `GridContent` (new `ingestion`/
  `retag`/`convert` params feeding the overlay-label `when`), the `DuplicateCheckSheet` gate reads
  `ingestion.duplicateCheck`, `LocalBgRemovalScreen` reads `ingestion.localBgReviewQueue`, the
  Settings feedback dialog reads `ingestion.pendingJobs`, and `AppContent`'s `FixCutoutBgDialog`
  host reads `cutoutBgFixProgress`. What stays on `WardrobeUiState`, per the straddle analysis:
  `isProcessing`/`isUploading`/`processingImageId` (written by both the pipeline transitions and
  the VM's per-item ops) and the `sync*` restore-overlay fields (part-3 deferral below).
- *Restore-overlay engine aggregation — DEFERRED.* `SyncEngine` exposes only `drain()` (no
  sync-state flow); the current latch/debounce/`isLoading`-gating restore path is deliberately
  tuned. Revisit alongside the slice-9 work / a future `SyncEngine` sync-state surface.

**Slice 8 — VM splits per screen.** Now mechanical: `OutfitsViewModel` →
`OutfitsListViewModel` + `OutfitComposerViewModel` + `PredictionViewModel`
(`OutfitsUiState` is already partitioned along these lines); `TryOnViewModel` → composer vs
history. Same-package splits; the shared pieces (save funnel `persistOutfitFolders`, prompt
builders) are already standalone. Each split verified by the existing Robolectric flow tests.
**Status: OutfitsRepository foundation LANDED (June 2026); the actual VM splits deferred.**
On inspection the "mechanical" framing didn't hold: the `OutfitsUiState` *fields* are partitioned
but the *methods* aren't — every mutator funnels through the shared `_state.value.outfits` list +
the `persistOutfitFolders` save funnel + the store-derived `outfits`/`wardrobeImages` collectors,
and the VM is referenced pervasively outside itself (`AppContent` composer/prediction open-flags,
Travel's `saveOutfitDirectly`/`addOutfits`/`updateOutfitsRefined`, TryOn). A clean three-way split
needs somewhere shared to hold that data + funnel, and the composer/prediction navigation it
touches is exactly slice 9's rework. So per the user's call we did the **foundation** first:
`outfit/OutfitsRepository` (`@Singleton`) now owns the cross-closet scope, the store-derived
`outfits`/`wardrobeImages` `StateFlow`s (§ 5 slice 4b derivation, `stateIn` on a process-long
scope), the Drive load helpers (`cachedOutfits`/`syncOutfitsFromDrive`/`idToNameAllFolders`) and
the local-first `persistOutfitFolders` funnel (+ its mutex). `OutfitsViewModel` is now a thin
consumer: it drives the scope via `repo.setScope` from the closet session, mirrors
`repo.outfits`/`repo.wardrobeImages` into `OutfitsUiState` (so screen consumers are unchanged),
keeps all UI state (isLoading/error/composer/prediction/selection) + the mutators (which delegate
the funnel to the repo), and exposes `folderId`/`saveFolderId`/`persistOutfitFolders` as thin
delegators so the VM extensions (composer/prediction/tags) needed **zero changes**. This makes the
eventual 3-way VM split and slice 9's destination-scoped VMs mechanical (each VM reads the repo's
flows + calls its funnel). A pure-logic unit test (`OutfitsRepositoryTest`) covers the
correctness-critical bits factored out for it — `resolveOutfits` (extension-agnostic
`itemNames`→`itemIds` resolution that survives the WebP rename + cross-closet moves) and
`foldersToWrite`/`outfitsHomedIn` (the single-home write selection); the repo itself can't yet be
constructed in a plain test (concrete `DriveRepository` dep — awaits § 3 interfaces).
**The 2-way VM split LANDED (July 2026, first slice-9 increment — see slice 9 status):**
- *`OutfitsViewModel` → list + generation (2-way, NOT the planned 3-way):* confirmed that composer
  and prediction are **one shared AI-generation state machine** — the composer's `generate` flow and
  the prediction setup both read *and* write the same `composer*` generation-parameter cluster
  through the same setters (`setComposerWeatherMode`/`setComposerForecastDate`/
  `setComposerConsideration`/vibes/manual-weather/source-folders/suggestion-count), so they can't be
  two faithful VMs. Plan revised to a 2-way split (user's call): `OutfitsViewModel` stays the **list**
  VM (list/selection/delete/loved/calendar-wear/wear-history + the save mutators Travel/TryOn call —
  keeping the widely-used API so those ~19 consumers don't churn), and a new
  `OutfitGenerationViewModel` owns composer + prediction (their shared state + the `OutfitsComposer`/
  `OutfitsPrediction`/`OutfitsTags` extension files). Execution sequence: (1) shared *new-outfit save*
  moves to `OutfitsRepository.saveOutfit(...)` so both VMs persist without a cross-VM call; (2) the
  scroll-to-outfit one-shot moves to the repo (generation's `commitOutfit` emits, the list collects)
  to handle the save→scroll hand-off; (3) split `OutfitsUiState` → list fields stay,
  composer/prediction fields move to `OutfitGenerationUiState`; (4) rewire the generation consumers
  (composer screens, prediction screens, `AppContent` open-flag/nav, the two viewer destinations).
  The composer/prediction open-flags it mirrors in `AppContent` collapse into the destination-scoping
  work below.
  **Status: steps (1)–(4) LANDED (July 2026).** `OutfitsRepository.saveOutfit(name, description,
  itemIds, tags)` is the one create path (resolves cross-closet `itemNames`, stamps the home
  folder, fires the scroll one-shot, funnels through `persistOutfitFolders`; returns null when no
  save target). The scroll-to-outfit `Channel` moved onto the repo (`repo.events` /
  `requestScrollToOutfit` — the list VM re-exposes both, so Try-On's "View outfit" caller is
  unchanged), and the post-edit-save "wear it now" hand-off became `repo.pendingWearOutfitId`
  (a StateFlow, not a one-shot — the snackbar shows until acted on; generation's `commitOutfit`
  sets it, the list VM mirrors it into `OutfitsUiState.pendingWearOutfitId`, `clearPendingWear`
  clears it on the repo). `OutfitGenerationUiState` carries the whole `composer*` cluster +
  prediction + the tag-suggestion/tag-edit flow, plus read-only `outfits`/`wearHistory` mirrors
  (prompt taste signals + edit-save lookup; the generation VM collects `repo.outfits` and
  `wearHistoryFlow` itself — no cross-VM read). `OutfitGenerationViewModel` owns `trendsCache`,
  `commitOutfit`, `prepareSave`/`dismissSaveDialog`, `startEditing` (+ `startEditingTripOutfit`)
  and `deviceCountryCode`; the three extension files retarget it verbatim except
  `openComposerFromSelectedOutfits(selected, …)`, which now takes the selection from the caller
  (OutfitsScreen passes it and clears selection on the list VM — selection is list state).
  Consumers rewired: `OutfitComposerScreen` + `PredictionSetupDialog` take only the generation VM;
  `OutfitsScreen`, the two viewer destinations, `TripViewerScreen`, `TravelScreen`/
  `TravelOutfitsView` and `ItemViewerDestination` take both; `AppContent` declares
  `outfitGenerationViewModel`, its composer open-flag observer + every `openComposer`/
  `closeComposer` site target it. `OutfitsViewModel` is ~230 lines of pure list surface
  (`_state`/`folderId`/`persistOutfitFolders` now `private` — no extensions target it).
- *`TryOnViewModel` → composer/history:* on closer reading these are **not** separable screens —
  they're one layered modal sharing a nav-flag state machine (`isComposerOpen`/`isHistoryOpen`/
  `historyIsRoot`/`historyDetailIsRoot`/`viewingTryOn`), and `openComposer`/`openHistoryRoot`/
  `openHistoryDetail` each set composer **and** history flags together, so a split needs cross-VM
  coordination on every open. There's also no heavy save funnel to extract as a foundation (the
  history feed is already a thin `TryOnStore.observeTryOns()` derivation, § 5 slice 4d). The split
  therefore waits on slice 9's `TryOnRoute` navigation rework rather than a foundation step.

**Slice 9 — destination-scoped VMs + real composer navigation** (closes the phase-3/4
deferral). **Status: the folded-in slice-8 VM split landed (July 2026, above), and
`OutfitComposerRoute` converted to real navigation (July 2026):** `OutfitGenerationUiState` has
no `isComposerOpen` — openers seed the draft then navigate (the three `AppContent` seed sites
navigate inline; `OutfitsScreen` / the two viewer destinations / `TripViewerScreen` /
`TravelScreen`→`TravelOutfitsView` get an `onOpenComposer` callback), the destination passes
`onClose` (pop) into `OutfitComposerScreen`, whose close paths (requestClose / discard-confirm /
save-success via `commitOutfit`'s `onDone`) call `closeComposer()` + `onClose()`. Both
`AppContent` mirror effects for the outfit composer are gone; `goToTab` jumps still call
`closeComposer()` for draft hygiene only. Process-death restore shows an empty draft instead of
the old blank-then-pop. **`TryOnRoute` converted too (July 2026):** the layered flag machine
(`isComposerOpen`/`isHistoryOpen`/`historyIsRoot`/`historyDetailIsRoot`/`viewingTryOn`) is gone —
`TryOnUiState` is composer draft + derived history only, and the surface split into three real
routes: `TryOnRoute` (composer/result/no-photos), `TryOnHistoryRoute` (feed),
`TryOnDetailRoute(imageDriveId)` (pager over live history, pops when it empties). Dead code from
the removed Try-Ons sub-tab was deleted along the way (`openHistory`/`openHistoryRoot`/
`openHistoryDetail`/`requestSwapSource`/`closeHistory`/`viewTryOn`/`dismissViewingTryOn` — the
first two replaced by navigation, the rest had no callers). `TryOnScreen.kt` now hosts the three
destination composables over a shared `TryOnPageScaffold` chrome + `TryOnErrorDialog`. Back-stack
semantics improved intentionally: feed-hero edit / detail Regenerate push the composer (back
returns to the feed/detail; the flag machine closed everything). The "any pop must close the VM"
rule is fully retired — `close()`/`closeComposer()` on the Settings/AI-notice jumps are draft
hygiene only. **The TryOn composer/history VM split LANDED (July 2026)**, mirroring the outfits
split: a `@Singleton` `tryon/TryOnRepository` owns the store-derived `history` StateFlow (the
§ 5 slice 4d derivation moved off the VM), the `_tryons.json` read/write (`loadEntries`/
`writeAll` — Drive write + store mirror in one funnel), the Phase-2 `refreshFromDrive` (download
missing image files, then store write → invalidation re-runs the cached-file filter), the
batch `deleteTryOns`, and the cache-file naming + root-folder memo. `TryOnViewModel` is the
composer half (draft/generate/`saveCurrent` through `repo.writeAll`); the new
`TryOnHistoryViewModel` (`TryOnHistoryUiState`: history + error) owns refresh/deletes. The two
never call each other — a composer save reaches the feed via the repo flow. Consumers rewired:
the feed/detail destinations take both VMs (composer seeds stay on `TryOnViewModel`; delete +
error dialog move to the history VM), `WardrobeScreen`/`ItemViewerDestination` cascade deletes
and `UsageCostsTab` take the history VM, `AppContent`'s pre-warm calls `historyViewModel
.refresh()`. Try-on writes stay Drive-first (deliberate § 2 leftover), now in one place.
Remaining work: with cross-VM dependencies gone: the viewer destinations
(`ItemViewerRoute`/`OutfitViewerRoute`/`TripViewerRoute`) get destination-scoped
`hiltViewModel()` instances resolving content from the stores by route ids; the state-mirrored
composer routes become plain navigation — openers `navigate(OutfitComposerRoute(seedItemIds))`
instead of flipping VM open-flags (images resolve from the store, prefs from the repository),
deleting the open-flag mirror effects and the "any path that pops must also close the VM"
rule. The `LocalViewModelStoreOwner provides activity` pins drop per converted destination.
**Also folded in from slice 7:** the `WardrobeUiState` progress-cluster prune (deferred here
because the cluster is consumed across ~9 surfaces — incl. `AppContent`'s restore overlay and the
`FixCutoutBgDialog` host — that only get direct `hiltViewModel()` reads at this slice; once they
do, the only residue is the `processingImageId`/`isUploading`/`isProcessing` shared-state straddle,
which stays VM-managed). Optionally pair with adding a `SyncEngine` sync-state flow so the restore
overlay aggregates engine progress instead of polling VMs' `isLoading` (slice 7 part 3).
### Exit criteria (what done looks like)

- `AppContent` hosts navigation, global dialog observers and theme — no data bridges; the
  surviving `LaunchedEffect`s are navigation-semantic only.
- No screen receives another feature's ViewModel as a parameter; shared data flows through
  `core/database` Flows + the two session objects.
- `WardrobeViewModel` ≤ ~400 lines, no extension files, no `internal var` shared state.
- Unblocked and ready to start: `feature/*` module extraction (§ 1, mechanical phase-4-style
  moves), § 3 interface extraction at the now-injectable seams, § 8 fake-based VM/use-case
  tests.

### Verification (per slice)

`./gradlew :app:assembleDebug testDebugUnitTest` green; new tests ride each slice
(store-Flow invalidation, `ClosetSession` fan-in, each use-case's progress contract); manual
regression list per slice from the phase checklist — closet switch, offline mode, capture +
import batch, move/rollback, reinstall-restore, calendar pick mode. Update CLAUDE.md (bridges,
cache-folder rule, VM split sections) as each slice lands, per the working agreement.

## Verification (per phase)

- `./gradlew :app:assembleDebug` + `./gradlew testDebugUnitTest` green at every step.
- New invariant tests per migrated feature: optimistic write → Room → sync queue → fake-Drive
  drain → rollback path.
- Manual regression checklist: offline mode, reinstall-restore, closet switch, calendar pick
  mode, WebP conversion idempotency — the flows the current hand-rolled rules protect.
- Drive-format compatibility check per phase: a build from `main` and the phase branch pointed
  at the same test account must round-trip each other's data.
