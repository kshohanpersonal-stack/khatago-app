# Privacy

This is the entire privacy model of KhataGo, and it fits on one page because there is almost nothing in it.

---

## What KhataGo collects

**Nothing.** There is no account, no sign-in, no email field, no phone number field, no device identifier,
no advertising ID, no analytics library, no crash reporter, no error reporting, no push service, no remote
config, no feature flags fetched from anywhere, and no update check.

## What KhataGo can send

**Nothing, structurally.** `AndroidManifest.xml` requests one permission — `POST_NOTIFICATIONS` — and does
**not** request `INTERNET`. An Android app without `INTERNET` cannot open a socket; this is enforced by the
platform, not by our good intentions. There is also no networking library in the dependency graph to remove
later by accident:

```bash
grep -c "uses-permission" app/src/main/AndroidManifest.xml    # 1
grep -rniE "okhttp|retrofit|ktor|firebase|admob|crashlytics|play-services" . --include=*.kts   # nothing
```

The one outbound *intent* is a user-initiated `ACTION_SEND_VIEW` of a single attachment file through
`FileProvider`, and one *user-chosen* save location through the system file picker. Both are the operating
system's own UI, and both happen only when the user taps a button that names them.

## Where your data lives

| Data | Location | Deleted by |
|---|---|---|
| The whole ledger (shops, credits, people, borrowings/lendings, loans, EMI plans, schedules, payments, income/expense, categories, payment methods, notes, settings, reminders) | `khatago.db` in app-private storage | "Delete everything" in Settings · Data, or uninstalling |
| Receipt and photo attachments | `filesDir/attachments/` | the record that owns them, a wipe, or uninstalling |
| App-lock PIN digest, biometric preference, failed-attempt counter | `khatago_security` preferences | only by the lock screen itself, with the current PIN |
| CSV / JSON files | wherever the user chose, via the system picker | the user, like any other file they saved |

Nothing is mirrored, cached, staged, queued-for-sync, or kept in a temporary file. There is no queue to flush
because there is no destination.

## The one asymmetry worth knowing

The PIN store is **deliberately outside** the database, and it cuts both ways:

- a backup never contains your PIN (a `khatago.db` copy is not a key to anything), and
- "Delete everything" does **not** disarm the lock, so wiping the ledger cannot be a thief's way around it.

## What the app never does

- no ledger content in a notification body — only counts, e.g. "2 payments are due"; no names, no amounts,
  and the channel is `VISIBILITY_PRIVATE` for lock screens;
- no screenshots or photos taken by the app, ever; no camera access, no microphone, no contacts, no location,
  no call log, no SMS;
- no reading of other apps, no package enumeration;
- no background upload of diagnostics; a failure is shown to you in the UI, in words, because you are the only
  person who can see it;
- no "sign in to keep your data", no trial, no upsell, no feature that is off until you pay. There is no
  billing library in the build, so there is nothing to sell with.

## Backup files

A KhataGo backup is plain, readable JSON on your device. The app says this in as many words before writing one,
and does not offer encryption it does not perform. It contains everything in the ledger, including names,
notes and reference data — so the advice printed in the UI is the honest advice: keep it somewhere you already
protect, and delete copies you made "temporarily" on a shared computer.

## Reminders

Daily reminders are computed and delivered locally by WorkManager (`docs/SECURITY.md` covers the notification
rules). They fire because a due date arrived, not because a server scheduled anything; turning them off in
Settings unschedules the work entirely.

## Uninstalling

Uninstalling deletes the ledger. This is a feature of an offline app, and its flip side is that there is no
server to recover from — which is why onboarding, settings and the delete dialog all point at taking a backup
first, and why "Delete everything" needs three deliberate taps.

## For auditors, reviewers and the store listing

- Data safety: no data collected, no data shared, no encryption needed because nothing leaves the device.
- Permissions: `POST_NOTIFICATIONS` only, used for local due-date reminders, never for anything else.
- Ads: none. Subscriptions/purchases: none. Internet: not permitted.
- Account/deletion requests: not applicable — there is no account and no data held by anyone but the user.

If any of the above ever stops being true, this file and the manifest must change in the same commit, and that
is the point of writing it down.
