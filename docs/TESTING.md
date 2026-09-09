# Testing

---

## 1. The design *is* the test plan

Two structural properties do more work here than any assertion:

1. **Derived balances, one derivation.** There is no stored total to keep in sync, and one function
   (`PayableResolver.resolve`) + one set of SQL (`StatsDao`, `LoanDao`, `EmiDao`, `CreditRow`) produce every
   balance. Tests therefore have to check *agreement*, not thousands of states.
2. **One writer for money.** `PaymentRepository` is the only path that touches `payments`. The invariant
   "remaining ≥ 0 and ≤ original" has exactly one place where it can break.

If you add a second way to compute a balance, no test can save you. That is the rule to defend in review.

## 2. Suites

| Suite | Location | Needs | Covers |
|---|---|---|---|
| Money core | `app/src/test/…/core/money/MoneyCoreTest.kt` | JVM only | parsing every edge case, formatting, clamping, overflow guards, currency fallback |
| Financial math | `app/src/test/…/domain/calc/FinancialMathTest.kt` | JVM only | balance/status derivation, payment guard kinds, instalment allocation, schedule generation per frequency |
| CSV | `app/src/test/…/core/CsvExportTest.kt` | JVM only | RFC-4180 quoting round-trip, BOM, CRLF, numeric cells a spreadsheet can sum |
| App lock | `app/src/test/…/core/security/SecurityLockTest.kt` | Robolectric | PIN hashing/salt/shape, verify/change/clear, biometric gating, attempt throttling and cool-down |
| Payment engine | `app/src/test/…/data/PaymentEngineTest.kt` | Robolectric + in-memory Room | record/update/delete/undo, overpayment refusal, down-payment identity, schedule rebuild safety, due queue |
| On-device | `app/src/androidTest/…/OnDeviceLedgerTest.kt` | emulator/device | real SQLite: empty-set aggregates, the due `UNION ALL` view, and a `pragma_table_info` grep proving no stored balance column exists |

```bash
gradle :app:testDebugUnitTest                                   # everything that runs without an emulator
gradle :app:testDebugUnitTest --tests "com.khatago.finance.data.PaymentEngineTest"
gradle :app:connectedDebugAndroidTest                           # on-device (needs a device/emulator)
```

Reports: `app/build/reports/tests/testDebugUnitTest/index.html`.

## 3. Conventions

- **Robolectric, not Espresso, for anything touching Room or `Context`.** `app/src/test/resources/
  robolectric.properties` pins `sdk=34` and `application=android.app.Application` — deliberately *not*
  `KhataGoApplication`, which builds a file-backed container and schedules work; every test constructs exactly
  the object it needs (`KhataGoDatabase.createInMemory(context)`).
- **Backtick test names must not contain apostrophes or quotes.** `tools/check_braces.py` reads a `'` as a
  char-literal opener even inside an identifier, so `fun `a loan's balance…`` produces a phantom imbalance.
  Write `fun `a loan balance…`` instead. (Documented in that tool's header.)
- **Money tests assert exact `Long`s.** `assertEquals(123_450L, …)`, never a tolerance.
- **Guard tests assert the `Kind`, not the sentence.** Copy is allowed to change; the rejection taxonomy is
  the contract (`PaymentValidation.Kind.*`, `MoneyParseResult.Kind.*`, `PaymentOutcome.Rejected`).
- **No test computes its own expectation with the same helper under test.** An allocation test sums the
  results with `sumOf`, not by calling `allocate` again.
- Flakiness is a bug: no sleeps, no wall-clock assumptions (`AppDates.today()` is captured once per test),
  no ordering between tests, and each test clears the preferences file it touches.

## 4. Manual QA — the list that actually finds things

An offline ledger app fails most often in places a JVM test cannot see. Run this before every release
(~25 minutes; do it on a small phone *and* a tablet):

**Numbers**
1. New credit 1,000.00, pay 999.99 → remaining 0.01; try 0.02 → refused, message names **0.01** as the max.
2. Edit the 999.99 payment to 999.98 → remaining 0.02; to 1,000.01 → refused; delete → remaining 1,000.00.
3. Loan 12,000 payable, 2,000 down → "I owe" shows 12,000 after the first EMI of 1,000 → 11,000. Never 10,000.
4. EMI 36,000 total incl. 4,000 down → "I owe" 32,000 → pay 4 EMIs → 28,000. Then pay it out → 0, and the
   record says *Settled*, not *Overdue*.
5. 1/3 split (100,000 over 3) → 333.33 / 333.33 / 333.34; the schedule sums to the total exactly.
6. Cancel a partly-paid record → excluded from every total; restore → the same numbers return.

**Dates & devices**
7. A 31 Jan monthly schedule from a 31st start: Feb shows 28 (29 in a leap year) and later months do not drift
   back to the 31st.
8. Change the device timezone +21 h, re-open: epoch-day based due dates and "today" shift together; no record
   changes its status alone.
9. Force-stop mid-write (Android 13+, developer option "Don't keep activities" is not enough — kill the
   process while a payment saves) → on relaunch, either the payment exists and balances are consistent, or it
   does not. Never half.

**Export / backup**
10. CSV of a ledger with `Rahman Store, Main Rd`, a note containing a newline, and Bengali text → opens in
    Excel/LibreOffice/Google Sheets with the same characters, and the amount column sums to the on-screen total.
11. Backup → wipe → restore **Replace** → identical record counts, identical balances, and the PIN still works.
12. Restore a **future-version** file (bump `backupVersion` by hand) → refused with an explanation, nothing written.

**Lock & notifications**
13. Wrong PIN 5 times → 5-minute cool-down with live minutes; correct PIN after cool-down unlocks; the ledger
    was never visible behind the lock screen (check the recents thumbnail).
14. Background the app, return → re-locked. Take a receipt photo, return → **not** re-locked (`onStop`, not
    `onPause`).
15. Grant nothing, one due today → after "notify now", the notification says counts only — no amount, no name.
    Repeat the same day → still one notification.

**Accessibility & polish**
16. TalkBack: every amount reads as one string, every status has its word, the bottom bar is navigable.
17. Font scale "Largest" (2x): the hero number wraps rather than clips; no text overlaps a button.
18. Reduced motion: money figures appear instantly.
19. Airplane mode, entire app: nothing changes. (If anything does, there is a network path.)
20. Dark device mode + battery saver: the app stays light.

## 5. What is deliberately *not* tested automatically

- **R8 output.** Verified by installing and running a release build, not by a unit test; the keep rules are in
  `app/proguard-rules.pro`.
- **Per-OEM notification behaviour.** Xiaomi/Samsung aggressive background killing is a device property; the
  app's contract is "a daily flexible window, idempotent per day", which is what `ReminderWorker` can guarantee.
- **Room schema JSON diffs** are a *review* step (`app/schemas/…json` in the PR), not a test. CI force-runs
  `:app:kaptDebugKotlin --rerun` and compares it with the committed baseline, but a mismatch is a warning, not
  a failure: an un-regenerated JSON is a missing review artifact, not a broken build, and CI cannot know which
  of the two the author intended. The verdict and the diff land in `CARRIER.txt` on `room-schema-baseline`.
- **Cloud backup/restore of the file** — not our code and not our promise; the app only writes to where the user
  points the picker.

## 6. Static checks in this repository (not tests, but CI gates)

`tools/` are the substitute for "run the compiler" in environments that cannot, and the first job in CI:

| Script | Proves |
|---|---|
| `check_braces.py` | braces/parens/brackets balance after stripping strings and comments, **and** every block comment closes (Kotlin comments nest) |
| `audit_imports.py` | every project import resolves to a declared top-level name and is referenced — and every project or library name a file *uses* is imported by it |
| `audit_symbols.py` | unqualified constructor calls in `ui/`, `container.*` accessors, `KhataGoIcons.*` members — over `app/src` by default, so the test sources are audited with the same rigour |
| `audit_data_api.py` | every `database.xxxDao().method()` call site matches a declared DAO method, in main **and** test sources. A local only counts as a DAO handle when the whole right-hand side is the accessor, so `val rows = db.paymentDao().findForPayable(…)` cannot report `.single()` as a missing query |
| `check_yaml.py` | the GitHub Actions files are structurally valid YAML (this sandbox has no PyYAML, so a real parser is unavailable) |
| `check_workflows.py` | every `run:` block in those files is a script `bash -n` can parse — the gap `check_yaml.py` cannot see, and the one that cost real CI rounds (`while read` eating stdin, an `rc=$?` that stopped following its command, a heredoc quoting mistake) |

```bash
for t in check_braces audit_imports audit_symbols audit_data_api; do python3 tools/$t.py app/src; done
python3 tools/check_yaml.py .github/workflows && python3 tools/check_workflows.py .github/workflows
```

They prove nothing about types, and no amount of them adds up to a build. A green sandbox run is a licence to
open a PR, not a licence to say "tests pass".

This is not theoretical. CI caught two defects that every one of these checkers passed — a duplicate `clean`
task registration that failed Gradle's *configuration* phase, and a `java.util.Base64` reference inside a
Gradle script (where `java` resolves to the plugin extension, not the package). Both are legal Kotlin and
both were invisible to structural checks, because both are errors about the *build*, not about the source.
The lesson to keep: these scripts can prove a tree is self-consistent, never that it compiles.

A third family of the same lesson, and the expensive one: two defects that produced
`e: Could not load module <Error module>` from `:app:kaptGenerateStubsDebugKotlin` and survived many
CI rounds, because kapt substitutes that single line for the compiler's real diagnostics and the
checkers were blind to the *shape* of the bug.

- `ui/KhataGoApp.kt` imported three route composables (`InsightsRoute`, `MoreRoute`, `QuickAddRoute`)
  from the package they used to live in. `MoreRoute` was even imported twice, once correctly.
- `ui/components/KhataGoFields.kt` called `AppDates.relativeDay(...)`. The object declares
  `humanDay(...)`, same signature.

Both are ordinary unresolved references, and both are invisible to an import checker that only asks
"does anything in that package mention this name?" They are now gates, not folklore:
`tools/audit_imports.py` keeps a strict per-package index of top-level declarations and reports
"`X` is declared in package `Y`, not `Z`" (plus duplicate imports), and `tools/audit_symbols.py`
checks every `ProjectType.member` access against the members that type actually declares, staying
quiet about extensions, enum-generated `entries` and unparseable bodies so it never cries wolf.
Each rule was verified by re-injecting the original defect and confirming a `MISS` line and exit 1 —
a static check nobody has seen fail is not evidence of anything.

That is still the rule, and it is how the widened scope was accepted: injecting
`database.paymentDao().totallyMadeUpQuery(1L)` into `PaymentRepository` **and** into `PaymentEngineTest` each
printed a `MISS` line and exit 1, and the same injection done with a `"` left off a `run:` block made
`check_workflows.py` name the file and line. Both then restored the tree and went quiet again.

A fourth round found the *shape* of the bug behind 1048 errors, and it was a comment. The KDoc on
`data/db/entity/Entities.kt` mentioned the generated schema files by glob — `app/schemas/` plus a star
plus `1.json`. **Kotlin block comments nest**, unlike Java's, so the `*/` that ended the doc comment closed
only the comment the `*` region had opened: the outer comment ran to end of file, deleted all 18 entity
declarations, and the compiler's entire complaint was one line — `Unclosed comment` at the last line.
`check_braces.py` had modelled Java's non-nesting rule and called that tree balanced. It now shares a
single lexer with the brace check and reports only what Kotlin actually forbids (a comment left open, a
`*/` with nothing to close) while accepting legitimate nesting; both directions were tested by re-injecting
the glob and by writing a deliberately nested comment.

The largest family in that round was not a wrong import but a *missing* one: `data/db/KhataGoSeed.kt`
used `CategoryEntity` and `AppSettingEntity` with no import statements in the file at all (31 errors), and
several screens reached for `Box`, `RoundedCornerShape`, `BuildConfig` or
`Icons.AutoMirrored.Outlined.KeyboardArrowRight` the same way. A checker that only judges the imports
already written cannot see those, so `audit_imports.py` now resolves names forward as well as backward: a
project type used outside its own package must be imported, and so must any library name this repository
already imports unambiguously from exactly one package. Dotted access (`Modifier.weight`,
`ActivityResultContracts.OpenDocument`) is skipped on purpose — that resolves through the receiver, not
through an import — as are enum entries and `private` top-level declarations, which nobody can import.
The same discipline as above: each rule was proven by deleting an import that a fix had just added and
watching the checker report it.

**What running the suite found, which none of the above could.** The first execution of
`:app:testDebugUnitTest` reported 12 failing of 93 tests — and 0 of those 12 were a syntax, import or
structure problem that a checker could have seen. Four were the app being wrong about money:

- `StatsDao` derived the loan and EMI headlines against a *different* down-payment convention from every
  other query in the app. A loan's paid side left the down payment out, so a loan with every instalment
  paid still reported its down payment as outstanding — the dashboard could never show it settled. An
  EMI's total added the down payment again although `totalPayable` already contains it, so the tile showed
  one instalment more than anyone owed. `PaymentEngineTest` caught both because it compares the tile, the
  module query and the engine snapshot against each other, not against a constant.
- `InstallmentAllocator` spread the obligation's whole paid total *on top of* what each line had already
  been paid for, so `sum(allocated)` exceeded the paid total and lines read as settled that nobody paid
  for. A line's `paidMinor` is a **subset** of the obligation's paid total, never an addition to it.

The other eight were tests asserting the wrong number — including two EMI fixtures written straight from
`docs/MONEY.md` §6, which itself stated the double-counted identity. The doc was corrected first, then the
fixtures, because a doc that is wrong produces wrong tests faster than it produces wrong code. What was *not*
done is the part worth stating: no assertion was deleted, skipped or softened to reach green. The cases
that were wrong now assert more than before (the cap is tested at its boundary; `wouldExceed` at its
inclusive edge; the sum-of-rows case checks three derivations agree for *any* deleted row instead of a
hard-coded one), and the four real defects stay pinned by the same tests that found them.
