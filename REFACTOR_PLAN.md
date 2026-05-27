# Refactor plan — 500-line file ceiling

**Goal:** no `.kt` file over 500 lines (target band 300–500); each file one cohesive
responsibility, clean names, no dead code. Follows the **Placement rule** in `CLAUDE.md`.

**Soft-limit decision (agreed):** split by logical unit, but a file may exceed 500 when a
*single cohesive composable/function* dominates it and can't be split without artificially
fragmenting it. Don't decompose a working composable just to chase the number. Clusters of
many functions over 500 still get split.

## Progress
- ✅ **Phase 0** — legacy `SettingsScreen.kt` deleted (commit 46e0a81).
- ✅ **Phase 1 · WardrobeScreen.kt** (3273 → 9 files; compiles + tests green). All ≤500
  except `WardrobeGrid.kt` (816, single `GridContent` composable) and `FullScreenViewer.kt`
  (509, single composable) — both permitted under the soft limit. Candidate for later
  composable decomposition: `GridContent` (740 LOC).
- ✅ **Phase 1 · OutfitComposerScreen.kt** (2193 → 7 files: Screen/Chrome/Stack/Sheets/
  Dialogs/AddItem/Suggestions; compiles + tests green). Only `OutfitComposerScreen.kt` (598)
  over the limit — single dominant `OutfitComposerScreen` composable (soft-limit ok).
- ✅ **Phase 1 · OutfitsScreen.kt** (2071 → 6 files: OutfitsScreen/OutfitList/OutfitCard/
  OutfitFullScreenViewer/OutfitViewerParts/OutfitTagDialogs; compiles + tests green). Only
  `OutfitList.kt` (699) over — single dominant `OutfitListScreen` composable (soft-limit ok).
- ✅ **Phase 1 · ShoppingHelperScreen.kt** (1790 → 6 files: ShoppingHelperScreen/
  ShoppingListTab/SimilarityFinderTab/MatchPreview/MatchDebug/IdentifyGapsTab; compiles +
  tests green). All ≤500.
- ✅ **Phase 1 · TryOnScreen.kt** (1619 → 5 files: TryOnScreen/TryOnComposer/OutfitPicker/
  TryOnResult/TryOnDetail; compiles + tests green). All ≤500.
- ✅ **Phase 1 · TravelScreen.kt** (1595 → 6 files: TravelScreen/TravelPlannerContent/
  TravelOutfitsView/TripCards/TravelPlannerForm/TravelGoalCards). All ≤500.
- ✅ **Phase 1 · TripViewerScreen.kt** (1215 → 4 files: TripViewerScreen/TripViewerChrome/
  TripDayCards/TripRefine). All ≤500.
- ✅ **Phase 1 · PredictionSetupScreen.kt** (1000 → 3 files: PredictionSetupScreen/
  PredictionSetupCards/PredictionWeatherSheet). All ≤500.
- ✅ **Phase 1 · CaptureScreen.kt** (715 → 2 files: CaptureScreen/PhotoReviewScreen). ≤500.
- ✅ **Phase 1 · OutfitCalendar.kt** (671 → 2 files: OutfitCalendar/OutfitWearStats). ≤500.
- ✅ **Phase 1 · WardrobeFiltersShared.kt** (563 → 2 files: WardrobeFiltersShared/
  WardrobeColorSwatches). ≤500.
- ✅ **Phase 1 · UsageScreen.kt** (508 → 2 files: UsageScreen/UsageSection). ≤500.

**Phase 1 COMPLETE** (all 12 screen files). Remaining >500 files are Phase 2/3 (ViewModels,
MainActivity, repositories) plus four soft-limit-accepted single-dominant-composable files:
`WardrobeGrid.kt` (816), `OutfitList.kt` (699), `OutfitComposerScreen.kt` (598),
`FullScreenViewer.kt` (509).

**Hard constraints (do not break):**
- **Same-package extraction** for UI — keep `internal` visibility working without widening it.
- **Tested signatures stay put**: the stateless `*Content` composables, `TagFilterBar`,
  `TagEditScreenContent`, etc. are exercised by Robolectric tests — keep their name,
  package, and visibility. Move callers, not these.
- **Multi-language**: pure move, no string changes. No new hardcoded text.
- **Verify every file** with `./gradlew :app:assembleDebug` (validates resources + compile)
  and run `./gradlew testDebugUnitTest` after each phase.
- **Ask before committing**; commit per-file or per-small-group so each diff is a pure move
  and reviewable. No behavior changes in this effort.

## Scope

19 files exceed 500 lines (~30k lines). The 13 files in the 300–500 band already comply and
are out of scope. Order below is by leverage and risk.

---

## Phase 0 — Free win: delete the legacy Settings screen
- **`settings/SettingsScreen.kt` (3252)** is the compiled-but-unreferenced one-release
  fallback (per CLAUDE.md; live UI is `settings/v2/`). **Delete it** rather than split.
  - Confirm no references: `grep -rn "SettingsScreen(" --include=*.kt app/src | grep -v settings/v2`
    and that nothing imports `com.librelookai.settings.SettingsScreen`.
  - Remove the "kept compiled-but-unreferenced as a one-release fallback" note from CLAUDE.md.
  - **−3252 lines for one delete.** Biggest single win; do first.

## Phase 1 — Screens (mechanical, same-package composable extraction)
Each: keep the entry `*Screen` composable + its private state in the original file; move
cohesive composable clusters into new siblings in the **same package**.

- **`wardrobe/WardrobeScreen.kt` (3273)** →
  - `WardrobeScreen.kt` — `WardrobeScreen` + `GridContent` (~700)
  - `WardrobeTagTaxonomy.kt` — `SortOption`, `TagCategory`/`TagCount`, `tagValueResId`,
    `localizedTagValue`, `tagCategories`/`tagCategoryCounts` extensions (403–608)
  - `WardrobeTagFilterBar.kt` — `TagFilterBar`, `HideTagsChip`, `SortButton` *(tested — keep names)*
  - `FullScreenViewer.kt` — `FullScreenViewer`, `ZoomableImage`, `TagsOverlay` (1479–2072)
  - `TagEditScreen.kt` — `TagEditScreen`/`TagEditScreenContent` + `IdentityCard`, `SubField`,
    `TagsTableCard`, `TagRow*`, `Color*`, `ChipDrawer`, chips, `SaveIndicator` (2073–2837)
    *(`TagEditScreenContent` tested — keep name/visibility/package)*
  - `WardrobeSheets.kt` — `DuplicateCheckSheet`, `FindByPhotoResultsSheet`,
    `FixCutoutBgItemDialog`, `UrlImportDialog`
- **`outfit/OutfitComposerScreen.kt` (2193)** →
  - `OutfitComposerScreen.kt` — entry + `ComposerHeader`/`ContextStrip`/`ComposerEditBottomBar`
  - `OutfitComposerStack.kt` — `ComposerStackedView`/`CoreBand`/`FlankColumn`/`StackedTile`
  - `OutfitComposerSheets.kt` — `CategoryPickerSheet`, `AddItemSheet`, `WeatherPickerSheet`,
    `ClosetPickerSheet`, `WeatherSection`, `OutfitTagsEditor`, `SaveOutfitDialog`,
    `DiscardChangesDialog` *(several `internal` + reused by header cluster — keep names)*
  - `OutfitComposerSuggestions.kt` — `ComposerSuggestionSwiper`/`Viewer`
- **`outfit/OutfitsScreen.kt` (2071)** →
  - `OutfitsScreen.kt` — `OutfitsScreen` + `OutfitListScreen` + sort
  - `OutfitCard.kt` — `OutfitCard`, `StyleSortButton`, `OutfitTripChip`/`OutfitTagChip`
  - `OutfitFullScreenViewer.kt` — viewer + `OutfitPageBody` + `OutfitViewerItemTile` + buckets
  - `OutfitTagDialogs.kt` — `SuggestTagsDialog`, `EditOutfitTagsDialog`, `RefinementSection`
- **`shopping/ShoppingHelperScreen.kt` (1790, 17 composables)** → screen / result cards /
  `MatchPreviewDialog` + dialogs (note: `MatchPreviewDialog` is shared — keep name/visibility).
- **`tryon/TryOnScreen.kt` (1619, 16)** → screen / composer / picker dialogs / inline history.
- **`travel/TravelScreen.kt` (1595, 22)** → trip list / trip editor / day cards / dialogs.
- **`travel/TripViewerScreen.kt` (1215, 13)** → viewer / day pages / dialogs.
- **`outfit/PredictionSetupScreen.kt` (1000, 16)** → setup form / option sections / dialogs.
- **`wardrobe/CaptureScreen.kt` (715, 4)** → capture/camera vs review-and-tag.
- **`outfit/OutfitCalendar.kt` (671, 9)** → month grid vs day-detail sheet.
- **`wardrobe/WardrobeFiltersShared.kt` (563, 3)** → group by filter concern.
- **`settings/UsageScreen.kt` (508, borderline)** → `UsageCostsTab` vs `UsageSection`.

## Phase 2 — MainActivity ✅ DONE
- ✅ **`MainActivity.kt` (1203 → 5 files; compiles + tests green).** Lifted the entire
  `setContent` composition into a top-level `AppContent(activity)` composable, leaving
  `MainActivity` a 20-line thin Activity. Extracted siblings:
  - `AppContent.kt` (802) — root composition (single dominant composable; soft-limit ok)
  - `MainCompositionLocals.kt` (68) — `LocalOpenSettings`/`LocalClosetSelector` +
    `ClosetSelectorContext` + `ViewerHeaderActions`
  - `AppScreenHeader.kt` (210) — `AppScreenHeader` + `LocationButton`
  - `MainNavBar.kt` (184) — `AppNavBar` + `NavSlot` (custom bottom bar)
  - `MainActivity.kt` (20) — thin Activity
  Lift substitutions: `this@MainActivity`→`activity` (×12), the `application` arg,
  `POWER_SERVICE`→`Context.POWER_SERVICE`, and one bare `this`-as-Context. `AppNavBar`
  bumped private→internal. No behavior change.

## Phase 3 — ViewModels & repositories (extract collaborators; can't slice a class)
- **`wardrobe/WardrobeViewModel.kt` (3075)** — heaviest:
  - `WardrobeModels.kt` — move UI-state/data classes (`DriveImage`, `FindByPhoto`,
    `DuplicateCheck`, `WardrobeUiState`, `AuditProgress`, `CutoutBgFixProgress`, …, 50–365).
    Pure cross-package data → `data/model/`; UI-state stays in `wardrobe/`.
  - `WardrobeAuditController.kt` — orphan/raw/sidecar/duplicate audit workflow
  - `WardrobeImportController.kt` — URL/SAF import + preview
  - `CutoutBgFixController.kt` — cutout-bg fix scan/review
  - `WardrobeDedupeController.kt` — find-by-photo + duplicate detection
  - VM delegates to these; remaining VM should land near target band.
- **`outfit/OutfitsViewModel.kt` (1789)** → extract suggestion/prediction, tag-edit,
  calendar/wear-stats workflows into collaborators or same-package extensions.
- **`data/drive/DriveRepository.kt` (1004)** → split by domain: client/auth, file CRUD,
  folder/closet ops, sidecar+metadata, sync (same `data/drive` package).
- **`gemini/GeminiRepository.kt` (880)** → by call category (tagging, outfit suggestion,
  try-on, segmentation/bg) + shared proxy/networking helper; keep null-on-failure contract
  and `TokenUsageRepository.recordUsage` logging at each call site.
- **`shopping/ShoppingClosetViewModel.kt` (642)** → extract its largest workflow.

## Validation & sequencing
1. Phase 0 (delete) → build → commit.
2. Phases 1–2 one file at a time: extract → `assembleDebug` → spot-check → commit per file.
   These are pure moves; low risk.
3. Phase 3 last (highest risk — touches data flow): per CLAUDE.md, update
   `CLAUDE.md`/`CLAUDE_ARCHIVE.md` package-layout notes as files move; run full
   `testDebugUnitTest` after each VM/repo.
4. Final gate: `find ... -name '*.kt' | xargs wc -l | awk '$1>500'` returns nothing but
   intentionally-exempt generated files (none expected).

## Risks / watch-outs
- **Robolectric tests** reference specific `internal` composables — never rename/move those
  out of package; only move their callers. Re-run tests after Phase 1 UI splits.
- **Compose `@Preview`** functions, if any, move with their target composable.
- **Window/Dialog quirks** (`CLAUDE_ARCHIVE.md`): extracted Dialog/Sheet composables must keep
  the `CompositionLocalProvider` re-provision pattern intact — move whole, don't trim.
- ViewModel extraction is the only phase that can introduce behavior bugs; keep collaborators
  stateless where possible and pass the VM's `viewModelScope`/state in explicitly.
