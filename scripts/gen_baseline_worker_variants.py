#!/usr/bin/env python3
"""
Generate worker/maxInflight variants of every YAML under baseline/.

For each existing baseline YAML (workerCount=32, maxInflight=32) we
produce sibling files with the same content but different
(workerCount, maxInflight) pairs, and a matching run-name suffix so
results land in a distinct results/ subfolder.

Default variants (edit VARIANTS below to change):
    (workerCount=16, maxInflight=18)
    (workerCount=8,  maxInflight=8)

Comments, key order, quoting, and blank lines in the source YAML are
preserved by doing narrow regex substitutions instead of a PyYAML
round-trip.

Usage:
    python3 scripts/gen_baseline_worker_variants.py
    python3 scripts/gen_baseline_worker_variants.py --dry-run
    python3 scripts/gen_baseline_worker_variants.py --root baseline

The script is idempotent: rerunning overwrites existing variant files
but never touches the original *_baseline.yaml sources. Variant files
whose name would collide with the source (e.g. a variant matching the
source's own worker/maxInflight) are skipped.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import Iterable

# (workerCount, maxInflight) variants to generate for every baseline.
VARIANTS: list[tuple[int, int]] = [
    (16, 18),
    (8, 8),
]

# Only touch top-level `workerCount:` and `maxInflight:` inside the
# `global:` block. We accept any leading whitespace (2- or 4-space
# indent) but require the key to be at start-of-line-after-indent so
# nested occurrences (there are none today, but be safe) are not hit.
RE_WORKER  = re.compile(r"^(?P<indent>\s+)workerCount:\s*\d+\s*$", re.MULTILINE)
RE_INFLIGHT = re.compile(r"^(?P<indent>\s+)maxInflight:\s*\d+\s*$", re.MULTILINE)

# Matches `  - name: <run-name>` inside the runs: block.
RE_RUN_NAME = re.compile(r"^(?P<prefix>\s*-\s*name:\s*)(?P<name>\S+)\s*$", re.MULTILINE)


def variant_suffix(worker: int, inflight: int) -> str:
    return f"_w{worker}_m{inflight}"


def patch_yaml(text: str, worker: int, inflight: int, run_suffix: str) -> str:
    """Return `text` with workerCount, maxInflight, and every runs[].name patched."""
    def _set_worker(m: re.Match) -> str:
        return f"{m.group('indent')}workerCount: {worker}"
    def _set_inflight(m: re.Match) -> str:
        return f"{m.group('indent')}maxInflight: {inflight}"
    def _set_name(m: re.Match) -> str:
        return f"{m.group('prefix')}{m.group('name')}{run_suffix}"

    out = RE_WORKER.sub(_set_worker, text, count=1)
    out = RE_INFLIGHT.sub(_set_inflight, out, count=1)
    out = RE_RUN_NAME.sub(_set_name, out)
    return out


def iter_baseline_yamls(root: Path) -> Iterable[Path]:
    for p in sorted(root.rglob("*.yaml")):
        # Do not treat previously-generated variants as sources.
        stem = p.stem
        if any(stem.endswith(variant_suffix(w, m)) for (w, m) in VARIANTS):
            continue
        yield p


def variant_path(src: Path, worker: int, inflight: int) -> Path:
    return src.with_name(f"{src.stem}{variant_suffix(worker, inflight)}{src.suffix}")


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", default="baseline",
                    help="Directory to scan for baseline YAMLs (default: baseline).")
    ap.add_argument("--dry-run", action="store_true",
                    help="Print planned writes without touching the filesystem.")
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    if not root.is_dir():
        print(f"[error] --root {root} is not a directory", file=sys.stderr)
        return 2

    sources = list(iter_baseline_yamls(root))
    if not sources:
        print(f"[warn] no baseline YAMLs found under {root}", file=sys.stderr)
        return 1

    print(f"[gen] scanning {root}  ({len(sources)} baseline files, "
          f"{len(VARIANTS)} variants each → {len(sources) * len(VARIANTS)} outputs)")

    written = skipped = 0
    for src in sources:
        try:
            text = src.read_text(encoding="utf-8")
        except OSError as e:
            print(f"[skip] {src}: {e}", file=sys.stderr)
            skipped += 1
            continue

        for (worker, inflight) in VARIANTS:
            dst = variant_path(src, worker, inflight)
            if dst == src:
                skipped += 1
                continue
            patched = patch_yaml(text, worker, inflight, variant_suffix(worker, inflight))

            rel = dst.relative_to(root)
            if args.dry_run:
                print(f"[dry-run] would write {rel}")
                continue

            dst.write_text(patched, encoding="utf-8")
            print(f"[write]   {rel}")
            written += 1

    if args.dry_run:
        print(f"[done] dry-run: {len(sources) * len(VARIANTS)} files planned")
    else:
        print(f"[done] wrote {written} files, skipped {skipped}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

