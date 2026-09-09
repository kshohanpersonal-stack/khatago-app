package com.khatago.finance.domain.calc

import com.khatago.finance.core.money.MoneyMinor
import com.khatago.finance.domain.model.Installment
import com.khatago.finance.domain.model.InstallmentStatus
import com.khatago.finance.domain.model.LedgerStatus

/**
 * Balance of a single obligation, always **derived** from its original amount and its payment
 * ledger. There is no `remainingAmount` column anywhere in KhataGo: a stored "remaining" can drift
 * away from the payments that were actually recorded, and a drifted number in a money app is a bug
 * the user discovers months later. Everything downstream recomputes.
 */
data class FinancialBalance(
    val originalMinor: Long,
    val paidMinor: Long,
) {
    init {
        require(originalMinor >= 0L) { "originalMinor must not be negative" }
        require(paidMinor >= 0L) { "paidMinor must not be negative" }
    }

    /** Clamped at zero: an obligation can never have a negative remaining amount. */
    val remainingMinor: Long get() = if (paidMinor >= originalMinor) 0L else originalMinor - paidMinor

    val isSettled: Boolean get() = remainingMinor == 0L
    val hasHistory: Boolean get() = originalMinor > 0L || paidMinor > 0L
    val paid: MoneyMinor get() = MoneyMinor.ofMinor(paidMinor)
    val remaining: MoneyMinor get() = MoneyMinor.ofMinor(remainingMinor)
    val original: MoneyMinor get() = MoneyMinor.ofMinor(originalMinor)

    /** 0..100 with one decimal handled by the caller; ratio keeps it exact and testable. */
    val paidRatio: Double
        get() = if (originalMinor <= 0L) 0.0 else minOf(1.0, paidMinor.toDouble() / originalMinor.toDouble())

    fun status(dueDateEpochDay: Long?, todayEpochDay: Long, cancelled: Boolean = false): LedgerStatus =
        LedgerStatus.of(originalMinor, paidMinor, dueDateEpochDay, todayEpochDay, cancelled)

    companion object {
        val ZERO = FinancialBalance(0L, 0L)

        fun of(original: MoneyMinor, paid: MoneyMinor) =
            FinancialBalance(original.minor, paid.minor)
    }
}

/** Guard rails for a payment before it is written. */
sealed interface PaymentValidation {
    data class Accepted(val amountMinor: Long) : PaymentValidation

    data class Rejected(val message: String, val kind: Kind) : PaymentValidation

    enum class Kind {
        AmountTooSmall,
        ExceedsRemaining,
        NothingOutstanding,
        ObligationCancelled,
    }

    companion object {
        /**
         * KhataGo records what actually happened, so it never needs a negative or "credit balance"
         * entry: paying more than the outstanding amount is refused with an explanation instead of
         * silently accepted. This keeps `Remaining >= 0` a hard, provable invariant.
         */
        fun validate(
            amountMinor: Long,
            originalMinor: Long,
            paidMinor: Long,
            cancelled: Boolean = false,
        ): PaymentValidation {
            if (cancelled) {
                return Rejected(
                    "This record is cancelled. Restore it first, then record payments against it.",
                    Kind.ObligationCancelled,
                )
            }
            if (amountMinor <= 0L) {
                return Rejected("Enter an amount greater than zero.", Kind.AmountTooSmall)
            }
            val balance = FinancialBalance(originalMinor, paidMinor)
            if (balance.isSettled) {
                return Rejected(
                    "Nothing is outstanding on this record — it is already fully paid.",
                    Kind.NothingOutstanding,
                )
            }
            if (amountMinor > balance.remainingMinor) {
                return Rejected(
                    "That is more than the remaining balance. The largest payment allowed is the " +
                        "remaining amount.",
                    Kind.ExceedsRemaining,
                )
            }
            return Accepted(amountMinor)
        }
    }
}

/**
 * Applies an obligation's paid total across its installment schedule and returns the per-line
 * status.
 *
 * Allocation order is chronological and greedy (installment 1 fills first). This is the same
 * convention banks use when they apply a payment to a loan, and it has the property that matters
 * most here: the sum of the allocations never exceeds the obligation's paid total (on anything this
 * app writes), so the schedule and the headline balance can never disagree.
 *
 * A line's own `paidMinor` is **part of** that paid total, never an addition to it: every payment
 * recorded against an installment is also a payment against the obligation, so the money left to
 * spread over the schedule is `totalPaidMinor - SUM(lines' own paid)`. Feeding the whole paid total
 * into the pool as well would count line payments twice and show instalments as settled that were
 * never paid for.
 */
data class AllocatedInstallment(
    val installment: Installment,
    val allocatedMinor: Long,
    val status: InstallmentStatus,
) {
    val shortfallMinor: Long get() = (installment.scheduledMinor - allocatedMinor).coerceAtLeast(0L)
    val isSettled: Boolean get() = allocatedMinor >= installment.scheduledMinor && installment.scheduledMinor > 0L
}

object InstallmentAllocator {

    fun allocate(
        installments: List<Installment>,
        totalPaidMinor: Long,
        todayEpochDay: Long,
    ): List<AllocatedInstallment> {
        require(totalPaidMinor >= 0L) { "totalPaidMinor must not be negative" }
        if (installments.isEmpty()) return emptyList()

        val ordered = installments.sortedWith(
            compareBy({ it.dueDateEpochDay }, { it.number }),
        )
        // The obligation's paid total already contains every payment that was booked against a line, so
        // the money still free to move across the schedule is what is left after those lines have been
        // honoured. Clamped at zero rather than allowed to go negative: a restored file can carry line
        // figures the ledger does not support, and in that case the lines simply keep what they say.
        val paidOnLines = installments.sumOf { if (it.paidMinor > 0L) it.paidMinor else 0L }
        var pool = if (totalPaidMinor > paidOnLines) totalPaidMinor - paidOnLines else 0L
        return ordered.map { installment ->
            // Money paid directly at the installment line counts first; the rest comes from the
            // obligation-level pool.
            val direct = installment.paidMinor.coerceIn(0L, installment.scheduledMinor)
            val fromPool = minOf(pool, (installment.scheduledMinor - direct).coerceAtLeast(0L))
            pool -= fromPool
            val allocated = (direct + fromPool).coerceAtMost(installment.scheduledMinor)
            AllocatedInstallment(
                installment = installment,
                allocatedMinor = allocated,
                status = InstallmentStatus.of(
                    scheduledMinor = installment.scheduledMinor,
                    allocatedMinor = allocated,
                    dueDateEpochDay = installment.dueDateEpochDay,
                    todayEpochDay = todayEpochDay,
                ),
            )
        }
    }

    /** Sum of scheduled amounts for unpaid/partial installments — the schedule's remaining total. */
    fun remainingMinor(allocated: List<AllocatedInstallment>): Long =
        allocated.sumOf { it.shortfallMinor }

    fun countPaid(allocated: List<AllocatedInstallment>): Int = allocated.count { it.isSettled }

    fun nextDue(allocated: List<AllocatedInstallment>, todayEpochDay: Long): AllocatedInstallment? =
        allocated.filter { !it.isSettled && it.installment.dueDateEpochDay >= todayEpochDay }
            .minByOrNull { it.installment.dueDateEpochDay }

    fun overdue(allocated: List<AllocatedInstallment>, todayEpochDay: Long): List<AllocatedInstallment> =
        allocated.filter { !it.isSettled && it.installment.dueDateEpochDay < todayEpochDay }
            .sortedBy { it.installment.dueDateEpochDay }
}

/**
 * Sum of a payment ledger, clamped so the result can never exceed the obligation it belongs to.
 *
 * The clamp is not a fudge: the DAO layer already rejects overpayments at write time, but a
 * balance must stay correct even for data that arrived from a restore, so the derived value is
 * defensive by construction.
 */
fun clampPaid(originalMinor: Long, paidSumMinor: Long): Long =
    when {
        originalMinor <= 0L -> 0L
        paidSumMinor <= 0L -> 0L
        paidSumMinor > originalMinor -> originalMinor
        else -> paidSumMinor
    }

fun balanceOf(originalMinor: Long, paidSumMinor: Long): FinancialBalance =
    FinancialBalance(originalMinor, clampPaid(originalMinor, paidSumMinor))

/**
 * Totals for a whole module or for the dashboard, and the invariant check the app runs before
 * showing them: the headline number must equal the sum of the rows underneath it.
 */
data class OutstandingTotals(
    val originalMinor: Long = 0L,
    val paidMinor: Long = 0L,
) {
    val remainingMinor: Long get() = (originalMinor - paidMinor).coerceAtLeast(0L)
    val isEmpty: Boolean get() = originalMinor == 0L && paidMinor == 0L

    operator fun plus(other: OutstandingTotals) =
        OutstandingTotals(originalMinor + other.originalMinor, paidMinor + other.paidMinor)

    companion object {
        fun from(balances: List<FinancialBalance>): OutstandingTotals = OutstandingTotals(
            originalMinor = balances.sumOf { it.originalMinor },
            paidMinor = balances.sumOf { it.paidMinor },
        )
    }
}
