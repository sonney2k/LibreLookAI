# FIN.md — Coin monetization plan

Concrete plan for paid-coin monetization alongside BYOK. Grounded in the existing
`billing/` + `gemini/` packages and the already-wired `geminiProxy` Cloud Function.

## Deployment progress (2026-05-30)

The code is shipped (see § "Status (2026-05-17)" below). The remaining work is
ops: standing up the live project, seeding data, wiring Play, smoke-testing.

**Done**
- ✅ Firestore database `(default)` in `eur3` (Belgium + Netherlands multi-region).
- ✅ Cloud Functions pinned to `europe-west1` via `setGlobalOptions` (commit `4535764`); deployed: `geminiProxy`, `verifyPurchase`, `recomputePublicPricing`.
- ✅ `local.properties` `firebase.proxy.url` points at the EU endpoint.
- ✅ `GEMINI_API_KEY` Functions secret provisioned.
- ✅ Interactive `scripts/seed_pricing.sh` (read-show-prompt-write workflow) + rewritten `firebase/functions/src/seed.ts` (commit `fe14761`).
- ✅ Validated `LOCAL_MODEL_PRICING` against the EU billing list, fixed (EUR-native split rates), and **re-derived the whole credit-price table** for a 100% after-tax profit — see § 12 "Gemini pricing model & unit economics".

**Pending — required to go live**
- [x] ~~Apply Option A~~ → **superseded by Option B (landed)**: `config/modelPricing`, `ModelPricingClient`, `TokenUsage`, and the cost UI (`UsageSection`, `CostBadge`) are now **EUR-native** with per-token-type split rates (`text/image/cached in`, `text/image out`). Fixes the 10× `gemini-3.1-flash-image-preview` text-out overcharge and the −46% image-out undercharge; drops the `fxUsdToEur` round-trip. Caching is **not** implemented (`cachedTextInPerM` is recorded but unused). **Action:** re-run `scripts/seed_pricing.sh` to replace the old `inUsdPerM`/`outUsdPerM`/`fxUsdToEur` doc with the new `config/modelPricing` shape.
- [ ] One-time **ADC login** so `seed_pricing.sh` can write to Firestore:
      `gcloud auth application-default login &&
       gcloud auth application-default set-quota-project librelookai-firebase`.
- [ ] **Run `scripts/seed_pricing.sh`** → review diff → confirm. Populates `config/pricing` and `config/modelPricing`; the `recomputePublicPricing` trigger mirrors to `config/publicPricing`.
- [ ] **Firestore rules** deployed: `firebase deploy --only firestore:rules --project=librelookai-firebase`.
- [ ] **Play Console** (see § 5 of the deploy guide):
  - [ ] Three managed in-app products created (`credits_100`, `credits_500`, `credits_2000`, prices per § 5 below).
  - [ ] Play Console → Setup → API access linked to GCP project `librelookai-firebase`.
  - [ ] Cloud Functions runtime SA `923211051414-compute@developer.gserviceaccount.com` invited to Play Console with "View financial data, orders, and cancellation survey responses" + "Manage orders and subscriptions" permissions.
  - [ ] Signed `.aab` uploaded to **Internal testing** track (Billing v7 won't resolve products otherwise). Bump `versionCode` first.
  - [ ] License testers added (Setup → License testing); internal-testing track opt-in URL clicked on the test device.
- [ ] **Smoke test** end-to-end on the test device: buy a pack → Firestore `users/{uid}.credits` increments and `users/{uid}/ledger` gets a `purchase` row; trigger AI action → credits decrement and a `spend` row appears; drain balance → 402 dialog routes to Settings.

**Pending — nice to have, non-blocking**
- [ ] Free starter credits on signup — `auth.user().onCreate` Cloud Function granting 20 coins (recommended, called out below).
- [x] ~~**Option B** pricing refactor~~ — **landed** (EUR-native split rates) and credit prices re-derived for 100% after-tax profit. Caching intentionally **not** built. Full rationale + cost tables consolidated into § 12 below (`plan/OPTIONS.md` deleted).
- [ ] Bind `.firebaserc` (`cd firebase && firebase use librelookai-firebase`) — cosmetic; everything else passes `--project=librelookai-firebase`, so deploys work without it.

---

## Status (2026-05-17)

**Landed:**
- Server: pricing config split (`config/pricing` private + `config/publicPricing` mirror), `recomputePublicPricing` trigger, in-memory cache, safe defaults.
- Server: ledger writes on every charge / refund / purchase (`users/{uid}/ledger`).
- Server: hardened `verifyPurchase` with Google Play Developer API validation (`androidpublisher.purchases.products.get` + acknowledge).
- Server: bug fix — `tryOnOutfit` was missing from the cost map and undercharging by 6 coins per call.
- Server: Firestore rules deny client reads of `/config/pricing`; allow auth reads of `/config/publicPricing`; allow user reads of their own `/ledger`.
- Client: `PricingClient` (Firestore listener + SharedPrefs cache + fallback). Started in `MainActivity.onCreate`.
- Client: `InsufficientCreditsException` + `throwIf402` at all 5 Gemini call sites in `GeminiRepository`.
- Client: `CostBadge` composable + `ConfirmSpendDialog` + `InsufficientCreditsDialog` + `requiresSpendConfirm()` predicate.
- Client: badges wired into TryOn (full 402 flow: badge + dialog + clear), Outfit Composer AI suggest, Settings → Import dialog (live prices), PredictionSetup (scales with outfit count), WardrobeScreen per-item FABs (tag, remove BG, fix cutout BG), WardrobeScreen multi-select "Suggest replacements", ShoppingHelper gap analyze.
- CaptureScreen / UrlImportPicker do not get direct badges — they're pre-processing steps; cost is incurred downstream by the import-options dialog (already wired).
- Find-by-photo (`findSimilarInCandidates`) uses on-device EmbeddingService — no Gemini, no badge needed.
- Strings: 11 new keys added to default `values/strings.xml` and translated into all 31 active locale mirrors (ar, b+es+419, cs, da, de, el, en-rGB, es, fi, fil, fr, hi, hu, in, it, iw, ja, ko, ms, nl, no, pl, pt-rBR, ro, sv, th, tr, uk, ur, vi, zh-rTW). Placeholders preserved; ja reorders `%1$d`/`%2$d` for natural reading.

**Simplifications vs. original plan:**
- Dropped reservation/commit complexity (§3.1). Given the **conservative fixed** pricing decision, the existing deduct-then-refund-on-error pattern is correct and simpler. Removed `users/{uid}/reservations`, `X-Request-Id` header, and `releaseStaleReservations` scheduled job from the plan.
- Skipped RevenueCat. Keeping the existing direct Google Play Billing path; webhook can be swapped in later without client changes if iOS/web lands.

**402 propagation (landed)** — instead of per-VM `needsTopUp` state, `throwIf402` emits on a global `billing/CreditsEvents.topUp` SharedFlow *before* throwing. `MainActivity` hosts a single `InsufficientCreditsDialog` observer that fires for any caller. "Buy" jumps to the Settings tab. Per-VM catches only reset loading flags (`isGenerating` / `isAnalyzing` / `isProcessing`) and short-circuit bulk loops via a local `creditsExhausted` flag (so a depleted balance doesn't trigger N pointless proxy calls).

**Remaining** (not blocking):
- Free starter credits on signup — recommended but not implemented. Add via `auth.user().onCreate` Cloud Function granting 20 coins.

---

## 0. What already exists (do not rebuild)

- `billing/BillingRepository.kt` — direct **Google Play Billing v7** client (`BillingClient`, `queryProductDetailsAsync`, `launchBillingFlow`, `queryPurchasesAsync`). **No RevenueCat dependency.**
- `billing/CreditRepository.kt` — Firestore listener on `users/{uid}.credits`, posts `purchaseToken` + `productId` to `${PROXY_BASE_URL}/verifyPurchase` with a Firebase ID token.
- `billing/CreditPack.kt` — three SKUs (`credits_100/500/2000`) + per-action cost constants (`COST_BG_REMOVAL=5`, `COST_CLASSIFY=2`, `COST_TEXT=2`, `COST_TRENDS=2`, `COST_TRY_ON=8`) and `bulkCost(...)`.
- `billing/CreditsViewModel.kt` + `BuyCreditsScreen.kt` — purchase UI.
- `gemini/GeminiRepository.kt:79` — `isProxyMode()`; `buildRequest()` already routes to `${PROXY_BASE_URL}/geminiProxy` with `Authorization: Bearer <firebase-id-token>`, `X-AI-Action`, `X-Gemini-Model`. BYOK path attaches `?key=` to the direct Gemini URL.
- `BuildConfig.PROXY_BASE_URL` and `BuildConfig.GEMINI_API_KEY` driven by `local.properties`.

**Verdict:** the architecture the user described is ~70% built. Missing pieces are server-side enforcement, the multiplier, and consistent cost-badge UX.

---

## 1. Decision: RevenueCat vs. keep direct Google Play Billing

**Recommendation: keep the existing direct Google Play Billing path.** Reasons:

1. It already works and is in `BillingRepository.kt` (124 lines, simple).
2. Single-platform (Android-only) — RevenueCat's main value is cross-platform receipt unification (iOS + Android + web), which we don't need.
3. Server-side verification we'll still do via Google's `androidpublisher.purchases.products.get` API in the Cloud Function — RevenueCat would just wrap that.
4. RevenueCat takes 1% of revenue above $10k MTR — pure cost on top of Play's 15–30%.
5. Migration risk: replacing a working billing client to gain webhook delivery isn't worth it for one platform.

**Adopt RevenueCat only if** an iOS or web client lands later, or if you want their paywall A/B test tooling. The Cloud Function below is structured so a RevenueCat webhook can be swapped in for the `verifyPurchase` HTTP path without touching the client.

---

## 2. Firestore schema

All paths under default database. Rules in §6.

```
users/{uid}
  credits: number               # current balance (server-mutated only)
  lifetimePurchased: number     # for analytics
  lastPurchaseAt: timestamp

users/{uid}/ledger/{autoId}     # append-only audit log
  type: "purchase" | "spend" | "refund" | "reservation_release"
  delta: number                 # +credits for purchase, -credits for spend
  balanceAfter: number
  action: string?               # GeminiAction header value, for spends
  model: string?
  inputTokens: number?
  outputTokens: number?
  estimatedCost: number?        # what we reserved
  actualCost: number?           # what we charged
  productId: string?            # for purchases
  purchaseToken: string?        # for purchases (idempotency key)
  createdAt: timestamp

users/{uid}/reservations/{requestId}    # in-flight coin holds
  action: string
  amount: number
  createdAt: timestamp
  expiresAt: timestamp          # TTL 5 min — Cloud Scheduler job releases stale

config/pricing                  # SERVER-ONLY READ; rules block client read
  multiplier: number            # 2.0 — the markup
  costs: map<string, number>    # { "remove_background": 5, "classify": 2, ... }
  modelCostPer1kTokens: map<string, {input: number, output: number}>
  updatedAt: timestamp

config/publicPricing            # client-readable mirror, no multiplier visible
  costs: map<string, number>    # final coin price per action (already includes markup)
  updatedAt: timestamp
```

**Key invariant:** the multiplier lives **only** in `config/pricing`. `config/publicPricing` is the post-multiplier output the client sees. A Cloud Function (`recomputePublicPricing`) is the only writer for both; it runs on `config/pricing` write.

---

## 3. Cloud Functions (new `functions/` directory)

Stack: Node 20 + TypeScript + Firebase Functions v2 (`onCall` for callable, `onRequest` for the proxy that needs raw streaming).

```
functions/
  src/
    geminiProxy.ts        # existing endpoint, enforce coins
    verifyPurchase.ts     # existing endpoint, harden
    recomputePublicPricing.ts
    releaseStaleReservations.ts   # scheduled, every 5 min
    lib/
      pricing.ts          # estimateCost(), actualCost()
      reservations.ts     # reserve(), commit(), release()
      ledger.ts           # append()
      playValidator.ts    # Google Play Developer API client
```

### 3.1 `geminiProxy` (replaces today's pass-through)

Signature (kept HTTP for streaming compat with current client):

```
POST /geminiProxy
  Authorization: Bearer <firebase-id-token>
  X-AI-Action: remove_background | classify_clothing | try_on_outfit | ...
  X-Gemini-Model: gemini-3.1-flash-image-preview | ...
  X-Request-Id: <uuid>       # NEW — client-generated, used as reservation key
  Body: <Gemini request JSON, unchanged>
```

Flow:

1. Verify Firebase ID token → `uid`.
2. Load `config/pricing` (cached in-memory for 60s).
3. `estimated = pricing.costs[action]` (already post-multiplier; this is the *fixed conservative* price per §1 in the conversation).
4. **Atomic reserve** in a Firestore transaction:
   - Read `users/{uid}.credits`.
   - If `credits < estimated` → return `402 Payment Required` with `{ needed, have }`.
   - Decrement `credits` by `estimated`, write `users/{uid}/reservations/{X-Request-Id}` with `expiresAt = now + 5min`.
5. Call Gemini with the server-held API key (Secret Manager, see §7).
6. On Gemini response:
   - Parse `usageMetadata.{promptTokenCount, candidatesTokenCount}`.
   - `actual = pricing.actualCost(model, inputTokens, outputTokens) * multiplier`, **rounded up to integer coins, capped at `estimated`** (we never charge more than reserved — variance is on us, matches the "conservative fixed" decision).
   - Transaction: delete reservation, refund `(estimated - actual)` to `credits`, append `ledger` row with both numbers.
7. Return Gemini response body verbatim to the client.
8. On Gemini failure: transaction releases the full reservation, ledger row `type: "reservation_release"`.

Idempotency: if `X-Request-Id` already has a non-expired reservation, return `409 Conflict`. Client must generate a fresh UUID per call.

### 3.2 `verifyPurchase` (harden existing)

1. Verify Firebase ID token → `uid`.
2. Call `androidpublisher.purchases.products.get(packageName, productId, purchaseToken)` with a service account.
3. Reject if `purchaseState != 0` (purchased), `consumptionState != 0` (already consumed), or `orderId` already in `ledger`.
4. Transaction: increment `users/{uid}.credits` by `CREDITS_PER_PACK[productId]`, append `ledger` row `{ type: "purchase", productId, purchaseToken, orderId, delta }`.
5. Acknowledge + consume the purchase via Play API (so it's reusable).
6. Return `{ creditsAdded, newBalance }`.

### 3.3 `recomputePublicPricing` (Firestore trigger on `config/pricing` write)

Computes `publicPricing.costs[action] = ceil(rawCost[action] * multiplier)` and writes. This is the single source of truth shipped to clients.

### 3.4 `releaseStaleReservations` (Cloud Scheduler, every 5 min)

Query `collectionGroup('reservations').where('expiresAt', '<', now)`, transactionally refund + delete + ledger.

---

## 4. Client changes

### 4.1 New: `gemini/PricingClient.kt`

- Listens to `config/publicPricing` (Firestore snapshot), caches `Map<String, Int>` in memory + DataStore for offline.
- `fun coinCost(action: GeminiAction): Int?` — null if action not in table.
- `fun coinCostBulk(items: List<GeminiAction>): Int`.
- Falls back to the constants in `CreditPack.kt` (which become **defaults only**) if Firestore listener has never resolved.

### 4.2 `billing/CreditPack.kt` — demote constants to fallbacks

Add a comment that `COST_*` are last-resort defaults; runtime authority is `PricingClient`. Keep the values in sync as a safety net (offline first-launch).

### 4.3 `gemini/GeminiRepository.kt` — add request ID + 402 handling

- Generate `X-Request-Id = UUID.randomUUID().toString()` in `buildRequest` when in proxy mode.
- Wrap callers to detect HTTP 402 → emit a typed `InsufficientCreditsException(needed, have)` → caller routes to `BuyCreditsScreen` with the deficit pre-filled.
- Existing `null`-on-failure contract preserved for non-402 errors.

### 4.4 New: `ui/CostBadge.kt` (in an existing UI package — likely `billing/` since it reads `CreditsViewModel`)

```kotlin
@Composable
fun CostBadge(action: GeminiAction, modifier: Modifier = Modifier)
// Renders one of:
//   "🪙 5"          when in managed/proxy mode (real coin price)
//   "~3k tokens"    when in BYOK mode (estimate from PricingClient.tokenEstimate)
//   ""              hidden when neither key nor proxy configured
```

Place on **every** AI-triggering button:
- `wardrobe/CaptureScreen` — capture / bulk re-process
- `wardrobe/UrlImportPicker` — import
- `wardrobe/WardrobeScreen` — auto-tag, classify, re-segment
- `outfit/PredictionSetupScreen` — predict
- `outfit/OutfitComposerScreen` — AI suggest
- `tryon/TryOnScreen` — generate
- `shopping/ShoppingHelperScreen` — find-by-photo, similarity
- Bulk-action confirm dialogs — show `bulkCost(...)` total before confirming.

**Confirm-before-spend rule:** if `coinCost ≥ 20` OR the action is bulk over ≥ 5 items, show an `AlertDialog` with `"This will use X coins. Continue?"` and a balance line.

### 4.5 `settings/SettingsScreen.kt`

Already references credits — add a "How coins work" link, current balance, and a small note: "BYOK mode uses your own Gemini key — no coins are spent." Toggle/UI for switching between BYOK and managed mode if not already present (gate on whether `PROXY_BASE_URL` is set).

### 4.6 Strings

Every new user-facing string into `res/values/strings.xml` and all 30 `values-*/strings.xml` mirrors in the same change (per CLAUDE.md mandatory rule). New keys:
- `cost_badge_coins`, `cost_badge_tokens_estimate`
- `insufficient_credits_title`, `insufficient_credits_body`
- `confirm_spend_title`, `confirm_spend_body`
- `coins_balance_label`, `coins_byok_no_spend_note`

---

## 5. Pricing table — initial values

> **SUPERSEDED (2026-05-30) by § 12.** This original "1 coin ≈ $0.001" table predates the real EUR cost model — it badly under-prices image-generation actions (output is €51.303/M, ~50× text). The live prices are now derived in § 12 for a 100% after-tax profit. Table kept for history only.

Per the "conservative fixed, 2x markup" decision. Base costs are the *real* Gemini cost in coins where 1 coin ≈ $0.001 (chosen so `credits_100 ≈ $1` after markup). Tune in Firestore without a release.

| Action                 | Raw cost (coins) | Public price (×2) |
| ---------------------- | ---------------- | ----------------- |
| `remove_background`    | 3                | 6                 |
| `classify_clothing`    | 1                | 2                 |
| `text_prompt`          | 1                | 2                 |
| `style_prediction`     | 1                | 2                 |
| `trend_lookup`         | 1                | 2                 |
| `try_on_outfit`        | 4                | 8                 |
| `outfit_suggestion`    | 2                | 4                 |

Values land in `config/pricing` on first deploy via a one-shot admin script; `recomputePublicPricing` mirrors them.

---

## 6. Firestore security rules

```
match /users/{uid} {
  allow read: if request.auth.uid == uid;
  allow write: if false;                    // server-only
  match /ledger/{id} {
    allow read: if request.auth.uid == uid;
    allow write: if false;
  }
  match /reservations/{id} {
    allow read, write: if false;
  }
}
match /config/pricing {
  allow read, write: if false;              // multiplier never reaches client
}
match /config/publicPricing {
  allow read: if request.auth != null;
  allow write: if false;
}
```

The multiplier is enforced by **both** layers: rules block reads, and `recomputePublicPricing` is the only writer to `publicPricing`.

---

## 7. Secrets & service accounts

- **Gemini API key (server)** — Secret Manager `GEMINI_API_KEY`, bound to `geminiProxy` only.
- **Google Play Developer API** — service account JSON in Secret Manager `PLAY_DEVELOPER_SA`, bound to `verifyPurchase`. Service account email added as a user in Play Console with "View financial data" + "Manage orders" permissions on the app.
- **Firebase Admin** — implicit (functions run as the project SA).
- `local.properties` keeps `firebase.proxy.url` for dev; nothing else changes client-side.

---

## 8. Telemetry

Add to `util/Analytics.kt`:
- `coin_purchase_succeeded { productId, credits }`
- `coin_purchase_failed { productId, reason }`
- `coin_spend { action, estimated, actual }`
- `coin_insufficient { action, needed, have }` — drives "should we offer bigger packs?"
- `byok_call { action }` (no coin info)

Server-side: log to Cloud Logging with `uid` + `action` for cost reconciliation against Gemini billing.

---

## 9. Rollout

1. **Functions repo** — scaffold `functions/`, deploy `recomputePublicPricing` + seed `config/pricing`.
2. **Harden `verifyPurchase`** — add Play API validation, ledger, idempotency. Existing client keeps working unchanged.
3. **Harden `geminiProxy`** — reservation + commit flow. Add `X-Request-Id` to client. Coordinate deploy so server tolerates missing header for one release (logs only).
4. **PricingClient + CostBadge** — ship behind a Remote Config flag `cost_badges_enabled` so it can be turned on without a release if numbers look off.
5. **402 handling + InsufficientCreditsException** — wire into all callers.
6. **Confirm-before-spend dialogs** — bulk paths first (highest blast radius), then ≥20-coin singletons.
7. **Settings copy + strings in 30 locales**.
8. **`versionCode` bump + release notes** (per CLAUDE.md release process).

---

## 10. Open questions to resolve before coding

1. Coin pack pricing: $0.99 / $4.49 / $14.99 currently implied by 100/500/2000 + 2x markup — confirm Play Console matches.
2. Free starter credits on first sign-in? (Recommend yes — 20 coins, granted by Cloud Function on `auth.user().onCreate`.)
3. Refund policy for failed Gemini calls already handled (reservation release). What about *bad* outputs the user rejects? Recommend: no refund (UX-level retry is on us, but cost is real).
4. Should `try_on_outfit` price scale with output image count? Currently fixed.
5. Do we want a "burn rate" indicator on the Insights tab — "at current usage, 200 coins lasts ~3 weeks"? Easy add given the ledger.

---

## 11. Files touched (forecast)

**New:**
- `functions/**` (whole tree)
- `app/src/main/java/com/librelookai/gemini/PricingClient.kt`
- `app/src/main/java/com/librelookai/billing/CostBadge.kt`
- `app/src/main/java/com/librelookai/billing/InsufficientCreditsException.kt`

**Modified:**
- `app/src/main/java/com/librelookai/gemini/GeminiRepository.kt` (request ID, 402 handling)
- `app/src/main/java/com/librelookai/billing/CreditPack.kt` (demote to defaults)
- `app/src/main/java/com/librelookai/billing/CreditRepository.kt` (read from PricingClient, surface ledger)
- All AI-trigger screens listed in §4.4
- `app/src/main/res/values/strings.xml` + 30 `values-*/strings.xml`
- `app/src/main/java/com/librelookai/util/Analytics.kt`
- `CLAUDE.md` / `CLAUDE_ARCHIVE.md` — new "Monetization" section pointing here, then archived once stable.
- `versionCode` + release notes.

---

## 12. Gemini pricing model & unit economics

Consolidates the former `plan/OPTIONS.md` (pricing validation, the EUR-native
split refactor, and the context-caching analysis) plus the full unit-economics
that drive the credit prices. **This is the source of truth for pricing.**

### 12.1 EUR-native model pricing (landed)

Validated the local model pricing against the EUR list prices on the GCP
billing account (`plan/Pricing for Fuer Soeren - LibreLookAi.csv`). The old
USD model was wrong in four structural ways, the worst a **10× overcharge** on
`gemini-3.1-flash-image-preview` text output. Fixed by going **EUR-native**
(Google bills the EU account directly in EUR — no USD ground truth, no FX) with
a **per-token-type split** so figures match the bill:

| | text in | image in | cached text in | text out | image out |
|---|---:|---:|---:|---:|---:|
| `gemini-3-flash-preview` | 0.4275 | 0.4275 | 0.0428 | 2.5651 | 2.5651 |
| `gemini-3.1-flash-image-preview` | 0.4275 | 0.4275 | — | 2.5651 | **51.303** |

(€ per 1M tokens.) The four structural fixes: (1) split text-out vs image-out —
they're 20× apart on the image model; (2) split text-in vs image-in (equal
today, future-proofed); (3) added the cached-input tier (−90%); (4) dropped
`fxUsdToEur`. Lives in `config/modelPricing` (`seed.ts`) → `ModelPricingClient`
→ `GeminiPricing.eurFor` → `TokenUsage`/`UsageSection`/`CostBadge`, all EUR.
`isImageOutputModel` (name contains "image") selects the image-out rate.

### 12.2 Per-item & per-action token assumptions

A wardrobe item is serialized as JSON (`OutfitsPrompts.kt:50`), ~141 chars,
dominated by a ~33-char random Drive ID that tokenizes poorly ⇒ **≈ 50 tokens
per item**. A 1000-item wardrobe ≈ **50,000 input tokens** (+ ~800 base prompt).
Only **style prediction (`generateText`)** and **outfit suggestion/compose
(`outfitSuggestion`)** embed the full wardrobe; bg-removal/classify/try-on send
only the relevant item image(s); trends sends none. Per-action token estimates
are in `CostBadge.kt → estimateTokens`.

### 12.3 Real Gemini cost per action (1000-item wardrobe)

Rates: input €0.4275/M, text-out €2.5651/M, image-out €51.303/M.

| Action | Model | Input tok | Output tok | **Gemini cost €** |
|---|---|---:|---:|---:|
| Remove background | image | 700 | 1,290 (img) | **0.0665** |
| Try-on outfit | image | 1,200 | 1,290 (img) | **0.0667** |
| Style prediction | text | 50,800 | 350 (txt) | **0.0226** |
| Outfit suggestion | text | 50,800 | 400 (txt) | **0.0227** |
| Classify / auto-tag | text | 1,000 | 400 (txt) | **0.0015** |
| Trend lookup | text | 200 | 600 (txt) | **0.0016** |

Image output (~1,290 tokens ≈ €0.066/image) dominates everything — ~45× a full
text classification. Processing a whole 1000-item wardrobe: bg-removal ×1000 =
€66.48, classify ×1000 = €1.45.

### 12.4 The revenue stack — what the developer actually keeps

Every €0.01 credit (assumes 100-pack ≈ €1) passes through three layers, all at
their German/maximum values:

| Slice | € | Cumulative |
|---|---:|---:|
| User pays (gross, incl. VAT) | 0.010000 | 100% |
| − German VAT (19% → 19/119) | −0.001597 | 84.0% |
| − Google Play fee (30% of ex-VAT) | −0.002521 | 58.8% |
| **= Developer net revenue** | **0.005882** | **58.8%** |
| − Income tax (47.475% of *profit*) | (on margin) | — |
| **Take-home on a zero-cost credit** | **0.003089** | **30.9%** |

Notes: VAT and the Play fee come off **revenue**; income tax (top German rate
45% + 5.5% soli = **47.475%**) hits **profit** only — so it halves winners and
does nothing for loss-making actions (a loss only yields a ~47% shield against
other profit). Play fee = 30% (max tier); the first-$1M/yr **15%** tier nets
€0.007143/credit (71.4%) instead.

### 12.5 Repricing for 100% after-tax profit (landed)

Target: **after-tax profit = 100% of Gemini cost** (you double the cost after
all three taxes). Solving `(N · €0.005882 − C) · 0.52525 = C` for credits `N`:

  **N (public credits) ≈ 493.7 × C(€)**

With `multiplier = 2`, `raw = N/2`. New `DEFAULT_PRICING.costs` / `CreditPacks`:

| Action | Gemini C € | raw | **public credits** | (was) | after-tax margin |
|---|---:|---:|---:|---:|---:|
| removeBackground | 0.0665 | 17 | **34** | 6 | ~105% |
| tryOnOutfit | 0.0667 | 17 | **34** | 8 | ~105% |
| generateText (style/text) | 0.0226 | 6 | **12** | 2 | ~111% |
| outfitSuggestion | 0.0227 | 6 | **12** | 4 | ~110% |
| classifyClothing | 0.0015 | 1 | **2** | 2 | ≫100% (floored) |
| searchFashionTrends | 0.0016 | 1 | **2** | 2 | ≫100% (floored) |

classify/trends can't go below 1 raw / 2 public, so they overshoot. The
wardrobe-context actions are priced for a 1000-item wardrobe — smaller closets
yield higher margin (fixed per-action pricing can't track wardrobe size).
Source: `firebase/functions/src/pricing.ts` + `billing/CreditPack.kt`; push with
`scripts/seed_pricing.sh`.

> **Caveat:** image actions jumped 5–6× (try-on 8→34, bg-removal 6→34). That may
> be too steep for UX even though it's the break-even reality — consider steering
> image-heavy paths to **BYOK**, or accepting a lower target margin. The
> derivation here makes the trade-off explicit and easy to re-tune in Firestore.

### 12.6 Context caching — analyzed, NOT built

Could the wardrobe + profile live in a Gemini context cache to cheapen repeat
queries? Decided **no** for now:
- **Explicit cache** bills cached input at €0.0428/M (−90%) **but** adds storage
  at ~$1.00/M-tokens/hour for the cache's whole TTL, and is **immutable except
  TTL** — any wardrobe edit means delete + recreate the whole cache (no per-item
  add/remove). One call references one cache, which must be the prefix.
- **Implicit cache** (auto, no storage cost) gives Gemini-3 hits but, per current
  docs, **no cost discount yet** (2.5-only).
- **Blocker:** `gemini-3.1-flash-image-preview` (try-on, bg-removal — the
  expensive paths) **can't cache at all** (media-gen exclusion). Caching only
  helps text-model calls, most of which are per-item today.
- **Break-even** (≈42k-token closet): storage ≈ €0.039/hr vs ≈€0.016 saved per
  whole-closet query ⇒ need ≥3 such queries within the cache hour. `cachedTextInPerM`
  is recorded in the schema for the day this changes; nothing creates a cache.
