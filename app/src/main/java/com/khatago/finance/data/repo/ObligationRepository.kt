package com.khatago.finance.data.repo

import androidx.room.withTransaction
import com.khatago.finance.core.time.Frequency
import com.khatago.finance.core.time.ScheduleEntry
import com.khatago.finance.core.time.InstallmentSchedule
import com.khatago.finance.data.db.KhataGoDatabase
import com.khatago.finance.data.db.entity.EmiPurchaseEntity
import com.khatago.finance.data.db.entity.InstallmentEntity
import com.khatago.finance.data.db.entity.LoanEntity
import com.khatago.finance.data.db.dao.ObligationRow
import com.khatago.finance.domain.calc.FinancialBalance
import com.khatago.finance.domain.calc.InstallmentAllocator
import com.khatago.finance.domain.model.Installment
import com.khatago.finance.domain.model.InstallmentView
import com.khatago.finance.domain.model.Obligation
import com.khatago.finance.domain.model.PayableType
import com.khatago.finance.domain.model.ScheduleProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Bank/NGO loans and EMI purchases, plus the installment schedule that both share.
 *
 * Two decisions here are load-bearing:
 *
 *  - **The schedule is a plan, not a balance.** `installments.paidMinor` is only ever what a payment
 *    recorded directly at that line. The *displayed* per-line status comes from
 *    [InstallmentAllocator] applied to the obligation's real paid total, which guarantees
 *    "sum of the schedule" == "the headline remaining" — the two can never drift apart, and the
 *    user can always reconcile the schedule against the total.
 *  - **Regeneration is manual and lossless-by-construction.** Editing terms regenerates only the
 *    lines the user has not touched; manually edited lines are kept and reported back, because a
 *    silently rewritten schedule after an edit is how trust in a loan tracker dies.
 */
class ObligationRepository(private val database: KhataGoDatabase) {

    // --- loans ----------------------------------------------------------------

    fun observeLoans(): Flow<List<LoanEntity>> = database.loanDao().observeAll()

    fun observeLoanBalances(todayEpochDay: Long, hideCancelled: Boolean): Flow<List<ObligationRow>> =
        database.loanDao().observeBalances(todayEpochDay, hideCancelled)

    fun observeLoanObligations(todayEpochDay: Long): Flow<List<Obligation>> =
        database.loanDao().observeBalances(todayEpochDay, hideCancelled = false).map { rows ->
            rows.map { it.toObligation(PayableType.Loan) }
        }

    fun observeLoan(id: Long): Flow<LoanEntity?> = database.loanDao().observeById(id)

    fun observeLoanBalance(id: Long, todayEpochDay: Long): Flow<ObligationRow?> =
        database.loanDao().observeBalance(id, todayEpochDay)

    fun observeLoanOutstanding(): Flow<Long> = database.loanDao().observeOutstanding()

    suspend fun findLoan(id: Long): LoanEntity? = database.loanDao().findById(id)

    suspend fun findAllLoans(): List<LoanEntity> = database.loanDao().findAll()

    fun validateLoan(loan: LoanEntity): String? = when {
        loan.institution.isBlank() -> "Enter the bank or NGO name."
        loan.loanName.isBlank() -> "Give this loan a name, e.g. \"Agriculture loan\"."
        loan.principalMinor <= 0L -> "The loan amount must be greater than zero."
        loan.totalPayableMinor < loan.principalMinor ->
            "The total payable cannot be less than the amount you received."
        loan.installmentCount <= 0 -> "Enter how many installments the loan has."
        loan.installmentCount > 600 -> "That is too many installments to be a real schedule."
        loan.installmentAmountMinor <= 0L -> "Enter the installment amount."
        loan.downPaymentMinor > loan.totalPayableMinor ->
            "The down payment cannot exceed the total payable."
        else -> null
    }

    suspend fun saveLoan(loan: LoanEntity, regenerateSchedule: Boolean = true): SaveResult =
        database.withTransaction {
            validateLoan(loan)?.let { return@withTransaction SaveResult.Invalid(it) }
            val now = System.currentTimeMillis()
            val id = if (loan.id == 0L) {
                database.loanDao().insert(loan.copy(createdAt = now, updatedAt = now))
            } else {
                database.loanDao().update(loan.copy(updatedAt = now))
                loan.id
            }
            if (regenerateSchedule) {
                syncSchedule(
                    ownerType = PayableType.Loan.displayName,
                    ownerId = id,
                    firstDueDateEpochDay = loan.firstDueDateEpochDay,
                    count = loan.installmentCount,
                    frequency = Frequency.fromName(loan.installmentFrequency),
                    customIntervalDays = loan.customIntervalDays,
                    installmentMinor = loan.installmentAmountMinor,
                    totalPayableMinor = loan.totalPayableMinor,
                )
            }
            SaveResult.Saved
        }

    suspend fun deleteLoan(id: Long) = database.withTransaction { database.loanDao().deleteWithSchedule(id) }

    // --- EMI ------------------------------------------------------------------

    fun observeEmis(): Flow<List<EmiPurchaseEntity>> = database.emiDao().observeAll()

    fun observeEmiBalances(todayEpochDay: Long, hideCancelled: Boolean): Flow<List<ObligationRow>> =
        database.emiDao().observeBalances(todayEpochDay, hideCancelled)

    fun observeEmiObligations(todayEpochDay: Long): Flow<List<Obligation>> =
        database.emiDao().observeBalances(todayEpochDay, hideCancelled = false).map { rows ->
            rows.map { it.toObligation(PayableType.Emi) }
        }

    fun observeEmi(id: Long): Flow<EmiPurchaseEntity?> = database.emiDao().observeById(id)

    fun observeEmiBalance(id: Long, todayEpochDay: Long): Flow<ObligationRow?> =
        database.emiDao().observeBalance(id, todayEpochDay)

    fun observeEmiOutstanding(): Flow<Long> = database.emiDao().observeOutstanding()

    suspend fun findEmi(id: Long): EmiPurchaseEntity? = database.emiDao().findById(id)

    suspend fun findAllEmis(): List<EmiPurchaseEntity> = database.emiDao().findAll()

    fun validateEmi(emi: EmiPurchaseEntity): String? = when {
        emi.productName.isBlank() -> "Enter what you bought."
        emi.merchant.isBlank() -> "Enter the shop or provider."
        emi.totalPayableMinor <= 0L -> "Enter the total payable for this plan."
        emi.installmentCount <= 0 -> "Enter how many EMIs there will be."
        emi.installmentCount > 600 -> "That is too many installments to be a real schedule."
        emi.emiAmountMinor <= 0L -> "Enter the EMI amount."
        emi.downPaymentMinor > emi.totalPayableMinor -> "The down payment cannot exceed the total payable."
        emi.cashPriceMinor > 0L && emi.totalPayableMinor < emi.cashPriceMinor ->
            "The total payable is lower than the cash price — check which figure is which."
        else -> null
    }

    suspend fun saveEmi(emi: EmiPurchaseEntity, regenerateSchedule: Boolean = true): SaveResult =
        database.withTransaction {
            validateEmi(emi)?.let { return@withTransaction SaveResult.Invalid(it) }
            val now = System.currentTimeMillis()
            val id = if (emi.id == 0L) {
                database.emiDao().insert(emi.copy(createdAt = now, updatedAt = now))
            } else {
                database.emiDao().update(emi.copy(updatedAt = now))
                emi.id
            }
            if (regenerateSchedule) {
                syncSchedule(
                    ownerType = PayableType.Emi.displayName,
                    ownerId = id,
                    firstDueDateEpochDay = emi.firstDueDateEpochDay,
                    count = emi.installmentCount,
                    frequency = Frequency.fromName(emi.installmentFrequency),
                    customIntervalDays = emi.customIntervalDays,
                    installmentMinor = emi.emiAmountMinor,
                    totalPayableMinor = emi.totalPayableMinor - emi.downPaymentMinor,
                )
            }
            SaveResult.Saved
        }

    suspend fun deleteEmi(id: Long) = database.withTransaction { database.emiDao().deleteWithSchedule(id) }

    // --- schedules ------------------------------------------------------------

    /**
     * Rebuilds the schedule while preserving manually edited lines *and* per-line paid amounts.
     *
     * Two invariants, both about not losing money's history when a plan is edited:
     *  - a line the user dated by hand is kept as-is; regenerating it would silently move a due date
     *    the person negotiated with a lender;
     *  - `paidMinor` on a generated line is carried over by instalment number, because payments
     *    already recorded against line 3 must still show on line 3 after the schedule is rebuilt.
     *
     * Payments are detached from their line ids first: the rows are deleted here, so a payment left
     * pointing at a deleted id would later match a *regenerated* line by coincidence of id. The
     * payments themselves are never touched — the obligation's paid total is the payments table.
     */
    private suspend fun syncSchedule(
        ownerType: String,
        ownerId: Long,
        firstDueDateEpochDay: Long,
        count: Int,
        frequency: Frequency,
        customIntervalDays: Int,
        installmentMinor: Long,
        totalPayableMinor: Long,
    ): Int {
        val existing = when (ownerType) {
            PayableType.Loan.displayName -> database.loanDao().findInstallments(ownerId)
            else -> database.emiDao().findInstallments(ownerId)
        }
        val manualLines = existing.filter { it.manual }
        val paidByNumber = existing.filter { it.paidMinor > 0L }.associate { it.number to it.paidMinor }
        val generated = InstallmentSchedule.generate(
            firstDueDateEpochDay = firstDueDateEpochDay.takeIf { it > 0L },
            count = count,
            frequency = frequency,
            customIntervalDays = customIntervalDays,
            installmentMinor = installmentMinor.takeIf { it > 0L },
            totalPayableMinor = totalPayableMinor.takeIf { it > 0L },
        )
        val now = System.currentTimeMillis()
        val rows = generated.map { entry ->
            InstallmentEntity(
                ownerType = ownerType,
                ownerId = ownerId,
                number = entry.number,
                dueDateEpochDay = entry.dueDateEpochDay,
                scheduledAmountMinor = entry.amountMinor,
                paidMinor = paidByNumber[entry.number] ?: 0L,
                manual = false,
                createdAt = now,
            )
        } + manualLines.map { it.copy(ownerType = ownerType, ownerId = ownerId) }

        when (ownerType) {
            PayableType.Loan.displayName -> {
                database.paymentDao().detachFromLines(ownerType, ownerId)
                database.loanDao().clearSchedule(ownerId)
                if (rows.isNotEmpty()) database.loanDao().insertInstallments(rows)
            }

            else -> {
                database.paymentDao().detachFromLines(ownerType, ownerId)
                database.emiDao().clearSchedule(ownerId)
                if (rows.isNotEmpty()) database.emiDao().insertInstallments(rows)
            }
        }
        return manualLines.size
    }

    /** Preview a schedule from a form, before anything is written. */
    fun previewSchedule(
        firstDueDateEpochDay: Long?,
        count: Int,
        frequency: Frequency,
        customIntervalDays: Int,
        installmentMinor: Long,
        totalPayableMinor: Long?,
    ): List<ScheduleEntry> = InstallmentSchedule.generate(
        firstDueDateEpochDay = firstDueDateEpochDay,
        count = count,
        frequency = frequency,
        customIntervalDays = customIntervalDays,
        installmentMinor = installmentMinor.takeIf { it > 0L },
        totalPayableMinor = totalPayableMinor?.takeIf { it > 0L },
    )

    /** The rendered schedule for a loan or EMI: allocations come from the obligation's paid total. */
    suspend fun scheduleProgress(
        ownerType: PayableType,
        ownerId: Long,
        paidMinor: Long,
        todayEpochDay: Long,
    ): ScheduleProgress {
        val entities = when (ownerType) {
            PayableType.Loan -> database.loanDao().findInstallments(ownerId)
            PayableType.Emi -> database.emiDao().findInstallments(ownerId)
            else -> emptyList()
        }
        return computeProgress(entities, paidMinor, todayEpochDay)
    }

    fun observeScheduleProgress(
        ownerType: PayableType,
        ownerId: Long,
        todayEpochDay: Long,
    ): Flow<ScheduleProgress> {
        val flow: Flow<List<InstallmentEntity>> = when (ownerType) {
            PayableType.Loan -> database.loanDao().observeInstallments(ownerId)
            else -> database.emiDao().observeInstallments(ownerId)
        }
        val obligations: Flow<ObligationRow?> = when (ownerType) {
            PayableType.Loan -> database.loanDao().observeBalance(ownerId, todayEpochDay)
            else -> database.emiDao().observeBalance(ownerId, todayEpochDay)
        }
        return kotlinx.coroutines.flow.combine(flow, obligations) { installments, balance ->
            computeProgress(installments, balance?.paidMinor ?: 0L, todayEpochDay)
        }
    }

    private fun computeProgress(
        entities: List<InstallmentEntity>,
        paidMinor: Long,
        todayEpochDay: Long,
    ): ScheduleProgress {
        val installments = entities.map {
            Installment(
                id = it.id,
                ownerType = PayableType.fromKey(it.ownerType) ?: PayableType.Loan,
                ownerId = it.ownerId,
                number = it.number,
                dueDateEpochDay = it.dueDateEpochDay,
                scheduledMinor = it.scheduledAmountMinor,
                paidMinor = it.paidMinor,
                manual = it.manual,
            )
        }
        val allocated = InstallmentAllocator.allocate(installments, paidMinor, todayEpochDay)
        val views = allocated.map {
            InstallmentView(
                id = it.installment.id,
                number = it.installment.number,
                dueDateEpochDay = it.installment.dueDateEpochDay,
                scheduledMinor = it.installment.scheduledMinor,
                allocatedMinor = it.allocatedMinor,
                statusLabel = it.status.label,
                isSettled = it.isSettled,
            )
        }
        return ScheduleProgress(
            paidCount = InstallmentAllocator.countPaid(allocated),
            totalCount = views.size,
            allocated = views,
            nextDueEpochDay = InstallmentAllocator.nextDue(allocated, todayEpochDay)?.installment?.dueDateEpochDay,
            overdueCount = InstallmentAllocator.overdue(allocated, todayEpochDay).size,
            overdueMinor = InstallmentAllocator.overdue(allocated, todayEpochDay).sumOf { it.shortfallMinor },
        )
    }

    /** Updates a single installment line by hand (an institution sent a different figure). */
    suspend fun editInstallment(
        installment: InstallmentEntity,
        newDueDateEpochDay: Long?,
        newScheduledMinor: Long?,
    ): SaveResult = database.withTransaction {
        val updated = installment.copy(
            dueDateEpochDay = newDueDateEpochDay ?: installment.dueDateEpochDay,
            scheduledAmountMinor = newScheduledMinor ?: installment.scheduledAmountMinor,
            manual = true,
        )
        database.installmentDao().insert(updated)
        SaveResult.Saved
    }
}

private fun ObligationRow.toObligation(type: PayableType) = Obligation(
    type = type,
    id = id,
    title = title,
    subtitle = subtitle,
    balance = FinancialBalance(totalMinor, paidMinor),
    dueDateEpochDay = dueDateEpochDay,
    cancelled = cancelled,
)

