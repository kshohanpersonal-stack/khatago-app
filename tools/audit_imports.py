#!/usr/bin/env python3
"""Import-level audit for KhataGo (no compiler available in this environment).

Catches the two import defects a `check_braces` pass cannot see:
  1. `import com.khatago.finance.X.Y` where Y is not a top-level declaration of X/Y.kt nor a member of
     a file in that package — the classic "wrote the call site before reading the declaration" bug
     (a bare `import ObligationSnapshot` and `import androidx.compose.runtime.ViewModel` both landed
     here during development).
  2. An import that no line of the file references (unused imports are warnings under Kotlin, but they
     also mean a name was renamed on one side only).

Usage:  python3 tools/audit_imports.py [path ...]     (defaults to app/src)
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / 'app' / 'src'
PKG = 'com.khatago.finance'

TOP_LEVEL = re.compile(
    r'^(?:@\w+(?:\([^)]*\))?\s*)*'
    r'(?:public |internal |private |abstract |sealed |open |data |value |inline |fun |annotation |enum )*'
    r'(?:class|interface|object|enum class|annotation class)\s+(\w+)',
    re.M,
)
FUN_OR_VAL = re.compile(
    r'^(?:@\w+(?:\([^)]*\))?\s*)*(?:public |internal |private |inline |suspend |operator |infix )*'
    r'(?:fun|val|var|const val|typealias)\s+(?:<[^>]+>\s*)?(\w+)',
    re.M,
)
MEMBERS = re.compile(r'^\s{4}(?:@\w+\s*)*(?:data |value |enum )?class\s+(\w+)', re.M)
IMPORT = re.compile(r'^import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$', re.M)
# Comments and string literals never count as a "use" of an imported name.
COMMENTS = re.compile(r'/\*[\s\S]*?\*/|//[^\n]*')
STRINGS = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"')

# Generated at build time or provided by a library we do not vendor.
ALLOWED_UNKNOWN = {'BuildConfig', 'R', 'Manifest', 'DataBinding'}
EXTERNAL_ROOTS = ('android.', 'androidx.', 'java.', 'javax.', 'kotlin.', 'kotlinx.', 'org.', 'io.', 'com.google.')


def strip_noise(text: str) -> str:
    text = COMMENTS.sub(' ', text)
    return STRINGS.sub('""', text)


def collect() -> tuple[dict[str, set[str]], dict[str, set[str]]]:
    """(per-file names, per-package names) for every Kotlin file under app/src.

    Two indexes because Kotlin resolves `import a.b.C` two ways: C is a file in package a.b, *or* C is
    a top-level/member declaration inside any file of package a.b (a nested `object Routes` in
    KhataGoApp.kt is imported as `com.khatago.finance.ui.Routes`). Checking only the file-name reading
    reports hundreds of phantom misses.
    """
    by_file: dict[str, set[str]] = {}
    by_package: dict[str, set[str]] = {}
    for f in sorted(SRC.rglob('*.kt')):
        text = f.read_text(encoding='utf-8')
        pkg = re.search(r'^package\s+([\w.]+)', text, re.M)
        if pkg is None:
            continue
        package = pkg.group(1)
        names = (
            set(TOP_LEVEL.findall(text))
            | set(FUN_OR_VAL.findall(text))
            | set(MEMBERS.findall(text))
            # Any indented declaration counts too: `object Routes` nested in a file, `data class Ready`
            # nested in a sealed interface, and enum entries are all importable names.
            | set(re.findall(r'^\s+(?:@[\w.]+\s*)*(?:const val|val|var|fun|class|object|interface|enum)[ \t]+(?:<[^>]+>[ \t]*)?(\w+)', text, re.M))
            | set(re.findall(r'^\s+(\w+)(?:\s*\(|,|;)', text, re.M))
        )
        by_file.setdefault(package + '.' + f.stem, set()).update(names)
        by_package.setdefault(package, set()).update(names)
        parts = package.split('.')
        for i in range(3, len(parts) + 1):
            by_package.setdefault('.'.join(parts[:i]), set())
    return by_file, by_package


def main(argv: list[str]) -> int:
    by_file, by_package = collect()
    targets = [Path(a).resolve() for a in argv[1:]] or [SRC]
    problems: list[str] = []
    checked = 0

    for base in targets:
        files = [base] if base.is_file() else sorted(base.rglob('*.kt'))
        for f in files:
            if f.suffix != '.kt':
                continue
            text = f.read_text(encoding='utf-8')
            body = strip_noise(text)
            for fqn, alias in IMPORT.findall(text):
                checked += 1
                if not fqn.startswith(PKG):
                    if not fqn.startswith(EXTERNAL_ROOTS):
                        problems.append(f'{f.relative_to(ROOT)}: import {fqn} is outside every known root')
                    continue
                parent, _, last = fqn.rpartition('.')
                if last in ALLOWED_UNKNOWN:
                    continue
                known = by_package.get(parent, set()) | by_file.get(fqn, set())
                if fqn not in by_file and last not in by_package.get(parent, set()) and not known:
                    problems.append(
                        f'{f.relative_to(ROOT)}: import {fqn} — no file or top-level name in '
                        f'package {parent}',
                    )
                    continue
                if alias:
                    continue
                if re.search(rf'\b{re.escape(last)}\b', body) is None:
                    problems.append(f'{f.relative_to(ROOT)}: unused import {fqn}')

    print(f'checked {checked} import statements across {len(list(SRC.rglob("*.kt")))} files')
    for line in problems:
        print('MISS', line)
    if problems:
        print(f'{len(problems)} problem(s) — import-resolution and unused-import check only')
        return 1
    print('OK: every own-package import resolves to a declared top-level name and is referenced')
    return 0


if __name__ == '__main__':
    raise SystemExit(main(sys.argv))
