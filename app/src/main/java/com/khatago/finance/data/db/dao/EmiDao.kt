package com.khatago.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.khatago.finance.data.db.entity.EmiPurchaseEntity
import com.khatago.finance.data.db.entity.InstallmentEntity
import com.khatago.finance.data.db.entity.PaymentEntity
import kotlinx.coroutines.flow.Flow

/**
 * EMI / installment purchases.
 *
 * Balance rule (shared with loans so the two can never drift apart):
 *   committed  = totalPayable
 *   paid       = down payment + every payment recorded against the purchase
 *   remaining  = committed - paid, clamped at zero
 * `cashPriceMinor` is kept for reference only (so the user can see what the plan cost them); it
 * never enters the balance arithmetic.
 */
@Dao
interface EmiDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(emi: EmiPurchaseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<EmiPurchaseEntity>): List<Long>

    @Update
    suspend fun update(emi: EmiPurchaseEntity)

    @Query("DELETE FROM emi_purchases WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM emi_purchases")
    suspend fun deleteAll()

    @Query("SELECT * FROM emi_purchases WHERE id = :id")
    suspend fun findById(id: Long): EmiPurchaseEntity?

    @Query("SELECT * FROM emi_purchases WHERE id = :id")
    fun observeById(id: Long): Flow<EmiPurchaseEntity?>

    @Query("SELECT * FROM emi_purchases ORDER BY endDateEpochDay IS NULL, endDateEpochDay ASC, id DESC")
    fun observeAll(): Flow<List<EmiPurchaseEntity>>

    @Query("SELECT * FROM emi_purchases")
    suspend fun findAll(): List<EmiPurchaseEntity>

    @Query("SELECT COUNT(*) FROM emi_purchases")
    suspend fun count(): Int

    @Query(
        """
        SELECT
            e.id AS id,
            e.productName AS title,
            e.merchant AS subtitle,
            e.totalPayableMinor AS totalMinor,
            e.downPaymentMinor + COALESCE((SELECT SUM(p.amountMinor) FROM payments p
                       WHERE p.payableType = 'emi' AND p.payableId = e.id), 0) AS paidMinor,
            (SELECT MIN(i.dueDateEpochDay) FROM installments i
              WHERE i.ownerType = 'emi' AND i.ownerId = e.id
                AND i.dueDateEpochDay >= :today
                AND i.scheduledAmountMinor > (
                    SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                    WHERE p.installmentId = i.id)) AS dueDateEpochDay,
            e.cancelled AS cancelled
        FROM emi_purchases e
        WHERE (:hideCancelled = 0 OR e.cancelled = 0)
        ORDER BY e.endDateEpochDay IS NULL, e.endDateEpochDay ASC, e.id DESC
        """,
    )
    fun observeBalances(today: Long, hideCancelled: Boolean): Flow<List<ObligationRow>>

    @Query(
        """
        SELECT
            e.id AS id,
            e.productName AS title,
            e.merchant AS subtitle,
            e.totalPayableMinor AS totalMinor,
            e.downPaymentMinor + COALESCE((SELECT SUM(p.amountMinor) FROM payments p
                       WHERE p.payableType = 'emi' AND p.payableId = e.id), 0) AS paidMinor,
            (SELECT MIN(i.dueDateEpochDay) FROM installments i
              WHERE i.ownerType = 'emi' AND i.ownerId = e.id
                AND i.dueDateEpochDay >= :today
                AND i.scheduledAmountMinor > (
                    SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                    WHERE p.installmentId = i.id)) AS dueDateEpochDay,
            e.cancelled AS cancelled
        FROM emi_purchases e
        WHERE e.id = :id
        """,
    )
    fun observeBalance(id: Long, today: Long): Flow<ObligationRow?>

    @Query(
        """
        SELECT COALESCE(SUM(emiRemaining.totalMinor - emiRemaining.paidMinor), 0) AS totalMinor
        FROM (
            SELECT e.totalPayableMinor AS totalMinor,
                   e.downPaymentMinor + (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                                          WHERE p.payableType = 'emi' AND p.payableId = e.id) AS paidMinor
            FROM emi_purchases e
            WHERE e.cancelled = 0
        ) emiRemaining
        WHERE emiRemaining.totalMinor - emiRemaining.paidMinor > 0
        """,
    )
    fun observeOutstanding(): Flow<Long>

    @Query("SELECT * FROM payments WHERE payableType = 'emi' AND payableId = :emiId ORDER BY paidDateEpochDay DESC, id DESC")
    fun observePayments(emiId: Long): Flow<List<PaymentEntity>>

    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) - :downPayment FROM payments
        WHERE payableType = 'emi' AND payableId = :emiId AND id != :excludingPaymentId
        """,
    )
    suspend fun paidExcludingPayment(emiId: Long, downPayment: Long, excludingPaymentId: Long): Long

    @Query("SELECT id FROM installments WHERE ownerType = 'emi' AND ownerId = :emiId")
    suspend fun findInstallmentIds(emiId: Long): List<Long>

    @Query(
        """
        DELETE FROM payments
        WHERE (payableType = 'emi' AND payableId = :emiId)
           OR installmentId IN (:installmentIds)
        """,
    )
    suspend fun deleteEmiPayments(emiId: Long, installmentIds: List<Long>)

    @Transaction
    suspend fun deleteWithSchedule(emiId: Long) {
        val installmentIds = findInstallmentIds(emiId)
        deleteEmiPayments(emiId, installmentIds)
        if (installmentIds.isNotEmpty()) deleteInstallmentsByIds(installmentIds)
        deleteById(emiId)
    }

    @Query("DELETE FROM installments WHERE id IN (:ids)")
    suspend fun deleteInstallmentsByIds(ids: List<Long>)

    @Query("SELECT * FROM installments WHERE ownerType = 'emi' AND ownerId = :emiId ORDER BY number ASC")
    fun observeInstallments(emiId: Long): Flow<List<InstallmentEntity>>

    @Query("SELECT * FROM installments WHERE ownerType = 'emi' AND ownerId = :emiId ORDER BY number ASC")
    suspend fun findInstallments(emiId: Long): List<InstallmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInstallments(installments: List<InstallmentEntity>)

    @Query("DELETE FROM installments WHERE ownerType = 'emi' AND ownerId = :emiId")
    suspend fun clearSchedule(emiId: Long)
}
