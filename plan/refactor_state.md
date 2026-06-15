# Refactor — parked state (resume here)

**Parked 2026-06-15.** We are pausing the architecture refactor to address tester
concerns / features. This file is the resume point. The full blueprint + per-slice
status detail lives in `plan/refactor.md`; this is the short "where we left off" note.

## Branch / commit layout

- **`main` @ `8c5a338`** — `refactor(wardrobe): extract ItemIngestionPipeline + JobLock
  use-case (§ 5 slice 5)`. 50 commits ahead of `origin/main` (`a2c50bf` = released
  **v2.4.0 / versionCode 29**), 0 behind. The whole 50-commit stack is the refactor
  (Hilt → core modules → NavHost → Room caches → SyncEngine queue → § 5 slices 0–5).
  Local `main` also contains the unpushed **v2.4.1 (versionCode 30)** release commit.
  Build: `:app:assembleDebug` clean; `testDebugUnitTest` + `:core:database` = **133
  tests, 0 failures**. Uncommitted in tree when parked: trivial Hilt
  `@param:ApplicationContext` annotation-target fixes on `JobLock.kt` /
  `ItemIngestionPipeline.kt` (+ this TODO/plan edit) — commit or stash before tagging.
- **`refactor-phase5-rest` @ `645a80a`** — 6 commits ahead of `main`, 0 behind. Carries
  slice 6 (complete), the events-half of slice 7, and the slice-8 foundation. **This is
  the branch to return to** when resuming the refactor.

## Where the refactor stands (the big picture)

Drive storage layout is unchanged. **Room is the single in-app source of truth** and all
list views (wardrobe, outfits, shopping, calendar wears, trips, try-on history) now
*derive* from it via store-observing collectors. The **read** path is fully converted.
What remains is the **write / VM-structure** side plus the cross-cutting arch sections.

## Phase 5 slice status (the active workstream)

Slices 0–5 landed on `main`. Slices 6–9 are the remaining phase-5 work; `refactor-phase5-rest`
advances 6–8:

| Slice | State | Notes |
|-------|-------|-------|
| 0–5 | ✅ on `main` | observable stores, ClosetSession, UserPreferencesRepository, wear-history DB read, all read paths on Room Flows (4a–4d), ItemIngestionPipeline + JobLock |
| 6 — bulk-maintenance use-cases | ✅ on branch | `RetagAllUseCase`, `RemoveAllBackgroundsUseCase`, `WebpConvertUseCase`, `CutoutBgFixUseCase` extracted; `ItemVersions`/`SidecarSyncQueue` relocated. **`RepairAuditUseCase`/`FolderImportUseCase` were dead code (no UI host) → deleted** (`…Audit.kt`, `…Import.kt`, `pendingAudit`, `TransientItems` overlay, dedupe/preferLocalBg VM mirrors all gone). `WardrobeViewModel` ~1126 lines. |
| 7 — UiState slimming + sealed events | ◑ partial on branch | **Done:** scroll one-shots (`WardrobeEvent.ScrollToItem` / `OutfitsEvent.ScrollToOutfit`) as buffered `Channel`s — kills the old state-field + `consumePendingScroll*` + `setLocation` preservation hack. **Deferred → slice 9:** the `WardrobeUiState` progress-cluster prune (`processingImageId`/`isUploading`/`isProcessing` straddle the VM↔use-case boundary, read across ~9 surfaces). **Deferred (no owner):** restore-overlay engine aggregation (needs a `SyncEngine` sync-state flow; only `drain()` exists today). |
| 8 — VM splits per screen | ◑ foundation only on branch | **Done:** `OutfitsRepository` (`@Singleton`) extracted — owns cross-closet scope, store-derived `outfits`/`wardrobeImages` flows, Drive load helpers, the `persistOutfitFolders` funnel + mutex; `OutfitsViewModel` is now a thin consumer. `OutfitsRepositoryTest` covers `resolveOutfits` + single-home write selection. **Deferred → slice 9:** the actual splits. **Plan revised:** `OutfitsViewModel` → **2-way** (list + new `OutfitGenerationViewModel`), NOT 3-way — composer + prediction share one AI-generation state machine. `TryOnViewModel` split is entangled with slice 9 nav (one layered modal sharing nav flags). |
| 9 — destination-scoped VMs + real composer nav | ✗ not started | The single remaining lump. Now **also carries** the deferred slice-7 UiState prune and the slice-8 VM splits. |

## Cross-cutting arch sections still open (from `refactor.md` § status table)

- **§1 Multi-module** — `core/*` libs landed; **`feature/*` modules blocked on slice 9** (VMs still activity-scoped + cross-feature). `core/designsystem` blocked on splitting the 31-locale `res/`.
- **§3 DI interfaces** — Hilt in, package cycles broken; **no repository interfaces at the I/O seams** (blocks fake-based tests). Static globals still alive: `ImageEncoding.tier`, `GeminiProgress` slot, `AiRetry.action`.
- **§6 Typed `AiResult`** — only the `AiErrorReason` enum landed; sealed `AiResult<T>` not started → **Gemini still returns `null` on failure**.
- **§7 DataStore** — BYOK key Keystore-encrypted; `UserPreferences`/`OnboardingState`/pricing-cache still SharedPrefs.
- **§8 Testing** — store invariant tests landed; fake-based repo/VM tests blocked on §3 interfaces.

## §2 SyncEngine leftovers (small, independent)

- **Try-on saves are still Drive-first** (not queued through the SyncEngine).
- **Shopping `saveSidecar`** writes Drive directly instead of riding `wardrobe.sidecarSync` (handler is already folder-agnostic → cheap follow-up).

## Resume plan when we come back

1. `git checkout refactor-phase5-rest`; confirm green (`./gradlew :app:assembleDebug testDebugUnitTest`).
2. Tackle **slice 9** as the keystone — it unblocks `feature/*` (§1) and absorbs the deferred slice-7/8 work. Execution sequence is spelled out in `refactor.md` slice-8 status (move new-outfit save + scroll-to-outfit one-shot into `OutfitsRepository`, split `OutfitsUiState`, rewire generation consumers, then destination-scope the viewer/composer routes).
3. §6/§7 are independent — can land anytime without blocking the spine.
4. Exit criteria (from `refactor.md`): `AppContent` has no data bridges; no screen takes another feature's VM as a param; `WardrobeViewModel` ≤ ~400 lines.
