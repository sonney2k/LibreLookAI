#!/usr/bin/env bash
# Report translation completeness for every values-<locale>/strings.xml,
# measured against the default values/strings.xml — aggregated across every
# module that owns string resources (:app, :core:designsystem, :core:service,
# :feature:auth, :feature:billing, :feature:onboarding, :feature:tryon and :feature:travel after the § 1 res-split).
#
# Usage:
#   scripts/translation_status.sh            # table sorted by completeness (worst first)
#   scripts/translation_status.sh --done     # only locales at 100%
#   scripts/translation_status.sh --todo     # only locales below 100%
#   scripts/translation_status.sh <locale>   # list the missing keys for one locale
#
# Run from the repository root.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RES_DIRS=(
    "$REPO_ROOT/app/src/main/res"
    "$REPO_ROOT/core/designsystem/src/main/res"
    "$REPO_ROOT/core/service/src/main/res"
    "$REPO_ROOT/feature/auth/src/main/res"
    "$REPO_ROOT/feature/billing/src/main/res"
    "$REPO_ROOT/feature/onboarding/src/main/res"
    "$REPO_ROOT/feature/tryon/src/main/res"
    "$REPO_ROOT/feature/travel/src/main/res"
)

for d in "${RES_DIRS[@]}"; do
    if [ ! -f "$d/values/strings.xml" ]; then
        echo "error: $d/values/strings.xml not found — run from repo root" >&2
        exit 1
    fi
done

# Union of keys across the module res roots for one values dir (e.g. "values-de").
keys_for_dir() {
    local values_dir="$1"
    local found=""
    for d in "${RES_DIRS[@]}"; do
        [ -f "$d/$values_dir/strings.xml" ] && found="$found $d/$values_dir/strings.xml"
    done
    [ -n "$found" ] || return 0
    # shellcheck disable=SC2086
    grep -hoE 'name="[^"]+"' $found | sort -u
}

base_count() { keys_for_dir values | wc -l | tr -d ' '; }

# All values-* dirs present in any module res root.
locale_dirs() {
    for d in "${RES_DIRS[@]}"; do
        for f in "$d"/values-*/strings.xml; do
            [ -e "$f" ] && basename "$(dirname "$f")"
        done
    done | sort -u
}

print_one_locale_missing() {
    local loc="$1"
    local present
    present=$(keys_for_dir "values-$loc" | wc -l | tr -d ' ')
    if [ "$present" -eq 0 ]; then
        echo "error: no strings.xml for values-$loc in any module" >&2
        exit 1
    fi
    local base
    base=$(base_count)
    echo "Locale: $loc"
    echo "Strings: $present / $base ($((present * 100 / base))%)"
    echo "Missing keys:"
    comm -23 <(keys_for_dir values) <(keys_for_dir "values-$loc") | sed 's/^name="/  /; s/"$//'
}

print_table() {
    local filter="${1:-all}"
    local base
    base=$(base_count)
    printf "Default: %d strings (%s)\n\n" "$base" "${RES_DIRS[*]}"
    printf "%-12s %6s  %5s  %8s\n" "locale" "count" "pct" "missing"
    printf -- '------------ ------  -----  --------\n'
    {
        locale_dirs | while read -r vd; do
            local loc
            loc=${vd#values-}
            local n
            n=$(keys_for_dir "$vd" | wc -l | tr -d ' ')
            local pct=$((n * 100 / base))
            local missing=$((base - n))
            case "$filter" in
                done) [ "$missing" -eq 0 ] || continue ;;
                todo) [ "$missing" -gt 0 ] || continue ;;
            esac
            printf "%-12s %6d  %4d%%  %8d\n" "$loc" "$n" "$pct" "$missing"
        done
    } | sort -k4 -rn
}

case "${1:-}" in
    ''|all)   print_table all  ;;
    --done)   print_table done ;;
    --todo)   print_table todo ;;
    -h|--help)
        sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
        ;;
    *)        print_one_locale_missing "$1" ;;
esac
