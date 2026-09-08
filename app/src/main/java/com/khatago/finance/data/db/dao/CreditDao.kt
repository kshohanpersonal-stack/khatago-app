package com.khatago.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.khatago.finance.data.db.entity.PaymentEntity
import com.khatago.finance.data.db.entity.ShopCreditEntity
import kotlinx.coroutines.flow.Flow

/**
 * Shop credit lines (what was bought on credit) and their payment-derived balances.
 */
@Dao
interface CreditDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(credit: ShopCreditEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(credits: List<ShopCreditEntity>): List<Long>

    @Update
    suspend fun update(credit: ShopCreditEntity)

    @Delete
    suspend fun delete(credit: ShopCreditEntity)

    @Query("SELECT * FROM shop_credits WHERE id = :id")
    suspend fun findById(id: Long): ShopCreditEntity?

    @Query("SELECT * FROM shop_credits WHERE id = :id")
    fun observeById(id: Long): Flow<ShopCreditEntity?>

    @Query("SELECT * FROM shop_credits WHERE shopId = :shopId ORDER BY purchaseDateEpochDay DESC, id DESC")
    fun observeAllForShop(shopId: Long): Flow<List<ShopCreditEntity>>

    @Query("SELECT * FROM shop_credits")
    suspend fun findAll(): List<ShopCreditEntity>

    @Query("DELETE FROM shop_credits")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM shop_credits")
    suspend fun count(): Int

    /**
     * Credit lines with original/paid derived in SQL, for one shop or for the whole app
     * (`shopId == null`).
     *
     * The remaining amount is intentionally *not* projected: exposing `totalMinor - paidMinor` from
     * the database would let a UI screen treat a SQL subtraction as the ledger's truth. The domain
     * layer owns that derivation, so it is clamped, tested and identical everywhere.
     */
    /** All credits across shops — the Records tab's "Shop" filter and global totals use this. */
    @Query(
        """
        SELECT
            c.id AS id,
            c.shopId AS shopId,
            s.name AS shopName,
            c.productName AS productName,
            c.quantity AS quantity,
            c.unitPriceMinor AS unitPriceMinor,
            c.totalAmountMinor AS totalMinor,
            COALESCE((SELECT SUM(p.amountMinor) FROM payments p
                       WHERE p.payableType = 'shop_credit' AND p.payableId = c.id), 0) AS paidMinor,
            c.purchaseDateEpochDay AS purchaseDateEpochDay,
            c.dueDateEpochDay AS dueDateEpochDay,
            c.cancelled AS cancelled,
            c.notes AS notes
        FROM shop_credits c
        JOIN shops s ON s.id = c.shopId
        WHERE (:shopId IS NULL OR c.shopId = :shopId)
          AND (:hideCancelled = 0 OR c.cancelled = 0)
        ORDER BY c.purchaseDateEpochDay DESC, c.id DESC
        """,
    )
    fun observeCreditBalances(shopId: Long?, hideCancelled: Boolean): Flow<List<CreditRow>>

    /** Suspended single-row fetch used by delete confirmations and undo snapshots. */
    @Query(
        """
        SELECT
            c.id AS id,
            c.shopId AS shopId,
            s.name AS shopName,
            c.productName AS productName,
            c.quantity AS quantity,
            c.unitPriceMinor AS unitPriceMinor,
            c.totalAmountMinor AS totalMinor,
            COALESCE((SELECT SUM(p.amountMinor) FROM payments p
                       WHERE p.payableType = 'shop_credit' AND p.payableId = c.id), 0) AS paidMinor,
            c.purchaseDateEpochDay AS purchaseDateEpochDay,
            c.dueDateEpochDay AS dueDateEpochDay,
            c.cancelled AS cancelled,
            c.notes AS notes
        FROM shop_credits c
        JOIN shops s ON s.id = c.shopId
        WHERE c.id = :id
        """,
    )
    suspend fun findCreditRow(id: Long): CreditRow?

    @Query("SELECT * FROM shop_credits WHERE shopId = :shopId")
    suspend fun findCreditsForShop(shopId: Long): List<ShopCreditEntity>

    /** Payments recorded against one credit, newest first — the credit's mini ledger. */
    @Query(
        """
        SELECT * FROM payments
        WHERE payableType = 'shop_credit' AND payableId = :creditId
        ORDER BY paidDateEpochDay DESC, id DESC
        """,
    )
    fun observePaymentsForCredit(creditId: Long): Flow<List<PaymentEntity>>

    /**
     * Paid total excluding one payment — this is what makes **editing** a payment safe: the
     * overpayment guard must compare the *new* value against the obligation minus every *other*
     * payment, not against a total that already includes the payment being changed.
     */
    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM payments
        WHERE payableType = 'shop_credit' AND payableId = :payableId AND id != :excludingPaymentId
        """,
    )
    suspend fun paidExcluding(payableId: Long, excludingPaymentId: Long): Long

    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM payments
        WHERE payableType = 'shop_credit' AND payableId = :payableId
        """,
    )
    suspend fun paidTotal(payableId: Long): Long

    @Query(
        """
        SELECT id FROM payments WHERE payableType = 'shop_credit' AND payableId = :payableId
        """,
    )
    suspend fun findPaymentIdsForCredit(payableId: Long): List<Long>

    /**
     * Deleting a credit also removes its payment rows, atomically.
     *
     * `payments.payableId` has no foreign key (it is polymorphic — see [PaymentEntity]), so this
     * cleanup is the guarantee that no orphaned payments can survive a deleted obligation and then
     * be mis-attributed to an unrelated record that later reuses the id.
     */
    @Transaction
    suspend fun deleteWithPayments(credit: ShopCreditEntity): Int {
        val ids = findPaymentIdsForCredit(credit.id)
        if (ids.isNotEmpty()) deletePaymentsByIds(ids)
        delete(credit)
        return ids.size
    }

    @Query("DELETE FROM payments WHERE id IN (:ids)")
    suspend fun deletePaymentsByIds(ids: List<Long>)
}
