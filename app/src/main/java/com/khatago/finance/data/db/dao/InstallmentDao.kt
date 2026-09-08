package com.khatago.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.khatago.finance.data.db.entity.InstallmentEntity
import kotlinx.coroutines.flow.Flow

/**
 * The installment schedule table, shared by loans and EMI purchases.
 *
 * A schedule row is a *plan*, not a balance. Whether a line is settled is answered by summing the
 * payments attached to it; that is the only reason editing a payment needs no repair job anywhere
 * else in the app.
 */
@Dao
interface InstallmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(installments: List<InstallmentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(installment: InstallmentEntity): Long

    @Query("SELECT * FROM installments WHERE id = :id")
    suspend fun findById(id: Long): InstallmentEntity?

    @Query("SELECT * FROM installments")
    suspend fun findAll(): List<InstallmentEntity>

    @Query("DELETE FROM installments")
    suspend fun deleteAll()

    @Query("DELETE FROM installments WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun deleteForOwner(ownerType: String, ownerId: Long)

    /** Payments attached directly to one installment line. */
    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM payments
        WHERE installmentId = :installmentId
        """,
    )
    suspend fun paidAtLine(installmentId: Long): Long

    /**
     * Installments still open on or before [endEpochDay] (or with no upper bound when null), across
     * both loans and EMI purchases. This single query feeds the Payment Center's "Due Soon" and the
     * reminder worker, so a scheduled payment can never appear in one place and not the other.
     */
    @Query(
        """
        SELECT i.* FROM installments i
        WHERE i.dueDateEpochDay <= :endEpochDay
          AND i.scheduledAmountMinor > (
              SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p WHERE p.installmentId = i.id)
        ORDER BY i.dueDateEpochDay ASC, i.number ASC
        """,
    )
    fun observeOpenThrough(endEpochDay: Long): Flow<List<InstallmentEntity>>

    @Query(
        """
        SELECT i.* FROM installments i
        WHERE i.dueDateEpochDay <= :endEpochDay
          AND i.scheduledAmountMinor > (
              SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p WHERE p.installmentId = i.id)
        ORDER BY i.dueDateEpochDay ASC, i.number ASC
        """,
    )
    suspend fun findOpenThrough(endEpochDay: Long): List<InstallmentEntity>

    @Query(
        """
        SELECT COALESCE(SUM(i.scheduledAmountMinor - (
                SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p WHERE p.installmentId = i.id)), 0)
        FROM installments i
        WHERE i.dueDateEpochDay < :today
          AND i.scheduledAmountMinor > (
              SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p WHERE p.installmentId = i.id)
        """,
    )
    fun observeOverdueScheduledAmount(today: Long): Flow<Long>

    @Query("SELECT COUNT(*) FROM installments i WHERE i.dueDateEpochDay < :today")
    suspend fun countOverdueLines(today: Long): Int
}
