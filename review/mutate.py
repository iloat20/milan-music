#!/usr/bin/env python3
"""Mutation score for the ReplayGain / ID3 decode path.

Why this exists
---------------
A green suite says the code does not contradict the tests. It says nothing about
whether the tests would notice if the code were wrong. Mutation testing measures
exactly that: apply one semantic change at a time, run the tests, and count how
many changes the suite catches. A surviving mutant is a place where a real bug
would ship silently.

The score is `killed / (killed + survived)`; mutants that fail to compile are
excluded because they were never a valid program.

Usage
-----
    python review/mutate.py                 # all mutants
    python review/mutate.py --only M6 M7    # a subset
    python review/mutate.py --list

Exit code is 1 when any mutant survives that the harness expected to be killed,
so this can be wired into a gate later without touching the thresholds today.

Implementation note: each mutant is a literal find/replace inside one file. The
original text is restored in a `finally` and verified by SHA-256 — a harness that
can leave the tree mutated is worse than no harness.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app" / "src" / "main" / "java" / "com" / "milan" / "music"

JAVA_HOME = os.environ.get(
    "JAVA_HOME",
    r"C:\Users\Administrator\AppData\Local\Programs\Eclipse Adoptium\jdk-17.0.19.10-hotspot",
)

TEST_FILTERS = [
    "com.milan.music.play.Id3StreamReaderTest",
    "com.milan.music.play.Id3StreamFuzzTest",
    "com.milan.music.play.ReplayGainTest",
]

# The suite as it stood before the fuzz harness existed. Running both and diffing
# the survivors is what turns "100% killed" into a measurement: it shows whether the
# new test added kill power or just restated the mutants its author had in mind.
LEGACY_FILTERS = [name for name in TEST_FILTERS if not name.endswith("Id3StreamFuzzTest")]


@dataclass(frozen=True)
class Mutant:
    mid: str
    path: Path
    old: str
    new: str
    note: str
    # What the author expects: "killed" means the suite should notice. Recorded so
    # a survivor is a finding rather than a number.
    expect: str = "killed"


ID3 = APP / "play" / "Id3StreamReader.kt"
RG = APP / "play" / "ReplayGain.kt"

MUTANTS = [
    Mutant("M1", ID3, "val frameSize = if (version >= 4) {", "val frameSize = if (version > 4) {",
           "v2.4 frame size read as plain big-endian"),
    Mutant("M2", ID3, "if (flags and 0x40 != 0) {", "if (flags and 0x20 != 0) {",
           "extended-header flag not recognised"),
    Mutant("M3", ID3, "return if (skipFully(stream, size - 4)) size else null",
           "return if (skipFully(stream, size)) size else null",
           "v2.4 extended header measured excluding its own size"),
    Mutant("M4", ID3, "if (frameSize <= 0 || frameSize > remaining - FRAME_HEADER_BYTES) return null",
           "if (frameSize > remaining - FRAME_HEADER_BYTES) return null",
           "non-positive frame size no longer rejected"),
    Mutant("M5", ID3, 'if (frameId == "TXXX") {', 'if (frameId == "TXXX" || frameId == "APIC") {',
           "cover-art body read instead of skipped"),
    Mutant("M6", ID3, "return trackGain ?: albumGain", "return albumGain ?: trackGain",
           "album gain wins over track gain"),
    Mutant("M7", ID3, "var remaining = declared.coerceAtMost(MAX_TAG_BYTES)",
           "var remaining = declared",
           "declared size used as budget with no cap"),
    Mutant("M8", ID3, "val skipped = stream.skip(remaining)", "val skipped = stream.skip(remaining / 2)",
           "skip advances half, rest falls back to reads"),
    Mutant("M9", RG, "val frameSize = if (version >= 4) {", "val frameSize = if (version > 4) {",
           "buffered v2.4 frame size read as big-endian"),
    Mutant("M10", RG, "if (split >= end) return null", "if (split > end) return null",
           "missing TXXX terminator no longer rejected"),
    Mutant("M11", RG, "private const val MAX_PLAUSIBLE_DB = 60f", "private const val MAX_PLAUSIBLE_DB = 600f",
           "absurd dB accepted up to 600"),
    Mutant("M12", RG, "coerceIn(MIN_NORMALIZED_VOLUME, 1f)", "coerceIn(0f, 1f)",
           "volume floor removed"),
    Mutant("M13", RG, "i += 2", "i += 1",
           "UTF-16 terminator scan steps one byte"),
    Mutant("M14", RG, "if (isLast) return null", "if (isLast && type == 0) return null",
           "FLAC last-block flag ignored"),
    # The three below target operators no hand-written fixture distinguishes: padding
    # detection, the exact loop bound, and the syncsafe high-bit guard. If the fuzz
    # harness earns its place anywhere, it is here — a generated input can reach these
    # without anyone writing a case for them.
    Mutant("N1", ID3, "            if (frameHeader[0].toInt() == 0) break",
           "            if (frameHeader[0].toInt() == 0 && frameHeader[1].toInt() == 0) break",
           "padding detected only on a two-byte zero run"),
    Mutant("N2", ID3, "while (remaining >= FRAME_HEADER_BYTES) {",
           "while (remaining > FRAME_HEADER_BYTES) {",
           "walk stops one frame header early"),
    Mutant("N3", RG, "if (byte and 0x80 != 0) return null",
           "if (byte and 0x80 != 0 && byte and 0x40 == 0) return null",
           "syncsafe high-bit guard made conditional"),
]


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run_tests(filters: list[str]) -> tuple[str, str]:
    # A .bat cannot be launched directly by CreateProcess, so this goes through the
    # shell (cmd.exe on Windows, which is what the release scripts already do).
    test_args = " ".join(f'--tests "{name}"' for name in filters)
    cmd = f"gradlew.bat --quiet testDebugUnitTest {test_args}"
    env = dict(os.environ, JAVA_HOME=JAVA_HOME)
    started = time.time()
    proc = subprocess.run(
        cmd, cwd=ROOT, env=env, capture_output=True, text=True, encoding="utf-8",
        errors="replace", shell=True, timeout=900,
    )
    output = (proc.stdout or "") + (proc.stderr or "")
    elapsed = time.time() - started
    if "Compilation error" in output or re.search(r"^e: file:", output, re.M):
        return "COMPILE_ERROR", f"{elapsed:.0f}s"
    if "BUILD SUCCESSFUL" in output:
        return "SURVIVED", f"{elapsed:.0f}s"
    return "KILLED", f"{elapsed:.0f}s"


def apply_mutant(m: Mutant) -> None:
    # Bytes, not text: `read_text` folds CRLF into LF and `write_text` puts it back,
    # which makes every file look "modified" to git and makes the restore check
    # depend on the platform's newline translation rather than on the content.
    text = m.path.read_bytes().decode("utf-8")
    count = text.count(m.old)
    if count != 1:
        raise SystemExit(f"{m.mid}: pattern occurs {count} times in {m.path.name}, expected exactly 1")
    m.path.write_bytes(text.replace(m.old, m.new, 1).encode("utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", nargs="*", default=None)
    parser.add_argument("--suite", choices=("all", "legacy"), default="all",
                        help="legacy = the suite before the fuzz harness existed")
    parser.add_argument("--list", action="store_true")
    args = parser.parse_args()

    filters = TEST_FILTERS if args.suite == "all" else LEGACY_FILTERS

    if args.list:
        for m in MUTANTS:
            print(f"{m.mid:>4}  {m.path.name:20} {m.note}")
        return 0

    selected = [m for m in MUTANTS if not args.only or m.mid in args.only]
    originals = {p: (p.read_bytes(), sha(p)) for p in {m.path for m in selected}}

    results = []
    print(f"{'id':>4}  {'result':<12} {'expect':<8} {'time':>5}  note")
    try:
        for m in selected:
            apply_mutant(m)
            try:
                result, elapsed = run_tests(filters)
            finally:
                original_bytes, digest = originals[m.path]
                m.path.write_bytes(original_bytes)
                if sha(m.path) != digest:
                    raise SystemExit(f"FATAL: {m.path} was not restored")
            results.append({"id": m.mid, "file": m.path.name, "note": m.note,
                            "result": result, "expect": m.expect, "time": elapsed})
            print(f"{m.mid:>4}  {result:<12} {m.expect:<8} {elapsed:>5}  {m.note}")
    finally:
        for path, (data, digest) in originals.items():
            if sha(path) != digest:
                path.write_bytes(data)
                print(f"restored {path.name} after abnormal exit")

    scored = [r for r in results if r["result"] != "COMPILE_ERROR"]
    killed = [r for r in scored if r["result"] == "KILLED"]
    survived = [r for r in scored if r["result"] == "SURVIVED"]
    score = (len(killed) / len(scored) * 100) if scored else 0.0

    print()
    print(f"mutants valid={len(scored)}  killed={len(killed)}  survived={len(survived)}  score={score:.1f}%")
    if survived:
        print("survivors (a real bug here would ship unnoticed):")
        for r in survived:
            print(f"  {r['id']}  {r['file']}  {r['note']}")

    name = "mutation.json" if args.suite == "all" else f"mutation-{args.suite}.json"
    out = ROOT / "review" / "raw" / time.strftime("%Y-%m-%d") / name
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(
        {"suite": args.suite, "score": round(score, 1), "valid": len(scored),
         "killed": len(killed), "survived": len(survived), "mutants": results},
        indent=2, ensure_ascii=False,
    ), encoding="utf-8")
    print(f"raw -> {out.relative_to(ROOT)}")

    unexpected = [r for r in survived if r["expect"] == "killed"]
    return 1 if unexpected else 0


if __name__ == "__main__":
    sys.exit(main())
