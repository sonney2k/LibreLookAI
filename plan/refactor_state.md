# Refactor — state (resume here)

**Updated 2026-07-12** (originally parked 2026-06-15). The full blueprint + per-slice status
detail lives in `plan/refactor.md`; this is the short "where we left off" note.

## TL;DR (2026-07-11)

**All eight architecture sections (§§ 1–8) are closed.** § 1 finished 2026-07-08 with the
`feature:wardrobe` extraction (`1938707` enabler + `08c6b57`) — every feature is now a
`core/*`-only leaf module (8 feature modules, 10 core modules). **`feature:settings` was
dropped by recorded decision** (see the § 1 slice-6 notes in `refactor.md`): settings is
shell-shaped — no feature→settings edge exists, its types sank to core, its cross-feature
hosts lifted to the shell in slice 3 — so a module would add a boundary with exactly one
consumer. `./gradlew :app:assembleDebug testDebugUnitTest` re-verified green on
`refactor-phase5-rest` (2026-07-11).

**What remains before this branch merges to `main` (not architecture work):**

1. **Manual regression pass** (the checklist below) on a device build.
2. Merge `refactor-phase5-rest` → `main` (fast-forward or merge commit — user's call), then
   the normal release process when ready.

Manual regression checklist: closet switch, offline mode, capture + import batch,
move/rollback, reinstall-restore, calendar pick mode, and the overlay destinations
(trip/outfit/item viewers, both composers, try-on feed/detail).

The last § 8 gap is closed (2026-07-12): the `SimilarityService` seam (`core/ml`, Hilt
`@Provides` → the `EmbeddingService` object) made `ItemIngestionPipeline`'s dedupe-gate /
local-bg-review branches fake-testable — 10 new tests in `ItemIngestionPipelineTest` over
`testing/FakeSimilarityService`.

---

## (historical note, 2026-07-06 — superseded by the TL;DR above)

## Branch / commit layout

- **`main` @ `8c5f5aa`** — slices 0–5 + the `@param:` Hilt annotation fixes. Still ahead of
  `origin/main` (`a2c50bf` = released **v2.4.0 / versionCode 29**); local `main` also contains
  the unpushed **v2.4.1 (versionCode 30)** release commit.
- **`refactor-phase5-rest`** — **the active branch**, rebased onto `main` (2026-07-02, clean).
  Carries slice 6 (complete), slice 7 (events + progress prune), slices 8–9 (complete, incl.
  the overlay-destination pin drops, `74253bf`), **all of § 3** (slices 1–5, closed by the
  static-global kills `6f4a802`, 2026-07-04) and the **§ 8 fake-based repository tests for all
  five repos** (2026-07-06, latest work — uncommitted as of this note). Build:
  `:app:assembleDebug` + `testDebugUnitTest` (app + all `core/*`) green.

## Where the refactor stands (the big picture)

Drive storage layout is unchanged. **Room is the single in-app source of truth** and all list
views derive from it. The read path is fully converted, the outfit/try-on/trips/wardrobe/shopping
load cores live on five `@Singleton` repos, every viewer destination has destination-scoped VMs
(§ 5 complete), **§ 3 is complete** (the `DriveService` + `AiClient` seams cover every consumer;
`AiRetry`/`GeminiProgress` injected), and **the § 8 fake-based repository layer is done** — all
five repos have invariant tests over `FakeDriveService` + the `testing/FakeStores.kt` fakes.
What remains: § 1 `feature/*` modules, § 6 `AiResult`, § 7 DataStore, § 8 VM tests, and the two
small § 2 leftovers below.

## § 8 testing status (the just-finished workstream)

- Store invariant tests (`data/local/*StoreTest`) — landed earlier.
- Handler suites (`WardrobeSyncHandlersTest`/`OutfitSyncHandlersTest`/`TripSyncHandlersTest`) —
  all six § 2 handlers' drain/retry/rollback/wipe-guard rules (landed with § 3 slice 2).
- First `AiClient` suite: `WardrobeBulkAiUseCasesTest` over `FakeAiClient` (§ 3 slice 4).
- **All five repos fake-tested** (2026-07-06): `TripsRepositoryTest`, `ShoppingRepositoryTest`,
  `TryOnRepositoryTest`, `OutfitsRepositoryTest` (pure resolution logic + derived flows,
  cross-closet reconcile, `saveOutfit`/`persistOutfitFolders` funnel), `WardrobeRepositoryTest`
  (session-driven scope, two-phase reconcile, recently-moved suppression via a gated listing,
  § 2 move/delete funnels, prefetch; Robolectric for the ConnectivityManager online gate — a
  `ShadowNetworkCapabilities` helper sets INTERNET+VALIDATED).
- Shared fakes promoted into `testing/FakeStores.kt` (`FakeMutationStore`, `FakeTryOnStore`) and
  `FakeDriveService` grew scriptable cache/listing/sidecar maps + call counters/gate hooks.
  `FakeOutfitStore`/`FakeOutfitEventStore` now mirror the Room re-home invariant (primary key
  re-homes on `replaceFolder`; outfit reads restore the `@Transient folderId`).
- Incidental cleanup: `OutfitsRepository` dropped its unused `Application` constructor param
  (keeps its test plain JUnit); `FakeDriveService.loadOutfitsJson` serves `outfitsJsonByFolder`.
- **Open follow-on: VM tests** (both seams exist; VMs are constructible with fakes).

## Cross-cutting arch sections still open (from `refactor.md` § status table)

- **§1 Multi-module** — `core/*` libs landed; `feature/*` modules unblocked (§ 5 done).
- **§6 Typed `AiResult`** — only `AiErrorReason` landed; Gemini still returns `null` on failure.
- **§7 DataStore** — `UserPreferences`/`OnboardingState`/pricing-cache still SharedPrefs;
  `ImageEncoding.tier` static (deferred from § 3 slice 5) waits on this settings seam.

## §2 SyncEngine leftovers (small, independent)

- Try-on saves are still Drive-first (not queued).
- ~~Shopping `saveSidecar` writes Drive directly~~ — **done 2026-07-06**: both shopping tag
  paths enqueue via the shared `SidecarSyncQueue` (`ShoppingClosetViewModelTest`, the first
  § 8 fake-based VM suite, proves the funnel end-to-end with the real handler registered).

## Resume plan

1. On `refactor-phase5-rest`: § 5, § 3 and the § 8 repo layer are done. Next candidates, in
   rough value order: **§ 8 VM tests** (mechanical now), **§ 6 `AiResult`** (self-contained,
   improves every AI call site), **§ 7 DataStore** (unblocks the `ImageEncoding.tier` kill),
   **§ 1 `feature/*` modules** (biggest churn, do last).
2. The §2 leftovers above are small and can ride any slice.
3. Manual regression before merging the branch to `main`: closet switch, offline mode,
   capture + import batch, move/rollback, reinstall-restore, calendar pick mode, and the
   overlay destinations (trip/outfit/item viewers, both composers, try-on feed/detail).
4. Exit criteria check (from `refactor.md`): no screen takes another feature's VM as a param
   is **not yet true** (screens still receive shared activity VMs as explicit params — that's
   the §1/§3 follow-on, not slice 9); `AppContent` data bridges are down to the restore
   overlay's loading reads (kept by decision) + navigation-semantic effects.
