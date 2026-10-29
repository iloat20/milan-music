#!/usr/bin/env python3
"""Entry-by-entry diff of two APKs.

Why this exists
---------------
"This build only differs in X" has been a manual claim, made by unzipping both
files and comparing by eye. That is the one claim in the release notes a reader
cannot check without repeating the work, and it is exactly the claim that decides
whether the shipped artifact is the code that was reviewed.

This does the comparison mechanically: every entry's compressed size, CRC and
SHA-256 of its decompressed bytes. Output is grouped as removed / added / changed,
so a diff of "1 changed entry" is a fact rather than a memory.

Note on what a match does and does not prove: identical entries prove the two
packages carry the same bytes for those entries. They do not prove the build is
reproducible — a third build could differ in a different entry. Reproducibility
needs the same commit built twice, which is what CI does.

Usage
-----
    python review/apk-diff.py A.apk B.apk
    python review/apk-diff.py A.apk B.apk --json review/raw/<date>/apk-diff.json
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
import zipfile
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Entry:
    name: str
    compressed: int
    uncompressed: int
    crc: int
    digest: str

    @property
    def key(self) -> tuple[int, int, int, str]:
        return (self.compressed, self.uncompressed, self.crc, self.digest)


def read_apk(path: Path) -> dict[str, Entry]:
    entries: dict[str, Entry] = {}
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            # Directories carry no content; comparing them adds noise only.
            if info.is_dir():
                continue
            data = archive.read(info.filename)
            entries[info.filename] = Entry(
                name=info.filename,
                compressed=info.compress_size,
                uncompressed=info.file_size,
                crc=info.CRC,
                digest=hashlib.sha256(data).hexdigest(),
            )
    return entries


def describe(before: Entry | None, after: Entry | None) -> str:
    if before is None:
        return f"added    {after.uncompressed:>9} B"
    if after is None:
        return f"removed  {before.uncompressed:>9} B"
    delta = after.uncompressed - before.uncompressed
    return f"changed  {before.uncompressed:>9} B -> {after.uncompressed:>9} B ({delta:+,})"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("--json", type=Path, default=None)
    args = parser.parse_args()

    for path in (args.before, args.after):
        if not path.is_file():
            print(f"missing: {path}", file=sys.stderr)
            return 2

    before = read_apk(args.before)
    after = read_apk(args.after)

    removed = sorted(set(before) - set(after))
    added = sorted(set(after) - set(before))
    changed = sorted(n for n in set(before) & set(after) if before[n].key != after[n].key)

    print(f"A {args.before.name}: {len(before)} entries, {args.before.stat().st_size:,} B")
    print(f"B {args.after.name}: {len(after)} entries, {args.after.stat().st_size:,} B")
    print(f"identical entries: {len(set(before) & set(after)) - len(changed)}")
    print()

    for name in removed:
        print(f"  {describe(before[name], None)}  {name}")
    for name in added:
        print(f"  {describe(None, after[name])}  {name}")
    for name in changed:
        print(f"  {describe(before[name], after[name])}  {name}")
        if before[name].uncompressed == after[name].uncompressed:
            # Same length, different bytes: the case that a size comparison misses.
            print(f"           sha256 {before[name].digest[:16]} -> {after[name].digest[:16]}")

    print()
    total_removed = sum(before[n].uncompressed for n in removed)
    total_added = sum(after[n].uncompressed for n in added)
    print(f"summary: {len(removed)} removed, {len(added)} added, {len(changed)} changed"
          f" | uncompressed {args.after.stat().st_size:,} B on disk"
          f" | content delta {total_added - total_removed:+,} B")

    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps({
            "created": time.strftime("%Y-%m-%dT%H:%M:%S"),
            "a": {"path": args.before.name, "size": args.before.stat().st_size, "entries": len(before),
                  "sha256": hashlib.sha256(args.before.read_bytes()).hexdigest()},
            "b": {"path": args.after.name, "size": args.after.stat().st_size, "entries": len(after),
                  "sha256": hashlib.sha256(args.after.read_bytes()).hexdigest()},
            "removed": removed,
            "added": added,
            "changed": [
                {"name": n, "before": before[n].__dict__, "after": after[n].__dict__}
                for n in changed
            ],
        }, indent=2), encoding="utf-8")
        print(f"raw -> {args.json}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
