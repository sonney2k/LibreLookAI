/**
 * One-shot admin script to seed config/pricing in Firestore.
 *
 * Run locally against the live project:
 *
 *   cd firebase/functions
 *   GOOGLE_APPLICATION_CREDENTIALS=/path/to/sa.json \
 *     FIREBASE_PROJECT_ID=librelookai \
 *     npm run build && node lib/seed.js
 *
 * It is safe to re-run: it overwrites config/pricing with the values below.
 * The recomputePublicPricing trigger will mirror these into
 * config/publicPricing automatically.
 *
 * NOTE: this file is intentionally excluded from the deployed bundle via the
 * "functions[].ignore" in firebase.json (lib/seed.js is never invoked by the
 * Functions runtime — it's only run manually with `node`).
 */

import * as admin from "firebase-admin";
import { DEFAULT_PRICING } from "./pricing";

async function main() {
  admin.initializeApp();
  const db = admin.firestore();
  await db.collection("config").doc("pricing").set({
    multiplier: DEFAULT_PRICING.multiplier,
    costs: DEFAULT_PRICING.costs,
    perItemCosts: DEFAULT_PRICING.perItemCosts ?? {},
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  });
  // The onDocumentWritten trigger will fire and populate publicPricing.
  // eslint-disable-next-line no-console
  console.log("Seeded config/pricing:", DEFAULT_PRICING);

  // Per-model Gemini USD rates — published list prices, edited manually when
  // Google updates them. Clients fetch this doc to estimate spend ($ badge)
  // and to snapshot $ at the time of each UsageEvent.
  const modelPricing = {
    fxUsdToEur: 0.92,
    fallback: { inUsdPerM: 0.30, outUsdPerM: 2.50 },
    models: {
      "gemini-3-flash-preview": { inUsdPerM: 0.30, outUsdPerM: 2.50 },
      "gemini-3.1-flash-image-preview": { inUsdPerM: 0.30, outUsdPerM: 30.00 },
    },
  };
  await db.collection("config").doc("modelPricing").set({
    ...modelPricing,
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  });
  // eslint-disable-next-line no-console
  console.log("Seeded config/modelPricing:", modelPricing);
}

main().catch((e) => {
  // eslint-disable-next-line no-console
  console.error(e);
  process.exit(1);
});
