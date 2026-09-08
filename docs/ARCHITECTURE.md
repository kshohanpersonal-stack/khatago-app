# Architecture

KhataGo is one Gradle module with a strict inside-out layering. This document explains the layering and,
more importantly, *why* each boundary is where it is — the rules that make an accidental bug hard to write.

```
┌──────────────────────────────────────────────────────────────────────────┐
│ ui/          Compose screens + ViewModels. No arithmetic. No SQL.        │
│              Home · Records · Payments · Analytics · More · forms/ ·     │
│              detail/ (record, person, ledger, search, reports, backup,   │
│              settings, security, data, about, onboarding) · components/  │
├──────────────────────────────────────────────────────────────────────────┤
│ domain/      Pure Kotlin. Money rules expressed as functions, not SQL.   │
│   model/     Obligation, ObligationSnapshot, DueItem, LedgerStatus,      │
│              PayableType, PaymentMethod, DashboardSnapshot, …            │
│   calc/      FinancialMath (allocation, clamping, schedules),            │
│              Insights + SnapshotCalculator (rule-based commentary)         │
├──────────────────────────────────────────────────────────────────────────┤
│ data/        Room + repositories. The only layer that knows SQLite exists.│
│   db/        18 entities, 12 DAOs, KhataGoDatabase, seed, migrations      │
│   repo/      Shop / Obligation / Person / Transaction / Payment /         │
│              Catalog / Stats / Search / Attachment / Security / Data /     │
│              Backup + CSV export                                          │
│   backup/    BackupDocument (serialisable DTOs) + BackupValidation        │
├──────────────────────────────────────────────────────────────────────────┤
│ core/        No Android, no Room, no Compose. Testable by inspection.     │
│   money/     MoneyMinor, CurrencySpec, MoneyFormat, MoneyParseResult      │
│   time/      AppDates, DueStatus, InstallmentSchedule + Frequency          │
│   csv/       CsvWriter (RFC-4180)                                          │
│   security/  PinHasher (PBKDF2-HMAC-SHA256)                                 │
└──────────────────────────────────────────────────────────────────────────┘
        notify/  WorkManager reminder worker + scheduler + Notifications
        AppContainer / KhataGoApplication / MainActivity  — composition only
```

## One module, deliberately

Multi-module Gradle setups pay for themselves when a module is reused, has its own build, or needs an
enforced visibility boundary. KhataGo has none of those: one app, one flavour, no library consumers. So
the boundary is enforced by **package discipline plus static checks** instead of by Gradle:

- `core/` imports nothing from `data/`, `domain/`, or `ui/`. Enforced by `tools/audit_imports.py`.
- `domain/` imports nothing from `data/` or `ui/` (it may use `core/`).
- `ui/` never touches `androidx.room` DAOs directly — it goes through a repository, always through the
  `AppContainer` it was handed. `tools/audit_symbols.py` greps for `container.` accessors and unqualified
  constructors in `ui/`; `tools/audit_data_api.py` resolves every `database.xxxDao().method()` call site
  against the real DAO declarations.
- `data/` never formats a number for a human. Formatting is a `ui`/`core.money` concern; a repository that
  returns `String` is a repository that has already decided how a currency looks.

If a change makes those scripts fail, the change is crossing a layer, and the fix is usually "move the
responsibility", not "add an exception to the script".

## Composition: `AppContainer`

There is no DI framework. `AppContainer` is a plain class built once in `KhataGoApplication.onCreate()`,
holding lazy repositories over a single `KhataGoDatabase`. ViewModels receive it through
`viewModelFactory { initializer { … } }` (see `ui/components/ViewModelAccess.kt`), so a test can build a
ViewModel over an in-memory database with no reflection and no Robolectric shadow.

Why hand-rolled: the graph is ~12 repositories over 1 database. Hilt would add a kapt pass, an
Application-generated component, and a `@HiltViewModel` annotation on every class — for a graph this flat
that buys nothing and makes the dependency chain harder to read at a glance.

## The money path, end to end

1. The user types into a `OutlinedTextField` bound to a `String`. There is no numeric field state.
2. `MoneyParseResult.parse(text, currency, allowZero)` returns either `Success(MoneyMinor)` or
   `Invalid(message, kind)` with `kind ∈ {EMPTY, NOT_A_NUMBER, NEGATIVE, TOO_LARGE, TOO_PRECISE,
   ZERO_NOT_ALLOWED}` — never an exception, never a silent `0`, and never a silent round. A form shows the
   message; a test asserts the `kind`, so copy can change without the contract moving.
3. Validation runs in the form; the repository validates *again* (`ShopRepository.validateCredit`,
   `ObligationRepository.validateLoan/validateEmi`) because a form is not the only writer (backup restore,
   sample data).
4. Entities store `Long` minor units only. `quantity × unitPrice` is computed once, in
   `MoneyMinor.ofProduct`, which guards overflow against `MAX_MINOR = 1e15`.
5. Reads go through a DAO projection (`CreditRow`, `ObligationRow`, `DueRow`) that computes paid as
   `downPayment + COALESCE(SUM(payments), 0)` in SQL.
6. Projections become `ObligationSnapshot` / domain models in the repository — **not** in the ViewModel.
7. `MoneyFormat.format(minor, currency)` renders it. `CurrencySpec` supplies symbol, decimal count and
   grouping, so formatting is a function of data, not of `Locale`.

The same snapshot is used by the payment guard. That is the whole trick: because the number a screen shows
and the number that refuses an overpayment come from one function, they cannot disagree.

## The payment engine

`PaymentRepository` is the only writer against `payments`. Its three entry points share one shape:

- `record(...)` — resolve the obligation inside a transaction, clamp against
  `original − (recordedPaid + installmentId?)`, reject `<= 0`, reject cancelled, reject missing; insert and
  return the derived remaining.
- `update(id, ...)` — re-validate against `original − (paidExcluding(thisPayment))`, so an edit cannot
  smuggle an overpayment past a screen that showed the old value.
- `delete(row)` — if the payment pointed at an instalment line, recompute that line's `paidMinor` from the
  surviving ledger **inside the same transaction** (`PaymentDao.deleteAndRepair`), so a schedule can never
  be observed marked paid for money that no longer exists. `restore(row)` puts the exact row back for undo.

Nothing in `ui/` recomputes a balance. `domain/calc/FinancialMath.allocate` is the JVM-testable model of
how payments apply across instalments; the SQL projections mirror it, and
`FinancialMathTest` + `PaymentEngineTest` are what keep the two from diverging.

## Derived everything, stored nothing

The database stores facts (a credit, a payment, a plan, a schedule line) and never a result:

- no `remainingMinor` column on any payable table (asserted on-device by
  `androidTest/…/OnDeviceLedgerTest.kt` via `pragma_table_info`),
- no trigger, no `@Update` cascade that adjusts a total,
- no "last computed" cache.

`fallbackToDestructiveMigration()` is not called anywhere: with derived state, a failed migration must
fail loudly rather than quietly delete a ledger.

## Schedules

`installments` is a single table shared by loans and EMIs, discriminated by `ownerType` (`"loan"`,
`"emi"`) plus `ownerId`. Lines carry `scheduledAmountMinor`, `paidMinor`, and `manual`:

- generated from `InstallmentSchedule.generate(frequency, count, …)` when a plan is saved,
- `manual = true` lines survive a regeneration untouched,
- regeneration order is fixed: `detachFromLines` (clear payment → line pointers) → carry paid forward by
  `number` → `clearSchedule` → insert new lines. Skipping the detach is how a payment ends up pointing at
  a deleted row, which then looks like money missing from the schedule.

Per-line paid figures are *display* state derived by `InstallmentAllocator.allocate`; the authoritative
paid total for an obligation is always `payments` (plus the down payment), never the sum of the lines.

## Screens and navigation

`ui/KhataGoApp.kt` owns the graph: a single `NavHost`, four bottom tabs (Home, Records, Payments,
Analytics) with `More` as a destination, one shared detail route `detail/{type}/{id}` dispatched by
`RecordDetailDispatcher`, and form routes that take `editId` as a query argument rather than a second
route per form.

- Deep links (`khatago://add|search|due/today|due/tomorrow|records/overdue|home`) map through
  `DeepLinks.routeFor`; an unknown path yields `null` and is dropped, because `MainActivity` is exported
  and a stray `khatago://anything` must not be treated as "open my ledger at a sensible place".
- The app lock gate wraps the whole `Scaffold`, so a locked app composes no ledger value at all — not even
  into a screenshot.
- All detail screens live in `com.khatago.finance.ui.detail`, including `MoreScreen` and
  `ObligationDetailScreen`; the package name is historical, the rule is "one place for record-level UI".

## Files worth reading first

| File | Why |
|---|---|
| `core/money/MoneyMinor.kt` | the representation, and every guard that follows from it |
| `domain/model/FinancialModels.kt` | `ObligationSnapshot` — the type the whole app agrees on |
| `data/repo/PaymentRepository.kt` | the only writer for money movement, plus `PayableResolver` |
| `data/db/dao/StatsDao.kt` | the derived headline numbers, with the down-payment asymmetry spelled out |
| `data/db/KhataGoDatabase.kt` | entity list, version policy, WAL, seed hook, in-memory/wipe helpers |
| `ui/KhataGoApp.kt` | the graph, the lock gate, deep links |
| `ui/components/KhataGoKit.kt` | the component kit every screen is built from |
