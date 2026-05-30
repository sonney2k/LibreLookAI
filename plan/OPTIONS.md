# Gemini pricing — validation & options

> **STATUS (2026-05-30): Option B LANDED, EUR-native.** `config/modelPricing`
> (`seed.ts`), `ModelPricingClient`, `TokenUsage`, `UsageSection`, and
> `CostBadge` now use per-token-type EUR rates (text/image/cached in,
> text/image out) and the whole cost path dropped USD + `fxUsdToEur`. Caching
> (Option B+) was **deliberately not built** — kept below as the decision
> record. This file is retained for rationale, not as a to-do.

Validated `LOCAL_MODEL_PRICING` in `firebase/functions/src/seed.ts` against
the EUR list prices in `plan/Pricing for Fuer Soeren - LibreLookAi.csv`
(downloaded from GCP for the user's billing account). The local model does
**not** match in four structural ways plus several numerical mismatches.
The biggest single issue is dangerous: a **10× overcharge** on
`gemini-3.1-flash-image-preview` text output.

## Side-by-side (EUR per 1M tokens)

Local table is denominated in USD; converted at the script's
`fxUsdToEur = 0.92` to put both on the same scale.

| Model | Token type | Local (€/M, after FX) | Actual (€/M, CSV) | Mismatch |
|---|---|---|---|---|
| `gemini-3-flash-preview` | text in | 0.276 | **0.4275** | undercharge **−35%** |
| `gemini-3-flash-preview` | text out | 2.300 | **2.5651** | undercharge **−10%** |
| `gemini-3-flash-preview` | image in | (collapsed to text in) 0.276 | **0.4275** | undercharge **−35%** |
| `gemini-3-flash-preview` | cached text in | (not modeled) | **0.0428** | structural gap |
| `gemini-3.1-flash-image-preview` | text in | 0.276 | **0.4275** | undercharge **−35%** |
| `gemini-3.1-flash-image-preview` | image in | (collapsed to text in) 0.276 | **0.4275** | undercharge **−35%** |
| `gemini-3.1-flash-image-preview` | **text out** | **27.60** | **2.5651** | **overcharge ×10.8** ⚠️ |
| `gemini-3.1-flash-image-preview` | **image out** | **27.60** | **51.303** | undercharge **−46%** ⚠️ |

## The four structural problems

1. **Output is one rate per model, but Google bills text-out and image-out
   separately.** For the image-preview model the two are 20× apart (€2.57
   vs €51.30) — collapsing them into a single `outUsdPerM = $30` produces
   both the 10× overcharge on its text output and the −46% undercharge on
   its image output. For pure image generation we *underbill*; for any
   text emitted by the same model (e.g. AI tags returned alongside an
   image) we *grossly overbill*.
2. **Input is one rate per model, but Google bills text-in and image-in
   separately.** Today they happen to be the same number (€0.4275/M) so no
   economic damage right now — but the model can't represent it if Google
   diverges.
3. **No cached-input tier.** Google charges only €0.0428/M for cached
   text input — a **−90%** discount the local model doesn't claim. If our
   try-on / classify prompts have a cacheable preamble (system prompt,
   taxonomy, few-shot examples), real cost is much lower than the local
   model assumes.
4. **`fxUsdToEur = 0.92` is the wrong layer.** Google bills the EU account
   directly in EUR from the list above — they're not converting from a USD
   ground truth via spot FX. Round-tripping through USD just bakes in a
   ~5% drift any time the FX rate is adjusted. Either store EUR-native
   rates and drop `fxUsdToEur`, or store USD and rely on Google's own FX
   (but then numbers won't match the bill).

## Does this matter for `config/pricing` (coin charges)?

Not directly. `config/pricing` is the **fixed per-action coin price** with
a 2× markup buffer. The Gemini USD/EUR cost only feeds into telemetry and
$-attribution. So this isn't bleeding money — but any dashboards and any
"you've spent ~€X" UI driven from `modelPricing` are off by the
percentages above. Worst case: a `tryOnOutfit` call that emits both an
image and a small text caption will show a wildly inflated text-cost line
item.

## Option A — minimal: fix the numbers, keep the shape

Quick win, mitigates the image-model text-out overcharge. The
cost-attribution side stays approximate.

```ts
const LOCAL_MODEL_PRICING = {
  fxUsdToEur: 1.0,                                  // drop FX — values are now EUR-native
  fallback: { inEurPerM: 0.4275, outEurPerM: 2.5651 },
  models: {
    "gemini-3-flash-preview":          { inEurPerM: 0.4275, outEurPerM: 2.5651 },
    "gemini-3.1-flash-image-preview":  { inEurPerM: 0.4275, outEurPerM: 51.303 },
  },
};
```

Trade-off: image-model **text** output is still overcharged ~20×, but its
share of total output tokens is tiny in image-gen calls.

**Effort:** one-line drop-in. No call-site changes.

## Option B — correct: split text/image and add cache

Matches the bill exactly.

```ts
const LOCAL_MODEL_PRICING = {
  currency: "EUR",
  // Explicit-cache storage: €/M cached tokens *per hour* the cache is alive
  // (Google bills ~$1.00/M/hr; EUR-native figure below). Accrues regardless
  // of query volume — see "Option B+" for the break-even.
  cacheStoragePerMPerHour: 0.92,
  fallback: {
    textInPerM: 0.4275, imageInPerM: 0.4275, cachedTextInPerM: 0.0428,
    textOutPerM: 2.5651, imageOutPerM: 2.5651,
  },
  models: {
    "gemini-3-flash-preview": {
      textInPerM: 0.4275, imageInPerM: 0.4275, cachedTextInPerM: 0.0428,
      textOutPerM: 2.5651, imageOutPerM: 2.5651,  // no image-out for this model in practice
    },
    "gemini-3.1-flash-image-preview": {
      textInPerM: 0.4275, imageInPerM: 0.4275,    // cachedTextInPerM omitted:
      textOutPerM: 2.5651, imageOutPerM: 51.303,  // media-gen models can't cache
    },
  },
};
```

Requires the client/Functions $-attribution code that reads this doc to
know about the new field names. The Gemini API response's `usageMetadata`
already separates `promptTokenCount` / `cachedContentTokenCount` /
`candidatesTokenCount` and (for image preview) image-token counters, so
the data is there.

**Effort:** schema change + every reader of `modelPricing` updated
(android `TokenUsageRepository` cost-attribution, any cost dashboards).
Strings/locales unaffected.

## Option B+ — cache the wardrobe + profile

Idea: hold the user's whole wardrobe (and profile try-on photos) in a
Gemini context cache so every subsequent query is cheaper/faster. Worth
doing **only** for the text model and **only** if we add whole-closet
queries. Mechanics gathered from the current Gemini docs:

### How Gemini caching actually works
- **Implicit caching** — on by default for Gemini 3 + 2.5, prefix-matched,
  **no storage cost**. But the cost discount currently lands **only on 2.5
  models**; our text model `gemini-3-flash-preview` gets cache *hits* with
  **no billing discount yet**. So implicit caching buys us latency, not money,
  today.
- **Explicit caching** — `POST` a `CachedContent` (model + a *prefix* of
  contents + TTL) → cache name → pass as `cachedContent` on later
  `generateContent`. Cached tokens bill at `cachedTextInPerM` (€0.0428/M,
  ≈ −90%); `usageMetadata.cachedContentTokenCount` reports the hit. This is
  the path that gives the flash model a real discount.

### Hard constraints (answers to the design questions)
1. **Does it cost?** Two costs: (a) **creation** = one full-price input pass
   to ingest the wardrobe (= sending it once); (b) **storage** ≈
   **$1.00/M tokens/hour** (`cacheStoragePerMPerHour` above) for the cache's
   whole TTL, charged whether queried or not.
2. **Incremental add/delete?** **No.** A cache is **immutable except its
   TTL** — no per-item append/remove. Any wardrobe edit ⇒ **delete + recreate
   the whole cache**, re-paying ingest. Don't mutate the referenced image
   objects while a cache points at them (it invalidates the cache).
3. **Multiple caches?** **Yes** — each is its own named resource (per-closet,
   or profile-vs-wardrobe). But **one call references exactly one cache and it
   must be the prefix**, so profile + wardrobe must share a cache when a query
   needs both.
4. **Min size / TTL:** min cacheable input is model-dependent (≈1–4k tokens),
   well under a real wardrobe. TTL default 60 min, freely adjustable.

### The blocker
**`gemini-3.1-flash-image-preview` (try-on, bg-removal) does not support
context caching at all** — media-gen models are excluded. So caching cannot
touch the image pipeline; it only helps text-model calls (tagging, gap
analysis, whole-closet outfit/shopping reasoning). And today most text calls
are **per-item** (full-wardrobe similarity is on-device via
`EmbeddingService`), so a whole-wardrobe cache is dead weight unless we add
queries that reason over the entire closet at once.

### Break-even (≈100-item closet ≈ 42k tokens)
- Storage: 0.042M × €0.92/M/hr ≈ **€0.039/hour** alive.
- Saving per whole-wardrobe query: (0.4275 − 0.0428)/M × 0.042M ≈ **€0.016**.
- ⇒ need **≳3 whole-closet queries within the cache's live hour** to net out
  ahead; a single query then idle loses money on storage.

### Verdict
Don't build explicit wardrobe caching yet — there's no whole-closet
text-query workload to amortise the storage, and the image pipeline (the
expensive part) can't cache anyway. Cheap wins available now: structure
text-model prompts with a **stable prefix** (system prompt + taxonomy +
few-shot first) so implicit caching kicks in for free latency today and free
*cost* savings the moment Gemini 3 implicit discounts arrive. Revisit explicit
caching **if/when** we add a recurring whole-closet text feature (e.g. an
always-on "stylist over your entire wardrobe"); then a per-closet cache,
recreated on wardrobe mutation and TTL-refreshed on use, pays off.

## Recommendation

Land **Option A now** to stop the 10× overcharge surfacing in any
$-attribution UI. Schedule **Option B** for the next time
`TokenUsageRepository` is touched (it'll re-read `usageMetadata` anyway,
so adding cached/image counters is a small extra) — include
`cacheStoragePerMPerHour` so a future cache feature has a rate to bill
against. Treat **Option B+** (explicit wardrobe cache) as deferred until a
whole-closet text workload exists. Once Option B lands, delete this file.
