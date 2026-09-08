#!/usr/bin/env python3
"""Static balance checker for Kotlin sources.

The sandbox this project was written in has no JVM, no Android SDK and no network access to a
package mirror, so `./gradlew` cannot run here. This script is the *weakest* possible substitute:
it lexes away comments, string literals and char literals, then checks that braces, parentheses and
brackets balance per file. It catches the single most likely authoring error in hand-written code
(an unbalanced block) and nothing else.

It is not a parser, not a type checker, and never a substitute for `./gradlew build`.

Kotlin block comments **do nest** (unlike Java's), and that is what makes a stray glob inside KDoc so
expensive: `app/schemas/*.json` in a doc comment opens a *second* comment, the doc's own `*/` closes
only that inner one, and the outer comment then swallows the rest of the file. Here that one comment
deleted all 18 Room entities from the program and produced 1048 compiler errors plus kapt's
`Could not load module <Error module>` — eight CI rounds before it was found. So the comment scan below
is not decoration: it reports the two things that are actually illegal — a comment left open at EOF,
and a `*/` with nothing to close — while *accepting* legitimate nesting.

Two Kotlin details the checker deliberately refuses, because a "clever" fix would be worse than the
false alarm: an apostrophe or a double quote inside a backtick-quoted identifier (legal in Kotlin, and
our test names use them) is still read as a string/char opener. So test names must avoid apostrophes.
Every other construct in this repository's sources is handled.

Run:  python3 tools/check_braces.py $(find app/src -name '*.kt')     (or a directory)
"""
from __future__ import annotations

import sys
from pathlib import Path


def scan(src: str) -> tuple[str, list[tuple[int, str]]]:
    """(code with comments/strings blanked, list of (line, message) comment problems).

    One lexer for both checks, because this file used to carry two: `strip()` treated block comments as
    non-nesting while the compiler treats them as nesting, so the tool reported a broken tree as
    `OK: 91 file(s)` and everyone lost time believing it.
    """
    out: list[str] = []
    problems: list[tuple[int, str]] = []
    stack: list[int] = []          # line number of every open `/*`
    state = None                    # None | line | block | str | char | tri
    i, n, lineno = 0, len(src), 1
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''

        if state is None:
            if c == '/' and nxt == '/':
                state = 'line'
                out.append('  ')
                i += 2
                continue
            if c == '/' and nxt == '*':
                state = 'block'
                stack.append(lineno)
                out.append('  ')
                i += 2
                continue
            if src.startswith('"""', i):
                state = 'tri'
                out.append('   ')
                i += 3
                continue
            if c == '"':
                state = 'str'
                out.append(' ')
                i += 1
                continue
            if c == "'":
                state = 'char'
                out.append(' ')
                i += 1
                continue
            if c == '*' and nxt == '/':
                problems.append((lineno, '`*/` with no block comment open'))
            out.append(c)
            i += 1
            continue

        if state == 'line':
            if c == '\n':
                state = None
                out.append('\n')
            else:
                out.append(' ')
            lineno += c == '\n'
            i += 1
            continue

        if state == 'block':
            # Inside a comment only the comment delimiters mean anything — quotes do not, which is why
            # `/* don't */` is fine and why a KDoc apostrophe never starts a string here.
            if c == '/' and nxt == '*':
                stack.append(lineno)          # legal in Kotlin: comments nest
                out.append('  ')
                i += 2
                continue
            if c == '*' and nxt == '/':
                if stack:
                    stack.pop()
                state = None if not stack else 'block'
                out.append('  ')
                i += 2
                continue
            out.append('\n' if c == '\n' else ' ')
            if c == '\n':
                lineno += 1
            i += 1
            continue

        if state == 'tri':
            if src.startswith('"""', i):
                state = None
                out.append('   ')
                i += 3
                continue
            out.append('\n' if c == '\n' else ' ')
            if c == '\n':
                lineno += 1
            i += 1
            continue

        # str / char
        if c == '\\':
            out.append('  ')
            i += 2
            continue
        quote = '"' if state == 'str' else "'"
        if c == quote:
            state = None
            out.append(' ')
            i += 1
            continue
        if c == '\n':
            # A single-quoted literal cannot span lines; a " that leaks is handled by the next state.
            state = None
            out.append('\n')
            lineno += 1
            i += 1
            continue
        out.append(' ')
        i += 1

    for line in stack:
        problems.append((line, 'block comment opened here is never closed (Kotlin comments nest, so a '
                               '`/*` inside a comment needs its own `*/)'))
    return ''.join(out), problems


def check(path: Path) -> bool:
    src = path.read_text(encoding='utf-8')
    code, problems = scan(src)
    ok = True
    for line, why in problems:
        print(f'FAIL {path}:{line} {why}')
        ok = False
    depth = {'{': 0, '(': 0, '[': 0}
    closer = {'}': '{', ')': '(', ']': '['}
    first_negative = None
    for lineno, line in enumerate(code.split('\n'), 1):
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
        print(f'FAIL {path} depth={depth} first_closer_without_opener_line={first_negative}')
        ok = False
    return ok


def main(argv: list[str]) -> int:
    paths = [Path(a) for a in argv[1:]]
    files = []
    for p in paths:
        if p.is_dir():
            files.extend(sorted(p.rglob('*.kt')))
        else:
            files.append(p)
    if not files:
        print('no .kt files given')
        return 1
    failed = [f for f in files if not check(f)]
    if failed:
        print(f'{len(failed)}/{len(files)} file(s) unbalanced or with a malformed comment')
        return 1
    print(f'OK: {len(files)} file(s) balanced, comments well-formed (structure only, not a compile)')
    return 0


if __name__ == '__main__':
    raise SystemExit(main(sys.argv))
