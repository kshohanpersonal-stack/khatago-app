package com.khatago.finance.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.khatago.finance.data.db.dao.CatalogDao
import com.khatago.finance.data.db.dao.CreditDao
import com.khatago.finance.data.db.dao.DueDao
import com.khatago.finance.data.db.dao.EmiDao
import com.khatago.finance.data.db.dao.InstallmentDao
import com.khatago.finance.data.db.dao.LoanDao
import com.khatago.finance.data.db.dao.PaymentDao
import com.khatago.finance.data.db.dao.PersonDao
import com.khatago.finance.data.db.dao.SearchDao
import com.khatago.finance.data.db.dao.ShopDao
import com.khatago.finance.data.db.dao.StatsDao
import com.khatago.finance.data.db.dao.TransactionDao
import com.khatago.finance.data.db.entity.AppSettingEntity
import com.khatago.finance.data.db.entity.AttachmentEntity
import com.khatago.finance.data.db.entity.BorrowingEntity
import com.khatago.finance.data.db.entity.CategoryEntity
import com.khatago.finance.data.db.entity.EmiPurchaseEntity
import com.khatago.finance.data.db.entity.ExpenseEntity
import com.khatago.finance.data.db.entity.IncomeEntity
import com.khatago.finance.data.db.entity.InstallmentEntity
import com.khatago.finance.data.db.entity.LendingEntity
import com.khatago.finance.data.db.entity.LoanEntity
import com.khatago.finance.data.db.entity.PaymentEntity
import com.khatago.finance.data.db.entity.PaymentMethodEntity
import com.khatago.finance.data.db.entity.PersonEntity
import com.khatago.finance.data.db.entity.ProfileEntity
import com.khatago.finance.data.db.entity.ReminderEntity
import com.khatago.finance.data.db.entity.ReminderLogEntity
import com.khatago.finance.data.db.entity.ShopCreditEntity
import com.khatago.finance.data.db.entity.ShopEntity

/**
 * Database version. `1` is the shipped v1.0.0 schema; see [KhataGoMigrations] for the rules when this
 * number moves. It is a top-level constant because the `@Database` annotation needs a compile-time
 * constant, and the About screen prints the same value rather than a hardcoded string.
 */
const val KHATAGO_DB_VERSION: Int = 1

/** The file name is stable forever: renaming it would orphan every existing user's ledger. */
const val KHATAGO_DB_NAME: String = "khatago.db"

/**
 * The one Room database. All tables are in a single database on purpose: KhataGo's core promise is
 * that a payment, the obligation it settles, and the totals built from both are always consistent —
 * and the only way to *guarantee* that across modules is to let SQLite commit them in one
 * transaction, which cross-database writes cannot do.
 */
@Database(
    entities = [
        ProfileEntity::class,
        ShopEntity::class,
        ShopCreditEntity::class,
        PersonEntity::class,
        BorrowingEntity::class,
        LendingEntity::class,
        LoanEntity::class,
        EmiPurchaseEntity::class,
        InstallmentEntity::class,
        PaymentEntity::class,
        PaymentMethodEntity::class,
        CategoryEntity::class,
        IncomeEntity::class,
        ExpenseEntity::class,
        AttachmentEntity::class,
        ReminderEntity::class,
        ReminderLogEntity::class,
        AppSettingEntity::class,
    ],
    version = KHATAGO_DB_VERSION,
    exportSchema = true,
)
abstract class KhataGoDatabase : RoomDatabase() {

    abstract fun shopDao(): ShopDao
    abstract fun creditDao(): CreditDao
    abstract fun personDao(): PersonDao
    abstract fun loanDao(): LoanDao
    abstract fun emiDao(): EmiDao
    abstract fun installmentDao(): InstallmentDao
    abstract fun paymentDao(): PaymentDao
    abstract fun dueDao(): DueDao
    abstract fun transactionDao(): TransactionDao
    abstract fun statsDao(): StatsDao
    abstract fun searchDao(): SearchDao
    abstract fun catalogDao(): CatalogDao

    /**
     * Reference data only. No transaction, no demo ledger: the first launch must be genuinely
     * empty so the dashboard never shows a number the user did not enter.
     */
    private class SeedCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) = KhataGoSeed.onCreate(db)
    }

    companion object {

        fun create(context: Context): KhataGoDatabase =
            Room.databaseBuilder(context.applicationContext, KhataGoDatabase::class.java, KHATAGO_DB_NAME)
                .addCallback(SeedCallback())
                .addMigrations(*KhataGoMigrations.all.toTypedArray())
                // WAL: KhataGo writes small ledger transactions while reading aggregate flows, and
                // WAL is what stops a dashboard read from blocking a payment write (and vice versa).
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()

        /** In-memory instance for Robolectric DAO tests. */
        fun createInMemory(context: Context): KhataGoDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, KhataGoDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        /**
         * Nukes every row and re-seeds reference data, then closes and deletes the database file so
         * a genuinely fresh start also drops the WAL side-files. Used by "Delete all data", which the
         * UI gates behind a typed-confirmation dialog.
         *
         * Attachments on disk are removed by [com.khatago.finance.data.repo.DataRepository] in the
         * same operation: deleting the ledger but leaving receipt images behind would be a privacy
         * bug, not a cleanup.
         */
        suspend fun wipe(context: Context, database: KhataGoDatabase) {
            database.clearAllTables()
            KhataGoSeed.onCreate(database.openHelper.writableDatabase)
        }
    }
}
