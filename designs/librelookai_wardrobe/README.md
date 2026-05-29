# Handoff: LibreLookAI — Wardrobe + App Wireframes

## Overview

This bundle contains the updated UI design for **LibreLookAI**, an Android wardrobe / outfits / try-on app. There are two artifacts:

1. **`LibreLookAI Wireframes v2.html`** — A wireframe overview of all 5 top-level tabs (Outfits, Wardrobe, Shopping, Travel, Insights) plus their sub-tabs and key flows. Use this to understand the app's information architecture.
2. **`Wardrobe Grid Interactive v2.html`** — A high-fidelity interactive prototype of the **Wardrobe** screen (header, grid, FAB, filter bottom sheet, selection mode, theme switcher). Use this as the pixel reference for the Wardrobe redesign.

The target codebase is the existing **Jetpack Compose / Kotlin** Android app at the repo root. These designs should be implemented by editing the matching `*Screen.kt` files (`WardrobeScreen.kt`, `OutfitsScreen.kt`, `ShoppingHelperScreen.kt`, `InsightsScreen.kt`, `TravelScreen.kt`, `MainActivity.kt` for nav).

## About the Design Files

The HTML files in this bundle are **design references** — React/JSX prototypes built to communicate intended look, structure, and behavior. They are **not code to ship**. The implementation task is to recreate these designs in the existing Compose codebase, reusing established patterns (Material 3, the existing theme, existing ViewModels). Do not introduce a web view or port the JSX — translate the visual spec into Compose.

## Fidelity

- **`Wardrobe Grid Interactive v2.html` — High fidelity.** Pixel targets. Exact colors, spacing, type, motion, and interactions are intentional.
- **`LibreLookAI Wireframes v2.html` — Mid fidelity.** Shows IA, tab order, sub-tab structure, header chrome, FAB behavior across screens. Use it for layout and flow; defer to the existing Compose theme for finishes on screens beyond Wardrobe.

---

## What changed since the prior design pass

These are the deltas the developer needs to apply to the current code:

### 1. Bottom navigation
- **Calendar tab is removed** from the bottom nav and merged into Insights as a sub-tab.
- **Shopping** is now a top-level tab (icon: shopping bag).
- **Final tab order**: Outfits · Wardrobe · Shopping · Travel · Insights.

### 2. Outfits screen
- Now has **two sub-tabs**: `Outfits` (default) and `Try-Ons`.
- Try-Ons sub-tab loads history lazily on first reveal.
- Single `+` FAB that opens a unified composer (replaces the older split actions).
- Sort button in header is hidden when on the Try-Ons sub-tab.

### 3. Shopping screen (new top-level)
Three sub-tabs:
- **Shopping List** — manual list + camera capture flow.
- **Similarity Finder** — pick a reference photo, find similar items.
- **Identify Gaps** — analyze wardrobe, suggest missing categories.

### 4. Insights screen
Four sub-tabs (was three):
- **Calendar** (moved here from the old top-level tab)
- **Calendar Stats**
- **Wardrobe Stats**
- **Costs** — token usage, activity counts, 14-day daily charts (items, outfits, try-ons, wears, imports). Uses `UsageViewModel.events` filtered by `UsageCategory.BG_REMOVAL` for imports.

### 5. Wardrobe screen (the focus of the hi-fi prototype)
- **Header order (left → right):** Title "Wardrobe" · Location pill (e.g. "Main") · **Filters pill** · Find-by-Photo icon · Sort icon · Settings icon.
- **Filters pill** lives next to the location pill (not next to Sort). Shows applied-filter count badge when > 0; opens the filter bottom sheet.
- **URL import** option in the FAB sub-menu (alongside Camera and Gallery).
- **Dedupe / similarity check on import** — when adding items, run the embedder and surface near-duplicates before commit.
- **Selection mode** triggered by long-press on a tile; header swaps to count + bulk actions.

### 6. Settings
- New **AI** tab (model selection, on-device vs cloud toggles).
- New **Feedback** tab.
- API key fields moved into the **Credits** tab.

---

## Screens / Views

### Wardrobe (hi-fi spec — match exactly)

**Frame**: 390 × 844 (iPhone 14 Pro logical px; Compose target should treat this as a flexible portrait phone layout, not a fixed canvas).

#### Header (sticky top, height ~56dp)
Horizontal flex row, 16dp horizontal padding, 12dp vertical padding, `gap: 8dp`. Items in order:
1. **Title** — "Wardrobe", 20sp, weight 700, `flex: 1` (pushes the rest right).
2. **Location pill** — rounded 20dp, `chipBg` background, 1dp border `border`. Padding 6dp v / 10dp h. Icon `place` 14dp + label 12sp/500 + chevron-down 14dp. Tap toggles a dropdown listing closets ("Main", "Office", "Storage" when multi-closet enabled). Selected row shows a check + bold weight + `primaryDim` background.
3. **Filters pill** — rounded 20dp. Default state: `chipBg` background, **dashed 1dp border** in `border` color. Active state (when ≥1 filter applied): `primaryDim` background, **solid 1.5dp border in `primary`**, label and icon switch to `primary` color, plus a count badge (`primary` bg, white text, 10sp/700, padding 1×6dp, radius 10dp). Padding 6dp v / 11dp h, `gap: 4dp`. Icon `filter` 13dp + label "Filters" 12sp/600.
4. **Find-by-Photo** — 40×40 icon button, no background, icon `imgSrch` 22dp in `textMuted`.
5. **Sort** — 40×40 icon button, opens a sort menu (Name A–Z, Recently Added, Color, Category).
6. **Settings** — 40×40 icon button (gear), navigates to Settings.

#### Grid
- 3 columns, 8dp gutter, 16dp horizontal page padding.
- Each tile: aspect 3:4, rounded 12dp, `surface` background, image cover-fit. Selection state shows `selBorder` 2dp border + `selOverlay` tint + `selCheck` checkmark in top-right.
- Long-press tile → enters selection mode; header transforms to: back/clear · "{n} selected" · bulk actions (delete, move, tag).

#### FAB (bottom-right, above bottom nav)
- 56dp circle, `fabBg` bg, `fabFg` icon, elevation 6dp. Tap expands a vertical stack of mini-FABs (40dp, `fabMini` bg, `fabMiniBd` 1dp border, `fabMiniFg` icon + label):
  - Camera (capture new item)
  - Gallery (import from device)
  - URL (paste image URL)

#### Filter bottom sheet
- Modal sheet, `sheetBg` background, rounded top 20dp. Sections:
  - Category (chips: Tops, Bottoms, Outerwear, Footwear, Accessories…)
  - Color (color swatches)
  - Season (Spring/Summer/Fall/Winter chips)
  - Last worn (range slider, 0–365 days)
- Sticky footer: `Reset` (text button, `textMuted`) + `Apply ({n})` (filled, `primary`).

### Outfits (mid-fi spec)
- Top app bar: title "Outfits", location pill, sort icon (only on Outfits sub-tab), settings.
- TabRow below: `Outfits` | `Try-Ons`.
- Outfits sub-tab: 2-column grid of saved looks, each card shows the composed outfit image, name, last-worn date.
- Try-Ons sub-tab: 2-column grid of generated try-on images with timestamp.
- FAB `+` (single) opens the composer regardless of sub-tab.

### Shopping (mid-fi spec)
- TabRow: `Shopping List` | `Similarity Finder` | `Identify Gaps`.
- **Shopping List**: list of items with checkboxes, FAB to add (text or camera capture).
- **Similarity Finder**: empty state with "Pick a reference photo" CTA → result grid of similar wardrobe items.
- **Identify Gaps**: card list of missing categories with sample suggestions.

### Travel (mid-fi spec)
- Trip list. Each trip shows destination, date range, packed item count.
- FAB to create trip → trip detail with packing checklist.

### Insights (mid-fi spec)
- TabRow: `Calendar` | `Calendar Stats` | `Wardrobe Stats` | `Costs`.
- **Calendar**: month view, dots on days with logged outfits, tap day → outfit detail.
- **Costs**: 4 count cards (Items / Outfits / Try-Ons / Wears) + four 14-day bar charts (Outfits, Try-Ons, Wears, Imports) + a token-usage section pulling from `UsageViewModel`.

---

## Interactions & Behavior

- **Theme switcher** (Wardrobe prototype only — exposed for review): cycles 7 themes. In the real app, theme is set in Settings.
- **Header dropdowns** (Location, Sort): open on tap, close on outside-tap; only one open at a time. The Filter pill instead opens a **bottom sheet**, not a dropdown.
- **Filter pill state** is derived from the count of currently-applied filters (`appliedCount > 0` → active styling).
- **FAB sub-menu**: tap main FAB to expand, tap outside or main FAB again to collapse. Mini-FABs animate in with a 60ms stagger.
- **Selection mode**: long-press to enter, tap tiles to toggle, back gesture exits. Use `BackHandler` in Compose.
- **Toast**: bottom-center pill, `toastBg` / `toastFg`, 14sp, auto-dismisses after 1.8s.
- **Lazy load**: Try-Ons sub-tab calls `onLoadTryOnHistory()` only on first reveal (`LaunchedEffect(onTryOnsTab)`).

## State Management

Already wired in the Compose codebase — keep using the existing ViewModels:
- `WardrobeViewModel` — items, locations, selection, import.
- `OutfitsViewModel` — outfits list, sort.
- `TryOnViewModel` — try-on history.
- `OutfitEventsViewModel` — wear log (calendar).
- `UsageViewModel` — token usage events for Costs tab.

New UI state to add:
- Wardrobe header: `appliedFilterCount: Int` (drives Filter pill styling).
- Wardrobe FAB: `fabExpanded: Boolean`.
- Wardrobe sheet: `filterSheetOpen: Boolean`.

## Design Tokens

The prototype ships **7 theme variants** (token sets). The default is `green-light`. All themes share the same key names; pick whichever matches the existing app theme or wire up a theme picker.

### Default theme — `green-light`

| Token | Value |
|---|---|
| `bg` | `#F3F6EF` |
| `surface` | `#EBF1E5` |
| `surface2` | `#E0EAD8` |
| `primary` | `#4E7844` |
| `primaryDim` | `#D0E4C8` |
| `text` | `#1A2618` |
| `textMid` | `#3D5438` |
| `textMuted` | `#6A8060` |
| `border` | `#BDD0B2` |
| `divider` | `#D4E0CC` |
| `navBg` | `#EBF1E5` |
| `navIndicator` | `#D0E4C8` |
| `chipABg` (active chip) | `#4E7844` |
| `chipAFg` | `#FFFFFF` |
| `chipBg` (idle chip) | `#DFE9D8` |
| `chipFg` | `#2E4A28` |
| `fabBg` | `#4E7844` |
| `fabFg` | `#FFFFFF` |
| `fabMini` | `#EBF1E5` |
| `fabMiniFg` | `#4E7844` |
| `fabMiniBd` | `#4E7844` |
| `selBorder` | `#4E7844` |
| `selOverlay` | `rgba(78,120,68,0.18)` |
| `selCheck` | `#4E7844` |
| `progressFg` | `#4E7844` |
| `progressBg` | `#C4D8BC` |
| `toastBg` | `#1A2618` |
| `toastFg` | `#EBF1E5` |
| `sheetBg` | `#EBF1E5` |
| `error` | `#C0392B` |

Other themes available in the prototype (token names identical, swap values by reading the `THEMES` object in `Wardrobe Grid Interactive v2.html`):
- `green-dark`
- `sand-light`
- `indigo-dark`
- `pastel-mint`
- `pastel-blush`
- `pastel-lavender`

### Spacing scale
4 / 8 / 12 / 16 / 20 / 24 dp.

### Radius scale
- Tile / card: 12dp
- Pill / chip / dropdown: 20dp
- Sheet top: 20dp
- FAB / circular icon button: 50% (full)

### Typography
Font family in the prototype: **Plus Jakarta Sans**. In Compose, use the existing app font; if matching the prototype, swap to Plus Jakarta Sans via Google Fonts.

| Role | Size | Weight |
|---|---|---|
| Screen title | 20sp | 700 |
| Section header | 16sp | 600 |
| Body | 14sp | 400 |
| Caption / chip | 12sp | 500–600 |
| Badge | 10sp | 700 |

### Shadows / Elevation
- FAB: 6dp Material elevation
- Bottom sheet: 8dp scrim + sheet shadow
- Dropdown: 4dp

## Assets

- **Icons** — drawn inline as SVG in the prototype (`Ico` component, `P` paths object). Replace with Material Icons in Compose: `place`, `filter_alt`, `image_search`, `sort`, `settings`, `add`, `camera_alt`, `photo_library`, `link`, `check`, `close`, `keyboard_arrow_down`.
- **Images** — placeholder gradients in the prototype. Real app should use the existing image-loading pipeline (`AppImage` / Coil).

## Files in this bundle

- `Wardrobe Grid Interactive v2.html` — hi-fi Wardrobe prototype (reference for pixel/interaction detail).
- `LibreLookAI Wireframes v2.html` — full-app IA + screen wireframes.
- `README.md` — this document.

## Implementation suggestions

1. Start with **`MainActivity.kt`** — update the bottom nav to the new 5-tab order; remove Calendar route; add Shopping route.
2. Update **`WardrobeScreen.kt`** header — reorder header trailing content so the Filters pill sits between Location and Find-by-Photo. Add `appliedFilterCount` styling.
3. Add the **URL import** option to the Wardrobe FAB sub-menu and wire it to a small dialog that accepts a URL → fetch → run through the existing import pipeline (with dedupe check).
4. Update **`OutfitsScreen.kt`** — confirm sub-tabs match (`outfits_tab_outfits`, `outfits_tab_tryons`); make sure the sort button hides on Try-Ons sub-tab.
5. Build **`ShoppingHelperScreen.kt`** sub-tabs (Shopping List / Similarity Finder / Identify Gaps) if not yet present.
6. Update **`InsightsScreen.kt`** to surface 4 sub-tabs with Calendar as index 0.
7. Settings — add AI tab and Feedback tab; move API key into Credits tab.
