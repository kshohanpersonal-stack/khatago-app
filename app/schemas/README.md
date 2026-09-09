Room schema export directory (see `room.schemaLocation` in `app/build.gradle.kts`, and
`exportSchema = true` on the `@Database`).

Nothing here is written by hand. `com.khatago.finance.data.db.KhataGoDatabase/<version>.json` is emitted by
Room while kapt runs, and the committed copy is that generated file byte for byte — first from a real build,
in CI if nowhere else (ci.yml publishes the generated JSON to the `room-schema-baseline` branch on any run
where it is not yet tracked, and says "nothing to carry" once it is).

That JSON is the review artifact for a schema change: the diff between the committed file and the newly
generated one *is* the migration review, and `KhataGoMigrations` must reproduce exactly that diff. An empty
directory here means no build has produced a baseline yet — which is a release blocker, not a formality.
