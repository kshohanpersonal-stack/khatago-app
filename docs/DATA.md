# Data

The database, the derived queries, the migrations policy, and everything that leaves the device
(CSV, JSON backup) or comes back in (restore, sample data).

---

## Storage map

| Store | Contents | Why there |
|---|---|---|
| `khatago.db` (Room, SQLite, WAL) | the entire ledger + reference data + settings that a query needs | one place for one dataset: transactions, backup, wipe and migration all apply to it uniformly |
| `khatago_security` (SharedPreferences, `MODE_PRIVATE`) | PIN digest, biometric flag, failure counter, lockout timestamp | **must not** be inside the ledger file: a backup must not carry a PIN, and "delete all data" must not silently disarm the lock |
| `filesDir/attachments/` | receipt/photo copies, named by UUID | removed in the same operation that deletes their record or wipes the app — leaving images behind would be a privacy bug, not a cleanup |
| nothing else | no network cache, no analytics buffer, no remote config | there is no network stack to cache it through |

## Tables (18 entities, `data/db/entity/Entities.kt`)

| Table | Role | Key columns |
|---|---|---|
| `profile` | one row, `id = 1` | `displayName`, `currencyCode`, `onboardingComplete`, … |
| `shops` | a shop the user buys from | `name`, `archived` |
| `shop_credits` | goods taken on credit | `totalAmountMinor`, `overrideTotal`, `purchaseDateEpochDay`, `dueDateEpochDay?`, `cancelled` |
| `people` | a person on either side of a debt | `name`, `direction` |
| `borrowings` / `lendings` | money in from / out to a person | `amountMinor`, `dueDateEpochDay?`, `cancelled` |
| `loans` | bank/NGO loan plan | `principalMinor`, `totalPayableMinor` (**excludes** down payment), `downPaymentMinor`, `installmentCount`, `installmentFrequency`, `interestRatePercent` |
| `emi_purchases` | instalment purchase plan | `cashPriceMinor`, `totalPayableMinor` (**includes** down payment), `downPaymentMinor`, `emiAmountMinor`, `installmentCount` |
| `installments` | one shared schedule table for loans and EMIs | `ownerType` (`"loan"`/`"emi"`), `ownerId`, `number`, `scheduledAmountMinor`, `paidMinor`, `manual` |
| `payments` | **the only place money moves** | `payableType`, `payableId`, `installmentId?`, `amountMinor`, `paidDateEpochDay`, `methodName`, `reference?` |
| `payment_methods`, `categories` | reference rows, user-editable | `name`, `isDefault` |
| `incomes`, `expenses` | the plain ledger | `amountMinor`, `dateEpochDay`, `category`, `note` |
| `attachments` | one image/document per row | `targetType`, `targetId`, `fileName`, `mimeType`, `sizeBytes` — the bytes live in `filesDir/attachments/`, the row is the pointer |
| `reminders` | user-defined date reminders | `entityType?`, `entityId?`, `dueDateEpochDay`, `dueTimeMinute`, `enabled`, `lastFiredEpochDay` |
| `reminder_log` | dedupe ledger for notifications | `dedupeKey` UNIQUE |
| `app_settings` | the switches a *query* needs | `key`, `value` (stringly typed, read leniently — see below) |

There are **no TypeConverters**: dates are `Long` epoch days, timestamps are `Long` epoch millis, enums are
`String` keys (`PayableType.displayName`) resolved with `fromKey`. That keeps every column greppable in a
SQLite browser and keeps an enum rename from corrupting history.

## Derived state — what no table has

`remaining`, `balance`, `paidTotal`, `outstanding`. Paid is always
`downPayment + COALESCE(SUM(payments), 0)` computed in SQL; the projections that read it are:

- `CreditRow` (`ShopDao`/`CreditDao`) — shop lists and record rows,
- `ObligationRow` (`LoanDao`, `EmiDao`) — loan/EMI lists and details,
- `DueRow` + `DueSummaryRow` (`DueDao`) — the union "due view" behind the Payments tab, the dashboard tiles
  and the reminder worker,
- `StatsDao` — the cross-module headline (`observeTotalIOwe`, `observeTotalOwedToMe`, overdue, per-module
  outstanding) and their `*Once` twins used by export/reports.

Every `StatsDao` aggregate is `COALESCE(SUM(remaining), 0)` over an inner query that clamps per record
(`WHERE remaining > 0`) and excludes cancelled records — so one corrupt record cannot poison a headline,
and a settled one is never negative. `data/PaymentEngineTest.kt` asserts the dashboard tile, the module
query and `PayableResolver` produce the same number for the same data.

**Settings are read leniently.** `AppSettingEntity` booleans are written as `"1"`/`"0"`, seeded as
`"1"`/`"0"`, and historically also accepted `"true"`/`"false"`. `ui/ViewModels.kt`'s `toSwitchOn(fallback)`
is the only reader, with the *friendly* default (an absent reminders key means "on"). A strict
`== "true"` comparison is how a switch silently renders off after a seed or a restore.

## Queries: the shapes that must not change

- **Never reference a Kotlin projection as a table** (`CreditRow` is a `@Query` result, not a `FROM` target).
- **A `WHERE` in the last `UNION ALL` branch only** leaks rows from the other branches — every branch of the
  due view carries its own `cancelled = 0` and date filter.
- No shared materialised "totals" table: the per-module and cross-module queries deliberately mirror each
  other in ~20 lines of SQL rather than introducing a cache that can lag.
- Anything a ViewModel calls is `suspend` (or a `Flow`); no main-thread queries. `allowMainThreadQueries()`
  exists **only** inside `createInMemory`, for tests.

## DAO layer

12 DAOs, obtained through `AppContainer` → repository → `database.xxxDao()`; no screen calls a DAO
directly. `tools/audit_data_api.py` resolves every `database.xxxDao().method()` call site in the repository
against the real declarations, which is what catches a renamed query before the (impossible-here) compile
would.

Note: attachments go through `catalogDao` / `AttachmentRepository`; there is no `attachmentDao()`.

## Migrations

`KhataGoDatabase` is `version = KHATAGO_DB_VERSION` (**1** for v1.0.0), `exportSchema = true`,
`room.schemaLocation = app/schemas`, built with `addMigrations(*KhataGoMigrations.all.toTypedArray())` and
**never** `fallbackToDestructiveMigration()`.

Rules (from `data/db/KhataGoMigrations.kt`):

1. Any schema change bumps the version and adds a `Migration(old, new)`; there is no automatic path.
2. A migration may only add columns/indices/tables, or copy data forward — never drop a column that holds
   user data in a release that has already shipped.
3. Additive columns need a non-null default or must be nullable, because `payments`, `installments` and the
   payable tables are read by derived queries that would otherwise meet a `NULL` mid-migration.
4. Exported schemas (`app/schemas/com.khatago.finance.data.db.KhataGoDatabase/1.json`) are the diff tool.
   `:app:kaptDebugKotlin` is the task that writes them — not `kaptGenerateStubsDebugKotlin`, which only
   produces the stubs, and not a plain build, which may skip kapt entirely from the build cache; force it with
   `gradle :app:kaptDebugKotlin --rerun`. If a committed schema and a generated one differ, the migration is
   wrong, and the diff is the review. CI checks exactly that and warns on drift (see `app/schemas/README.md`).
5. `BackupDocument.backupVersion` is a **separate** number from the schema version and must not silently move
   with it.

`KhataGoDatabase.wipe()` deletes every row, re-seeds reference data, then closes and removes the file plus
its WAL side-files — "fresh start" has to be a fresh *file*, not a truncate, or the WAL keeps old pages
readable.

## Seeding

`RoomDatabase.Callback.onCreate` runs raw SQL that inserts reference rows (default payment methods,
categories, settings) — it must **not** insert a `profile` row: `onboardingComplete` is stored on the
profile, and a seeded profile makes the app look already-onboarded and skips the flow.

`KhataGoSeed` (sample/demo ledger) is only ever run from an explicit user action, only when the ledger is
provably empty (`DataRepository.isEmpty()` counts six payable/ledger tables and checks people), and it sets
`sample_data_loaded` inside the
same transaction as the inserts — a flag written outside the transaction could survive a failed load.

## CSV export

`CsvExportRepository` + `core/csv/CsvWriter.kt`:

- CRLF line endings, optional UTF-8 BOM (`CsvWriter.Bom`) so Excel detects UTF-8 for `৳`, `রহমান`;
- RFC-4180 quoting: a value containing `,` `"` CR LF or with leading/trailing whitespace is quoted, embedded
  quotes double up;
- amounts are `MoneyFormat.toCsvNumber` — plain major units, no symbol, no grouping — so the column sums;
- the currency and the date range are printed in the header, because a bare number in a file is ambiguous
  five years later;
- `CsvWriter.parse` exists to make the round-trip testable (and is used by `core/CsvExportTest.kt`).

Files are written only through the SAF picker the user invoked; `FileProvider` serves the app's own
attachments directory, nothing else.

## Backup JSON (`data/backup/`)

`BackupDocument` mirrors the entity graph (one definition of "what a record is"), with four load-bearing
rules:

1. **Primary keys are `@Transient`** — ids are written for human readability but ignored on import, where
   every id is freshly assigned and foreign keys re-mapped. Importing a file that dictates ids into a
   non-empty database is how a payment lands on an unrelated obligation.
2. **Amounts are minor-unit `Long`s**, exactly as stored: an export → edit → import round-trip cannot
   introduce a float or a locale-dependent separator.
3. **`backupVersion` is checked before anything is touched** — `> CURRENT_VERSION` is refused with an
   explanation (`FutureVersion`), `< MIN_SUPPORTED_VERSION` likewise. A file from the future is never
   half-applied.
4. **Reference data travels with the ledger** (profile, settings, categories, payment methods) so a restored
   ledger is not pointing at categories that do not exist.

`BackupValidation.validate()` additionally enforces `MAX_RECORDS = 200_000`, non-negative amounts,
`amount <= 1e15`, sane epoch-day ranges, and referential integrity: rows whose owner is absent are dropped
and counted (`droppedRecords`), and a payment is kept only if `paymentBelongs` — its obligation *and* its
installment line, both post-link-check — survived. A record with more paid than owed gets a
`PaidExceedsObligation` warning, not a silent rewrite.

Restore flow, always in this order: `exportTo`/`inspect(uri)` → `InspectResult.Ready(counts, warnings,
dropped)` or `Rejected(issues)` → the user picks **Replace** or **Merge** → `importDocument(document, mode)`
→ `RestoreReport(applied, recordsAdded, droppedRecords, warnings, headline)`. Nothing is written between
`inspect` and the explicit mode choice, and a file the user did not choose is never parsed.
