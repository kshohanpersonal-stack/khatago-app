package com.khatago.finance.domain.model

/**
 * The kinds of financial record KhataGo tracks.
 *
 * Direction is modelled explicitly rather than implied by signs, because the single most damaging
 * bug a khata app can have is mixing "what I owe" with "what is owed to me".
 */
enum class ObligationType(val displayName: String, val direction: DebtDirection) {
    ShopCredit("Shop credit", DebtDirection.IOwe),
    Loan("Loan", DebtDirection.IOwe),
    Emi("EMI purchase", DebtDirection.IOwe),
    Borrowing("Borrowed from a person", DebtDirection.IOwe),
    Lending("Lent to a person", DebtDirection.OwedToMe),
    ;

    /** Types whose obligations can be paid down through the payment engine. */
    companion object {
        val payable: List<ObligationType> = entries.toList()

        fun of(type: PayableType): ObligationType = when (type) {
            PayableType.ShopCredit -> ShopCredit
            PayableType.Loan -> Loan
            PayableType.Emi -> Emi
            PayableType.Borrowing -> Borrowing
            PayableType.Lending -> Lending
        }
    }
}

enum class DebtDirection(val label: String) {
    IOwe("Money I owe"),
    OwedToMe("Money owed to me"),
}

/** Stable, persisted identifier of what a payment is attached to. Never reordered or renamed. */
enum class PayableType(val displayName: String) {
    ShopCredit("shop_credit"),
    Loan("loan"),
    Emi("emi"),
    Borrowing("borrowing"),
    Lending("lending"),
    ;

    companion object {
        fun fromKey(key: String?): PayableType? = entries.firstOrNull { it.displayName == key }
    }
}

/** What a payment is applied against: an obligation as a whole, or one scheduled installment. */
data class PayableRef(
    val type: PayableType,
    /** Id of the obligation (shop credit / loan / EMI purchase / borrowing / lending). */
    val obligationId: Long,
    /** Set only when the payment was recorded against a specific loan or EMI installment. */
    val installmentId: Long? = null,
)

/** A scheduled repayment belonging to a loan or an EMI purchase. */
data class Installment(
    val id: Long,
    val ownerType: PayableType,
    val ownerId: Long,
    val number: Int,
    val dueDateEpochDay: Long,
    val scheduledMinor: Long,
    /** Paid *directly* against this installment (allocations are computed separately). */
    val paidMinor: Long = 0L,
    val manual: Boolean = false,
)

enum class InstallmentStatus(val label: String) {
    Paid("Paid"),
    PartiallyPaid("Partially paid"),
    Overdue("Overdue"),
    DueToday("Due today"),
    Upcoming("Upcoming"),
    ;

    companion object {
        fun of(scheduledMinor: Long, allocatedMinor: Long, dueDateEpochDay: Long, todayEpochDay: Long): InstallmentStatus =
            when {
                allocatedMinor >= scheduledMinor && scheduledMinor > 0L -> Paid
                allocatedMinor > 0L -> PartiallyPaid
                dueDateEpochDay < todayEpochDay -> Overdue
                dueDateEpochDay == todayEpochDay -> DueToday
                else -> Upcoming
            }
    }
}

data class PaymentMethodOption(val id: Long, val name: String, val isDefault: Boolean)

/** Income / expense classification. */
enum class TransactionKind(val label: String) {
    Income("Income"),
    Expense("Expense"),
}

/** Status of a single obligation from the ledger's point of view. */
enum class LedgerStatus(val label: String) {
    Unpaid("Unpaid"),
    PartiallyPaid("Partially paid"),
    Paid("Paid"),
    Overdue("Overdue"),
    Cancelled("Cancelled"),
    ;

    companion object {
        /**
         * Pure ledger status: derived only from amounts, never stored.
         * Note [Overdue] requires a due date in the past *and* an unsettled balance — a fully paid
         * record is [Paid] even if it was paid late, which is what a paper khata shows too.
         */
        fun of(
            originalMinor: Long,
            paidMinor: Long,
            dueDateEpochDay: Long?,
            todayEpochDay: Long,
            cancelled: Boolean = false,
        ): LedgerStatus {
            if (cancelled) return Cancelled
            val settled = paidMinor >= originalMinor
            if (settled) return Paid
            if (paidMinor > 0L) return PartiallyPaid
            if (dueDateEpochDay != null && dueDateEpochDay < todayEpochDay && originalMinor > 0L) return Overdue
            if (originalMinor == 0L) return Paid
            return Unpaid
        }
    }
}
