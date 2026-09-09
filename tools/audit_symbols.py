#!/usr/bin/env python3
"""Cross-file symbol audit for Kotlin sources in this repository.

The sandbox this code was written in has no JVM, no Gradle and no Android SDK, so there is no
compiler to catch "that function does not exist" / "wrong import path". This script is a *cheap,
deliberately incomplete* substitute for exactly one class of compiler error: a top-level symbol
referenced from another file without being declared anywhere.

What it checks
--------------
1. Every `import com.khatago.finance.X.y` — does `y` exist as a top-level declaration in file `X`?
2. Every unqualified capitalised composable call (`HeroCard(...)`) inside `ui/**` — is it declared in
   the same file, imported from the project, imported from a library (assumed fine), or a framework
   composable in the built-in allowlist?
3. Every `container.<something>` and `app.<something>` access in `ui/**` — is that property declared on
   `AppContainer`? (The app's object graph is small enough to enumerate, and a typo'd accessor is a
   guaranteed compile error.)
4. Every `ProjectType.member` access where `ProjectType` is a class/object this project declares — does it
   have that member? (`AppDates.relativeDay` was written against an object whose function is called
   `humanDay`; the import checker cannot see it, because the *type* imports fine.)

What it does **not** check: types, arity, nullability, overloads, generics, or anything at all about
correctness. `./gradlew build` still has to happen; this only removes whole classes of avoidable
errors before it does.

Usage:  python3 tools/audit_symbols.py [srcRoot]
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PKG = 'com.khatago.finance'

TOP_DECL = re.compile(
    r'^(?:@\w+(?:\([^)]*\))?\s*\n)*'
    r'(?:@\w+\s*\n)*'
    r'(?:@\w+(?:\([^)]*\))?\s*)?'
    r'(?:(?:public|internal|private|abstract|open|sealed|data|enum|annotation|value|inline)\s+)*'
    r'(?:fun|class|interface|object|typealias|val|var|const\s+val|enum\s+class|annotation\s+class)\s+'
    r'(?:(?:<[^>]+>)\s*)?'
    r'(?:(?:\w+)\.)?'                       # receiver type, e.g. `fun StatusTone.Companion.foo`
    r'`?(\w+)`?',
    re.M,
)
IMPORT = re.compile(rf'^import\s+{re.escape(PKG)}\.([\w.]+?)(?:\.(\w+))?(?:\s+as\s+(\w+))?\s*$', re.M)
CONTAINER_ACCESS = re.compile(r'\b(?:container|app)\.([a-z]\w*)')
# `app.` also matches "the app" in prose inside KDoc; strip comments before scanning.
COMMENTS = re.compile(r'/\*\*?.*?\*/', re.S)

# Framework / library composables and types we do not attempt to resolve.
ALLOWLIST = {
    # compose + material
    'Text', 'Icon', 'Box', 'Row', 'Column', 'Spacer', 'Card', 'Scaffold', 'Button', 'Surface',
    'Image', 'container', 'FocusRequester', 'rememberFocusRequester', 'KeyboardActions',
    'KeyboardOptions', 'Modifier', 'Uri', 'Bitmap', 'Intent',
    'OutlinedTextField', 'TextField', 'Switch', 'Checkbox', 'LinearProgressIndicator',
    'CircularProgressIndicator', 'TopAppBar', 'BottomAppBar', 'NavigationBarItem', 'Divider',
    'HorizontalDivider', 'VerticalDivider', 'DropdownMenu', 'DropdownMenuItem', 'AlertDialog',
    'BasicTextField', 'LazyColumn', 'LazyRow', 'Grid', 'Canvas', 'DatePicker', 'DatePickerDialog',
    'TimePicker', 'FilterChip', 'AssistChip', 'InputChip', 'Snackbar', 'SnackbarHost',
    'SnackbarHostState', 'ExperimentalMaterial3Api', 'MaterialTheme', 'Shapes', 'Typography',
    'OutlinedButton', 'TextButton', 'FilledTonalButton', 'ElevatedButton', 'IconButton',
    'TabRow', 'PrimaryTabRow', 'ScrollableTabRow', 'Tab', 'SegmentedButton', 'Chip',
    'ExposedDropdownMenuBox', 'ExposedDropdownMenuDefaults', 'TopAppBarDefaults',
    'CardDefaults', 'ButtonDefaults', 'OutlinedTextFieldDefaults', 'TextFieldDefaults',
    'ModalBottomSheet', 'ModalBottomSheetState', 'rememberModalBottomSheetState',
    'BottomSheetScaffold', 'rememberBottomSheetScaffoldState', 'rememberBottomSheetState',
    'SheetState', 'SheetValue', 'PullToRefreshBox', 'ExperimentalFoundationApi',
    'ExperimentalLayoutApi', 'FlowRow', 'AuxiliaryChip',
    # kotlin stdlib / java
    'Int', 'Long', 'Float', 'Double', 'Boolean', 'String', 'List', 'Map', 'Set', 'Pair', 'Triple',
    # generated at build time, so no source file declares it
    'BuildConfig',
    'Result', 'LocalDate', 'LocalDateTime', 'LocalTime', 'ZoneId', 'Duration', 'Month',
    'YearMonth', 'DateTimeFormatter', 'Locale', 'Math', 'Integer', 'Thread', 'File',
    'Uri', 'Intent', 'Bundle', 'Context', 'View', 'Build', 'PackageManager', 'NotificationCompat',
    'NotificationManagerCompat', 'NotificationChannel', 'PendingIntent', 'BitmapFactory',
    'Bitmap', 'Canvas', 'Paint', 'RectF', 'Color',
    'Modifier', 'Alignment', 'PaddingValues', 'Dp', 'TextUnit', 'TextStyle', 'Brush',
    'FontWeight', 'FontFamily', 'ColorFilter', 'CornerRadius', 'Offset', 'Size', 'Path',
    'Stroke', 'DrawScope', 'ImageVector', 'Vector',
    'Composable', 'OptIn', 'Suppress', 'Stable', 'Immutable', 'ComposableInferredTarget',
    'DisposableEffect', 'LaunchedEffect', 'SideEffect', 'remember', 'rememberSaveable',
    'mutableStateOf', 'mutableFloatStateOf', 'mutableIntStateOf', 'mutableLongStateOf',
    'mutableStateListOf', 'derivedStateOf', 'collectAsState', 'collectAsStateWithLifecycle',
    'viewModel', 'NavHost', 'NavHostController', 'rememberNavController', 'composable',
    'navArgument', 'NavType', 'AnimatedVisibility', 'fadeIn', 'fadeOut', 'slideInVertically',
    'slideOutVertically', 'tween', 'spring', 'keyframes', 'Animatable', 'animateFloatAsState',
    'animateDpAsState', 'animateColorAsState', 'MutableInteractionSource', 'rememberRipple',
    'LocalContext', 'LocalDensity', 'LocalConfiguration', 'LocalView', 'LocalLifecycleOwner',
    'LocalAccessibilityManager', 'LocalSoftwareKeyboardController', 'LocalUriHandler',
    'BiometricPrompt', 'BiometricManager', 'Builder', 'FragmentActivity', 'SharedPreferences',
    'MutableStateFlow', 'StateFlow', 'SharedFlow', 'ViewModel', 'ViewModelStore',
    'WhileSubscribed', 'Eagerly', 'SharingStarted', 'CoroutineScope', 'SupervisorJob',
    'Dispatchers', 'IOException', 'FileNotFoundException', 'SimpleDateFormat', 'Date',
    'Uri', 'IntentFilter', 'ShortcutManagerCompat', 'ShortcutInfoCompat', 'FileProvider',
    'WorkManager', 'Constraints', 'PeriodicWorkRequestBuilder',
    'AuthenticationCallback', 'AuthenticationResult', 'TrailingIcon', 'PromptInfo',
    'Builder', 'R', 'LocalConfigurationContext', 'EntryPoint', 'Preview',
    'OneTimeWorkRequestBuilder', 'ExistingPeriodicWorkPolicy', 'ExistingWorkPolicy',
    'Arrangement', 'BorderStroke', 'KeyboardOptions', 'KeyboardType', 'ImeAction',
    'KeyboardCapitalization', 'TextAlign', 'TextOverflow', 'RoundedCornerShape', 'CircleShape',
    'rememberDatePickerState', 'rememberTimePickerState', 'rememberDrawerState', 'menuAnchor',
    'rememberTopAppBarScrollBehavior', 'rememberScaffoldState', 'rememberSnackbarHostState',
}


def decls_in(text: str) -> set[str]:
    names: set[str] = set()
    for line in text.split('\n'):
        st = line.rstrip()
        if not st or st[0].isspace():
            continue
        m = TOP_DECL.match(st)
        if m:
            names.add(m.group(1))
    # also: data class Foo(, object Foo, enum class Foo on one line
    for m in re.finditer(
        r'^(?:@\w+\s*)?(?:(?:public|internal|private|abstract|open|sealed|data|enum|value|inline)\s+)*'
        r'(?:fun|class|interface|object|typealias|val|var)\s+(?:<[^>]+>\s*)?(?:(?:[\w.]+)\.)?`?(\w+)`?',
        text,
        re.M,
    ):
        names.add(m.group(1))
    return names


def members_in(text: str) -> set[str]:
    """Every declared member name at any indent — coarse on purpose."""
    return set(re.findall(r'\b(?:fun|val|var)\s+(?:<[^>]+>\s*)?(?:(?:[\w.]+)\.)?`?(\w+)`?', text))


def strip_strings(text: str) -> str:
    """Blank out comments and string literals so prose inside them is not mistaken for code."""
    out = []
    i, n = 0, len(text)
    state = None
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ''
        if state is None:
            if c == '/' and nxt == '/':
                state = 'line'; out.append('  '); i += 2; continue
            if c == '/' and nxt == '*':
                state = 'block'; out.append('  '); i += 2; continue
            if text.startswith(TRIPLE, i):
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
            if text.startswith(TRIPLE, i):
                state = None; out.append('   '); i += 3; continue
            out.append('\n' if c == '\n' else ' ')
            i += 1
            continue
        if c == '\\':
            out.append('  '); i += 2; continue
        q = '"' if state == 'str' else "'"
        if c == q:
            state = None; out.append(' '); i += 1; continue
        if c == '\n':
            state = None
            out.append('\n'); i += 1; continue
        out.append(' '); i += 1
    return ''.join(out)


TRIPLE = chr(34) * 3


def rel(path: Path) -> str:
    """Repo-relative path, tolerant of relative argv roots (other checkers take `app/src`, not an absolute path)."""
    try:
        return str(path.resolve().relative_to(ROOT))
    except ValueError:
        return str(path)


def main() -> int:
    # Default to the whole source tree, not just the main source set: the Robolectric tests call the same
    # constructors and DAOs, and an audit that quietly skips them has already skipped the bugs.
    roots = [Path(a) if Path(a).is_absolute() else ROOT / a for a in sys.argv[1:]] or [ROOT / 'app' / 'src']
    src_root = roots[0]
    files = sorted(src_root.rglob('*.kt'))
    if not files:
        print(f'no sources under {src_root}')
        return 1

    decls: dict[Path, set[str]] = {}
    members: dict[Path, set[str]] = {}
    by_fqn: dict[str, Path] = {}
    for f in files:
        text = f.read_text(encoding='utf-8')
        decls[f] = decls_in(text)
        members[f] = members_in(text)
        pkg = re.search(r'^package\s+([\w.]+)', text, re.M)
        prefix = pkg.group(1) if pkg else ''
        for name in decls[f]:
            by_fqn[f'{prefix}.{name}'] = f

    problems: list[str] = []

    # 1. project imports resolve
    for f in files:
        text = f.read_text(encoding='utf-8')
        for path, member, alias in IMPORT.findall(text):
            fqn = f'{PKG}.{path}'
            if f'{fqn}.{member}' == f'{PKG}.R' or fqn.endswith('.R'):
                continue  # generated resource class
            if member in ALLOWLIST:
                continue  # framework/generated member on an otherwise-unknown path (BuildConfig)
            if path in ALLOWLIST:
                continue
            target = by_fqn.get(fqn)
            if target is None:
                # importing a whole file by package: `com.khatago.finance.ui.theme` is a package, not a symbol
                pkg_dir = ROOT / 'app' / 'src' / 'main' / 'java' / fqn.replace('.', '/')
                if pkg_dir.exists():
                    continue
                # A path whose final segment is a lowercase package is a package, not a symbol. When the
                # segment is capitalised but no such file exists, the import targets a *generated*
                # class (BuildConfig) or a top-level member of another file in this package — both valid.
                if (ROOT / 'app' / 'src' / 'main' / 'java' / fqn.replace('.', '/')).with_suffix('.kt').exists():
                    continue
                problems.append(f'{rel(f)}: import {fqn} resolves to nothing')
                continue
            if member:
                if member not in members[target] and member not in by_fqn.get(f'{fqn}.{member}', target) :
                    if f'{fqn}.{member}' not in by_fqn and member not in members[target]:
                        problems.append(
                            f'{rel(f)}: imported symbol {fqn}.{member} is not declared in '
                            f'{target.relative_to(ROOT)}',
                        )

    # 2. unqualified composable calls in ui/ resolve
    ui_files = [f for f in files if f.relative_to(src_root).parts and 'ui' in f.relative_to(src_root).parts]
    for f in ui_files:
        text = f.read_text(encoding='utf-8')
        enum_entries = set(
            re.findall(r'^\s{4}(\w+)(?:\s*\(|,|;)', text, re.M),
        )
        local = decls[f] | members[f] | enum_entries
        imported = set()
        for path, member, alias in IMPORT.findall(text):
            imported.add(alias or member or path.split('.')[-1])
        # Any `import a.b.C` makes `C` usable here, whether or not it is ours: matching only our own
        # package prefix hid framework imports (androidx…) and imports whose sub-package chain did not
        # line up with the regex, which produced phantom "no import" reports.
        imported |= {
            name
            for name in re.findall(r'^import\s+[\w.]*\.(\w+)(?:\s+as\s+\w+)?\s*$', text, re.M)
        }
        scan = strip_strings(text)
        for name in set(re.findall(r'\b([A-Z]\w+)\s*\(', scan)):
            # A `com.khatago.finance.x.Name(` fully-qualified use needs no import.
            if re.search(rf'[\w.]\.{name}\s*[(<]', scan):
                continue
            if name in ALLOWLIST or name in local or name in imported:
                continue
            if f'{PKG}.{name}' in by_fqn or any(k.endswith(f'.{name}') for k in by_fqn):
                # exists somewhere: an unimported use is still a compile error, so report it only
                # when the declaring file is not in the same package.
                holder = next(k for k in by_fqn if k.endswith(f'.{name}'))
                owner_pkg = holder.rsplit('.', 1)[0]
                my_pkg = re.search(r'^package\s+([\w.]+)', text, re.M)
                if my_pkg and my_pkg.group(1) == owner_pkg:
                    continue
                problems.append(
                    f'{rel(f)}: uses {name}() with no import (declared in {owner_pkg})',
                )
                continue
            problems.append(f'{rel(f)}: {name}() is not declared anywhere in the project')

    # 3. container accessors
    app_container = ROOT / 'app' / 'src' / 'main' / 'java' / 'com' / 'khatago' / 'finance' / 'AppContainer.kt'
    container_members = members_in(app_container.read_text(encoding='utf-8'))
    known_shared = {'database', 'applicationScope'}
    for f in ui_files:
        text = f.read_text(encoding='utf-8')
        code = COMMENTS.sub(' ', text)
        for acc in CONTAINER_ACCESS.findall(code):
            if not acc[:1].islower():
                continue
            if acc in container_members or acc in known_shared:
                continue
            problems.append(f'{rel(f)}: container.{acc} is not a member of AppContainer')

    # 4. qualified member access on a type this project declares: `AppDates.relativeDay(...)` when AppDates
    #    has no such function. This is the shape that cost the most CI rounds — an unresolved reference makes
    #    kapt print `e: Could not load module <Error module>` with no file and no line, and the import
    #    checkers above cannot see it because the *type* is imported correctly and only the *member* is wrong.
    #    Deliberately conservative: a name is only flagged when it appears nowhere in the repository as a
    #    declaration, so extension functions, companion members and generated `entries`/`serializer` are
    #    never reported, and an unparseable type (empty member set) is skipped rather than guessed about.
    any_declared: set[str] = set()
    types_members: dict[str, set[str]] = {}
    for f in files:
        text = f.read_text(encoding='utf-8')
        code = strip_strings(COMMENTS.sub(' ', text))
        # `fun Foo.bar()` / `val Foo.baz` declare a member *of the receiver*, not a function named `Foo`,
        # so the extension reading has to come first or every extension looks like a member of its own
        # receiver type (and every legitimate `Foo.bar` call is then reported as missing).
        for name in re.findall(
            r'\b(?:fun|val|var|const val)\s+(?:<[^>]+>\s*)?(?:[A-Za-z_]\w*\.)?([A-Za-z_]\w*)', code
        ):
            any_declared.add(name)
        for name in re.findall(r'\b(?:fun|val|var)\s+[A-Za-z_]\w*\.([A-Za-z_]\w*)', code):
            any_declared.add(name)
        for t in re.finditer(
            r'^\s*(?:@\w+\s*)*(?:public |internal |private |abstract |open |sealed |data |value |enum |annotation )*'
            r'(?:class|interface|object)\s+([A-Z]\w*)[^\n]*\{', code, re.M,
        ):
            name = t.group(1)
            i, depth, j = t.end(), 1, t.end()
            while j < len(code) and depth:
                if code[j] == '{':
                    depth += 1
                elif code[j] == '}':
                    depth -= 1
                j += 1
            body = code[i:j]
            found = set(re.findall(r'(?:val|var|fun)\s+(?:<[^>]+>\s*)?([A-Za-z_]\w*)', body))
            found |= set(re.findall(r'^\s{4,}([A-Z][A-Z0-9_]{1,})\b', body, re.M))  # enum entries
            if re.search(r'enum class\s+' + name + r'\b', code):
                # every enum gets these from java.lang.Enum, and `values()` from the generated companion
                found |= {'entries', 'values', 'valueOf', 'name', 'ordinal'}
            types_members.setdefault(name, set()).update(found)
    QUALIFIED = re.compile(r'(?<![\w.$])([A-Z]\w*)\.([a-z_]\w*)\b')
    for f in files:
        code = strip_strings(COMMENTS.sub(' ', f.read_text(encoding='utf-8')))
        for m in QUALIFIED.finditer(code):
            t, member = m.group(1), m.group(2)
            members_of = types_members.get(t)
            if not members_of:
                continue                      # no parseable body: nothing to conclude
            if member in members_of or member in any_declared:
                continue                      # real member, or an extension/prop somewhere in the app
            problems.append(f'{rel(f)}: {t}.{member} — {t} declares no such member')

    if problems:
        for p in sorted(set(problems)):
            print('MISS', p)
        print(f'{len(set(problems))} problem(s) — symbol-resolution check only')
        return 1
    shown = src_root.relative_to(ROOT) if src_root != ROOT else Path('.')
    print(f'OK: {len(files)} file(s) under {shown}; every project import, ui call, container '
          'accessor and qualified member access resolves (not a type check)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
