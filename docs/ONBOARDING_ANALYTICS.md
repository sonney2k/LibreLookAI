# Onboarding funnel analytics

How to measure how many users make it through first-run onboarding — in particular how many
reach and complete the **Gemini API key** step.

The app emits the raw events below (via `util/Analytics` → Firebase Analytics, instrumented in
`onboarding/OnboardingScreen.kt`). Firebase/GA4 does **not** show a funnel automatically — you have
to register the custom events/params and build a Funnel exploration once in the console. This doc is
that recipe.

## Events emitted

| Event | When | Params |
| --- | --- | --- |
| `onboarding_step` | Every time a page is reached (incl. swiping back to it) | `step` (string, see names below), `index` (0-based page position), `total` (page count) |
| `onboarding_api_key_entered` | A plausible Gemini key is present (`length ≥ 20`) | — |
| `onboarding_finish` | User exits the tour, whether by completing it or tapping Skip | `completed` (`true` = reached the deliberate end, `false` = Skip), `drive_connected`, `background_granted`, `api_key_entered` (all `true`/`false`, end-state of the required steps) |

### `step` values (stable, count-independent)

Ordered as the user encounters them:

1. `intro_0` … `intro_5` — value-prop / feature pages (named `intro_<index>`; count may change)
2. `drive_signin` — connect Google Drive *(required)*
3. `background_permission` — battery-optimisation exemption *(required)*
4. `location_permission` — location for weather *(optional)*
5. `api_key` — paste Gemini API key *(required)*
6. `profile` — style profile
7. `photo` — try-on photo
8. `finish` — setup-summary page

> Names are derived from the step's role, not its numeric index, so adding/removing intro pages
> won't break the funnel. Only `intro_*` names shift with the page count.

## Console setup (one-time)

### 1. Register custom dimensions
GA4 won't let you filter/segment on a param until it's registered.

**Admin → Custom definitions → Custom dimensions → Create**, scope **Event**, for each:

| Dimension name | Event parameter |
| --- | --- |
| onboarding_step | `step` |
| onboarding_index | `index` |
| onboarding_completed | `completed` |
| onboarding_drive_connected | `drive_connected` |
| onboarding_background_granted | `background_granted` |
| onboarding_api_key_entered | `api_key_entered` |

(Custom events like `onboarding_step` appear in **Events** automatically once data arrives — up to
24h. No need to mark them as conversions unless you want `onboarding_finish` as one.)

### 2. Build the funnel
**Explore → Funnel exploration → blank.** Add ordered, *open* steps (so users are still counted if
they skip an optional step). Use **Event = `onboarding_step`** with a condition on the `step`
dimension for each:

1. `onboarding_step` where `step = drive_signin`
2. `onboarding_step` where `step = background_permission`
3. `onboarding_step` where `step = api_key`   ← the key step
4. `onboarding_api_key_entered`               ← did they actually add a key
5. `onboarding_step` where `step = finish`
6. `onboarding_finish` where `completed = true`

The drop between steps 3 → 4 is the **API-key conversion** (reached the prompt vs. entered a key);
3 → 6/last is overall completion.

> Optional: omit `location_permission`, `profile`, `photo` from the funnel — they're skippable, so
> including them as required steps would understate completion. Add them as extra open steps only if
> you want their drop-off too.

## Notes / gotchas

- **`onboarding_api_key_entered` is not transition-only.** It also fires on first composition if a
  key already exists (reinstall / new device / replaying the tour from Settings ▸ "Take the tour").
  So it counts "a key was present," slightly inflating fresh entries. For strict end-state, prefer
  the `api_key_entered` param on `onboarding_finish`.
- **Replaying the tour re-emits everything.** Re-running from Settings logs a fresh `onboarding_step`
  sequence. To isolate true first-run, segment on new users / first-open cohort.
- **`onboarding_step` fires on back-swipes too**, so raw event counts ≠ unique users. Funnels and
  "total users" metrics dedupe by user; use those, not raw event counts, for funnel rates.
- DebugView: enable with `adb shell setprop debug.firebase.analytics.app com.librelookai` to watch
  events live while testing the flow.
