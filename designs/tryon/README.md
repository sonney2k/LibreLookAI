# Handoff: Try-On — Entry, History & States

## Overview

Three coordinated pieces of the Try-On experience for LibreLookAI:

1. **Entry point — Center AI button.** A new global way to start a try-on, reachable from every main tab. Replaces "discover Try-Ons buried in Outfits sub-tab" with a raised AI button in the center of the bottom navigation. Tapping it opens a **Quick Try-On sheet** that asks which surface to start from (outfit / wardrobe / shopping / travel) and routes to the composer with the source pre-filled.
2. **Past try-ons — Hero feed.** The history list (currently the Outfits ▸ Try-Ons sub-tab and the result of the "See past try-ons" shortcut). The latest try-on is a big hero card with an item-stack overlay; earlier results fall into a mixed 2-column + 3-column grid for visual rhythm.
3. **Screen states** — empty, no-reference-photos, generating, result/preview, past-try-on detail.

These work together: the entry button populates `TryOnUiState.sourceOutfitId` (or an equivalent for wardrobe/shopping selections), the composer renders the source banner, generation runs, the result saves to Drive, and the history screen surfaces it with source provenance preserved.

## About the Design Files

The HTML and JSX files in this bundle are **design references** — React prototypes built with `<deck-stage>`-free vanilla React + Babel to communicate intended layout, behavior, and visual treatment. They are **not** production code to ship.

The implementation task is to **recreate these designs in the existing Compose codebase** at `app/src/main/java/com/librelookai/`, reusing established patterns: `MainActivity`'s `NavigationBar`, Material 3 `colorScheme`, existing `TryOnViewModel` / `TryOnUiState`, the `ProfileViewModel.tryOnFiles()` reference-photo plumbing, existing `Analytics.action(...)` event names, and `LocalSystemBarsPadding`. Do **not** port JSX into a web view.

Open `Try-On Preview.html` in any browser to view all screens side by side on a pannable canvas.

## Fidelity

**High-fidelity.** Colors, spacing, radii, typography, and interaction details are intentional. Match them in Compose.

---

## Screen 1 — Center AI button (entry point)

### Name
Persistent Try-On entry — lives in every screen's bottom navigation.

### Purpose
Make Try-On discoverable from anywhere in the app without forcing the user into a particular destination first. Tap → choose source → land in composer with that source's data preloaded.

### Layout — Bottom navigation

Frame `390 × 844`. Bottom nav sits at the bottom of every main destination (Outfits, Wardrobe, Shopping, Travel). Five slots in a Row:

| Slot | Width | Content |
|---|---|---|
| 1 | 1f | **Outfits** — `Icons.Default.Style` + label |
| 2 | 1f | **Wardrobe** — `Icons.Default.Tune` (or existing wardrobe icon) + label |
| 3 | 60.dp fixed | **Raised AI button** (see below) |
| 4 | 1f | **Shopping** — `Icons.Default.ShoppingBag` + label |
| 5 | 1f | **Travel** — `Icons.Default.FlightTakeoff` + label |

Container: `Surface(color = surface, top border 1.dp divider)`, padding `horizontal=6.dp, top=4.dp, bottom=14.dp + navBar insets`.

Each regular slot:
- 6.dp vertical / 2.dp horizontal padding
- 22.dp icon, 10sp/600 label, gap 3.dp
- Active state: 34×28 rounded-14.dp `primarySoft` background pill behind the icon; icon + label tint `primary`, label weight 700
- Inactive: icon + label tint `textMuted`

### Raised AI button
- 56×56 circle, positioned at `top=-22.dp` from the nav top (so it floats above the nav row)
- Background: `linear-gradient(135deg, aiAccent → primary)` — in Compose use a `Brush.linearGradient(listOf(aiAccent, primary))` on a `Surface(shape = CircleShape)`
- Icon: `Icons.Default.AutoAwesome` (sparkle), 24.dp, tint `onPrimary`
- Shadow: `0 10 24` of `primary` at ~40% alpha; **plus** a 5.dp outer ring in `background` color to punch the button out of the nav row (use a `border = BorderStroke(5.dp, background)` or wrap in a `Box` with a slightly larger background-colored circle behind)
- Label below: "Try on", 10sp/700, `primary`, centered, marginTop 34.dp from the floating button's center

### Where Insights goes
Insights drops out of the bottom nav. It moves to a **chart icon in each main tab's header** (top-right, next to Settings):
- 36×36 IconButton, no background, `Icons.Default.TrendingUp` 18.dp, tint `textMuted`
- Tap → navigates to `InsightsScreen` (existing destination, just routed from a header icon instead of a tab)

Rationale: Insights is the lowest-traffic destination. Trading a permanent nav slot for a header icon keeps it accessible while freeing the slot for the AI button.

### Interactions
- **Tap raised AI button** → open `QuickTryOnSheet` (bottom modal, see Screen 2). `Analytics.action("TryOn", "open_quick_sheet", mapOf("from" to currentTab))`
- **Long-press AI button** → optional shortcut: open `TryOnHistoryGrid` directly (skip the sheet). Show a `Toast`-style hint the first time.
- **Tap any regular nav item** → existing nav behavior
- The button does **not** indicate an active state — it's an action, not a destination

---

## Screen 2 — Quick Try-On sheet

### Name
**Quick Try-On** — modal bottom sheet, opened from the center AI button.

### Purpose
Disambiguate where the user wants to start. The composer needs a source to render its banner and pre-fill items; this sheet captures that intent in one tap.

### Layout
- `ModalBottomSheet` (Material 3) with `RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)`, `containerColor = bg`
- Drag handle: 38×4 rounded-2.dp `divider`, top-centered
- Padding `horizontal=18.dp, top=10.dp, bottom=24.dp + navBar insets`

Content (top → bottom):
1. **Title row** — "Quick try-on", 18sp/800 `onSurface`, marginBottom 2.dp
2. **Subtitle** — "What should we put on you?", 12sp `textMuted`, marginBottom 14.dp
3. **Source options** — 4 rows, gap 8.dp, marginBottom 16.dp
4. **Past try-ons shortcut** — outlined button, 44.dp tall, full width

### Source option row (×4)
- `Surface(shape = RoundedCornerShape(14.dp), color = surface, border = 1.dp divider)`, padding `horizontal=12.dp, vertical=10.dp`, `clickable { … }`
- Layout: Row, gap 12.dp, vertical center
  - 38×38 rounded-11.dp icon tile — background `sourceTint @ 13% alpha`, icon `sourceTint`, 18.dp
  - Column (flex 1):
    - Label, 13sp/700 `onSurface`
    - Sub, 11sp `textMuted`, marginTop 1.dp
  - Trailing chevron — `Icons.Default.ChevronRight`, 14.dp, `textMuted`

| Source | Label | Sub | Icon | Tint |
|---|---|---|---|---|
| `outfit` | A saved outfit | Pick from your library | `Icons.Default.Style` | `#7BBD6C` |
| `wardrobe` | Pick items | Mix & match from your wardrobe | `Icons.Default.Tune` | `#A8C09C` |
| `shopping` | Something to buy | From your shopping list | `Icons.Default.ShoppingBag` | `#C9A65A` |
| `travel` | Outfit from a trip | "From {trip name} · Day {n}" — null/hidden if no active trip | `Icons.Default.FlightTakeoff` | `#8AB8D8` |

### Interactions
- Tapping each row routes:
  - **outfit** → `OutfitPickerDialog` (existing in `TryOnScreen.kt`), then opens composer pre-filled
  - **wardrobe** → opens `WardrobeScreen` in **selection mode** with a sticky bottom CTA "Try on (n)" that calls `tryOnViewModel.openComposer(selectedIds, sourceOutfitId = null)`
  - **shopping** → opens `ShoppingHelperScreen` Shopping List tab in selection mode, same pattern; new shopping items get an `isNew` flag passed to the composer
  - **travel** → routes to the active trip's outfit picker; future feature, can be a stub for now
- "See past try-ons" → `tryOnViewModel.openHistory()`
- **Analytics**: `Analytics.action("TryOn/QuickSheet", "pick_source", mapOf("source" to choice))`

---

## Screen 3 — Composer (entry from Quick sheet)

This is the existing `TryOnComposerContent` in `TryOnScreen.kt` with a new **source banner** at the top and a slightly cleaner section layout. Keep all existing data flow (`tryOnViewModel.addItem` / `removeItem` / `selectOutfit` / `generate`).

### Source banner (new)
- `Surface(shape = RoundedCornerShape(14.dp), color = surface, border = 1.dp divider)`, padding `horizontal=12.dp, vertical=10.dp`
- Row, gap 10.dp, vertical center
  - 34×34 rounded-10.dp icon tile — `sourceTint @ 13% alpha` bg, `sourceTint` icon, 16.dp (icon = source's icon per the table above)
  - Column (flex 1):
    - Eyebrow: e.g. "From outfit", 10sp/600 `textMuted`, letter-spacing 0.4, uppercase
    - Title: source label (e.g. "Sunday Brunch"), 13sp/700 `onSurface`, marginTop 1.dp
  - **Swap** button — outlined pill, height 28.dp, padding `horizontal=10.dp, vertical=6.dp`, radius 999, border `1.dp` `border`, label 11sp/600 `textMid`. Tapping reopens the QuickTryOnSheet so the user can switch sources without losing the dialog stack.

The banner is **always present** in the composer when it was opened from a source surface. For the "Pick items" / wardrobe route, the eyebrow says "From wardrobe" and the title says e.g. "3 items selected".

### Rest of composer
Same as today but tightened:
- Items section heading: "Items ({n})" 13sp/700 + "Tap to remove" hint 11sp `textMuted`
- Items grid: 3-column, 8.dp gap, each tile aspect 1:1, radius 14.dp, border `1.dp` `divider`
  - X overlay: 22×22 rounded-11.dp, `Color.Black @ 65% alpha` bg, white close icon 12.dp
  - Name footer: 10sp/600 white over a `linear-gradient(0deg, black@45% → transparent)`, padded `horizontal=8.dp, top=14.dp, bottom=6.dp`
- Add tile: same size, dashed 1.5.dp `border`, `+ Add item` centered (20.dp icon + 10sp/600 label)
- **Reference-photos preview** (new card, optional): shows the user's 3 reference shots inline so they know what Gemini will work from. `Surface(border = 1.dp divider, radius=14.dp, padding=12.dp)`. Header: person icon + "Your reference photos" 12sp/700, trailing "Edit" 11sp/700 `primary` (opens Settings → Profile). Body: 3 equal-flex cards aspect 3:4, radius 10.dp, with Front/Side/Back labels.
- Sticky bottom CTA: `Generate try-on` button — `primary` bg, 50.dp tall, radius 25.dp, label 14sp/700, leading `Icons.Default.AutoAwesome` 16.dp, trailing pill "8 credits" 10sp/700 in `white @ 18% alpha`. Box-shadow `0 6 18` of `primary` @ 33% alpha.

---

## Screen 4 — Past try-ons (Hero feed)

### Name
**Past try-ons** — opened via:
- Outfits screen → Try-Ons sub-tab (existing entry, replaces `TryOnHistoryGrid`'s 2-column layout)
- Quick Try-On sheet → "See past try-ons" shortcut
- Result screen → after saving, the back button lands here

### Purpose
Show every saved try-on with source provenance, latest first. Encourage re-entry into the create flow via a prominent CTA.

### Layout
Frame `390 × 844`. Vertical scroll. Padding `horizontal=14.dp, bottom=100.dp` (to clear the FAB).

| Section | Notes |
|---|---|
| Header | Back · "Past try-ons" + subtitle "Latest at top" · step/menu icon (right) |
| Hero card | The most recent try-on, large |
| "Earlier" section heading | `EARLIER · {n} more` eyebrow row |
| Mid row | 2-column grid, 10.dp gap — next 2 try-ons |
| Dense row | 3-column grid, 8.dp gap — remaining try-ons |
| FAB | "New try-on" extended FAB, bottom-right |

### Hero card
- `Surface(color = surface, border = 1.dp divider, radius = 22.dp)`, marginBottom 18.dp
- Body: image area aspect 4:5, radius `22.dp` on top corners only
- **Top-left overlay**: source pill (filled variant) — see Source pill spec below, padding 12.dp from top/left
- **Top-right overlay**: 2 floating action buttons (Edit + Favorite), 32×32 rounded-16.dp, `white @ 80% alpha` bg with backdrop blur, icon 14.dp, color `onSurface`
- **Bottom overlay**: `linear-gradient(0deg, black@40% → transparent)` from bottom, padding `horizontal=14.dp, top=40.dp, bottom=12.dp`
  - Row, bottom-aligned, gap 8.dp
    - Column (flex 1):
      - `{date} · {time}` — 10sp/600 white @ 75% alpha, uppercase, letter-spacing 0.4
      - source label — 16sp/700 white, marginTop 2.dp
    - **Item stack** — 3 overlapping square chips: 32×32 rounded-9.dp, white 2.dp border, each rotated slightly (`-4°, 0°, +4°`) and marginLeft `-8.dp` for the 2nd and 3rd; first is straight. The image inside is the cutout/thumbnail (same `DriveImage.localPath` source you use elsewhere)

### Mid card (×2)
- `Surface(color = surface, border = 1.dp divider, radius = 14.dp)`
- Image area aspect 3:4 with source pill (small variant) at top-left
- Footer padding `horizontal=9.dp, top=7.dp, bottom=9.dp`:
  - Source label, 11sp/700 `onSurface`, single-line truncated
  - Date, 10sp `textMuted`, marginTop 2.dp

### Small card (×n)
- `Surface(color = surface, border = 1.dp divider, radius = 12.dp)`
- Image area aspect 3:4
- 14×14 source-color dot in top-right, 2.dp white border (so it reads on any image)
- Footer padding `horizontal=8.dp, top=6.dp, bottom=7.dp`: just the short date (e.g. "Tue"), 9sp/600 `textMuted`

### Source pill spec (shared across history + composer banner)
**Small (history cards)** — 3px 8px padding, radius 999, `surface` bg, `1.dp` `divider` border, 6×6 rounded-3.dp `sourceTint` dot + label 10sp/600 `textMid`.

**Solid (hero overlay + composer banner)** — 5px 10px padding, radius 999, `sourceTint @ 13% alpha` bg, `sourceTint @ 27% alpha` border, leading icon 12.dp + label 11sp/700, all in `sourceTint`.

### Extended FAB
- `ExtendedFloatingActionButton`, bottom-end, padding `bottom=22.dp, end=18.dp`
- 48.dp tall, padding `horizontal=18.dp`, radius 24.dp, bg `primary`, fg `onPrimary`
- Leading `Icons.Default.AutoAwesome` 16.dp + "New try-on" label 13sp/700
- Box-shadow `0 8 24` of `primary` @ 33% alpha
- Tap → opens the Quick Try-On sheet

### Interactions
- **Tap hero or any card** → opens history detail view (Screen 5d — see States section)
- **Tap edit overlay on hero** → opens composer pre-filled with that try-on's items + `sourceOutfitId`
- **Tap favorite** — optional feature; can be deferred. Visual is enough for now.
- **Pull-to-refresh** — re-runs `tryOnViewModel.loadHistory()`
- **Empty state** — see State 1 below

---

## Screen 5 — States

All five render inside the same dialog/screen the existing `TryOnComposerScreen` uses (full-screen `Dialog` with `decorFitsSystemWindows = false`, see `TryOnScreen.kt`).

### 5a · Empty (no try-ons yet)
Shown when `TryOnUiState.history.isEmpty()` and the user is on the history screen.

- Full-page centered layout, padding `horizontal=28.dp`, text-align center
- **Illustration tile**: 170×200, radius 18.dp, dashed border 1.dp `border`, bg `surface`. Inside: a stylized figure poster (placeholder — use a Compose `Canvas` or any neutral illustration), overlaid with a 50% white scrim and a centered 64×64 `primary` circle with `Icons.Default.AutoAwesome` 28.dp. Box-shadow `0 8 24` of `primary` @ 33% alpha.
- **Title**: "No try-ons yet", 18sp/700 `onSurface`, marginBottom 6.dp
- **Body**: "Generate a try-on from an outfit, your wardrobe, or a shopping item — we'll save it here so you can revisit anytime.", 13sp `textMuted`, line-height 1.45, max-width 260.dp, marginBottom 18.dp
- **CTA**: "Start a try-on" — primary button, 46.dp tall, padding `horizontal=22.dp`, radius 23.dp, leading sparkle 14.dp, label 13sp/700. Tap → opens Quick Try-On sheet.
- **Source chips**: 3 inline chips below the CTA — "From an outfit", "From wardrobe", "From shopping". 5px 10px padding, radius 999, `surface` bg, `1.dp` `divider`, 10sp/600 `textMid`, gap 6.dp. Marginal — purely informational, not interactive.

### 5b · No reference photos
Shown when the user enters the composer but `ProfileViewModel.tryOnFiles()` returns empty.

Hard requirement to upload at least front + side photos before generation works. The dialog presents that requirement inline instead of routing the user away.

- Hero card: `aiGrad` background (`linear-gradient(135deg, primarySoft → #D6E8C8)`), 1.dp `border`, radius 18.dp, padding `horizontal=16.dp, vertical=18.dp`, marginBottom 16.dp
  - Eyebrow: person icon 16.dp + "Reference photos needed" 11sp/700 `primary` uppercase letter-spacing 0.5
  - Title: "Add a few photos of yourself so Gemini can place outfits onto your body.", 16sp/700 `onSurface`, line-height 1.3
  - Body: "Front, side, and back — full-body, plain background works best. Photos stay in your own Drive.", 12sp `textMid`, line-height 1.5
- Section label: "UPLOAD 3 REFERENCE SHOTS", 12sp/700 `textMid` uppercase letter-spacing 0.4
- Three upload slots, row, gap 8.dp, aspect 3:4 each, flex 1:
  - Radius 14.dp, dashed `1.5.dp` `border`, `surface` bg, centered:
    - `Icons.Default.Add` 22.dp
    - Label (Front / Side / Back) 11sp/700
    - Optional sub-label "Optional" 9sp `textMuted` (Back only)
- Tip card: `surface` bg, `1.dp` `divider`, radius 14.dp, padding 12.dp. Sparkle icon tile + "Wear simple, fitted clothing in your reference photos. Patterns and bulky layers reduce accuracy.", 11sp `textMid` line-height 1.5.
- Sticky bottom: secondary button "Open Settings → Profile", 50.dp tall, radius 25.dp, `surface` bg with `1.5.dp` `border`. Tap → existing Settings → Profile route.

### 5c · Generating
Existing `AiProcessingOverlay` already covers this. The design refresh just changes the dialog *inside* the overlay:

- 300.dp max-width card, padding `horizontal=22.dp, top=22.dp, bottom=24.dp`, radius 22.dp, `surface` bg, `1.dp` `divider`, box-shadow `0 12 40` rgba(40,60,30,18%)
- Spinner ring: 64×64. Outer ring 3.dp `primarySoft`. Inner ring 3.dp transparent except top + right are `primary`, rotating 360° in 1s linear infinite. Centered sparkle 24.dp `primary` inside.
- Title: "Generating your try-on", 15sp/700, marginBottom 4.dp
- Body: "Composing {n} items onto your reference photos. Usually 20–40 seconds.", 12sp `textMuted` line-height 1.5, marginBottom 14.dp
- **Progress steps**: 4-segment bar, each 3.dp tall, radius 2.dp, gap 4.dp. `primarySoft` track, `primary` fill. Animate fills based on actual progress if exposed (0%, 30%, 60%, 100%); otherwise indeterminate (fill all to 50% with a shimmering animation).
- "Cancel" text button below, 11sp/600 `textMuted`. Tap → `tryOnViewModel.cancelGeneration()` (add if missing).

### 5d · Result / Preview
Replaces today's `TryOnResultContent`. Show the generated image with cleaner chrome.

- Header: back icon · "Your try-on" (15sp/700) · share icon (right) — share writes to gallery via existing `MediaStore` plumbing
- Body, vertical scroll, padding `horizontal=14.dp, bottom=140.dp`:
  - Image area: aspect 4:5, radius 22.dp, border 1.dp `divider`. The `ZoomableImage` (existing) goes here. Black background underneath (`Modifier.background(Color.Black)`) shows through padding-style.
  - Top-left overlay: source pill (solid variant) with the source label
  - Bottom-right overlay: hint pill "Pinch to zoom", 5px 9px padding, radius 10.dp, `white @ 85% alpha` bg with backdrop blur, 10sp/600 `textMid`
  - Section: "ITEMS WORN" eyebrow 11sp/700 `textMid` uppercase, marginBottom 8.dp
  - Item strip: Row, gap 8.dp. Each item is `flex: 1, aspect: 1:1`, radius 11.dp, `1.dp` `divider`. Uses the same cutout `AsyncImage` you already render in `TryOnDetailContent`.
- Sticky bottom action group, padding `horizontal=14.dp, top=10.dp, bottom=18.dp + navBar`, `bg @ 90% + blur`, top divider 1.dp:
  - **Top row** — gap 8.dp, marginBottom 8.dp
    - Save (filled, flex 1, 46.dp, radius 23.dp, leading check 14.dp, label 13sp/700, primary)
    - Try again (outlined, flex 1, 46.dp, leading refresh 14.dp)
  - **Bottom row** — full-width text button "Change items", 38.dp tall, 12sp/600 `textMid`, leading edit 12.dp

Save state machine (existing): idle → saving (spinner + "Saving…") → saved (check + "Saved" disabled).

### 5e · Past try-on detail
Reached from any history card. Replaces today's `TryOnDetailContent`.

- Header: back · "Try-on details" · delete icon (right, tint `error`)
- Body padding `horizontal=14.dp, bottom=24.dp`:
  - **Image card**: aspect 4:5, radius 22.dp, 1.dp `divider`, marginBottom 14.dp. Source pill solid at top-left.
  - **Metadata card**: `surface` bg, `1.dp` `divider`, radius 14.dp, padding 12.dp, marginBottom 14.dp
    - Row 1: two eyebrows side-by-side — "GENERATED" left, "SOURCE" right — both 10sp/600 `textMuted` uppercase letter-spacing 0.4
    - Row 2: date+time on left (13sp/700) and a text-button "View {sourceType}" with trailing chevron on right (12sp/700 `primary`). Tap → navigates: outfit → outfit detail; trip → trip detail; wardrobe → wardrobe with those items selected; shopping → shopping list.
  - **Items worn** heading: "Items worn ({n})", 13sp/700, marginBottom 8.dp
  - **Item rows**: Column, gap 8.dp, marginBottom 18.dp
    - Each row: `surface` bg, `1.dp` `divider`, radius 12.dp, padding 8.dp, Row gap 10.dp center-aligned
    - 44×44 image, radius 9.dp, 1.dp `divider`
    - Column (flex 1): name 13sp/700, "Tap to view in wardrobe" hint 10sp `textMuted` marginTop 2.dp
    - Trailing chevron 14.dp `textMuted`
    - Missing items use the existing `tryon_history_items_missing` treatment (gray out, italic note)
  - **Action row**: Row, gap 8.dp, two equal-flex outlined buttons 44.dp tall, radius 22.dp, label 12sp/700
    - **Regenerate** (refresh icon) → calls `tryOnViewModel.openComposer(tryOn.itemIds, tryOn.sourceOutfitId)`
    - **Save to gallery** (send icon) → existing `saveToGallery()` path

### Delete confirmation
Reuse the existing `AlertDialog` driven by `tryon_delete_confirm_title` / `tryon_delete_confirm_text`. No design change.

---

## Interactions & Behavior

### Navigation graph (additions)
- `MainActivity.kt` `NavigationBar`:
  - Remove the **Insights** tab.
  - Insert a center "fake" slot containing the raised AI button, sized to `60.dp` so it doesn't compete for layout space with other tabs.
  - On each main destination's app bar, add a trailing `IconButton(TrendingUp)` that navigates to the Insights route.
- New nav action: `openQuickTryOnSheet()` — opens the `ModalBottomSheet`, currentTab passed for analytics. The sheet is rendered at the `MainActivity` level so it sits above the current destination.
- The center button never navigates by itself; it always opens the sheet. The sheet then drives navigation into the existing `TryOnComposerScreen` dialog (or the wardrobe/shopping selection mode flow).

### Animations
- Raised AI button entry (on first launch only): 280ms scale-in from 0.8 + fade. Subsequent appearances are instant.
- ModalBottomSheet: default M3 slide-up.
- Hero card item-stack rotation: static (no animation needed). On entering Result screen → history transition, optionally animate the just-saved item into the hero slot (`AnimatedContent` swap from result → hero).
- Empty state CTA: subtle 1.5-second pulse on the inner sparkle circle (scale 1.0 → 1.04). Use `infiniteRepeatable` with `Easing.InOutSine`.
- Generating spinner: 1s linear infinite (already in spec above).

### Touch targets
- Raised AI button: 56.dp target hit area, but extend the tap region to ~72.dp via a transparent `Box(modifier = Modifier.size(72.dp).clickable(...))` wrapping it. Without that, the floating position makes it easy to miss on touch.
- All other targets ≥ 44.dp.

### Edge cases
- **No source can be inferred** (user opens via long-press shortcut) → composer opens with no banner; banner block is hidden, items grid still works.
- **Travel option in Quick sheet** with no active trip → hide the row entirely (don't show disabled).
- **History empty** → show State 5a instead of the hero feed.
- **Result then back gesture** before saving → confirm "Discard try-on?" with existing dialog.

---

## State Management

Existing `TryOnViewModel` already covers the create + history + detail flows. Additions:

```kotlin
data class TryOnUiState(
    // existing fields ...
    val sourceKind: TryOnSourceKind = TryOnSourceKind.NONE,  // NEW
    val sourceContext: String? = null,                       // NEW — display label
)

enum class TryOnSourceKind { NONE, OUTFIT, WARDROBE, SHOPPING, TRAVEL }
```

When the QuickTryOnSheet routes:
- `outfit` chosen + outfit picked → `tryOnViewModel.openComposer(outfit.itemIds, outfit.id)`, set `sourceKind = OUTFIT`, `sourceContext = outfit.name`
- `wardrobe` chosen + items selected → `openComposer(selectedIds, null)`, `sourceKind = WARDROBE`, `sourceContext = "{n} selected"`
- `shopping` chosen + items selected → `openComposer(selectedIds, null)`, `sourceKind = SHOPPING`, `sourceContext = "Shopping · {firstItemName}"`
- `travel` → `sourceKind = TRAVEL`, `sourceContext = "{tripName} · Day {n}"`

`TryOn` data model already persists `sourceOutfitId` — extend with:
```kotlin
data class TryOn(
    // existing ...
    val sourceKind: String = "outfit", // serialized as String for forward-compat
    val sourceContext: String = "",
)
```
This survives Drive round-trips because the history JSON lives in `_tryons.json`. Migrating: when loading old entries with no `sourceKind`, infer from existence of `sourceOutfitId` (outfit if present, else "wardrobe").

### Insights re-route
- Remove `Calendar` was already done; remove `Insights` from `MainActivity` nav graph's `NavigationBar` items list.
- Add an `IconButton(TrendingUp)` to each `AppHeader` (Outfits / Wardrobe / Shopping / Travel) — they all use the same header composable already.
- Insights route URL stays the same; only its entry point moves.

---

## Design Tokens

Tokens come from the existing `green-light` `colorScheme` (see `design_handoff_librelookai_wardrobe/README.md` for the full table). Additions specific to Try-On:

| Token | Value | Use |
|---|---|---|
| `aiAccent` | `#7BBD6C` | Center button gradient stop, sparkle icon |
| `aiGrad` | `linear-gradient(135deg, primarySoft → #D6E8C8)` | No-photos hero, Quick sheet container highlight |
| `aiGradStrong` | `linear-gradient(135deg, primary → aiAccent)` | Center button background; header pill |

### Source tints

| Source | Tint |
|---|---|
| outfit | `#7BBD6C` |
| wardrobe | `#A8C09C` |
| shopping | `#C9A65A` |
| travel | `#8AB8D8` |

Wrap them in a `SourceColors` object alongside `MaterialTheme.colorScheme` so they survive theme changes (provide a different palette in dark / sand if needed).

### Spacing
4 / 6 / 8 / 10 / 12 / 14 / 16 / 18 / 22 / 24.dp

### Radii
- Card / surface: 14.dp
- Hero card: 22.dp
- Image inside card: 12.dp (small) / 14.dp (mid) / 18.dp (large) / 22.dp (hero)
- Pill / chip / button: 999.dp (full)
- Spinner / circle: 50% (full)

### Typography (Plus Jakarta Sans in prototype → existing app font)

| Use | Size | Weight |
|---|---|---|
| Page title (header) | 15sp | 700 |
| Section title | 13sp | 700 |
| Hero overlay title | 16sp | 700 |
| Big numeric / hero count | 18sp | 800 |
| Body | 13sp | 500 |
| Sub / hint | 12sp | 400–500 |
| Caption | 10–11sp | 600 |
| Eyebrow / uppercase label | 9–10sp | 700, uppercase, letter-spacing 0.4–0.6 |

### Shadows
- Raised AI button: `0 10 24` of `primary` @ 40% alpha + 5.dp `background`-colored ring
- Extended FAB: `0 8 24` of `primary` @ 33%
- Generating card: `0 12 40` rgba(40,60,30,18%)
- Cards: none — border only

---

## Icons (Compose mapping)

| Prototype | Compose |
|---|---|
| `ai` (sparkle) | `Icons.Default.AutoAwesome` |
| `back` | `Icons.Default.ArrowBack` (or right chevron used as forward) |
| `close` | `Icons.Default.Close` |
| `check` | `Icons.Default.Check` |
| `add` | `Icons.Default.Add` |
| `edit` | `Icons.Default.Edit` |
| `refresh` | `Icons.Default.Refresh` |
| `send` | `Icons.Default.Share` (for save-to-gallery) |
| `trend` | `Icons.Default.TrendingUp` (Insights header icon) |
| `tune` | `Icons.Default.Tune` |
| `place` | `Icons.Default.Place` |
| `flight` | `Icons.Default.FlightTakeoff` |
| `bag` | `Icons.Default.ShoppingBag` |
| `shirt` | `Icons.Default.Style` |
| `person` | `Icons.Default.Person` |
| `heart` | `Icons.Default.Favorite` |
| `cal` | `Icons.Default.CalendarMonth` |
| `step` | `Icons.Default.Sort` (menu / list-options) |

---

## Strings

All visible strings should be Compose `stringResource(...)` lookups. Most already exist in `values/strings.xml`; new ones to add:

| Key | English |
|---|---|
| `tryon_quick_sheet_title` | Quick try-on |
| `tryon_quick_sheet_subtitle` | What should we put on you? |
| `tryon_quick_source_outfit` | A saved outfit |
| `tryon_quick_source_outfit_sub` | Pick from your library |
| `tryon_quick_source_wardrobe` | Pick items |
| `tryon_quick_source_wardrobe_sub` | Mix & match from your wardrobe |
| `tryon_quick_source_shopping` | Something to buy |
| `tryon_quick_source_shopping_sub` | From your shopping list |
| `tryon_quick_source_travel` | Outfit from a trip |
| `tryon_quick_source_travel_sub` | From %1$s · Day %2$d |
| `tryon_quick_see_history` | See past try-ons |
| `tryon_nav_label` | Try on |
| `tryon_history_earlier` | Earlier |
| `tryon_history_more_count` | %1$d more |
| `tryon_source_label_outfit` | From outfit |
| `tryon_source_label_wardrobe` | From wardrobe |
| `tryon_source_label_shopping` | From shopping |
| `tryon_source_label_travel` | From travel |
| `tryon_source_swap` | Swap |
| `tryon_empty_title` | No try-ons yet |
| `tryon_empty_body` | Generate a try-on from an outfit, your wardrobe, or a shopping item — we'll save it here so you can revisit anytime. |
| `tryon_empty_cta` | Start a try-on |
| `tryon_generating_title` | Generating your try-on |
| `tryon_generating_body` | Composing %1$d items onto your reference photos. Usually 20–40 seconds. |
| `tryon_generating_cancel` | Cancel |
| `tryon_result_title` | Your try-on |
| `tryon_result_zoom_hint` | Pinch to zoom |
| `tryon_result_items_worn` | Items worn |
| `tryon_detail_view_source_outfit` | View outfit |
| `tryon_detail_view_source_trip` | View trip |
| `tryon_detail_view_source_wardrobe` | View in wardrobe |
| `tryon_detail_view_source_shopping` | View shopping list |
| `tryon_detail_regenerate` | Regenerate |
| `tryon_detail_save_to_gallery` | Save to gallery |
| `tryon_detail_item_tap_hint` | Tap to view in wardrobe |
| `tryon_photos_eyebrow` | Reference photos needed |
| `tryon_photos_title` | Add a few photos of yourself so Gemini can place outfits onto your body. |
| `tryon_photos_body` | Front, side, and back — full-body, plain background works best. Photos stay in your own Drive. |
| `tryon_photos_section_label` | Upload 3 reference shots |
| `tryon_photos_optional` | Optional |
| `tryon_photos_tip` | **Tip:** wear simple, fitted clothing in your reference photos. Patterns and bulky layers reduce accuracy. |
| `tryon_photos_open_settings` | Open Settings → Profile |
| `nav_insights_header_icon` | Insights |

Per `CLAUDE.md`, **mirror every new string in `values-de/strings.xml`** and update `TRANSLATION.md`. Other locale files exist in the repo (fil, ro, zh-rTW, it, cs); if your tooling auto-translates, run that pass too.

---

## Analytics

Add these `Analytics.action(...)` events. Keep the existing `TryOn/Composer`, `TryOn/Result`, `TryOn/Detail` namespaces.

| Event | Properties |
|---|---|
| `TryOn` · `nav_button_tap` | `{ from: <currentTab> }` |
| `TryOn/QuickSheet` · `shown` | `{}` |
| `TryOn/QuickSheet` · `pick_source` | `{ source: outfit\|wardrobe\|shopping\|travel\|history }` |
| `TryOn/QuickSheet` · `dismiss` | `{}` |
| `TryOn/Composer` · `source_swap` | `{ from: <source>, to: <source> }` |
| `TryOn/History` · `open_hero` | `{}` |
| `TryOn/History` · `open_card` | `{ index: Int }` |
| `TryOn/History` · `open_quick_sheet_from_fab` | `{}` |
| `TryOn/Empty` · `cta_tap` | `{}` |

---

## Files in this bundle

| File | Purpose |
|---|---|
| `Try-On Preview.html` | Pannable design canvas with all screens — open in any browser |
| `tryon-nav.jsx` | Entry-point components: `NavOptionCenter` (bottom nav variants by tab), `QuickTryOnSheet`, `BottomNavItem`, `FrontPageMock` |
| `tryon-history-v2.jsx` | Hero feed history: `TryOnHistoryV2`, `MidCard`, `SmallCard` |
| `tryon-states.jsx` | `TryOnEmpty`, `TryOnNoPhotos`, `TryOnGenerating`, `TryOnResult`, `TryOnDetail` |
| `tryon-create-v1.jsx` | The composer screen (Option A's destination after the Quick sheet) — sectioned layout with source banner |
| `tryon-shared.jsx` | Shared helpers: `TRYON_HISTORY` (sample data), `SOURCE_META`, `SourcePill`, `TryOnPoster`, `GarmentTile`, `TryOnHeader`, `ENTRY_PRESETS` |
| `ai-shared.jsx` | Cross-feature primitives reused from earlier handoffs: `PhoneShellAI`, `AIco` icons, `AIThumb`, `AIChip`, `AI_THEMES` palette |
| `design-canvas.jsx` | The pannable canvas component used by the preview — pure scaffold, not part of the app |

---

## What to do

1. Open `Try-On Preview.html` in a browser. Pan and zoom to study each screen. Toggle the variants on the canvas if you want to see context.
2. **Insights re-route first** (smallest change, highest IA impact):
   - Remove Insights from `MainActivity.kt`'s `NavigationBar` items list.
   - Add a `TrendingUp` IconButton to the shared `AppHeader` composable (used across Outfits / Wardrobe / Shopping / Travel) that navigates to `Insights`.
3. **Bottom nav with center button**:
   - Modify `MainActivity.kt` to render a custom `Row` instead of stock `NavigationBar` (Material's NavigationBar doesn't gracefully support a floating center slot). Each tab is a `NavigationBarItem`-shaped composable you write; the center is a separate `Box` positioned with `Modifier.offset(y = (-22).dp)`.
   - Wire the center button to open the new `QuickTryOnSheet` (`ModalBottomSheet`).
4. **QuickTryOnSheet**: new composable in `com.librelookai.tryon`. Renders the 4 source rows + history shortcut. Each source row triggers the corresponding existing flow (`OutfitPickerDialog` already exists; wardrobe and shopping selection-modes already exist — wire them to call `tryOnViewModel.openComposer(...)` on confirm).
5. **Composer source banner**: add to `TryOnComposerContent` in `TryOnScreen.kt`. Read `state.sourceKind` + `state.sourceContext`; hide the banner when `sourceKind == NONE`.
6. **History v2 (hero feed)**: replace `TryOnHistoryGrid` (in `TryOnScreen.kt`). New file `TryOnHistoryScreen.kt` for clarity. Reuse the existing `state.history` flow.
7. **States**: replace `TryOnResultContent` and `TryOnDetailContent` with the new layouts. The empty state lives inside `TryOnHistoryGrid`'s "history empty" branch. The no-photos state is a new screen shown before `TryOnComposerContent` when `profileViewModel.tryOnFiles().isEmpty()`. The generating overlay reuses `AiProcessingOverlay` but updates its inner card.
8. **Strings & translations** — add the new keys to `values/strings.xml`, then mirror them in `values-de/strings.xml` (and other locales if your pipeline supports it).
9. **Analytics** — add the new event calls listed above.
10. **Verify dark theme** — every spec uses semantic tokens; if `green-dark` is wired through `colorScheme`, it should just work. Spot-check the hero overlay text legibility on dark.

## Out of scope

- The other 2 history variants (Polaroid grid, Source timeline) — explored on the main canvas, not selected.
- The other 2 composer variants (Poster preview, Layered stack) — same.
- The entry-point alternatives (5th tab, header pill) — same.
- Multi-photo source uploads (using more than 3 reference shots) — defer.
- Background-removal during reference-photo upload — out of scope; the existing photo pipeline is fine.
- Favorite/heart action on history hero — visual only, can ship as a no-op or skip entirely.
- Sharing to social — `Save to gallery` is enough.
