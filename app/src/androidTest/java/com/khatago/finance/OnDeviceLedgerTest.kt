package com.khatago.finance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.khatago.finance.core.time.AppDates
import com.khatago.finance.data.db.KhataGoDatabase
import com.khatago.finance.data.db.entity.PaymentEntity
import com.khatago.finance.data.db.entity.ShopCreditEntity
import com.khatago.finance.data.db.entity.ShopEntity
import com.khatago.finance.data.repo.PayableResolver
import com.khatago.finance.data.repo.PaymentOutcome
import com.khatago.finance.data.repo.PaymentRepository
import com.khatago.finance.data.repo.ShopRepository
import com.khatago.finance.data.repo.isSaved
import com.khatago.finance.domain.model.PayableType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The payment engine on a real device, against real SQLite.
 *
 * Robolectric already covers this logic on the JVM, and that is where a regression should be caught.
 * This test exists for the one thing a JVM cannot tell us: whether the *actual* SQLite that ships on a
 * phone evaluates the derived-balance queries exactly the way the tests assume. Two cases have historically
 * differed between the two engines and both are asserted here:
 *
 *  - `COALESCE(SUM(...))` over an empty set (the "no payments yet" case, which is every new record), and
 *  - an aggregate subquery inside a `UNION ALL` branch of the due view (the query that feeds the reminder).
 *
 * There is deliberately no Activity here: the debug build id carries a `.debug` suffix and the app's own
 * Application builds a file-backed container, so an on-device UI test belongs to a follow-up run with a
 * dedicated test runner, not to this suite.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceLedgerTest {

    private lateinit var database: KhataGoDatabase
    private lateinit var shops: ShopRepository
    private lateinit var payments: PaymentRepository
    private val today = AppDates.today()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, KhataGoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        shops = ShopRepository(database)
        payments = PaymentRepository(database, PayableResolver(database))
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun newCredit(totalMinor: Long): Long = runBlocking {
        val shopId = database.shopDao().insert(ShopEntity(name = "Rahman Store"))
        val result = shops.saveCredit(
            ShopCreditEntity(
                shopId = shopId,
                productName = "Rice 25kg",
                quantity = 1L,
                unitPriceMinor = totalMinor,
                totalAmountMinor = totalMinor,
                purchaseDateEpochDay = today - 5L,
                dueDateEpochDay = today + 3L,
            ),
        )
        assertTrue("save rejected: $result", result.isSaved)
        database.creditDao().findCreditsForShop(shopId).single().id
    }

    @Test
    fun aBrandNewRecordDerivesZeroPaidRatherThanNull() = runBlocking {
        val creditId = newCredit(50_000L)

        // COALESCE(SUM()) over no rows must be 0, not NULL — otherwise "50,000 − null" propagates and the
        // home screen shows a gap where a balance should be.
        assertEquals(0L, database.creditDao().paidTotal(creditId))
        val snapshot = payments.resolveOnce(PayableType.ShopCredit, creditId)!!
        assertEquals(50_000L, snapshot.originalMinor)
        assertEquals(50_000L, snapshot.remainingMinor)
        assertEquals(50_000L, database.statsDao().observeTotalIOwe().first())
    }

    @Test
    fun derivedRemainingTracksTheLedgerOnDevice() = runBlocking {
        val creditId = newCredit(50_000L)
        payments.record(PayableType.ShopCredit, creditId, 20_000L, today, "Cash", null, null)
        assertEquals(30_000L, payments.resolveOnce(PayableType.ShopCredit, creditId)!!.remainingMinor)

        payments.record(PayableType.ShopCredit, creditId, 30_000L, today, "bKash", null, null)
        val snapshot = payments.resolveOnce(PayableType.ShopCredit, creditId)!!
        assertEquals(0L, snapshot.remainingMinor)
        assertEquals(50_000L, database.creditDao().paidTotal(creditId))

        // And the row projection the list screens read agrees with the engine, digit for digit.
        val row = database.creditDao().findCreditRow(creditId)!!
        assertEquals(row.totalMinor - row.paidMinor, snapshot.remainingMinor)
    }

    @Test
    fun overpaymentIsRefusedByTheDeviceBuildToo() = runBlocking {
        val creditId = newCredit(50_000L)
        val outcome = payments.record(PayableType.ShopCredit, creditId, 50_001L, today, "Cash", null, null)
        assertTrue("expected a rejection, got $outcome", outcome is PaymentOutcome.Rejected)
        assertEquals(0L, database.creditDao().paidTotal(creditId))
    }

    @Test
    fun theDueUnionViewSeesTheRecordOnSqliteAsWellAsOnH2() = runBlocking {
        val creditId = newCredit(50_000L)
        val rows = database.dueDao().findBetween(today, today + 7L)
        assertTrue(rows.any { it.payableType == "shop_credit" && it.payableId == creditId })

        payments.record(PayableType.ShopCredit, creditId, 50_000L, today, "Cash", null, null)
        val after = database.dueDao().findBetween(today, today + 7L)
        assertFalse(
            "a settled record must not appear in the reminder queue",
            after.any { it.payableType == "shop_credit" && it.payableId == creditId },
        )
    }

    @Test
    fun theSchemaStoresNoDerivedBalance() = runBlocking {
        // The strongest guard in this file, and the cheapest: if a `remaining` column ever appears on a
        // payable table, some future screen will read it instead of deriving it, and the app will start
        // telling people the wrong number the first time a payment is edited.
        val cursor = database.openHelper.readableDatabase.rawQuery(
            "SELECT name FROM pragma_table_info('shop_credits')",
            arrayOf(),
        )
        val columns = mutableListOf<String>()
        cursor.use { while (it.moveToNext()) columns += it.getString(0) }
        assertTrue("columns came back empty: $columns", columns.isNotEmpty())
        assertFalse(
            "shop_credits must not store a balance: $columns",
            columns.any { it.contains("remaining", ignoreCase = true) || it.contains("balance", ignoreCase = true) },
        )
    }

    @Test
    fun aPaymentRowKeepsThePayablePointerItWasRecordedAgainst() = runBlocking {
        val creditId = newCredit(50_000L)
        payments.record(PayableType.ShopCredit, creditId, 5_000L, today, "Cash", "txn-42", "partial")

        val row: PaymentEntity = database.paymentDao().findForPayable("shop_credit", creditId).single()
        assertEquals("shop_credit", row.payableType)
        assertEquals(creditId, row.payableId)
        assertEquals(5_000L, row.amountMinor)
        assertEquals("txn-42", row.reference)
        assertEquals(today, row.paidDateEpochDay)
        assertEquals(null, row.installmentId)
    }
}
