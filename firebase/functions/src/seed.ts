/**
 * Interactive seed for Firestore config docs.
 *
 * Shows the live `config/pricing` + `config/modelPricing` against the local
 * source-of-truth values, prints a diff, and prompts before writing. Re-runs
 * are safe — writes only happen on explicit "y" or `--yes`.
 *
 * Usage (preferred via wrapper):
 *
 *   scripts/seed_pricing.sh                    # interactive
 *   scripts/seed_pricing.sh --yes              # CI / unattended
 *
 * Direct run (advanced):
 *
 *   cd firebase/functions
 *   GOOGLE_APPLICATION_CREDENTIALS=/path/to/sa.json \
 *     GOOGLE_CLOUD_PROJECT=librelookai-firebase \
 *     npm run build && node lib/seed.js [--yes]
 *
 * Excluded from the deployed bundle via firebase.json (lib/seed.js is only
 * run manually with `node` — never by the Functions runtime).
 */

import * as admin from "firebase-admin";
import * as readline from "readline";
import { DEFAULT_PRICING } from "./pricing";

// Local source-of-truth for config/modelPricing. Edited when Google publishes
// new Gemini list prices.
const LOCAL_MODEL_PRICING = {
  fxUsdToEur: 0.92,
  fallback: { inUsdPerM: 0.30, outUsdPerM: 2.50 },
  models: {
    "gemini-3-flash-preview": { inUsdPerM: 0.30, outUsdPerM: 2.50 },
    "gemini-3.1-flash-image-preview": { inUsdPerM: 0.30, outUsdPerM: 30.00 },
  },
};

// ---------------------- ANSI helpers (no deps) ----------------------
const useColor = process.stdout.isTTY;
const c = (code: string, s: string) => useColor ? `\x1b[${code}m${s}\x1b[0m` : s;
const bold = (s: string) => c("1", s);
const dim = (s: string) => c("2", s);
const green = (s: string) => c("32", s);
const red = (s: string) => c("31", s);
const yellow = (s: string) => c("33", s);

// ---------------------- Diff ----------------------
type Json = unknown;

function isObject(x: Json): x is Record<string, Json> {
  return x !== null && typeof x === "object" && !Array.isArray(x);
}

/** Recursive `current → desired` value diff. Returns a flat list of "path: old → new" lines. */
function diffLines(current: Json, desired: Json, path = ""): string[] {
  if (JSON.stringify(current) === JSON.stringify(desired)) return [];
  if (!isObject(current) || !isObject(desired)) {
    return [`${path}: ${red(JSON.stringify(current))} → ${green(JSON.stringify(desired))}`];
  }
  const keys = Array.from(new Set([...Object.keys(current), ...Object.keys(desired)])).sort();
  const out: string[] = [];
  for (const k of keys) {
    const childPath = path ? `${path}.${k}` : k;
    if (!(k in current)) out.push(`${green("+")} ${childPath}: ${green(JSON.stringify(desired[k]))}`);
    else if (!(k in desired)) out.push(`${red("-")} ${childPath}: ${red(JSON.stringify(current[k]))}`);
    else out.push(...diffLines(current[k], desired[k], childPath));
  }
  return out;
}

function printBlock(title: string, value: Json) {
  console.log(bold(title));
  console.log(JSON.stringify(value, null, 2).split("\n").map((l) => "  " + l).join("\n"));
}

// ---------------------- Prompt ----------------------
function confirm(question: string): Promise<boolean> {
  return new Promise((resolve) => {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
    rl.question(`${question} `, (ans) => {
      rl.close();
      resolve(/^y(es)?$/i.test(ans.trim()));
    });
  });
}

// ---------------------- Main ----------------------
async function main() {
  const yes = process.argv.includes("--yes") || process.argv.includes("-y");

  admin.initializeApp();
  const db = admin.firestore();

  const desiredPricing = {
    multiplier: DEFAULT_PRICING.multiplier,
    costs: DEFAULT_PRICING.costs,
    perItemCosts: DEFAULT_PRICING.perItemCosts ?? {},
  };

  // ---- config/pricing ----
  console.log(bold("\n── config/pricing ──"));
  const livePricingSnap = await db.collection("config").doc("pricing").get();
  const livePricing: Json = livePricingSnap.exists
    ? stripServerTimestamps(livePricingSnap.data())
    : null;
  printBlock("current (Firestore):", livePricing ?? dim("<missing>"));
  printBlock("desired (DEFAULT_PRICING):", desiredPricing);
  const pricingDiff = livePricing
    ? diffLines(livePricing, desiredPricing)
    : Object.keys(desiredPricing).map((k) => `${green("+")} ${k}: ${green(JSON.stringify((desiredPricing as any)[k]))}`);
  printDiff("diff:", pricingDiff);

  // ---- config/modelPricing ----
  console.log(bold("\n── config/modelPricing ──"));
  const liveModelSnap = await db.collection("config").doc("modelPricing").get();
  const liveModel: Json = liveModelSnap.exists
    ? stripServerTimestamps(liveModelSnap.data())
    : null;
  printBlock("current (Firestore):", liveModel ?? dim("<missing>"));
  printBlock("desired (LOCAL_MODEL_PRICING):", LOCAL_MODEL_PRICING);
  const modelDiff = liveModel
    ? diffLines(liveModel, LOCAL_MODEL_PRICING)
    : Object.keys(LOCAL_MODEL_PRICING).map((k) => `${green("+")} ${k}: ${green(JSON.stringify((LOCAL_MODEL_PRICING as any)[k]))}`);
  printDiff("diff:", modelDiff);

  // ---- Apply? ----
  const totalChanges = pricingDiff.length + modelDiff.length;
  console.log();
  if (totalChanges === 0) {
    console.log(green("✓ Firestore is already in sync. Nothing to do."));
    return;
  }
  console.log(yellow(`${totalChanges} change(s) pending.`));

  if (!yes) {
    const ok = await confirm("Write desired values to Firestore? [y/N]");
    if (!ok) {
      console.log(dim("Aborted. No writes performed."));
      return;
    }
  } else {
    console.log(dim("--yes given; applying without prompt."));
  }

  await db.collection("config").doc("pricing").set({
    ...desiredPricing,
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  });
  console.log(green("✓ Wrote config/pricing"));

  await db.collection("config").doc("modelPricing").set({
    ...LOCAL_MODEL_PRICING,
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  });
  console.log(green("✓ Wrote config/modelPricing"));

  console.log(dim("The recomputePublicPricing trigger will mirror to config/publicPricing."));
}

function printDiff(title: string, lines: string[]) {
  console.log(bold(title));
  if (lines.length === 0) console.log("  " + dim("(no changes)"));
  else lines.forEach((l) => console.log("  " + l));
}

/** Remove server timestamp Firestore objects from a doc snapshot so diffs are clean. */
function stripServerTimestamps(data: FirebaseFirestore.DocumentData | undefined): Json {
  if (!data) return null;
  const out: Record<string, Json> = {};
  for (const [k, v] of Object.entries(data)) {
    if (k === "updatedAt") continue;
    out[k] = v as Json;
  }
  return out;
}

main().catch((e) => {
  // eslint-disable-next-line no-console
  console.error(e);
  process.exit(1);
});
