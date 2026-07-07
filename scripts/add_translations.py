#!/usr/bin/env python3
"""Bulk-insert translated <string> entries into per-locale strings.xml files.

Companion to translation_status.sh. Use this when you add new user-facing keys
to a module's res/values/strings.xml and need to mirror them into the
translated locales (CLAUDE.md: "every user-facing string ... mirror in every
values-*/strings.xml in the same change").

Input is a JSON file mapping each locale directory to a key→value map, with
RAW (unescaped) translation values:

    {
      "values-de": { "settings_hero_edit": "Bearbeiten", ... },
      "values-fr": { "settings_hero_edit": "Modifier",   ... }
    }

For each locale this script:
  * applies Android XML escaping (&, <, >, ', ") so authored values never have
    to worry about escaping — format specifiers like %1$d pass through untouched;
  * inserts the rows just before </resources> under a comment header;
  * skips any key already present (idempotent — safe to re-run);
  * skips locale dirs that have no strings.xml (e.g. the vestigial values-ru).

Strings live in the module that *references* them: feature strings in :app,
shared-UI strings in :core:designsystem (the § 1 res-split). Pick the target
with --module.

Usage:
    python3 scripts/add_translations.py path/to/batch.json
    python3 scripts/add_translations.py path/to/batch.json --header "Settings redesign (V1)"
    python3 scripts/add_translations.py path/to/batch.json --module designsystem

Run ./gradlew :app:assembleDebug afterwards — mergeDebugResources validates the
XML and the format specifiers across every locale.
"""
import argparse
import json
import os
import re

REPO_ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

MODULE_RES_DIRS = {
    "app": os.path.join(REPO_ROOT, "app", "src", "main", "res"),
    "designsystem": os.path.join(REPO_ROOT, "core", "designsystem", "src", "main", "res"),
    "service": os.path.join(REPO_ROOT, "core", "service", "src", "main", "res"),
    "auth": os.path.join(REPO_ROOT, "feature", "auth", "src", "main", "res"),
    "billing": os.path.join(REPO_ROOT, "feature", "billing", "src", "main", "res"),
}


def escape(value: str) -> str:
    """Escape a raw string for an Android <string> resource body."""
    value = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    value = value.replace("'", "\\'").replace('"', '\\"')
    return value


def apply(batch: dict, header: str, res_dir: str) -> None:
    for locale, kv in batch.items():
        path = os.path.join(res_dir, locale, "strings.xml")
        if not os.path.exists(path):
            print(f"!! {locale}: no strings.xml — skipped")
            continue
        with open(path, encoding="utf-8") as f:
            text = f.read()
        existing = set(re.findall(r'name="([^"]+)"', text))
        rows = [
            f'    <string name="{k}">{escape(v)}</string>'
            for k, v in kv.items()
            if k not in existing
        ]
        if not rows:
            print(f"== {locale}: nothing to add")
            continue
        block = f"\n    <!-- {header} -->\n" + "\n".join(rows) + "\n"
        text = text.replace("</resources>", block + "</resources>", 1)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"++ {locale}: +{len(rows)}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Insert translated strings into per-locale strings.xml.")
    parser.add_argument("batch", help="JSON file mapping locale dir -> {key: raw value}")
    parser.add_argument("--header", default="Added strings", help="Comment header placed above each inserted block")
    parser.add_argument(
        "--module",
        default="app",
        choices=sorted(MODULE_RES_DIRS),
        help="Module whose res/ receives the strings (default: app)",
    )
    args = parser.parse_args()
    with open(args.batch, encoding="utf-8") as f:
        apply(json.load(f), args.header, MODULE_RES_DIRS[args.module])


if __name__ == "__main__":
    main()
