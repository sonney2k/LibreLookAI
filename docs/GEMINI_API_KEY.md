# Get your Gemini API key (5 minutes)

LibreLookAI runs entirely on **your own Google Gemini API key**. The key is stored
only on your phone and used directly with Google — nothing passes through our servers,
and there is no subscription to us.

There are two levels:

| What you want to do | Key you need |
|---|---|
| Auto-tagging, outfit suggestions, packing lists, trend search | **Free key** is enough |
| Virtual try-on, background removal (anything that makes an image) | **Billing-enabled (paid) key** |

> **Why paid?** Try-on and background removal use Gemini's *image generation*, which
> Google does not offer on the free tier. You still only pay Google for what you
> actually use — typically a few cents per image — with no minimum and no charge to us.

The app guides you through this during setup, but here is the full walkthrough.

---

## Step 1 — Create the key (free)

1. On your phone, tap **Get an API key** in the setup screen
   (or open **[aistudio.google.com/app/apikey](https://aistudio.google.com/app/apikey)**).
2. Sign in with your Google account.
3. Tap **Create API key**. Pick or create a Google Cloud project if asked — any name is fine.
4. Your key appears (it starts with `AIza…`). Tap **Copy**.

## Step 2 — Paste it into LibreLookAI

1. Go back to LibreLookAI.
2. Paste the key into the **API Key** box on the setup screen.
   (Later you can change it under **Settings ▸ Advanced ▸ Gemini API key**.)
3. Done — tagging and outfit suggestions work immediately.

## Step 3 — Enable billing (for try-on & background removal)

This unlocks the image features. It's the only part that takes a Google Cloud step.

1. Tap **Enable billing** in setup
   (or open **[aistudio.google.com/app/billing](https://aistudio.google.com/app/billing)**).
2. Choose the **same Google Cloud project** you made the key in.
3. Click **Set up billing** / **Upgrade**, and add a payment method.
   New Google Cloud accounts usually include free credit to start.
4. Back in LibreLookAI, virtual try-on and background removal now work with the same key.

That's it. You can come back and add billing any time — until then, everything except
image generation already works on the free key.

---

## FAQ

**Is my key safe?**
Yes. It is saved only on this device (Android private storage) and sent straight to
Google's API. It is never uploaded to us or synced to your Drive.

**How much does it cost?**
Tagging/suggestions on the free tier: **€0**. Image features (try-on, background
removal) are billed by Google per image — usually a few cents each. You set spending
limits and see usage in your [Google Cloud console](https://console.cloud.google.com/billing).

**I pasted a key but try-on says it failed.**
That key probably doesn't have billing enabled. Do **Step 3**, then try again.

**Can I use a totally free key?**
Yes — for tagging, outfit suggestions, packing lists and trend search. Only the
image-generating features (try-on, background removal) need billing.

**Where do I change or remove my key?**
**Settings ▸ Advanced ▸ Gemini API key** — tap **Change** or **Clear**.
