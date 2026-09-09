package com.khatago.finance.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.khatago.finance.core.time.AppDates
import com.khatago.finance.data.db.KhataGoDatabase
import com.khatago.finance.data.db.entity.InstallmentEntity
import com.khatago.finance.data.db.entity.EmiPurchaseEntity
import com.khatago.finance.data.db.entity.LoanEntity
import com.khatago.finance.data.db.entity.PaymentEntity
import com.khatago.finance.data.db.entity.ShopCreditEntity
import com.khatago.finance.data.db.entity.ShopEntity
import com.khatago.finance.data.repo.ObligationRepository
import com.khatago.finance.data.repo.PayableResolver
import com.khatago.finance.data.repo.PaymentOutcome
import com.khatago.finance.data.repo.PaymentRepository
import com.khatago.finance.data.repo.ShopRepository
import com.khatago.finance.data.repo.isSaved
import com.khatago.finance.domain.model.PayableType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The payment engine against a real (in-memory) Room database.
 *
 * These are the invariants the whole product rests on, and they are checked where they are actually
 * enforced — one write path, one derived balance, no stored totals:
 *
 *  1. remaining never goes negative and never exceeds the original;
 *  2. an overpayment is refused and *nothing* is written;
 *  3. editing a payment validates against the obligation minus the other payments, so an edit cannot
 *     smuggle in an overpayment;
 *  4. deleting a payment leaves the derived balance exactly right, and an installment line's own paid
 *     figure is repaired in the same transaction;
 *  5. a down payment counts as paid once — never twice;
 *  6. the derived total equals the sum of the payment rows (there is no second place where a balance
 *     lives, so nothing can drift).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PaymentEngineTest {

    private lateinit var database: KhataGoDatabase
    private lateinit var shops: ShopRepository
    private lateinit var payments: PaymentRepository
    private lateinit var obligations: ObligationRepository
    private val today = AppDates.today()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = KhataGoDatabase.createInMemory(context)
        val resolver = PayableResolver(database)
        shops = ShopRepository(database)
        payments = PaymentRepository(database, resolver)
        obligations = ObligationRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // --- helpers ---------------------------------------------------------------

    private suspend fun credit(
        totalMinor: Long,
        dueInDays: Long? = null,
        cancelled: Boolean = false,
    ): Long {
        val shopId = database.shopDao().insert(ShopEntity(name = "Rahman Store ${System.nanoTime()}"))
        val result = shops.saveCredit(
            ShopCreditEntity(
                shopId = shopId,
                productName = "Rice 5kg",
                quantity = 2L,
                unitPriceMinor = totalMinor / 2L,
                totalAmountMinor = totalMinor,
                purchaseDateEpochDay = today - 10L,
                dueDateEpochDay = dueInDays?.let { today + it },
                cancelled = cancelled,
            ),
        )
        assertTrue("credit save failed: $result", result.isSaved)
        // `single()`, not `first()`: this helper writes exactly one credit and every assertion below
        // counts on that, so a second row must fail the test loudly instead of being ignored.
        return database.creditDao().findCreditsForShop(shopId).single().id
    }

    private suspend fun record(creditId: Long, amount: Long): PaymentOutcome =
        payments.record(
            payableType = PayableType.ShopCredit,
            payableId = creditId,
            amountMinor = amount,
            paidDateEpochDay = today,
            methodName = "Cash",
            reference = null,
            note = null,
        )

    private suspend fun paidTotal(payableId: Long): Long =
        database.creditDao().paidTotal(payableId)

    // --- 1 & 2: recording, and the overpayment refusal ------------------------

    @Test
    fun `a payment inside the balance is written and the derived remaining follows`() = runTest {
        val creditId = credit(totalMinor = 10_000L)

        val outcome = record(creditId, 4_000L)
        assertTrue(outcome is PaymentOutcome.Recorded)
        assertEquals(4_000L, (outcome as PaymentOutcome.Recorded).amountMinor)
        assertEquals(6_000L, outcome.remainingAfterMinor)
        assertEquals(4_000L, paidTotal(creditId))

        val snapshot = payments.resolveOnce(PayableType.ShopCredit, creditId)
        assertNotNull(snapshot)
        assertEquals(6_000L, snapshot!!.remainingMinor)
        assertEquals(10_000L, snapshot.originalMinor)
    }

    @Test
    fun `overpaying is refused and nothing at all is written`() = runTest {
        val creditId = credit(totalMinor = 10_000L)
        record(creditId, 9_000L)

        val outcome = record(creditId, 1_001L)
        assertTrue("expected a rejection, got $outcome", outcome is PaymentOutcome.Rejected)
        assertEquals(1_000L, (outcome as PaymentOutcome.Rejected).remainingMinor)
        // The message tells the user the allowed maximum instead of just saying "invalid".
        assertTrue(outcome.errorMessage()!!.contains("10.00"))

        assertEquals(9_000L, paidTotal(creditId))
        assertEquals(1, database.paymentDao().findForPayable("shop_credit", creditId).size)
    }

    @Test
    fun `paying the exact remaining amount settles the record`() = runTest {
        val creditId = credit(totalMinor = 10_000L)
        record(creditId, 6_000L)
        assertTrue(record(creditId, 4_000L) is PaymentOutcome.Recorded)
        assertEquals(10_000L, paidTotal(creditId))

        val snapshot = payments.resolveOnce(PayableType.ShopCredit, creditId)!!
        assertEquals(0L, snapshot.remainingMinor)
        assertTrue(snapshot.remainingAfter(0L) == 0L)
        // Anything further is now "nothing outstanding", not a negative balance.
        assertTrue(record(creditId, 1L) is PaymentOutcome.Rejected)
        assertEquals(10_000L, paidTotal(creditId))
    }

    @Test
    fun `zero and negative amounts never reach the ledger`() = runTest {
        val creditId = credit(totalMinor = 10_000L)
        assertTrue(record(creditId, 0L) is PaymentOutcome.Rejected)
        assertTrue(record(creditId, -500L) is PaymentOutcome.Rejected)
        assertEquals(0L, paidTotal(creditId))
    }

    @Test
    fun `a cancelled record refuses payments until it is restored`() = runTest {
        val creditId = credit(totalMinor = 10_000L, cancelled = true)
        val outcome = record(creditId, 1_000L)
        assertTrue(outcome is PaymentOutcome.Rejected)
        assertTrue((outcome as PaymentOutcome.Rejected).errorMessage()!!.isNotBlank())
        assertEquals(0L, paidTotal(creditId))
    }

    @Test
    fun `paying a record that no longer exists is reported, not swallowed`() = runTest {
        val outcome = record(creditId = 9_999L, amount = 500L)
        assertTrue(outcome is PaymentOutcome.NotFound)
    }

    // --- 3: editing a payment --------------------------------------------------

    @Test
    fun `editing a payment is validated against the remaining amount excluding itself`() = runTest {
        val creditId = credit(totalMinor = 10_000L)
        val first = record(creditId, 4_000L) as PaymentOutcome.Recorded
        record(creditId, 2_000L)

        // Growing the first payment to 8,000 with 2,000 already paid elsewhere would total 10,000 —
        // exactly the balance, so it must be accepted.
        val grown = payments.update(first.paymentId, 8_000L, today, "Cash", null, null)
        assertTrue("expected acceptance, got $grown", grown is PaymentOutcome.Recorded)
        assertEquals(10_000L, paidTotal(creditId))

        // One paisa more would be an overpayment smuggled in through an edit.
        val rejected = payments.update(first.paymentId, 8_001L, today, "Cash", null, null)
        assertTrue(rejected is PaymentOutcome.Rejected)
        assertEquals(10_000L, paidTotal(creditId))
    }

    @Test
    fun `shrinking a payment reopens the balance`() = runTest {
        val creditId = credit(totalMinor = 10_000L)
        val recorded = record(creditId, 10_000L) as PaymentOutcome.Recorded
        assertEquals(0L, payments.resolveOnce(PayableType.ShopCredit, creditId)!!.remainingMinor)

        payments.update(recorded.paymentId, 3_500L, today, "bKash", null, null)
        assertEquals(3_500L, paidTotal(creditId))
        assertEquals(6_500L, payments.resolveOnce(PayableType.ShopCredit, creditId)!!.remainingMinor)
    }

    // --- 4: deleting and undoing ------------------------------------------------

    @Test
    fun `deleting a payment restores the balance and undo restores the row`() = runTest {
        val creditId = credit(totalMinor = 10_000L)
        val recorded = record(creditId, 4_000L) as PaymentOutcome.Recorded
        val row = database.paymentDao().findById(recorded.paymentId)!!

        payments.delete(row)
        assertEquals(0L, paidTotal(creditId))
        assertEquals(10_000L, payments.resolveOnce(PayableType.ShopCredit, creditId)!!.remainingMinor)

        val restoredId = payments.restore(row)
        assertTrue(restoredId > 0L)
        assertEquals(4_000L, paidTotal(creditId))
        val restored = database.paymentDao().findById(restoredId)
        assertNotNull(restored)
        assertEquals(
            "an undo must reproduce the original row exactly",
            row.copy(id = 0L),
            restored!!.copy(id = 0L),
        )
    }

    @Test
    fun `deleting a payment repairs the installment line it was applied to`() = runTest {
        val loanId = database.loanDao().insert(
            LoanEntity(
                institution = "City Bank",
                loanName = "Housing",
                principalMinor = 1_000_000L,
                disbursementDateEpochDay = today - 90L,
                totalPayableMinor = 1_200_000L,
                downPaymentMinor = 200_000L,
                installmentAmountMinor = 100_000L,
                installmentCount = 10,
                startDateEpochDay = today - 90L,
                firstDueDateEpochDay = today - 60L,
            ),
        )
        database.loanDao().insertInstallments(
            listOf(
                InstallmentEntity(
                    ownerType = "loan",
                    ownerId = loanId,
                    number = 1,
                    dueDateEpochDay = today - 60L,
                    scheduledAmountMinor = 100_000L,
                    paidMinor = 0L,
                ),
            ),
        )
        val lineId = database.loanDao().findInstallments(loanId).single().id

        val outcome = payments.record(
            payableType = PayableType.Loan,
            payableId = loanId,
            amountMinor = 100_000L,
            paidDateEpochDay = today,
            methodName = "Bank transfer",
            reference = null,
            note = null,
            installmentId = lineId,
        ) as PaymentOutcome.Recorded

        assertEquals(100_000L, database.paymentDao().paidAtLine(lineId))

        val row = database.paymentDao().findById(outcome.paymentId)!!
        payments.delete(row)
        // The line's own paid figure must fall back to what the surviving ledger supports: a line left
        // "paid" for money that no longer exists is a schedule that contradicts its own obligation.
        assertEquals(0L, database.paymentDao().paidAtLine(lineId))
        assertEquals(0L, database.installmentDao().findById(lineId)?.paidMinor ?: 0L)
        // The down payment is metadata on the plan, never a `payments` row, so `PaymentDao.paidTotal` is
        // empty again once the row is gone — that is the raw ledger, and it is exactly why the app's paid
        // figure is derived one layer up. Assert the number the engine itself publishes.
        assertEquals(
            "only the down payment is left on the loan's own ledger",
            200_000L,
            payments.resolveOnce(PayableType.Loan, loanId)!!.recordedPaidMinor,
        )
        assertEquals(0L, database.paymentDao().paidTotal("loan", loanId))
    }

    // --- 5: down payments -------------------------------------------------------

    @Test
    fun `a loan down payment counts as paid once on both sides of the balance`() = runTest {
        val loanId = database.loanDao().insert(
            LoanEntity(
                institution = "NGO",
                loanName = "Season loan",
                principalMinor = 1_000_000L,
                disbursementDateEpochDay = today - 30L,
                totalPayableMinor = 1_200_000L,
                downPaymentMinor = 200_000L,
                installmentAmountMinor = 100_000L,
                installmentCount = 10,
                startDateEpochDay = today - 30L,
                firstDueDateEpochDay = today + 1L,
            ),
        )

        // The convention (docs/MONEY.md section 6), applied identically by the engine and by every
        // aggregate query:
        //   paid      = downPayment + SUM(payments)
        //   remaining = original - paid  ->  exactly the sum of the schedule.
        // `original` is the only part that differs per plan: a loan's totalPayable EXCLUDES the down
        // payment so its original is `totalPayable + downPayment`, while an EMI's totalPayable already
        // INCLUDES it so its original is just `totalPayable`. Adding it to an EMI's total as well would
        // count the down payment twice and inflate the plan by one instalment.
        val snapshot = payments.resolveOnce(PayableType.Loan, loanId)!!
        assertEquals(1_400_000L, snapshot.originalMinor)
        assertEquals(200_000L, snapshot.recordedPaidMinor)
        assertEquals(1_200_000L, snapshot.remainingMinor)

        assertTrue(
            payments.record(
                PayableType.Loan, loanId, 1_200_001L, today, "Cash", null, null,
            ) is PaymentOutcome.Rejected,
        )
        assertTrue(
            payments.record(PayableType.Loan, loanId, 1_200_000L, today, "Cash", null, null)
                is PaymentOutcome.Recorded,
        )
        assertEquals(0L, payments.resolveOnce(PayableType.Loan, loanId)!!.remainingMinor)
        assertEquals(
            "a settled loan must not be reported as outstanding",
            0L,
            database.statsDao().observeLoanOutstanding().first(),
        )
        assertEquals(
            "the headline and the module tile must be the same derivation",
            payments.resolveOnce(PayableType.Loan, loanId)!!.remainingMinor,
            database.loanDao().observeOutstanding().first(),
        )
    }

    @Test
    fun `an EMI down payment leaves the financed remainder payable and nothing more`() = runTest {
        val emiId = database.emiDao().insert(
            EmiPurchaseEntity(
                productName = "Refrigerator",
                merchant = "Rahman Store",
                purchaseDateEpochDay = today - 20L,
                cashPriceMinor = 3_200_000L,
                totalPayableMinor = 3_600_000L,
                downPaymentMinor = 400_000L,
                emiAmountMinor = 400_000L,
                installmentCount = 8,
                firstDueDateEpochDay = today + 10L,
            ),
        )
        // totalPayable (3,600,000) already contains the 400,000 down payment, so it IS the original; the
        // paid side carries the down payment, which leaves the eight EMIs of 400,000 = 3,200,000 to pay.
        val snapshot = payments.resolveOnce(PayableType.Emi, emiId)!!
        assertEquals(3_600_000L, snapshot.originalMinor)
        assertEquals(400_000L, snapshot.recordedPaidMinor)
        assertEquals(3_200_000L, snapshot.remainingMinor)

        assertTrue(
            payments.record(PayableType.Emi, emiId, 3_200_001L, today, "Cash", null, null)
                is PaymentOutcome.Rejected,
        )
        // The "I owe" headline must agree with the snapshot to the paisa.
        assertTrue(
            payments.record(PayableType.Emi, emiId, 3_200_000L, today, "Cash", null, null)
                is PaymentOutcome.Recorded,
        )
        assertEquals(0L, database.statsDao().observeEmiOutstanding().first())

        // The three places an EMI balance is derived must agree, to the paisa. They are separate SQL
        // (dashboard tile, per-module outstanding, per-record projection), and that is precisely where a
        // down-payment convention can be "simplified" in one of them and silently double-count.
        val snapshot2 = payments.resolveOnce(PayableType.Emi, emiId)!!
        assertEquals(0L, database.emiDao().observeOutstanding().first())
        assertEquals(snapshot2.remainingMinor, database.statsDao().observeEmiOutstanding().first())
    }

    @Test
    fun `the three EMI derivations agree while the plan is still open`() = runTest {
        val emiId = database.emiDao().insert(
            EmiPurchaseEntity(
                productName = "Television",
                merchant = "Rahman Electronics",
                purchaseDateEpochDay = today - 40L,
                cashPriceMinor = 60_000_000L,
                totalPayableMinor = 66_000_000L,
                downPaymentMinor = 6_000_000L,
                emiAmountMinor = 6_000_000L,
                installmentCount = 10,
                firstDueDateEpochDay = today + 5L,
            ),
        )
        payments.record(PayableType.Emi, emiId, 12_000_000L, today, "Cash", null, null)

        // original 66,000,000 (the down payment is already inside it) - paid (6,000,000 down +
        // 12,000,000 in two EMIs) = 48,000,000 = the eight EMIs still owed. Treating the down payment as
        // an extra instalment on top — the loan's convention, wrong for an EMI — is what used to make the
        // dashboard tile disagree with the EMI module by exactly 6,000,000.
        val snapshot = payments.resolveOnce(PayableType.Emi, emiId)!!
        assertEquals(48_000_000L, snapshot.remainingMinor)
        assertEquals(48_000_000L, database.emiDao().observeOutstanding().first())
        assertEquals(48_000_000L, database.statsDao().observeEmiOutstanding().first())
        assertEquals(48_000_000L, database.statsDao().observeTotalIOwe().first())
    }

    // --- 6: no stored balance can drift ----------------------------------------

    @Test
    fun `the derived total always equals the sum of the payment rows`() = runTest {
        val creditId = credit(totalMinor = 10_000L)
        listOf(1_000L, 2_000L, 3_000L).forEach { amount -> record(creditId, amount) }

        val rows = database.paymentDao().findForPayable("shop_credit", creditId)
        assertEquals(6_000L, rows.sumOf { it.amountMinor })
        assertEquals(6_000L, paidTotal(creditId))
        assertEquals(4_000L, payments.resolveOnce(PayableType.ShopCredit, creditId)!!.remainingMinor)

        // `findForPayable` returns newest first, so the test must not assume which row `first()` is: the
        // property is that all three derivations move together with the surviving rows, for any row at all.
        val removed = rows.first()
        payments.delete(removed)
        val survivors = database.paymentDao().findForPayable("shop_credit", creditId)
        assertEquals(6_000L - removed.amountMinor, survivors.sumOf { it.amountMinor })
        assertEquals(survivors.sumOf { it.amountMinor }, paidTotal(creditId))
        assertEquals(
            survivors.sumOf { it.amountMinor },
            10_000L - payments.resolveOnce(PayableType.ShopCredit, creditId)!!.remainingMinor,
        )
    }

    @Test
    fun `a credit cannot be shrunk below what has already been paid`() = runTest {
        val creditId = credit(totalMinor = 10_000L)
        record(creditId, 7_000L)
        val shopId = database.creditDao().findById(creditId)!!.shopId

        val result = shops.saveCredit(
            ShopCreditEntity(
                id = creditId,
                shopId = shopId,
                productName = "Rice 5kg",
                quantity = 1L,
                unitPriceMinor = 5_000L,
                totalAmountMinor = 5_000L,
                purchaseDateEpochDay = today - 10L,
            ),
        )
        assertFalse("shrinking below the paid total must be refused", result.isSaved)
        assertEquals(7_000L, paidTotal(creditId))
        assertEquals(10_000L, database.creditDao().findById(creditId)!!.totalAmountMinor)
    }

    // --- the due queue the reminder worker reads --------------------------------

    @Test
    fun `the due queue shows an unpaid record and drops it once settled`() = runTest {
        val creditId = credit(totalMinor = 10_000L, dueInDays = 2L)
        val rows = database.dueDao().findBetween(today, today + 7L)
        assertTrue(rows.any { it.payableType == "shop_credit" && it.payableId == creditId })

        record(creditId, 10_000L)
        val afterSettlement = database.dueDao().findBetween(today, today + 7L)
        assertFalse(
            "a fully paid record must leave the queue",
            afterSettlement.any { it.payableType == "shop_credit" && it.payableId == creditId },
        )
    }

    @Test
    fun `an overdue record is picked up by the overdue query`() = runTest {
        val creditId = credit(totalMinor = 10_000L, dueInDays = -3L)
        val overdue = database.dueDao().findOverdueOnce(today)
        assertTrue(overdue.any { it.payableType == "shop_credit" && it.payableId == creditId })

        record(creditId, 10_000L)
        assertFalse(database.dueDao().findOverdueOnce(today).any { it.payableId == creditId })
    }

    // --- schedule rebuild safety (the bug that orphans payments) -----------------

    @Test
    fun `editing an EMI plan keeps the payments and repairs the schedule lines`() = runTest {
        val emiId = database.emiDao().insert(
            EmiPurchaseEntity(
                productName = "Motorcycle",
                merchant = "Bikash Motors",
                purchaseDateEpochDay = today - 60L,
                cashPriceMinor = 1_000_000L,
                totalPayableMinor = 1_200_000L,
                downPaymentMinor = 200_000L,
                emiAmountMinor = 100_000L,
                installmentCount = 10,
                firstDueDateEpochDay = today - 30L,
            ),
        )
        obligations.saveEmi(
            database.emiDao().findById(emiId)!!.copy(installmentCount = 12, emiAmountMinor = 100_000L),
        )
        val lines = database.emiDao().findInstallments(emiId)
        assertEquals(12, lines.size)

        payments.record(PayableType.Emi, emiId, 100_000L, today, "Cash", null, null, lines.first().id)
        assertEquals(100_000L, database.paymentDao().paidAtLine(lines.first().id))

        // Rebuilding again must detach the payment from a line id that is about to stop existing…
        val lineIdBefore = lines.first().id
        obligations.saveEmi(
            database.emiDao().findById(emiId)!!.copy(installmentCount = 12, emiAmountMinor = 100_000L),
        )
        val surviving = database.paymentDao().findForPayable("emi", emiId)
        assertEquals(1, surviving.size)
        assertNull(
            "a payment must not keep pointing at a deleted installment row",
            surviving.single().installmentId,
        )
        // …while the money itself, and the schedule's paid position, stay correct.
        // 200,000 down + the one 100,000 payment that survived the rebuild. The raw ledger holds only the
        // payment, so the obligation-level figure is the one that has to be asserted here.
        assertEquals(300_000L, payments.resolveOnce(PayableType.Emi, emiId)!!.recordedPaidMinor)
        assertEquals(
            900_000L,
            payments.resolveOnce(PayableType.Emi, emiId)!!.remainingMinor,
        )
        val reallocated = database.emiDao().findInstallments(emiId)
        assertEquals(12, reallocated.size)
        assertTrue(
            "the rebuild must replace the rows, not reuse them",
            reallocated.all { it.id != lineIdBefore },
        )
    }

    @Test
    fun `no query anywhere reports a balance that contradicts the ledger`() = runTest {
        val creditId = credit(totalMinor = 10_000L, dueInDays = 1L)
        record(creditId, 4_000L)

        val row = database.creditDao().findCreditRow(creditId)!!
        assertEquals(10_000L, row.totalMinor)
        assertEquals(4_000L, row.paidMinor)
        assertEquals(
            "the derived remaining shown in the list must equal the engine's snapshot",
            payments.resolveOnce(PayableType.ShopCredit, creditId)!!.remainingMinor,
            row.totalMinor - row.paidMinor,
        )
        assertEquals(LocalDate.ofEpochDay(today - 10L).toEpochDay(), row.purchaseDateEpochDay)
    }
}
