#!/usr/bin/env python3
"""Static balance checker for Kotlin sources.

The sandbox this project was written in has no JVM, no Android SDK and no network access to a
package mirror, so `./gradlew` cannot run here. This script is the *weakest* possible substitute:
it strips comments, string literals and char literals, then checks that braces, parentheses and
brackets balance per file. It catches the single most likely authoring error in hand-written code
(an unbalanced block) and nothing else.

It is not a parser, not a type checker, and never a substitute for `./gradlew build`.

Kotlin block comments do **not** nest, which is a trap that cost this repository eight CI rounds: a
KDoc line mentioning `app/schemas/*.json` closed the comment early at `*/` and left `Unresolved
reference` errors in ~90 files, while `strip()` above happily treated the `/*` as a nest and reported
the file as balanced. `check_comments()` therefore models Kotlin's real (non-nesting) rule and is run
first.

Two Kotlin details the checker deliberately refuses, because a "clever" fix would be worse than the
false alarm: an apostrophe or a double quote inside a backtick-quoted identifier (legal in Kotlin, and
our test names use them) is still read as a string/char opener. So test names must avoid apostrophes.
Every other construct in this repository's sources is handled.

Run:  python3 tools/check_braces.py $(find app/src -name '*.kt')
"""
from __future__ import annotations

import sys
from pathlib import Path


def strip(src: str) -> str:
    out = []
    i, n = 0, len(src)
    state = None  # None | line | block | str | char | tri
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if state is None:
            if c == '/' and nxt == '/':
                state = 'line'; out.append('  '); i += 2; continue
            if c == '/' and nxt == '*':
                state = 'block'; out.append('  '); i += 2; continue
            if src.startswith('"""', i):
                state = 'tri'; out.append('   '); i += 3; continue
            if c == '"':
                state = 'str'; out.append(' '); i += 1; continue
            if c == "'":
                state = 'char'; out.append(' '); i += 1; continue
            out.append(c); i += 1; continue
        if state == 'line':
            if c == '\n':
                state = None
                out.append('\n')
            else:
                out.append(' ')
            i += 1
            continue
        if state == 'block':
            if c == '*' and nxt == '/':
                state = None; out.append('  '); i += 2; continue
            out.append('\n' if c == '\n' else ' ')
            i += 1
            continue
        if state == 'tri':
            if src.startswith('"""', i):
                state = None; out.append('   '); i += 3; continue
            out.append('\n' if c == '\n' else ' ')
            i += 1
            continue
        # str / char
        if c == '\\':
            out.append('  '); i += 2; continue
        quote = '"' if state == 'str' else "'"
        if c == quote:
            state = None; out.append(' '); i += 1; continue
        if c == '\n':
            state = None
            out.append('\n')
            i += 1
            continue
        out.append(' ')
        i += 1
    return ''.join(out)


def comment_scan(src: str) -> tuple[list[int], list[int]]:
    """(lines where a `/*` appears *inside* a block comment, line of the unterminated opener).

    Mirrors the lexer in `strip()` but without block-comment nesting, exactly as Kotlin scans.
    """
    nested: list[int] = []
    i, n, lineno = 0, len(src), 1
    state = None  # None | line | block | str | char | tri
    block_open_line = 0
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if c == "\n":
            lineno += 1
            if state == "line":
                state = None
        if state is None:
            if c == "/" and nxt == "/":
                state = "line"; i += 2; continue
            if c == "/" and nxt == "*":
                state = "block"; block_open_line = lineno; i += 2; continue
            if src.startswith('"""', i):
                state = "tri"; i += 3; continue
            if c == '"':
                state = "str"; i += 1; continue
            if c == "'":
                state = "char"; i += 1; continue
            i += 1; continue
        if state == "line":
            i += 1; continue
        if state == "block":
            if c == "*" and nxt == "/":
                state = None; i += 2; continue
            if c == "/" and nxt == "*":
                nested.append(lineno)      # Kotlin just closed nothing: it will end at the next */
                i += 2; continue
            i += 1; continue
        if state == "tri":
            if src.startswith('"""', i):
                state = None; i += 3; continue
            i += 1; continue
        # str / char
        if c == "\\":
            i += 2; continue
        quote = '"' if state == "str" else "'"
        if c == quote:
            state = None; i += 1; continue
        if c == "\n":
            state = None
        i += 1
    return nested, ([block_open_line] if state == "block" else [])


def check_comments(path: Path) -> bool:
    src = path.read_text(encoding="utf-8")
    nested, unterminated = comment_scan(src)
    ok = True
    for line in nested:
        print(f"FAIL {path}:{line} `/*` inside a block comment — Kotlin comments do not nest, so the "
              f"comment ends at the next `*/` and the rest of the line becomes code")
        ok = False
    for line in unterminated:
        print(f"FAIL {path}:{line} block comment opened here is never closed")
        ok = False
    return ok


def check(path: Path) -> bool:
    src = strip(path.read_text(encoding='utf-8'))
    depth = {'{': 0, '(': 0, '[': 0}
    closer = {'}': '{', ')': '(', ']': '['}
    first_negative = None
    for lineno, line in enumerate(src.split('\n'), 1):
        for ch in line:
            if ch in depth:
                depth[ch] += 1
            elif ch in closer:
                key = closer[ch]
                depth[key] -= 1
                if depth[key] < 0 and first_negative is None:
                    first_negative = lineno
    bad = {k: v for k, v in depth.items() if v}
    if bad or first_negative:
        print(f"FAIL {path} depth={depth} first_closer_without_opener_line={first_negative}")
        return False
    return True


def main(argv: list[str]) -> int:
    paths = [Path(a) for a in argv[1:]]
    files = []
    for p in paths:
        if p.is_dir():
            files.extend(sorted(p.rglob('*.kt')))
        else:
            files.append(p)
    if not files:
        print("no .kt files given")
        return 1
    failed = [f for f in files if not (check_comments(f) and check(f))]
    if failed:
        print(f"{len(failed)}/{len(files)} file(s) unbalanced or with a malformed comment")
        return 1
    print(f"OK: {len(files)} file(s) balanced, comments well-formed (structure only, not a compile)")
    return 0


if __name__ == '__main__':
    raise SystemExit(main(sys.argv))
