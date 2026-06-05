#!/usr/bin/env bash
# Cut a LibreLookAI release: bump the version, prepend human-readable release
# notes, build the signed release APK *and* the Play Store AAB, commit + tag,
# distribute the APK to the Firebase App Distribution "testers" group, and write
# a short Play "What's new" draft (play-whatsnew.txt) for the Play Console.
#
# Mirrors the per-release checklist in plan/CLAUDE_ARCHIVE.md § Release process.
#
# Usage:
#   scripts/release.sh <versionName> [options]
#
#   <versionName>        New marketing version, e.g. 2.1.3. versionCode is
#                        auto-incremented from the current build.gradle.kts value.
#
# Options:
#   --notes <file>       Read the release-notes body from <file> instead of
#                        opening $EDITOR. The body is the "#### Section" markdown;
#                        the "### LibreLookAI vX Release Notes" heading is added
#                        automatically. If <file> is "-", read from stdin.
#   --no-upload          Build, commit and tag, but skip the Firebase upload.
#                        (The Play AAB + What's new draft are still produced.)
#   --distribute-only    Skip the version bump / notes / commit / tag entirely;
#                        just build the signed APK for the CURRENT version and
#                        upload it to the Firebase App Distribution "testers"
#                        group. Use this to push an already-cut release (one
#                        whose version is committed + tagged for the Play track)
#                        out to testers. <versionName> is optional here; if given
#                        it must match the current build (sanity check).
#   --push               git push main + the new tag after a successful upload.
#   --yes                Don't prompt for confirmation (needs --notes for the body).
#   --dry-run            Show the version bump + notes that would be applied, then
#                        revert and exit. Touches nothing else.
#   -h, --help           Show this help.
#
# Notes:
#   * Run on a CLEAN working tree (the only changes committed are the version
#     bump + release-notes.txt). The script refuses to run otherwise.
#   * Firebase upload uses the App Distribution Gradle plugin, which needs
#     credentials: a logged-in `firebase` CLI, FIREBASE_TOKEN, or
#     GOOGLE_APPLICATION_CREDENTIALS pointing at a service-account key.
#
# Run from anywhere; paths resolve against the repo root.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_FILE="$REPO_ROOT/app/build.gradle.kts"
NOTES_FILE="$REPO_ROOT/release-notes.txt"
WHATSNEW_FILE="$REPO_ROOT/play-whatsnew.txt"
AAB_PATH="$REPO_ROOT/app/build/outputs/bundle/release/app-release.aab"
PLAY_WHATSNEW_LIMIT=500

die() { echo "✗ $*" >&2; exit 1; }
info() { echo "→ $*"; }

# Build both signed release artifacts: the APK (Firebase App Distribution) and
# the AAB (Play Store upload). Caller handles failure (e.g. revert on a cut).
build_release_artifacts() {
    info "Building signed release APK + Play AAB …"
    ./gradlew :app:assembleRelease :app:bundleRelease
}

# Condense a markdown release-notes body into a Play Store "What's new" draft:
# plain text, • bullets, no #### headings. Writes $WHATSNEW_FILE and warns if it
# blows Play's per-language 500-char limit.
write_whatsnew() {
    local body="$1"
    printf '%s\n' "$body" \
        | sed -E -e 's/^#### *//' -e 's/^[[:space:]]*[-*] +/• /' \
        > "$WHATSNEW_FILE"
    local chars; chars="$(wc -m < "$WHATSNEW_FILE" | tr -d ' ')"
    info "Play 'What's new' draft → $WHATSNEW_FILE ($chars/$PLAY_WHATSNEW_LIMIT chars)"
    [[ "$chars" -gt "$PLAY_WHATSNEW_LIMIT" ]] && \
        echo "⚠ over Play's $PLAY_WHATSNEW_LIMIT-char limit — trim before pasting." >&2
    return 0
}

# Extract the most recent section body from release-notes.txt: everything
# between the latest "### … Release Notes" heading and the first '---' divider,
# with the heading itself stripped. Used by --distribute-only, which has no
# freshly-gathered notes body of its own.
latest_notes_body() {
    awk '
        /^---[[:space:]]*$/ { exit }
        /^### .*Release Notes/ { skip=1; next }
        skip { print }
    ' "$NOTES_FILE" | sed -e '/./,$!d'
}

# Portable in-place sed (BSD vs GNU differ on -i).
sed_inplace() {
    local file="$1"; shift
    local tmp; tmp="$(mktemp)"
    sed -E "$@" "$file" > "$tmp" && mv "$tmp" "$file"
}

show_help() { sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//; s/^#$//'; }

# --- Parse args ---------------------------------------------------------------
NEW_NAME=""
NOTES_SRC=""
DO_UPLOAD=1
DO_PUSH=0
ASSUME_YES=0
DRY_RUN=0
DISTRIBUTE_ONLY=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --notes)            NOTES_SRC="${2:-}"; shift 2 ;;
        --no-upload)        DO_UPLOAD=0; shift ;;
        --distribute-only)  DISTRIBUTE_ONLY=1; shift ;;
        --push)             DO_PUSH=1; shift ;;
        --yes)              ASSUME_YES=1; shift ;;
        --dry-run)          DRY_RUN=1; shift ;;
        -h|--help)          show_help; exit 0 ;;
        -*)                 die "unknown option: $1 (see --help)" ;;
        *)                  [[ -z "$NEW_NAME" ]] && NEW_NAME="$1" || die "unexpected arg: $1"; shift ;;
    esac
done

[[ -f "$GRADLE_FILE" ]] || die "$GRADLE_FILE not found"
if [[ -n "$NEW_NAME" ]]; then
    [[ "$NEW_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "versionName must look like X.Y.Z (got '$NEW_NAME')"
    TAG="v$NEW_NAME"
elif [[ "$DISTRIBUTE_ONLY" -eq 0 ]]; then
    show_help; exit 1
fi

# --- Pre-flight ---------------------------------------------------------------
cd "$REPO_ROOT"

if [[ "$DISTRIBUTE_ONLY" -eq 0 ]]; then
    if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
        die "tracked files have uncommitted changes — commit or stash first."
    fi
    if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
        die "tag $TAG already exists."
    fi
fi

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [[ "$BRANCH" != "main" ]]; then
    echo "⚠ not on main (on '$BRANCH')."
    [[ "$ASSUME_YES" -eq 1 ]] || { read -rp "  Continue anyway? [y/N] " a; [[ "$a" =~ ^[Yy]$ ]] || exit 1; }
fi

CUR_CODE="$(grep -E '^[[:space:]]*versionCode[[:space:]]*=' "$GRADLE_FILE" | grep -oE '[0-9]+' | head -1)"
CUR_NAME="$(grep -E '^[[:space:]]*versionName[[:space:]]*=' "$GRADLE_FILE" | sed -E 's/.*"([^"]+)".*/\1/')"
[[ -n "$CUR_CODE" ]] || die "could not read versionCode from $GRADLE_FILE"

# --- Distribute-only: upload the CURRENT build to testers, no bump/commit/tag ---
if [[ "$DISTRIBUTE_ONLY" -eq 1 ]]; then
    [[ "$DO_UPLOAD" -eq 1 ]] || die "--distribute-only with --no-upload would do nothing."
    if [[ -n "$NEW_NAME" && "$NEW_NAME" != "$CUR_NAME" ]]; then
        die "--distribute-only uploads the current build ($CUR_NAME, versionCode $CUR_CODE), but you asked for $NEW_NAME. Drop --distribute-only to cut a new version, or pass $CUR_NAME."
    fi
    info "Distribute-only: current build $CUR_NAME (versionCode $CUR_CODE) → Firebase testers."
    info "Release notes come from $NOTES_FILE as-is (no bump, commit, or tag)."
    if [[ "$ASSUME_YES" -ne 1 ]]; then
        read -rp "Build signed APK + Play AAB and upload APK to testers? [y/N] " a
        [[ "$a" =~ ^[Yy]$ ]] || { echo "Aborted."; exit 1; }
    fi
    build_release_artifacts || die "build failed."
    info "Uploading to Firebase App Distribution (group: testers) …"
    ./gradlew :app:appDistributionUploadRelease
    write_whatsnew "$(latest_notes_body)"
    echo
    info "Done: distributed $CUR_NAME (versionCode $CUR_CODE) to Firebase testers."
    info "Play AAB ready: $AAB_PATH"
    exit 0
fi

NEW_CODE=$((CUR_CODE + 1))

info "Current: $CUR_NAME (versionCode $CUR_CODE)"
info "New:     $NEW_NAME (versionCode $NEW_CODE)  →  tag $TAG"

LAST_TAG="$(git tag -l 'v*' --sort=-v:refname | head -1)"
echo
if [[ -n "$LAST_TAG" ]]; then
    echo "Commits since $LAST_TAG:"
    git log --oneline "$LAST_TAG"..HEAD | sed 's/^/  /'
else
    echo "(no previous v* tag found)"
fi
echo

# --- Gather release-notes body ------------------------------------------------
# Priority: --notes file > piped stdin > $EDITOR.
NOTES_BODY=""
strip_comments() { grep -v '^[[:space:]]*#' || true; }

if [[ -n "$NOTES_SRC" ]]; then
    if [[ "$NOTES_SRC" == "-" ]]; then
        NOTES_BODY="$(cat)"
    else
        [[ -f "$NOTES_SRC" ]] || die "notes file not found: $NOTES_SRC"
        NOTES_BODY="$(cat "$NOTES_SRC")"
    fi
elif [[ ! -t 0 ]]; then
    NOTES_BODY="$(cat)"
else
    [[ "$ASSUME_YES" -eq 1 ]] && die "--yes needs --notes <file> (cannot open an editor unattended)."
    tmpl="$(mktemp)"
    {
        echo "# Write user-facing release notes for $TAG below."
        echo "# Lines starting with # are ignored. Use #### Section headings, like:"
        echo "#"
        echo "#   #### What's new"
        echo "#   - A clear, user-facing sentence about the change."
        echo "#"
        echo "# Recent commits for reference:"
        [[ -n "$LAST_TAG" ]] && git log --oneline "$LAST_TAG"..HEAD | sed 's/^/#   /'
        echo
        echo "#### "
        echo "- "
    } > "$tmpl"
    "${EDITOR:-vi}" "$tmpl"
    NOTES_BODY="$(strip_comments < "$tmpl")"
    rm -f "$tmpl"
fi

# Trim leading/trailing blank lines.
NOTES_BODY="$(printf '%s\n' "$NOTES_BODY" | sed -e '/./,$!d' | awk 'BEGIN{RS="";ORS="\n"}{print}')"
[[ -n "${NOTES_BODY//[[:space:]]/}" ]] || die "release notes are empty — aborting."

NEW_SECTION="### LibreLookAI $TAG Release Notes

$NOTES_BODY"

echo "──────── $TAG release notes ────────"
echo "$NEW_SECTION"
echo "────────────────────────────────────"
echo

# --- Apply version bump + prepend notes --------------------------------------
apply_changes() {
    sed_inplace "$GRADLE_FILE" "s/versionCode = $CUR_CODE/versionCode = $NEW_CODE/"
    sed_inplace "$GRADLE_FILE" "s/versionName = \"$CUR_NAME\"/versionName = \"$NEW_NAME\"/"

    if [[ -f "$NOTES_FILE" ]]; then
        local tmp; tmp="$(mktemp)"
        { printf '%s\n\n---\n\n' "$NEW_SECTION"; cat "$NOTES_FILE"; } > "$tmp"
        mv "$tmp" "$NOTES_FILE"
    else
        printf '%s\n' "$NEW_SECTION" > "$NOTES_FILE"
    fi
}

revert_changes() { git checkout -- "$GRADLE_FILE" "$NOTES_FILE"; }

if [[ "$DRY_RUN" -eq 1 ]]; then
    apply_changes
    info "Dry run — diff that would be committed:"
    git --no-pager diff -- "$GRADLE_FILE" "$NOTES_FILE"
    revert_changes
    echo
    write_whatsnew "$NOTES_BODY"
    echo "──────── Play 'What's new' draft ────────"
    cat "$WHATSNEW_FILE"
    echo "─────────────────────────────────────────"
    rm -f "$WHATSNEW_FILE"
    info "Reverted. Nothing built, committed or uploaded."
    exit 0
fi

if [[ "$ASSUME_YES" -ne 1 ]]; then
    plan="build signed APK + Play AAB, commit, tag $TAG"
    [[ "$DO_UPLOAD" -eq 1 ]] && plan="$plan, upload to Firebase testers"
    [[ "$DO_PUSH" -eq 1 ]]   && plan="$plan, push"
    read -rp "Proceed to $plan? [y/N] " a
    [[ "$a" =~ ^[Yy]$ ]] || { echo "Aborted."; exit 1; }
fi

apply_changes

# --- Build (revert version/notes on failure so the tree stays clean) ---------
if ! build_release_artifacts; then
    revert_changes
    die "build failed — reverted version bump + notes."
fi

# Play "What's new" draft from the notes we just gathered.
write_whatsnew "$NOTES_BODY"

# --- Commit + tag -------------------------------------------------------------
info "Committing + tagging $TAG …"
git add "$GRADLE_FILE" "$NOTES_FILE"
git commit -m "release: $TAG (versionCode $NEW_CODE)"
git tag "$TAG"

# --- Upload to Firebase App Distribution -------------------------------------
if [[ "$DO_UPLOAD" -eq 1 ]]; then
    info "Uploading to Firebase App Distribution (group: testers) …"
    ./gradlew :app:appDistributionUploadRelease
else
    info "Skipping Firebase upload (--no-upload)."
fi

# --- Push ---------------------------------------------------------------------
if [[ "$DO_PUSH" -eq 1 ]]; then
    info "Pushing $BRANCH + $TAG …"
    git push origin "$BRANCH"
    git push origin "$TAG"
else
    echo
    info "Not pushed. When ready:  git push origin $BRANCH && git push origin $TAG"
fi

echo
info "Done: $NEW_NAME (versionCode $NEW_CODE), tagged $TAG."
info "Play AAB ready to upload: $AAB_PATH"
info "Play 'What's new' draft:  $WHATSNEW_FILE"
