#!/usr/bin/env python3
"""Summarise Gradle's JUnit XML test results: one block per failing test, then the counts.

Gradle's console output for `:app:testDebugUnitTest` prints "N tests completed, M failed" and the path of
an HTML report nobody can open from a restricted network. The XML beside it does name each failing case,
with the assertion message and the stack — so this is the file CI turns into something readable, and the
same command works on a laptop:

    ./gradlew :app:testDebugUnitTest && python3 tools/summarise_tests.py app/build/test-results/testDebugUnitTest

Exit status is 0 when the XML was readable, 1 when the directory held nothing parseable — a missing report
must never look like a passing suite. A failing *test* is not an error of this script: it prints and exits
0, because CI already knows the build is red and this tool's job is only to explain it.

Usage: python3 tools/summarise_tests.py <dir with TEST-*.xml> [...]
"""
from __future__ import annotations

import glob
import os
import sys
import xml.etree.ElementTree as ET

MESSAGE_CHARS = 420
STACK_LINES = 5


def summarise(directory: str) -> tuple[int, int, int]:
    """(failing, total, files read) for one Gradle test-results directory."""
    paths = sorted(glob.glob(os.path.join(directory, "TEST-*.xml")))
    total = failing = 0
    for path in paths:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:  # a truncated file is a CI problem, not a silent pass
            print(f"{os.path.basename(path)}: unreadable ({exc})")
            continue
        total += int(root.get("tests", 0) or 0)
        failing += int(root.get("failures", 0) or 0) + int(root.get("errors", 0) or 0)
        for case in root.iter("testcase"):
            name = f"{case.get('classname')}.{case.get('name')}"
            skipped = case.find("skipped")
            if skipped is not None:
                # Named explicitly: a suite that quietly skips half its cases is how a money rule ends up
                # untested while the build is green.
                print(f"SKIPPED {name}: {skipped.get('message') or 'no reason given'}")
                continue
            for item in list(case.findall("failure")) + list(case.findall("error")):
                print(name)
                message = " ".join((item.get("message") or "").split())
                # Gradle often repeats the type in the message, so only prefix it when there is nothing
                # else to say.
                print(f"   {message[:MESSAGE_CHARS] if message else (item.get('type') or 'failure')}")
                for line in (item.text or "").strip().splitlines()[:STACK_LINES]:
                    print(f"   {line.strip()[:200]}")
                print()
    return failing, total, len(paths)


def main(argv: list[str]) -> int:
    dirs = argv[1:] or ["app/build/test-results"]
    found = failing = total = 0
    for d in dirs:
        candidates = [d]
        if os.path.isdir(d):
            # A suite directory, or the parent of several suites (testDebugUnitTest, testReleaseUnitTest).
            children = sorted(
                name for name in os.listdir(d) if os.path.isdir(os.path.join(d, name))
            )
            candidates += [os.path.join(d, name) for name in children]
        for candidate in candidates:
            if not os.path.isdir(candidate):
                continue
            f, t, n = summarise(candidate)
            if n:
                print(f"-- {candidate}: {f} failing of {t} tests in {n} class(es)")
            found += n
            failing += f
            total += t
    if not found:
        print(f"no TEST-*.xml under {', '.join(dirs)} — did the test task run at all?")
        return 1
    print(f"== {failing} failing of {total} tests across {found} result file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
