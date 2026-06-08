# Handoff: LibreLookAI · Consistent FAB System

## Overview

Across the five primary screens — **Outfits, Wardrobe, Calendar, Shopping, Travel/Trips** — the floating action button (FAB) is currently inconsistent and overlaps content. This handoff replaces the ad-hoc per-screen FABs with **one shared, scroll-aware Extended FAB** plus **one shared selection-mode action bar**, so create-actions and multi-select actions look and behave identically everywhere while still saying the right verb per screen.

Target users are non-technical, fashion-oriented women and men, so the system favors **spelled-out verbs** ("New outfit", "Add item") over a bare "+".

## About the design files

The files in this bundle are **design references created in HTML/React/JSX** — prototypes showing intended look and behavior. **They are not production code to copy directly.** Re-implement in the existing app's Compose stack (Material 3, Kotlin, `com.librelookai.*`) using its established patterns, theming, and the existing `WardrobePalette` tokens. Use the mocks for layout, proportions, copy, color, and behavior — not source transcription.

| File | Purpose |
|---|---|
| `FAB System.html` | The canvas — open this to see everything: the problem, three placement directions, the recommended system across all 5 screens, the per-screen selection bars, and dark mode. |
| `fab-shared.jsx` | The nav-bar replica + the FAB variants + the `SelectionBar`. The two components you're porting are `ExtendedFab`/`CollapsedFab` and `SelectionBar`. |
| `fab-system.jsx` | The recommended system applied per screen — read this for the exact verb + actions per screen. |
| `fab-options.jsx` | The three placement directions (A recommended, B docked, C mini+sheet). Reference only. |
| `fab-current.jsx` | The current problems, recreated. **Do not implement** — it's the "before". |

## Fidelity

**High-fidelity.** Match sizes, colors (via existing palette tokens), positions, copy, and behavior. Adapt to real M3 components (`ExtendedFloatingActionButton`, `FloatingActionButton`, `Surface`) where visually equivalent.

---

## The current state (what we're replacing)

Every screen places a FAB at `Alignment.BottomEnd` with `padding(16.dp)`, but the behavior, color, and selection-mode treatment all differ:

| Screen | File (approx line) | Idle FAB | Selection / menu treatment |
|---|---|---|---|
| Outfits | `outfit/OutfitList.kt` (~587) | circular `+` → create composer | vertical **speed-dial** column of ExtendedFABs (Combine / Try on / Delete), ~527 |
| Wardrobe | `wardrobe/WardrobeGrid.kt` (~589) | circular `+` → open camera | vertical **speed-dial** (Create style / Try on / Suggest replacements / Move / Delete), ~496 |
| Calendar | `outfit/OutfitCalendar.kt` (~291) | circular `+` → **DropdownMenu** (Add / Copy to day / Move to day / Remove) | n/a — uses the dropdown |
| Shopping | `shopping/ShoppingListTab.kt` (~303) | circular `+` → open camera | vertical **speed-dial** (Create outfit / Try on / Move to closet / Delete / Cancel), ~214 |
| Travel | `travel/TravelOutfitsView.kt` (~301) | circular `+` → open planner | n/a |

Problems:
1. **Two competing floating buttons** — every screen's corner `+` fights the raised center "Try on" ✦ FAB in `MainNavBar.kt` (~105).
2. **Overlap** — the corner FAB sits directly above the nav bar on top of the last row of items; lists don't reserve space for it.
3. **Color drift** — the nav FAB uses `palette.fabBg`; the screen FABs use M3 `colorScheme.primary` / `primaryContainer`. They don't match.
4. **Selection speed-dials** stack 3–5 buttons up the right edge, covering the items being selected.
5. **Calendar's `+`** opens a menu, so the same icon means something different there.

---

## What to build

### 1. `AppFab` — one shared scroll-aware Extended FAB

Create `ui/components/AppFab.kt`. Replaces every idle per-screen FAB.

**Signature**
```kotlin
@Composable
fun AppFab(
    label: String,                 // the verb, e.g. "Add item"
    icon: ImageVector,             // leading icon
    onClick: () -> Unit,
    expanded: Boolean,             // true = labeled pill, false = icon-only circle
    modifier: Modifier = Modifier,
    visible: Boolean = true,       // hidden during selection mode / offline
)
```

**Visuals**
- Use M3 `ExtendedFloatingActionButton(expanded = expanded, …)` — it already animates the pill↔circle transition.
- `containerColor = palette.fabBg`, `contentColor = palette.fabFg` — **the same tokens the nav "Try on" FAB uses**, so the two read as one family. Do **not** use `colorScheme.primaryContainer` anymore.
- Position: `Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = <navBarHeight> + 14.dp)`. It must rest **above** the nav bar, not tucked behind it. The current code pads only 16.dp, which is why it collides with the bar — add the nav-bar height (the screens are inside the Scaffold content area whose bottom inset is the nav bar; pad by that inset + 14.dp).
- Wrap with `AnimatedVisibility(visible)` so it slides out when selection mode starts.

**Scroll-aware behavior**
- Drive `expanded` from the screen's `LazyGridState` / `LazyListState`:
  ```kotlin
  val expanded by remember {
      derivedStateOf { listState.firstVisibleItemScrollOffset == 0 || !listState.isScrollInProgress }
  }
  ```
  i.e. **expanded (labeled) when at rest or at the top; collapsed (circle) while actively scrolling down.** Re-expands when scrolling stops. Keep it simple — don't over-engineer direction detection unless QA asks.

**Content padding (fixes the overlap)**
- Every list/grid must add bottom `contentPadding` so the last item clears the resting pill:
  `contentPadding = PaddingValues(bottom = 96.dp + navBarInset)` (≈ FAB height 50 + gap + breathing room). This is the core fix for "don't overlap the items."

**Per-screen wiring**

| Screen | `label` | `icon` | `onClick` (existing handler) |
|---|---|---|---|
| Outfits | "New outfit" | `Icons.Default.Add` | `onOpenCreateComposer()` |
| Wardrobe | "Add item" | `Icons.Default.Add` | `onOpenCamera()` |
| Calendar | "Log outfit" | `Icons.Default.CalendarMonth` | open the "add outfit to calendar" sheet (`addTargetDay = null; showAddSheet = true`) |
| Shopping | "Add find" | `Icons.Default.Add` | `onCaptureClick()` |
| Travel | "Plan trip" | `Icons.Default.FlightTakeoff` | `onOpenPlanner()` |

> Keep using the existing string resources where they exist; add new ones for the new verbs (see Strings). The `tryon_fab` string is unrelated — leave it on the nav FAB.

### 2. `SelectionActionBar` — one shared bottom action bar

Create `ui/components/SelectionActionBar.kt`. **Replaces every selection-mode speed-dial column AND the Calendar dropdown.**

**Signature**
```kotlin
data class SelectionAction(
    val label: String,
    val icon: ImageVector,
    val kind: Kind = Kind.Normal,     // Primary | Normal | Danger
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
fun SelectionActionBar(
    count: Int,
    onClear: () -> Unit,
    actions: List<SelectionAction>,
    modifier: Modifier = Modifier,
)
```

**Visuals** (see the `SelectionBar` mock in `fab-shared.jsx`)
- A bottom-anchored `Surface` spanning full width, `borderTop = 1.dp palette.divider`, soft top shadow, padding `10/14/14`. Sits **below** the content — content is NOT covered (unlike the speed-dial).
- Top row: a 26.dp circular ✕ (`onClear`) + "<count> selected" (`palette.text`, 14sp/700).
- Action row: `Row` of equal-ish buttons, each a stacked icon-over-label (10.5sp/700). Color by `kind`:
  - **Primary** → `palette.fabBg` bg / `palette.fabFg` fg, slightly wider (`weight 1.4f`). Always **first**.
  - **Normal** → `palette.surface2` bg / `palette.textMid` fg, `1.dp palette.border`.
  - **Danger** → `palette.error @ ~9% alpha` bg / `palette.error` fg. Always **last**.
- Animate in/out with `AnimatedVisibility` (slide up from bottom). When it's visible, hide `AppFab`.

**Per-screen actions** (order matters — primary first, danger last)

| Screen | `count` = | actions |
|---|---|---|
| Wardrobe | items | **Style** (primary, create outfit from selection) · Swap (suggest replacements) · Move (to closet) · **Delete** (danger) |
| Outfits | outfits | **Combine** (primary, shown when ≥2) · Try on (shown when ==1) · Love · **Delete** (danger) |
| Shopping | finds | **Add to closet** (primary) · Style (create outfit) · **Delete** (danger) |
| Calendar | a day's outfits | **Copy to day** (primary) · Move to day · **Remove** (danger) |
| Travel | packed outfits | **Move to trip** (primary) · **Remove** (danger) |

These map straight onto the existing handlers already wired in each file's speed-dial / dropdown (e.g. Wardrobe's `onCreateOutfitFromSelection`, `onSuggestReplacements`, `showMoveDialog`, `pendingDeleteIds`; Calendar's `pendingDayMove`, delete confirm). You are **re-presenting** existing actions, not adding new logic.

> Conditional actions (Outfits "Combine" needs ≥2, "Try on" needs ==1) — build the `actions` list conditionally before passing it in, same conditions the current code uses.

### 3. Entering selection mode

- **Unchanged trigger:** long-press an item (or tap a select affordance). The FAB is a *create* button and must **never** toggle selection mode.
- On entering: hide `AppFab` (`visible = false`), show `SelectionActionBar`.
- On clearing (✕ or back): reverse.

### 4. Calendar specifically

- The corner **DropdownMenu goes away.** The FAB becomes a single "Log outfit" action.
- Day-specific **Copy / Move / Remove** now live in the `SelectionActionBar`, reached by **long-pressing a day** (the code already tracks `fabMenuDay` / `pendingDayMove` — repoint long-press to set the selected day and show the bar instead of opening the dropdown).

---

## Files to change

| File | Change |
|---|---|
| `ui/components/AppFab.kt` | **new** — shared FAB |
| `ui/components/SelectionActionBar.kt` | **new** — shared selection bar |
| `wardrobe/WardrobeGrid.kt` | replace idle `+` (~589) with `AppFab`; replace speed-dial column (~488–586) with `SelectionActionBar` |
| `outfit/OutfitList.kt` | replace idle `+` (~587) with `AppFab`; replace speed-dial (~517–586) with `SelectionActionBar` |
| `outfit/OutfitCalendar.kt` | replace `+`+DropdownMenu (~280–330) with `AppFab` ("Log outfit") + long-press→`SelectionActionBar` |
| `shopping/ShoppingListTab.kt` | replace idle `+` (~303) with `AppFab`; replace speed-dial (~206–301) with `SelectionActionBar` |
| `travel/TravelOutfitsView.kt` | replace idle `+` (~301) with `AppFab` ("Plan trip") |
| each screen's list/grid | add bottom `contentPadding` (≈96.dp + nav inset) so the last row clears the FAB |

**Out of scope (follow-up):** the single-item edit speed-dials inside the full-screen viewers (`wardrobe/FullScreenViewer.kt`, `outfit/OutfitFullScreenViewer.kt`, `travel/TripViewerChrome.kt`'s `TripActionsFab`). These are a different surface (one open item, not a list). For now leave them, but ideally they later adopt the same `SelectionActionBar` grammar (primary-first / danger-last, `palette.fabBg`). Flag in PR description.

---

## Design tokens

Use the existing `WardrobePalette` (`ui/theme/Color.kt`, via `LocalWardrobePalette.current`). No new tokens needed — this is the point: the FAB finally uses the same tokens as the nav button.

| Use | Token |
|---|---|
| FAB / primary action background | `palette.fabBg` |
| FAB / primary action foreground | `palette.fabFg` |
| Normal selection button bg | `palette.surface2` |
| Normal selection button border | `palette.border` |
| Normal selection button fg | `palette.textMid` |
| Danger action fg | `palette.error` (bg = error @ ~9% alpha) |
| Selection bar surface | `palette.surface` |
| Selection bar top divider | `palette.divider` |
| Selected-item tint / border | `palette.primary` (overlay `palette.selOverlay`, check `palette.selCheck`) |

Because these are palette tokens, the whole system flips correctly across all seven themes (verified in the canvas "dark mode" section).

**Sizing:** FAB pill height 50.dp (M3 default for ExtendedFAB), collapsed circle 50.dp; corner radius from M3 FAB shape. Selection-bar action buttons 46.dp tall, 14.dp radius. Icon 18–24.dp.

---

## Strings / i18n

Add to `values/strings.xml` (+ existing locales, e.g. `values-fil`). Suggested keys:

```
fab_outfits_new          "New outfit"
fab_wardrobe_add         "Add item"
fab_calendar_log         "Log outfit"
fab_shopping_add         "Add find"
fab_travel_plan          "Plan trip"      (may reuse travel_plan_trip)

sel_count                "%1$d selected"
sel_style                "Style"
sel_swap                 "Swap"
sel_move                 "Move"
sel_combine              "Combine"
sel_love                 "Love"
sel_add_to_closet        "Add to closet"
sel_copy_to_day          "Copy to day"
sel_move_to_day          "Move to day"
sel_move_to_trip         "Move to trip"
sel_remove               "Remove"
action_delete            (exists)
tryon_fab                (exists — stays on nav FAB)
```

Reuse existing strings where the wording already matches (`wardrobe_suggest_replacements`, `shop_list_move_to_closet`, `calendar_copy_to_day`, `calendar_move_to_day`, etc.) rather than duplicating.

---

## Acceptance checklist

- [ ] Every primary screen shows one green Extended FAB using `palette.fabBg`, resting above the nav bar, never over the last row of content.
- [ ] FAB collapses to a circle while scrolling, re-expands at rest.
- [ ] Tapping the FAB performs the create action only — never toggles selection.
- [ ] Long-press an item → FAB hides, bottom `SelectionActionBar` slides up.
- [ ] Every selection bar: count + ✕ left, primary (green) first, danger (red) last; content is not covered.
- [ ] Calendar has no corner dropdown; day actions come from long-press + the bar.
- [ ] All seven palettes (incl. dark) render the FAB and bar correctly.
- [ ] Accessibility: FAB & every bar button ≥ 48.dp touch target, each with a `contentDescription`; bar announces "<n> selected".

## Open questions

1. **FAB scroll-collapse** — confirm you want collapse-on-scroll (Option A). If the team prefers the simpler always-expanded pill, drop the `derivedStateOf` and pass `expanded = true`. (Docked / mini variants are in `fab-options.jsx` if you reconsider placement.)
2. **Calendar long-press discoverability** — moving day actions behind long-press is cleaner but less discoverable. Acceptable, or add a hint on first use?
3. **Label wording** — "Add find" (Shopping) and "Log outfit" (Calendar) are my picks; swap for your in-product terms if different.
4. **Full-screen viewers** — adopt the same bar grammar now, or as a follow-up PR? (Listed out of scope above.)
