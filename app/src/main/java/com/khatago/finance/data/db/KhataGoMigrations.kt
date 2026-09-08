package com.khatago.finance.data.db

import androidx.room.migration.Migration

/**
 * Schema migrations.
 *
 * There are none in v1.0.0 (the shipped schema *is* version 1), and this file exists so that the
 * migration path is a first-class, reviewable part of the codebase rather than something improvised
 * when a column is added. That matters here because KhataGo stores money: an unreviewed
 * `fallbackToDestructiveMigration()` would silently delete a user's ledger the first time a table
 * changes, and the app ships with no cloud copy to restore from.
 *
 * Rules for adding one:
 *  1. Bump `KHATAGO_DB_VERSION` and add the matching `app/schemas/…/N.json` (Room's kapt plugin writes
 *     it; commit it — androidTest reads schemas off the device to verify migrations).
 *  2. Write the migration as an additive `ALTER TABLE` where possible. Never rename a column with a
 *     destructive copy when an alias would do: a copy that fails halfway leaves half a ledger.
 *  3. Never store a derived total in the new column. Balances are computed from payments so that an
 *     old row and a new row cannot disagree; a migrated `paidTotalMinor` would be exactly the kind of
 *     denormalised number that rots.
 *  4. Cover it with a test in `app/src/test/java/.../data/KhataGoMigrationTest.kt` that opens the
 *     previous schema, migrates, and re-derives the totals — and add a backup round-trip case, because
 *     the JSON backup format version (`BackupDocument.CURRENT_VERSION`) is a *separate* number that
 *     must not silently move with the schema version.
 *  5. Ship the migration before the code that reads the new column, never in the same release where
 *     the column is invented and consumed — a user upgrading two versions at once must land on a
 *     complete path.
 */
object KhataGoMigrations {

    /**
     * Ordered list handed to `Room.databaseBuilder(...).addMigrations(...)`.
     *
     * Empty by design at v1. Room then never falls back to destructive behaviour: with no migration
     * registered and no `fallbackToDestructiveMigration()` call, an un-migrated version throws at
     * open — which is the loud, data-preserving failure we want, compared to an empty database.
     */
    val all: List<Migration> = emptyList()

    /**
     * Example of the shape every migration below should take, kept as a comment so the pattern is not
     * lost between releases (this is *not* active code; v1 has no migrations):
     *
     * ```
     * // val v1to2 = object : Migration(1, 2) {
     * //     override fun migrate(db: SupportSQLiteDatabase) {
     * //         db.execSQL("ALTER TABLE payments ADD COLUMN reconciled INTEGER NOT NULL DEFAULT 0")
     * //     }
     * // }
     * ```
     */
}
