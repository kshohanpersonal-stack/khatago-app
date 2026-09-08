package com.khatago.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The unified "what is due" queue.
 *
 * Everything that needs to answer "what must I pay next" — the Payment Center, the dashboard's Due
 * Soon / Overdue tiles, the daily reminder worker and the reports — reads from these queries, so
 * the same rows and the same totals appear everywhere by construction. A dashboard tile computed
 * separately from the notification job is exactly the kind of inconsistency that destroys trust in
 * a money app.
 *
 * Amount semantics per branch:
 *  - installments: the scheduled amount of that line (an installment is a fixed obligation).
 *  - shop credit / borrowing / lending: the still-outstanding remainder of that record.
 */
@Dao
interface DueDao {

    @Query(
        """
        SELECT * FROM (
            SELECT
                i.ownerType AS payableType,
                i.ownerId AS payableId,
                COALESCE(l.loanName, e.productName, '') AS title,
                COALESCE(l.institution, e.merchant, '') || ' · installment ' || i.number AS subtitle,
                i.dueDateEpochDay AS dueDateEpochDay,
                i.scheduledAmountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                                          WHERE p.installmentId = i.id) AS amountMinor,
                i.scheduledAmountMinor AS totalMinor,
                (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                 WHERE p.installmentId = i.id) AS paidMinor
            FROM installments i
            LEFT JOIN loans l ON i.ownerType = 'loan' AND l.id = i.ownerId AND l.cancelled = 0
            LEFT JOIN emi_purchases e ON i.ownerType = 'emi' AND e.id = i.ownerId AND e.cancelled = 0
            WHERE (l.id IS NOT NULL OR e.id IS NOT NULL)
              AND i.scheduledAmountMinor > (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                                            WHERE p.installmentId = i.id)

            UNION ALL

            SELECT
                'shop_credit' AS payableType,
                c.id AS payableId,
                c.productName AS title,
                s.name AS subtitle,
                c.dueDateEpochDay AS dueDateEpochDay,
                c.totalAmountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                                      WHERE p.payableType = 'shop_credit' AND p.payableId = c.id) AS amountMinor,
                c.totalAmountMinor AS totalMinor,
                (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                 WHERE p.payableType = 'shop_credit' AND p.payableId = c.id) AS paidMinor
            FROM shop_credits c
            JOIN shops s ON s.id = c.shopId
            WHERE c.cancelled = 0 AND c.dueDateEpochDay IS NOT NULL
              AND c.totalAmountMinor > (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                                        WHERE p.payableType = 'shop_credit' AND p.payableId = c.id)

            UNION ALL

            SELECT
                'borrowing' AS payableType,
                b.id AS payableId,
                p.name AS title,
                'You borrowed' AS subtitle,
                b.dueDateEpochDay AS dueDateEpochDay,
                b.amountMinor - (SELECT COALESCE(SUM(pay.amountMinor), 0) FROM payments pay
                                 WHERE pay.payableType = 'borrowing' AND pay.payableId = b.id) AS amountMinor,
                b.amountMinor AS totalMinor,
                (SELECT COALESCE(SUM(pay.amountMinor), 0) FROM payments pay
                 WHERE pay.payableType = 'borrowing' AND pay.payableId = b.id) AS paidMinor
            FROM borrowings b
            JOIN people p ON p.id = b.personId
            WHERE b.cancelled = 0 AND b.dueDateEpochDay IS NOT NULL
              AND b.amountMinor > (SELECT COALESCE(SUM(pay.amountMinor), 0) FROM payments pay
                                   WHERE pay.payableType = 'borrowing' AND pay.payableId = b.id)

            UNION ALL

            SELECT
                'lending' AS payableType,
                ld.id AS payableId,
                p.name AS title,
                'They borrowed from you' AS subtitle,
                ld.dueDateEpochDay AS dueDateEpochDay,
                ld.amountMinor - (SELECT COALESCE(SUM(pay.amountMinor), 0) FROM payments pay
                                  WHERE pay.payableType = 'lending' AND pay.payableId = ld.id) AS amountMinor,
                ld.amountMinor AS totalMinor,
                (SELECT COALESCE(SUM(pay.amountMinor), 0) FROM payments pay
                 WHERE pay.payableType = 'lending' AND pay.payableId = ld.id) AS paidMinor
            FROM lendings ld
            JOIN people p ON p.id = ld.personId
            WHERE ld.cancelled = 0 AND ld.dueDateEpochDay IS NOT NULL
              AND ld.amountMinor > (SELECT COALESCE(SUM(pay.amountMinor), 0) FROM payments pay
                                    WHERE pay.payableType = 'lending' AND pay.payableId = ld.id)
        )
        WHERE dueDateEpochDay IS NOT NULL
          AND amountMinor > 0
          AND (:startEpochDay IS NULL OR dueDateEpochDay >= :startEpochDay)
          AND (:endEpochDay IS NULL OR dueDateEpochDay <= :endEpochDay)
        ORDER BY dueDateEpochDay ASC, payableType ASC, payableId ASC
        """
    )
    fun observeBetween(startEpochDay: Long?, endEpochDay: Long?): Flow<List<DueRow>>

    @Query(
        """
        SELECT COUNT(*) AS count, COALESCE(SUM(amountMinor), 0) AS totalMinor
        FROM (
            SELECT
                i.dueDateEpochDay AS dueDateEpochDay,
                i.scheduledAmountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                                          WHERE p.installmentId = i.id) AS amountMinor
            FROM installments i
            JOIN loans l ON i.ownerType = 'loan' AND l.id = i.ownerId AND l.cancelled = 0
            UNION ALL
            SELECT
                i.dueDateEpochDay AS dueDateEpochDay,
                i.scheduledAmountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                                          WHERE p.installmentId = i.id) AS amountMinor
            FROM installments i
            JOIN emi_purchases e ON i.ownerType = 'emi' AND e.id = i.ownerId AND e.cancelled = 0
            UNION ALL
            SELECT
                c.dueDateEpochDay AS dueDateEpochDay,
                c.totalAmountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                                      WHERE p.payableType = 'shop_credit' AND p.payableId = c.id) AS amountMinor
            FROM shop_credits c WHERE c.cancelled = 0
            UNION ALL
            SELECT
                b.dueDateEpochDay AS dueDateEpochDay,
                b.amountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                                 WHERE p.payableType = 'borrowing' AND p.payableId = b.id) AS amountMinor
            FROM borrowings b WHERE b.cancelled = 0
            UNION ALL
            SELECT
                l.dueDateEpochDay AS dueDateEpochDay,
                l.amountMinor - (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                                 WHERE p.payableType = 'lending' AND p.payableId = l.id) AS amountMinor
            FROM lendings l WHERE l.cancelled = 0
        )
        WHERE dueDateEpochDay IS NOT NULL
          AND amountMinor > 0
          AND (:startEpochDay IS NULL OR dueDateEpochDay >= :startEpochDay)
          AND (:endEpochDay IS NULL OR dueDateEpochDay <= :endEpochDay)
        """
    )
    fun observeSummaryBetween(startEpochDay: Long?, endEpochDay: Long?): Flow<DueSummaryRow>

}
