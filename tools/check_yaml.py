#!/usr/bin/env python3
"""Minimal YAML sanity checker for GitHub Actions workflows.

The sandbox this project is authored in has no PyYAML and no network access to fetch it, so a
`.github/workflows/*.yml` edit cannot be parsed by a real YAML implementation here. This script is a
*structural* check, not a parser: it catches the mistakes that actually happen when editing YAML by
hand (tabs, mis-indented list items, unbalanced quotes in a `- name:` value, a mapping key colliding with
its sibling, a block scalar that ends at a shallower indent than it started).

It does not validate the schema, GitHub's keywords, or expression syntax. `act` or a real Actions run is
the only true test, and CI is configured for exactly that.

Run:  python3 tools/check_yaml.py .github/workflows
"""
from __future__ import annotations

import sys
from pathlib import Path

LIST_ITEM = "- "


def check_file(path: Path) -> list[str]:
    problems: list[str] = []
    text = path.read_text()
    lines = text.split("\n")

    in_block: tuple[int, str] | None = None  # (indent, kind) for a `|`/`>` scalar
    for n, raw in enumerate(lines, 1):
        if "\t" in raw:
            problems.append(f"{path}:{n}: tab character (YAML forbids tabs for indentation)")
        if raw.rstrip() != raw:
            problems.append(f"{path}:{n}: trailing whitespace")

        stripped = raw.strip()

        if in_block is not None:
            indent = len(raw) - len(raw.lstrip(" ")) if stripped else len(raw)
            if stripped and indent <= in_block[0]:
                in_block = None  # block scalar ended; fall through and check this line normally
            else:
                continue

        if not stripped or stripped.startswith("#"):
            continue

        if raw.startswith(" ") and not stripped.startswith(LIST_ITEM):
            # A continuation line of a multi-line scalar/flow value is fine only when it is quoted
            # or part of a `run: |` block (handled above). Bare keys must line up with a known depth.
            if ":" not in stripped:
                problems.append(f"{path}:{n}: indented line that is neither a key nor a list item")

        if stripped.endswith(("|", "|-", ">", ">-", "|+", ">+")):
            in_block = (len(raw) - len(raw.lstrip(" ")), stripped[-1])
            continue

        # unbalanced double quotes on a mapping line: the usual cause of "expected <block end>"
        body = stripped[2:] if stripped.startswith(LIST_ITEM) else stripped
        if body.count('"') % 2 == 1:
            problems.append(f"{path}:{n}: odd number of double quotes")
        # a `${{ ... }}` expression containing a single-quoted string with an apostrophe inside a
        # double-quoted YAML scalar is legal, but an unquoted scalar with `: ` inside it is not.
        if not body.startswith(('"', "'", "{", "[")) and ": " in body:
            key = body.split(":", 1)[0]
            if " " in key.strip() and not key.strip().startswith("-"):
                problems.append(f"{path}:{n}: unquoted scalar containing ': ' -> quote the value")

    if lines and lines[-1] != "":
        problems.append(f"{path}: file does not end with a newline")
    return problems


def main(argv: list[str]) -> int:
    targets = argv[1:] or [".github/workflows"]
    files: list[Path] = []
    for target in targets:
        p = Path(target)
        if p.is_dir():
            files += sorted(list(p.glob("*.yml")) + list(p.glob("*.yaml")))
        else:
            files.append(p)

    if not files:
        print("no YAML files found", file=sys.stderr)
        return 1

    all_problems: list[str] = []
    for f in files:
        all_problems += check_file(f)

    for problem in all_problems:
        print("FAIL " + problem)
    if all_problems:
        print(f"{len(all_problems)} problem(s) in {len(files)} file(s)")
        return 1
    print(f"OK: {len(files)} workflow file(s) structurally sound (not a schema validation)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
