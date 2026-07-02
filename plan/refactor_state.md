# Refactor — state (resume here)

**Updated 2026-07-02** (originally parked 2026-06-15). The full blueprint + per-slice status
detail lives in `plan/refactor.md`; this is the short "where we left off" note.

## Branch / commit layout

- **`main` @ `8c5f5aa`** — slices 0–5 + the `@param:` Hilt annotation fixes. Still ahead of
  `origin/main` (`a2c50bf` = released **v2.4.0 / versionCode 29**); local `main` also contains
  the unpushed **v2.4.1 (versionCode 30)** release commit.
- **`refactor-phase5-rest`** — **the active branch**, rebased onto `main` (2026-07-02, clean).
  Carries slice 6 (complete), the events-half of slice 7, the slice-8 `OutfitsRepository`
  foundation, and the first slice-9 increments: the **2-way `OutfitsViewModel` split**
  (`fa0c5e8` — `OutfitGenerationViewModel` + `OutfitGenerationUiState`,
  `OutfitsRepository.saveOutfit` / repo-owned scroll one-shot + `pendingWearOutfitId`) and the
  **`OutfitComposerRoute` real-navigation conversion**. Build: `:app:assembleDebug` +
  `testDebugUnitTest` (app + all `core/*`) green.

## Where the refactor stands (the big picture)

Drive storage layout is unchanged. **Room is the single in-app source of truth** and all list
views derive from it. The read path is fully converted; the outfit write path now funnels through
`OutfitsRepository` shared by the two split VMs. What remains is slice 9's **navigation rework**
plus the cross-cutting arch sections.

## Phase 5 slice status (the active workstream)

| Slice | State | Notes |
|-------|-------|-------|
| 0–5 | ✅ on `main` | observable stores, ClosetSession, UserPreferencesRepository, wear-history DB read, all read paths on Room Flows (4a–4d), ItemIngestionPipeline + JobLock |
| 6 — bulk-maintenance use-cases | ✅ on branch | four use-cases extracted; dead audit/folder-import deleted |
| 7 — UiState slimming + sealed events | ◑ | scroll one-shots done; `WardrobeUiState` progress prune + restore-overlay engine aggregation deferred → slice 9 |
| 8 — VM splits | ✅ on branch | `OutfitsRepository` foundation **and the 2-way split landed**: `OutfitsViewModel` = list only (~230 lines, private state), `OutfitGenerationViewModel` = composer + prediction + tag flows (`OutfitsComposer`/`OutfitsPrediction`/`OutfitsTags` extensions retargeted). Repo owns `saveOutfit`, the scroll one-shot and `pendingWearOutfitId` — the VMs never call each other. `TryOnViewModel` split waits on slice-9 nav. |
| 9 — destination-scoped VMs + real composer nav | ◑ in progress | VM split done; **`OutfitComposerRoute` is real navigation** (no `isComposerOpen`; openers seed + navigate, closes clear + pop; both AppContent mirror effects deleted). Remaining: `TryOnRoute` real nav (+ TryOn composer/history VM split); viewer destinations get destination-scoped `hiltViewModel()`; drop the `LocalViewModelStoreOwner provides activity` pins per converted destination; the folded-in slice-7 `WardrobeUiState` progress prune. |

## Cross-cutting arch sections still open (from `refactor.md` § status table)

- **§1 Multi-module** — `core/*` libs landed; `feature/*` modules blocked on slice 9 completion.
- **§3 DI interfaces** — no repository interfaces at the I/O seams yet (blocks fake-based tests).
  Static globals still alive: `ImageEncoding.tier`, `GeminiProgress` slot, `AiRetry.action`.
- **§6 Typed `AiResult`** — only `AiErrorReason` landed; Gemini still returns `null` on failure.
- **§7 DataStore** — `UserPreferences`/`OnboardingState`/pricing-cache still SharedPrefs.
- **§8 Testing** — store invariant tests landed; fake-based repo/VM tests blocked on §3.

## §2 SyncEngine leftovers (small, independent)

- Try-on saves are still Drive-first (not queued).
- Shopping `saveSidecar` writes Drive directly instead of riding `wardrobe.sidecarSync`.

## Resume plan

1. On `refactor-phase5-rest`: confirm green (`./gradlew :app:assembleDebug testDebugUnitTest`).
2. Continue slice 9's navigation rework (see the slice-9 status in `refactor.md`): next is
   `TryOnRoute` real nav (+ its composer/history VM split — the layered
   `isComposerOpen`/`isHistoryOpen`/`historyIsRoot`/`historyDetailIsRoot` flag machine becomes
   routes), then destination-scope the viewers and drop the activity pins; finish with the
   `WardrobeUiState` progress prune.
3. §6/§7 are independent — can land anytime without blocking the spine.
4. Exit criteria (from `refactor.md`): `AppContent` has no data bridges; no screen takes another
   feature's VM as a param; `WardrobeViewModel` ≤ ~400 lines.
