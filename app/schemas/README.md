Room schema export directory (see `room.schemaLocation` in `app/build.gradle.kts`, and
`exportSchema = true` on the `@Database`).

Nothing here is written by hand. `com.khatago.finance.data.db.KhataGoDatabase/<version>.json` is emitted by
Room while kapt runs, and the committed copy is that generated file byte for byte. Version 1 reached the repo
this way rather than from a laptop: it is Room 2.6.1's output from CI run 34308658126, which is how a repo
maintained in a sandbox with no JDK obtains a build artifact at all.

Commit it byte for byte and never "tidy" it: Room's Gson output ends without a trailing newline, so adding
one (or reformatting) makes every later `--rerun` read as drift for a reason that has nothing to do with the
schema.

Regenerate it with `gradle :app:kaptDebugKotlin --rerun`. The `--rerun` is not decoration: the shared Gradle
build cache makes kapt a cache hit on a warm runner, and a cached task does not re-run Room, so a plain
`assembleDebug` leaves this directory untouched and looks like a config that never worked. (That exact
confusion is why ci.yml's build job force-runs the task, diffs it against the committed file, and publishes
`CARRIER.txt` naming the verdict — `up-to-date`, `drift` with the diff, `baseline-missing`, or
`kapt-emitted-nothing` — to the `room-schema-baseline` branch. Drift is a warning, not a build failure.)

That JSON is the review artifact for a schema change: the diff between the committed file and the newly
generated one *is* the migration review, and `KhataGoMigrations` must reproduce exactly that diff. A missing
file for the current `KHATAGO_DB_VERSION` is a release blocker, not a formality.
