# Handoff: Travel · Plan screen (postcard style)

## Overview

The pre-trip planning screen — where the user enters a destination, dates, duration, outfit count, goal, vibe, and AI considerations **before** Gemini generates the packing list. This is a **separate** screen from the packing list result; tapping **Generate packing list** navigates to that result screen.

**Replaces:** the existing "input section" + AI-considerations strip at the top of `TravelScreen.kt`. The list view (forecast + outfits) stays where it is and becomes the result screen reached after generation.

## About the Design Files

The HTML/JSX in this bundle is a **design reference** — a React prototype showing layout, behavior, and visual treatment. It is **not** production code. The task is to **recreate this design in the existing Compose codebase** (`com.librelookai.travel`), reusing existing state (`TravelUiState.destination`, `startDate`, `days`, `outfitCount`, `goal`, `considerationsOverride`) and existing actions (`updateDestination`, `updateDays`, `updateStartDate`, `updateOutfitCount`, `updateGoal`, `setConsideration`, `generate`).

The `vibes` chips here are **new** to the Travel flow — they don't exist in `TravelUiState` yet. See **State Management** below.

Open `Plan Preview.html` in any browser to see the design in three theme variants side by side.

## Fidelity

**High-fidelity.** Colors, spacing, radii, typography, and interaction details are intentional. Match them in Compose.

## Screen / View

### Name
**Plan a trip** — pre-generation form. Replaces the input portion of `TravelScreen.kt`.

### Purpose
Capture every input Gemini needs to build a packing list. Each input has its place; no input is buried behind a toggle. The bottom **Generate packing list** call-to-action triggers `TravelViewModel.generate(prefs, images, styles)` and navigates to the result screen.

### Layout

Frame `390 × 844` (logical). Respect `LocalSystemBarsPadding` for status/nav-bar insets.

Vertical stack, top → bottom:

| Section | Height | Notes |
|---|---|---|
| Status bar | 44 | Background = `sky` token to extend the hero |
| Sky hero | ~210 | Back button on glass, "PLAN A TRIP / New journey", destination glass card. Decorative sun + cloud blob. |
| Dates row | ~70 | One card combining date range + days stepper |
| Outfit count | ~62 | Single card with stepper |
| Goal card | ~150 | AI-gradient card with sparkle icon + read-only-looking text field |
| AI considers | ~80 | FlowRow of toggle chips |
| Sticky bottom bar | ~84 | Single full-width "Generate packing list" button |

Horizontal padding: `16.dp`. Card-to-card gap: `12.dp`.

### Components

#### Sky hero (top region)
- Background: vertical `linear-gradient(180°, sky → surface)` — sky token is `#C9E2F3` (light green theme) / `#2B3F50` (dark) / `#E6DCC8` (sand)
- Decorative elements:
  - **Sun** — a 64×64 radial-gradient circle, `sun` token at 30% opaque center → 60% mid → transparent. Position `top=14, right=24, opacity=0.7`.
  - **Cloud** — a 130×46 rounded blob, `cloud` token bg, `blur(2px)`, `top=50, left=-20, opacity=0.5`.
- Header row:
  - 36×36 glass back button: bg `surface @ 87% alpha`, `Icons.AutoMirrored.Filled.ArrowBack` 20dp, border-less
  - Column (margin-left 8.dp):
    - Eyebrow "PLAN A TRIP" — 9sp/700 `textMuted`, letter-spacing 0.4, uppercase
    - Title "New journey" — 18sp/700 `onSurface`
- **Destination glass card** (below header, padding `horizontal=18.dp, bottom=22.dp`):
  - Background `surface @ 87% alpha + backdrop blur 8px`, border `1.dp` `divider @ 50% alpha`, radius `16.dp`, padding `horizontal=12.dp, vertical=10.dp`
  - Eyebrow row: `Icons.Default.Place` 12dp + "DESTINATION" 9sp/700 uppercase `textMuted` letter-spacing 0.4
  - Value: 18sp/700 `onSurface`
  - Tap → opens the existing destination `OutlinedTextField` flow

#### Dates row (single combined card)
- `Surface(shape = RoundedCornerShape(14.dp), color = surface, border = 1.dp divider)`, padding `12.dp 14.dp`
- `Row(verticalAlign = CenterVertically, gap = 12.dp)`:
  - **Left (flex 1)** — Dates:
    - Eyebrow row: `Icons.Default.CalendarMonth` 11dp + "DATES" 10sp/700 uppercase letter-spacing 0.4 `textMuted`
    - Value: 14sp/700, format `{startDate} → {endDate}` — derive `endDate = startDate + days` and format `MMM d`
  - Vertical divider 1×32 `divider`
  - **Right** — Days stepper:
    - Eyebrow centered "DAYS" 10sp/700 uppercase
    - Stepper: 24×24 minus button + 14sp/700 number (min 18.dp wide) + 24×24 plus button
    - Buttons: `surface2` bg, 1.dp `border` border, full-circle radius, `textMid` "−"/"+" 700 weight
- Binds to: `updateStartDate`, `updateDays` (existing). Tapping the dates region opens the existing `DatePickerDialog`.

#### Outfit count card
- Same surface treatment as Dates row, padding `12.dp 14.dp`
- `Row(gap = 10.dp)`:
  - 32×32 rounded-10 icon: `primarySoft` bg, `primary` fg, `Icons.Outlined.Checkroom` (shirt) 16dp
  - Column (flex 1):
    - Eyebrow "OUTFITS TO PLAN" 9sp/700 uppercase letter-spacing 0.4 `textMuted`
    - Value 13sp/700: `"{n} looks · ~{daysPerOutfit} days each"`
  - Stepper: 28×28 buttons, value 14sp/700 minWidth 20.dp centered
- Binds to: `updateOutfitCount` (existing). Default tracks `days` until the user explicitly sets a value (existing logic).

#### Goal card (AI gradient)
- Background: `linear-gradient(135°, primarySoft → primaryDim)` (`#E4EFDD → #D0E4C8` light)
- Border `1.dp` `primary @ 33% alpha`, radius `16.dp`, padding `14.dp`, `position: relative; overflow: hidden`
- Decorative sparkle icon top-right: `Icons.Default.AutoAwesome` 72dp, opacity 0.12, `transform: rotate(15deg)`, positioned `right=-12, top=-12`
- Eyebrow row: `AutoAwesome` 12dp + "WHAT'S THE TRIP ABOUT?" 10sp/700 `primary` letter-spacing 0.4 uppercase
- Body: an editable `OutlinedTextField` styled as a row:
  - Background `surface`, border `1.dp` `border`, radius `12.dp`, padding `horizontal=12.dp, vertical=10.dp`, min-height `54.dp`
  - Left: value 14sp/500 `onSurface`, single-line wraps to multi
  - Right: `Icons.Default.Edit` 13dp `textMuted`
- Binds to: `updateGoal` (existing). Min 1, max 3 lines.

#### Vibe chips
- Eyebrow "VIBE ({n})" 10sp/700 uppercase letter-spacing 0.4 `textMuted`, margin-bottom 6.dp
- FlowRow of small chips (gap 5.dp)
- Vibes list (exact, in this order): `Casual · Sporty · Formal · Business · Streetwear · Minimalist · Classic · Elegant` — same 8 vibes as the outfit composer
- Chip spec — see Design Tokens

**State note:** Travel doesn't currently track vibes. Add a `vibes: Set<String> = emptySet()` field to `TravelUiState` plus a `toggleVibe(value)` action. Include the vibes set in the packing prompt — see the existing `OutfitsViewModel` for the prompt-building pattern (it joins selected vibes with " / "). Clear on `clearResult`.

#### AI considers (FlowRow)
- Eyebrow "AI CONSIDERS" 10sp/700 uppercase, margin-bottom 6.dp
- FlowRow of small chips with leading icons (gap 5.dp). One chip per consideration:
  - Weather (`WbSunny`)
  - Location (`Place`)
  - Trends (`TrendingUp`)
  - Gender (`Person`)
  - Age (`Cake`)
  - Preferences (`Favorite`)
- Binds to: `setConsideration(prefsDefault, transform)` (existing). The active value is `state.considerationsOverride ?: prefs.aiConsiderations` — same pattern as today's `AiConsiderationsStrip`.

#### Sticky bottom bar
- Background `bg @ 90% alpha + backdrop blur 12px`, top border `1.dp` `divider`, padding `horizontal=14.dp, top=10.dp, bottom=18.dp + nav-bar insets`
- Single full-width button:
  - Height `52.dp`, radius `26.dp`
  - Background: `linear-gradient(135°, primary → aiAccent)` — light: `#4E7844 → #7BBD6C`
  - Color: `#FFFFFF` (always white in both themes — high contrast on the gradient)
  - Label "Generate packing list" 15sp/700, leading `Icons.Default.AutoAwesome` 18dp
  - Shadow: `0 8 22` of `primary` at 40% alpha
- Disabled when `destination.isBlank()` (alpha 0.4)
- Tap → `TravelViewModel.generate(prefs, images, styles)` (existing). Show the existing `AiProcessingOverlay` while running. On success, navigate to the result screen.

## Interactions & Behavior

- **Tap destination glass card** → focus an inline `OutlinedTextField` (existing destination input). Commit on done.
- **Tap dates value** → existing `DatePickerDialog` for start date.
- **Stepper +/−** → existing `updateDays` / `updateOutfitCount`. Clamped: days 1–21, outfits 1–21.
- **Tap goal field** → focus an `OutlinedTextField`, 1–3 lines (existing `updateGoal`).
- **Vibe chip** → toggle in `state.vibes`.
- **Consideration chip** → toggle the corresponding `AiConsiderations.consider*` field via `setConsideration`.
- **Generate** → submit; transition to the result screen.

### Animations
- Chip selection: 140ms ease, color crossfade.
- Stepper press: 100ms scale 0.96 then back.
- Bottom bar gradient: no animation; the button has a fixed shadow.

## State Management

Reuse `TravelViewModel` and `TravelUiState`. Add a `vibes` field — every other input already exists:

```kotlin
data class TravelUiState(
    // …existing fields…
    val vibes: Set<String> = emptySet(),
)
```

Plus a new action:

```kotlin
fun toggleVibe(value: String) = _state.update {
    val next = it.vibes.toMutableSet()
    if (!next.remove(value)) next.add(value)
    it.copy(vibes = next)
}
```

And clear them in `clearResult`:

```kotlin
fun clearResult() = _state.update {
    it.copy(
        // …existing…
        vibes = emptySet(),
    )
}
```

Wire `vibes` into `buildPackingPrompt(...)` next to `goal` — append a `STYLE VIBES: {joined}` line so the model can pick it up. Match the existing format used by `OutfitsViewModel` for the composer.

## Design Tokens

Use existing Material 3 `colorScheme`. The prototype's `green-light` palette maps to:

| Token | Light · Green | Dark · Green | Sand |
|---|---|---|---|
| `bg` / `background` | `#F3F6EF` | `#171F15` | `#F7F3ED` |
| `surface` (cards) | `#FFFFFF` | `#1F2A1C` | `#FFFFFF` |
| `surface2` (input bg / stepper buttons) | `#EBF1E5` | `#273323` | `#EFE9E0` |
| `primary` | `#4E7844` | `#7BBD6C` | `#8A6340` |
| `primaryDim` | `#D0E4C8` | `#243D1F` | `#E8D8C4` |
| `primarySoft` (icon bg, AI grad start) | `#E4EFDD` | `#1E2E1A` | `#F0E5D5` |
| `text` / `onSurface` | `#1A2618` | `#DEE9D8` | `#261A0E` |
| `textMid` | `#3D5438` | `#A8C09C` | `#5A3E24` |
| `textMuted` | `#6A8060` | `#728060` | `#8A7060` |
| `border` | `#BDD0B2` | `#304028` | `#D4C0A8` |
| `divider` | `#D4E0CC` | `#283820` | `#E0CEB8` |
| `chipBg` | `#EFF4EA` | `#253020` | `#F0E8DC` |
| `chipFg` | `#2E4A28` | `#9ABF8A` | `#4A2E10` |
| `sky` (hero gradient top) | `#C9E2F3` | `#2B3F50` | `#E6DCC8` |
| `sun` (decorative) | `#F5C66B` | `#A88C40` | `#E0B448` |
| `cloud` (decorative) | `#D4D8DA` | `#384048` | `#D8CDB8` |

### Spacing
- Screen padding: `16.dp` horizontal
- Card-to-card gap: `12.dp`
- Card padding: `12.dp`
- Chip gap: `5.dp`
- Hero padding: `horizontal=18.dp, bottom=22.dp`

### Radii
- Cards: `14.dp`
- Hero destination glass card: `16.dp`
- Goal AI card: `16.dp`
- Chip / pill / circular button: `999.dp` (full)
- Section icon square (32×32): `10.dp`
- Stepper button (24×24, 28×28): full circle

### Typography (Plus Jakarta Sans in prototype → use existing app font)

| Use | Size | Weight |
|---|---|---|
| Hero title "New journey" | 18sp | 700 |
| Destination value | 18sp | 700 |
| Body / value | 13–14sp | 500–700 |
| Eyebrow (uppercase) | 9–10sp | 700, letter-spacing 0.3–0.4 |
| Chip | 11sp | 600 |
| Button | 15sp | 700 |

### Chip spec
- Small (`size="sm"`): padding `horizontal=10.dp, vertical=5.dp`, radius 999, 11sp/600
- Active: bg `primary`, fg `onPrimary`, 1.5.dp `primary` border
- Inactive: bg `chipBg`, fg `chipFg`, 1.dp `border`
- Optional leading icon: 11–12dp, same color as fg

### Shadows
- Generate button: `0 8 22` of `primary` at 40% alpha
- Cards: none (border only)
- Hero: no shadow; gradient blends into body

## Icons (Compose mapping)

| Prototype | Compose |
|---|---|
| `back` | `Icons.AutoMirrored.Filled.ArrowBack` |
| `place` | `Icons.Default.Place` |
| `cal` (calendar) | `Icons.Default.CalendarMonth` |
| `flight` | `Icons.Default.FlightTakeoff` |
| `shirt` | `Icons.Outlined.Checkroom` |
| `edit` | `Icons.Default.Edit` |
| `ai` / `sparkle` | `Icons.Default.AutoAwesome` |
| `sun` | `Icons.Default.WbSunny` |
| `trend` | `Icons.Default.TrendingUp` |
| `person` | `Icons.Default.Person` |
| `cake` | `Icons.Default.Cake` |
| `heart` | `Icons.Default.Favorite` |

## Strings

Reuse existing strings; add a small set of new ones (mirror in `values-de/strings.xml` at the same commit, per `CLAUDE.md`):

- Hero eyebrow: `R.string.travel_plan_eyebrow` — "Plan a trip"
- Hero title: `R.string.travel_plan_title` — "New journey"
- Eyebrows: `R.string.travel_label_destination` ("Destination"), `R.string.travel_label_dates` ("Dates"), `R.string.travel_label_days` ("Days"), `R.string.travel_label_outfits` ("Outfits to plan"), `R.string.travel_label_vibe` ("Vibe"), `R.string.travel_label_ai_considers` ("AI considers"), `R.string.travel_label_about` ("What's the trip about?")
- Outfit-count subtitle: `R.string.travel_outfit_count_summary` — `"%1$d looks · ~%2$s days each"`
- Vibes: reuse `R.string.composer_vibe_*` (8 already exist)
- Considerations: reuse `R.string.ai_consider_*` (6 already exist)
- Button: `R.string.travel_generate` — exists ("Generate Packing List"); use it.

## Files in this bundle

- `Plan Preview.html` — open in any browser to see the design in 3 themes side by side
- `travel-v1-plan.jsx` — React component reference
- `ai-shared.jsx` — shared tokens, icons, sample data
- `README.md` — this document

## What to do

1. Open `Plan Preview.html` to study the design.
2. Cross-reference the existing `TravelScreen.kt` — specifically the LazyColumn "input section" item (destination field, duration + start-date row, outfit-count stepper, goal field, considerations strip). That's the chunk you're replacing.
3. Add the new `vibes: Set<String>` field to `TravelUiState`, `toggleVibe` action, and clear it in `clearResult`. Wire it into `buildPackingPrompt` next to `goal`.
4. Implement the hero (sky gradient + sun/cloud + destination glass card), the dates+days combined card, outfit-count card, goal AI-gradient card, vibe chip row, and considerations chip row.
5. Sticky bottom bar with the gradient generate button.
6. Verify dark theme renders correctly. The sky/sun/cloud tokens have dark equivalents in the table above.
7. Verify localization. New strings → mirror in `values-de/`.

## Out of scope

- The packing-list result screen — separate handoff / unchanged.
- AI prompt construction in `TravelViewModel` beyond adding the new `vibes` line — already in place.
- The existing `AiProcessingOverlay` — keep.
- Trip-history / saved trips — none of those are touched.
