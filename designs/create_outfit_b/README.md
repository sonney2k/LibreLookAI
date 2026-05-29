# Handoff: Create Outfit screen — Variant B (Layered look board)

## Overview

The **Create Outfit** screen lets a user build a complete outfit from items in their wardrobe. This handoff covers **Variant B — the "layered look board"** layout, which presents one row per garment layer (Outerwear / Top / Bottom / Footwear / Accessory) with a horizontal filmstrip of alternatives in each row. AI fills missing slots from a goal/occasion. Existing screen being replaced: `OutfitComposerScreen.kt` (Jetpack Compose).

## About the Design Files

The HTML/JSX in this bundle is a **design reference** — a working prototype showing layout, behavior, and visual treatment. It is **not** intended to be shipped as-is. The task is to **recreate this design in the existing Android/Compose codebase** (`com.librelookai`) using the existing Material 3 theme, Coil image loading, and existing ViewModel state (`OutfitsViewModel.composer*` fields). Reuse `ComposerTargets`, `ComposerWeatherMode`, `composerVibes`, `composerItemIds`, etc. — no data-model changes needed for the visual rebuild.

Open `Variant B Preview.html` in a browser to see the three seed states side-by-side (blank / pre-seeded / AI-generated).

## Fidelity

**High-fidelity.** Colors, typography, spacing, radii, and chip shapes are final. Match them in Compose.

## Entry modes (all use the same screen)

The screen handles three seeds, distinguished only by which slots are pre-filled:

1. **Blank** — opened from Outfits FAB. All 5 slots empty.
2. **Pre-seeded** — opened from a Wardrobe selection with N items. Each item lands in its layer row; remaining slots empty.
3. **AI suggestion** — user typed an occasion and tapped "Fill missing"; every layer pre-populated.

User can always swap, remove, or hit "Fill missing" to let AI complete empty slots.

## Layout

Phone-frame mock is `390 × 844` (logical iPhone 13). On Android: target the full content area inside `Scaffold`, respect status-bar + nav-bar insets via `LocalSystemBarsPadding` (see `CLAUDE.md` — *Compose Dialog Quirks*).

Vertical stack, top → bottom:

| Section | Height | Notes |
|---|---|---|
| Status bar | 44 | System; do not draw |
| App header | ~56 | Close (left) + title "Build your look" + subtitle "N of 5 layers · tap to swap" |
| Goal pill | ~52 | AI-gradient bg, occasion text + "Fill missing" button right-side |
| Context strip | ~36 | Horizontal scroll: weather chip, closet chip, vibe chips |
| Layer rows | 5× ~140 | Outerwear, Top, Bottom, Footwear, Accessory — scrollable |
| Advanced row | ~44 | Dashed pill, opens secondary sheet |
| Bottom bar | ~76 | Name field + Save button — sticky, with blur backdrop |

All horizontal padding is `16dp`. Vertical gap between rows: `8dp`.

## Components

### App header

- `Row`, `padding(start=8.dp, end=8.dp, top=6.dp, bottom=10.dp)`
- IconButton(close, 40dp): `Icons.Default.Close`, tint `colorScheme.onSurface`
- Column (flex 1):
  - Title: `titleLarge`, `fontWeight=Bold`, 18sp
  - Subtitle: `bodySmall`, color `textMuted` (#6A8060 light / #728060 dark), 11sp

### Goal pill (occasion strip)

- Background: linear gradient 135° from `#E4EFDD` to `#D6E8C8` (light) / `#1E2E1A → #243D1F` (dark)
- `RoundedCornerShape(16.dp)`, `1.dp` border `colorScheme.primary` at 33% alpha
- Padding `horizontal=12.dp, vertical=10.dp`
- Left: Material `AutoAwesome` icon, 16dp, tint `primary` (#4E7844)
- Middle: occasion text, `bodyMedium` 13sp, `fontWeight=600` when filled / `400` placeholder, single line ellipsis
- Right: filled button "Fill missing" with `AutoAwesome` 11dp icon, fontSize 11sp/700, background `primary`, foreground `onPrimary`, radius `14.dp`, padding `horizontal=10.dp, vertical=6.dp`

Behavior: tap the strip opens an inline edit affordance (existing pattern from current composer's `composerFeedback` field). Tap "Fill missing" calls `enhanceComposerWithAi(...)`.

### Context strip

- `Row` horizontal scroll, gap `6.dp`
- First chip: weather — `Chip(active=true)` with sun icon 12dp + "18° Sunny"
- Second chip: closet — place icon 12dp + closet name
- Then up to 5 vibe chips from `VIBES = ["Casual","Business","Formal","Streetwear","Minimalist","Sporty","Elegant","Classic"]` — single-select for this prototype, but the existing model `composerVibes: Set<String>` is multi-select — keep multi.

Chip spec: see Design Tokens below.

### Layer row (×5)

The defining component. One per layer in order: **Outerwear, Top, Bottom, Footwear, Accessory**.

- Background `colorScheme.surface` (#EBF1E5), border `1.dp` `divider` (#D4E0CC), radius `16.dp`, padding `10.dp`
- Header row:
  - 24×24 rounded-6 square with layer icon. Background `primarySoft` (#E4EFDD) when filled, `colorScheme.background` when empty; icon tint `primary` filled / `textMuted` empty.
  - Layer name `labelLarge` 12sp/700
  - Right side, filled state: item label (11sp, `textMuted`) + 22×22 close button (#bg circle, close icon)
  - Right side, empty state: "EMPTY" or "OPTIONAL" badge (10sp/600/uppercase, letter-spacing 0.3, `textMuted`)
- Filmstrip row below:
  - Horizontal scroll, gap `6.dp`
  - Current item first (if any): 72×72 tile, radius `10.dp`, **2dp outline in primary, inset -1**, with a 18×18 primary-circle check badge top-right
  - Alternatives: 72×72 tiles, radius `10.dp`, 1dp border `divider`
  - "See all" tile at end: 72×72 dashed border `border`, background `colorScheme.background`, `MoreHoriz` icon 16dp + "All N" 9sp/700 below

Tap an alternative → swap (replaces current). Tap current's `×` → empty the slot.

### Garment thumbnail (placeholder in prototype)

Real implementation uses `AsyncImage` (Coil) with `ImageRequest.Builder(ctx).data(image.localPath).memoryCacheKey("${driveId}_${version}")`. The prototype renders a diagonal-stripe placeholder on a tinted background — replace with the real Coil tile from `OutfitComposerScreen.ItemsGrid`.

### Advanced row

- Dashed border, `1.dp dashed`, color `border`, background transparent, radius `14.dp`, padding `vertical=10.dp, horizontal=12.dp`
- Center-aligned row: `Tune` icon 14dp · "Advanced — targets, tags, description" 12sp/600 `textMid` · `ExpandMore` 14dp
- Opens existing advanced controls: `WeatherSection`, `TargetsSection` (ComposerTargets steppers), `OutfitTagsEditor`, description text field. Reuse those composables as-is in a bottom sheet or expanded section.

### Bottom bar (sticky)

- Position: bottom of screen, padding `start=16.dp end=16.dp top=10.dp bottom=18.dp` (plus nav bar insets)
- Background `colorScheme.background` at 90% alpha + blur if available, else solid; top border `1.dp` `divider`
- Left (flex 1): Name input — `OutlinedTextField` styled as a filled row, height 48dp, radius `14.dp`, `Edit` icon 14dp prefix, placeholder "Name (optional)"
- Right: filled button "Save" — height 48dp, padding `horizontal=18.dp`, radius `24.dp`, `Check` icon 16dp, fontSize 13sp/700. Disabled (alpha 0.4) when no items picked. Shadow: 0 6 18 of `primary` at 33% alpha.

## Interactions & Behavior

- **Tap filmstrip alternative** → swap with `setLayer(layer, id)` — updates `composerItemIds` so the new item is in the list and the previous layer-mate is out.
- **Tap × on current** → remove from `composerItemIds`.
- **Tap "See all"** → opens existing `AddItemSheet` filtered to that layer.
- **Tap "Fill missing"** → `OutfitsViewModel.enhanceComposerWithAi(...)`; only fills empty slots. Show existing `AiProcessingOverlay` while `isComposerEnhancing`.
- **Tap goal strip** → focuses inline text field for `composerFeedback`.
- **Tap a vibe chip** → `toggleComposerVibe(value)`.
- **Save** → `saveComposer()`; disabled when `composerItemIds.isEmpty()`.
- **Close (X)** → `closeComposer()` with confirmation if dirty (existing behavior).

### Animations

- Chip selection: 140ms ease — color + border transition.
- Filmstrip swap: cross-fade the current tile (200ms) when an alternative is tapped.
- Layer-row "fill" via AI: slide-in 250ms ease-out from leading edge.

## State

Reuse `OutfitsViewModel.OutfitComposerUiState` as-is. The relevant fields:

| Field | Type | Use |
|---|---|---|
| `composerItemIds` | `List<String>` | Items in the outfit. Group by layer via `DriveImage.category` / tag normalizer. |
| `composerFeedback` | `String` | Goal/occasion text shown in the AI strip. |
| `composerVibes` | `Set<String>` | Active vibe chips. |
| `composerWeatherMode` | `ComposerWeatherMode` | Auto vs manual; shown as weather chip. |
| `composerManualTempC` | `Int?` | Override temp; shown in chip when manual. |
| `composerSourceFolderIds` | `Set<String>` | Closet chip(s). |
| `composerTargets` | `ComposerTargets` | Per-layer stepper values, in advanced sheet. |
| `composerTags`, `composerDescription`, `composerName` | text | Advanced + name field. |
| `isComposerEnhancing` | `Boolean` | Show `AiProcessingOverlay`. |
| `composerReason`, `composerError` | text | Show post-AI banner (similar to current). |

**Grouping items into layer rows:** use existing tag normalization (`TagNormalizer.kt` / `WardrobeFiltersShared.kt`) — `category == "outerwear" | "tops" | "bottoms" | "footwear" | "accessories"`. Map `tops → top`, `bottoms → bottom`, `accessories → accessory`.

## Design Tokens

Use existing Material 3 `colorScheme`. The prototype's `green-light` palette maps to:

| Token | Light | Dark |
|---|---|---|
| `bg` / `background` | `#F3F6EF` | `#171F15` |
| `surface` | `#EBF1E5` | `#1F2A1C` |
| `surface2` | `#E0EAD8` | `#273323` |
| `primary` | `#4E7844` | `#7BBD6C` |
| `primaryDim` | `#D0E4C8` | `#243D1F` |
| `primarySoft` (AI gradient base) | `#E4EFDD` | `#1E2E1A` |
| `text` / `onSurface` | `#1A2618` | `#DEE9D8` |
| `textMid` | `#3D5438` | `#A8C09C` |
| `textMuted` | `#6A8060` | `#728060` |
| `border` | `#BDD0B2` | `#304028` |
| `divider` | `#D4E0CC` | `#283820` |
| `chipBg` | `#DFE9D8` | `#253020` |
| `chipFg` | `#2E4A28` | `#9ABF8A` |
| `fabFg` / `onPrimary` | `#FFFFFF` | `#0F1E0C` |
| `error` | `#C0392B` | `#E57373` |

AI gradient (goal pill background): `linear-gradient(135°, primarySoft → primaryDim)` — light: `#E4EFDD → #D0E4C8` (uses `#D6E8C8` in prototype, either reads fine).

### Spacing

- Screen padding: `16.dp` horizontal
- Section gap: `8.dp`
- Chip gap: `6.dp`
- Tile inside filmstrip: `6.dp`
- Component internal padding: `10–12.dp`

### Radii

- Card / row: `16.dp`
- Goal pill: `16.dp`
- Filmstrip tile: `10.dp`
- Layer-icon square: `6.dp`
- Chip: `999.dp` (full pill)
- Button (Save): `24.dp`
- Input field: `14.dp`

### Typography (Plus Jakarta Sans in prototype → use existing app font, currently system default)

| Use | Size | Weight | Notes |
|---|---|---|---|
| Title | 18sp | 700 | `titleLarge` |
| Section eyebrow | 10sp | 700 | uppercase, letter-spacing 0.4 |
| Body / item label | 11–13sp | 500–600 | |
| Chip / button text | 11–13sp | 600–700 | |
| Reason / why-this | 12.5sp | 400 | line-height 1.5 |

### Chip spec

- Padding: `horizontal=12.dp, vertical=6.dp` (default) / `horizontal=9.dp, vertical=3.dp` (small)
- Radius `999.dp`, gap `4.dp` for icon+label
- **Active**: bg `primary`, fg `onPrimary`, border `1.5.dp primary`
- **Inactive**: bg `chipBg`, fg `chipFg`, border `1.dp border`
- **Filter-state (counted)**: bg `primaryDim`, fg `primary`, border `1.5.dp primary`

### Shadows

- Save button: `0dp 6dp 18dp` `primary @ 33% alpha`
- Bottom bar: top `1.dp` divider line, optionally blur backdrop

## Icons

All Material icons (`androidx.compose.material.icons.filled`) — exact map from prototype:

| Prototype | Compose |
|---|---|
| `ai` (sparkle) | `Icons.Default.AutoAwesome` |
| `close` | `Icons.Default.Close` |
| `check` | `Icons.Default.Check` |
| `tune` | `Icons.Default.Tune` |
| `add` | `Icons.Default.Add` |
| `down` / `up` | `Icons.Default.ExpandMore` / `ExpandLess` |
| `place` | `Icons.Default.Place` |
| `sun` | `Icons.Default.WbSunny` |
| `more` | `Icons.Default.MoreHoriz` |
| `edit` | `Icons.Default.Edit` |
| `shirt`, `pants`, `shoe`, `jacket`, `bag` (layer icons) | custom — use existing app illustrations if any, else `Icons.Outlined.Checkroom` for top/jacket/shoe/bag/pants placeholders. The category icons are nice-to-have, not essential. |

## Assets

No new image assets. All garment tiles are real photos via `AsyncImage` / Coil from the existing wardrobe pipeline.

## Files in this bundle

- `Variant B Preview.html` — open in any browser to see the design in three seed states
- `compose-variant-b.jsx` — React component implementing the design (reference only)
- `compose-shared.jsx` — shared tokens, icon paths, sample data, phone shell
- `README.md` — this document

## What to do

1. Open `Variant B Preview.html` to study the visual.
2. Cross-reference with `LibreLookAI/app/src/main/java/com/librelookai/OutfitComposerScreen.kt` — the file you're replacing. Keep its outer `Dialog` + `Surface` scaffolding (and the inset handling in *Compose Dialog Quirks*).
3. Replace the scroll-column body with the new structure: goal pill → context strip → 5 layer rows → advanced row.
4. Reuse `AddItemSheet`, `WeatherSection`, `TargetsSection`, `OutfitTagsEditor`, `AiProcessingOverlay`, all existing ViewModel methods. No new data flow.
5. Group `composerItemIds` by layer for display. When swapping in a new alt, remove old layer-mate and add new id.
6. Verify dark theme (`green-dark`) renders correctly — the prototype includes the palette but only ships green-light in the preview.

## Out of scope

- The advanced sheet contents (already-shipped components — no redesign).
- AI prompt construction in `OutfitsViewModel` (already in place via `enhanceComposerWithAi`).
- New analytics events; reuse `Analytics.action("OutfitComposer", "…")` calls.
