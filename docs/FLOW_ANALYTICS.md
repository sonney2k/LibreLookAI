# Flow analytics plan

Instrumentation plans for the five core user flows, so each can be read as a **funnel** in
GA4. Companion to `docs/ONBOARDING_ANALYTICS.md` (which covers first-run onboarding) and the
top-level `screen_view` tracking.

## Conventions (already in place)

- **`screen_view`** (`Analytics.screen(name)`) — one per top-level destination. Now covers: the 5
  tabs (`Outfits`/`Wardrobe`/`Shopping`/`Travel`/`Settings`, in `AppContent.kt`), `SignIn`,
  `OutfitComposer`, `TryOnComposer`, `TryOnHistory`, `TravelPlanner`, `TripViewer`, plus the Shopping
  sub-tabs (`ShoppingHelperScreen.kt`). Onboarding has its own `onboarding_step` funnel.
- **`ui_action`** (`Analytics.action(screen, action, extra)`) — individual taps. Lots already exist
  (see the tables below). Good for "where do users tap" but **not** a funnel on their own.
- **Funnels need terminal events.** The biggest current gap across every flow is the **success /
  failure endpoint** — the moment an item actually lands, an outfit is saved, a generation returns.
  Without those you can see intent (taps) but not conversion. Each plan below marks new events
  **ADD**; everything else already exists.

> Funnel events should be **custom events** (`Analytics.event` / `Analytics.action`) with a stable
> `action`/`step` string, so each stage is a distinct, registrable GA4 dimension. Keep param
> cardinality low (counts, enums, booleans — never free text / IDs).

---

## Flow 1 — Adding wardrobe items (with funnel) — ✅ IMPLEMENTED

**Goal:** how many started an add, by source (camera / gallery / URL), and how many resulted in a
fully-processed item. This is the most important funnel — it feeds every other feature.

> **Implemented.** Events fire via `logWardrobeAdd(stage, source, extra)` (`WardrobeModels.kt`);
> `source` is `AddSource` (`camera`/`gallery`/`url`) threaded through `PendingJob` +
> `LocalBgReviewItem` so the queue's terminal events attribute the origin. Stages emitted:
> `upload_start`, `dedupe_prompt`/`dedupe_confirm`/`dedupe_cancel`, `item_live` (`tagged` bool),
> `failed` (`reason`: `upload`/`raw_missing`/`cutout_upload`/`exception`). Still TODO: register the
> event + params as GA4 custom dimensions and build the funnel (recipe below).

### The pipeline
Entry → (review) → dedupe gate → upload → background queue → cutout + classify → **item live**.
Three entry sources converge on `uploadPhotoInternal` → `proceedWithCameraUpload` → the queue
(`processQueue` → `processQueuedImage`, `WardrobeViewModel.kt:774/795`).

### Funnel stages

| # | Stage | Event | Status | Location |
| --- | --- | --- | --- | --- |
| 1 | Open add affordance | `ui_action` Capture `open_gallery` / `open_url_import_dialog`; Wardrobe `open_gallery` / `open_url_import_dialog` | exists | `CaptureScreen.kt:301/309`, `WardrobeScreen.kt:102/107` |
| 1b | Camera shutter | `ui_action` Capture `shutter` | exists | `CaptureScreen.kt:272` |
| 2 | Confirm captured photo | `ui_action` Capture/Review `confirm` | exists | `PhotoReviewScreen.kt:181` |
| 2b | Submit URL | `ui_action` Wardrobe/Shopping `submit_url_import` | exists | `WardrobeSheets.kt:121`, `ShoppingListTab.kt:440` |
| 3 | **Upload started** | `wardrobe_add` `{source, stage:"upload_start"}` | **ADD** | `WardrobeViewModelUpload.kt` — start of `proceedWithCameraUpload` / `uploadGalleryPhotos` / `confirmUrlImportPick` |
| 4 | Dedupe gate shown | `wardrobe_add` `{stage:"dedupe_prompt"}` + resolution `{stage:"dedupe_confirm"/"dedupe_cancel"}` | **ADD** | `routeImportAfterDedupe` / `confirmDuplicateImport` / `cancelDuplicateImport` (`WardrobeViewModelUpload.kt:81/104/114`) |
| 5 | **Item processed (success)** | `wardrobe_add` `{source, stage:"item_live", tagged:bool}` | **ADD** | end of `processQueuedImage` after classify (`WardrobeViewModel.kt:795`, after the `classifyClothing` block) |
| 5f | Processing failed | `wardrobe_add` `{stage:"failed", reason}` | **ADD** | the `?: return` bailouts in `processQueuedImage` (cutout upload fail, etc.) and the `runCatching{}` in `processQueue` (`:778`) |

**`source` param** = `camera` / `gallery` / `url` — thread it from the entry point into the queued
`PendingJob` so stage 5 can report it. (Check whether `PendingJob` already carries enough to infer
source; if not, add a `source` field.)

### Funnel to build in GA4
`open_gallery|shutter|submit_url` → `wardrobe_add{upload_start}` → `wardrobe_add{item_live}`.
Segment by `source` to compare camera vs gallery vs URL completion. The 4→5 drop is processing
loss (failed cutout / classify / offline); the 1→3 drop is users who back out before committing.

### Notes
- Gallery import is bulk — emit `upload_start` **per item** (or once with a `count` param) and
  `item_live` per item, so completion rate is per-photo not per-session. Decide one and keep it
  consistent.
- Background processing can finish minutes later (queue + `JobForegroundService`). `item_live`
  fires whenever `processQueuedImage` completes, which is correct but means stage 5 events trail
  stage 3 in time — fine for funnels (GA4 funnels are per-user, not per-session-window) but don't
  build a *session-scoped* funnel for this one.

---

## Flow 2 — Wardrobe items → outfit (creation flow) — ✅ IMPLEMENTED

**Goal:** how users go from wardrobe items to a saved outfit, and how AI-generate vs manual save
split.

> **Implemented.** `outfit_generate` `{result: ok/empty/failed, reason?, count?}` in
> `enhanceComposerWithAi` (`OutfitsComposer.kt`); `outfit_saved` `{mode: create/edit, ai_generated,
> items}` in `commitOutfit` (`OutfitsViewModel.kt`, both create + edit success paths). `ai_generated`
> is derived from whether composer suggestions were present at save time.

### Entry points (all open `OutfitComposerScreen`)
- Outfits tab "create" (`OutfitList.kt:585` `open_create_composer`).
- Wardrobe multi-select → "create outfit from selection" (`AppContent.kt:571`).
- Shopping selection → "create outfit from selection" (`ShoppingListTab.kt:216`, `AppContent.kt:622`).

### Funnel stages

| # | Stage | Event | Status | Location |
| --- | --- | --- | --- | --- |
| 1 | Open composer | `screen_view` `OutfitComposer` + the `open_create_composer` / `create_outfit_from_selection` actions (carry `count`) | exists | added this change; actions as above |
| 2 | Add/swap an item in a slot | `ui_action` ComposerSlotPicker `open_find_by_photo` etc. | partial | `OutfitComposerAddItem.kt:214` — **ADD** a generic `add_item` / `swap_item` action |
| 3 | Generate with AI (intent) | `ui_action` OutfitComposer `generate_with_ai` | exists | `OutfitComposerScreen.kt:407` |
| 3s | **AI generate result** | `outfit_generate` `{result:"ok"/"empty"/"failed"}` | **ADD** | `generateText`/composer generate call site in `OutfitsComposer.kt` (success vs `null`) |
| 4 | Save (intent) | `ui_action` OutfitComposer `save` | exists | `OutfitComposerScreen.kt:414` |
| 4s | **Outfit saved (success)** | `outfit_saved` `{items:count, ai_generated:bool, mode:"create"/"edit"}` | **ADD** | the save/commit fn in `OutfitsComposer.kt` (after the outfit is persisted) |

### Funnel to build
`OutfitComposer (screen)` → `generate_with_ai` (optional branch) → `save` → `outfit_saved`.
Two questions answered: (a) of users who open the composer, how many save? (b) of saves, what share
used AI generation (`ai_generated` param on `outfit_saved`)?

### Notes
- The composer is also the **edit** path. `mode` param on `outfit_saved` separates new creation from
  edits so the creation funnel isn't polluted.
- Suggestion-carousel navigation already tracked (`suggestion_prev/next`, `:315/321`); leave as
  engagement signals, not funnel stages.

---

## Flow 3 — Shopping helper — ✅ IMPLEMENTED

**Goal:** measure the two shopping sub-flows — (a) **find-by-photo / similarity** (does this exist
in my wardrobe?) and (b) **shopping-list management** (capture/import an item → act on it).

> **Implemented.** (a) `shopping_similarity` `{result: matches/none/failed, reason?, count?}` in
> `onCapturedFile` (`ShoppingHelperViewModel.kt`). (b) `shopping_item_added` `{source:
> camera/gallery/url/similarity}` in `uploadRaw` (`ShoppingClosetImport.kt`) — fires when the item
> first appears in the list. Shopping uses its **own** pipeline (`ShoppingClosetViewModel`), not the
> wardrobe one, so this is a dedicated event (no double-count with Flow 1).

### Sub-flow A: Similarity finder (`SimilarityFinderTab`)

| # | Stage | Event | Status | Location |
| --- | --- | --- | --- | --- |
| 1 | Enter Similarity sub-tab | `ui_action` Shopping `subtab_similarity` + `screen_view` | exists | `ShoppingHelperScreen.kt:173/159` |
| 2 | Capture/submit query | `ui_action` Shopping/Similarity `capture_query` | exists | `SimilarityFinderTab.kt:228` |
| 2s | **Search result** | `shopping_similarity` `{result:"matches"/"none"/"failed", count}` | **ADD** | where matches come back (VM search-similar result; embedding/index call) |
| 3 | Open a match | `ui_action` Shopping/Similarity `open_match_preview` | exists | `SimilarityFinderTab.kt:127` |
| 4 | Add to shopping list | `ui_action` Shopping/Similarity `add_query_to_shopping_list` / `add_match_to_shopping_list` | exists | `SimilarityFinderTab.kt:148/181` |

### Sub-flow B: Shopping list (`ShoppingListTab`)

| # | Stage | Event | Status | Location |
| --- | --- | --- | --- | --- |
| 1 | Add to list (capture / URL) | `ui_action` Shopping `open_camera` / `submit_url_import` | exists | `ShoppingListTab.kt:305/440` |
| 1s | **Item added to list** | `shopping_item_added` `{source:"camera"/"url"/"similarity"}` | **ADD** | shopping-closet upload completion (mirror Flow 1 stage 5 for `_shopping/`) |
| 2 | Select item(s) | `ui_action` Shopping `toggle_selection` / `long_press_select` | exists | `ShoppingListTab.kt:167/175` |
| 3 | Act on selection | `ui_action` Shopping `create_outfit_from_selection` / `try_on_selection` / `confirm_move_to_closet` | exists | `ShoppingListTab.kt:216/237/486` |

### Funnels to build
- A: `subtab_similarity` → `capture_query` → `shopping_similarity{matches}` → `add_*_to_shopping_list`.
- B: `open_camera|submit_url_import` → `shopping_item_added` → (`create_outfit_from_selection` |
  `try_on_selection` | `confirm_move_to_closet`). The "move to closet" terminal answers the core
  product question: did the shopping item become a real wardrobe item?

### Notes
- Shopping uploads reuse the wardrobe pipeline (`_shopping/` closet). If Flow 1 stage 5 carries a
  closet/`isShopping` param, `shopping_item_added` can be derived from `wardrobe_add{item_live}`
  filtered to the shopping closet instead of a separate event — **decide one** to avoid double
  counting.

---

## Flow 4 — Travel planner — ✅ IMPLEMENTED

**Goal:** of users who open the planner, how many fill it in and successfully generate a packing
plan; where they drop.

> **Implemented** in `TravelViewModel.kt`: `travel_plan` `{stage: destination_set}` (`pickDestination`),
> `{stage: generate_start, days, outfit_count, closets}`, `{stage: complete, outfits}`, and
> `{stage: failed, reason: forecast/no_response/empty}` — all in `doGenerate`. Note `destination_set`
> only fires when a **suggestion is picked**, not on free-typed destinations, so treat the clean
> funnel as `TravelPlanner (screen) → generate_start → complete`.

### The flow
Open planner (`TravelPlanner` screen) → set destination → set days/date/goal/vibes → pick source
closets → **Generate** → two-phase Gemini (`doGenerate`: outfits then packing list,
`TravelViewModel.kt:226/278`) → result (outfits + `packingList`).

### Funnel stages

| # | Stage | Event | Status | Location |
| --- | --- | --- | --- | --- |
| 1 | Open planner | `screen_view` `TravelPlanner` | exists | added this change (`AppContent.kt`) |
| 2 | Destination chosen | `travel_plan` `{stage:"destination_set"}` | **ADD** | `pickDestination` / `updateDestination` first commit (`TravelViewModel.kt:90/108`) |
| 3 | Generate (intent) | `travel_plan` `{stage:"generate_start", days, outfit_count, closets}` | **ADD** | `generate()` (`TravelViewModel.kt:179`) |
| 4a | Outfits phase done | `travel_plan` `{stage:"outfits_ok"/"outfits_failed"}` | **ADD** | end of phase 1 in `doGenerate` (`:226`) |
| 4b | **Packing plan done (success)** | `travel_plan` `{stage:"complete"}` / `{stage:"failed", phase}` | **ADD** | after phase 2 in `doGenerate` (`:278/302`, success vs `null` from `generateText`) |
| 5 | Acted on result | `ui_action` Travel `try_on` / save trip / create-outfit from a planned day | partial | trip viewer / planner result actions — **ADD** where missing |

### Funnel to build
`TravelPlanner (screen)` → `travel_plan{generate_start}` → `travel_plan{complete}`. The 1→3 drop is
form abandonment (too much setup?); the 3→4b drop is generation reliability / cost (`Insufficient
Credits`, Gemini `null`). Param `days`/`outfit_count`/`closets` on `generate_start` lets you see
whether bigger requests fail more.

### Notes
- Travel generation is the most expensive AI call and can 402 / time out. Make sure the `failed`
  terminal distinguishes `phase:"outfits"` vs `phase:"packing"` and ideally `reason` (credits /
  null / blocked) so the drop is diagnosable, not just "it failed."
- The planner reuses wear-history + considerations toggles; capturing which considerations were on
  (`history`/`weather`/`trends`) as low-cardinality booleans on `generate_start` would let you
  correlate settings with success — optional.

---

## Flow 5 — Try-On — ✅ IMPLEMENTED

**Goal:** of users who open a try-on, how many generate an image, how many succeed, and how many
keep the result. Try-on is the costliest image-generating call, so the generate→success drop is the
key reliability/cost signal.

> **Implemented** in `TryOnViewModel.generate`: `tryon_generate` `{result: ok/failed, reason?
> (credits/no_response), source, items?}`. `source` is the `TryOnSourceKind.serialized()` tag
> (outfit/wardrobe/shopping/travel), so the funnel ties back to the feeding flow.

### The flow
Quick sheet (pick source) → composer (seed person photo + items) → **Generate** →
`gemini.tryOnOutfit` (`TryOnViewModel.kt:271`, returns `null` on failure) → result → save / retry /
change items. Reachable from the center nav button, the history FAB, and empty-state CTAs.

### Funnel stages

| # | Stage | Event | Status | Location |
| --- | --- | --- | --- | --- |
| 1 | Quick sheet shown | `ui_action` TryOn/QuickSheet `shown` | exists | `AppContent.kt:742` |
| 2 | Pick a source | `ui_action` TryOn/QuickSheet `pick_source{source}` | exists | `AppContent.kt:745-777` |
| 2b | Pick outfit in composer | `ui_action` TryOn/Composer `pick_outfit{items}` | exists | `TryOnComposer.kt:244` |
| 3 | Composer shown | `screen_view` `TryOnComposer` | exists | `TryOnScreen.kt:87` |
| 4 | Generate (intent) | `ui_action` TryOn/Composer `generate{count}` | exists | `TryOnScreen.kt:310` |
| 4s | **Generate result** | `tryon_generate` `{result:"ok"/"failed", source, items}` | **ADD** | `generate()` after `gemini.tryOnOutfit` (`TryOnViewModel.kt:271`) — success vs `null` |
| 5 | Act on result | `ui_action` TryOn/Result `save` / `try_again` / `change_items` | exists | `TryOnScreen.kt:267/271/279` |
| 5b | Regenerate from detail | `ui_action` TryOn/Detail `regenerate` | exists | `TryOnDetail.kt:86` |

### Funnel to build
`TryOnComposer (screen)` → `generate` → `tryon_generate{ok}` → TryOn/Result `save`. The 4→4s drop is
generation reliability (Gemini `null`, 402 insufficient credits, blocked); the 4s→5 drop is result
quality (did they keep it or retry?). Segment by `source` (outfit / wardrobe / shopping / travel) —
ties try-on back to whichever flow fed it (`TryOnSourceKind`).

### Notes
- `notify = true` on `tryOnOutfit` already surfaces failures to the user via the global `AiNotice`
  dialog; the new `tryon_generate{failed}` should mirror that without a separate `reason` unless
  cheap to capture (credits vs null vs blocked already distinguished by `AiEvents` — reuse the same
  mapping if convenient).
- Retry-heavy sessions (`try_again` / `regenerate`) inflate generate counts; for the funnel use the
  per-user rate, and treat `try_again` as a quality signal, not a funnel stage.

---

## Status

All five flows' terminal events are ✅ **implemented and compiling** (additive, no behaviour change).
Events shipped:

| Flow | Event(s) |
| --- | --- |
| 1 wardrobe-add | `wardrobe_add` (`upload_start`, `dedupe_*`, `item_live`, `failed`) + `source` |
| 2 outfit | `outfit_generate`, `outfit_saved` |
| 3 shopping | `shopping_similarity`, `shopping_item_added` |
| 4 travel | `travel_plan` (`destination_set`, `generate_start`, `complete`, `failed`) |
| 5 try-on | `tryon_generate` |

**Remaining (console-only, no code):** register each event + its params as GA4 custom dimensions,
then build the funnels per the "Funnel to build" lines above (recipe in
`docs/ONBOARDING_ANALYTICS.md`). Verify the `screen_view` additions and the new events live with
`adb shell setprop debug.firebase.analytics.app com.librelookai` + GA4 DebugView.
