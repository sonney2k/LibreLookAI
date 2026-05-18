import * as admin from "firebase-admin";

export type LedgerType = "purchase" | "spend" | "refund";

export interface LedgerEntry {
  type: LedgerType;
  delta: number;            // signed: +credits for purchase/refund, -credits for spend
  balanceAfter: number;
  action?: string;          // for spend/refund
  model?: string;
  inputTokens?: number;
  outputTokens?: number;
  /** Number of items requested in one call (e.g. multi-suggestion generateText). */
  bulkItems?: number;
  productId?: string;       // for purchase
  purchaseToken?: string;
  reason?: string;          // optional human-readable note (e.g. "gemini_failed")
}

/**
 * Append a ledger row. Designed to be called *inside* a transaction so the
 * balanceAfter we record matches the credits we just wrote.
 */
export function appendLedgerInTx(
  tx: admin.firestore.Transaction,
  userRef: admin.firestore.DocumentReference,
  entry: LedgerEntry,
): void {
  const ledgerRef = userRef.collection("ledger").doc();
  tx.set(ledgerRef, {
    ...entry,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
  });
}

/**
 * Append a ledger row outside any transaction — used for refunds-on-error
 * where the original deduction already committed and we just need to record
 * the compensating action.
 */
export async function appendLedger(
  userRef: admin.firestore.DocumentReference,
  entry: LedgerEntry,
): Promise<void> {
  await userRef.collection("ledger").add({
    ...entry,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
  });
}
