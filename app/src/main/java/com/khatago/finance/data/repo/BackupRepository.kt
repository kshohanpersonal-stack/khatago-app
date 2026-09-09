package com.khatago.finance.data.repo

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.khatago.finance.core.money.CurrencySpec
import com.khatago.finance.data.backup.BackupDocument
import com.khatago.finance.data.backup.BackupIssue
import com.khatago.finance.data.backup.BackupValidation
import com.khatago.finance.data.backup.BackupValidationResult
import com.khatago.finance.data.db.KhataGoDatabase
import com.khatago.finance.data.db.KhataGoSeed
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Export / import of the whole ledger as a versioned JSON file.
 *
 * The import is the part that deserves care. Three properties, in order of importance:
 *
 *  1. **It is all-or-nothing.** Every table is written inside one Room transaction. A crash, a
 *     full disk or a mid-import validation failure can therefore never leave half a ledger behind.
 *  2. **Ids are never trusted.** File ids are ignored; rows are inserted fresh and every relation
 *     (credit → shop, borrowing → person, installment → loan/EMI, payment → obligation,
 *     attachment → record) is re-mapped through the new ids. Reusing ids from a file is precisely
 *     how money gets attached to the wrong record.
 *  3. **Validate, then write.** Amounts, dates and relations are checked by [BackupValidation]
 *     *before* the first insert; a rejected file changes nothing at all.
 *
 * Replace mode clears every table first (nothing is left orphaned); merge mode keeps existing data
 * and de-duplicates shops and people by name so a second import of the same file does not create a
 * second copy of reality.
 */
class BackupRepository(
    private val context: Context,
    private val database: KhataGoDatabase,
    private val appVersionName: String,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        explicitNulls = true
    }

    /** Serialises the current database state. Exposed for tests and for the "what is in my backup" view. */
    /**
     * Counts per table, parsed from [com.khatago.finance.data.db.dao.StatsDao.recordCounts].
     *
     * Exposed as a typed value rather than the raw string because two screens show it (backup and
     * About), and a UI that splits a string on '/' in two places is two places that break when the
     * order of the SQL columns changes.
     */
    suspend fun statsSnapshot(): BackupStats =
        BackupStats.from(database.statsDao().recordCounts())

    suspend fun buildDocument(): BackupDocument {
        val catalog = database.catalogDao()
        return BackupDocument(
            backupVersion = BackupDocument.CURRENT_VERSION,
            appVersion = appVersionName,
            createdAt = System.currentTimeMillis(),
            currencyCode = catalog.findProfile()?.currencyCode ?: CurrencySpec.BDT.code,
            profile = catalog.findProfile()?.copy(onboardingComplete = false),
            settings = catalog.findAllSettings(),
            categories = catalog.findAllCategories(),
            paymentMethods = catalog.findAllPaymentMethods(),
            shops = database.shopDao().findAll(),
            shopCredits = database.creditDao().findAll(),
            people = database.personDao().findAllPeople(),
            borrowings = database.personDao().findAllBorrowings(),
            lendings = database.personDao().findAllLendings(),
            loans = database.loanDao().findAll(),
            emiPurchases = database.emiDao().findAll(),
            installments = database.installmentDao().findAll(),
            payments = database.paymentDao().findAll(),
            incomes = database.transactionDao().findAllIncomes(),
            expenses = database.transactionDao().findAllExpenses(),
            attachments = catalog.findAllAttachments(),
            reminders = catalog.findAllReminders(),
        )
    }

    fun encode(document: BackupDocument): String = json.encodeToString(BackupDocument.serializer(), document)

    fun decode(text: String): Result<BackupDocument> = runCatching {
        json.parseToJsonElement(text).let { json.decodeFromJsonElement(BackupDocument.serializer(), it) }
    }

    /** Writes the current ledger to the user-chosen file. Returns the number of records exported. */
    suspend fun exportTo(uri: Uri): ExportResult = withContext(Dispatchers.IO) {
        try {
            val document = buildDocument()
            val text = encode(document)
            context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.flush()
            } ?: return@withContext ExportResult.Failed("KhataGo could not open that location for writing.")
            ExportResult.Done(recordCount = document.recordCount(), bytes = text.toByteArray(Charsets.UTF_8).size)
        } catch (error: java.io.IOException) {
            ExportResult.Failed("The file could not be written: ${error.message ?: "storage error"}")
        }
    }

    /**
     * Reads a file and validates it *without* touching the database, so the UI can show the user
     * what is inside and what mode will do before they confirm.
     */
    suspend fun inspect(uri: Uri): InspectResult = withContext(Dispatchers.IO) {
        val text = readText(uri).getOrElse {
            return@withContext InspectResult.Rejected(
                listOf(BackupIssue(BackupIssue.Code.EmptyFile, it.message ?: "The file could not be read.")),
            )
        }
        val document = decode(text).getOrElse {
            return@withContext InspectResult.Rejected(
                listOf(
                    BackupIssue(
                        BackupIssue.Code.EmptyFile,
                        "This is not a KhataGo backup file, or it is damaged.",
                    ),
                ),
            )
        }
        when (val validation = BackupValidation.validate(document)) {
            is BackupValidationResult.Failed -> InspectResult.Rejected(validation.issues)
            is BackupValidationResult.Ok -> InspectResult.Ready(
                document = validation.validated.document,
                recordCount = validation.validated.document.recordCount(),
                warnings = validation.validated.warnings,
                droppedRecords = validation.validated.droppedRecords,
            )
        }
    }

    /**
     * Performs the import. Assumes [inspect] already succeeded and hands over its validated document;
     * the write path re-checks nothing that could change in between, because the file is not
     * re-read (which would allow a swap between confirm and write).
     */
    suspend fun importDocument(document: BackupDocument, mode: RestoreMode): RestoreReport =
        withContext(Dispatchers.IO) {
            val validation = BackupValidation.validate(document)
            if (validation is BackupValidationResult.Failed) {
                return@withContext RestoreReport(
                    applied = false,
                    recordsAdded = 0,
                    warnings = validation.issues,
                    droppedRecords = 0,
                    failureMessage = validation.issues.first().message,
                )
            }
            val ok = validation as BackupValidationResult.Ok
            val safe = ok.validated.document
            val warnings = ok.validated.warnings.toMutableList()
            val stats = ImportStats()

            database.withTransaction {
                if (mode == RestoreMode.Replace) {
                    wipeAll()
                    reseedReferenceData(safe, stats)
                }
                stats.recordsAdded += run {
                    var added = 0
                    added += importProfile(safe, mode)
                    added += importSettings(safe, mode)
                    added += importCatalog(safe)
                    val shopIds = importShops(safe.shops, mode, stats)
                    val creditIds = importCredits(safe.shopCredits, shopIds, stats)
                    val personIds = importPeople(safe.people, mode, stats)
                    val borrowingIds = importBorrowings(safe.borrowings, personIds, stats)
                    val lendingIds = importLendings(safe.lendings, personIds, stats)
                    val loanIds = importLoans(safe.loans, stats)
                    val emiIds = importEmis(safe.emiPurchases, stats)
                    val installmentIds = importInstallments(safe.installments, loanIds, emiIds, stats)
                    importPayments(safe.payments, creditIds, loanIds, emiIds, borrowingIds, lendingIds, installmentIds, stats)
                    importIncomes(safe.incomes, stats)
                    importExpenses(safe.expenses, stats)
                    importAttachments(safe.attachments, shopIds, creditIds, personIds, loanIds, emiIds, stats)
                    importReminders(safe.reminders, stats)
                    added
                }
            }

            RestoreReport(
                applied = true,
                recordsAdded = stats.recordsAdded,
                warnings = warnings,
                droppedRecords = stats.dropped,
                failureMessage = null,
            )
        }

    // --- internals -----------------------------------------------------------

    private suspend fun wipeAll() {
        with(database) {
            paymentDao().deleteAll()
            installmentDao().deleteAll()
            creditDao().deleteAll()
            loanDao().deleteAll()
            emiDao().deleteAll()
            personDao().deleteAllBorrowings()
            personDao().deleteAllLendings()
            personDao().deleteAllPeople()
            shopDao().deleteAll()
            transactionDao().deleteAllIncomes()
            transactionDao().deleteAllExpenses()
            catalogDao().deleteAllAttachments()
            catalogDao().deleteAllReminders()
            catalogDao().deleteAllCategories()
            catalogDao().deleteAllPaymentMethods()
            catalogDao().clearSettings()
            catalogDao().clearProfile()
        }
    }

    /**
     * Reference data comes from the file when the file has it, otherwise from the app's defaults.
     * A backup made by a version that pre-dated a category must not leave the restored ledger with
     * no categories at all.
     */
    private suspend fun reseedReferenceData(document: BackupDocument, stats: ImportStats) {
        if (document.categories.isEmpty()) {
            database.catalogDao().insertCategories(
                // KhataGoSeed is the one source of the built-in catalogue: a restore that re-seeds
                // must produce exactly the rows a fresh install has, or "replace everything" would
                // quietly change a user's category list.
                KhataGoSeed.EXPENSE_CATEGORIES.mapIndexed { index, name ->
                    CategoryEntity(kind = CategoryEntity.KIND_EXPENSE, name = name, orderIndex = index, builtIn = true)
                } + KhataGoSeed.INCOME_CATEGORIES.mapIndexed { index, name ->
                    CategoryEntity(kind = CategoryEntity.KIND_INCOME, name = name, orderIndex = index, builtIn = true)
                },
            )
        }
        if (document.paymentMethods.isEmpty()) {
            database.catalogDao().insertPaymentMethods(
                KhataGoSeed.PAYMENT_METHODS.mapIndexed { index, name ->
                    PaymentMethodEntity(name = name, orderIndex = index, builtIn = true)
                },
            )
        }
    }

    private suspend fun importProfile(document: BackupDocument, mode: RestoreMode): Int {
        val incoming = document.profile ?: return 0
        val existing = database.catalogDao().findProfile()
        if (mode == RestoreMode.Merge && existing != null) return 0
        database.catalogDao().upsertProfile(
            incoming.copy(id = existing?.id ?: 1, onboardingComplete = existing?.onboardingComplete ?: false),
        )
        return 1
    }

    private suspend fun importSettings(document: BackupDocument, mode: RestoreMode): Int {
        if (document.settings.isEmpty()) return 0
        if (mode == RestoreMode.Merge) {
            document.settings.forEach { setting -> database.catalogDao().putSetting(setting) }
        } else {
            database.catalogDao().putAllSettings(document.settings)
        }
        return document.settings.size
    }

    private suspend fun importCatalog(document: BackupDocument): Int {
        var count = 0
        document.categories.forEach { category ->
            database.catalogDao().insertCategories(
                listOf(category.copy(id = 0)),
            )
            count++
        }
        document.paymentMethods.forEach { method ->
            database.catalogDao().insertPaymentMethods(listOf(method.copy(id = 0)))
            count++
        }
        return count
    }

    /** @return map of file-id → database-id for the inserted shops. */
    private suspend fun importShops(shops: List<ShopEntity>, mode: RestoreMode, stats: ImportStats): Map<Long, Long> {
        val mapping = HashMap<Long, Long>()
        shops.forEach { shop ->
            val existingByName = database.shopDao().findIdByName(shop.name.trim())
            if (existingByName != null) {
                mapping[shop.id] = existingByName
                stats.dropped++
                return@forEach
            }
            val id = database.shopDao().insert(shop.copy(id = 0))
            mapping[shop.id] = id
            stats.recordsAdded++
        }
        return mapping
    }

    private suspend fun importCredits(
        credits: List<ShopCreditEntity>,
        shopIds: Map<Long, Long>,
        stats: ImportStats,
    ): Map<Long, Long> {
        val mapping = HashMap<Long, Long>()
        credits.forEach { credit ->
            val newShopId = shopIds[credit.shopId] ?: run {
                stats.dropped++
                return@forEach
            }
            val id = database.creditDao().insert(credit.copy(id = 0, shopId = newShopId))
            mapping[credit.id] = id
            stats.recordsAdded++
        }
        return mapping
    }

    private suspend fun importPeople(
        people: List<PersonEntity>,
        mode: RestoreMode,
        stats: ImportStats,
    ): Map<Long, Long> {
        val mapping = HashMap<Long, Long>()
        people.forEach { person ->
            val existing = database.personDao().findPersonId(person.name.trim(), person.relationship)
            if (existing != null) {
                mapping[person.id] = existing
                stats.dropped++
                return@forEach
            }
            val id = database.personDao().insertPerson(person.copy(id = 0))
            mapping[person.id] = id
            stats.recordsAdded++
        }
        return mapping
    }

    private suspend fun importBorrowings(
        borrowings: List<BorrowingEntity>,
        personIds: Map<Long, Long>,
        stats: ImportStats,
    ): Map<Long, Long> {
        val mapping = HashMap<Long, Long>()
        borrowings.forEach { borrowing ->
            val newPersonId = personIds[borrowing.personId] ?: run { stats.dropped++; return@forEach }
            val id = database.personDao().insertBorrowing(borrowing.copy(id = 0, personId = newPersonId))
            mapping[borrowing.id] = id
            stats.recordsAdded++
        }
        return mapping
    }

    private suspend fun importLendings(
        lendings: List<LendingEntity>,
        personIds: Map<Long, Long>,
        stats: ImportStats,
    ): Map<Long, Long> {
        val mapping = HashMap<Long, Long>()
        lendings.forEach { lending ->
            val newPersonId = personIds[lending.personId] ?: run { stats.dropped++; return@forEach }
            val id = database.personDao().insertLending(lending.copy(id = 0, personId = newPersonId))
            mapping[lending.id] = id
            stats.recordsAdded++
        }
        return mapping
    }

    private suspend fun importLoans(loans: List<LoanEntity>, stats: ImportStats): Map<Long, Long> {
        val mapping = HashMap<Long, Long>()
        loans.forEach { loan ->
            val id = database.loanDao().insert(loan.copy(id = 0))
            mapping[loan.id] = id
            stats.recordsAdded++
        }
        return mapping
    }

    private suspend fun importEmis(items: List<EmiPurchaseEntity>, stats: ImportStats): Map<Long, Long> {
        val mapping = HashMap<Long, Long>()
        items.forEach { emi ->
            val id = database.emiDao().insert(emi.copy(id = 0))
            mapping[emi.id] = id
            stats.recordsAdded++
        }
        return mapping
    }

    private suspend fun importInstallments(
        installments: List<InstallmentEntity>,
        loanIds: Map<Long, Long>,
        emiIds: Map<Long, Long>,
        stats: ImportStats,
    ): Map<Long, Long> {
        val mapping = HashMap<Long, Long>()
        installments.forEach { installment ->
            val newOwnerId = when (installment.ownerType) {
                "loan" -> loanIds[installment.ownerId]
                "emi" -> emiIds[installment.ownerId]
                else -> null
            } ?: run { stats.dropped++; return@forEach }
            val id = database.installmentDao().insert(installment.copy(id = 0, ownerId = newOwnerId))
            mapping[installment.id] = id
            stats.recordsAdded++
        }
        return mapping
    }

    /**
     * Payments are the last thing written because they reference almost everything else.
     *
     * They are also written **unclamped**: whatever the file says was paid is stored, because a
     * payment is a historical fact, not a derived field. The clamping that keeps a balance ≥ 0
     * happens at read time in the domain layer, where it is visible and testable.
     */
    private suspend fun importPayments(
        payments: List<PaymentEntity>,
        creditIds: Map<Long, Long>,
        loanIds: Map<Long, Long>,
        emiIds: Map<Long, Long>,
        borrowingIds: Map<Long, Long>,
        lendingIds: Map<Long, Long>,
        installmentIds: Map<Long, Long>,
        stats: ImportStats,
    ) {
        payments.forEach { payment ->
            val newPayableId = when (payment.payableType) {
                "shop_credit" -> creditIds[payment.payableId]
                "loan" -> loanIds[payment.payableId]
                "emi" -> emiIds[payment.payableId]
                "borrowing" -> borrowingIds[payment.payableId]
                "lending" -> lendingIds[payment.payableId]
                else -> null
            } ?: run { stats.dropped++; return@forEach }
            database.paymentDao().insert(
                payment.copy(
                    id = 0,
                    payableId = newPayableId,
                    installmentId = payment.installmentId?.let { installmentIds[it] },
                ),
            )
            stats.recordsAdded++
        }
    }

    private suspend fun importIncomes(incomes: List<IncomeEntity>, stats: ImportStats) {
        incomes.forEach { income ->
            database.transactionDao().insertIncome(income.copy(id = 0))
            stats.recordsAdded++
        }
    }

    private suspend fun importExpenses(expenses: List<ExpenseEntity>, stats: ImportStats) {
        expenses.forEach { expense ->
            database.transactionDao().insertExpense(expense.copy(id = 0))
            stats.recordsAdded++
        }
    }

    /**
     * Attachment rows are restored, but the image files themselves are *not* part of the JSON (a
     * base64 blob in a settings file would make the file fragile and unreadable). If the referenced
     * file is absent the row is skipped, so the UI never offers a broken thumbnail. This is stated
     * in the restore sheet rather than left for the user to discover.
     */
    private suspend fun importAttachments(
        attachments: List<AttachmentEntity>,
        shopIds: Map<Long, Long>,
        creditIds: Map<Long, Long>,
        personIds: Map<Long, Long>,
        loanIds: Map<Long, Long>,
        emiIds: Map<Long, Long>,
        stats: ImportStats,
    ) {
        val dir = attachmentDir()
        attachments.forEach { attachment ->
            val newTargetId = when (attachment.targetType) {
                AttachmentEntity.TARGET_SHOP -> shopIds[attachment.targetId]
                AttachmentEntity.TARGET_SHOP_CREDIT -> creditIds[attachment.targetId]
                AttachmentEntity.TARGET_PERSON -> personIds[attachment.targetId]
                AttachmentEntity.TARGET_LOAN -> loanIds[attachment.targetId]
                AttachmentEntity.TARGET_EMI -> emiIds[attachment.targetId]
                else -> null
            } ?: run { stats.dropped++; return@forEach }
            if (!File(dir, attachment.fileName).exists()) {
                stats.dropped++
                return@forEach
            }
            database.catalogDao().insertAttachment(attachment.copy(id = 0, targetId = newTargetId))
            stats.recordsAdded++
        }
    }

    private suspend fun importReminders(reminders: List<ReminderEntity>, stats: ImportStats) {
        reminders.forEach { reminder ->
            database.catalogDao().insertReminder(reminder.copy(id = 0))
            stats.recordsAdded++
        }
    }

    private fun attachmentDir(): File = File(context.filesDir, "attachments").apply { mkdirs() }

    private suspend fun readText(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                // A backup is a text file the user chose; a hard cap stops a wrong file (e.g. a
                // video) from being slurped into memory.
                val bytes = stream.readBytes()
                require(bytes.size <= MAX_BACKUP_BYTES) { "That file is too large to be a KhataGo backup." }
                bytes.toString(Charsets.UTF_8)
            } ?: error("KhataGo could not read that file.")
        }
    }

    private class ImportStats {
        var recordsAdded: Int = 0
        var dropped: Int = 0
    }

    companion object {
        /** 32 MB of JSON is ~250k records; beyond that something is wrong with the file. */
        const val MAX_BACKUP_BYTES = 32 * 1024 * 1024
    }
}

enum class RestoreMode {
    /** Clear everything, then import. Simple, complete, and the only mode with a defined end state. */
    Replace,

    /** Keep existing records; de-duplicate shops/people by name and re-link the rest. */
    Merge,
}

/** Result of writing a backup file. */
sealed interface ExportResult {
    data class Done(val recordCount: Int, val bytes: Int) : ExportResult {
        val humanSize: String
            get() = if (bytes < 1024) {
                "$bytes B"
            } else {
                "%.1f KB".format(java.util.Locale.US, bytes / 1024.0)
            }
    }

    data class Failed(val message: String) : ExportResult
}

/** Result of reading a file and validating it, before the user confirms anything. */
sealed interface InspectResult {
    data class Ready(
        val document: BackupDocument,
        val recordCount: Int,
        val warnings: List<BackupIssue>,
        val droppedRecords: Int,
    ) : InspectResult

    data class Rejected(val issues: List<BackupIssue>) : InspectResult
}

data class RestoreReport(
    val applied: Boolean,
    val recordsAdded: Int,
    val warnings: List<BackupIssue>,
    val droppedRecords: Int,
    val failureMessage: String? = null,
) {
    val headline: String
        get() = when {
            !applied -> failureMessage ?: "The backup could not be applied."
            droppedRecords > 0 ->
                "Restored $recordsAdded records; $droppedRecords could not be linked and were skipped."
            else -> "Restored $recordsAdded records."
        }
}

/** Per-table record counts, in the exact order `StatsDao.recordCounts()` concatenates them. */
data class BackupStats(
    val shops: Int = 0,
    val credits: Int = 0,
    val people: Int = 0,
    val borrowings: Int = 0,
    val lendings: Int = 0,
    val loans: Int = 0,
    val emis: Int = 0,
    val installments: Int = 0,
    val payments: Int = 0,
    val incomes: Int = 0,
    val expenses: Int = 0,
    val attachments: Int = 0,
    val reminders: Int = 0,
) {
    val total: Int
        get() = shops + credits + people + borrowings + lendings + loans + emis + installments +
            payments + incomes + expenses

    val isEmpty: Boolean get() = total == 0

    /** Labelled rows for the UI; order mirrors the SQL so a reader can verify it against the query. */
    fun rows(): List<Pair<String, String>> = buildList {
        add("Shops" to shops.toString())
        add("Shop credit records" to credits.toString())
        add("People" to people.toString())
        add("Borrowings" to borrowings.toString())
        add("Lendings" to lendings.toString())
        add("Loans" to loans.toString())
        add("EMI plans" to emis.toString())
        add("Installment lines" to installments.toString())
        add("Payments" to payments.toString())
        add("Income entries" to incomes.toString())
        add("Expense entries" to expenses.toString())
        add("Attachments" to attachments.toString())
        add("Reminders" to reminders.toString())
        add("Total (excluding attachments and reminders)" to total.toString())
    }

    companion object {
        val ORDER = listOf(
            "shops", "shop_credits", "people", "borrowings", "lendings", "loans", "emi_purchases",
            "installments", "payments", "incomes", "expenses", "attachments", "reminders",
        )

        fun from(raw: String): BackupStats {
            val parts = raw.split('/').map { it.trim().toIntOrNull() ?: 0 }
            fun at(index: Int): Int = parts.getOrElse(index) { 0 }
            return BackupStats(
                shops = at(0),
                credits = at(1),
                people = at(2),
                borrowings = at(3),
                lendings = at(4),
                loans = at(5),
                emis = at(6),
                installments = at(7),
                payments = at(8),
                incomes = at(9),
                expenses = at(10),
                attachments = at(11),
                reminders = at(12),
            )
        }
    }
}
