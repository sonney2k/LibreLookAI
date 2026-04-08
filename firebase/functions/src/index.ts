import * as admin from "firebase-admin";
import { onRequest } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";
import fetch, { RequestInit } from "node-fetch";

admin.initializeApp();
const db = admin.firestore();

const geminiApiKey = defineSecret("GEMINI_API_KEY");

// Credit cost per AI action
const CREDIT_COSTS: Record<string, number> = {
  removeBackground: 5,
  classifyClothing:  2,
  generateText:      2,
  searchFashionTrends: 2,
};

// Credits awarded per in-app product
const CREDITS_PER_PACK: Record<string, number> = {
  credits_100:  100,
  credits_500:  500,
  credits_2000: 2000,
};

const GEMINI_BASE =
  "https://generativelanguage.googleapis.com/v1beta/models";

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
//   Authorization: Bearer <firebase_id_token>
//   X-AI-Action:   removeBackground | classifyClothing | generateText | searchFashionTrends
//   X-Gemini-Model: gemini-2.0-flash-exp (the Gemini model to route to)

export const geminiProxy = onRequest(
  {
    cors: false,
    memory: "512MiB",
    timeoutSeconds: 540,   // background removal can take a while
    secrets: [geminiApiKey],
  },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).json({ error: "Method not allowed" });
      return;
    }

    const uid = await verifyToken(req.headers.authorization ?? "");
    if (!uid) { res.status(401).json({ error: "Unauthorized" }); return; }

    const action = (req.headers["x-ai-action"] as string) ?? "generateText";
    const model  = (req.headers["x-gemini-model"] as string) ?? "";
    if (!model) {
      res.status(400).json({ error: "Missing X-Gemini-Model header" });
      return;
    }

    const cost = CREDIT_COSTS[action] ?? 2;
    const userRef = db.collection("users").doc(uid);

    // Deduct credits atomically — fail fast if insufficient
    try {
      await db.runTransaction(async (tx) => {
        const doc = await tx.get(userRef);
        const credits = doc.exists ? (doc.data()?.credits ?? 0) : 0;
        if (credits < cost) {
          const err = new Error("Insufficient credits") as any;
          err.code = 402;
          throw err;
        }
        tx.set(userRef, { credits: credits - cost }, { merge: true });
      });
    } catch (e: any) {
      res.status(e?.code === 402 ? 402 : 500).json({ error: e.message });
      return;
    }

    // Forward to Gemini
    const geminiUrl = `${GEMINI_BASE}/${model}:generateContent?key=${geminiApiKey.value()}`;
    const bodyStr = typeof req.body === "string"
      ? req.body
      : JSON.stringify(req.body);

    try {
      const init: RequestInit = {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: bodyStr,
      };
      const geminiResp = await fetch(geminiUrl, init);
      const responseText = await geminiResp.text();

      if (!geminiResp.ok) {
        // Refund on Gemini-side error
        await userRef.set(
          { credits: admin.firestore.FieldValue.increment(cost) },
          { merge: true },
        );
      }
      res.status(geminiResp.status)
        .setHeader("Content-Type", "application/json")
        .send(responseText);
    } catch (e: any) {
      // Refund on network error
      await userRef.set(
        { credits: admin.firestore.FieldValue.increment(cost) },
        { merge: true },
      );
      res.status(500).json({ error: "Proxy call failed" });
    }
  },
);

// ---------- Verify IAP purchase & credit purchase ----------
// App sends: { purchaseToken, productId }
// Function records the purchase (idempotent) and adds credits.

export const verifyPurchase = onRequest(
  { cors: false, memory: "256MiB", timeoutSeconds: 60 },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).json({ error: "Method not allowed" });
      return;
    }

    const uid = await verifyToken(req.headers.authorization ?? "");
    if (!uid) { res.status(401).json({ error: "Unauthorized" }); return; }

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

    const userRef = db.collection("users").doc(uid);
    await db.runTransaction(async (tx) => {
      const userSnap = await tx.get(userRef);
      const current = userSnap.exists ? (userSnap.data()?.credits ?? 0) : 0;
      tx.set(userRef, { credits: current + creditsToAdd }, { merge: true });
      tx.set(purchaseRef, {
        uid,
        productId,
        credits: creditsToAdd,
        processedAt: admin.firestore.FieldValue.serverTimestamp(),
      });
    });

    res.json({ credits: creditsToAdd });
  },
);
