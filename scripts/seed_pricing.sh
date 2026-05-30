#!/usr/bin/env bash
# Show the live config/pricing + config/modelPricing in Firestore against the
# local source-of-truth values (DEFAULT_PRICING in firebase/functions/src/
# pricing.ts and LOCAL_MODEL_PRICING in seed.ts), print a diff, and prompt
# before writing. The recomputePublicPricing trigger mirrors the result into
# config/publicPricing for the client.
#
# Usage:
#   scripts/seed_pricing.sh                       # interactive review + prompt
#   scripts/seed_pricing.sh --yes                 # apply without prompting (CI)
#   FIREBASE_PROJECT_ID=other-proj scripts/seed_pricing.sh
#
# Read-only by default: nothing is written until you confirm.

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
functions_dir="$repo_root/firebase/functions"
gservices="$repo_root/app/google-services.json"

# --- 1. Project ID: env override > google-services.json > error ---
project="${FIREBASE_PROJECT_ID:-}"
if [[ -z "$project" ]]; then
  if [[ ! -f "$gservices" ]]; then
    echo "✗ Set FIREBASE_PROJECT_ID or place google-services.json at app/." >&2
    exit 1
  fi
  project="$(grep -oE '"project_id":[[:space:]]*"[^"]+"' "$gservices" \
              | head -1 | sed -E 's/.*"([^"]+)"$/\1/')"
  if [[ -z "$project" ]]; then
    echo "✗ Could not parse project_id from $gservices" >&2
    exit 1
  fi
fi
echo "→ Project: $project"

# --- 2. ADC sanity check (the Admin SDK needs this; gcloud login alone won't do) ---
if ! gcloud auth application-default print-access-token >/dev/null 2>&1; then
  cat >&2 <<EOF
✗ Application Default Credentials not configured.

Run these once, then retry:

  gcloud auth application-default login
  gcloud auth application-default set-quota-project $project
EOF
  exit 1
fi

# --- 3. Build (idempotent; fast on a warm tree) ---
echo "→ Building $functions_dir …"
( cd "$functions_dir" && npm run build --silent )

# --- 4. Run the review/diff/prompt seed (forwards extra args, e.g. --yes) ---
( cd "$functions_dir" \
    && GOOGLE_CLOUD_PROJECT="$project" \
       node lib/seed.js "$@" )
