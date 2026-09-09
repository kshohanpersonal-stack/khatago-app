package com.khatago.finance.domain.model

import com.khatago.finance.core.time.DueStatus
import com.khatago.finance.core.time.dueStatusOf
import com.khatago.finance.domain.calc.FinancialBalance

/**
 * The one read model every list in KhataGo is built from.
 *
 * A "record" is intentionally uniform across shop credits, loans, EMIs, borrowings and lendings:
 * an obligation, its derived balance, its direction, its due position. That uniformity is what lets
 * Records, search, reports, CSV export and the payment centre share one rendering and one set of
 * tests instead of five subtly-different implementations.
 */
data class Obligation(
    val type: PayableType,
    val id: Long,
    val title: String,
    val subtitle: String,
    val balance: FinancialBalance,
    val dueDateEpochDay: Long?,
    val cancelled: Boolean,
    /** Installment progress for loans/EMIs; null for flat obligations. */
    val installmentsPaid: Int? = null,
    val installmentsTotal: Int? = null,
) {
    val remainingMinor: Long get() = balance.remainingMinor
    val isSettled: Boolean get() = balance.isSettled
    val direction: DebtDirection
        get() = if (type == PayableType.Lending) DebtDirection.OwedToMe else DebtDirection.IOwe

    fun dueStatus(todayEpochDay: Long): DueStatus =
        if (isSettled) DueStatus.NoDueDate else dueStatusOf(dueDateEpochDay, todayEpochDay)
}

/**
 * The numbers a payment guard needs, resolved once per write.
 *
 * It lives in the domain layer because it contains *no* storage knowledge: it is simply
 * "original, paid, due, cancelled, and how it is labelled", which is exactly the input the payment
 * validation rules require. Keeping it here means the repository can build it from Room while the
 * rules stay testable without Android.
 */
data class ObligationSnapshot(
    val originalMinor: Long,
    val recordedPaidMinor: Long,
    val dueDateEpochDay: Long?,
    val cancelled: Boolean,
    val title: String,
    val subtitle: String,
) {
    val remainingMinor: Long get() = (originalMinor - recordedPaidMinor).coerceAtLeast(0L)

    /**
     * Remaining balance *after* a candidate payment, floored at zero.
     *
     * The floor is what makes an overpayment impossible to persist: the engine validates against the
     * true remainder, and this is what the UI shows as "will leave". They are the same function, so a
     * form can never preview a balance the write will not produce.
     */
    fun remainingAfter(paymentMinor: Long): Long =
        (originalMinor - (recordedPaidMinor + paymentMinor)).coerceAtLeast(0L)
}

/** A payment as displayed in a ledger list (name of the payer/payee resolved for display). */
data class PaymentEntry(
    val id: Long,
    val amountMinor: Long,
    val paidDateEpochDay: Long,
    val methodName: String,
    val reference: String?,
    val note: String?,
    val installmentId: Long?,
    val installmentNumber: Int?,
)

/** One installment line with its allocated position, ready to render. */
data class InstallmentView(
    val id: Long,
    val number: Int,
    val dueDateEpochDay: Long,
    val scheduledMinor: Long,
    val allocatedMinor: Long,
    val statusLabel: String,
    val isSettled: Boolean,
) {
    val shortfallMinor: Long get() = (scheduledMinor - allocatedMinor).coerceAtLeast(0L)
}

data class ScheduleProgress(
    val paidCount: Int,
    val totalCount: Int,
    val allocated: List<InstallmentView>,
    val nextDueEpochDay: Long?,
    val overdueCount: Int,
    val overdueMinor: Long,
) {
    val percentLabel: String
        get() = if (totalCount <= 0) {
            "—"
        } else {
            "%.1f%%".format(java.util.Locale.US, paidCount * 100.0 / totalCount)
        }
}

/** Everything the dashboard shows, derived from one repository call so tiles cannot disagree. */
data class DashboardSnapshot(
    val greetingName: String?,
    val totalIOweMinor: Long,
    val totalOwedToMeMinor: Long,
    val dueSoonCount: Int,
    val dueSoonMinor: Long,
    val overdueCount: Int,
    val overdueMinor: Long,
    val monthIncomeMinor: Long,
    val monthExpenseMinor: Long,
    val monthPaidMinor: Long,
    val shopCreditOutstandingMinor: Long,
    val loanOutstandingMinor: Long,
    val emiOutstandingMinor: Long,
    val borrowingOutstandingMinor: Long,
    val lendingOutstandingMinor: Long,
    val upcoming: List<DueItem>,
    val recentActivity: List<ActivityItem>,
    val todayEpochDay: Long,
    val hasAnyRecord: Boolean,
) {
    val netCashFlowMinor: Long get() = monthIncomeMinor - monthExpenseMinor
    val outstandingBreakdown: List<Pair<PayableType, Long>>
        get() = listOf(
            PayableType.ShopCredit to shopCreditOutstandingMinor,
            PayableType.Loan to loanOutstandingMinor,
            PayableType.Emi to emiOutstandingMinor,
            PayableType.Borrowing to borrowingOutstandingMinor,
        ).filter { it.second > 0L }
}

data class DueItem(
    val type: PayableType,
    val obligationId: Long,
    val title: String,
    val subtitle: String,
    val dueDateEpochDay: Long,
    val amountMinor: Long,
    val totalMinor: Long,
    val paidMinor: Long,
) {
    fun status(todayEpochDay: Long): DueStatus = dueStatusOf(dueDateEpochDay, todayEpochDay)
    val isOverdue: Boolean get() = amountMinor > 0L
}

data class ActivityItem(
    val typeKey: String,
    val id: Long,
    val title: String,
    val subtitle: String,
    val amountMinor: Long,
    val dateEpochDay: Long,
)

/** Payment-centre grouping. */
data class PaymentCenterState(
    val dueToday: List<DueItem>,
    val upcoming: List<DueItem>,
    val overdue: List<DueItem>,
    val recentlyPaid: List<PaymentEntry>,
)

/** A month of cash-flow for analytics and reports. */
data class CashFlowPoint(
    val monthKey: String,
    val label: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
) {
    val netMinor: Long get() = incomeMinor - expenseMinor
}

data class CategorySlice(
    val name: String,
    val totalMinor: Long,
    val recordCount: Int,
) {
    val share: Double get() = if (totalMinor <= 0L) 0.0 else totalMinor.toDouble()
}

/**
 * The Financial Snapshot: an app-generated, plainly-labelled overview.
 *
 * It is explicitly **not** a credit score. There is no external data, no scoring bureau, no
 * prediction. It is a transparent, deterministic description of the numbers on the user's own
 * device, with the formula shown in the UI so the user can see exactly why it says what it says.
 */
data class FinancialSnapshot(
    val score: Int,
    val band: SnapshotBand,
    val monthlyNetMinor: Long,
    val outstandingMinor: Long,
    val owedToMeMinor: Long,
    val overdueCount: Int,
    val paymentConsistency: Double,
    val reasons: List<String>,
)

enum class SnapshotBand(val label: String) {
    GettingOrganised("Getting organised"),
    Steady("Steady"),
    UnderPressure("Under pressure"),
    SolidGround("Solid ground"),
}
