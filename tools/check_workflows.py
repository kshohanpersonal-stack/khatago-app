#!/usr/bin/env python3
"""Extract every `run:` block from the GitHub Actions files and make bash parse it.

Why this exists: `check_yaml.py` proves the YAML is well-formed, and YAML happily contains shell that
dies two seconds into the step. This repository has already lost CI rounds to exactly that gap — a `while
read` loop that swallowed the loop's own stdin, a `rc=$?` that no longer followed the command it measured,
a heredoc whose quoting ended the block early. None of those are YAML errors; all of them are bash ones,
and `bash -n` sees every one of them without running anything.

Actions substitutes `${{ ... }}` before bash ever reads the script, so each expression is replaced with a
plain word first. Nothing here judges whether a step *should* exist, whether a command is correct, or
whether the shell would behave at runtime: it judges only "is this a script bash can parse".

Run: python3 tools/check_workflows.py [.github/workflows]
Exit 0 = every block parses; 1 = at least one does not, or there were no blocks to read.
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUN_BLOCK = re.compile(r"^(\s*)run:\s*\|?\s*(.*)$")
EXPRESSION = re.compile(r"\$\{\{.*?\}\}", re.S)


def blocks(path: Path) -> list[tuple[int, str]]:
    """(line, body) for each `run: |` / `run: >` block in the file, de-indented."""
    lines = path.read_text(encoding="utf-8").split("\n")
    found: list[tuple[int, str]] = []
    i = 0
    while i < len(lines):
        match = RUN_BLOCK.match(lines[i])
        if not match:
            i += 1
            continue
        indent = len(match.group(1)) + 2
        body: list[str] = []
        j = i + 1
        while j < len(lines):
            line = lines[j]
            if line.strip() and (len(line) - len(line.lstrip())) < indent:
                break
            body.append(line[indent:] if len(line) > indent else "")
            j += 1
        # `run: |` keeps its newlines; a one-line `run: command` has its command on the header line.
        if not body and match.group(2).strip():
            body = [match.group(2).strip()]
        found.append((i + 1, "\n".join(body)))
        i = j
    return found


def main(argv: list[str]) -> int:
    directory = Path(argv[1]) if len(argv) > 1 else ROOT / ".github" / "workflows"
    if not directory.is_dir():
        directory = ROOT / directory
    files = sorted(directory.glob("*.yml")) + sorted(directory.glob("*.yaml"))
    if not files:
        print(f"no workflow files under {directory}")
        return 1

    checked = 0
    problems: list[str] = []
    for file in files:
        rel = file.relative_to(ROOT)
        for line, body in blocks(file):
            checked += 1
            script = EXPRESSION.sub("SUBSTITUTED", body)
            result = subprocess.run(["bash", "-n"], input=script, capture_output=True, text=True)
            if result.returncode != 0:
                detail = " ".join(result.stderr.split()) or f"exit {result.returncode}"
                problems.append(f"{rel}:{line}: bash cannot parse this run block — {detail}")

    for problem in problems:
        print("MISS", problem)
    if problems:
        print(f"{len(problems)} unparseable run block(s) of {checked}")
        return 1
    print(f"OK: {checked} run block(s) in {len(files)} file(s) parse under bash -n "
          "(syntax only; not a claim that the commands are right)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
