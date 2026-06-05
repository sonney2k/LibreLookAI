# LibreLookAI

**Your closet, styled by AI.** LibreLookAI turns photos of the clothes you already own into a smart
digital wardrobe: snap each item, let AI remove the background and tag it, then get outfit ideas, try
looks on yourself, shop the gaps, and pack for trips — all kept **private in your own Google Drive**.

**Free and open-source.** LibreLookAI is released under the permissive **BSD 2-Clause License** (see
[`LICENSE`](LICENSE)). The whole point of the project is that everyone gets the full app for free:
you bring your own Google Gemini API key, your data stays in *your* Drive, and there is nothing to
subscribe to. Anyone can read, build, fork, and ship it. No paywalls, no telemetry-for-profit, no
locked features.

Android app (Kotlin / Jetpack Compose), package `com.librelookai`. It runs against the Google Gemini
API in **bring-your-own-key (BYOK)** mode — the default and the way the app is meant to be used.

---

## Core ideas

- **Your data lives in your Drive, not our server.** Every wardrobe item (cutout PNG + original JPG +
  a JSON sidecar of tags) and every outfit/trip/try-on is stored under a `LibreLookAI/` folder tree in
  the user's own Google Drive. Reinstalling or switching devices just re-reads Drive. There is no
  app-owned database of user content.
- **Identity is the Drive folder.** A "closet" maps to a Drive subfolder; an item's identity is its
  `folderId` + Drive file ID, never an ephemeral in-memory id. The shopping closet is a reserved
  `LibreLookAI/_shopping/` folder.
- **AI is assistive and degradable.** Gemini does background removal, clothing classification, outfit
  composition, try-on image generation, and travel packing. Every call can fail and return `null`;
  the UI always degrades gracefully and gates AI/write surfaces when offline.
- **BYOK is the model.** The user pastes their own Google Gemini API key and calls go straight to
  Google — the user pays Google directly (often nothing, on the free tier) and the app takes no cut.
  An optional *managed-credits* mode exists purely as a **convenience for less technical users** who
  don't want to create their own API key: it routes calls through a Firebase proxy and is **off by
  default**. It adds no features — it's just an easier on-ramp. See [Optional managed mode](#optional-managed-mode).
  *Looking ahead:* as phones gain capable on-device generative AI, the cloud calls — and the currently
  tedious AI Studio key setup — should become unnecessary altogether, leaving an app that runs fully
  on-device for free.
- **Privacy- and offline-first.** Optimistic local writes with a local cache mirror Drive; weather/
  location are opt-in; analytics are the only thing that leaves the device, and that's configurable.
- **On-device ML where it helps.** A local embedder + segmenter power visual similarity search,
  duplicate detection, and optional on-device background removal without a network round-trip.

## Features

| Feature | What it does |
|---|---|
| **Wardrobe** | Photograph each item once; AI removes the background and tags type, colour, material, etc. so everything stays searchable. Import from camera, gallery, or a product URL. |
| **Outfits** | Outfit suggestions tailored to weather, occasion, and taste — built only from clothes you own. A composer lets you assemble/edit looks by slot. |
| **Try-On** | Add a photo of yourself and see any outfit rendered on you before wearing it. |
| **Travel** | Tell the planner where and how long; it builds a day-by-day capsule wardrobe from your closet. |
| **Shopping** | Save items you're considering; the helper checks whether they match what you own (visual similarity) and flags real wardrobe gaps. |
| **Calendar & stats** | Log what you wear; wear history feeds back into suggestions as a taste signal. |

## Project structure

```
app/        Android app (Kotlin/Compose) — see package layout below
firebase/   Cloud Functions (geminiProxy, verifyPurchase, pricing trigger) + Firestore rules
scripts/    Python/shell helpers (translations, file-splitting, release)
plan/        Long-form docs: CLAUDE_ARCHIVE.md (deep architecture), FIN.md, TODO.md, TRANSLATION.md
docs/        Focused docs: Gemini key guide, analytics funnels
designs/    Design-handoff bundles (HTML mockups, JSX prototypes)
website/    Static marketing site (vanilla HTML/CSS + i18n in 12 languages)
icon/       App iconography
```

**App package layout** (`com.librelookai`): `MainActivity` + `AppContent` host the whole composition
(no Jetpack Navigation — a single `selectedTab`). Feature packages: `auth/`, `onboarding/`,
`wardrobe/`, `outfit/`, `travel/`, `tryon/`, `shopping/`, `billing/`, `settings/`. Cross-cutting:
`data/model/` (pure data), `data/drive/` (`DriveRepository`), `gemini/` (`GeminiRepository`,
prompts, tag normalization), `ml/` (embedder, segmenter, pHash), `weather/`, `service/`, `util/`,
`ui/theme/`.

> **`CLAUDE.md` (repo root) is the authoritative day-to-day guide**; deep architecture, data-flow
> pipelines, and rationale live in **`plan/CLAUDE_ARCHIVE.md`**. Read those before changing data flow,
> pipelines, or window-based UI. Every user-facing string is localized across 31 locales.

## Build & run

Requirements: Android Studio, JDK 21 (the test toolchain is pinned to Java 21 — Robolectric can't
instrument under JDK 25), and a `local.properties` (see below).

```bash
./gradlew assembleDebug          # build the debug APK
./gradlew testDebugUnitTest      # JVM unit tests (JUnit + Robolectric Compose flow tests)
./gradlew installDebug           # install on a connected device/emulator
```

Minimal `local.properties` for a BYOK debug build needs only the Android SDK path; the app prompts for
a Gemini API key on first run. To exercise managed mode or partner links, add the keys described in the
sections below.

**Cut a release:** `scripts/release.sh <versionName>` bumps `versionCode`/`versionName`, prepends
release notes, builds the signed APK, commits + tags, and uploads to Firebase App Distribution. Full
release/signing details: `plan/CLAUDE_ARCHIVE.md` § Release process.

## Optional managed mode

LibreLookAI is BYOK-first. The managed-credits mode is an **optional convenience** for users who'd
rather not create their own Gemini key — it provides no extra functionality and is **off by default**.

It is gated by a single build flag (`managed.billing.enabled` in `local.properties`, default `false`).
Off = a pure BYOK release: every coin/purchase surface is hidden and no Firebase proxy is used.
Flipping it on (with a deployed proxy + Play products) routes AI calls through a Firebase proxy that
deducts coins. Most people running the app from source will never touch this. Deep rationale:
`plan/FIN.md`. End-user key walkthrough: `docs/GEMINI_API_KEY.md`.

## License

BSD 2-Clause — see [`LICENSE`](LICENSE). Permissive: use, modify, redistribute, and ship it
(including in your own builds) freely, with attribution. © 2026 Soeren Sonnenburg.

---

# Setup reference

The rest of this document is operational setup for the optional Firebase backend, analytics, and
affiliate links. None of it is required for a BYOK debug build.

## Firebase Setup (Managed Credits Mode)

This is optional. Without Firebase the app works fine in BYOK mode (users supply their own Gemini API key).
Enable it to offer the refillable-credit subscription model.

### 1. Create a Firebase project

1. Go to https://console.firebase.google.com → "Add project"
2. Enable **Google Analytics** (optional but recommended)
3. Add an **Android app** with package name `com.librelookai`
4. Download `google-services.json` → place it at `app/google-services.json`

### 2. Enable Firebase services

In the Firebase console:
- **Authentication** → Sign-in method → enable **Google**
- **Firestore Database** → Create database → start in **production mode**
  - Deploy the bundled rules: `cd firebase && firebase deploy --only firestore:rules`
- **Cloud Functions** → will be deployed in step 5

### 3. Store the Gemini API key as a Firebase secret

```
firebase login
cd firebase
firebase use <your-project-id>
firebase functions:secrets:set GEMINI_API_KEY
# paste your key when prompted
```

### 4. Add Firebase config to local.properties

```
# local.properties (never committed)
firebase.proxy.url=https://<region>-<project-id>.cloudfunctions.net
firebase.web.client.id=<OAuth 2.0 Web Client ID from Firebase Auth settings>
```

The Web Client ID is found in Firebase console → Authentication → Sign-in method → Google → Web SDK configuration.

### 5. Deploy Cloud Functions

```
cd firebase/functions
npm install
cd ..
firebase deploy --only functions
```

Functions deployed:
- `geminiProxy` — verifies Firebase ID token, deducts credits, proxies to Gemini
- `verifyPurchase` — verifies Play Billing purchases, adds credits to Firestore

### 6. Register in-app products in Google Play Console

Create these one-time product SKUs under your app's "In-app products":

| Product ID      | Suggested price | Credits |
|-----------------|-----------------|---------|
| `credits_100`   | $0.99           | 100     |
| `credits_500`   | $4.99           | 500     |
| `credits_2000`  | $14.99          | 2 000   |

### 7. Credit costs per AI action

| Action               | Credits |
|----------------------|---------|
| Background removal   | 5       |
| Classify clothing    | 2       |
| Generate text        | 2       |
| Search trends        | 2       |

### Fallback behaviour

If `google-services.json` is absent or `firebase.proxy.url` is not set, the app silently falls back to BYOK mode. The Credits tab in Settings is hidden; no Firebase code runs.

---

## Firebase Analytics

1. Confirm the Analytics product is enabled in the Firebase Console for the project that owns the google-services.json you already ship in app/google-services.json:
- Go to console.firebase.google.com → your project → Analytics in the left nav. If it asks you to set it up, accept the data-sharing terms and link/create a Google
Analytics account. Without this, events accumulate but nothing appears in dashboards.
2. Test in DebugView before shipping:
adb shell setprop debug.firebase.analytics.app com.librelookai
./gradlew installDebug
3. Open the app, navigate around. In the Firebase Console go to Analytics → DebugView — within ~10 s you should see screen_view, ui_action, sign_in_success events
stream in with their parameters.
Disable when done: adb shell setprop debug.firebase.analytics.app .none.
4. Register custom parameters so they show up in standard reports (without this, params still flow into BigQuery but aren't aggregatable in the UI):
- Analytics → Custom definitions → Create custom dimension, register at least:
- Event parameter screen (event-scoped) — dimension name "Screen"
- Event parameter action (event-scoped) — dimension name "Action"
- Event parameter count (event-scoped) — dimension name "Count" (or as Metric if you want to sum it)
- Event parameter index (event-scoped) — dimension name "Tab Index"
- These start collecting from the moment you create them; historical events are not retroactively dimensionalised.
5. Mark key events as conversions if you want funnels in the GA4 UI: Analytics → Events, toggle sign_in_success, ui_action (or specific actions you'll funnel on like
suggest_replacements) as conversions/key events.
6. Optional but recommended for "in which order" analysis: in the Google Analytics 4 property (not Firebase) → Admin → BigQuery Links, link the GA4 property to
BigQuery (free daily export tier). Once linked, every event lands in events_* tables with event_timestamp and you can run path queries (sequence of screen + action
per user_pseudo_id per session). This is by far the best surface for the "what gets tapped, in what order" question — the GA4 UI alone is too aggregated.
7. Privacy: Firebase Analytics collects an Android Advertising ID and IP-derived geo by default. If you have a privacy policy / consent flow, either:
- Disable collection until consent: set <meta-data android:name="firebase_analytics_collection_enabled" android:value="false" /> in AndroidManifest.xml and call
FirebaseAnalytics.getInstance(ctx).setAnalyticsCollectionEnabled(true) after consent, or
- Document the existing default in your privacy policy. Worth thinking about before publishing.

> **Funnels:** onboarding and the core flows (wardrobe-add, outfit, shopping, travel, try-on) emit
> structured funnel events. The event reference and GA4 funnel-build recipe live in
> `docs/ONBOARDING_ANALYTICS.md` and `docs/FLOW_ANALYTICS.md`.

---

## Partner Programs

local.properties — add your IDs here (never committed to git):
amazon.affiliate.tag=yourstore-20
shopstyle.publisher.id=uid2500-XXXXX-XX

How it works:
- Amazon button → amazon.com/s?k=<query>&tag=<your-tag> — cookies the user for 24 hours, earning commission on anything they buy
- ShopStyle button (replaces Google Shopping) → shopstyle.com/browse?q=<query>&pid=<your-pid> — ShopStyle Collective pays CPC or CPS depending on the retailer

Fallback: if either ID is blank (e.g. debug builds without local.properties), the URL still works as a plain search — no broken links.

To sign up:
- Amazon Associates: affiliate-program.amazon.com
- ShopStyle Collective: shopstylecollective.com/publishers
