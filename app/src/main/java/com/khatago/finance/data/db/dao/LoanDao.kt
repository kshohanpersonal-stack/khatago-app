package com.khatago.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.khatago.finance.data.db.entity.InstallmentEntity
import com.khatago.finance.data.db.entity.LoanEntity
import kotlinx.coroutines.flow.Flow

/**
 * Bank / NGO loans.
 *
 * A loan's paid total is the sum of every payment recorded against the loan, whether the user
 * recorded it against the loan as a whole or against a specific installment line — that is why the
 * balance query below does not touch `installments.paidMinor` at all. Installment-level allocation
 * is computed in the domain layer (`InstallmentAllocator`) from the same single source of truth.
 */
@Dao
interface LoanDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(loan: LoanEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(loans: List<LoanEntity>): List<Long>

    @Update
    suspend fun update(loan: LoanEntity)

    @Query("DELETE FROM loans WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM loans")
    suspend fun deleteAll()

    @Query("SELECT * FROM loans WHERE id = :id")
    suspend fun findById(id: Long): LoanEntity?

    @Query("SELECT * FROM loans WHERE id = :id")
    fun observeById(id: Long): Flow<LoanEntity?>

    @Query("SELECT * FROM loans ORDER BY endDateEpochDay IS NULL, endDateEpochDay ASC, id DESC")
    fun observeAll(): Flow<List<LoanEntity>>

    @Query("SELECT * FROM loans")
    suspend fun findAll(): List<LoanEntity>

    @Query("SELECT COUNT(*) FROM loans")
    suspend fun count(): Int

    /**
     * Loan balances including the down payment, which is treated exactly like a first payment made
     * on day one. That makes the arithmetic uniform: `remaining = totalPayable - (down payment +
     * recorded payments)`, so a loan where the down payment alone settles everything correctly
     * shows as settled with no installments outstanding.
     */
    @Query(
        """
        SELECT
            l.id AS id,
            l.loanName AS title,
            l.institution AS subtitle,
            l.totalPayableMinor + l.downPaymentMinor AS totalMinor,
            l.downPaymentMinor + COALESCE((SELECT SUM(p.amountMinor) FROM payments p
                       WHERE p.payableType = 'loan' AND p.payableId = l.id), 0) AS paidMinor,
            (SELECT MIN(i.dueDateEpochDay) FROM installments i
              WHERE i.ownerType = 'loan' AND i.ownerId = l.id
                AND i.dueDateEpochDay >= :today
                AND i.scheduledAmountMinor > (
                    SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                    WHERE p.installmentId = i.id)) AS dueDateEpochDay,
            l.cancelled AS cancelled
        FROM loans l
        WHERE (:hideCancelled = 0 OR l.cancelled = 0)
        ORDER BY l.endDateEpochDay IS NULL, l.endDateEpochDay ASC, l.id DESC
        """,
    )
    fun observeBalances(today: Long, hideCancelled: Boolean): Flow<List<ObligationRow>>

    @Query(
        """
        SELECT
            l.id AS id,
            l.loanName AS title,
            l.institution AS subtitle,
            l.totalPayableMinor + l.downPaymentMinor AS totalMinor,
            l.downPaymentMinor + COALESCE((SELECT SUM(p.amountMinor) FROM payments p
                       WHERE p.payableType = 'loan' AND p.payableId = l.id), 0) AS paidMinor,
            (SELECT MIN(i.dueDateEpochDay) FROM installments i
              WHERE i.ownerType = 'loan' AND i.ownerId = l.id
                AND i.dueDateEpochDay >= :today
                AND i.scheduledAmountMinor > (
                    SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                    WHERE p.installmentId = i.id)) AS dueDateEpochDay,
            l.cancelled AS cancelled
        FROM loans l
        WHERE l.id = :id
        """,
    )
    fun observeBalance(id: Long, today: Long): Flow<ObligationRow?>

    /** Outstanding total across all loans — used by the dashboard breakdown. */
    @Query(
        """
        SELECT COALESCE(SUM(loanRemaining.totalMinor - loanRemaining.paidMinor), 0) AS totalMinor
        FROM (
            SELECT l.totalPayableMinor + l.downPaymentMinor AS totalMinor,
                   l.downPaymentMinor + (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                                          WHERE p.payableType = 'loan' AND p.payableId = l.id) AS paidMinor
            FROM loans l
            WHERE l.cancelled = 0
        ) loanRemaining
        WHERE loanRemaining.totalMinor - loanRemaining.paidMinor > 0
        """,
    )
    fun observeOutstanding(): Flow<Long>

    @Query(
        """
        SELECT COALESCE(SUM(l.principalMinor), 0) AS totalMinor
        FROM loans l WHERE l.cancelled = 0
          AND l.downPaymentMinor + (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                                     WHERE p.payableType = 'loan' AND p.payableId = l.id)
              < l.totalPayableMinor + l.downPaymentMinor
        """,
    )
    fun observeActivePrincipal(): Flow<Long>

    /** Number of installments still open across all loans (the "which payments are due" metric). */
    @Query(
        """
        SELECT COUNT(*) FROM installments i
        WHERE i.ownerType = 'loan'
          AND i.dueDateEpochDay <= :endEpochDay
          AND i.scheduledAmountMinor > (
              SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p WHERE p.installmentId = i.id)
        """,
    )
    fun observeUnsettledInstallmentsThrough(endEpochDay: Long): Flow<Int>

    // --- payments + cascade for this loan -------------------------------------

    @Query("SELECT * FROM payments WHERE payableType = 'loan' AND payableId = :loanId ORDER BY paidDateEpochDay DESC, id DESC")
    fun observePayments(loanId: Long): Flow<List<com.khatago.finance.data.db.entity.PaymentEntity>>

    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) - :downPayment FROM payments
        WHERE payableType = 'loan' AND payableId = :loanId AND id != :excludingPaymentId
        """,
    )
    suspend fun paidExcludingPayment(loanId: Long, downPayment: Long, excludingPaymentId: Long): Long

    @Query("SELECT id FROM installments WHERE ownerType = 'loan' AND ownerId = :loanId")
    suspend fun findInstallmentIds(loanId: Long): List<Long>

    @Query(
        """
        DELETE FROM payments
        WHERE (payableType = 'loan' AND payableId = :loanId)
           OR installmentId IN (:installmentIds)
        """,
    )
    suspend fun deleteLoanPayments(loanId: Long, installmentIds: List<Long>)

    /**
     * A loan carries its own schedule; deleting it must remove the schedule and every payment
     * recorded against it in one transaction, otherwise orphaned payments would silently attach to
     * the next loan that happens to reuse the id.
     */
    @Transaction
    suspend fun deleteWithSchedule(loanId: Long) {
        val installmentIds = findInstallmentIds(loanId)
        deleteLoanPayments(loanId, installmentIds)
        if (installmentIds.isNotEmpty()) {
            deleteInstallmentsByIds(installmentIds)
        }
        deleteById(loanId)
    }

    @Query("DELETE FROM installments WHERE id IN (:ids)")
    suspend fun deleteInstallmentsByIds(ids: List<Long>)

    @Query("SELECT * FROM installments WHERE ownerType = 'loan' AND ownerId = :loanId ORDER BY number ASC")
    fun observeInstallments(loanId: Long): Flow<List<InstallmentEntity>>

    @Query("SELECT * FROM installments WHERE ownerType = 'loan' AND ownerId = :loanId ORDER BY number ASC")
    suspend fun findInstallments(loanId: Long): List<InstallmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInstallments(installments: List<InstallmentEntity>)

    @Query("DELETE FROM installments WHERE ownerType = 'loan' AND ownerId = :loanId")
    suspend fun clearSchedule(loanId: Long)
}
