# KHATAGO

**A shop-credit and personal-debt ledger that runs entirely on your phone. No account. No server.
No internet. No ads. No subscription.**

KhataGo is the paper *khatā* (খাতা) — the notebook a Bangladeshi shopkeeper keeps behind the counter —
rebuilt as an offline Android app for the whole household ledger: shop credit, personal borrowings and
lendings, bank/NGO loans, EMI purchases, and a plain income/expense record. Everything it shows you is
computed from what you typed, on your device, and it never leaves unless you export it yourself.

```
Min. Android 8.0 (API 26) · target/max API 35 · Kotlin 2.x-era toolchain (1.9.24), Jetpack Compose + Material 3
Package: com.khatago.finance · License: Apache-2.0 (see LICENSE and NOTICE)
```

---

## Contents

- [What it does](#what-it-does)
- [The rules that make the numbers trustworthy](#the-rules-that-make-the-numbers-trustworthy)
- [Install / run](#install--run)
- [Build it yourself](#build-it-yourself)
- [Release build & signing](#release-build--signing)
- [Tests](#tests)
- [Continuous integration](#continuous-integration)
- [Documentation](#documentation)
- [Brand assets](#brand-assets)
- [Privacy, in one paragraph](#privacy-in-one-paragraph)

---

## What it does

Seven modules, one payment engine:

| Module | What it records | Outstanding balance means |
|---|---|---|
| **Shop credit** | Goods taken now, paid later, against a named shop | `totalAmount − Σ payments` |
| **Borrowed** | Money you took from a person | `amount − Σ payments` |
| **Lent** | Money you gave to a person, expected back | `amount − Σ payments` |
| **Loans** | Bank / NGO loans with a down payment and a schedule | `(totalPayable + down) − (down + Σ payments)` |
| **EMI** | Instalment purchases with a down payment and a schedule | `totalPayable − Σ payments` |
| **Income** | Money in, categorised | — (ledger only) |
| **Expense** | Money out, categorised | — (ledger only) |

Around those:

- **Home** — "I owe" and "I am owed" as two separate numbers, due-this-week, overdue, this month's
  income/expense/paid, a per-module breakdown, upcoming lines and recent activity. Hideable widgets.
- **Payments centre** — one screen for every payable: what is due today, tomorrow, this week, overdue,
  and a single payment sheet that prefills the exact remaining amount and refuses anything larger.
- **Records** — module tabs with filters (all / outstanding / settled / overdue / cancelled), sorting,
  and per-record detail with the schedule, the payment history and attachment thumbnails.
- **Analytics** — cash flow by month, expense by category, income by source, outstanding breakdown,
  payment-day histogram, plus rule-based insights that state their window and their arithmetic.
- **Reports & export** — CSV for every module and a JSON backup, both saved through the system file
  picker (SAF); nothing is written to shared storage behind your back.
- **Backup & restore** — inspect first (record counts, format version, warnings, dropped rows), then an
  explicit **Replace** or **Merge** decision. A restore that would lose records is refused, not warned about.
- **Search** — across all modules, 180 ms debounce, 2-character minimum, routes on the stored type key.
- **Reminders** — a daily local notification (WorkManager, not AlarmManager, because there is no boot
  receiver to re-arm and nothing to sync). Never shows an amount on the lock screen. At most one per
  category per day. Silent when nothing is due.
- **App lock** — optional 4–8 digit PIN (PBKDF2-HMAC-SHA256, 120,000 iterations, random salt per record)
  with an optional biometric shortcut, a 5-attempt/5-minute cool-down, and re-lock on background.
- **Onboarding** — three skippable steps that teach the two rules the rest of the app depends on, plus
  optional sample data in a ledger that is provably empty.
- **Settings** — currency label (relabels only, never converts), widget visibility, reminders, payment
  methods and categories, data management, about.

## The rules that make the numbers trustworthy

These are not style preferences; they are the reasons the balances are correct. Each is enforced in
code and each has a test or a query shape that makes the wrong thing hard to write.

1. **Money is `Long` minor units.** Taka is stored as poisha. There is no `Double`, no `Float`, and no
   stringly-typed arithmetic anywhere in the money path. Multiplication by quantity happens once, in
   `MoneyMinor.ofProduct`, with an overflow guard at `1e15` minor units.
2. **Balances are never stored.** There is no `remaining` column, no cached total, no trigger. Every
   outstanding figure — the home headline, a list row, a detail screen, the payment guard, the CSV — is
   derived from the same SQL against `payments`. Nothing can drift, because there is only one place to be wrong.
3. **One writer for payments.** `PaymentRepository` is the only path that records money against an
   obligation. It resolves the obligation, refuses overpayment *including one paisa over*, refuses
   non-positive amounts, refuses cancelled records, and repairs the affected instalment line in the same
   transaction it deletes in.
4. **Debt direction is never netted.** "I owe" and "I am owed" are two numbers, two colours, two flows.
   A lending and a borrowing against the same person do not cancel, because a notebook that hides one of
   them is how people lose money.
5. **Down payments count exactly once.** A loan's `totalPayableMinor` excludes the down payment; an EMI's
   includes it. The two therefore derive outstanding differently — and both are checked against
   `PayableResolver`, so the headline and the record detail cannot disagree. See [docs/MONEY.md](docs/MONEY.md).
6. **Schedules are derived, editable, and rebuild-safe.** Instalment lines are generated from the plan but
   can be edited by hand; regenerating after an edit preserves per-line paid amounts and manual lines, and
   detaches payment pointers first so a payment never ends up attached to a deleted row.
7. **Cancelled is not deleted.** A cancelled record keeps its history, is excluded from totals by default,
   and is restorable. Settling is not cancelling, and neither is deleting.
8. **One currency, per profile, and changing it relabels.** There is no FX in an offline app on purpose:
   a silent conversion of a 3-year-old ledger is worse than no conversion.

Full detail: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/MONEY.md](docs/MONEY.md),
[docs/DATA.md](docs/DATA.md).

## Install / run

There is no store listing and no APK in this repository's source tree — an APK is a build artefact, and
committing one would hide the fact that it can be rebuilt from these sources.

Two ways to get it running:

**A. From CI (no Android SDK needed).** Push to `main` (or open a PR) and let
[`.github/workflows/ci.yml`](.github/workflows/ci.yml) build it. The `debug` job uploads
`app-debug.apk` as an artefact; the `release` job produces `app-release.apk` when signing secrets exist.
Install on a device with `adb install -r app-debug.apk` (or enable "install unknown apps" and sideload it).
A debug build is signed with the standard Android debug key, so it can sit next to nothing else — but do
not ship one to other people as if it were a release.

**B. From Android Studio.** Open this folder, let Gradle sync, pick the `app` configuration, Run. Requires
Android Studio with the API 35 platform and Build-Tools installed, JDK 17, and internet **only** to
download the Gradle dependencies on first sync — the app itself needs none.

Debug builds are the normal way to use this app: they are functionally identical (release only adds R8
shrinking and your signing key) and they keep the sample-data and destructive-wipe flows reachable.

## Build it yourself

```bash
# 0) Prerequisites: JDK 17, Android SDK with platform 35, ANDROID_HOME set.

# 1) The Gradle wrapper is committed as configuration only (see note below), so generate it once:
gradle wrapper --gradle-version 8.9          # or: sdkman/IDE-managed Gradle 8.9

# 2) Point the build at your SDK (never committed; there is a template):
cp local.properties.example local.properties
#   then edit sdk.dir=

# 3) Build + test
gradle :app:assembleDebug          # debug APK
gradle :app:testDebugUnitTest      # unit tests (pure JVM + Robolectric)
gradle :app:lintDebug              # Android Lint
gradle spotlessCheck               # formatting (CI enforces this)
gradle :app:assembleRelease        # unsigned release APK unless signing is configured (see below)
```

**Why there is no `gradle-wrapper.jar` in git.** A binary blob nobody can review does not belong in a
repository whose whole claim is auditability. `gradle/wrapper/gradle-wrapper.properties` pins Gradle 8.9
and CI installs that exact version, so the build is still reproducible; running `gradle wrapper` locally
regenerates the jar for machines that want it. If you prefer to commit the jar (some teams do, for
air-gapped builds), that is a one-file decision, and nothing else in the build depends on it.

## Release build & signing

Release signing is **opt-in and external**. The build never looks for a keystore inside the repository,
and no keystore, password, or `local.properties` is committed (`.gitignore` enforces this; CI greps for
them and fails the build if one appears).

Sources, in priority order:

1. `keystore.properties` in the repository root (git-ignored):
   ```properties
   storeFile=/absolute/path/to/khatago.jks
   storePassword=…
   keyAlias=khatago
   keyPassword=…
   ```
2. CI secrets, consumed by [`.github/workflows/release.yml`](.github/workflows/release.yml):
   `KHATAGO_KEYSTORE_BASE64`, `KHATAGO_KEYSTORE_PASSWORD`, `KHATAGO_KEY_ALIAS`, `KHATAGO_KEY_PASSWORD`.

With neither present, `assembleRelease` still succeeds and produces an **unsigned** APK — deliberate, so
the release pipeline can be exercised without inventing credentials. Sign it later with `apksigner`.

R8 is on for release (`minifyEnabled` + `shrinkResources`) with the keep rules in
`app/proguard-rules.pro`; the shrink step does not rename Room entities, DAO-generated implementations,
serialised backup DTOs, or `AppContainer`/`ReminderWorker`, which are reflection- or framework-instantiated.

Before publishing an artefact, do the honest minimum: install it on a real phone, run
`adb shell dumpsys package com.khatago.finance | grep -i permission` and confirm the *only* dangerous
permission is `POST_NOTIFICATIONS`.

## Tests

The mandatory suite lives in `app/src/test/` and runs on the JVM (no emulator needed):

| File | Covers |
|---|---|
| `core/money/MoneyCoreTest.kt` | parse/round/format, poisha-safe multiplication, clamping, the 1e15 guard, grouping and negative sign placement |
| `domain/calc/FinancialMathTest.kt` | instalment allocation, `clampPaid`, due/overdue derivation, schedule generation per frequency, last-line remainder |
| `core/CsvExportTest.kt` | RFC-4180 quoting, embedded quotes/newlines/commas, header row, `MoneyFormat.toCsvNumber` |
| `core/security/SecurityLockTest.kt` | PIN shape rules, per-record salt, never storing the PIN, verify/change/clear, biometric gating, attempt throttling and cool-down (Robolectric) |
| `data/PaymentEngineTest.kt` | the whole payment engine against a real in-memory Room DB: overpayment refusal, edit validation, delete/undo repair, down-payment semantics, schedule-rebuild safety, due queue (Robolectric) |

```bash
gradle :app:testDebugUnitTest --tests "com.khatago.finance.*"
```

`app/src/androidTest/java/com/khatago/finance/OnDeviceLedgerTest.kt` runs the same engine against real
SQLite on a device (empty-set aggregates, the due `UNION ALL` view, and a `pragma_table_info` grep that
proves no table stores a derived balance). It needs an emulator, so CI runs the unit suite on every push and
the instrumented suite on demand:

```bash
gradle :app:connectedDebugAndroidTest
```

## Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every push and pull request:

1. `checks` — the static guards in `tools/` (brace balance, import resolution, every DAO call site,
   every `container.*` accessor), then `spotlessCheck`.
2. `unit-tests` — `testDebugUnitTest`, plus the test report as an artefact.
3. `build` — `assembleDebug` + `lintDebug`, uploading `app-debug.apk`.
4. `release` — `assembleRelease` on `main` only, attaching the signed or unsigned APK to a GitHub Release
   when a tag (`v*`) triggered the run.

Add it in your own fork/clone by pushing to `main` and enabling **Actions** in the repository settings —
workflows do not run until Actions is on.

## Documentation

| Document | Read it when |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | you want the layering, why one module, and where each responsibility lives |
| [docs/MONEY.md](docs/MONEY.md) | you are touching any number, any format, or any rounding |
| [docs/DATA.md](docs/DATA.md) | you are adding a column, a query, a migration, or an export |
| [docs/SECURITY.md](docs/SECURITY.md) | you are touching the app lock, backup, or what is stored where |
| [docs/PRIVACY.md](docs/PRIVACY.md) | someone asks "where does my data go?" (answer: nowhere) |
| [docs/DESIGN.md](docs/DESIGN.md) | you are changing colour, type, spacing, motion, or icons |
| [docs/TESTING.md](docs/TESTING.md) | you are adding a test, or wondering what is and is not covered |
| [docs/RELEASE.md](docs/RELEASE.md) | you are cutting a release, signing an APK, or shipping the Play bundle |

## Brand assets

The launcher icon, the adaptive icon, the splash mark and the wordmark are **generated** from two masters
in `assets_src/brand/` (`flat_square.png`, `logo_wordmark.png`). The masters currently checked in are an
original placeholder created for this repository so that the build is self-contained — swap in official
artwork and re-run the generator, and nothing else has to change:

```bash
# replace the masters, then:
./assets_src/brand/regenerate_icons.sh
```

It regenerates every `mipmap-*` density, the `drawable-nodpi` copies, the monochrome adaptive layer, the
notification icon and the splash icon. All of it is derived, so it should never be hand-edited.

## Privacy, in one paragraph

KhataGo has no internet permission, so it *cannot* phone home — that is an OS-enforced statement, not a
policy. It collects nothing, sends nothing, and contains no analytics, crash-reporting, advertising or
billing SDK. The only things it persists are a Room database in app-private storage and a handful of
preferences; the only data leaving the device is whatever you explicitly export through the system
picker. Uninstalling the app deletes the entire ledger, which is exactly why the app tells you to take a
backup before you do it, and why a restore needs an explicit Replace/Merge decision rather than a shrug.
Full text: [docs/PRIVACY.md](docs/PRIVACY.md).

---

## Licence

Apache License 2.0 — see [LICENSE](LICENSE). Attribution and third-party notices: [NOTICE](NOTICE).
