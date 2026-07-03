# Refactor — state (resume here)

**Updated 2026-07-03** (originally parked 2026-06-15). The full blueprint + per-slice status
detail lives in `plan/refactor.md`; this is the short "where we left off" note.

## Branch / commit layout

- **`main` @ `8c5f5aa`** — slices 0–5 + the `@param:` Hilt annotation fixes. Still ahead of
  `origin/main` (`a2c50bf` = released **v2.4.0 / versionCode 29**); local `main` also contains
  the unpushed **v2.4.1 (versionCode 30)** release commit.
- **`refactor-phase5-rest`** — **the active branch**, rebased onto `main` (2026-07-02, clean).
  Carries slice 6 (complete), slice 7 (events + progress prune), the slice-8
  `OutfitsRepository` foundation, **all of slice 9** (closed by the overlay-destination
  `LocalViewModelStoreOwner` pin drops, `74253bf`), and the start of **§ 3** (the
  `DriveService` repo seam, 2026-07-03). Build: `:app:assembleDebug` + `testDebugUnitTest`
  (app + all `core/*`) green.

## Where the refactor stands (the big picture)

Drive storage layout is unchanged. **Room is the single in-app source of truth** and all list
views derive from it. The read path is fully converted, the outfit/try-on/trips/wardrobe/shopping
load cores live on five `@Singleton` repos, every viewer destination has destination-scoped VMs,
and **slice 9 is complete** — the § 5 spine is done. What remains are the cross-cutting arch
sections (§ 1 feature modules, § 3 interfaces, § 6 AiResult, § 7 DataStore, § 8 fake-based tests),
now unblocked.

## Phase 5 slice status (the active workstream)

| Slice | State | Notes |
|-------|-------|-------|
| 0–5 | ✅ on `main` | observable stores, ClosetSession, UserPreferencesRepository, wear-history DB read, all read paths on Room Flows (4a–4d), ItemIngestionPipeline + JobLock |
| 6 — bulk-maintenance use-cases | ✅ on branch | four use-cases extracted; dead audit/folder-import deleted |
| 7 — UiState slimming + sealed events | ◑ | scroll one-shots + `WardrobeUiState` progress prune done; restore-overlay engine aggregation **stays deferred by decision** (2026-07-03): the `isLoading` flags encode each VM's tuned two-phase load orchestration, only `WardrobeRepository` exposes a sync-status flow — revisit if/when `SyncEngine` grows a sync-state surface |
| 8 — VM splits | ✅ on branch | `OutfitsRepository` foundation + the 2-way outfits split; try-on split landed with slice 9 |
| 9 — destination-scoped VMs + real composer nav | ✅ **complete** (2026-07-03) | VM splits (outfits 2-way, try-on 2-way over `TryOnRepository`); both composers real navigation; five repos (`Outfits`/`TryOn`/`Trips`/`Wardrobe`/`Shopping` + `ShoppingIngestionQueue`); destination-scoped VMs on all three viewer destinations; `WardrobeViewModel` slimmed (~520 lines, shape criteria met); **overlay pin drops landed** — all eight overlay destinations lost their `LocalViewModelStoreOwner provides activity` pin, the overlay-hosted screens lost every VM param default (compile error instead of silent fork; `OutfitComposerRoute` now passes `outfitEventsViewModel` explicitly — the one live default). Home / tab / Settings-graph pins stay (load-bearing: defaulted activity-scoped VMs in those subtrees) |

## Cross-cutting arch sections still open (from `refactor.md` § status table)

- **§1 Multi-module** — `core/*` libs landed; `feature/*` modules **now unblocked** (slice 9 done).
- **§3 DI interfaces** — **started (2026-07-03, see the § 3 execution plan in `refactor.md`)**:
  slice 1 landed — `DriveService` interface at the repo seam (the five § 5 repos depend on it;
  `DriveRepository` implements, absorbed extensions renamed `internal *Impl` with one-line
  member delegates; Hilt `@Binds`). Next: slice 2 (sync handlers), slice 3 (pipeline/use-cases/
  VMs), slice 4 (`AiClient`), slice 5 (static-global kills: `ImageEncoding.tier`,
  `GeminiProgress` slot, `AiRetry.action`).
- **§6 Typed `AiResult`** — only `AiErrorReason` landed; Gemini still returns `null` on failure.
- **§7 DataStore** — `UserPreferences`/`OnboardingState`/pricing-cache still SharedPrefs.
- **§8 Testing** — store invariant tests landed; **fake-based repo tests started**:
  `TripsRepositoryTest` over the shared `FakeDriveService` (`app/src/test/…/testing/`) covers
  reconcile / single-flight / memoization / save+delete funnels. The other four repos follow
  the same pattern; VM tests want the § 3 `AiClient` seam. New test dep:
  `kotlinx-coroutines-test` (virtual-time Main for the repos' `Main.immediate` scopes).

## §2 SyncEngine leftovers (small, independent)

- Try-on saves are still Drive-first (not queued).
- Shopping `saveSidecar` writes Drive directly instead of riding `wardrobe.sidecarSync`.

## Resume plan

1. On `refactor-phase5-rest`: § 5 is done and § 3 is the active workstream — continue with
   § 3 slice 2 (sync handlers onto `DriveService`, + their § 8 drain/retry/rollback tests),
   then slice 3 (pipeline/use-cases/VMs), slice 4 (`AiClient`), slice 5 (static globals).
   §1 `feature/*` modules and §6/§7 stay independent alternatives.
2. The §2 leftovers above are small and can ride any slice.
3. Manual regression before merging the branch to `main`: closet switch, offline mode,
   capture + import batch, move/rollback, reinstall-restore, calendar pick mode, and the
   overlay destinations (trip/outfit/item viewers, both composers, try-on feed/detail).
4. Exit criteria check (from `refactor.md`): no screen takes another feature's VM as a param
   is **not yet true** (screens still receive shared activity VMs as explicit params — that's
   the §1/§3 follow-on, not slice 9); `AppContent` data bridges are down to the restore
   overlay's loading reads (kept by decision) + navigation-semantic effects.
