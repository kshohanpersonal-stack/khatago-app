package com.khatago.finance.data.repo

import androidx.room.withTransaction
import com.khatago.finance.core.money.MoneyMinor
import com.khatago.finance.data.db.KhataGoDatabase
import com.khatago.finance.data.db.dao.CreditRow
import com.khatago.finance.data.db.dao.ShopSummaryRow
import com.khatago.finance.data.db.entity.PaymentEntity
import com.khatago.finance.data.db.entity.ShopCreditEntity
import com.khatago.finance.data.db.entity.ShopEntity
import com.khatago.finance.domain.calc.FinancialBalance
import com.khatago.finance.domain.model.Obligation
import com.khatago.finance.domain.model.PayableType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Shop credit: the market khata.
 *
 * Responsibilities that make this more than a table wrapper:
 *  - **Total integrity.** A credit's `totalAmountMinor` must match `quantity x unitPrice` unless the
 *    user explicitly typed the total (a discount or rounding in a paper khata is normal). A
 *    mismatch is surfaced to the form layer rather than silently "fixed".
 *  - **Balances are never stored.** Every number the UI shows is recomputed from `shop_credits` +
 *    `payments`, so editing or deleting a payment is reflected in the shop header, the shop list,
 *    the dashboard and the reports at the same instant.
 *  - **Deletion is deliberate.** Removing a credit removes its payment rows in the same transaction
 *    (the ledger's `payableId` is not a foreign key — see [PaymentEntity]) and reports how many
 *    payments went with it, so the user is never surprised by vanished history.
 */
class ShopRepository(private val database: KhataGoDatabase) {

    fun observeShops(): Flow<List<ShopEntity>> = database.shopDao().observeAll()

    fun observeShop(id: Long): Flow<ShopEntity?> = database.shopDao().observeById(id)

    fun observeSummaries(todayEpochDay: Long): Flow<List<ShopSummaryRow>> =
        database.shopDao().observeSummaries(todayEpochDay)

    fun observeSummary(id: Long, todayEpochDay: Long): Flow<ShopSummaryRow?> =
        database.shopDao().observeSummary(id, todayEpochDay)

    fun observeCredits(shopId: Long?, hideCancelled: Boolean): Flow<List<CreditRow>> =
        database.creditDao().observeCreditBalances(shopId, hideCancelled)

    /** Shop credits in the app-wide unified [Obligation] shape (used by Records and Reports). */
    fun observeObligations(hideCancelled: Boolean): Flow<List<Obligation>> =
        database.creditDao().observeCreditBalances(null, hideCancelled).map { rows ->
            rows.map { it.toObligation() }
        }

    fun observeTotalOutstanding(): Flow<Long> = database.shopDao().observeOutstanding()

    suspend fun findShop(id: Long): ShopEntity? = database.shopDao().findById(id)

    suspend fun findAllShops(): List<ShopEntity> = database.shopDao().findAll()

    suspend fun findCreditRow(id: Long): CreditRow? = database.creditDao().findCreditRow(id)

    suspend fun findAllCreditRows(): List<CreditRow> = database.shopDao().findAllCreditRows()

    /**
     * Creates or updates a shop. A duplicate name is rejected rather than auto-merged: two
     * "Rahman Store" rows in different neighbourhoods are a real situation, and silently combining
     * them would silently combine two ledgers.
     */
    suspend fun saveShop(shop: ShopEntity): SaveResult {
        val trimmed = shop.name.trim()
        if (trimmed.isEmpty()) return SaveResult.Invalid("Give the shop a name.")
        if (trimmed.length > 80) return SaveResult.Invalid("Keep the shop name under 80 characters.")
        val existingId = database.shopDao().findIdByName(trimmed)
        if (existingId != null && existingId != shop.id) {
            return SaveResult.Conflict("You already have a shop called \"$trimmed\".")
        }
        val now = System.currentTimeMillis()
        return try {
            database.withTransaction {
                if (shop.id == 0L) {
                    database.shopDao().insert(shop.copy(name = trimmed, createdAt = now, updatedAt = now))
                } else {
                    database.shopDao().update(shop.copy(name = trimmed, updatedAt = now))
                }
            }
            SaveResult.Saved
        } catch (_: android.database.SQLException) {
            SaveResult.Conflict("You already have a shop called \"$trimmed\".")
        }
    }

    suspend fun shopDeletionImpact(shopId: Long): DeletionImpact {
        val row = database.shopDao().deletionImpact(shopId)
        return DeletionImpact(creditCount = row.creditCount, paymentCount = row.paymentCount)
    }

    /** Returns the number of credit lines removed (their payments go with them, atomically). */
    suspend fun deleteShop(shop: ShopEntity): Int = database.withTransaction {
        val credits = database.creditDao().findCreditsForShop(shop.id)
        credits.forEach { credit -> database.creditDao().deleteWithPayments(credit) }
        database.shopDao().delete(shop)
        credits.size
    }

    /** Archive is the non-destructive alternative the UI offers before delete. */
    suspend fun setArchived(shopId: Long, archived: Boolean): SaveResult = database.withTransaction {
        val shop = database.shopDao().findById(shopId)
            ?: return@withTransaction SaveResult.Invalid("That shop no longer exists.")
        database.shopDao().update(shop.copy(archived = archived, updatedAt = System.currentTimeMillis()))
        SaveResult.Saved
    }

    // --- credit lines ---------------------------------------------------------

    /**
     * Validates a credit line before it is written: quantity sanity, the quantity x price identity
     * (unless the total was typed deliberately), and a due date that is not before the purchase.
     */
    fun validateCredit(
        quantity: Long,
        unitPriceMinor: Long,
        totalAmountMinor: Long,
        overrideTotal: Boolean,
        purchaseDateEpochDay: Long,
        dueDateEpochDay: Long?,
    ): SaveResult? {
        if (quantity <= 0L) return SaveResult.Invalid("Quantity must be at least 1.")
        if (quantity > 1_000_000L) return SaveResult.Invalid("That quantity looks too large.")
        if (totalAmountMinor <= 0L) return SaveResult.Invalid("The credit amount must be greater than zero.")
        val computed = runCatching { MoneyMinor.ofProduct(quantity, unitPriceMinor) }.getOrNull()
            ?: return SaveResult.Invalid("Quantity times price is too large to store.")
        if (!overrideTotal && unitPriceMinor > 0L && computed.minor != totalAmountMinor) {
            return SaveResult.Mismatch(
                message = "The total does not match quantity x price.",
                expectedMinor = computed.minor,
            )
        }
        if (dueDateEpochDay != null && dueDateEpochDay < purchaseDateEpochDay) {
            return SaveResult.Invalid("The due date cannot be before the purchase date.")
        }
        return null
    }

    suspend fun saveCredit(credit: ShopCreditEntity): SaveResult = database.withTransaction {
        val validated = validateCredit(
            quantity = credit.quantity,
            unitPriceMinor = credit.unitPriceMinor,
            totalAmountMinor = credit.totalAmountMinor,
            overrideTotal = credit.overrideTotal,
            purchaseDateEpochDay = credit.purchaseDateEpochDay,
            dueDateEpochDay = credit.dueDateEpochDay,
        )
        if (validated != null) return@withTransaction validated
        if (credit.shopId <= 0L || database.shopDao().findById(credit.shopId) == null) {
            return@withTransaction SaveResult.Invalid("Choose the shop this purchase belongs to.")
        }
        // A payment ledger that already exceeds the new total means the user is shrinking a credit
        // below what has been paid. Refusing is the correct behaviour: deleting payments would
        // destroy history, and allowing it would produce a negative remaining balance.
        if (credit.id != 0L) {
            val paid = database.creditDao().paidTotal(credit.id)
            if (paid > credit.totalAmountMinor) {
                return@withTransaction SaveResult.Invalid(
                    "This credit already has " +
                        "${MoneyMinor.ofMinor(paid).toPlainString(2)} recorded against it, so its total " +
                        "cannot go below that. Adjust the payments first.",
                )
            }
        }
        val now = System.currentTimeMillis()
        if (credit.id == 0L) {
            database.creditDao().insert(credit.copy(createdAt = now, updatedAt = now))
        } else {
            database.creditDao().update(credit.copy(createdAt = credit.createdAt, updatedAt = now))
        }
        SaveResult.Saved
    }

    /** Deleting a single credit line. Returns how many payments were removed with it. */
    suspend fun deleteCredit(creditId: Long): Int = database.withTransaction {
        val credit = database.creditDao().findById(creditId) ?: return@withTransaction 0
        database.creditDao().deleteWithPayments(credit)
    }

    suspend fun findCredit(id: Long): ShopCreditEntity? = database.creditDao().findById(id)

    fun observePaymentsForCredit(creditId: Long): Flow<List<PaymentEntity>> =
        database.creditDao().observePaymentsForCredit(creditId)
}

private fun CreditRow.toObligation(): Obligation = Obligation(
    type = PayableType.ShopCredit,
    id = id,
    title = productName,
    subtitle = shopName,
    balance = FinancialBalance(totalMinor, paidMinor),
    dueDateEpochDay = dueDateEpochDay,
    cancelled = cancelled,
)

/** Row-count summary shown in delete confirmations. */
data class DeletionImpact(
    val creditCount: Int,
    val paymentCount: Int,
) {
    val isDestructive: Boolean get() = creditCount > 0 || paymentCount > 0

    fun describe(): String = when {
        creditCount == 0 && paymentCount == 0 -> "This shop has no records yet."
        paymentCount == 0 -> "This shop has $creditCount credit record(s)."
        else -> "This shop has $creditCount credit record(s) and $paymentCount payment(s)."
    }
}

/** Every repository write returns this, so the UI renders one consistent set of messages. */
sealed interface SaveResult {
    data object Saved : SaveResult
    data class Invalid(val message: String) : SaveResult
    data class Conflict(val message: String) : SaveResult
    data class Mismatch(val message: String, val expectedMinor: Long) : SaveResult
}

val SaveResult.isSaved: Boolean get() = this is SaveResult.Saved
