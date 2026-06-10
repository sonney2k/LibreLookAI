# Phase 2 execution plan: Room replaces the remaining JSON caches

Working copy of the approved plan (original: `~/.claude/plans/async-swimming-axolotl.md`),
with live execution state. Pattern source for all slices:
`data/local/OutfitLocalDb.kt` / `RoomOutfitStore.kt` / `OutfitStoreTest.kt` (and
`WardrobeItemStoreTest.kt` for the test harness).

## Conventions (all steps)

- One commit per slice, each independently shippable/revertible; additive Room migrations only
  (`MIGRATION_N_N+1` in `LocalDataModule.kt`, exact CREATE TABLE/INDEX SQL matching the entity).
- Store rows keep the model as opaque Gson `json` (schema doesn't churn; Gson back-fills
  defaults — load-bearing for `OutfitEvent`'s no-arg-ctor back-fill).
- Legacy JSON files are read once (marker-gated) and left on disk as a downgrade safety net.
- Update `plan/refactor.md` phase-2 status per slice; one consolidated CLAUDE.md
  optimistic-writes edit at the end (avoid editing the same bullet three times).
- Verify per slice: `./gradlew :app:assembleDebug :app:testDebugUnitTest` green + confirm the
  new `*StoreTest` suite ran via `app/build/test-results/testDebugUnitTest/TEST-*.xml`.

## Step 1 — Outfits slice ✅ DONE (commit `5305dc9`)

`OutfitStore`/`RoomOutfitStore`/`OutfitLocalDb` (DB v2, `MIGRATION_1_2`), `OutfitsViewModel`
rewired, `folderId` stamped on create/edit save paths, `OutfitStoreTest` (5 tests), docs updated.

## Step 2 — Outfit events slice ✅ DONE (commit `1488f98`)

`OutfitEventStore`/`RoomOutfitEventStore`/`OutfitEventLocalDb` (DB v3, `MIGRATION_2_3`),
`OutfitEventsViewModel` rewired (Phase-1 read, Phase-2 + `persist()` cache writes; Drive-first
ordering kept), `OutfitEventStoreTest` (4 tests, incl. legacy `styleId` alias), docs updated.

## Step 3 — Trips slice ✅ DONE

`TripStore`/`RoomTripStore`/`TripLocalDb` (DB v4, `MIGRATION_3_4`; single-row seed marker —
global list), `TripsViewModel` rewired (`mutateTrip`'s sync cache write moved into its launch
ahead of the Drive save; `java.io.File`/`TypeToken` imports dropped), `TripStoreTest` (4 tests),
`plan/refactor.md` updated.

## Step 4 — Try-ons slice ✅ DONE

`TryOnStore`/`RoomTryOnStore`/`TryOnLocalDb` (DB v5, `MIGRATION_4_5`; key = `TryOn.id`,
single-row seed marker), `TryOnViewModel` rewired (Phase-1 read via store + `migrateSource`,
`localPath` resolution kept — image bytes stay files; Phase-2 + `deleteTryOns` writes via
store, the two `historyCacheFile()` writers found by grep; `historyCacheFile()` deleted),
`TryOnStoreTest` (4 tests), `plan/refactor.md` + consolidated CLAUDE.md optimistic-writes
edit done.

## Deferred (later slices, out of scope)

Token-usage files (`gemini/TokenUsage.kt`, own per-month structure + Drive sync) and
`trends_cache.json` (`gemini/FashionTrendsCache` is deliberately disk→Drive→Gemini layered).
Shopping items already ride on `WardrobeItemStore`.

## Manual regression flows (call out in final summary; not automatable here)

Cold-start Phase-1 paint of calendar / trips list / try-on history, offline mode,
reinstall-restore overlay, upgrade path (legacy JSON seeds exactly once).
