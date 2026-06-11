# LibreLookAI architecture refactor blueprint

## Status at a glance (June 2026)

The four *migration phases* below have all landed their mechanically-possible slices, but the
real checklist is the eight **Target architecture** sections — several are deliberately
deferred and inter-blocking:

| § | Layer | Status |
|---|-------|--------|
| 1 | Multi-module Gradle | **Partial** — `:core:model`/`:core:common`/`:core:database`/`:core:ml`/`:core:sync`/`:core:ai` landed (phase 4); `core/designsystem` blocked on splitting the 31-locale `res/`, `feature/*` on § 5 |
| 2 | Room as source of truth + SyncEngine | **Partial** — all five JSON-cache slices landed (phase 2) as opaque-JSON cache rows; the `PendingMutation` queue + `SyncEngine` are live with the wardrobe **and shopping** metadata writes converted (sidecar saves, deletes, moves — June 2026, see phase 2 status); outfit/event/trip writes, real entities and the WorkManager trigger are not started |
| 3 | DI + interfaces at the seams | **Partial** — Hilt landed (phase 1); the gemini↔drive↔billing package cycles are broken (June 2026, see phase 1 status), making the layering acyclic; interface extraction at the I/O seams pending; static globals (`ImageEncoding.tier`, `GeminiProgress` slot, `AiRetry.action`) still alive |
| 4 | Navigation Compose | **Done** (phase 3 complete; per-destination VM scoping deferred to § 5) |
| 5 | Thin VMs over use-cases | **Not started** — the keystone blocker: gates `feature/*` modules, destination-scoped VMs, and removal of the cross-VM `LaunchedEffect` bridges |
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
   After events: trips (same Drive-first analysis), then WorkManager scheduling once a § 5
   pipeline needs process-death survival mid-drain.
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
   mechanical (`scripts/kt_split.py`-style, compiler-driven).
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

## Verification (per phase)

- `./gradlew :app:assembleDebug` + `./gradlew testDebugUnitTest` green at every step.
- New invariant tests per migrated feature: optimistic write → Room → sync queue → fake-Drive
  drain → rollback path.
- Manual regression checklist: offline mode, reinstall-restore, closet switch, calendar pick
  mode, WebP conversion idempotency — the flows the current hand-rolled rules protect.
- Drive-format compatibility check per phase: a build from `main` and the phase branch pointed
  at the same test account must round-trip each other's data.
