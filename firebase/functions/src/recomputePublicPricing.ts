import * as admin from "firebase-admin";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { logger } from "firebase-functions/v2";
import {
  computePublicCosts,
  computePublicPerItemCosts,
  DEFAULT_PRICING,
  invalidatePricingCache,
  PricingConfig,
} from "./pricing";

/**
 * Triggered whenever an admin edits config/pricing in the Firebase console.
 * Recomputes config/publicPricing (which holds the post-multiplier coin price
 * the client sees) so the client never has to know about the multiplier.
 *
 * Also invalidates the in-process pricing cache used by geminiProxy.
 */
export const recomputePublicPricing = onDocumentWritten(
  "config/pricing",
  async (event) => {
    const db = admin.firestore();
    const after = event.data?.after?.data() as Partial<PricingConfig> | undefined;

    const cfg: PricingConfig = {
      multiplier: typeof after?.multiplier === "number" ? after!.multiplier : DEFAULT_PRICING.multiplier,
      costs: { ...DEFAULT_PRICING.costs, ...(after?.costs ?? {}) },
      perItemCosts: { ...(DEFAULT_PRICING.perItemCosts ?? {}), ...(after?.perItemCosts ?? {}) },
    };

    const publicCosts = computePublicCosts(cfg);
    const publicPerItemCosts = computePublicPerItemCosts(cfg);
    await db.collection("config").doc("publicPricing").set({
      costs: publicCosts,
      perItemCosts: publicPerItemCosts,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    invalidatePricingCache();
    logger.info("publicPricing recomputed", { actions: Object.keys(publicCosts).length });
  },
);
