#!/usr/bin/env python3
"""
Convert MEMORY benchmark YAMLs from DURATION_CONTROLLED (targetMicros)
to FIXED_STEP (memorySteps) mode, for both shared/sharded × memory8/memory16.

Directory layout expected:
  baseline/{shared,sharded}/{memory8,memory16}/{50us,100us,200us,300us,500us}/*.yaml

Each YAML has:
    targetMillis: 0
    targetMicros: <N>
which will be replaced with:
    memorySteps: <M>       # M derived from linear scaling of 1100 steps ≈ 200 µs

Also updates (optional):
  - warmupSeconds: 0  ->  warmupSeconds: 10        (--no-warmup to skip)
  - slowExecutionMicros: <old>  ->  ~1.1 × target  (--no-slow to skip)

Backups: original file is copied to <file>.bak_<timestamp> before first edit
         unless --no-backup is passed.
Restore: --restore reverts the latest .bak_<timestamp> for every file
         that has one.
"""

from __future__ import annotations
import argparse
import re
import shutil
import sys
import time
from pathlib import Path

# ---- Calibration table: 1100 memorySteps ≈ 200 µs on this hardware ----
# bufferMB=512, RANDOM, writeBack=false. Linear scaling.
STEPS_BY_MICROS = {
    50:  275,
    100: 550,
    200: 1100,
    300: 1650,
    500: 2750,
}

# slowExecutionMicros ≈ 1.1 × target, rounded to something readable
SLOW_MICROS_BY_TARGET = {
    50:  60,
    100: 120,
    200: 240,
    300: 340,
    500: 550,
}

REPO_ROOT = Path(__file__).resolve().parent.parent  # tools/ -> repo root
BASELINE_ROOT = REPO_ROOT / "baseline"

MODES = ("shared", "sharded")
WORKER_DIRS = ("memory8", "memory16")
TARGET_DIRS = ("50us", "100us", "200us", "300us", "500us")


def target_micros_from_dir(name: str) -> int:
    """'50us' -> 50."""
    m = re.match(r"(\d+)us$", name)
    if not m:
        raise ValueError(f"Cannot parse target dir name: {name}")
    return int(m.group(1))


def find_yaml_files() -> list[tuple[Path, int]]:
    """Return [(path, target_micros)] for every YAML we should touch."""
    out: list[tuple[Path, int]] = []
    for mode in MODES:
        for wdir in WORKER_DIRS:
            for tdir in TARGET_DIRS:
                d = BASELINE_ROOT / mode / wdir / tdir
                if not d.is_dir():
                    print(f"[skip] missing dir: {d}", file=sys.stderr)
                    continue
                for yml in d.glob("*.yaml"):
                    out.append((yml, target_micros_from_dir(tdir)))
    return out


def convert_text(text: str, target_us: int, *,
                 update_warmup: bool, update_slow: bool) -> tuple[str, list[str]]:
    """
    Perform in-place substitutions on YAML text. Returns (new_text, notes).
    Regex-based on purpose: preserves comments, ordering, indentation.
    """
    notes: list[str] = []
    new_text = text

    steps = STEPS_BY_MICROS[target_us]

    # 1) Replace `targetMicros: <N>` line with `memorySteps: <steps>` (same indent).
    pat_target_micros = re.compile(
        r"^([ \t]*)targetMicros:[ \t]*\d+[ \t]*(#.*)?$",
        re.MULTILINE,
    )
    m = pat_target_micros.search(new_text)
    if m:
        indent = m.group(1)
        new_text = pat_target_micros.sub(
            f"{indent}memorySteps: {steps}", new_text, count=1,
        )
        notes.append(f"targetMicros -> memorySteps: {steps}")
    else:
        # Already converted? Double-check that memorySteps exists.
        if not re.search(r"^[ \t]*memorySteps:[ \t]*\d+", new_text, re.MULTILINE):
            notes.append("!! WARNING: no targetMicros AND no memorySteps found")

    # 2) Remove `targetMillis: 0` line entirely (only when value is exactly 0).
    pat_target_millis_zero = re.compile(
        r"^[ \t]*targetMillis:[ \t]*0[ \t]*(#.*)?\r?\n",
        re.MULTILINE,
    )
    if pat_target_millis_zero.search(new_text):
        new_text = pat_target_millis_zero.sub("", new_text, count=1)
        notes.append("removed 'targetMillis: 0'")

    # 3) warmupSeconds: 0  ->  warmupSeconds: 10
    if update_warmup:
        pat_warmup = re.compile(
            r"^([ \t]*)warmupSeconds:[ \t]*0[ \t]*(#.*)?$",
            re.MULTILINE,
        )
        if pat_warmup.search(new_text):
            new_text = pat_warmup.sub(
                lambda m: f"{m.group(1)}warmupSeconds: 10",
                new_text, count=1,
            )
            notes.append("warmupSeconds 0 -> 10")

    # 4) slowExecutionMicros: <anything>  ->  1.1 × target
    if update_slow:
        new_slow = SLOW_MICROS_BY_TARGET[target_us]
        pat_slow = re.compile(
            r"^([ \t]*)slowExecutionMicros:[ \t]*\d+[ \t]*(#.*)?$",
            re.MULTILINE,
        )
        if pat_slow.search(new_text):
            new_text = pat_slow.sub(
                lambda m: f"{m.group(1)}slowExecutionMicros: {new_slow}",
                new_text, count=1,
            )
            notes.append(f"slowExecutionMicros -> {new_slow}")

    return new_text, notes


def latest_backup(path: Path) -> Path | None:
    backups = sorted(path.parent.glob(path.name + ".bak_*"))
    return backups[-1] if backups else None


def do_convert(args) -> int:
    files = find_yaml_files()
    if not files:
        print("No YAML files found under", BASELINE_ROOT)
        return 1

    stamp = time.strftime("%Y%m%d_%H%M%S")
    changed = 0
    for path, target_us in files:
        original = path.read_text()
        new_text, notes = convert_text(
            original, target_us,
            update_warmup=not args.no_warmup,
            update_slow=not args.no_slow,
        )
        if new_text == original:
            print(f"[unchanged] {path.relative_to(REPO_ROOT)}")
            continue

        rel = path.relative_to(REPO_ROOT)
        print(f"[change]    {rel}  ({target_us}us)")
        for n in notes:
            print(f"              - {n}")

        if args.dry_run:
            continue

        if not args.no_backup:
            bak = path.with_name(path.name + f".bak_{stamp}")
            shutil.copy2(path, bak)

        path.write_text(new_text)
        changed += 1

    if args.dry_run:
        print(f"\n(dry-run) {sum(1 for _ in files)} files scanned, "
              f"{len(files)} candidates listed. Nothing written.")
    else:
        print(f"\nDone. Modified {changed} file(s).")
        if not args.no_backup:
            print(f"Backups: *.bak_{stamp} next to each file.")
    return 0


def do_restore(args) -> int:
    files = find_yaml_files()
    restored = 0
    for path, _ in files:
        bak = latest_backup(path)
        if bak is None:
            continue
        print(f"[restore] {path.relative_to(REPO_ROOT)} <- {bak.name}")
        if not args.dry_run:
            shutil.copy2(bak, path)
            restored += 1
    if args.dry_run:
        print("(dry-run) no files touched.")
    else:
        print(f"\nRestored {restored} file(s) from latest backup.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dry-run", action="store_true",
                    help="Show what would change; do not write files.")
    ap.add_argument("--no-backup", action="store_true",
                    help="Do not create .bak_<timestamp> copies.")
    ap.add_argument("--no-warmup", action="store_true",
                    help="Do not touch warmupSeconds.")
    ap.add_argument("--no-slow", action="store_true",
                    help="Do not touch slowExecutionMicros.")
    ap.add_argument("--restore", action="store_true",
                    help="Restore each YAML from its latest .bak_<timestamp>.")
    args = ap.parse_args()

    if args.restore:
        return do_restore(args)
    return do_convert(args)


if __name__ == "__main__":
    sys.exit(main())
