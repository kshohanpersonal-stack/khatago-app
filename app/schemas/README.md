Room schema export directory (see `room.schemaLocation` in `app/build.gradle.kts`).

Nothing here is written by hand, and nothing is checked in until the first real build runs
`kaptGenerateStubsDebugKotlin`/`assembleDebug`, which makes Room emit
`com.khatago.finance.data.db.KhataGoDatabase/1.json`.

That JSON is the review artifact for a schema change: the diff between the committed file and the newly
generated one *is* the migration review, and `KhataGoMigrations` must reproduce exactly that diff. If the
directory stays empty, nobody ran a build yet — which is a release blocker, not a formatting issue
(see `docs/RELEASE.md`).
