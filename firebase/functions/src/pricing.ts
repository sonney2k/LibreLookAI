import * as admin from "firebase-admin";

/**
 * Pricing lives in Firestore so we can tune it without redeploying.
 *
 *   config/pricing         { multiplier: number, costs: { action: rawCost } }   server-only read
 *   config/publicPricing   { costs: { action: finalCoins } }                    auth-readable
 *
 * The multiplier is the markup we add on top of raw Gemini cost to stay
 * sustainable. It must never leave the server — Firestore rules deny client
 * reads on /config/pricing, and recomputePublicPricing is the only writer to
 * /config/publicPricing (which holds the already-multiplied final price).
 *
 * Defaults below are used when:
 *  - Firestore has no /config/pricing doc yet (first deploy), or
 *  - the cache is being warmed for the first time and we want a synchronous
 *    fallback so a Gemini call never blocks waiting for config.
 */

export type Action =
  | "removeBackground"
  | "classifyClothing"
  | "generateText"
  | "searchFashionTrends"
  | "tryOnOutfit"
  | "outfitSuggestion";

export interface PricingConfig {
  multiplier: number;
  /** Raw cost per action, BEFORE multiplier. */
  costs: Record<string, number>;
}

/**
 * Raw (pre-multiplier) defaults. Halve of the final public prices that
 * existed in v1 of the credit system, so multiplier=2 reproduces them.
 */
export const DEFAULT_PRICING: PricingConfig = {
  multiplier: 2,
  costs: {
    removeBackground: 3,
    classifyClothing: 1,
    generateText: 1,
    searchFashionTrends: 1,
    tryOnOutfit: 4,
    outfitSuggestion: 2,
  },
};

/** Final per-action coin cost (post-multiplier, rounded up). */
export function publicCostFor(cfg: PricingConfig, action: string): number {
  const raw = cfg.costs[action];
  if (raw == null) {
    // Unknown action — use generateText as the conservative default so we
    // never undercharge a new action that someone forgot to register.
    return Math.ceil((cfg.costs.generateText ?? 1) * cfg.multiplier);
  }
  return Math.ceil(raw * cfg.multiplier);
}

export function computePublicCosts(cfg: PricingConfig): Record<string, number> {
  const out: Record<string, number> = {};
  for (const [action, raw] of Object.entries(cfg.costs)) {
    out[action] = Math.ceil(raw * cfg.multiplier);
  }
  return out;
}

// ---------- Server-side in-memory cache (60s TTL) ----------

let cached: { cfg: PricingConfig; expiresAt: number } | null = null;
const CACHE_TTL_MS = 60_000;

/**
 * Load /config/pricing with a 60s in-memory cache. On any failure, returns
 * DEFAULT_PRICING so a transient Firestore hiccup can never break Gemini calls.
 */
export async function loadPricing(db: admin.firestore.Firestore): Promise<PricingConfig> {
  const now = Date.now();
  if (cached && cached.expiresAt > now) return cached.cfg;
  try {
    const snap = await db.collection("config").doc("pricing").get();
    if (!snap.exists) {
      cached = { cfg: DEFAULT_PRICING, expiresAt: now + CACHE_TTL_MS };
      return DEFAULT_PRICING;
    }
    const data = snap.data() as Partial<PricingConfig>;
    const cfg: PricingConfig = {
      multiplier: typeof data.multiplier === "number" ? data.multiplier : DEFAULT_PRICING.multiplier,
      costs: {
        ...DEFAULT_PRICING.costs,
        ...(data.costs ?? {}),
      },
    };
    cached = { cfg, expiresAt: now + CACHE_TTL_MS };
    return cfg;
  } catch {
    return DEFAULT_PRICING;
  }
}

/** Test/admin hook — clears the in-memory cache so the next read hits Firestore. */
export function invalidatePricingCache(): void {
  cached = null;
}
