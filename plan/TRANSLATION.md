# Translation Completion Plan

The default `app/src/main/res/values/strings.xml` currently has **804** strings.
Each `values-<locale>/strings.xml` should mirror the same set of keys.

## Current status

| Locale     | Strings | %    | Missing |
|------------|--------:|-----:|--------:|
| ar         |     804 | 100% |       0 |
| b+es+419   |     804 | 100% |       0 |
| cs         |     804 | 100% |       0 |
| da         |     804 | 100% |       0 |
| de         |     804 | 100% |       0 |
| el         |     804 | 100% |       0 |
| en-rGB     |     804 | 100% |       0 |
| es         |     804 | 100% |       0 |
| fi         |     804 | 100% |       0 |
| fil        |     804 | 100% |       0 |
| fr         |     804 | 100% |       0 |
| it         |     804 | 100% |       0 |
| ja         |     804 | 100% |       0 |
| ko         |     804 | 100% |       0 |
| nl         |     804 | 100% |       0 |
| no         |     804 | 100% |       0 |
| pl         |     804 | 100% |       0 |
| pt-rBR     |     804 | 100% |       0 |
| sv         |     804 | 100% |       0 |
| tr         |     804 | 100% |       0 |
| iw         |     778 |  96% |      26 |
| in         |     317 |  39% |     487 |
| ms         |     317 |  39% |     487 |
| ro         |     315 |  39% |     489 |
| hi         |     316 |  39% |     488 |
| hu         |     316 |  39% |     488 |
| th         |     316 |  39% |     488 |
| uk         |     316 |  39% |     488 |
| ur         |     316 |  39% |     488 |
| vi         |     316 |  39% |     488 |
| zh-rTW     |     316 |  39% |     488 |

The 10 partial locales share **nearly the same** missing set (it diverges by 1–2 keys), so the same English source can be reused across all of them in lock-step. This is the structural lever that makes the work batchable.

Refresh the table or inspect a single locale with `scripts/translation_status.sh`:

```sh
scripts/translation_status.sh            # full table, worst-first
scripts/translation_status.sh --todo     # only incomplete locales
scripts/translation_status.sh --done     # only complete locales
scripts/translation_status.sh iw         # list the missing keys for one locale
```

## Strategy

1. **One feature-prefix at a time.** The 488 missing keys group cleanly by prefix (`settings_`, `tag_val_`, `composer_`, …). Each prefix is a self-contained batch — small enough to translate and review, large enough to be worth committing.
2. **Same batch across all locales in one commit.** When you translate, e.g., the `tryon_*` batch, do it for all 10 locales in one PR. The English source is identical so the workflow is "translate once per locale, all keys in the batch" — keeps the diff focused.
3. **Build after every batch.** `./gradlew assembleDebug` catches missing keys (`%s` mismatches, unescaped apostrophes) before they ship.
4. **No partial keys.** Don't add a `<string name="foo">` that's still in English unless you mark it `translatable="false"` or wrap it in a TODO comment — otherwise the line-count diff stops being a useful signal.

## Phases

### Phase 0 — Hebrew (`iw`), 26 keys
All 26 missing keys are in the `composer_*` family (`composer_vibe_*`, `composer_weather_*`, `composer_layer_*`, `composer_precip_*`, `composer_stepper_*`, plus a handful of placeholders). One commit, one locale. Use this as the trial run for the workflow.

### Phase 1 — single-key gaps
- `ro` is missing `settings_rebg_dialog_title`
- `in` and `ms` are missing `about_description`

Fold these into the matching feature-prefix batch in Phase 2 (don't bother with a standalone commit).

### Phase 2 — 488-key bulk (10 locales: `hi`, `hu`, `in`, `ms`, `ro`, `th`, `uk`, `ur`, `vi`, `zh-rTW`)

Batches, ordered easy → hard. "Easy" means short, low-context strings (single-word tag values, short button labels). "Hard" means full sentences with placeholders that need re-reading in context.

| #  | Prefix(es)                                                         | Keys | Notes |
|---:|--------------------------------------------------------------------|-----:|-------|
| 1  | `tag_val_*`                                                        |   84 | One-word color/material/category labels. Highest parallelization payoff — same word everywhere. |
| 2  | `viewer_*`, `action_*`, `replacements_*`, `calendar_*`, `prediction_*` |  9 | Small leftover prefixes. Bundle as one batch. |
| 3  | `about_*`, `credits_*`                                             |   25 | Short marketing-ish strings, no placeholders. |
| 4  | `local_*`, `gap_*`, `dedupe_*`, `repair_*`, `job_*`, `battery_*`, `import_*` |  34 | Background-job / repair-flow strings. Mostly short. |
| 5  | `travel_*`                                                         |   13 | Travel-tab UI. |
| 6  | `shop_*`                                                           |   17 | Shopping-helper UI. |
| 7  | `wardrobe_*`                                                       |   17 | Wardrobe-screen UI. |
| 8  | `ai_*`                                                             |   38 | AI-prompt / dialog strings. Some have `%s`. |
| 9  | `outfit_*` (singular) + `outfits_*` (plural)                       |   54 | Outfit-composer + outfits-list. Some have `%s`. |
| 10 | `composer_*`                                                       |   61 | Composer surface (sheets, dialogs, placeholders). Several `%s`. |
| 11 | `tryon_*`                                                          |   34 | Try-On flow. Some have `%s`. |
| 12 | `settings_*`                                                       |  102 | Biggest. Save for last when reviewer fatigue is lowest. Many descriptions. |

Total: 488. Across 10 locales that's 12 batches × 10 commits ≈ ~120 small reviewable commits — or, if you prefer breadth-over-depth, 12 wide commits (one per batch, touching all 10 locales).

**Recommended cadence:** one batch per session, fan-out across all 10 locales in a single commit. Stops the file count diverging again.

## Workflow per batch

```sh
PREFIX=tag_val      # change per batch
LOCALES="hi hu in ms ro th uk ur vi zh-rTW"

# 1. Extract the English source for the batch, plus an already-translated
#    reference column (German is the most carefully reviewed locale and a
#    good tiebreaker for register/tone).
grep -oE 'name="'"$PREFIX"'_[^"]+"' app/src/main/res/values/strings.xml \
  | sort -u > /tmp/keys.txt
while read -r namekey; do
  key=${namekey#name=\"}; key=${key%\"}
  en=$(grep -oE "<string name=\"$key\">[^<]*</string>" app/src/main/res/values/strings.xml)
  de=$(grep -oE "<string name=\"$key\">[^<]*</string>" app/src/main/res/values-de/strings.xml || echo "—")
  echo -e "$key\t$en\t$de"
done < /tmp/keys.txt > /tmp/batch-en-de.tsv

# 2. For each locale, translate using BOTH the English source and the
#    German reference (see "Use multiple sources" below).

# 3. Append translations to the locale file
#    Drop the <string …>…</string> lines just before the closing </resources>
#    (anywhere is fine — AAPT doesn't care about order)

# 4. Sanity-check
./gradlew assembleDebug
git diff --stat   # one prefix-batch, N locales
```

### Use multiple sources, not just English

English strings in this app are often **terse UI labels** ("Tops", "Fill missing", "Refine") whose intent is ambiguous in isolation. Single-source machine translation will get the surface meaning right but miss register, tone, and disambiguation. Translating from two source languages closes most of that gap at near-zero extra cost.

**Minimum**: when translating a batch into locale `X`, feed the translator (LLM or human) **both** of these per key:

1. `values/strings.xml` — the English source (canonical meaning).
2. `values-de/strings.xml` — the German translation (most-reviewed locale; resolves register/formality and surfaces app-specific vocabulary like "Closet" → "Kleiderschrank", "Wardrobe" → "Garderobe").

**Better**: also include a third locale typologically close to the target — e.g., `values-fr` when translating into `ro` (Romance family), `values-ja` when translating into `ko`, `values-pl` when translating into `cs`/`uk`. The closer reference catches loan-words and shared idioms that English misses.

When the references disagree (e.g., English uses a noun, German uses an imperative verb, French uses an infinitive), follow the convention of the **target** locale's UI norms — not the English source. A button labeled "Save" is `Speichern` (infinitive) in DE, `Enregistrer` (infinitive) in FR, `保存` (verb stem) in JA, all from the same English word. The references show you the pattern.

For the actual translation step, a one-shot LLM call per locale with the whole batch in TSV works well — pass the EN + DE columns and ask for the target column. Review the output before pasting back. Mark anything ambiguous (e.g., `%s` order in sentences) with a `<!-- REVIEW -->` comment.

## Stop conditions

- All locales report 804 strings — verify with the status snippet at the top.
- `./gradlew lintDebug` does not emit `MissingTranslation` warnings.
- Spot-check 2–3 screens per locale on a device with that locale forced (Settings → System → Languages, or the in-app language toggle).

## What NOT to do

- **Don't paste English into a locale file as a placeholder.** It silently makes the line count match while leaving the user in the wrong language. If you must stub, set `translatable="false"` and accept that the default fallback wins.
- **Don't bulk-machine-translate without review.** `tag_val_*` is safe; anything with a `%s` or a UI verb (Save / Discard / Cancel) needs eyes.
- **Don't reorder the default `strings.xml`.** The locale files don't need to match order, but the default is the reference index — keep the section comments stable.
- **Don't add new keys mid-translation pass.** Finish a batch, commit, then add features. New keys go to all 21 fully-translated locales in the same commit (see CLAUDE.md → "Multi-language is mandatory").
