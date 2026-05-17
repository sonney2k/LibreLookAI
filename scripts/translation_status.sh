#!/usr/bin/env bash
# Report translation completeness for every values-<locale>/strings.xml,
# measured against the default app/src/main/res/values/strings.xml.
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
RES_DIR="$REPO_ROOT/app/src/main/res"
DEFAULT_FILE="$RES_DIR/values/strings.xml"

if [ ! -f "$DEFAULT_FILE" ]; then
    echo "error: $DEFAULT_FILE not found — run from repo root" >&2
    exit 1
fi

extract_keys() {
    grep -oE 'name="[^"]+"' "$1" | sort -u
}

base_keys() { extract_keys "$DEFAULT_FILE"; }
base_count() { base_keys | wc -l | tr -d ' '; }

print_one_locale_missing() {
    local loc="$1"
    local file="$RES_DIR/values-$loc/strings.xml"
    if [ ! -f "$file" ]; then
        echo "error: $file not found" >&2
        exit 1
    fi
    local base
    base=$(base_count)
    local present
    present=$(extract_keys "$file" | wc -l | tr -d ' ')
    echo "Locale: $loc"
    echo "Strings: $present / $base ($((present * 100 / base))%)"
    echo "Missing keys:"
    comm -23 <(base_keys) <(extract_keys "$file") | sed 's/^name="/  /; s/"$//'
}

print_table() {
    local filter="${1:-all}"
    local base
    base=$(base_count)
    printf "Default: %d strings (%s)\n\n" "$base" "$DEFAULT_FILE"
    printf "%-12s %6s  %5s  %8s\n" "locale" "count" "pct" "missing"
    printf -- '------------ ------  -----  --------\n'
    {
        for f in "$RES_DIR"/values-*/strings.xml; do
            local loc
            loc=$(basename "$(dirname "$f")" | sed 's/values-//')
            local n
            n=$(extract_keys "$f" | wc -l | tr -d ' ')
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
        sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'
        ;;
    *)        print_one_locale_missing "$1" ;;
esac
