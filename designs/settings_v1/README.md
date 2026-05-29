# Handoff: LibreLookAI · Settings Redesign (V1 — Single Scroll)

## Overview

The current `SettingsScreen` ships **8 horizontally-scrolling tabs** (Profile · Display · Data · Credits · Costs · AI · Feedback · About) crammed into a single Compose screen file (~3,200 lines). Our target audience — fashion-oriented women and men who are **not tech-savvy** — find this confusing: half the tabs are hidden off-screen at any time, half of the visible labels read as developer jargon, and destructive AI operations sit one careless tap away from low-stakes preferences.

This handoff replaces that screen with **V1: a single calmly-grouped scroll page** in the iOS-Settings idiom, plus a separate **Advanced** screen that hides every power-user and destructive option behind one quiet door at the bottom.

## About the design files

The files in this bundle are **design references created in HTML/React/JSX** — prototypes showing intended look and behavior. **They are not production code to copy directly.** Re-implement them in the existing app's Compose stack (Material 3, Kotlin, `com.librelookai.*`) using its established patterns, theming, navigation, and resource conventions. Use the HTML mocks for layout, hierarchy, copy, and proportions — not for source-code transcription.

## Fidelity

**High-fidelity.** Colors, type sizes, spacings, corner radii, copy, and section ordering in the mocks are the target. Reproduce them pixel-faithfully but adapt to Material 3 components (e.g. use `ListItem`, `Card`, `Switch`, `Surface`) rather than hand-rolled `Row`s where the result is visually equivalent.

## Files to read in this bundle

| File | Purpose |
|---|---|
| `Settings Redesign.html` | The canvas — open this to see everything side by side (current state → V1 → Advanced → confirm dialog). The `V1-full` artboard shows the entire page at its natural height. |
| `settings-v1.jsx` | Main settings page composition (the target). |
| `settings-advanced.jsx` | The "Advanced" sub-screen reached from the "Advanced" row. |
| `settings-confirm.jsx` | The friendly destructive-action confirm dialog (used for Re-tag, Re-remove-BG, etc.). |
| `settings-shared.jsx` | Shared row/card/section primitives — read this to understand `Row`, `Card`, `SecLabel`, `ScreenHeader`, `ThemeSwatch`. |
| `settings-current.jsx` | The **current** screen recreated for comparison. **Do not implement this** — it's the "before". |
| `settings-v2.jsx` | Discarded alternative (3-tab variant). For reference only. |

---

## What changes vs. today

### Removed entirely from the main settings surface

| Today's tab / control | Where it goes in V1 |
|---|---|
| Profile tab (display name, gender, year of birth, language) | Collapsed into a **single "You" hero card** at the top + a row in the "Your style" card for Language. The hero card opens a profile-edit destination via the trailing "Edit" pill. |
| Display tab (theme, font, density, accent picker) | Theme becomes **visual swatches** in the "How it looks" card. Font picker, density, accent picker → **Advanced**. |
| Data tab (closets, re-scan tags, re-remove BG, cutout fix) | Closets become the "Your closets" card. All three destructive AI ops → **Advanced** under "Fix AI mistakes". |
| Credits tab | The "AI credits" card. |
| Costs tab (charts of credit spend) | → **Advanced** → "See AI usage & cost charts". |
| AI tab (BYOK key, on-device toggle, similarity preview) | → **Advanced** under "Skip the credits" + "Power options". |
| Feedback tab | → **Advanced** → "Send feedback". |
| About tab | Last row in the "More" card (version inline). |
| Horizontal tab row | **Deleted.** No tabs anywhere. |

### Reworded for non-technical users

| Old copy | New copy |
|---|---|
| "Rescan all wardrobe items" | "Re-tag all my clothes" |
| "Remove BG from All Items" | "Re-remove backgrounds" |
| "Fix Cutout Backgrounds" | "Fix leftover background pixels" |
| "BYOK Gemini API Key" | "Use your own free AI key" |
| "Dedupe threshold" | "Skip duplicate items on import" (toggle) |
| "Locations" | "Closets" (throughout the app, not just here) |
| "Similarity debug preview" | "Show AI similarity preview" |
| "240 credits" (bare number) | "240 credits left · Enough for ~30 try-ons or 48 outfit ideas." |

---

## The screen: V1 — Settings (main)

### Layout

A single vertically-scrolling `LazyColumn`. **No tabs, no top-level segmentation.** Sections are visually separated by all-caps section labels above grouped cards.

Top-to-bottom order (this order is intentional — most-frequent first, irreversible last):

1. **TopAppBar** — title "Settings", standard back arrow leading.
2. **"You" hero card** (no section label above it; sits flush at the top with 4px top margin)
3. Section: **YOUR STYLE** — card containing Try-on photos block + Style preferences row + Language row
4. Section: **YOUR CLOSETS** — card containing one row per closet + "Add a closet" row. Section label has a trailing hint "Tap one to make default".
5. Section: **HOW IT LOOKS** — card containing helper sentence + horizontally-scrollable theme swatches
6. Section: **AI CREDITS** — card with balance + plain-English equivalence + "Get more" CTA
7. Section: **MORE** — card with Advanced row + Help & FAQ row + About row (trailing value `v2.4.1`)
8. Footer caption — "Made with love · Free & open source", centered, muted

### Components in detail

#### "You" hero card

- Container: `Surface` with `RoundedCornerShape(18.dp)`, 1px `divider` border, `16.dp` horizontal margin, `4.dp` top margin, `16.dp` internal padding.
- Avatar: 56×56 circle. Background = brand gradient (`aiGradStrong` — see Design Tokens). Centered single-letter initial (font weight 800, 22sp, on-gradient text color).
- Middle column (flex 1): display name (17sp/700) + one-line preference summary (12sp/muted) e.g. `"Casual · Minimalist · she/her · English"`. The summary is **computed from current settings** — see "State" below.
- Trailing "Edit" pill: rounded 999 capsule, primaryDim background, primary text, 12sp/700, padding `7×12`. Tapping it navigates to a Profile edit destination (see Navigation).

#### Try-on photos block (inside Your Style card)

- Block padding: `14 14 10`.
- Heading: "Try-on photos" (13sp/700).
- Sub: "Three quick body shots so AI can dress you up. Only you see them." (11.5sp/muted, lineHeight 1.35).
- Three equal-width slots in a `Row` with `8.dp` `gap`, each aspect-ratio 3:4, `RoundedCornerShape(10.dp)`. Filled slots: 2px primary border + figure SVG silhouette tinted with the theme accent. Empty slot: 1.5px dashed border + center "+" icon.
- Slot caption below the box (11sp/textMid): "Front", "Side", "Back".
- Tapping a slot opens the camera/picker for that pose. Long-press a filled slot offers "Replace / Remove".

#### "Your closets" card

- Each row: 12×14 padding, 12.dp gap between bullet + content + trailing edit icon.
- Leading element: 8×8 round dot. Color = `primary` for the default closet, `divider` for the others. **(Not the 30×30 icon tile used elsewhere — this is the one exception, because closets read as a list.)**
- Title row: name (14sp), weight 700 if default else 600; trailing inline pill `DEFAULT` (10sp/700, primary text on primaryDim, 2×6 padding, 8.dp corner) shown only for the default.
- Subtitle: city + item count (`"Brooklyn · 142 items"`).
- Trailing icon: pencil/edit (16sp, textMuted). Tap → rename + city sheet.
- **Tap anywhere on a non-default row** to make it default (with subtle haptic + the dot/pill animating to its new home). No confirm dialog — it's reversible.
- Final row: "+ Add a closet" with `add` icon and chevron, opens a sheet to pick name + city.

#### "How it looks" card

- Helper line: "Pick a vibe. You can change anytime." (11.5sp/muted, padded `4 16 12`).
- A horizontally-scrolling `LazyRow` of swatches, padded `0 16`, `10.dp` gap between items.
- Each swatch: 78dp tall column → 64×84 preview rectangle showing four stacked color rects from the palette (bg / surface / primary / accent), with rounded corners. Selected = 2px primary border + soft shadow + small check badge top-right. Label (11sp) under, weight 700 if selected.
- Tapping a swatch applies the theme **live** (no confirm, no preview-then-apply). Persist immediately.
- Seven themes ship: `green-light` (default), `green-dark`, `sand-light`, `indigo-dark`, `pastel-mint`, `pastel-blush`, `pastel-lavender`. Use the palette values listed in `THEME_PALETTES` in `settings-shared.jsx`.

#### "AI credits" card

- Single row, `14 16` padding.
- Leading: 42×42 round, gradient `aiGrad` background, primary-tint coin icon.
- Big number: 20sp/800, "240" with " credits left" as 12sp/600/muted on the same baseline.
- Sub: "Enough for ~30 try-ons or 48 outfit ideas." (11.5sp/muted). **This subtitle is computed**: take the credit balance, divide by the average cost per try-on (8) and per outfit-idea (5), and pick the two largest things the user has done recently. If the user has zero history, default to "try-ons or outfit ideas".
- Trailing CTA pill: "Get more" — primary background, on-primary text, 7×12 padding, 999 corner. Tap → in-app purchase sheet.

#### "More" card

- Three rows, all using the standard `Row` component:
  - `gear` icon, label "Advanced", sub "Re-scan tags · Re-remove backgrounds · API key · Diagnostics", trailing chevron → navigates to `SettingsAdvancedScreen`
  - `help` icon, label "Help & FAQ", trailing chevron → external URL or in-app help
  - `star` icon, label "About LibreLookAI", trailing value `v2.4.1`, trailing chevron → about page

### Standard `Row` (used throughout)

This is the building block. Compose it as a reusable composable (`SettingsRow`):

- Container: 12.dp vertical + 14.dp horizontal padding. Click ripple. Bottom 1px divider unless `isLast`.
- Leading: optional 30×30 round-rect icon tile (`RoundedCornerShape(8.dp)`). Background defaults to `primaryDim`, icon tint `primary`. Pass `iconBg` to override (Advanced uses a translucent accent: `aiAccent @ 13% alpha`).
- Center column (flex 1, 12.dp gap from leading):
  - Label: 14sp / weight 600 / color `text` (or `error` if `danger=true`)
  - Optional sub: 11.5sp / `textMuted` / lineHeight 1.3 / 2.dp top margin
- Trailing value (optional): 13sp / `textMuted`, ellipsized at maxWidth 130.dp, right-aligned, no wrap.
- Trailing accessory (mutually exclusive): chevron-right (default) **or** Material 3 `Switch` (`switch-on` / `switch-off`).

---

## The screen: V1 — Advanced

Reached only via the "Advanced" row in the "More" card. Has its own `TopAppBar` with back arrow and title "Advanced".

Top-down:

1. **Warning pill** at the top — `display:inline-flex`, accent-tinted background, primary text, 10.5sp/700/uppercase, `5×10` padding, with a small ⚠ icon. Text: **"Rarely needed"**.
2. **Intro paragraph** below it (13sp/textMid/lineHeight 1.5): "These tools fix problems with AI-tagged data or change how AI behaves. Most people never need to touch them."
3. Section **FIX AI MISTAKES** — three rows (all accent-tinted icon tiles):
   - `refresh` · "Re-tag all my clothes" · "Ask AI to re-name and re-categorize everything. Costs ~2 credits per item."
   - `sparkle` · "Re-remove backgrounds" · "Re-cut every item from its photo. Costs ~5 credits per item."
   - `tune` · "Fix leftover background pixels" · "Scan for items where the cutout missed a corner."
   - **All three tap-throughs open the destructive-confirm dialog** described below.
4. Section **SKIP THE CREDITS** — single card with:
   - Title "Use your own free AI key" (13.5sp/700)
   - Help text mentioning `aistudio.google.com` (with the URL in primary color)
   - Read-only display of the saved key as masked `AIza••••••••••••••••••••••` (monospace, in a surface2 row), with a trailing "SAVED" badge (or empty input + "PASTE KEY" CTA when unset)
5. Section **POWER OPTIONS** — four rows:
   - `trend` · "See AI usage & cost charts" · "14-day trends for credits spent." → opens chart screen
   - (no icon) · "Skip duplicate items on import" · "Catch near-duplicates with image AI." · Switch — default ON
   - (no icon) · "Prefer on-device background removal" · "Faster + free, slightly lower quality." · Switch — default OFF
   - (no icon) · "Show AI similarity preview" · "Diagnostics for the find-by-photo feature." · Switch — default OFF
6. Section **HELP US IMPROVE** — two rows:
   - `send` · "Send feedback" → email composer with prefilled diagnostics
   - `download` · "Export diagnostics" → zips and shares the local diagnostics bundle

---

## The screen: V1 — Destructive confirm dialog

Triggered by **any** of the three "Fix AI mistakes" rows in Advanced. Replaces the existing terse `AlertDialog` ("Rescan all wardrobe items?").

This is a **modal sheet** centered on the screen, not a Material `AlertDialog` — the visual design matters here for the audience.

- Backdrop: 45% black scrim over the parent screen.
- Card: 22.dp corners, `bg` background, 1px divider border, large drop shadow `0 24 60 rgba(0,0,0,0.35)`, 22/14 padding.
- Stacked vertically with 10.dp gap:
  1. **Hero icon** — 54×54 round, `aiGrad` background, primary-tinted action icon (refresh / sparkle / tune depending on the trigger). Centered horizontally.
  2. **Title** — 18sp/800/centered. Templated: `"Re-tag all 204 clothes?"` (or "Re-remove backgrounds for 204 items?" / "Fix backgrounds on 12 items?"). The item count is computed from the wardrobe.
  3. **Body** — 13sp/textMid/centered/lineHeight 1.45. Honest about what's destroyed: e.g. "AI will look at every item again and may change its name, color, or category. Anything you renamed by hand will be replaced."
  4. **Cost-breakdown card** — surface background, 12.dp corners, 12/14 padding:
     - Two key/value rows: "Takes about" / `~8 minutes`, "Credits used" / `408 / 240 left` (the slash-balance is a hint).
     - If the user is short on credits: a red-tinted callout row at the bottom: `⚠ You're 168 credits short. Top up first.` (red text on red @ 7% alpha).
  5. **Action stack** — vertical, 8.dp gap:
     - Primary action button: full-width, primary background, on-primary text, 12.dp padding, 12.dp corners. Label changes based on credit state:
       - Enough credits → `"Re-tag all clothes"` / `"Re-remove backgrounds"` / `"Fix backgrounds"`
       - Insufficient credits → `"Buy credits & continue"`
     - "Cancel" — transparent background, textMid text, same padding.

Dismissal: tapping the scrim or the system back button = Cancel (no-op).

---

## Interactions & behavior

### Navigation

- This screen lives at the same NavGraph destination the existing `SettingsScreen` occupied — `route = "settings"`. **Do not introduce sub-routes for tabs** — the page is a single destination.
- **New destinations** (add to NavGraph):
  - `settings/advanced` → `SettingsAdvancedScreen` (the Advanced screen)
  - `settings/profile` → `ProfileEditScreen` (already exists in some form; the hero card "Edit" pill navigates here)
  - `settings/closet/{id}` → existing closet edit destination
  - `settings/usage` → existing usage charts screen (referenced from Advanced)
  - `settings/about` → existing About destination
- **Removed routes**: any deep links into specific old tabs (e.g. `settings?tab=ai`) should redirect to `settings` for compatibility.

### Live state

- Theme swatch tap: persist immediately via the existing theme `DataStore` and let recomposition handle the visual flip. Animate the swatch-check badge with a 150ms scale-in.
- Closet default tap: persist immediately; animate the leading dot via `animateColorAsState` (200ms) and slide the `DEFAULT` badge to its new home (or fade-swap if animation is non-trivial).
- All `Switch` rows: standard `onCheckedChange` → persist to DataStore.

### Confirmation flow

For any of the three destructive Fix-AI-Mistakes rows:

1. Tap the row → open the confirm dialog with computed counts + estimated time + credit cost.
2. If user is short on credits → primary action becomes "Buy credits & continue" and routes to the existing in-app-purchase sheet, returning to the confirm dialog after success.
3. If user has enough → primary action kicks off the existing background worker (the work itself is unchanged from today — this redesign only changes the entry point).

### Computed copy

- **Hero summary** (`"Casual · Minimalist · she/her · English"`): join, in this order, the top 2 style chips → pronouns → language. Omit empty fields.
- **Credits friendliness** (`"Enough for ~30 try-ons or 48 outfit ideas."`): `floor(balance / 8)` try-ons, `floor(balance / 5)` outfit ideas. Pick the two largest features the user has actually used in the last 30 days; default to those two.
- **Destructive title item count**: read from the wardrobe ItemDao count.

---

## State management

This screen is a **read-from-everywhere, write-to-few** surface. Most of what it shows is already in existing stores.

Use a single `SettingsViewModel` that exposes one `UiState` data class:

```
data class SettingsUiState(
  val user: UserSummary,                    // name, initial, pronouns
  val styleSummary: String,                 // computed hero subtitle
  val language: String,
  val tryOnPhotos: TryOnPhotosState,        // three slots
  val closets: List<ClosetRow>,             // ordered; first = default
  val themeId: String,
  val credits: CreditsState,
  val appVersion: String,
)
```

All writes go through ViewModel functions: `selectClosetAsDefault(id)`, `selectTheme(id)`, `toggleAdvanced(key, value)`, `triggerDestructiveAction(kind)` (which opens the confirm dialog via a separate `confirmState: StateFlow<ConfirmRequest?>`).

Data sources to wire into the ViewModel (these all already exist; we are just consolidating the read sites):
- `UserPreferencesRepository` (name, pronouns, year of birth, language)
- `ThemeRepository` (themeId)
- `ClosetRepository` (closets list + default)
- `WardrobeRepository` (item counts for confirm dialog)
- `CreditsRepository` (balance + recent feature usage)
- `StylePrefsRepository` (style chips)

---

## Design tokens

The HTML mocks reference a theme object. Map it to the existing `LibreLookTheme` MaterialTheme as follows. **The token names map to existing Material 3 color slots — do not invent new top-level theme entries.**

| Mock token | Maps to | green-light value (default) |
|---|---|---|
| `bg` | `colorScheme.background` | `#EBF1E5` |
| `surface` | `colorScheme.surface` | `#F5F8F1` |
| `surface2` | `colorScheme.surfaceVariant` | `#E3ECDB` |
| `text` | `colorScheme.onBackground` | `#1A2618` |
| `textMid` | `onBackground @ 75%` | derived |
| `textMuted` | `onBackground @ 55%` | derived |
| `divider` | `colorScheme.outlineVariant` | `#D0E0C5` |
| `border` | `colorScheme.outline` | `#B5CCA8` |
| `primary` | `colorScheme.primary` | `#4E7844` |
| `primaryDim` | `primary @ 14% alpha` | derived |
| `primarySoft` | `primary @ 8% alpha` | derived |
| `activeFg` | `colorScheme.onPrimary` | `#FFFFFF` |
| `error` | `colorScheme.error` | `#B23A48` |
| `aiAccent` | extended brand accent | `#7BBD6C` |
| `aiGrad` | linear-gradient(135°, `aiAccent @ 22%`, `primaryDim`) | — |
| `aiGradStrong` | linear-gradient(135°, `aiAccent`, `primary`) | — |
| `chipBg` | `surfaceVariant` | — |
| `chipFg` | `onSurfaceVariant` | — |

**Typography:** Use the existing app type scale. The mocks use 11/11.5/12/13/13.5/14/17/18/20 sp; Material 3 equivalents are roughly `labelSmall` / `bodySmall` / `bodyMedium` / `titleSmall` / `titleMedium` / `headlineSmall`.

**Spacing:** 4 / 8 / 10 / 12 / 14 / 16 / 18 / 22 dp. Card horizontal margin = 16dp throughout. Card-to-card vertical rhythm = ~18dp top padding on section labels.

**Corner radii:** 8 (icon tile) · 10 (try-on slot) · 12 (cost card, dialog buttons) · 14 (Card) · 18 (hero card) · 22 (modal dialog) · 999 (pills).

**Shadows / elevation:** Cards use M3 `surfaceContainerLow` instead of literal shadows. The dialog uses M3 `surfaceContainerHigh` + elevation 6.

---

## Assets

No new image assets. Icons used (map to your existing `Icons.*` or vector drawables, listed by name as referenced in `settings-shared.jsx` and `ai-shared.jsx`):

`back`, `gear`, `right` (chevron-right), `chev` (chevron-down), `globe`, `help`, `coin`, `palette`, `warn`, `trash`, `lock`, `download`, `star`, `heart`, `add`, `edit`, `refresh`, `sparkle`, `tune`, `trend`, `send`, `check`, `step` (overflow ⋯).

Material 3 / Material Symbols equivalents are all available out of the box; reuse whatever the codebase already imports.

---

## Strings / i18n

All new copy must live in `app/src/main/res/values/strings.xml` and have translations in the existing locales (the codebase has at least `values-fil`). Suggested keys:

```
settings_section_your_style
settings_section_your_closets
settings_section_how_it_looks
settings_section_ai_credits
settings_section_more
settings_section_advanced_fix
settings_section_advanced_byok
settings_section_advanced_power
settings_section_advanced_feedback
settings_advanced_intro
settings_advanced_rarely_needed_pill
settings_credits_friendly_format     // "Enough for ~%1$d try-ons or %2$d outfit ideas."
settings_tryon_photos_hint
settings_closets_default_badge
settings_closets_hint                // "Tap one to make default"
settings_more_advanced_subtitle
settings_retag_title                 // "Re-tag all %1$d clothes?"
settings_retag_body
settings_rebg_title
settings_rebg_body
settings_cutout_title
settings_cutout_body
settings_confirm_takes_about
settings_confirm_credits_used
settings_confirm_short_format        // "You're %1$d credits short. Top up first."
settings_confirm_buy_and_continue
settings_advanced_byok_title
settings_advanced_byok_body
settings_advanced_byok_saved_badge
```

**Removed strings** (orphan them from values, do not delete until the new screen ships): every `settings_tab_*` key, plus the technical copy referenced in "What changes vs. today" above.

---

## Implementation notes for Claude Code

- The existing `SettingsScreen.kt` is ~3,200 lines. **Do not edit it in place.** Add `SettingsScreenV2.kt` (or rename old to `LegacySettingsScreen.kt` and the new one to `SettingsScreen.kt`) and switch the `MainActivity` route over in one commit. Keep the legacy file untouched for one release cycle as a fallback.
- Break the new screen into small composables, one per card, in a `settings/v2/` subpackage:
  - `SettingsScreen.kt` (host + LazyColumn)
  - `HeroCard.kt`
  - `YourStyleCard.kt`
  - `YourClosetsCard.kt`
  - `HowItLooksCard.kt`
  - `AiCreditsCard.kt`
  - `MoreCard.kt`
  - `SettingsRow.kt` (the shared row primitive)
  - `SettingsAdvancedScreen.kt`
  - `DestructiveConfirmDialog.kt`
- Target ≤300 lines per file. The current monolith is the primary thing to avoid recreating.
- Animate the closet-default change and the theme-swatch selection. Skip all other animations — over-animating is its own form of complexity for this audience.
- Accessibility: every `Row` needs `Modifier.semantics { role = Role.Button; contentDescription = "<label>, <sub>" }`. Switches need their state read out. Hit target ≥ 48dp on every interactive element — bump row vertical padding if necessary.

---

## Open questions to resolve before merging

1. **Hero card "Edit" destination** — does a single profile-edit screen exist, or are name/pronouns/year-of-birth currently edited inline on the Profile tab? If inline-only, build a new `ProfileEditScreen` matching the row aesthetic.
2. **Closet default has no confirm** — confirm this matches expectations. Today, closet management is on a separate tab; instant-swap is a deliberate simplification.
3. **Credits friendliness math** — confirm the cost-per-try-on (8) and cost-per-outfit-idea (5) defaults. If different, update both the helper text in this README and the `settings_credits_friendly_format` plurals.
4. **"Cutout fix"** — confirm this feature is still worth exposing at all. If usage telemetry shows it's run by <0.5% of users, consider removing it entirely instead of demoting it to Advanced.
5. **BYOK** — once a user has pasted a key, today's UI still charges credits if the key fails. Confirm V1's "Use your own free AI key" implies "if this is set, never charge credits — fail loud if the key is invalid" or matches today's behavior.
