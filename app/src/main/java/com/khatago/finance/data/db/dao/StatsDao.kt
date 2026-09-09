package com.khatago.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Cross-module aggregates: the dashboard's headline tiles and the analytics breakdowns.
 *
 * These queries deliberately mirror the per-module queries rather than introducing a shared
 * materialised "totals" table. The duplication is small and visible; the alternative (a cached
 * totals table) is a second source of truth that can lag behind the ledger, and a stale headline
 * number in a money app is worse than an extra 20 lines of SQL. The invariant test asserts the
 * dashboard totals equal the sum of the module rows so the mirroring cannot silently drift.
 *
 * Every branch clamps at the record level (`remaining > 0`) *and* filters cancelled records, which
 * is what keeps "Total I owe" from being reduced by a mistake elsewhere in the ledger.
 */
@Dao
interface StatsDao {

    /**
     * Total the user owes: unsettled remainders across all four "I owe" sources.
     *
     * Down payments follow **one** convention here and in `PayableResolver.resolve`, `LoanDao`,
     * `EmiDao`, `BackupValidation` and the CSV export, and every balance in the app derives from it:
     *
     *     paid      = downPayment + SUM(payments)        (both plans)
     *     remaining = original - paid
     *
     * What differs is `original`, because `totalPayableMinor` means something different per plan, and
     * that is the trap:
     *
     *     loan: original = totalPayable + downPayment    (a loan's totalPayable EXCLUDES the down)
     *     emi:  original = totalPayable                  (an EMI's totalPayable already INCLUDES it)
     *
     * A loan's schedule is generated from `totalPayable`, so its headline has to add the down payment
     * back to reach the whole cost. An EMI's schedule is generated from `totalPayable - downPayment`
     * because the total already contains the down payment, and adding it to the total as well counts it
     * twice and makes the plan look one instalment bigger than the user's. Omitting it from the paid
     * side is the mirror-image mistake, and the more dangerous one: a loan whose EMIs are all paid keeps
     * reporting its down payment as outstanding, so its tile can never reach zero. Both halves are
     * spelled out instead of cancelled, because the cancelled form is what invites a reader to
     * "simplify" one side and double-count a down payment.
     *
     * A down payment is metadata, never a `payments` row (the form says so in its helper). If a user
     * also records it as a payment, it is counted as paid twice — and the payment guard, which uses
     * the same identity, refuses the overpayment that would otherwise open a credit balance.
     */
    @Query(
        """
        SELECT COALESCE(SUM(remaining), 0) FROM (
            SELECT c.totalAmountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                    WHERE p.payableType = 'shop_credit' AND p.payableId = c.id) AS remaining
            FROM shop_credits c WHERE c.cancelled = 0
            UNION ALL
            SELECT (l.totalPayableMinor + l.downPaymentMinor) - (l.downPaymentMinor +
                    (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                     WHERE p.payableType = 'loan' AND p.payableId = l.id)) AS remaining
            FROM loans l WHERE l.cancelled = 0
            UNION ALL
            SELECT e.totalPayableMinor - (e.downPaymentMinor +
                    (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                     WHERE p.payableType = 'emi' AND p.payableId = e.id)) AS remaining
            FROM emi_purchases e WHERE e.cancelled = 0
            UNION ALL
            SELECT b.amountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                    WHERE p.payableType = 'borrowing' AND p.payableId = b.id) AS remaining
            FROM borrowings b WHERE b.cancelled = 0
        )
        WHERE remaining > 0
        """,
    )
    fun observeTotalIOwe(): Flow<Long>

    /** Total owed *to* the user, from lendings only — never netted against what the user owes. */
    @Query(
        """
        SELECT COALESCE(SUM(remaining), 0) FROM (
            SELECT l.amountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                    WHERE p.payableType = 'lending' AND p.payableId = l.id) AS remaining
            FROM lendings l WHERE l.cancelled = 0
        )
        WHERE remaining > 0
        """,
    )
    fun observeTotalOwedToMe(): Flow<Long>

    /** Overdue obligations across every module (remainder-based and schedule-based together). */
    @Query(
        """
        SELECT COALESCE(SUM(remaining), 0) FROM (
            SELECT c.totalAmountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                    WHERE p.payableType = 'shop_credit' AND p.payableId = c.id) AS remaining
            FROM shop_credits c WHERE c.cancelled = 0 AND c.dueDateEpochDay < :today
            UNION ALL
            SELECT b.amountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                    WHERE p.payableType = 'borrowing' AND p.payableId = b.id) AS remaining
            FROM borrowings b WHERE b.cancelled = 0 AND b.dueDateEpochDay < :today
            UNION ALL
            SELECT l.amountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                    WHERE p.payableType = 'lending' AND p.payableId = l.id) AS remaining
            FROM lendings l WHERE l.cancelled = 0 AND l.dueDateEpochDay < :today
            UNION ALL
            SELECT i.scheduledAmountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                    WHERE p.installmentId = i.id) AS remaining
            FROM installments i WHERE i.dueDateEpochDay < :today
        )
        WHERE remaining > 0
        """,
    )
    fun observeOverdueAmount(today: Long): Flow<Long>

    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT c.id AS id FROM shop_credits c
              WHERE c.cancelled = 0 AND c.dueDateEpochDay < :today
                AND c.totalAmountMinor > (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                                          WHERE p.payableType = 'shop_credit' AND p.payableId = c.id)
            UNION ALL
            SELECT b.id AS id FROM borrowings b
              WHERE b.cancelled = 0 AND b.dueDateEpochDay < :today
                AND b.amountMinor > (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                                     WHERE p.payableType = 'borrowing' AND p.payableId = b.id)
            UNION ALL
            SELECT l.id AS id FROM lendings l
              WHERE l.cancelled = 0 AND l.dueDateEpochDay < :today
                AND l.amountMinor > (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                                     WHERE p.payableType = 'lending' AND p.payableId = l.id)
            UNION ALL
            SELECT i.id AS id FROM installments i
              WHERE i.dueDateEpochDay < :today
                AND i.scheduledAmountMinor > (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                                              WHERE p.installmentId = i.id)
        )
        """,
    )
    fun observeOverdueCount(today: Long): Flow<Int>

    /** Outstanding per module, for the dashboard breakdown and the Analytics debt chart. */
    @Query(
        """
        SELECT COALESCE(SUM(remaining), 0) FROM (
            SELECT c.totalAmountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                    WHERE p.payableType = 'shop_credit' AND p.payableId = c.id) AS remaining
            FROM shop_credits c JOIN shops s ON s.id = c.shopId WHERE c.cancelled = 0
        )
        WHERE remaining > 0
        """,
    )
    fun observeShopCreditOutstanding(): Flow<Long>

    @Query(
        """
        SELECT COALESCE(SUM(remaining), 0) FROM (
            SELECT (l.totalPayableMinor + l.downPaymentMinor) - (l.downPaymentMinor +
                    (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                     WHERE p.payableType = 'loan' AND p.payableId = l.id)) AS remaining
            FROM loans l WHERE l.cancelled = 0
        )
        WHERE remaining > 0
        """,
    )
    fun observeLoanOutstanding(): Flow<Long>

    @Query(
        """
        SELECT COALESCE(SUM(remaining), 0) FROM (
            SELECT e.totalPayableMinor - (e.downPaymentMinor +
                    (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                     WHERE p.payableType = 'emi' AND p.payableId = e.id)) AS remaining
            FROM emi_purchases e WHERE e.cancelled = 0
        )
        WHERE remaining > 0
        """,
    )
    fun observeEmiOutstanding(): Flow<Long>

    @Query(
        """
        SELECT COALESCE(SUM(remaining), 0) FROM (
            SELECT b.amountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                    WHERE p.payableType = 'borrowing' AND p.payableId = b.id) AS remaining
            FROM borrowings b WHERE b.cancelled = 0
        )
        WHERE remaining > 0
        """,
    )
    fun observeBorrowingOutstanding(): Flow<Long>

    /**
     * The unified activity stream: obligations created, payments made, income and expense.
     * Dates are epoch days so everything sorts on one axis; `typeKey` drives the icon and route.
     */
    @Query(
        """
        SELECT * FROM (
            SELECT 'shop_credit' AS typeKey, c.id AS id, c.productName AS title, s.name AS subtitle,
                   c.totalAmountMinor AS amountMinor, c.purchaseDateEpochDay AS dateEpochDay,
                   c.updatedAt AS sortKey
            FROM shop_credits c JOIN shops s ON s.id = c.shopId
            UNION ALL
            SELECT 'loan' AS typeKey, l.id AS id, l.loanName AS title, l.institution AS subtitle,
                   l.totalPayableMinor AS amountMinor, l.disbursementDateEpochDay AS dateEpochDay,
                   l.updatedAt AS sortKey
            FROM loans l
            UNION ALL
            SELECT 'emi' AS typeKey, e.id AS id, e.productName AS title, e.merchant AS subtitle,
                   e.totalPayableMinor AS amountMinor, e.purchaseDateEpochDay AS dateEpochDay,
                   e.updatedAt AS sortKey
            FROM emi_purchases e
            UNION ALL
            SELECT 'borrowing' AS typeKey, b.id AS id, p.name AS title, 'You borrowed' AS subtitle,
                   b.amountMinor AS amountMinor, b.borrowDateEpochDay AS dateEpochDay,
                   b.updatedAt AS sortKey
            FROM borrowings b JOIN people p ON p.id = b.personId
            UNION ALL
            SELECT 'lending' AS typeKey, ld.id AS id, p.name AS title, 'You lent' AS subtitle,
                   ld.amountMinor AS amountMinor, ld.lendDateEpochDay AS dateEpochDay,
                   ld.updatedAt AS sortKey
            FROM lendings ld JOIN people p ON p.id = ld.personId
            UNION ALL
            SELECT 'income' AS typeKey, i.id AS id, COALESCE(i.source, i.categoryName) AS title,
                   i.categoryName AS subtitle, i.amountMinor AS amountMinor,
                   i.transactionDateEpochDay AS dateEpochDay, i.updatedAt AS sortKey
            FROM incomes i
            UNION ALL
            SELECT 'expense' AS typeKey, ex.id AS id, COALESCE(ex.merchant, ex.categoryName) AS title,
                   ex.categoryName AS subtitle, ex.amountMinor AS amountMinor,
                   ex.transactionDateEpochDay AS dateEpochDay, ex.updatedAt AS sortKey
            FROM expenses ex
            UNION ALL
            SELECT 'payment' AS typeKey, pm.id AS id, 'Payment · ' || pm.methodName AS title,
                   pm.payableType || ':' || pm.payableId AS subtitle,
                   pm.amountMinor AS amountMinor, pm.paidDateEpochDay AS dateEpochDay,
                   pm.updatedAt AS sortKey
            FROM payments pm
        )
        ORDER BY dateEpochDay DESC, sortKey DESC, id DESC
        LIMIT :limit
        """,
    )
    fun observeRecentActivity(limit: Int): Flow<List<ActivityRow>>

    /**
     * Record counts for Data management, backup metadata and the About screen.
     * One query on purpose: it runs when the user opens a settings screen, not on a hot path.
     */
    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM shops) || '/' ||
            (SELECT COUNT(*) FROM shop_credits) || '/' ||
            (SELECT COUNT(*) FROM people) || '/' ||
            (SELECT COUNT(*) FROM borrowings) || '/' ||
            (SELECT COUNT(*) FROM lendings) || '/' ||
            (SELECT COUNT(*) FROM loans) || '/' ||
            (SELECT COUNT(*) FROM emi_purchases) || '/' ||
            (SELECT COUNT(*) FROM installments) || '/' ||
            (SELECT COUNT(*) FROM payments) || '/' ||
            (SELECT COUNT(*) FROM incomes) || '/' ||
            (SELECT COUNT(*) FROM expenses) || '/' ||
            (SELECT COUNT(*) FROM attachments) || '/' ||
            (SELECT COUNT(*) FROM reminders) AS counts
        """,
    )
    suspend fun recordCounts(): String

    // --- one-shot reads for export/reports (the same SQL as the flows above) -----

    @Query(
        """
        SELECT COALESCE(SUM(remaining), 0) FROM (
            SELECT c.totalAmountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                    WHERE p.payableType = 'shop_credit' AND p.payableId = c.id) AS remaining
            FROM shop_credits c WHERE c.cancelled = 0
            UNION ALL
            SELECT (l.totalPayableMinor + l.downPaymentMinor) - (l.downPaymentMinor +
                    (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                     WHERE p.payableType = 'loan' AND p.payableId = l.id)) AS remaining
            FROM loans l WHERE l.cancelled = 0
            UNION ALL
            SELECT e.totalPayableMinor - (e.downPaymentMinor +
                    (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                     WHERE p.payableType = 'emi' AND p.payableId = e.id)) AS remaining
            FROM emi_purchases e WHERE e.cancelled = 0
            UNION ALL
            SELECT b.amountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                    WHERE p.payableType = 'borrowing' AND p.payableId = b.id) AS remaining
            FROM borrowings b WHERE b.cancelled = 0
        )
        WHERE remaining > 0
        """,
    )
    suspend fun totalIOweOnce(): Long

    @Query(
        """
        SELECT COALESCE(SUM(remaining), 0) FROM (
            SELECT l.amountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                    WHERE p.payableType = 'lending' AND p.payableId = l.id) AS remaining
            FROM lendings l WHERE l.cancelled = 0
        )
        WHERE remaining > 0
        """,
    )
    suspend fun totalOwedToMeOnce(): Long

    @Query(
        """
        SELECT COALESCE(SUM(remaining), 0) FROM (
            SELECT c.totalAmountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                    WHERE p.payableType = 'shop_credit' AND p.payableId = c.id) AS remaining
            FROM shop_credits c WHERE c.cancelled = 0 AND c.dueDateEpochDay < :today
            UNION ALL
            SELECT b.amountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                    WHERE p.payableType = 'borrowing' AND p.payableId = b.id) AS remaining
            FROM borrowings b WHERE b.cancelled = 0 AND b.dueDateEpochDay < :today
            UNION ALL
            SELECT l.amountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                    WHERE p.payableType = 'lending' AND p.payableId = l.id) AS remaining
            FROM lendings l WHERE l.cancelled = 0 AND l.dueDateEpochDay < :today
            UNION ALL
            SELECT i.scheduledAmountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                    WHERE p.installmentId = i.id) AS remaining
            FROM installments i WHERE i.dueDateEpochDay < :today
        )
        WHERE remaining > 0
        """,
    )
    suspend fun overdueAmountOnce(today: Long): Long

    /** Total paid in a window — "Paid this month" on the dashboard. */
    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM payments
        WHERE paidDateEpochDay BETWEEN :startEpochDay AND :endEpochDay
        """,
    )
    fun observePaidBetween(startEpochDay: Long, endEpochDay: Long): Flow<Long>
}
