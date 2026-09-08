package com.khatago.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.khatago.finance.data.db.entity.ShopEntity
import kotlinx.coroutines.flow.Flow

/**
 * Shops and their derived outstanding balances.
 *
 * The aggregate SQL below is the heart of the shop module. Note the shape of every "paid"
 * expression: a payment is attributed to a shop *through the credit it was made against*
 * (`payments.payableType = 'shop_credit' AND payments.payableId = shop_credits.id`). Nothing
 * stores a shop balance, so a shop's number can never drift from the payment rows underneath it.
 */
@Dao
interface ShopDao {

    @Query("SELECT * FROM shops ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ShopEntity>>

    @Query("SELECT * FROM shops WHERE id = :id")
    fun observeById(id: Long): Flow<ShopEntity?>

    @Query("SELECT * FROM shops WHERE id = :id")
    suspend fun findById(id: Long): ShopEntity?

    @Query("SELECT * FROM shops ORDER BY name COLLATE NOCASE ASC")
    suspend fun findAll(): List<ShopEntity>

    @Query("SELECT id FROM shops WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findIdByName(name: String): Long?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(shop: ShopEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(shops: List<ShopEntity>): List<Long>

    @Update
    suspend fun update(shop: ShopEntity)

    @Delete
    suspend fun delete(shop: ShopEntity)

    @Query("DELETE FROM shops")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM shops")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM shops")
    suspend fun count(): Int

    /**
     * Per-shop rollup for the shop list.
     *
     * `overdueMinor` is deliberately restricted to unsettled records (`total > paid`), so a credit
     * that was paid late but is now closed never shows up as a current overdue amount.
     */
    @Transaction
    @Query(
        """
        SELECT
            s.id AS id,
            s.name AS name,
            s.phone AS phone,
            s.ownerName AS ownerName,
            s.archived AS archived,
            (SELECT COUNT(*) FROM shop_credits c WHERE c.shopId = s.id) AS creditCount,
            (SELECT COUNT(*) FROM shop_credits c
                WHERE c.shopId = s.id AND c.cancelled = 0
                  AND c.totalAmountMinor > (
                      SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                      WHERE p.payableType = 'shop_credit' AND p.payableId = c.id)) AS activeCount,
            COALESCE((SELECT SUM(c.totalAmountMinor) FROM shop_credits c WHERE c.shopId = s.id), 0) AS totalMinor,
            COALESCE((
                SELECT SUM(p.amountMinor) FROM payments p
                JOIN shop_credits c ON c.id = p.payableId
                WHERE c.shopId = s.id AND p.payableType = 'shop_credit'), 0) AS paidMinor,
            COALESCE((
                SELECT SUM(c.totalAmountMinor - (
                    SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                    WHERE p.payableType = 'shop_credit' AND p.payableId = c.id))
                FROM shop_credits c
                WHERE c.shopId = s.id AND c.cancelled = 0 AND c.dueDateEpochDay < :today
                  AND c.totalAmountMinor > (
                      SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                      WHERE p.payableType = 'shop_credit' AND p.payableId = c.id)), 0) AS overdueMinor,
            (SELECT MAX(c.purchaseDateEpochDay) FROM shop_credits c WHERE c.shopId = s.id) AS lastCreditDateEpochDay,
            (SELECT MIN(c.dueDateEpochDay) FROM shop_credits c
                WHERE c.shopId = s.id AND c.cancelled = 0 AND c.dueDateEpochDay >= :today
                  AND c.totalAmountMinor > (
                      SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                      WHERE p.payableType = 'shop_credit' AND p.payableId = c.id)) AS nextDueDateEpochDay
        FROM shops s
        ORDER BY totalMinor - paidMinor DESC, s.name COLLATE NOCASE ASC
        """,
    )
    fun observeSummaries(today: Long): Flow<List<ShopSummaryRow>>

    @Transaction
    @Query(
        """
        SELECT
            s.id AS id,
            s.name AS name,
            s.phone AS phone,
            s.ownerName AS ownerName,
            s.archived AS archived,
            (SELECT COUNT(*) FROM shop_credits c WHERE c.shopId = s.id) AS creditCount,
            (SELECT COUNT(*) FROM shop_credits c
                WHERE c.shopId = s.id AND c.cancelled = 0
                  AND c.totalAmountMinor > (
                      SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                      WHERE p.payableType = 'shop_credit' AND p.payableId = c.id)) AS activeCount,
            COALESCE((SELECT SUM(c.totalAmountMinor) FROM shop_credits c WHERE c.shopId = s.id), 0) AS totalMinor,
            COALESCE((
                SELECT SUM(p.amountMinor) FROM payments p
                JOIN shop_credits c ON c.id = p.payableId
                WHERE c.shopId = s.id AND p.payableType = 'shop_credit'), 0) AS paidMinor,
            COALESCE((
                SELECT SUM(c.totalAmountMinor - (
                    SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                    WHERE p.payableType = 'shop_credit' AND p.payableId = c.id))
                FROM shop_credits c
                WHERE c.shopId = s.id AND c.cancelled = 0 AND c.dueDateEpochDay < :today
                  AND c.totalAmountMinor > (
                      SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                      WHERE p.payableType = 'shop_credit' AND p.payableId = c.id)), 0) AS overdueMinor,
            (SELECT MAX(c.purchaseDateEpochDay) FROM shop_credits c WHERE c.shopId = s.id) AS lastCreditDateEpochDay,
            (SELECT MIN(c.dueDateEpochDay) FROM shop_credits c
                WHERE c.shopId = s.id AND c.cancelled = 0 AND c.dueDateEpochDay >= :today
                  AND c.totalAmountMinor > (
                      SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                      WHERE p.payableType = 'shop_credit' AND p.payableId = c.id)) AS nextDueDateEpochDay
        FROM shops s
        WHERE s.id = :shopId
        """,
    )
    fun observeSummary(shopId: Long, today: Long): Flow<ShopSummaryRow?>

    @Query(
        """
        SELECT COALESCE(SUM(creditRemaining.totalMinor - creditRemaining.paidMinor), 0) AS totalMinor
        FROM (
            SELECT c.totalAmountMinor AS totalMinor,
                   (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                     WHERE p.payableType = 'shop_credit' AND p.payableId = c.id) AS paidMinor
            FROM shop_credits c
            WHERE c.cancelled = 0
        ) creditRemaining
        WHERE creditRemaining.totalMinor - creditRemaining.paidMinor > 0
        """,
    )
    fun observeOutstanding(): Flow<Long>

    /**
     * Everything that would disappear if this shop were deleted. The delete dialog must state the
     * true blast radius before the user commits, which is why this is a query and not a UI guess.
     */
    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM shop_credits WHERE shopId = :shopId) AS creditCount,
            (
                SELECT COUNT(*) FROM payments p
                WHERE p.payableType = 'shop_credit'
                  AND p.payableId IN (SELECT id FROM shop_credits WHERE shopId = :shopId)
            ) AS paymentCount
        """,
    )
    suspend fun deletionImpact(shopId: Long): ShopDeletionRow

    /**
     * One-shot flat list of every credit line with its paid total, for CSV export and reports.
     *
     * Deliberately *not* a Flow: an export runs once inside a coroutine and must not hold a database
     * observation open for the length of a file write. The join is repeated here instead of reusing
     * the Flow query so the export can never accidentally subscribe to live updates mid-write and
     * produce a file whose rows disagree with each other.
     */
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
        ORDER BY c.purchaseDateEpochDay DESC, c.id DESC
        """,
    )
    suspend fun findAllCreditRows(): List<CreditRow>

}
