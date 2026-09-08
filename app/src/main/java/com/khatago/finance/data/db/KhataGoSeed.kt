package com.khatago.finance.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import com.khatago.finance.data.db.entity.AppSettingEntity
import com.khatago.finance.data.db.entity.CategoryEntity

/**
 * Reference data inserted once, when the database file is first created.
 *
 * This is deliberately *catalogue only*: categories, payment methods, and the default reminder
 * settings. No demo ledger, no profile row. Two consequences follow from that:
 *  - a fresh install shows empty totals rather than numbers the user never entered;
 *  - the onboarding gate keys off `profile.onboardingComplete`, and since no profile is seeded here,
 *    first launch always lands in onboarding. A seeded profile would silently skip it.
 *
 * Raw SQL rather than DAOs: this runs inside Room's `RoomDatabase.Callback`, where the Room session is
 * not usable yet. Everything here is idempotent per insert (`INSERT OR IGNORE` on UNIQUE columns), so
 * re-running after a wipe-and-reseed cannot duplicate a category.
 */
internal object KhataGoSeed {

    /**
     * The built-in catalogue, in insertion order. Public because a restore that imported a file with
     * no categories must re-seed *exactly* these names — a second copy of the list would drift.
     */
    val INCOME_CATEGORIES = listOf(
        "Salary", "Business", "Freelance", "Bonus", "Commission", "Gift", "Investment", "Other",
    )

    val EXPENSE_CATEGORIES = listOf(
        "Food", "Transport", "Shopping", "Bills", "Rent", "Utilities", "Education", "Health",
        "Family", "Entertainment", "Business", "Loan Payment", "EMI Payment", "Other",
    )

    // Cash first: it is the overwhelmingly common path in a khata workflow, and the payment sheet
    // prefills the first enabled row.
    val PAYMENT_METHODS = listOf("Cash", "Bank transfer", "Mobile banking", "Card", "Other")

    fun onCreate(db: SupportSQLiteDatabase) {
        seedCategories(db)
        seedPaymentMethods(db)
        seedSettings(db)
    }

    private fun seedCategories(db: SupportSQLiteDatabase) {
        var order = 0
        INCOME_CATEGORIES.forEach { name ->
            insertCategory(
                db,
                CategoryEntity(kind = CategoryEntity.KIND_INCOME, name = name, orderIndex = order++),
            )
        }

        order = 0
        EXPENSE_CATEGORIES.forEach { name ->
            insertCategory(
                db,
                CategoryEntity(kind = CategoryEntity.KIND_EXPENSE, name = name, orderIndex = order++),
            )
        }
    }

    private fun insertCategory(db: SupportSQLiteDatabase, entity: CategoryEntity) {
        db.execSQL(
            """
            INSERT OR IGNORE INTO categories(kind, name, orderIndex, builtIn, enabled)
            VALUES(?, ?, ?, 1, 1)
            """.trimIndent(),
            arrayOf(entity.kind, entity.name, entity.orderIndex),
        )
    }

    private fun seedPaymentMethods(db: SupportSQLiteDatabase) {
        PAYMENT_METHODS.forEachIndexed { index, name ->
            db.execSQL(
                """
                INSERT OR IGNORE INTO payment_methods(name, orderIndex, builtIn, enabled)
                VALUES(?, ?, 1, 1)
                """.trimIndent(),
                arrayOf(name, index),
            )
        }
    }

    private fun seedSettings(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        // "1"/"0" (not "true"/"false") because these are also read from raw SQL by the reminder worker.
        // CatalogRepository accepts both spellings so a hand-edited value cannot flip a switch.
        mapOf(
            AppSettingEntity.NOTIFICATIONS_ENABLED to "1",
            AppSettingEntity.REMINDER_HOUR to "9",
            AppSettingEntity.REMIND_DUE_TODAY to "1",
            AppSettingEntity.REMIND_DUE_TOMORROW to "1",
            AppSettingEntity.REMIND_OVERDUE to "0",
            AppSettingEntity.DASHBOARD_WIDGETS_HIDDEN to "0",
            AppSettingEntity.SAMPLE_DATA_LOADED to "0",
        ).forEach { (key, value) ->
            db.execSQL(
                """
                INSERT OR IGNORE INTO app_settings(key, value, updatedAt) VALUES(?, ?, ?)
                """.trimIndent(),
                arrayOf(key, value, now),
            )
        }
    }
}
