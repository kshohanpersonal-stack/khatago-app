package com.khatago.finance.data.backup

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
import com.khatago.finance.data.db.entity.ShopCreditEntity
import com.khatago.finance.data.db.entity.ShopEntity
import kotlinx.serialization.Transient
import kotlinx.serialization.Serializable

/**
 * KhataGo's backup file (JSON) — version 1.
 *
 * The format is the entity graph itself, which keeps a single definition of "what a record is"
 * between the database and the file. Four rules make that safe:
 *
 *  - **Primary keys are `@Transient`:** they are written to the file for readability but **ignored on
 *    import**, where ids are freshly assigned and every foreign key re-mapped. Importing a file that
 *    dictated ids into a database that already has rows is how a payment ends up attached to an
 *    unrelated obligation.
 *  - **Amounts are minor-unit `Long`s**, exactly as stored. An export → edit → import round-trip
 *    therefore cannot introduce a float, a rounding, or a locale-dependent decimal separator.
 *  - **`backupVersion` is checked before anything is touched.** A file from the future is refused
 *    with an explanation instead of half-applied.
 *  - **Reference data (profile, settings, categories, payment methods) is included** so restoring a
 *    ledger never leaves it pointing at categories that do not exist.
 *
 * A backup is plain text on the user's device, written only where the user chooses in the system
 * file picker, and never uploaded. The UI says so in as many words: the file is not encrypted.
 */
@Serializable
data class BackupDocument(
    val backupVersion: Int = CURRENT_VERSION,
    val appVersion: String = "1.0.0",
    val createdAt: Long = 0L,
    val currencyCode: String = "BDT",
    val profile: ProfileEntity? = null,
    val settings: List<AppSettingEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val paymentMethods: List<PaymentMethodEntity> = emptyList(),
    val shops: List<ShopEntity> = emptyList(),
    val shopCredits: List<ShopCreditEntity> = emptyList(),
    val people: List<PersonEntity> = emptyList(),
    val borrowings: List<BorrowingEntity> = emptyList(),
    val lendings: List<LendingEntity> = emptyList(),
    val loans: List<LoanEntity> = emptyList(),
    val emiPurchases: List<EmiPurchaseEntity> = emptyList(),
    val installments: List<InstallmentEntity> = emptyList(),
    val payments: List<PaymentEntity> = emptyList(),
    val incomes: List<IncomeEntity> = emptyList(),
    val expenses: List<ExpenseEntity> = emptyList(),
    val attachments: List<AttachmentEntity> = emptyList(),
    val reminders: List<ReminderEntity> = emptyList(),
) {
    companion object {
        /** v1 — the initial format. */
        const val CURRENT_VERSION: Int = 1
        const val MIN_SUPPORTED_VERSION: Int = 1

        /** File name pattern used by the create-document picker. */
        fun fileName(epochMilli: Long): String = "khatago-backup-$epochMilli.json"
    }

    fun recordCount(): Int =
        shops.size + shopCredits.size + people.size + borrowings.size + lendings.size +
            loans.size + emiPurchases.size + installments.size + payments.size +
            incomes.size + expenses.size

    /** Metadata block shown in the restore confirmation, so the user sees *what* they are importing. */
    fun summary(): String = buildString {
        append("KhataGo backup v$backupVersion · ")
        append("${recordCount()} records · ")
        append("currency $currencyCode")
        if (createdAt > 0L) append(" · created ${BackupTime.format(createdAt)}")
    }
}

/**
 * The write order for a restored graph: parents before children. Not cosmetic — this is what lets
 * the import run inside one transaction with foreign-key enforcement on, rather than requiring
 * deferred constraints that a crash could leave violated.
 */
data class RestoredGraph(
    val profile: ProfileEntity?,
    val settings: List<AppSettingEntity>,
    val categories: List<CategoryEntity>,
    val paymentMethods: List<PaymentMethodEntity>,
    val shops: List<ShopEntity>,
    val shopCredits: List<ShopCreditEntity>,
    val people: List<PersonEntity>,
    val borrowings: List<BorrowingEntity>,
    val lendings: List<LendingEntity>,
    val loans: List<LoanEntity>,
    val emiPurchases: List<EmiPurchaseEntity>,
    val installments: List<InstallmentEntity>,
    val payments: List<PaymentEntity>,
    val incomes: List<IncomeEntity>,
    val expenses: List<ExpenseEntity>,
    val attachments: List<AttachmentEntity>,
    val reminders: List<ReminderEntity>,
) {
    val totalRecords: Int
        get() = shops.size + shopCredits.size + people.size + borrowings.size + lendings.size +
            loans.size + emiPurchases.size + installments.size + payments.size +
            incomes.size + expenses.size
}

/** Tiny date helper so the backup header stays dependency-free (and JVM-testable). */
object BackupTime {
    fun format(epochMilli: Long): String {
        val date = java.time.Instant.ofEpochMilli(epochMilli)
            .atZone(java.time.ZoneId.systemDefault())
        return java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", java.util.Locale.ENGLISH)
            .format(date)
    }
}


/**
 * `@Transient` on every primary key is what makes an import safe (ids are reassigned and foreign
 * keys re-mapped by [com.khatago.finance.data.repo.BackupRepository]). The marker lives on the
 * entities themselves — see `data/db/entity/Entities.kt` — so the backup format and the table
 * definition can never drift into two different shapes.
 */
object BackupIdPolicy {
    /** Field name that is written for humans but ignored on import. */
    const val ID_FIELD = "id"
}
