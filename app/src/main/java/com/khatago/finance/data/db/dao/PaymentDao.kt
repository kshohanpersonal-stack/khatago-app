package com.khatago.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.khatago.finance.data.db.entity.PaymentEntity
import kotlinx.coroutines.flow.Flow

/**
 * The payment ledger.
 *
 * One table serves every obligation type. Adding a sixth obligation type (say, "rent deposit") is
 * therefore a matter of adding its own table plus a `payableType` key — never a new payments table,
 * a new balance column and a new set of drift bugs.
 */
@Dao
interface PaymentDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(payment: PaymentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(payments: List<PaymentEntity>): List<Long>

    @Update
    suspend fun update(payment: PaymentEntity)

    @Delete
    suspend fun delete(payment: PaymentEntity)

    @Query("DELETE FROM payments")
    suspend fun deleteAll()

    @Query("SELECT * FROM payments")
    suspend fun findAll(): List<PaymentEntity>

    @Query("SELECT * FROM payments WHERE id = :id")
    suspend fun findById(id: Long): PaymentEntity?

    @Query(
        """
        SELECT * FROM payments
        WHERE payableType = :payableType AND payableId = :payableId
        ORDER BY paidDateEpochDay DESC, id DESC
        """,
    )
    fun observeForPayable(payableType: String, payableId: Long): Flow<List<PaymentEntity>>

    @Query(
        """
        SELECT * FROM payments
        WHERE payableType = :payableType AND payableId = :payableId
        ORDER BY paidDateEpochDay DESC, id DESC
        """,
    )
    suspend fun findForPayable(payableType: String, payableId: Long): List<PaymentEntity>

    @Query(
        """
        SELECT * FROM payments WHERE installmentId = :installmentId
        ORDER BY paidDateEpochDay DESC, id DESC
        """,
    )
    suspend fun findForInstallment(installmentId: Long): List<PaymentEntity>

    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM payments
        WHERE payableType = :payableType AND payableId = :payableId
        """,
    )
    suspend fun paidTotal(payableType: String, payableId: Long): Long

    /**
     * Paid total excluding one payment id — the correct denominator when *editing* a payment, so
     * the new value is validated against everything except itself.
     */
    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM payments
        WHERE payableType = :payableType AND payableId = :payableId AND id != :excludingId
        """,
    )
    suspend fun paidTotalExcluding(payableType: String, payableId: Long, excludingId: Long): Long

    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM payments
        WHERE payableType = :payableType AND payableId = :payableId AND installmentId IS NOT NULL
        """,
    )
    suspend fun paidViaInstallments(payableType: String, payableId: Long): Long

    @Query(
        """
        SELECT * FROM payments ORDER BY paidDateEpochDay DESC, id DESC LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<PaymentEntity>>

    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM payments
        WHERE paidDateEpochDay BETWEEN :startEpochDay AND :endEpochDay
        """,
    )
    fun observePaidBetween(startEpochDay: Long, endEpochDay: Long): Flow<Long>

    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM payments
        WHERE paidDateEpochDay BETWEEN :startEpochDay AND :endEpochDay
        """,
    )
    suspend fun paidBetween(startEpochDay: Long, endEpochDay: Long): Long

    @Query(
        """
        SELECT paidDateEpochDay AS paidDateEpochDay,
               SUM(amountMinor) AS totalMinor,
               COUNT(*) AS recordCount
        FROM payments
        WHERE paidDateEpochDay BETWEEN :startEpochDay AND :endEpochDay
        GROUP BY paidDateEpochDay
        ORDER BY paidDateEpochDay ASC
        """,
    )
    fun observePaymentDays(startEpochDay: Long, endEpochDay: Long): Flow<List<PaymentDayTotalRow>>

    @Query(
        """
        SELECT paidDateEpochDay AS paidDateEpochDay,
               SUM(amountMinor) AS totalMinor,
               COUNT(*) AS recordCount
        FROM payments
        GROUP BY paidDateEpochDay
        ORDER BY paidDateEpochDay ASC
        """,
    )
    suspend fun allPaymentDays(): List<PaymentDayTotalRow>

    /**
     * Removes a payment and, if it was attached to an installment line, clears that line's own
     * paid counter back to what the remaining ledger supports. Kept in one transaction so the
     * schedule and the obligation total can never be observed mid-update.
     */
    @Transaction
    suspend fun deleteAndRepair(payment: PaymentEntity, newLinePaid: Long) {
        delete(payment)
        payment.installmentId?.let { installmentId ->
            updateInstallmentPaid(installmentId, newLinePaid)
        }
    }

    @Query("UPDATE installments SET paidMinor = :value WHERE id = :id")
    suspend fun updateInstallmentPaid(id: Long, value: Long)
}
