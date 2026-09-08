package com.khatago.finance.data.backup

import com.khatago.finance.data.db.entity.PaymentEntity

/**
 * Pure validation of an untrusted backup document, before a single row is written.
 *
 * This lives apart from the repository on purpose: the rules here are the difference between
 * "restore repaired my ledger" and "restore quietly corrupted it", and they must be testable with a
 * plain JVM unit test rather than only through an emulator. There are no Android imports in this
 * file.
 *
 * Policy, and the reasoning behind it:
 *  - **Hard errors abort the restore.** Wrong money values, impossible dates, an unsupported format
 *    version: applying any of these is worse than refusing, because the user then trusts numbers
 *    that were never theirs.
 *  - **A broken relationship drops that one record and warns.** A credit whose shop is missing has no
 *    honest place in the UI; dropping it *and saying so* beats attaching it to whatever id happens to
 *    exist.
 *  - **A paid total that exceeds its obligation is clamped, never repaired by deleting payments.**
 *    The payment rows stay — removing history to make a total add up would destroy what the user
 *    asked us to restore — but the balance is clamped and the affected record is reported.
 */

data class BackupIssue(
    val code: Code,
    val message: String,
) {
    enum class Code {
        UnsupportedVersion,
        FutureVersion,
        EmptyFile,
        NegativeAmount,
        ImpossibleAmount,
        OutOfRangeDate,
        InvalidPaymentLink,
        InvalidOwnerLink,
        DuplicateShop,
        DuplicatePerson,
        PaidExceedsObligation,
        MissingProfile,
        TooManyRecords,
    }
}

/** Everything the validator found; [document] is normalised and safe to write. */
data class ValidatedBackup(
    val document: BackupDocument,
    val warnings: List<BackupIssue>,
    val droppedRecords: Int,
) {
    val hasWarnings: Boolean get() = warnings.isNotEmpty() || droppedRecords > 0
}

sealed interface BackupValidationResult {
    data class Ok(val validated: ValidatedBackup) : BackupValidationResult
    data class Failed(val issues: List<BackupIssue>) : BackupValidationResult
}

object BackupValidation {

    /** Ceilings for a personal ledger: anything beyond these is a corrupt or hostile file. */
    const val MAX_RECORDS: Int = 200_000
    const val MAX_AMOUNT_MINOR: Long = 1_000_000_000_000_000L

    /**
     * @param checkRelations false when the caller has already re-mapped ids (merge-import path),
     *   because at that point the file's ids no longer refer to anything in the document.
     */
    fun validate(document: BackupDocument, checkRelations: Boolean = true): BackupValidationResult {
        val errors = mutableListOf<BackupIssue>()
        val warnings = mutableListOf<BackupIssue>()

        if (document.backupVersion > BackupDocument.CURRENT_VERSION) {
            return BackupValidationResult.Failed(
                listOf(
                    BackupIssue(
                        BackupIssue.Code.FutureVersion,
                        "This backup was created by a newer version of KhataGo (format v" +
                            "${document.backupVersion}). Update the app and try again; importing it " +
                            "partially could lose records.",
                    ),
                ),
            )
        }
        if (document.backupVersion < BackupDocument.MIN_SUPPORTED_VERSION) {
            return BackupValidationResult.Failed(
                listOf(
                    BackupIssue(
                        BackupIssue.Code.UnsupportedVersion,
                        "This backup uses format v${document.backupVersion}, which this version of " +
                            "KhataGo no longer reads.",
                    ),
                ),
            )
        }
        if (document.recordCount() == 0) {
            return BackupValidationResult.Failed(
                listOf(BackupIssue(BackupIssue.Code.EmptyFile, "This backup contains no records.")),
            )
        }
        if (document.recordCount() > MAX_RECORDS) {
            return BackupValidationResult.Failed(
                listOf(
                    BackupIssue(
                        BackupIssue.Code.TooManyRecords,
                        "This backup claims ${document.recordCount()} records, more than KhataGo can " +
                            "safely import from one file.",
                    ),
                ),
            )
        }

        // --- money and dates --------------------------------------------------
        amounts(document).forEach { (where, value) ->
            when {
                value < 0L -> errors += BackupIssue(
                    BackupIssue.Code.NegativeAmount,
                    "$where is negative, which cannot be a valid amount.",
                )
                value > MAX_AMOUNT_MINOR -> errors += BackupIssue(
                    BackupIssue.Code.ImpossibleAmount,
                    "$where is larger than KhataGo can store.",
                )
            }
        }
        dates(document).forEach { (where, day) ->
            if (day != null && (day < MIN_EPOCH_DAY || day > MAX_EPOCH_DAY)) {
                errors += BackupIssue(
                    BackupIssue.Code.OutOfRangeDate,
                    "$where has a date outside the range KhataGo supports.",
                )
            }
        }

        // --- referential integrity -------------------------------------------
        val normalised: BackupDocument
        val dropped: Int
        if (checkRelations) {
            val shopIds = document.shops.map { it.id }.toSet()
            val personIds = document.people.map { it.id }.toSet()
            val loanIds = document.loans.map { it.id }.toSet()
            val emiIds = document.emiPurchases.map { it.id }.toSet()
            val installmentIds = document.installments.map { it.id }.toSet()

            val validCredits = document.shopCredits.filter { it.shopId in shopIds }
            val validBorrowings = document.borrowings.filter { it.personId in personIds }
            val validLendings = document.lendings.filter { it.personId in personIds }
            val validInstallments = document.installments.filter { installment ->
                (installment.ownerType == "loan" && installment.ownerId in loanIds) ||
                    (installment.ownerType == "emi" && installment.ownerId in emiIds)
            }
            val obligationIds: Map<String, Set<Long>> = mapOf(
                "shop_credit" to validCredits.map { it.id }.toSet(),
                "loan" to loanIds,
                "emi" to emiIds,
                "borrowing" to validBorrowings.map { it.id }.toSet(),
                "lending" to validLendings.map { it.id }.toSet(),
            )
            val validPayments = document.payments.filter { payment ->
                val ids = obligationIds[payment.payableType] ?: return@filter false
                payment.payableId in ids &&
                    (payment.installmentId == null || payment.installmentId in installmentIds)
            }

            dropped = (document.shopCredits.size - validCredits.size) +
                (document.borrowings.size - validBorrowings.size) +
                (document.lendings.size - validLendings.size) +
                (document.installments.size - validInstallments.size) +
                (document.payments.size - validPayments.size)

            if (validCredits.size != document.shopCredits.size) {
                warnings += BackupIssue(
                    BackupIssue.Code.InvalidOwnerLink,
                    "${document.shopCredits.size - validCredits.size} shop credit(s) refer to a shop " +
                        "missing from this file and will be skipped.",
                )
            }
            warnDropped(document.borrowings.size - validBorrowings.size, "borrowing", warnings)
            warnDropped(document.lendings.size - validLendings.size, "lending", warnings)
            warnDropped(document.installments.size - validInstallments.size, "installment", warnings)
            warnDropped(document.payments.size - validPayments.size, "payment", warnings)

            normalised = document.copy(
                shopCredits = validCredits,
                borrowings = validBorrowings,
                lendings = validLendings,
                installments = validInstallments,
                payments = validPayments,
            )
        } else {
            dropped = 0
            normalised = document
        }

        // --- paid totals vs obligation totals --------------------------------
        val paidByObligation = normalised.payments
            .groupBy { it.payableType to it.payableId }
            .mapValues { entry -> entry.value.sumOf { it.amountMinor } }
        paidByObligation.forEach { (key, paid) ->
            val original = originalFor(normalised, key)
            if (original >= 0L && paid > original) {
                warnings += BackupIssue(
                    BackupIssue.Code.PaidExceedsObligation,
                    "A record has more recorded against it (${formatMinor(paid)}) than its total " +
                        "(${formatMinor(original)}); the balance will show as settled.",
                )
            }
        }

        // --- duplicates / profile -------------------------------------------
        normalised.shops.map { it.name.trim().lowercase() }.groupBy { it }.values
            .filter { it.size > 1 }
            .forEach { group ->
                warnings += BackupIssue(
                    BackupIssue.Code.DuplicateShop,
                    "This file contains ${group.size} shops with the same name; they will be " +
                        "imported as separate records.",
                )
            }
        normalised.people.map { it.name.trim().lowercase() + "|" + it.relationship }.groupBy { it }.values
            .filter { it.size > 1 }
            .forEach { group ->
                warnings += BackupIssue(
                    BackupIssue.Code.DuplicatePerson,
                    "This file contains ${group.size} people with the same name and relationship.",
                )
            }
        if (normalised.profile == null) {
            warnings += BackupIssue(
                BackupIssue.Code.MissingProfile,
                "This backup has no profile, so your name and currency stay as they are.",
            )
        }

        if (errors.isNotEmpty()) {
            return BackupValidationResult.Failed(errors.distinctBy { it.code to it.message })
        }
        return BackupValidationResult.Ok(
            ValidatedBackup(
                document = normalised,
                warnings = warnings.distinctBy { it.code to it.message },
                droppedRecords = dropped,
            ),
        )
    }

    private fun paymentBelongs(payment: PaymentEntity, document: BackupDocument): Boolean = when (payment.payableType) {
        "shop_credit" -> document.shopCredits.any { it.id == payment.payableId }
        "loan" -> document.loans.any { it.id == payment.payableId }
        "emi" -> document.emiPurchases.any { it.id == payment.payableId }
        "borrowing" -> document.borrowings.any { it.id == payment.payableId }
        "lending" -> document.lendings.any { it.id == payment.payableId }
        else -> false
    } && (payment.installmentId == null || document.installments.any { it.id == payment.installmentId })

    private fun originalFor(document: BackupDocument, key: Pair<String, Long>): Long = when (key.first) {
        "shop_credit" -> document.shopCredits.firstOrNull { it.id == key.second }?.totalAmountMinor
        "borrowing" -> document.borrowings.firstOrNull { it.id == key.second }?.amountMinor
        "lending" -> document.lendings.firstOrNull { it.id == key.second }?.amountMinor
        "loan" -> document.loans.firstOrNull { it.id == key.second }
            ?.let { it.totalPayableMinor + it.downPaymentMinor }
        "emi" -> document.emiPurchases.firstOrNull { it.id == key.second }?.totalPayableMinor
        else -> null
    } ?: -1L

    private fun warnDropped(count: Int, label: String, warnings: MutableList<BackupIssue>) {
        if (count > 0) {
            warnings += BackupIssue(
                BackupIssue.Code.InvalidPaymentLink,
                "$count $label record(s) link to a record missing from this file and will be skipped.",
            )
        }
    }

    /** All monetary fields in the document, each labelled for an error message. */
    internal fun amounts(document: BackupDocument): List<Pair<String, Long>> = buildList {
        document.shopCredits.forEach {
            add("credit \"${it.productName}\" total" to it.totalAmountMinor)
            add("credit \"${it.productName}\" unit price" to it.unitPriceMinor)
            add("credit \"${it.productName}\" quantity" to it.quantity)
        }
        document.borrowings.forEach { add("a borrowing amount" to it.amountMinor) }
        document.lendings.forEach { add("a lending amount" to it.amountMinor) }
        document.loans.forEach {
            add("loan \"${it.loanName}\" principal" to it.principalMinor)
            add("loan \"${it.loanName}\" total payable" to it.totalPayableMinor)
            add("loan \"${it.loanName}\" down payment" to it.downPaymentMinor)
            add("loan \"${it.loanName}\" installment" to it.installmentAmountMinor)
        }
        document.emiPurchases.forEach {
            add("EMI \"${it.productName}\" total payable" to it.totalPayableMinor)
            add("EMI \"${it.productName}\" down payment" to it.downPaymentMinor)
            add("EMI \"${it.productName}\" EMI amount" to it.emiAmountMinor)
        }
        document.installments.forEach { add("installment #${it.number}" to it.scheduledAmountMinor) }
        document.payments.forEach { add("a payment amount" to it.amountMinor) }
        document.incomes.forEach { add("an income amount" to it.amountMinor) }
        document.expenses.forEach { add("an expense amount" to it.amountMinor) }
    }

    /** Every financial date (epoch day) in the document. */
    internal fun dates(document: BackupDocument): List<Pair<String, Long?>> = buildList {
        document.shopCredits.forEach {
            add("credit \"${it.productName}\" purchase date" to it.purchaseDateEpochDay)
            add("credit \"${it.productName}\" due date" to it.dueDateEpochDay)
        }
        document.borrowings.forEach {
            add("a borrowing date" to it.borrowDateEpochDay)
            add("a borrowing due date" to it.dueDateEpochDay)
        }
        document.lendings.forEach {
            add("a lending date" to it.lendDateEpochDay)
            add("a lending due date" to it.dueDateEpochDay)
        }
        document.loans.forEach {
            add("loan \"${it.loanName}\" start date" to it.startDateEpochDay)
            add("loan \"${it.loanName}\" first due date" to it.firstDueDateEpochDay)
        }
        document.emiPurchases.forEach {
            add("EMI \"${it.productName}\" first due date" to it.firstDueDateEpochDay)
        }
        document.installments.forEach { add("installment #${it.number} due date" to it.dueDateEpochDay) }
        document.payments.forEach { add("a payment date" to it.paidDateEpochDay) }
        document.incomes.forEach { add("an income date" to it.transactionDateEpochDay) }
        document.expenses.forEach { add("an expense date" to it.transactionDateEpochDay) }
    }

    internal fun formatMinor(minor: Long): String {
        val negative = minor < 0L
        val magnitude = Math.abs(minor)
        val text = "%d.%02d".format(magnitude / 100L, magnitude % 100L)
        return (if (negative) "-" else "") + "৳$text"
    }

    const val MIN_EPOCH_DAY = 10_000L // ~1997
    const val MAX_EPOCH_DAY = 60_000L // ~2164
}
