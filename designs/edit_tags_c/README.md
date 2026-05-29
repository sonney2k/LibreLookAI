# Handoff: Edit Tags — Variant C (Info-card panel)

## Overview

A redesign of the wardrobe item's **Edit Tags** screen. Variant C is an iOS-Photos-style info panel: each tag category becomes a one-line summary row that expands to an inline picker on tap. The screen prioritizes scannable, at-a-glance information density with progressive disclosure for fine-grained editing. **Replaces `TagEditSheet` in `WardrobeScreen.kt`.**

## About the Design Files

The HTML/JSX in this bundle is a **design reference** — a working prototype showing layout, behavior, and visual treatment. It is **not** production code. The task is to **recreate this design in the existing Compose codebase** (`com.librelookai.wardrobe`) using the existing Material 3 theme, `ClothingTags` model, and `WardrobeViewModel.updateTags(driveId, tags)` for persistence.

Open `Variant C Preview.html` in a browser to see three theme variants side by side.

## Fidelity

**High-fidelity.** Colors, spacing, radii, typography, and interaction details are intentional. Match them in Compose.

## Screen / View

### Name
**Edit Tags** — single screen, full-window dialog or fullscreen route (current code uses `ModalBottomSheet`; switch to `Dialog` with `usePlatformDefaultWidth = false` to allow the full-height layout).

### Purpose
Let the user view all tags on an item at a glance, and edit any single category with one tap. Save is automatic per-chip; explicit "Save" button is removed.

### Layout

Frame `390 × 844` (logical). Compose target: full content area inside `Scaffold`, respect `LocalSystemBarsPadding` for status/nav-bar insets.

Vertical stack, top → bottom:

| Section | Height | Notes |
|---|---|---|
| Status bar | 44 | System |
| App header | ~48 | Back (left) · "Edit tags" title (16sp/700) · Save indicator (right) |
| Identity card | ~136 | Card 1: photo + name + type + category + AI re-tag CTA |
| Tags table | flex | Card 2: 7 rows, one per tag category. Tapping a row opens its picker drawer below it inline. |

Horizontal padding: `14.dp`. Vertical gap between the two cards: `10.dp`.

### Components

#### App header
- `Row`, `padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 6.dp)`
- IconButton (40dp): `Icons.AutoMirrored.Filled.ArrowBack`, tint `onSurface`
- Title: 15sp/700, `colorScheme.onSurface`, `Modifier.weight(1f)`
- Save indicator: see below

#### Save indicator (autosave pill)
Right-aligned in header, swaps between three states:
- **`saved`** (default after autosave completes) — cloud-with-check icon + "Saved", fg `primary`, bg `primarySoft` (`#E4EFDD`)
- **`saving`** (active write in flight) — sync icon + "Saving…", fg `textMid`, bg `surface2`
- **`edited`** (rare; intermediate / failed write) — cloud icon + "Unsaved", fg `textMuted`, bg `surface2`

Spec: `Row(padding = horizontal=10.dp, vertical=4.dp)`, radius `999.dp`, gap `5.dp` between icon (12dp) and label (11sp/600).

Behavior: every chip toggle / text edit:
1. Immediately optimistically mutate local UI state.
2. Set indicator to `saving`.
3. Call `WardrobeViewModel.updateTags(driveId, newTags)` (existing method).
4. On success, indicator returns to `saved` after a min display time of ~400ms (so it doesn't flicker).
5. On failure, surface a Snackbar; indicator goes to `edited` until next attempt succeeds.

Debounce text fields (name/type/category — if added — 600ms idle); chip toggles fire immediately.

#### Identity card (Card 1)

`Surface(shape = RoundedCornerShape(18.dp), color = surface, border = 1.dp divider)`, padding `12.dp`, vertical gap `10.dp`.

Inside, a `Row(gap = 12.dp)`:
- **Photo**: 96×96 box, radius `14.dp`, background `surface2`, 1dp `divider` border, clipped to corners. Use `AsyncImage` (Coil) with the existing `memoryCacheKey = "${driveId}_${version}"` pattern from `WardrobeGridShared.kt`.
- **Right column** (flex 1, vertically centered):
  - Eyebrow "NAME" — 10sp/700, `textMuted`, letter-spacing 0.4, uppercase
  - Name — 15sp/700, `onSurface`, single line ellipsis
  - Margin top `8.dp`, then a sub-row with two equal columns (Type, Category):
    - Eyebrow 9sp/700 uppercase `textMuted`, letter-spacing 0.3
    - Value 12sp/600 `onSurface`, marginTop `1.dp` (category is capitalized)

Name/Type/Category fields are tappable to open inline `OutlinedTextField` edit; on commit, autosave fires.

Below the photo row, **AI re-tag CTA**:
- `Row(padding = horizontal=12.dp, vertical=8.dp)`, radius `12.dp`
- Background: linear gradient 135° `primarySoft → primaryDim` (light) / dark equivalents
- Border `1.dp` `primary @ 33% alpha`
- Sparkle icon 13dp (`Icons.Default.AutoAwesome`) + "Re-detect tags with AI" 12sp/700 in `primary`
- Right: "2 credits" 10sp/600/0.8 alpha
- Tap → `wardrobeViewModel.classifyClothing(driveId)` (existing flow; show `AiProcessingOverlay`)

Disabled when offline (`LocalIsOffline.current`).

#### Tags table (Card 2)

Single `Surface(shape = RoundedCornerShape(18.dp), color = surface, border = 1.dp divider, clip = true)` containing 7 rows; dividers between rows are `1.dp` `divider` lines (no divider after the last row).

Rows in order:
1. Colors
2. Uses
3. Seasonality
4. Aesthetic
5. Fit
6. Material
7. Pattern

##### Row (collapsed)
- `Row(padding = horizontal=14.dp, vertical=12.dp)`, gap `10.dp`, clickable to toggle expansion.
- Label column: fixed width `88.dp`, 12sp/700, `onSurface`
- Value column (flex 1, ellipsis):
  - **Empty state** ("Not set"): 12sp italic `textMuted`
  - **Colors row**: stacked overlapping color avatars (18×18 circle, 2dp `surface` border, marginLeft `-6dp` per chip, decreasing z-index) showing up to 5 — followed by the first two named values 12sp/600 + " +N" if more. Use `COLOR_HEX` map (see Tokens) — for unknown colors fall back to `chipBg`.
  - **Other rows**: comma-joined first 3 values 12sp/600 + " +N" if more, single line ellipsis.
- Trailing: chevron icon 16dp (`Icons.Default.ExpandMore` / `ExpandLess`), tint `textMuted`.

When a row is open, its row background becomes `surface2` to visually anchor the drawer below it.

##### Row drawer (expanded)
Background `surface2`, padding `horizontal=14.dp, top=4.dp, bottom=14.dp`. Two variants:

- **Colors drawer** — a 7-column grid of 32dp swatch buttons:
  - `Column(gap = 3.dp)`: swatch on top, label 9sp/500 below.
  - Swatch: 32dp circle, background = `COLOR_HEX[value]`, border `1.dp` `border` when inactive, `2.5.dp` `primary` when active, plus a 2dp `primarySoft` ring (shadow) when active.
  - When active, overlay a check icon 14dp centered, color `#222` for white/cream, `#fff` otherwise.
  - Label color: `primary` and weight 700 when active; otherwise `textMuted` weight 500.

- **Other drawers** — `FlowRow(gap = 5.dp)` of chips:
  - Active: bg `primary`, fg `onPrimary`, 1.5dp `primary` border, 12sp/600, padding `horizontal=10.dp, vertical=5.dp`, radius `999.dp`
  - Inactive: bg `chipBg` (`#EFF4EA`), fg `chipFg` (`#2E4A28`), 1dp `border` border
  - Plus a single trailing "Add custom" dashed chip — bg transparent, fg `textMuted`, 1dp dashed `border`, leading `Add` icon 12dp. Tapping opens a tiny inline `OutlinedTextField` (existing `ChipListEditor` behavior).

#### Behavior

- **Tap a row** → toggle its drawer; closing any other open row (only one open at a time).
- **Tap a chip / swatch** → toggle in `tags[field]`; autosave.
- **Tap "Add custom"** → reveal inline field, on submit append to the field's list.
- **Back** → close screen (no confirm — autosave already persisted).
- **Offline mode** (`LocalIsOffline.current`) — disable the AI re-tag CTA (alpha 0.5, not clickable). Chip editing remains available because tag writes go to local sidecar JSON first, then Drive (existing `WardrobeViewModel.updateTags` behavior).
- **Min one row open** is **not** enforced — all rows may be collapsed for the cleanest summary view.

#### Animation

- Row expand/collapse: 220ms ease-out (Compose `animateContentSize()`).
- Chip toggle: instant; chip color crossfade 140ms.
- Save indicator state change: 200ms crossfade between icon + label.

## State

Single source of truth = `ClothingTags` (`com.librelookai.gemini.ClothingTags`). Reuse `WardrobeViewModel.updateTags(driveId, ClothingTags)` — already debounced and persisted to Drive sidecar.

```kotlin
@Composable
fun TagEditScreen(
    image: DriveImage,
    allTagCategories: List<TagCategory>,   // wardrobe-wide chip suggestions
    onUpdate: (ClothingTags) -> Unit,      // -> viewModel.updateTags
    onDismiss: () -> Unit,
) {
    var tags by rememberSaveable(image.driveId) { mutableStateOf(image.tags ?: ClothingTags()) }
    var saveState by remember { mutableStateOf(SaveState.Saved) }
    var openRow by remember { mutableStateOf<String?>("colors") }

    fun mutate(transform: (ClothingTags) -> ClothingTags) {
        tags = transform(tags)
        saveState = SaveState.Saving
        // debounce via LaunchedEffect(tags) below
    }

    LaunchedEffect(tags) {
        delay(300)            // coalesce rapid toggles
        onUpdate(tags)
        delay(400)            // min display
        saveState = SaveState.Saved
    }
    // …
}

enum class SaveState { Saved, Saving, Edited }
```

For chip lists: `tags.copy(uses = tags.uses.toggle(v))` where `toggle` is the obvious extension.

### Suggestions per row

Merge `TAG_PRESETS` (existing module-level constants in `WardrobeScreen.kt`: `PRESET_USES`, `PRESET_SEASONALITY`, etc.) with `allTagCategories.find { it.label == sectionLabel }?.tags` — same logic as the current `TagEditSheet.suggestions(...)` function. Re-use it as-is.

## Design Tokens

Use existing Material 3 `colorScheme`. The prototype's `green-light` theme maps to:

| Token | Light | Dark |
|---|---|---|
| `bg` / `background` | `#F3F6EF` | `#171F15` |
| `surface` (cards) | `#FFFFFF` | `#1F2A1C` |
| `surface2` (drawer bg, secondary) | `#EBF1E5` | `#273323` |
| `primary` | `#4E7844` | `#7BBD6C` |
| `primaryDim` | `#D0E4C8` | `#243D1F` |
| `primarySoft` (saved pill bg, AI grad start) | `#E4EFDD` | `#1E2E1A` |
| `text` / `onSurface` | `#1A2618` | `#DEE9D8` |
| `textMid` | `#3D5438` | `#A8C09C` |
| `textMuted` | `#6A8060` | `#728060` |
| `border` | `#BDD0B2` | `#304028` |
| `divider` | `#D4E0CC` | `#283820` |
| `chipBg` | `#EFF4EA` | `#253020` |
| `chipFg` | `#2E4A28` | `#9ABF8A` |
| `onPrimary` / chip-active fg | `#FFFFFF` | `#0F1E0C` |

AI gradient: 135° `primarySoft → #D6E8C8` (light) / `#1E2E1A → #243D1F` (dark).

### Color swatch hex map

Use this single map for all "tag value → swatch" rendering. Falls back to `chipBg` when a tag value isn't listed.

```kotlin
val COLOR_HEX = mapOf(
  "black" to 0xFF1A1A1A, "white" to 0xFFF5F5F0, "gray" to 0xFF9A9A95, "charcoal" to 0xFF3A3A3A,
  "beige" to 0xFFE8DCCB, "cream" to 0xFFF4E8D0, "brown" to 0xFF7A5030, "tan" to 0xFFC8A878,
  "navy" to 0xFF1E2E4A, "blue" to 0xFF3050A0, "sky" to 0xFF9CBDD8, "denim blue" to 0xFF4860A0,
  "green" to 0xFF4A7040, "olive" to 0xFF5A6030, "forest" to 0xFF2C4E2A, "sage" to 0xFF9AB58F,
  "red" to 0xFFB83030, "burgundy" to 0xFF5E1820, "pink" to 0xFFD48090, "coral" to 0xFFE78060,
  "orange" to 0xFFD07030, "yellow" to 0xFFC8B030, "mustard" to 0xFFA08818,
  "purple" to 0xFF7060A0, "lavender" to 0xFFB5A0CC,
).mapValues { Color(it.value) }
// "multicolor" → render the multi-color gradient brush
```

For the named tags, run them through `String.normalizeColor()` (`TagNormalizer.kt`) before lookup so "grey", "sky blue", etc. resolve.

### Spacing

- Screen padding: `14.dp` horizontal
- Card padding: `12.dp`
- Row padding: `horizontal=14.dp, vertical=12.dp`
- Drawer padding: `horizontal=14.dp, bottom=14.dp, top=4.dp`
- Chip gap: `5.dp`
- Card-to-card gap: `10.dp`

### Radii

- Cards: `18.dp`
- Photo / drawer inputs: `14.dp`
- Chip / pill: `999.dp` (full)
- Color swatch: `999.dp` (full circle)

### Typography (`Plus Jakarta Sans` in prototype → use existing app font)

| Use | Size | Weight |
|---|---|---|
| Title | 15sp | 700 |
| Card heading / row label | 12sp | 700 |
| Body / row value | 12sp | 600 |
| Eyebrow (NAME / TYPE / CATEGORY) | 10sp / 9sp | 700, uppercase, letter-spacing 0.3–0.4 |
| Chip text | 11–12sp | 600 |
| Caption / save label | 11sp | 600 |

### Shadows

- No card shadow — borders only.
- Save pill — no shadow.
- AI re-tag CTA — no shadow (gradient + border is enough).

## Icons (Compose mapping)

| Prototype | Compose |
|---|---|
| `back` | `Icons.AutoMirrored.Filled.ArrowBack` |
| `check` | `Icons.Default.Check` |
| `close` | `Icons.Default.Close` |
| `add` | `Icons.Default.Add` |
| `ai` (sparkle) | `Icons.Default.AutoAwesome` |
| `down` / `up` | `Icons.Default.ExpandMore` / `ExpandLess` |
| `cloud` | `Icons.Default.CloudQueue` |
| `cloudDone` | `Icons.Default.CloudDone` |
| `syncing` | `Icons.Default.Sync` |

## Assets

No new assets. Item photo via Coil and `image.localPath`.

## Files in this bundle

- `Variant C Preview.html` — open in any browser to see the design (3 theme variants)
- `tags-variant-c.jsx` — React component reference
- `tags-shared.jsx` — shared tokens, icons, sample item, color map
- `README.md` — this document

## What to do

1. Open `Variant C Preview.html` to study the design.
2. Cross-reference `WardrobeScreen.kt` — specifically `TagEditSheet` (the function you're replacing) and the `PRESET_*` constants you'll keep.
3. Decide: keep the bottom sheet container or upgrade to a full-screen `Dialog`? Full-screen is recommended — the new layout is taller and benefits from edge-to-edge.
4. Implement `TagEditScreen` per the spec above. Reuse `WardrobeViewModel.updateTags`, `ClothingTags`, `normalize()`, `TagCategory`, `AiProcessingOverlay`, `LocalIsOffline`, `LocalSystemBarsPadding`.
5. Wire autosave: debounce 300ms for chip toggles, 600ms for free-text fields.
6. Verify localization — every label goes through `stringResource(...)`. The new strings needed:
   - `R.string.wardrobe_tag_sheet_title` — already exists, reuse for screen title
   - Section labels (Colors, Uses, …) — already exist as `R.string.tag_colors`, etc.
   - New: `R.string.wardrobe_tag_saved`, `R.string.wardrobe_tag_saving`, `R.string.wardrobe_tag_unsaved`, `R.string.wardrobe_tag_redetect_ai`, `R.string.wardrobe_tag_redetect_ai_cost` (e.g. "%1$d credits"), `R.string.wardrobe_tag_not_set`. Add to `values/strings.xml` AND `values-de/strings.xml`.
7. Verify dark theme — the prototype includes a green-dark palette that maps to the existing dark `colorScheme`.

## Out of scope

- The `ChipListEditor` "Add custom" flow — reuse existing.
- AI prompt / Gemini call path — already implemented in `GeminiRepository.classifyClothing`.
- Tag normalization at write time — already in `TagNormalizer.kt`.
