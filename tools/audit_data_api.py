#!/usr/bin/env python3
"""Cross-checks every `database.someDao().method(...)` / `db.someDao().method(...)` call site
against the methods actually declared in `data/db/dao/*.kt`.

Why this exists: the sandbox that produced this code has no JVM, so a typo in a DAO call is not
caught by a compiler. This script does not type-check anything — it catches the one class of error
that a compiler would catch cheaply: calling a query that was never written.

Test sources are scanned too: the app's money tests call the DAOs directly, and that is where a
renamed or never-written query tends to appear first.

Run: python3 tools/audit_data_api.py [root ...]   (default: app/src)
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / 'app' / 'src' / 'main' / 'java' / 'com' / 'khatago' / 'finance'
DAO_DIR = MAIN / 'data' / 'db' / 'dao'
# Where to look for call sites. `app/src` covers main + test + androidTest in one argument.
DEFAULT_ROOTS = [ROOT / 'app' / 'src']

CALL = re.compile(r'\b(?:database|db)\.(\w*[Dd]ao)\(\)\.(\w+)')
# Guard: only accessors that look like DAO methods are audited (see main()).
# also handle `val catalog = database.catalogDao(); catalog.putSetting(...)`
DECL = re.compile(r'^\s*(?:@\w+.*\n\s*)*(?:suspend\s+)?(?:abstract\s+)?fun\s+(?:<[^>]+>\s+)?(\w+)\s*\(', re.M)
# Names that legitimately appear as `foo().bar` in Kotlin without being DAO queries.
NON_DAO_MEMBERS = {
    "copy", "let", "also", "run", "apply", "also", "first", "isNotEmpty", "isEmpty", "size",
    "map", "filter", "forEach", "toSet", "distinct", "sortedBy", "orNull", "getOrThrow",
    "coerceAtLeast", "coerceIn", "coerceAtMost", "let", "takeIf", "firstOrNull", "orEmpty",
}

# An alias counts as a DAO handle only when the *whole* right-hand side is the accessor:
# `val dao = database.paymentDao()` yes, `val rows = database.paymentDao().findForPayable(...)` no — the
# latter is a List, and treating it as a DAO invented a "missing method" for every `.single()` in a test.
LOCAL_ALIAS = re.compile(r'val\s+(\w+)\s*=\s*(?:database|db)\.(\w*[Dd]ao)\(\)\s*$', re.M)


def dao_names() -> dict[str, set[str]]:
    out: dict[str, set[str]] = {}
    for file in sorted(DAO_DIR.glob('*.kt')):
        declared = set(DECL.findall(file.read_text(encoding='utf-8')))
        # interface name -> file-derived dao accessor is the interface name lowercased minus "Dao"
        for name in re.findall(r'interface\s+(\w+Dao)', file.read_text(encoding='utf-8')):
            out[name] = declared
    return out


def main() -> int:
    accessors: dict[str, str] = {}
    db_file = (MAIN / 'data' / 'db' / 'KhataGoDatabase.kt').read_text(encoding='utf-8')
    for accessor, iface in re.findall(r'abstract fun\s+(\w+)\(\):\s*(\w+)', db_file):
        accessors[accessor] = iface

    daos = dao_names()
    for name, methods in daos.items():
        if name not in accessors.values():
            print(f"WARN dao interface {name} has no accessor on KhataGoDatabase")

    roots = [Path(a) if Path(a).is_absolute() else ROOT / a for a in sys.argv[1:]] or DEFAULT_ROOTS
    files = sorted({f for root in roots for f in root.rglob('*.kt')})
    problems: list[str] = []
    checked = 0
    for file in files:
        if 'data/db/dao' in str(file).replace('\\', '/'):
            continue
        text = file.read_text(encoding='utf-8')
        aliases = {alias: accessor for alias, accessor in LOCAL_ALIAS.findall(text)}
        for accessor, method in CALL.findall(text):
            if not accessor.endswith('Dao'):
                continue
            if method in NON_DAO_MEMBERS:
                continue
            checked += 1
            iface = accessors.get(accessor)
            if iface is None:
                problems.append(f"{file.name}: no such DAO accessor `{accessor}()` on KhataGoDatabase")
                continue
            if method not in daos.get(iface, set()):
                problems.append(f"{file.name}: {accessor}().{method}() is not declared in {iface}")
        for alias, accessor in aliases.items():
            iface = accessors.get(accessor)
            if iface is None:
                continue
            for method in re.findall(rf'\b{alias}\.(\w+)\(', text):
                if method in NON_DAO_MEMBERS:
                    continue
                checked += 1
                if method not in daos.get(iface, set()):
                    problems.append(f"{file.name}: {alias}.{method}() ({iface}) not declared")

    print(f"checked {checked} DAO call sites in {len(files)} file(s) under "
          + ", ".join(str(r.relative_to(ROOT)) for r in roots))
    if problems:
        for p in sorted(set(problems)):
            print("MISS", p)
        print(f"{len(set(problems))} problem(s)")
        return 1
    print("OK: every DAO call site resolves to a declared method (not a type check)")
    return 0


if __name__ == '__main__':
    sys.exit(main())
