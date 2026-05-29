# Handoff: Outfit-with-AI Factor Dialog

## Overview

A redesign of the **"Tune your outfit AI"** dialog — the screen where the user picks the occasion, vibe, weather mode, and AI considerations (location, trends, gender, age, preferences) before tapping **Generate outfit**. This is the panel that feeds inputs to Gemini for outfit composition / suggestion. It does **not** include the items grid, items composition (target layer counts), or the description field — those stay in the main `OutfitComposerScreen`. The goal here is a clean, scannable, sectioned panel.

**Replaces:** the existing "AI factors" portion of `OutfitComposerScreen.kt` (the goal field + weather section + style-vibe chips + AI considerations strip). It does **not** replace `OutfitComposerScreen` wholesale.

## About the Design Files

The HTML/JSX in this bundle is a **design reference** — a React prototype showing layout, behavior, and visual treatment. It is **not** production code. The task is to **recreate this design in the existing Compose codebase** (`com.librelookai.outfit`), reusing existing state (`composerFeedback`, `composerVibes`, `composerWeatherMode`, `composerManualTempC`, `composerManualPrecip`, `composerSourceFolderIds`, plus `AiConsiderations`) and existing components (`AiConsiderationsStrip`, `WeatherSection`, `OutfitsViewModel.*` actions).

Open `Dialog Preview.html` in any browser to see the design in three theme variants side by side.

## Fidelity

**High-fidelity.** Colors, spacing, radii, typography, and interaction details are intentional. Match them in Compose.

## Screen / View

### Name
**Tune your outfit AI** — opens as a `Dialog` (or sheet) before AI generation. In the existing `OutfitComposerScreen` this is the upper section above the items grid; you can keep that pattern or extract it into a dedicated screen, whichever fits navigation.

### Purpose
Let the user steer the AI generation in one place: state the occasion, set weather (auto or manual), pick a style vibe, and toggle which signals (weather/location/trends/gender/age/preferences) AI should consider. The dialog ends with **Generate outfit**.

### Layout

Frame `390 × 844` (logical). Respect `LocalSystemBarsPadding` for status/nav-bar insets.

Vertical stack, top → bottom:

| Section | Height | Notes |
|---|---|---|
| Status bar | 44 | System |
| Sticky header | ~58 | Close · "Tune your outfit AI" + subtitle "What should Gemini consider?" · "Reset" text button |
| Scrollable body | flex | 4 sections, each a `Card`: Occasion · Weather · Style vibe · What should AI consider? |
| Sticky bottom bar | ~76 | "Cancel" (outlined) + "Generate outfit" (filled, primary) |

Horizontal padding: `14.dp`. Card-to-card gap: `10.dp`.

### Components

#### Sticky header
- `Row(padding = horizontal=4.dp, top=4.dp, bottom=10.dp)`, border-bottom `1.dp` `divider`
- IconButton (40dp): `Icons.Default.Close`, tint `onSurface`
- Column (flex 1):
  - Title — 15sp/700 `onSurface`
  - Subtitle — 11sp `textMuted`, marginTop `1.dp`
- `TextButton`: "Reset", 12sp/600 `textMuted`, padding `horizontal=10.dp`, `padding=vertical=6.dp`. Tapping resets every field in the dialog to its default.

#### Section card (used by all 4 sections)
- `Surface(shape = RoundedCornerShape(16.dp), color = surface, border = 1.dp divider)`, padding `14.dp`
- Header row:
  - 24×24 square (radius 8.dp), background `primarySoft`, color `primary`, icon 14dp inside
  - 8.dp gap
  - Title 13sp/700 `onSurface`
- Optional hint line (11sp `textMuted`), 4.dp below title; otherwise add 10.dp gap to body
- Body content varies per section

#### 1) Occasion section
- Icon: `Icons.Default.AutoAwesome`
- Title: "Occasion"
- Hint: "A sentence helps Gemini hit the right tone."
- Body: a tappable read-only-looking row that **focuses** an inline `OutlinedTextField` (1–3 lines):
  - Background `surface2`, border `1.dp` `border`, radius `12.dp`, padding `horizontal=12.dp, vertical=10.dp`, min-height `48.dp`
  - Left: the typed value (or placeholder), `bodyMedium` 14sp, weight 500 when filled
  - Right: `Icons.Default.Edit` 14dp `textMuted`
- Binds to: `OutfitsViewModel.updateComposerFeedback(...)` (state field `composerFeedback: String`)

#### 2) Weather section
- Icon: `Icons.Default.WbSunny`
- Title: "Weather"
- Body — mode toggle then optional manual controls:
  - **Mode chips** (row, gap 6.dp, margin-bottom 10.dp):
    - "Auto · {city} {temp}°" with `Icons.Default.Refresh` 13dp
    - "Manual" with `Icons.Default.Tune` 13dp
    - One is `active`; see chip spec below
  - **If manual** is selected, render three sub-rows (gap 10.dp):
    - "Temperature" label 11sp/600 `textMuted`, then a FlowRow of chips: `-5° / 5° / 12° / 18° / 22° / 28°`. Active = `tempC` equals that value.
    - "Precipitation" label, then chips: `none / light / heavy`. Active = current value.
  - All chips small (5px 10px padding, 11sp/600).
- Binds to: `composerWeatherMode` (`AUTO`/`MANUAL`), `composerManualTempC: Int?`, `composerManualPrecip: String` — exact existing fields. Reuse the **existing** `WeatherSection` composable as-is unless you want to restyle it; the chip-only treatment above is the intent.

#### 3) Style vibe section
- Icon: `Icons.Default.AutoAwesome` (or a sparkle if available)
- Title: "Style vibe"
- Hint: `"{n} selected"` — `n = composerVibes.size`
- Body: FlowRow of vibe chips (gap 5.dp). One chip per vibe. Multi-select.
- Vibes list (exact, in this order): `Casual · Sporty · Formal · Business · Streetwear · Minimalist · Classic · Elegant`
- Binds to: `composerVibes: Set<String>` via `toggleComposerVibe(value)`. These strings map 1:1 to existing string-resources `composer_vibe_*`.

#### 4) What should AI consider? section
- Icon: `Icons.Default.TrendingUp` (the trend line icon)
- Title: "What should AI consider?"
- Hint: "Signals layered on top of your wardrobe to personalize the result."
- Body: a vertical list of 6 toggle rows, divided by 1.dp `divider` lines (no divider after the last):
  - Row: `padding = horizontal=4.dp, vertical=10.dp`, clickable to toggle
  - Left: 28×28 square (radius 8.dp), `primarySoft` bg + `primary` icon when on, `surface2` bg + `textMuted` icon when off; 14dp icon inside
  - 10.dp gap
  - Label: 13sp/600 — color `onSurface` when on, `textMid` when off
  - Right: a custom switch — 34×20 pill, padding 2.dp, with a 14×14 thumb that slides right when on. On = `primary` bg + white thumb. Off = `surface2` bg + 1.dp `border` + `textMuted` thumb.
- Six rows, in this order:
  1. **Weather** — `Icons.Default.WbSunny`
  2. **Location** — `Icons.Default.Place`
  3. **Trends** — `Icons.Default.TrendingUp`
  4. **Gender** — `Icons.Default.Person`
  5. **Age** — `Icons.Default.Cake`
  6. **Preferences** — `Icons.Default.Favorite`
- Binds to: `AiConsiderations` (existing data class). Use the existing `AiConsiderationsStrip` logic but **render it as a vertical list** instead of the horizontal pill strip. Each toggle calls the same per-consideration setter the strip already uses.

#### Sticky bottom action bar
- Background `bg @ 90% alpha + backdrop blur`, top border `1.dp` `divider`, padding `horizontal=14.dp, top=10.dp, bottom=18.dp + nav-bar insets`
- Layout: `Row(gap = 8.dp)`
- **Cancel** — outlined button: height 48.dp, padding `horizontal=18.dp`, radius `24.dp`, `1.5.dp` `border`, fg `textMid`, 13sp/700. Closes the dialog without saving.
- **Generate outfit** — filled (flex 1): height 48.dp, radius `24.dp`, bg `primary`, fg `onPrimary`, leading `Icons.Default.AutoAwesome` 16dp, label 14sp/700. Shadow: `0 6 18` of `primary` at 33% alpha. Disabled (alpha 0.4) when occasion + vibes are both empty (optional — pick whichever bar makes sense for your model).

## Interactions & Behavior

- **Tap any chip** → updates the underlying set / value immediately (optimistic state).
- **Auto/Manual weather** → switching to Auto clears manual fields and resumes the live forecast. Manual keeps them.
- **Toggle consideration** → updates the per-toggle setter; **the active value is the per-call override**, falling back to the user's saved `UserPreferences.aiConsiderations` until they touch it (existing pattern — see `TravelViewModel.setConsideration`).
- **Reset** (header) → restores all four sections to defaults (occasion=empty, weather=AUTO, vibes=empty, considerations=`prefs.aiConsiderations`).
- **Generate outfit** → existing `OutfitsViewModel.enhanceComposerWithAi(prefs, weather, images)` call; show `AiProcessingOverlay` (existing) while running. Errors surface as Snackbar.
- **Cancel** → close dialog, no save.

### Animations
- Section card body expand/collapse: 220ms ease-out (`animateContentSize`) — only the Weather body shrinks/grows when toggling Auto↔Manual.
- Toggle switch thumb: 150ms ease.
- Chip selection: instant background change with 140ms crossfade.

## State Management

Reuse the existing model — **no new fields required**. Every section binds to an existing `OutfitsViewModel.composer*` field or to `AiConsiderations`.

```kotlin
@Composable
fun TuneOutfitAiDialog(
    composerState: OutfitComposerUiState,   // existing state
    prefsConsiderations: AiConsiderations,
    onOccasionChange: (String) -> Unit,
    onVibeToggle: (String) -> Unit,
    onWeatherModeChange: (ComposerWeatherMode) -> Unit,
    onManualTempChange: (Int?) -> Unit,
    onManualPrecipChange: (String) -> Unit,
    onConsiderationToggle: ((AiConsiderations) -> AiConsiderations) -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onGenerate: () -> Unit,
)
```

The active considerations value is `composerState.considerationsOverride ?: prefsConsiderations` — same pattern as the existing `TravelScreen` consideration strip.

## Design Tokens

Use existing Material 3 `colorScheme`. The prototype's `green-light` palette maps to:

| Token | Light | Dark |
|---|---|---|
| `bg` / `background` | `#F3F6EF` | `#171F15` |
| `surface` (cards) | `#FFFFFF` | `#1F2A1C` |
| `surface2` (input bg) | `#EBF1E5` | `#273323` |
| `primary` | `#4E7844` | `#7BBD6C` |
| `primaryDim` | `#D0E4C8` | `#243D1F` |
| `primarySoft` (icon bg) | `#E4EFDD` | `#1E2E1A` |
| `text` / `onSurface` | `#1A2618` | `#DEE9D8` |
| `textMid` | `#3D5438` | `#A8C09C` |
| `textMuted` | `#6A8060` | `#728060` |
| `border` | `#BDD0B2` | `#304028` |
| `divider` | `#D4E0CC` | `#283820` |
| `chipBg` (idle chip) | `#EFF4EA` | `#253020` |
| `chipFg` | `#2E4A28` | `#9ABF8A` |
| `onPrimary` | `#FFFFFF` | `#0F1E0C` |

### Spacing
- Card padding: `14.dp`
- Card-to-card gap: `10.dp`
- Chip gap: `5–6.dp`
- Section header bottom margin: `4.dp` (with hint) / `10.dp` (without hint)

### Radii
- Section card: `16.dp`
- Input row: `12.dp`
- Chip / pill / button: `999.dp` (full)
- Section icon square: `8.dp`
- Consideration icon square: `8.dp`
- Toggle switch: `10.dp` (pill)

### Typography (Plus Jakarta Sans in prototype → use existing app font)

| Use | Size | Weight |
|---|---|---|
| Title (header) | 15sp | 700 |
| Subtitle / hint | 11sp | 400–600 |
| Card heading | 13sp | 700 |
| Body / input | 14sp | 500 |
| Chip / label / button | 11–13sp | 600–700 |
| Eyebrow (TYPE / CATEGORY etc) | 9–10sp | 700 uppercase, letter-spacing 0.3–0.4 |

### Chip spec
- Default (`size="md"`): padding `horizontal=12.dp, vertical=7.dp`, radius 999.dp, 12sp/600
- Small (`size="sm"`): padding `horizontal=10.dp, vertical=5.dp`, 11sp/600
- Active: bg `activeBg` (= `primary`), fg `activeFg` (= `onPrimary`), 1.5.dp `primary` border
- Inactive: bg `chipBg`, fg `chipFg`, 1.dp `border`
- Optional leading icon: 11–13dp, same color as fg

### Shadows
- Generate button: `0 6 18` of `primary` at 33% alpha
- Cards: none (border only)
- Bottom bar: top `1.dp` divider

## Icons (Compose mapping)

| Prototype | Compose |
|---|---|
| `close` | `Icons.Default.Close` |
| `check` | `Icons.Default.Check` |
| `edit` | `Icons.Default.Edit` |
| `add` | `Icons.Default.Add` |
| `ai` (sparkle) | `Icons.Default.AutoAwesome` |
| `sparkle` (5-pt star) | `Icons.Default.AutoAwesome` (same — only one is shipped in M3) |
| `sun` | `Icons.Default.WbSunny` |
| `cloud` | `Icons.Default.Cloud` |
| `rain` | `Icons.Default.Grain` (or custom) |
| `snow` | `Icons.Default.AcUnit` |
| `place` | `Icons.Default.Place` |
| `flight` | `Icons.Default.FlightTakeoff` |
| `trend` | `Icons.Default.TrendingUp` |
| `person` | `Icons.Default.Person` |
| `cake` | `Icons.Default.Cake` |
| `heart` | `Icons.Default.Favorite` |
| `refresh` | `Icons.Default.Refresh` |
| `tune` | `Icons.Default.Tune` |

## Strings

Reuse existing strings; **no new resources needed** for any of the user-facing labels:

- Title: `R.string.composer_ai_title` (add if missing) — "Tune your outfit AI"
- Subtitle: `R.string.composer_ai_subtitle` (add if missing) — "What should Gemini consider?"
- Section titles: `R.string.composer_section_weather`, `R.string.composer_section_vibe`, plus add `R.string.composer_section_occasion`, `R.string.composer_section_considerations`
- Vibes: `R.string.composer_vibe_casual` … `composer_vibe_elegant` — all exist
- Considerations: `R.string.ai_consider_weather` … `ai_consider_preferences` — all exist
- Buttons: `R.string.action_cancel`, `R.string.composer_goal_generate` (or add `R.string.composer_generate_outfit`)
- Reset: `R.string.action_reset` (add if missing)

Any new strings must be **mirrored in `values-de/strings.xml`** at the same commit (per `CLAUDE.md`).

## Files in this bundle

- `Dialog Preview.html` — open in any browser to see the design in 3 themes side by side
- `ai-dialog-v1.jsx` — React component reference
- `ai-shared.jsx` — shared tokens, icons, sample data
- `README.md` — this document

## What to do

1. Open `Dialog Preview.html` to study the design.
2. Cross-reference the existing `OutfitComposerScreen.kt` and `AiConsiderationsStrip.kt`. Identify the "AI factors" portion you're replacing.
3. Decide whether this lives inline at the top of `OutfitComposerScreen` (as today) or extracts into a dedicated `TuneOutfitAiDialog`. Either works; the design is built to fit as a full-screen dialog.
4. Implement the four section cards. The Weather section can wrap (and restyle) the existing `WeatherSection` — keep the data flow intact. The Considerations section is a vertical re-layout of the existing `AiConsiderationsStrip` data; reuse the same `setConsideration` plumbing.
5. Confirm dark theme renders correctly (existing dark `colorScheme` already covers the green-dark spec).
6. Verify localization — every label goes through `stringResource(...)`. Add the few missing strings noted above and mirror them in `values-de/`.

## Out of scope

- The Items grid, ComposerTargets steppers, free-text Description, and Name field — those remain in their current locations.
- AI prompt / Gemini call path — already implemented in `GeminiRepository` + `OutfitsViewModel.enhanceComposerWithAi`.
- New analytics events — keep existing `Analytics.action("OutfitComposer", ...)` calls.
