Firebase Setup (Managed Credits Mode)
======================================

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

Firebase Analytics
===================

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
                                                                                                                                                                       
---

Partner Programs
=================

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
