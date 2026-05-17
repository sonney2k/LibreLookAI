import * as admin from "firebase-admin";
import { onRequest } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";
import { logger } from "firebase-functions/v2";
import fetch, { RequestInit } from "node-fetch";

import { loadPricing, publicCostFor } from "./pricing";
import { appendLedger, appendLedgerInTx } from "./ledger";
import { acknowledgePlayPurchase, validatePlayPurchase } from "./play";

admin.initializeApp();
const db = admin.firestore();

const geminiApiKey = defineSecret("GEMINI_API_KEY");

// Re-exported so it deploys alongside the HTTP functions.
export { recomputePublicPricing } from "./recomputePublicPricing";

const GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models";

// Credits awarded per in-app product. Server-authoritative; client values
// in CreditPack.kt must match.
const CREDITS_PER_PACK: Record<string, number> = {
  credits_100: 100,
  credits_500: 500,
  credits_2000: 2000,
};

// Android package name — must match the live app. Used for Play Developer API
// validation. Override via environment variable for staging/test builds.
const ANDROID_PACKAGE_NAME = process.env.ANDROID_PACKAGE_NAME ?? "com.librelookai";

// ---------- Helper: verify Firebase ID token ----------

async function verifyToken(authHeader: string): Promise<string | null> {
  if (!authHeader.startsWith("Bearer ")) return null;
  try {
    const decoded = await admin.auth().verifyIdToken(authHeader.slice(7));
    return decoded.uid;
  } catch {
    return null;
  }
}

// ---------- Gemini proxy ----------
// Receives the exact same JSON body the app would send directly to Gemini.
// Headers from app:
//   Authorization:   Bearer <firebase_id_token>
//   X-AI-Action:     removeBackground | classifyClothing | generateText |
//                    searchFashionTrends | tryOnOutfit | outfitSuggestion
//   X-Gemini-Model:  the Gemini model to route to
//
// Pricing is loaded from config/pricing (with 60s cache + safe defaults).
// On 402, returns JSON { needed, have } so the client can prompt for top-up.

export const geminiProxy = onRequest(
  {
    cors: false,
    memory: "512MiB",
    timeoutSeconds: 540, // background removal can take a while
    secrets: [geminiApiKey],
  },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).json({ error: "Method not allowed" });
      return;
    }

    const uid = await verifyToken(req.headers.authorization ?? "");
    if (!uid) {
      res.status(401).json({ error: "Unauthorized" });
      return;
    }

    const action = (req.headers["x-ai-action"] as string) ?? "generateText";
    const model = (req.headers["x-gemini-model"] as string) ?? "";
    if (!model) {
      res.status(400).json({ error: "Missing X-Gemini-Model header" });
      return;
    }

    const pricing = await loadPricing(db);
    const cost = publicCostFor(pricing, action);
    const userRef = db.collection("users").doc(uid);

    // Deduct credits atomically — fail fast if insufficient. We also write
    // the spend ledger entry inside the same transaction so the ledger and
    // balance can never diverge.
    let balanceAfterCharge = 0;
    try {
      await db.runTransaction(async (tx) => {
        const doc = await tx.get(userRef);
        const credits = doc.exists ? (doc.data()?.credits ?? 0) : 0;
        if (credits < cost) {
          const err = new Error("Insufficient credits") as any;
          err.code = 402;
          err.needed = cost;
          err.have = credits;
          throw err;
        }
        balanceAfterCharge = credits - cost;
        tx.set(userRef, { credits: balanceAfterCharge }, { merge: true });
        appendLedgerInTx(tx, userRef, {
          type: "spend",
          delta: -cost,
          balanceAfter: balanceAfterCharge,
          action,
          model,
        });
      });
    } catch (e: any) {
      if (e?.code === 402) {
        res.status(402).json({ error: "Insufficient credits", needed: e.needed, have: e.have });
      } else {
        logger.error("Charge transaction failed", { uid, action, error: e?.message });
        res.status(500).json({ error: "Charge failed" });
      }
      return;
    }

    // Forward to Gemini
    const geminiUrl = `${GEMINI_BASE}/${model}:generateContent?key=${geminiApiKey.value()}`;
    const bodyStr = typeof req.body === "string" ? req.body : JSON.stringify(req.body);

    try {
      const init: RequestInit = {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: bodyStr,
      };
      const geminiResp = await fetch(geminiUrl, init);
      const responseText = await geminiResp.text();

      if (!geminiResp.ok) {
        // Refund + ledger entry. We're outside the original tx, so re-read
        // balance and write atomically.
        await db.runTransaction(async (tx) => {
          const doc = await tx.get(userRef);
          const current = doc.exists ? (doc.data()?.credits ?? 0) : 0;
          const newBalance = current + cost;
          tx.set(userRef, { credits: newBalance }, { merge: true });
          appendLedgerInTx(tx, userRef, {
            type: "refund",
            delta: cost,
            balanceAfter: newBalance,
            action,
            model,
            reason: `gemini_${geminiResp.status}`,
          });
        });
      }
      res
        .status(geminiResp.status)
        .setHeader("Content-Type", "application/json")
        .send(responseText);
    } catch (e: any) {
      logger.error("Gemini fetch failed", { uid, action, error: e?.message });
      // Refund on network error
      try {
        await db.runTransaction(async (tx) => {
          const doc = await tx.get(userRef);
          const current = doc.exists ? (doc.data()?.credits ?? 0) : 0;
          const newBalance = current + cost;
          tx.set(userRef, { credits: newBalance }, { merge: true });
          appendLedgerInTx(tx, userRef, {
            type: "refund",
            delta: cost,
            balanceAfter: newBalance,
            action,
            model,
            reason: "network_error",
          });
        });
      } catch (refundErr: any) {
        logger.error("Refund failed after Gemini error", { uid, action, error: refundErr?.message });
      }
      res.status(500).json({ error: "Proxy call failed" });
    }
  },
);

// ---------- Verify IAP purchase & credit purchase ----------
// App sends: { purchaseToken, productId }
// Function:
//   1. Validates against Google Play Developer API.
//   2. Records the purchase idempotently (purchases/{token}).
//   3. Credits the user and appends a ledger entry.
//   4. Best-effort acknowledges via Play API so Google doesn't auto-refund.

export const verifyPurchase = onRequest(
  { cors: false, memory: "256MiB", timeoutSeconds: 60 },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).json({ error: "Method not allowed" });
      return;
    }

    const uid = await verifyToken(req.headers.authorization ?? "");
    if (!uid) {
      res.status(401).json({ error: "Unauthorized" });
      return;
    }

    const { purchaseToken, productId } = req.body ?? {};
    if (!purchaseToken || !productId) {
      res.status(400).json({ error: "Missing purchaseToken or productId" });
      return;
    }

    const creditsToAdd = CREDITS_PER_PACK[productId];
    if (!creditsToAdd) {
      res.status(400).json({ error: `Unknown product: ${productId}` });
      return;
    }

    // Idempotency: each purchase token is processed exactly once
    const purchaseRef = db.collection("purchases").doc(purchaseToken as string);
    const purchaseSnap = await purchaseRef.get();
    if (purchaseSnap.exists) {
      res.json({ credits: creditsToAdd, alreadyProcessed: true });
      return;
    }

    // Google Play Developer API validation
    const validation = await validatePlayPurchase(ANDROID_PACKAGE_NAME, productId, purchaseToken);
    if (!validation.ok) {
      logger.warn("Play API rejected purchase", { uid, productId, reason: validation.reason });
      res.status(400).json({ error: "Invalid purchase", reason: validation.reason });
      return;
    }

    const userRef = db.collection("users").doc(uid);
    await db.runTransaction(async (tx) => {
      const userSnap = await tx.get(userRef);
      const current = userSnap.exists ? (userSnap.data()?.credits ?? 0) : 0;
      const newBalance = current + creditsToAdd;
      tx.set(userRef, { credits: newBalance }, { merge: true });
      tx.set(purchaseRef, {
        uid,
        productId,
        orderId: validation.orderId ?? null,
        credits: creditsToAdd,
        processedAt: admin.firestore.FieldValue.serverTimestamp(),
      });
      appendLedgerInTx(tx, userRef, {
        type: "purchase",
        delta: creditsToAdd,
        balanceAfter: newBalance,
        productId,
        purchaseToken,
      });
    });

    // Best-effort acknowledgement so Google doesn't auto-refund
    try {
      await acknowledgePlayPurchase(ANDROID_PACKAGE_NAME, productId, purchaseToken);
    } catch (e: any) {
      // Already-acknowledged is fine; log everything else
      logger.warn("Play acknowledge failed", { uid, productId, error: e?.message });
      await appendLedger(userRef, {
        type: "purchase",
        delta: 0,
        balanceAfter: 0,
        productId,
        purchaseToken,
        reason: `ack_failed:${e?.message ?? "unknown"}`,
      });
    }

    res.json({ credits: creditsToAdd });
  },
);
