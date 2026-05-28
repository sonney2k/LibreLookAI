#!/usr/bin/env python3
"""Split an oversized Kotlin file into cohesive same-package siblings.

Built for the ≤500-line file rule (see CLAUDE.md → "File size"). It moves
declarations out of a big source file into new files **in the same package**, so
cross-references between the pieces need no imports. Two modes:

  - plain move   : data classes, top-level functions, top-level vals/consts.
  - extension    : class methods → top-level `internal fun Receiver.method()`
                   extension functions (the only way to "split" a Kotlin class).

It is a *helper*, not magic: it computes per-file imports by simple-name match and
performs the mechanical text edits, but the compiler is the source of truth. The
intended loop is: configure → run → `./gradlew :app:compileDebugKotlin` → fix what
the compiler flags (see "After running" below) → repeat → test → commit one file.

------------------------------------------------------------------------------
Usage (write a tiny driver, run it from the repo root):

    import sys; sys.path.insert(0, "scripts")
    from kt_split import split

    split(
        src="app/src/main/java/com/librelookai/foo/BigScreen.kt",
        pkg="package com.librelookai.foo",
        outputs=[
            # (path, [(start,end), ...], mode)   mode = "plain" | "ext"
            ("app/src/main/java/com/librelookai/foo/BigDialogs.kt", [(610,740)], "plain"),
            ("app/src/main/java/com/librelookai/foo/BigViewModelAudit.kt", [(319,520)], "ext"),
        ],
        stay=[(120,402), (670,1409)],          # body ranges kept in `src`
        bumps=[                                 # private -> internal, applied to whole text first
            ("private fun helper(", "internal fun helper("),
        ],
        receiver="BigViewModel",                # required if any output uses mode "ext"
    )

Line ranges are 1-based inclusive and refer to the CURRENT file. `stay` is what
remains in `src` (its imports are recomputed too). Extract from the original in one
`split()` call when you can; if you must run multiple passes, RE-DERIVE line numbers
after each run — a pass rewrites the file and shifts every line below it.

------------------------------------------------------------------------------
Gotchas baked in from the LibreLookAI refactor (Phases 1-3):

  * Section boundaries must land on a declaration's LEADING annotation/comment
    (e.g. the `@OptIn` line above `@Composable`), not the `fun`/`@Composable`
    line — otherwise you orphan an annotation onto the wrong file (syntax error).
  * Include `const` when scanning declarations; a dropped top-level `const val`
    only shows up as an "Unresolved reference" at compile time.
  * `getValue`/`setValue` are needed by `by` delegation but never appear as plain
    tokens — added automatically when the body contains `by`.
  * Extension funcs can't see `private` members → bump every private field/method
    they touch to `internal` (compiler lists them as "Cannot access 'X': private").
  * Companion-object members referenced from an extension file need
    `Receiver.NAME` qualification (use `qualify`, below) or move them to a
    top-level `internal const`.
  * member → extension changes call sites: same-package callers are unaffected,
    but OTHER packages need `import <pkg>.<fn>`. The compiler flags these as
    "Unresolved reference '<fn>'"; cascade noise (`it`, `id`, `size`…) resolves
    once the real method import is added, so filter to the known method names.

After running, fix compiler output with `add_imports()` / `qualify()` (below).
"""
import re

_FUN_RE = re.compile(r'^    (private |internal |public )?(suspend )?fun (\w+)\(')


def _needed_imports(body_text, import_lines):
    keep = []
    for imp in import_lines:
        spec = imp[len("import "):].strip()
        name = spec.split(" as ")[1].strip() if " as " in spec else spec.split(".")[-1].strip()
        if re.search(r"\b" + re.escape(name) + r"\b", body_text):
            keep.append(imp)
    if re.search(r"\bby\b", body_text):
        for op in ("import androidx.compose.runtime.getValue\n",
                   "import androidx.compose.runtime.setValue\n"):
            if op in import_lines and op not in keep:
                keep.append(op)
    return sorted(set(keep))


def split(src, pkg, outputs, stay, bumps=(), receiver=None):
    """Extract `outputs` from `src`, rewrite `src` as `stay`. See module docstring."""
    with open(src) as f:
        lines = f.readlines()

    text = "".join(lines)
    for old, new in bumps:
        n = text.count(old)
        assert n == 1, f"expected exactly 1 occurrence of {old!r}, found {n}"
        text = text.replace(old, new)
    lines = text.splitlines(keepends=True)
    import_lines = [ln for ln in lines if ln.startswith("import ")]

    def body_for(ranges):
        out = []
        for a, b in ranges:
            out.extend(lines[a - 1:b])
        return out

    def to_extensions(block):
        out = []
        for ln in block:
            m = _FUN_RE.match(ln)
            if m:
                assert receiver, "receiver= is required for mode 'ext'"
                suspend = m.group(2) or ""
                out.append(f"internal {suspend}fun {receiver}.{m.group(3)}{ln[m.end() - 1:]}")
            else:
                out.append(ln)
        return out

    def write(path, ranges, mode):
        block = body_for(ranges)
        if mode == "ext":
            block = to_extensions(block)
        while block and block[0].strip() == "":
            block.pop(0)
        imps = _needed_imports("".join(block), import_lines)
        with open(path, "w") as f:
            f.writelines([pkg + "\n", "\n"] + imps + ["\n"] + block)
        print(f"{path}: {2 + len(imps) + 1 + len(block)} lines, {len(imps)} imports ({mode})")

    for path, ranges, mode in outputs:
        write(path, ranges, mode)

    kept = body_for(stay)
    while kept and kept[0].strip() == "":
        kept.pop(0)
    imps = _needed_imports("".join(kept), import_lines)
    with open(src, "w") as f:
        f.writelines([pkg + "\n", "\n"] + imps + ["\n"] + kept)
    print(f"{src}: {2 + len(imps) + 1 + len(kept)} lines, {len(imps)} imports (STAY)")


def add_imports(adds):
    """adds = {file_path: [fully.qualified.symbol, ...]}. Idempotent; inserts after the
    last existing import from the same package (else after the `package` line). Use for
    the cross-package call sites the compiler flags after a member→extension move."""
    for path, symbols in adds.items():
        lines = open(path).readlines()
        ins = next((i for i, l in enumerate(lines) if l.startswith("package ")), -1) + 1
        for sym in symbols:
            prefix = "import " + sym.rsplit(".", 1)[0] + "."
            for i, l in enumerate(lines):
                if l.startswith(prefix):
                    ins = i + 1
        new = [f"import {s}\n" for s in symbols
               if not any(l.strip() == f"import {s}" for l in lines)]
        lines[ins:ins] = new
        open(path, "w").writelines(lines)
        print(f"{path}: +{len(new)} imports")


def qualify(path, receiver, names):
    """Prefix bare references to companion members with `receiver.` in one file
    (e.g. TAG -> Receiver.TAG). Skips already-qualified occurrences."""
    text = open(path).read()
    for n in names:
        text = re.sub(r"(?<![\w.])" + re.escape(n) + r"\b", f"{receiver}.{n}", text)
    open(path, "w").write(text)
    print(f"{path}: qualified {', '.join(names)} with {receiver}.")


if __name__ == "__main__":
    print(__doc__)
