package com.khatago.finance.data.repo

import androidx.room.withTransaction
import com.khatago.finance.core.money.MoneyMinor
import com.khatago.finance.data.db.KhataGoDatabase
import com.khatago.finance.data.db.entity.PaymentEntity
import com.khatago.finance.domain.calc.PaymentValidation
import com.khatago.finance.domain.model.DueItem
import com.khatago.finance.domain.model.PayableType
import com.khatago.finance.domain.model.PaymentEntry
import com.khatago.finance.domain.model.TransactionKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The payment engine — the only code path in KhataGo that writes money against an obligation.
 *
 * Every module (shop credit, loan, EMI, borrowing, lending) funnels through here, which is what keeps
 * the invariants provable rather than hoped-for:
 *
 *  1. A payment must be > 0 minor units.
 *  2. A payment may never exceed the obligation's remaining amount. KhataGo does not keep "credit
 *     balances": overpayment is rejected with an explanation, so `remaining >= 0` holds for every
 *     record at every instant, including after edits.
 *  3. Editing a payment validates against the obligation *minus every other payment*, so changing an
 *     existing payment cannot smuggle in an overpayment.
 *  4. Deleting a payment needs no repair pass, because nothing cached the total it contributed to.
 *
 * [ObligationSnapshot] is resolved by [PayableResolver], so the same four rules apply identically to
 * a ৳1,400 rice credit and to installment 7 of an NGO loan.
 */
class PaymentRepository(
    private val database: KhataGoDatabase,
    private val resolver: PayableResolver,
) {

    fun observePayments(payableType: PayableType, payableId: Long): Flow<List<PaymentEntity>> =
        database.paymentDao().observeForPayable(payableType.displayName, payableId)

    fun observePaymentEntries(payableType: PayableType, payableId: Long): Flow<List<PaymentEntry>> =
        observePayments(payableType, payableId).map { rows -> rows.map { it.toEntry() } }

    /** Everything recorded against a payable, newest first, for a ledger list. */
    suspend fun paymentsFor(payableType: PayableType, payableId: Long): List<PaymentEntity> =
        database.paymentDao().findForPayable(payableType.displayName, payableId)

    /**
     * Records a payment. `downPayment` is passed for loans/EMIs so the guard sees the *full* paid
     * figure (recorded payments + the down payment already baked into the obligation).
     */
    suspend fun record(
        payableType: PayableType,
        payableId: Long,
        amountMinor: Long,
        paidDateEpochDay: Long,
        methodName: String,
        reference: String?,
        note: String?,
        installmentId: Long? = null,
    ): PaymentOutcome = database.withTransaction {
        val snapshot = resolver.resolve(payableType, payableId)
            ?: return@withTransaction PaymentOutcome.NotFound(
                "That record no longer exists, so the payment cannot be saved.",
            )
        val validation = PaymentValidation.validate(
            amountMinor = amountMinor,
            originalMinor = snapshot.originalMinor,
            // `recordedPaidMinor` already includes the down payment for loans/EMIs.
            paidMinor = snapshot.recordedPaidMinor,
            cancelled = snapshot.cancelled,
        )
        when (validation) {
            is PaymentValidation.Rejected -> return@withTransaction PaymentOutcome.Rejected(
                message = validation.message,
                remainingMinor = snapshot.originalMinor - snapshot.recordedPaidMinor,
            )

            is PaymentValidation.Accepted -> {
                val now = System.currentTimeMillis()
                val id = database.paymentDao().insert(
                    PaymentEntity(
                        payableType = payableType.displayName,
                        payableId = payableId,
                        installmentId = installmentId,
                        amountMinor = validation.amountMinor,
                        paidDateEpochDay = paidDateEpochDay,
                        methodName = methodName.ifBlank { "Cash" },
                        reference = reference?.trim()?.takeIf { it.isNotEmpty() },
                        note = note?.trim()?.takeIf { it.isNotEmpty() },
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                PaymentOutcome.Recorded(
                    paymentId = id,
                    amountMinor = validation.amountMinor,
                    remainingAfterMinor = snapshot.remainingAfter(validation.amountMinor),
                )
            }
        }
    }

    /** Updates an existing payment, validated against everything except itself. */
    suspend fun update(
        paymentId: Long,
        amountMinor: Long,
        paidDateEpochDay: Long,
        methodName: String,
        reference: String?,
        note: String?,
    ): PaymentOutcome = database.withTransaction {
        val existing = database.paymentDao().findById(paymentId)
            ?: return@withTransaction PaymentOutcome.NotFound("That payment could not be found.")
        val payableType = PayableType.fromKey(existing.payableType)
            ?: return@withTransaction PaymentOutcome.NotFound("That payment refers to an unknown record type.")
        val snapshot = resolver.resolve(payableType, existing.payableId)
            ?: return@withTransaction PaymentOutcome.NotFound("The record this payment belongs to has been deleted.")

        // Read the ledger directly rather than subtracting from the snapshot: the snapshot may fold
        // in a down payment, and arithmetic on a derived figure is how off-by-one bugs are born.
        val paidExcludingThisRaw = database.paymentDao()
            .paidTotalExcluding(existing.payableType, existing.payableId, paymentId)
        val paidExcludingThis = (paidExcludingThisRaw + downPaymentFor(payableType, existing.payableId))
            .coerceAtLeast(0L)
        val validation = PaymentValidation.validate(
            amountMinor = amountMinor,
            originalMinor = snapshot.originalMinor,
            paidMinor = paidExcludingThis,
            cancelled = snapshot.cancelled,
        )
        when (validation) {
            is PaymentValidation.Rejected -> return@withTransaction PaymentOutcome.Rejected(
                message = validation.message,
                remainingMinor = (snapshot.originalMinor - paidExcludingThis).coerceAtLeast(0L),
            )

            is PaymentValidation.Accepted -> {
                database.paymentDao().update(
                    existing.copy(
                        amountMinor = validation.amountMinor,
                        paidDateEpochDay = paidDateEpochDay,
                        methodName = methodName.ifBlank { "Cash" },
                        reference = reference?.trim()?.takeIf { it.isNotEmpty() },
                        note = note?.trim()?.takeIf { it.isNotEmpty() },
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                PaymentOutcome.Recorded(
                    paymentId = paymentId,
                    amountMinor = validation.amountMinor,
                    remainingAfterMinor = (snapshot.originalMinor - (paidExcludingThis + validation.amountMinor))
                        .coerceAtLeast(0L),
                )
            }
        }
    }

    /**
     * Deletes a payment. Kept as its own call with the payment entity as argument so that callers can
     * hand the deleted row straight back for undo — an undo that has to re-derive the row from the
     * database after it is gone is an undo that silently loses data.
     */
    suspend fun delete(payment: PaymentEntity) = database.withTransaction {
        database.paymentDao().delete(payment)
    }

    suspend fun restore(payment: PaymentEntity): Long = database.withTransaction {
        database.paymentDao().insert(payment)
    }

    suspend fun findById(id: Long): PaymentEntity? = database.paymentDao().findById(id)

    fun observeRecent(limit: Int = 20): Flow<List<PaymentEntry>> =
        database.paymentDao().observeRecent(limit).map { rows -> rows.map { it.toEntry() } }

    fun observePaidBetween(startEpochDay: Long, endEpochDay: Long): Flow<Long> =
        database.paymentDao().observePaidBetween(startEpochDay, endEpochDay)

    /** The due queue behind Payments, the dashboard tiles and the daily reminder worker. */
    fun observeDueBetween(startEpochDay: Long?, endEpochDay: Long?): Flow<List<DueItem>> =
        database.dueDao().observeBetween(startEpochDay, endEpochDay).map { rows ->
            rows.map {
                DueItem(
                    type = PayableType.fromKey(it.payableType) ?: PayableType.ShopCredit,
                    obligationId = it.payableId,
                    title = it.title,
                    subtitle = it.subtitle,
                    dueDateEpochDay = it.dueDateEpochDay,
                    amountMinor = it.amountMinor,
                    totalMinor = it.totalMinor,
                    paidMinor = it.paidMinor,
                )
            }
        }

    fun observeDueSummary(startEpochDay: Long?, endEpochDay: Long?): Flow<Pair<Int, Long>> =
        database.dueDao().observeSummaryBetween(startEpochDay, endEpochDay).map { it.count to it.totalMinor }

    /**
     * Payment history bucketed per day for the Analytics chart. Aggregated in SQL and mapped to the
     * last N days in memory so an empty day still renders as zero instead of being invisible (a
     * chart that skips empty days shows a gap where there was simply no spending).
     */
    fun observePaymentDays(startEpochDay: Long, endEpochDay: Long):
        Flow<List<com.khatago.finance.data.db.dao.PaymentDayTotalRow>> =
        database.paymentDao().observePaymentDays(startEpochDay, endEpochDay)

    /** Loans/EMIs bake the down payment into their paid total; other payables have none. */
    private suspend fun downPaymentFor(payableType: PayableType, payableId: Long): Long = when (payableType) {
        PayableType.Loan -> database.loanDao().findById(payableId)?.downPaymentMinor ?: 0L
        PayableType.Emi -> database.emiDao().findById(payableId)?.downPaymentMinor ?: 0L
        else -> 0L
    }

}

/**
 * Resolves "what does this payable look like right now" for the payment engine.
 *
 * Each obligation module already knows how to compute its own balance from its ledger; the resolver
 * is the single adapter that presents all five in one shape so the payment guard cannot have a
 * per-module special case that someone forgets to update.
 */
class PayableResolver(private val database: KhataGoDatabase) {

    suspend fun resolve(payableType: PayableType, payableId: Long): ObligationSnapshot? = when (payableType) {
        PayableType.ShopCredit -> {
            val credit = database.creditDao().findById(payableId) ?: return null
            ObligationSnapshot(
                originalMinor = credit.totalAmountMinor,
                recordedPaidMinor = database.creditDao().paidTotal(payableId),
                dueDateEpochDay = credit.dueDateEpochDay,
                cancelled = credit.cancelled,
                title = credit.productName,
                subtitle = "shop credit",
            )
        }

        PayableType.Loan -> {
            val loan = database.loanDao().findById(payableId) ?: return null
            ObligationSnapshot(
                originalMinor = loan.totalPayableMinor + loan.downPaymentMinor,
                recordedPaidMinor = loan.downPaymentMinor +
                    database.paymentDao().paidTotal("loan", payableId),
                dueDateEpochDay = null,
                cancelled = loan.cancelled,
                title = loan.loanName,
                subtitle = loan.institution,
            )
        }

        PayableType.Emi -> {
            val emi = database.emiDao().findById(payableId) ?: return null
            ObligationSnapshot(
                originalMinor = emi.totalPayableMinor,
                recordedPaidMinor = emi.downPaymentMinor +
                    database.paymentDao().paidTotal("emi", payableId),
                dueDateEpochDay = null,
                cancelled = emi.cancelled,
                title = emi.productName,
                subtitle = emi.merchant,
            )
        }

        PayableType.Borrowing -> {
            val borrowing = database.personDao().findBorrowing(payableId) ?: return null
            ObligationSnapshot(
                originalMinor = borrowing.amountMinor,
                recordedPaidMinor = database.paymentDao().paidTotal("borrowing", payableId),
                dueDateEpochDay = borrowing.dueDateEpochDay,
                cancelled = borrowing.cancelled,
                title = "Borrowed",
                subtitle = "from a person",
            )
        }

        PayableType.Lending -> {
            val lending = database.personDao().findLending(payableId) ?: return null
            ObligationSnapshot(
                originalMinor = lending.amountMinor,
                recordedPaidMinor = database.paymentDao().paidTotal("lending", payableId),
                dueDateEpochDay = lending.dueDateEpochDay,
                cancelled = lending.cancelled,
                title = "Lent",
                subtitle = "to a person",
            )
        }
    }
}

/** The numbers the payment guard needs, and nothing else. */
data class ObligationSnapshot(
    val originalMinor: Long,
    val recordedPaidMinor: Long,
    val dueDateEpochDay: Long?,
    val cancelled: Boolean,
    val title: String,
    val subtitle: String,
) {
    val remainingMinor: Long get() = (originalMinor - recordedPaidMinor).coerceAtLeast(0L)

    fun remainingAfter(paymentMinor: Long): Long =
        (originalMinor - (recordedPaidMinor + paymentMinor)).coerceAtLeast(0L)
}

sealed interface PaymentOutcome {
    data class Recorded(val paymentId: Long, val amountMinor: Long, val remainingAfterMinor: Long) : PaymentOutcome
    data class Rejected(val message: String, val remainingMinor: Long) : PaymentOutcome
    data class NotFound(val message: String) : PaymentOutcome

    val isSuccess: Boolean get() = this is Recorded

    /** Message for the form, phrased the way you would tell a friend, not the way a validator logs. */
    fun errorMessage(): String? = when (this) {
        is Recorded -> null
        is Rejected -> message + " The most you can record right now is " +
            "${MoneyMinor.ofMinor(remainingMinor.coerceAtLeast(0L)).toPlainString(2)}."
        is NotFound -> message
    }
}

private fun PaymentEntity.toEntry(): PaymentEntry = PaymentEntry(
    id = id,
    amountMinor = amountMinor,
    paidDateEpochDay = paidDateEpochDay,
    methodName = methodName,
    reference = reference,
    note = note,
    installmentId = installmentId,
    installmentNumber = null,
)

/** Kind used by the ledger list; kept here so Records and Reports share one label source. */
internal fun kindLabel(kind: String): String = when (kind) {
    TransactionKind.Income.label.lowercase() -> "Income"
    TransactionKind.Expense.label.lowercase() -> "Expense"
    else -> "Payment"
}
