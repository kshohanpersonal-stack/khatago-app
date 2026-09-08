#!/usr/bin/env python3
"""Import-level audit for KhataGo (no compiler available in this environment).

Catches the four import defects a `check_braces` pass cannot see:
  1. `import com.khatago.finance.X.Y` where Y is not a top-level declaration of X/Y.kt nor a member of
     a file in that package — the classic "wrote the call site before reading the declaration" bug
     (a bare `import ObligationSnapshot` and `import androidx.compose.runtime.ViewModel` both landed
     here during development).
  2. An import that no line of the file references (unused imports are warnings under Kotlin, but they
     also mean a name was renamed on one side only).
  3. An import of a name that *does* exist but in a different package — `import
     com.khatago.finance.ui.detail.MoreRoute` when the composable lives in `ui.more`. This is the
     defect that cost the most CI rounds here: it is an unresolved reference, which kapt reports only as
     `e: Could not load module <Error module>`, with no file and no line.
  4. The same import written twice (a re-shuffled import block leaves both the old and the new line).
  5. A name that is *used* but never imported — the family that cost the most time here, because this
     script originally only judged imports that had already been written. `KhataGoSeed.kt` used
     `CategoryEntity` and `AppSettingEntity` with no imports at all in the file (31 compiler errors),
     and two screens reached for `Icons.AutoMirrored.Filled.KeyboardArrowRight` the same way. Two
     oracles find these without a compiler:
       (a) every top-level name this project declares must be imported by any file in a *different*
           package that mentions it; and
       (b) every library name the repo already imports — unambiguously, from one package — must be
           imported by every other file that mentions it bare. That catches `Box`, `RoundedCornerShape`,
           `Composable` and friends, which are types in libraries we cannot enumerate ourselves.
     Names reached through a dot (`Modifier.weight`, `ActivityResultContracts.OpenDocument`) are skipped:
     those resolve through the receiver or the qualified root, not through an import of the last part.

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


PRIVATE = re.compile(r'^private\b')
FUN_NAME = re.compile(r'^(?:(?:public |internal |@\w+(?:\([^)]*\))? )*)fun (\w+)\(', re.M)
PROP_NAME = re.compile(r'^(?:(?:public |internal |@\w+(?:\([^)]*\))? )*)(?:const val|val|var) (\w+)\s*[:=]', re.M)
TYPE_NAME = re.compile(
    r'^(?:(?:public |internal |@\w+(?:\([^)]*\))? )*)'
    r'(?:abstract |sealed |open |data |value |annotation |enum )*'
    r'(?:class|interface|object)\s+(\w+)', re.M)
TYPE_ALIAS = re.compile(r'^typealias\s+(\w+)', re.M)


def _declarations(text: str) -> list[tuple[int, str, str]]:
    """(line index, kind, name) for top-level declarations, so privacy can be read off the same line."""
    out: list[tuple[int, str, str]] = []
    for kind, rx in (('type', TYPE_NAME), ('alias', TYPE_ALIAS), ('fun', FUN_NAME), ('prop', PROP_NAME)):
        for m in rx.finditer(text):
            out.append((text[:m.start()].count('\n'), kind, m.group(1)))
    return out


def _line(lines: list[str], index: int) -> str:
    return lines[index] if 0 <= index < len(lines) else ''


def importable_top_level_names(text: str) -> set[str]:
    """Top-level names another file could legally `import` — public or internal, never private."""
    lines = text.split('\n')
    return {
        name for i, _kind, name in _declarations(text)
        if not PRIVATE.search(_line(lines, i)) and ' private ' not in _line(lines, i)
    }


def collect() -> tuple[dict[str, set[str]], dict[str, set[str]]]:
    """(per-file names, per-package names) for every Kotlin file under app/src.

    Two indexes because Kotlin resolves `import a.b.C` two ways: C is a file in package a.b, *or* C is
    a top-level/member declaration inside any file of package a.b (a nested `object Routes` in
    KhataGoApp.kt is imported as `com.khatago.finance.ui.Routes`). Checking only the file-name reading
    reports hundreds of phantom misses.
    """
    by_file: dict[str, set[str]] = {}
    by_package: dict[str, set[str]] = {}
    # Every own-package top-level name, keyed by the package that actually declares it. Used as the
    # "does this name exist at all?" oracle for defect 3 — a name that exists elsewhere is a wrong
    # import, not a missing declaration, and has to be reported as such.
    everywhere: dict[str, set[str]] = {}
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
        # Only *bare, importable* names belong in this oracle, which rules out two things the naive
        # reading gets wrong: an extension declaration (`fun List<Row>.grouped()`) declares `grouped`,
        # not `List`; and a `private` declaration cannot be imported by anybody, so reporting it as
        # "missing an import" would be wrong twice over. Getting this wrong turned the checker's first
        # run into 100 false positives about `String` and `List` that hid the real findings.
        everywhere.setdefault(package, set()).update(importable_top_level_names(text))
        parts = package.split('.')
        for i in range(3, len(parts) + 1):
            by_package.setdefault('.'.join(parts[:i]), set())
    return by_file, by_package, everywhere


BARE = re.compile(r'(?<![.\w])([A-Za-z_]\w*)')


def missing_import_problems(everywhere: dict[str, set[str]]) -> list[tuple[Path, str]]:
    """Defect 5: a name used bare with nothing importing it. See the module docstring."""
    own: dict[str, set[str]] = {}          # our top-level names -> declaring package(s)
    for package, names in everywhere.items():
        for n in names:
            own.setdefault(n, set()).add(package)

    lib: dict[str, set[str]] = {}           # library names -> packages they are imported from
    for f in sorted(SRC.rglob("*.kt")):
        for fqn, _alias in IMPORT.findall(f.read_text(encoding="utf-8")):
            if fqn.startswith(PKG + "."):
                continue
            parent, _, last = fqn.rpartition(".")
            if parent and last:
                lib.setdefault(last, set()).add(parent)

    # What each package *declares*, top-level only. `by_package` cannot be used here: it is
    # deliberately generous (it also collects names merely referenced at the start of an indented line,
    # so that defect 3 can judge a wrong import) and that generosity made every type used in a file look
    # like a neighbour of that file's package — the checker then approved the very missing import it
    # exists to find.
    same_package: dict[str, set[str]] = {}
    texts: dict[Path, str] = {}
    for f in sorted(SRC.rglob("*.kt")):
        text = f.read_text(encoding="utf-8")
        texts[f] = text
        pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
        if pkg is not None:
            same_package.setdefault(pkg.group(1), set()).update(importable_top_level_names(text))

    out: list[tuple[Path, str]] = []
    for f, text in texts.items():
        pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
        if pkg is None:
            continue
        package = pkg.group(1)
        body = strip_noise(re.sub(r"^import .*$", "", text, flags=re.M))
        imported = {a.rpartition(".")[2] for a, _ in IMPORT.findall(text)}
        declared = set(TOP_LEVEL.findall(text)) | set(FUN_OR_VAL.findall(text))
        neighbours = same_package.get(package, set())
        # Enum entries are declared at indentation, are Capitalized and are never imported — without
        # this, `Report`'s own `Payments("khatago-payments.csv", "Payments"),` line reads as a bare use
        # of `Icons.Outlined.Payments`, which other files do import.
        for m in re.finditer(r'^\s*enum class\s+\w+[^{]*\{([\s\S]*?)^\s*\}', text, re.M):
            for line in m.group(1).split('\n'):
                entry = re.match(r'\s*([A-Z]\w*)', line)
                if entry:
                    declared.add(entry.group(1))
        uses = set(BARE.findall(body))
        called = set(re.findall(r'\b([a-z]\w*)\s*\(', body))
        for name in sorted(uses):
            if name in imported or name in declared or name in neighbours or name in ALLOWED_UNKNOWN:
                continue
            home = own.get(name)
            # A lowercase top-level function is only a problem if it is actually *called* here: the same
            # letters turn up constantly as parameter and property names (`statusLabel = ...`).
            if home and (name[0].isupper() or name in called):
                homes = "/".join(sorted(home))
                out.append((f, f"uses {name}, declared in {homes}, with no import"))
                continue
            libs = lib.get(name)
            if libs and len(libs) == 1 and name[0].isupper():
                out.append((f, f"uses {name} bare; every other file imports it from {next(iter(libs))}"))
    return out


def main(argv: list[str]) -> int:
    by_file, by_package, everywhere = collect()
    targets = [Path(a).resolve() for a in argv[1:]] or [SRC]
    problems: list[str] = []
    checked = 0
    seen: set[tuple[str, str]] = set()

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
                key = (str(f), fqn)
                if key in seen:
                    problems.append(f'{f.relative_to(ROOT)}: import {fqn} appears twice in this file')
                else:
                    seen.add(key)
                known = by_package.get(parent, set()) | by_file.get(fqn, set())
                if known and last not in by_package.get(parent, set()):
                    homes = [pkg for pkg, names in everywhere.items() if last in names]
                    if homes:
                        problems.append(
                            f'{f.relative_to(ROOT)}: import {fqn} — {last} is declared in '
                            f'{", ".join(sorted(homes))}, not in {parent}'
                        )
                        continue
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

    for f, why in missing_import_problems(everywhere):
        problems.append(f'{f.relative_to(ROOT)}: {why}')

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
