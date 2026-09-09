# Money

How KhataGo represents, computes, displays and exports money. If you touch a number anywhere in this
app, this file applies.

---

## 1. The representation

**An amount is `Long` minor units. Always. Everywhere. No exceptions.**

```kotlin
data class MoneyMinor(val minor: Long)   // ৳100.50  ->  MoneyMinor(10050)
```

| Rule | Where enforced | Why |
|---|---|---|
| No negative money | `MoneyMinor.init` requires `minor >= 0` | a "credit balance" is not a thing a paper khata records; refusing it makes `remaining >= 0` provable |
| No overflow wrap | `addMinor` / `subtractMinor` / `times` all `require` the result stays in range | a Long that rolls over becomes a *small* number, which looks plausible |
| Supported maximum | `MAX_MINOR = 1_000_000_000_000_000` (10¹⁵) | beyond a personal ledger, and almost always a fat-finger entry |
| `Long`, not `Int` | every entity column | 10¹⁵ minor units does not fit an `Int`, and poisha in a `Double` is how 0.1 + 0.2 = 0.30000000000000004 gets into a balance |

`MoneyMinor` is deliberately **not** an inline value class: Room and `kotlinx.serialization` both need a
plain field, and the guard on construction is more valuable than the allocation it would save.

## 2. Parsing what the user typed

`MoneyParseResult.parse(raw, currency, allowZero)` (`core/money/MoneyFormat.kt`) is the **only** door from
"characters" to "money":

| Input | Result |
|---|---|
| `""`, `"   "` | `Invalid(…, EMPTY)` |
| `"abc"`, `"10.5.2"` | `Invalid(…, NOT_A_NUMBER)` |
| `"-50"` | `Invalid(…, NEGATIVE)` — *never* silently absolute-valued |
| `"10.005"` in a 2-decimal currency | `Invalid(…, TOO_PRECISE)` — **never silently rounded** |
| `"0"` with `allowZero = false` | `Invalid(…, ZERO_NOT_ALLOWED)` |
| 40 digits | `Invalid(…, TOO_LARGE)` |
| `" ৳10.00 "`, `"10,000.50"`, `"10.00৳"` | `Success` — the symbol, spaces and grouping glyphs are stripped wherever they sit |
| `"10 00"` | `Success` as **৳1,000.00** — a space is a thousands separator, never a decimal mark |

Both properties matter: a form can show the message verbatim, and no caller can quietly decide that
rounding half a poisha was fine.

`MoneyMinor.parse(raw, decimals, allowZero, strictExponent)` underneath uses
`BigDecimal` with `RoundingMode.HALF_UP` — the South-Asian market convention — and returns `null` rather
than throwing for anything out of range.

## 3. Formatting

`MoneyFormat.format(amountMinor, currency)` is pure, locale-independent and never touches `java.text`:

- grouping is 3-digit (`1,234,567`), the glyph comes from `CurrencySpec.grouping`;
- the decimal glyph comes from `CurrencySpec.decimalMark`;
- `CurrencySpec.symbolBefore` decides `৳5.00` vs `5.00 د.إ`;
- `decimals == 0` prints no fractional part at all (`Rp10,000`, never `Rp10,000.00`);
- a negative renders the sign **outside** the symbol: `-৳5.00`. Inside the digits it reads as part of the
  number, and a paper khata writes it outside;
- the same function is used by every screen, the CSV writer and the report headers, so a printed report can
  be compared against the screen digit for digit.
- every percentage is `.`-fixed too (`MoneyFormat.percentLabel`, the schedule's `percentLabel`, the insight
  sentences): a bar label like `66,7%` under `৳1,000.50` reads as a different convention on the same line,
  and on a locale with non-ASCII digits it stops being a number at all. Any `%`-formatted output in this
  app is therefore locale-pinned — including the hex in the app lock, where the value is parsed back later.

`MoneyFormat.toCsvNumber(minor, currency)` is the export variant: plain major-unit decimal, no symbol, no
grouping, `RoundingMode.HALF_UP` to the currency's scale — `1234.50`. A spreadsheet must be able to sum the
column. (Asserted in `core/CsvExportTest.kt`.)

## 4. Currency

`CurrencySpec` is data, not a `Locale`:

```kotlin
CurrencySpec(code, symbol, decimals, symbolBefore, grouping, decimalMark)
```

- **One active currency per profile.** Every stored amount is interpreted in it.
- **No FX conversion, ever, by design.** A personal ledger that invents or caches a rate produces wrong
  historical numbers, and a wrong number is worse than a missing feature. `CurrencySpec.fromCode` falls
  back to the default for an unknown/blank code so a corrupt preference cannot make amounts unreadable.
- Changing the currency in Settings **relabels**. The stored minor-unit integers are untouched, and the UI
  says so.
- Twelve currencies ship (`CurrencySpec.ALL`), BDT first because the product is built for that market.

## 5. Derived balances — the rule the whole app hangs on

There is no stored balance anywhere. Not a column, not a trigger, not a cache, not a "last known total".

```
original  =  the record's own obligation amount
paid      =  SUM(payments belonging to it)  [+ down payment, see §6]
remaining =  max(0, original - paid)
```

- `ObligationSnapshot` (`domain/model/FinancialModels.kt`) is that derivation as a type, with
  `remainingMinor` and `remainingAfter(candidate)`.
- `PayableResolver.resolve(...)` (`data/repo/PaymentRepository.kt`) builds a snapshot for all five payable
  types from one `when`. **The payment guard and every screen read through it.**
- `FinancialBalance` (`domain/calc/FinancialMath.kt`) is the same arithmetic, pure, on the JVM, tested.
- `clampPaid(original, paid)` keeps a derived total inside the obligation even for data that arrived from a
  restore: the guard already refuses overpayment at write time, so the clamp is defence against corruption,
  not a licence to overpay.

The invariant worth keeping: *the number a screen shows and the number that rejects an overpayment are
produced by the same function.* A detail screen that recomputed "remaining" from the rows it can see would
be free to disagree with the engine, and the engine is the thing that writes.

## 6. Down payments — the one asymmetry, and the convention that resolves it

A down payment is **metadata on the plan**, never a `payments` row (the form's helper states this, so a
user does not "helpfully" record it twice).

The **paid** side is identical for both plans and never varies:

```
paid      = downPaymentMinor + SUM(payments)
remaining = original - paid
```

What differs is `original`, because `totalPayableMinor` means something different per plan, and that is the
trap:

| Plan | `totalPayableMinor` | `original` | Schedule generated from |
|---|---|---|---|
| **Loan** | **excludes** the down payment (principal + interest) | `totalPayableMinor + downPaymentMinor` | `totalPayableMinor` |
| **EMI** | **includes** the down payment (whole cost of the purchase) | `totalPayableMinor` | `totalPayableMinor - downPaymentMinor` |

The EMI row is where a down payment is double-counted in practice. Its `totalPayable` already contains the
down payment, so `+ downPayment` on the left of the subtraction makes the plan look one instalment bigger
than the user's (a ৳6,60,000 fridge with a ৳60,000 down payment would show ৳6,60,000 still owed after the
down payment, when ten EMIs of ৳60,000 are exactly ৳6,00,000). The loan has the opposite hazard: its
`totalPayable` excludes the down payment, so a query that subtracts only `SUM(payments)` keeps reporting the
down payment as outstanding and the plan never settles.

Both halves are written out instead of cancelled, and `StatsDao.kt`, `EmiDao.kt`, `LoanDao.kt`,
`BackupValidation`, `PayableResolver` and the CSV export all spell them out in the same shape, so a reader
can compare them side by side. `data/PaymentEngineTest.kt` asserts the three EMI derivations agree while a
plan is open **and** when it settles, and that a settled loan reports zero on the dashboard tile — those are
exactly the two mistakes above.

A cancelled plan's down payment stays cancelled: it is excluded from every total by `WHERE cancelled = 0`.

## 7. Instalments

- `InstallmentSchedule.generate` (`core/time/InstallmentSchedule.kt`) is deterministic: exactly `count`
  lines, day-of-month anchored to the first due date and **clamped** (a 31 Jan start never invents 31 Feb
  and does not drift back to the 31st), `OneTime` yields one line, `Custom` interval is clamped to 1..366.
- Amounts: if a fixed instalment amount is given it is respected, and the indivisible remainder lands on the
  **last** line (a balloon instalment). If only a total is given, the even split's remainder lands on the last
  line. A schedule's amounts therefore always sum to the plan total exactly.
- `InstallmentAllocator.allocate(lines, totalPaid, today)` applies an obligation's paid total
  chronologically and greedily, with money paid *directly at a line* counting first. Those line figures are a
  **subset** of `totalPaid` (a payment against an instalment is also a payment against the obligation), so the
  pool that spreads over the schedule is `totalPaid - SUM(lines' own paid)`. `sum(allocated)` is therefore
  exactly the obligation's paid total whenever the schedule can absorb it, and never more than it — that is
  the property that keeps the schedule and the headline in agreement.
- Per-line `paidMinor` is display state. The authoritative paid total is always the payments table (plus the
  down payment), never `SUM(installments.paidMinor)`.

## 8. Statuses

`LedgerStatus.of(original, paid, dueDate, today, cancelled)` — derived, never stored:

```
cancelled                          -> Cancelled
paid >= original                   -> Paid          (even if paid late; a settled record is never "Overdue")
paid > 0                           -> PartiallyPaid
dueDate < today and original > 0   -> Overdue
original == 0                      -> Paid
otherwise                          -> Unpaid
```

## 9. Export numbers

CSV cells for amounts are `toCsvNumber` output — no symbol, no grouping, one row per record — with the
currency and the date range stated in the header, and RFC-4180 quoting for commas, quotes, newlines and
edge whitespace via `CsvWriter`. JSON backup carries minor-unit integers plus a `backupVersion`, so an
export from a future version cannot be mistaken for the current shape (see
[DATA.md](DATA.md#backup-json)).

## 10. What is *not* allowed here

- `Float`, `Double`, or `Int` for any stored or computed amount (`MoneyFormat.percent` returns `Double`, and
  only for a bar/label — it is never fed back into a balance);
- a stored `remaining`, `balance` or `totalPaid` column (`androidTest/…/OnDeviceLedgerTest.kt` greps
  `pragma_table_info` to keep it that way);
- rounding in a form (`MoneyParseResult` rejects the input instead);
- netting "I owe" against "I am owed";
- currency conversion of any kind;
- "simplifying" a down payment in or out of a query. `original` is `totalPayable + downPayment` for a
  loan and plain `totalPayable` for an EMI (§6): a query that "harmonises" the two branches is wrong
  for one of them, and the totals silently disagree with the module screen.
