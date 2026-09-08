# Release

How a KhataGo release is produced, signed, verified and shipped — and what a reviewer must do
themselves, because **nothing in this repository claims a build has run**. The sandbox this code was
authored in had no JDK, no Android SDK and no access to a package mirror, so no compile, no test run and
no APK assembly was performed here. Everything below is the procedure that turns these sources into an
installable artefact.

---

## Version policy

`app/build.gradle.kts` carries `versionCode = 1` / `versionName = "1.0.0"`; the database carries
`KHATAGO_DB_VERSION = 1` and `exportSchema = true`; the backup format carries `BackupDocument.CURRENT_VERSION = 1`.
These three numbers are independent and must stay that way:

| Number | Moves when | Migration needed? |
|---|---|---|
| `versionName` / `versionCode` | any shipped change (`versionCode` **only ever increases**) | — |
| `KHATAGO_DB_VERSION` | the Room schema changes | **yes**, a hand-written `Migration` in `KhataGoMigrations.all` (see [DATA.md](DATA.md#migrations)) |
| `BackupDocument.CURRENT_VERSION` | the backup file shape changes | a bump plus read support for `MIN_SUPPORTED_VERSION..CURRENT_VERSION`, or old files are refused *with an explanation* |

A release that bumps the schema version without a migration **fails loudly at first open**, by design:
`fallbackToDestructiveMigration()` is not called anywhere, and adding it is the single change most likely to
delete someone's ledger.

## Cutting a release

```bash
# 0. Everything below runs from a machine with JDK 17 + Android SDK (platform 35).

# 1. Make the wrapper real, then run the full local gate.
gradle wrapper --gradle-version 8.9
gradle spotlessApply                                   # formatting is enforced, so fix it locally
gradle :app:testDebugUnitTest :app:lintDebug
gradle :app:assembleDebug                                # install + smoke-test the debug build first
python3 tools/check_braces.py app/src && python3 tools/audit_imports.py app/src \
  && python3 tools/audit_symbols.py && python3 tools/audit_data_api.py

# 2. Work through the manual QA list in docs/TESTING.md §4. Do not skip it; it is short and it is
#    where a money app actually breaks (timezone, process death, Excel, a device with no notifications).

# 3. Release build. Signed only if signing is configured (next section), otherwise unsigned:
gradle :app:assembleRelease :app:bundleRelease

# 4. Commit the Room schema export if kapt regenerated it, so the next migration has a diff to review:
git add app/schemas && git status --short app/schemas

# 5. Tag and push. CI runs, and release.yml publishes a GitHub Release for a `v*` tag.
git commit -am "release: 1.0.1"
git tag -s v1.0.1 -m "KhataGo 1.0.1"
git push origin main --follow-tags
```

## Signing

Create a key **once**, keep it offline, and never put it in this repository:

```bash
keytool -genkeypair -v -keystore khatago-release.jks -alias khatago \
  -keyalg RSA -keysize 2048 -validity 10000
```

Two ways to hand it to the build, in priority order:

1. **Local**: `keystore.properties` in the repository root (git-ignored; template in
   `local.properties.example`'s neighbourhood):
   ```properties
   storeFile=/absolute/path/khatago-release.jks
   storePassword=…
   keyAlias=khatago
   keyPassword=…
   ```
2. **CI**: repository secrets, base64 so the file never touches a working tree:
   ```bash
   base64 -w0 khatago-release.jks > /tmp/ks.b64
   gh secret set KHATAGO_KEYSTORE_BASE64 < /tmp/ks.b64 && shred -u /tmp/ks.b64
   gh secret set KHATAGO_KEYSTORE_PASSWORD
   gh secret set KHATAGO_KEY_ALIAS
   gh secret set KHATAGO_KEY_PASSWORD
   ```

The build writes the decoded keystore into `build/keystore/` (removed by `clean`) and never into the source
tree. If you use Play App Signing, upload a separate *upload* key and keep the deployment key with Google.

**Losing the release key means losing the ability to update an installed app** (an APK signed with a new key
cannot replace the old one). Back it up in a password manager *and* on paper-backed-off media; for a free app
on Play, key loss is effectively app loss.

## Verifying an artefact is what it claims to be

```bash
BT=$ANDROID_HOME/build-tools/35.0.0        # any recent build-tools
sha256sum KhataGo-v1.0.1.apk                          # compare with the SHA256SUMS in the release
"$BT/apksigner" verify --print-certs KhataGo-v1.0.1.apk   # must show the project release certificate
"$BT/aapt" dump permissions KhataGo-v1.0.1.apk | grep -i permission
#   -> the only dangerous permission must be android.permission.POST_NOTIFICATIONS
adb install -r KhataGo-v1.0.1.apk
```

Then the honest test of the privacy claim: enable airplane mode and use the app. Nothing should change. If
any screen shows a spinner waiting on a network, that is a defect in this document's promise, not a
misconfiguration.

For R8: install the **release** build and walk the golden paths (add a credit, pay it, edit the payment,
export a CSV, restore a backup, lock and unlock). R8 problems are runtime `NoSuchMethodError`s, which no
unit test here can see; `app/proguard-rules.pro` keeps Room's generated implementations, `AppContainer`,
`ReminderWorker` and the serialised backup DTOs alive.

## Publishing

**Direct distribution** — the APK:
- attach `app-release.apk` (plus `SHA256SUMS`) to the GitHub Release; `release.yml` does this for a `v*` tag
  and states in the body whether the artefact is signed;
- say what a user must know: Android 8.0+, no account, no internet access needed, uninstalling deletes the
  ledger so take a backup first.

**Play Store** — the AAB (`bundleRelease` output):
1. No closed testing / no ads / no in-app purchases. Data safety form: **no data collected, no data shared**
   — which is true because the app has no `INTERNET` permission (the Play console asks you to justify any
   network use; there is none to justify).
2. Permissions declared: `POST_NOTIFICATIONS` only, justified as "reminders about due dates, computed
   locally".
3. `targetSdk 35` satisfies the current store requirement; `minSdk 26` covers Android 8.0+.
4. App bundle = the AAB, not the APK. Upload signing is Play's; keep your own key backup regardless.
5. Privacy policy field: link [docs/PRIVACY.md](PRIVACY.md) (or a page generated from it). A policy that
   says "we collect nothing" is acceptable to the store only if the manifest proves it — and here it does.

## When CI is red and you cannot read the log

GitHub serves Actions logs and artefacts from hosts that some networks block, so a red run can be
unreadable both in the browser *and* from a sandbox. The build job publishes its own report instead:

```bash
# 1. The ci-diagnostics branch (pushed only by a failing run) — plain git, never blocked:
git fetch origin ci-diagnostics
git show FETCH_HEAD:kapt-disabled.md   # every `e:` line from the five probes, then the failure summaries
git show FETCH_HEAD:kapt-disabled.txt  | less   # last 150 raw lines of each probe
git show FETCH_HEAD:build-errors.txt   | less   # the Gradle "What went wrong" block + tests.log tail
git show FETCH_HEAD:diagnostics.md     # annotations-style short summary from the build job
git show FETCH_HEAD:tests.log          # the whole test step, verbatim
```

Delete the branch when the build is green again: `git push origin :ci-diagnostics`.

#### Why the `diagnose-compile` job exists

`e: Could not load module <Error module>` from `kaptGenerateStubs*` is a known kapt behaviour, and it is
**not the error**. The compiler emits it when something in the compilation resolves to an *error
descriptor* — a reference to a type or member that does not exist — and in stub-generation mode kapt
replaces its own diagnostics with that single line. So the message names the *mechanism* (an unresolved
reference somewhere in ~90 files) and not the file, and re-running the same task with `--info` prints the
same line again. The fix is to make the same sources compile through a path that does report diagnostics.

The build itself never disables kapt — `compileDebugKotlin` runs after it, Room's processor runs, and the
APK needs both. The `diagnose-compile` job, which runs only `if: failure()`, re-compiles the same sources
five ways and publishes every log: `stubs-baseline` (the failing task, with `--stacktrace`, to prove where
the exception comes from), `kaptless-compile` and `kaptless-tests` (an init script that sets
`enabled = false` on every `kapt*` task, so the ordinary Kotlin compile — same sources, same classpath,
no stub mode — reports `file:line:col` for main and for the unit-test source set), `stubs-no-ic`
(incremental compilation off, to rule the IC state in or out) and `stubs-no-cc` (configuration cache off,
to rule out anything KGP computes lazily at execution time). Its output is a diagnosis, never a build, and
it is labelled as such in the job name.

## Enabling CI in a fresh clone

Workflows ship inert until Actions is switched on:

```bash
gh api -X PUT repos/:owner/:repo/actions/workflows/ci.yml/enable 2>/dev/null || \
  echo "Enable Actions in Settings → Actions, then push to main."
```

- `ci.yml` runs on every push to `main` and on every PR: hygiene grep (no keystores, no `INTERNET`, no
  networking SDK) → the four `tools/` static checks → `testDebugUnitTest` → `lintDebug` →
  `assembleDebug` + `assembleRelease` → `spotlessCheck`, uploading test/lint reports and both APKs.
- `release.yml` runs on `v*` tags: the same gates, then `assembleRelease :app:bundleRelease`, checksums,
  signature verification, and a GitHub Release. Absent signing secrets ⇒ **unsigned** artefacts plus a
  warning in the summary; it never fakes a signature.
- Neither workflow deploys anything or touches a store. `ci.yml` holds `contents: write` for exactly one
  purpose — publishing a red run's diagnostics to the `ci-diagnostics` branch, the only log channel
  reachable from networks that block GitHub's Actions hosts. It never writes to `main`; `release.yml`
  publishes only a Release for a `v*` tag.

## Hotfix branches, in one line

A fix on `main` must be cherry-picked onto the released line *and* re-run through the manual QA money list
(§"Cutting a release", step 2): a payment-guard fix that skips QA is how 1.0.2 becomes the release that
double-counts a down payment.
