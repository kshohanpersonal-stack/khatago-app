# Security

KhataGo holds a person's private financial reality on a device that gets lost, borrowed and repaired.
This file states exactly what is protected, how, and — equally important — what is *not* protected and why.

---

## Threat model, stated plainly

| Threat | Answer |
|---|---|
| Someone opens the app on an unlocked phone and reads the ledger | App lock (PIN, optional biometric) gates the UI |
| The device is lost, and the finder tries PINs | 5 attempts → 5-minute cool-down; no PIN recovery; 10⁴–10⁸ code space with a slow hash per guess |
| A backup file lands in a shared folder / cloud / Drive | The file is plain text, and the app says so before writing it. Nothing is encrypted at rest by KhataGo itself |
| Someone roots the device or attaches a debugger | Out of scope. App-private storage plus file-based encryption is the ceiling of what any app can do |
| The app exfiltrates data | It cannot: there is no `INTERNET` permission, no networking dependency, no analytics/crash/ads SDK. This is an OS-enforced property, not a policy |
| A malicious file is "restored" | Nothing is parsed unless the user chose it through SAF; `BackupValidation` rejects future versions, absurd record counts, negative/oversized amounts and out-of-range dates before a byte is written, and the user sees counts + warnings before confirming |

## The app lock (`data/repo/SecurityRepository.kt`, `core/security/PinHasher.kt`)

```
stored form:  "PBKDF2WithHmacSHA256:120000:<16-byte random salt, hex>:<32-byte derived key, hex>"
```

- **PBKDF2-HMAC-SHA256, 120,000 iterations, 256-bit derived key.** A 4-digit PIN has 10,000 candidates, so
  the only thing standing between a stolen phone and "reading someone's debts" is cost per guess; 120k
  iterations makes a brute force on-device take hours instead of seconds.
- **A fresh random salt per record**, so the same PIN on two installs produces different digests. Nothing in
  the store leaks *which* code is in use, and a table of precomputed 4-digit digests is useless.
- **The PIN is never stored, logged, echoed into a `toString()`, or written to the database.** The
  verification path derives and compares; the failure path increments a counter in the same preferences file.
- **Shape is validated before hashing**: 4–8 digits, nothing else. A malformed PIN is refused with a
  message rather than stored as a weak secret.
- **The stored string is ASCII whatever the device language is.** Both hex fields are rendered with
  `Locale.US`. The triple is written once and re-read on every unlock, so a formatter that followed the
  device locale could, on a locale whose decimal digits are not ASCII, store a value its own parser rejects
  — a lockout that looks like a corrupted database. `SecurityLockTest` sets an Arabic locale, encodes, and
  asserts both the shape (`32` and `64` hex characters) and a successful re-verify, so the property is a
  test rather than a comment.
- `MAX_ATTEMPTS = 5`, `LOCKOUT_MILLIS = 5 minutes`. After a cool-down the counter resets, and the UI shows
  `remainingAttempts` / `remainingLockoutMinutes` rather than a dead button.
- **Biometrics are an accelerator, never a replacement.** `isBiometricEnabled` reports false while no PIN
  exists, and the prompt is `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`. Removing the biometric shortcut, or
  enrolling a new finger, still has to pass through the PIN path.
- **No recovery.** "Forgot your PIN" is answered honestly: the ledger is intact on disk, and the only way in
  is to reinstall. Encryption-with-no-key would produce the same outcome while making a lockout unrecoverable
  *and* a backup impossible to read. A gate on the UI is the mechanism whose failure mode is
  "annoyance", not "data loss".

### What the lock does and does not do

The lock gates **the UI, not the disk**. `isUnlocked` is a `StateFlow` in `SecurityRepository`;
`onStop` → `onBackground()` re-arms it, `unlock()` clears it. Deliberately:

- the ledger must remain readable by the reminder worker and by a file export the user asked for, and
- a "locked" app whose *database* is also disabled would corrupt the very data the lock exists to protect.

The lock gate in `ui/KhataGoApp.kt` wraps the whole `Scaffold`, so a locked app composes **no ledger value
at all** — not into the view tree, and therefore not into a screenshot, a recents thumbnail, or an
accessibility tree inspection. The lock screen shows the greeting and nothing else.

`clearPin` requires the current PIN, so the setting cannot be flipped by a thumb on a table. Disabling the
lock in Settings is refused without it.

## Deletion and wiping (`ui/detail/DataManagementScreen.kt`)

- "Delete everything" is the only irreversible action in the app. Confirming takes **three taps on the same
  button**, with escalating copy ("Tap “Delete” three times to confirm." → "One more tap and everything
  goes."), because a single-tap destructive dialog is a pocket-dial away from deleting someone's ledger.
- A wipe deletes attachment files in the same operation as the rows that pointed at them, and removes the
  database file plus WAL side-files (`KhataGoDatabase.wipe()`).
- **A wipe preserves the PIN and the failure counter** — they live in `khatago_security`, not in Room. Two
  consequences, both wanted: a thief cannot erase the lockout by wiping the ledger, and a user who wipes the
  data does not lose the code protecting the *next* installation.
- Sample data can only be loaded into a provably empty ledger, so demo numbers can never be mixed into real
  ones.

## Notifications and the lock screen

`notify/Notifications.kt` + `ReminderWorker.kt`:

- one channel (`khatago.reminders`), `IMPORTANCE_DEFAULT`, description states that reminders are local;
- **the body never contains an amount or a person's name** — only counts ("2 payments are due"), because a
  notification is rendered on a lock screen someone else may be looking at;
- at most one notification per category per day, enforced by `reminder_log`'s UNIQUE `dedupeKey` with
  `OnConflictStrategy.IGNORE` (a re-run, a retry, or a device waking late inserts nothing: `-1`);
- nothing due → nothing posted;
- `PeriodicWorkRequest` (1 day, `KEEP` policy) instead of `AlarmManager`: there is no boot receiver to
  re-arm alarms, no internet to resync a schedule, and `KEEP` makes `syncFromPreferences()` idempotent.
- the permission is **checked, never demanded**: `Notifications.hasPermission()` is consulted before posting
  and a denial is a normal state (the worker returns success silently). On API 33+ the system shows its own
  one-time prompt when the channel is first used, which is the least surprising flow for an offline app;
- the channel sets `lockscreenVisibility = VISIBILITY_PRIVATE`, so even the title is hidden on a locked screen
  where the user's lock-screen setting asks for that.

## Exported surface

`MainActivity` is exported (launcher, plus a `khatago://` scheme). Therefore deep links are handled as if an
attacker wrote them:

- `DeepLinks.routeFor(target)` maps a fixed set of paths (`add`, `search`, `due/today`, `due/tomorrow`,
  `records/overdue`, `home`) and returns `null` for anything else;
- an unknown path is **dropped**, never "sent to home as a sensible default" — a foreign app must not be able
  to push the user into a screen (or a form) they did not ask for;
- ids arriving from a URI are parsed to a positive `Long` and then re-resolved through the repository; a
  nonexistent id renders a "record not found" state, not a crash and not someone else's row.

`FileProvider` (`exported="false"`) exposes only `filesDir/attachments/` — the `exports/` path you might
expect is deliberately absent, because a CSV or backup is written straight to wherever the user chose in the
system picker and is never copied into app storage where a content URI could reach it. No other provider is
exported, no `RECEIVE_BOOT_COMPLETED`, no `QUERY_ALL_PACKAGES`.

## Backups

`inspect → show counts/warnings → explicit Replace or Merge → import`, one transaction. Format version and
record-count caps are enforced before any write. A backup file is **not encrypted** by KhataGo and the UI
says that in as many words, with a suggestion to keep it somewhere the user already protects (a password
manager, an encrypted volume, or a USB drive that does not live in a bag).

## Verifying this yourself

```bash
# 1. The permission claim — the only dangerous permission in the app:
grep -c "uses-permission" app/src/main/AndroidManifest.xml      # → 1 (POST_NOTIFICATIONS)
grep "uses-permission" app/src/main/AndroidManifest.xml

# 2. No networking stack to permit:
grep -rniE "okhttp|retrofit|ktor|firebase|admob|crashlytics" app/build.gradle.kts   # → nothing

# 3. The PIN is stored as a digest only:
grep -rn "pin_hash" app/src/main/java/com/khatago/finance/data/repo/SecurityRepository.kt

# 4. Unit tests for the whole lock, on the JVM:
gradle :app:testDebugUnitTest --tests "com.khatago.finance.core.security.SecurityLockTest"
```

After installing an APK, the definitive check is `adb shell dumpsys package com.khatago.finance | grep -i permission`
and reading the list. If `INTERNET` is in it, this document is wrong and something was added to the build.
