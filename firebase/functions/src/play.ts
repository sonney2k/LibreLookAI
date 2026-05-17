import { google } from "googleapis";

/**
 * Validates a Google Play in-app purchase token by calling the Play Developer
 * API. Uses Application Default Credentials — the Cloud Functions runtime
 * service account must be granted access in Play Console
 * (Users & permissions → Add user → service account email → grant "View
 * financial data" + "Manage orders and subscriptions" on this app).
 *
 * Returns:
 *   { ok: true }   — purchaseState=0 (purchased) and not yet consumed
 *   { ok: false, reason } — anything else (cancelled, pending, already
 *                            consumed, API error)
 */
export async function validatePlayPurchase(
  packageName: string,
  productId: string,
  purchaseToken: string,
): Promise<{ ok: true; orderId?: string } | { ok: false; reason: string }> {
  try {
    const auth = new google.auth.GoogleAuth({
      scopes: ["https://www.googleapis.com/auth/androidpublisher"],
    });
    const androidpublisher = google.androidpublisher({ version: "v3", auth });

    const resp = await androidpublisher.purchases.products.get({
      packageName,
      productId,
      token: purchaseToken,
    });

    const data = resp.data;
    // purchaseState: 0=Purchased, 1=Cancelled, 2=Pending
    if (data.purchaseState !== 0) {
      return { ok: false, reason: `purchaseState=${data.purchaseState}` };
    }
    // consumptionState: 0=Yet to be consumed, 1=Consumed
    if (data.consumptionState === 1) {
      return { ok: false, reason: "already_consumed" };
    }
    return { ok: true, orderId: data.orderId ?? undefined };
  } catch (e: any) {
    return { ok: false, reason: `play_api_error:${e?.code ?? e?.message ?? "unknown"}` };
  }
}

/**
 * Acknowledge a one-time IAP via the Play Developer API. Must happen within
 * 3 days or Google auto-refunds. Client-side consumption (to make the SKU
 * re-purchasable) is handled by BillingClient.consumeAsync separately.
 * Best-effort: failures are logged but don't roll back the credit.
 */
export async function acknowledgePlayPurchase(
  packageName: string,
  productId: string,
  purchaseToken: string,
): Promise<void> {
  const auth = new google.auth.GoogleAuth({
    scopes: ["https://www.googleapis.com/auth/androidpublisher"],
  });
  const androidpublisher = google.androidpublisher({ version: "v3", auth });
  await androidpublisher.purchases.products.acknowledge({
    packageName,
    productId,
    token: purchaseToken,
  });
}
