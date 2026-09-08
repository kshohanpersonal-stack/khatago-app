package com.khatago.finance.domain.calc

import com.khatago.finance.core.money.MoneyMinor
import com.khatago.finance.core.time.Frequency
import com.khatago.finance.core.time.InstallmentSchedule
import com.khatago.finance.domain.model.Installment
import com.khatago.finance.domain.model.InstallmentStatus
import com.khatago.finance.domain.model.LedgerStatus
import com.khatago.finance.domain.model.PayableType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Balances, payment guards and schedule derivation — the three rules the whole app is built on:
 * remaining never goes negative, overpayment is refused, and an allocation's total always equals the
 * obligation's paid total. If any of those break, every screen that trusts them is wrong at the same
 * time, which is why they are tested here rather than through the UI.
 */
class FinancialMathTest {

    private val today = LocalDate.of(2025, 3, 15).toEpochDay()

    // --- FinancialBalance -----------------------------------------------------

    @Test
    fun `remaining is clamped at zero`() {
        assertEquals(500L, FinancialBalance(1_000L, 500L).remainingMinor)
        assertEquals(0L, FinancialBalance(1_000L, 1_000L).remainingMinor)
        assertEquals(0L, FinancialBalance(1_000L, 1_200L).remainingMinor)
    }

    @Test
    fun `a settled record is settled even when overpaid`() {
        assertTrue(FinancialBalance(1_000L, 1_000L).isSettled)
        assertTrue(FinancialBalance(1_000L, 1_500L).isSettled)
        assertFalse(FinancialBalance(1_000L, 999L).isSettled)
    }

    @Test
    fun `negative inputs are refused by the balance itself`() {
        runCatching { FinancialBalance(-1L, 0L) }.onSuccess {
            throw AssertionError("a negative original amount must not be constructible")
        }
        runCatching { FinancialBalance(0L, -1L) }.onSuccess {
            throw AssertionError("a negative paid amount must not be constructible")
        }
    }

    @Test
    fun `paid ratio is bounded for display bars`() {
        assertEquals(0.5, FinancialBalance(1_000L, 500L).paidRatio, 1e-9)
        assertEquals(1.0, FinancialBalance(1_000L, 2_000L).paidRatio, 1e-9)
        assertEquals(0.0, FinancialBalance(0L, 0L).paidRatio, 1e-9)
    }

    @Test
    fun `status treats a late-but-full payment as paid, and a zero record as paid`() {
        assertEquals(
            LedgerStatus.Paid,
            FinancialBalance(1_000L, 1_000L).status(today - 30, today),
        )
        assertEquals(
            LedgerStatus.Unpaid,
            FinancialBalance(1_000L, 0L).status(today + 5, today),
        )
        assertEquals(
            LedgerStatus.Overdue,
            FinancialBalance(1_000L, 0L).status(today - 1, today),
        )
        assertEquals(
            LedgerStatus.Cancelled,
            FinancialBalance(1_000L, 0L).status(today - 1, today, cancelled = true),
        )
        assertEquals(LedgerStatus.Paid, FinancialBalance(0L, 0L).status(null, today))
    }

    // --- payment guard --------------------------------------------------------

    @Test
    fun `a payment inside the remaining balance is accepted unchanged`() {
        val result = PaymentValidation.validate(amountMinor = 400L, originalMinor = 1_000L, paidMinor = 500L)
        assertTrue(result is PaymentValidation.Accepted)
        assertEquals(400L, (result as PaymentValidation.Accepted).amountMinor)
    }

    @Test
    fun `overpayment is rejected with the ExceedsRemaining kind`() {
        val result = PaymentValidation.validate(amountMinor = 401L, originalMinor = 1_000L, paidMinor = 500L)
        assertTrue(result is PaymentValidation.Rejected)
        assertEquals(PaymentValidation.Kind.ExceedsRemaining, (result as PaymentValidation.Rejected).kind)
        assertTrue(result.message.isNotBlank())
    }

    @Test
    fun `paying the exact remaining amount is accepted (it settles, it does not exceed)`() {
        assertTrue(
            PaymentValidation.validate(500L, 1_000L, 500L) is PaymentValidation.Accepted,
        )
    }

    @Test
    fun `settled records reject any further payment`() {
        val result = PaymentValidation.validate(1L, 1_000L, 1_000L)
        assertEquals(PaymentValidation.Kind.NothingOutstanding, (result as PaymentValidation.Rejected).kind)
    }

    @Test
    fun `zero and negative amounts are rejected`() {
        assertEquals(
            PaymentValidation.Kind.AmountTooSmall,
            (PaymentValidation.validate(0L, 1_000L, 0L) as PaymentValidation.Rejected).kind,
        )
        assertEquals(
            PaymentValidation.Kind.AmountTooSmall,
            (PaymentValidation.validate(-5L, 1_000L, 0L) as PaymentValidation.Rejected).kind,
        )
    }

    @Test
    fun `a cancelled record cannot receive a payment until it is restored`() {
        val result = PaymentValidation.validate(100L, 1_000L, 0L, cancelled = true)
        assertEquals(PaymentValidation.Kind.ObligationCancelled, (result as PaymentValidation.Rejected).kind)
    }

    @Test
    fun `clampPaid keeps a derived total inside the obligation even for corrupt data`() {
        assertEquals(700L, clampPaid(originalMinor = 1_000L, paidSumMinor = 700L))
        assertEquals(1_000L, clampPaid(originalMinor = 1_000L, paidSumMinor = 9_999L))
        assertEquals(0L, clampPaid(originalMinor = 1_000L, paidSumMinor = -5L))
    }

    // --- installment allocation ----------------------------------------------

    private fun line(number: Int, dueOffsetDays: Long, scheduled: Long, paid: Long = 0L) = Installment(
        id = number.toLong(),
        ownerType = PayableType.Loan,
        ownerId = 1L,
        number = number,
        dueDateEpochDay = today + dueOffsetDays,
        scheduledMinor = scheduled,
        paidMinor = paid,
    )

    @Test
    fun `allocation is chronological and greedy`() {
        val lines = listOf(
            line(2, dueOffsetDays = 30, scheduled = 500L),
            line(1, dueOffsetDays = -30, scheduled = 500L),
            line(3, dueOffsetDays = 60, scheduled = 500L),
        )
        val allocated = InstallmentAllocator.allocate(lines, totalPaidMinor = 700L, todayEpochDay = today)

        assertEquals(listOf(1L, 2L, 3L), allocated.map { it.installment.id })
        assertEquals(500L, allocated[0].allocatedMinor)
        assertEquals(200L, allocated[1].allocatedMinor)
        assertEquals(0L, allocated[2].allocatedMinor)
        assertEquals(InstallmentStatus.Paid, allocated[0].status)
        assertEquals(InstallmentStatus.PartiallyPaid, allocated[1].status)
        assertEquals(1, InstallmentAllocator.countPaid(allocated))
        assertEquals(800L, InstallmentAllocator.remainingMinor(allocated))
    }

    @Test
    fun `paid amounts on lines count before the obligation-level pool`() {
        val lines = listOf(
            line(1, -30, 500L, paid = 100L),
            line(2, 30, 500L),
        )
        val allocated = InstallmentAllocator.allocate(lines, totalPaidMinor = 100L, todayEpochDay = today)
        // Line 1 already has 100 paid directly, and the pool of 100 fills the rest of it: the
        // obligation's paid total is honoured exactly, and no money is invented.
        assertEquals(400L, allocated[0].allocatedMinor)
        assertEquals(500L, InstallmentAllocator.remainingMinor(allocated))
        assertEquals(500L, allocated.sumOf { it.allocatedMinor } + 0L)
    }

    @Test
    fun `allocation total can never exceed the sum of scheduled amounts`() {
        val lines = listOf(line(1, 1, 100L), line(2, 2, 100L))
        val allocated = InstallmentAllocator.allocate(lines, totalPaidMinor = 10_000L, todayEpochDay = today)
        assertEquals(200L, allocated.sumOf { it.allocatedMinor })
        assertTrue(allocated.all { it.isSettled })
    }

    @Test
    fun `overdue lines are those past their date and unsettled`() {
        val lines = listOf(line(1, -10, 500L), line(2, 10, 500L), line(3, 20, 500L, paid = 500L))
        val allocated = InstallmentAllocator.allocate(lines, totalPaidMinor = 0L, todayEpochDay = today)
        assertEquals(listOf(1L), InstallmentAllocator.overdue(allocated, today).map { it.installment.id })
        assertEquals(2L, InstallmentAllocator.nextDue(allocated, today)?.installment?.id)
    }

    @Test
    fun `an empty schedule allocates nothing and needs no special-casing`() {
        assertEquals(emptyList<AllocatedInstallment>(), InstallmentAllocator.allocate(emptyList(), 1_000L, today))
        assertNull(InstallmentAllocator.nextDue(emptyList(), today))
    }

    @Test
    fun `negative pool is refused rather than silently zeroed`() {
        val thrown = runCatching {
            InstallmentAllocator.allocate(listOf(line(1, 1, 100L)), -1L, today)
        }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    // --- schedule generation --------------------------------------------------

    @Test
    fun `monthly schedule clamps to the last day of shorter months`() {
        val jan31 = LocalDate.of(2025, 1, 31).toEpochDay()
        val entries = InstallmentSchedule.generate(
            firstDueDateEpochDay = jan31,
            count = 4,
            frequency = Frequency.Monthly,
            customIntervalDays = 30,
            installmentMinor = 1_000L,
            totalPayableMinor = null,
        )
        assertEquals(
            listOf(
                LocalDate.of(2025, 1, 31),
                LocalDate.of(2025, 2, 28),
                LocalDate.of(2025, 3, 31),
                LocalDate.of(2025, 4, 30),
            ).map { it.toEpochDay() },
            entries.map { it.dueDateEpochDay },
        )
        assertEquals(listOf(1, 2, 3, 4), entries.map { it.number })
    }

    @Test
    fun `the remainder of an uneven split lands on the last installment, so totals match exactly`() {
        // 1,000.00 across 3 = 333.33 + 333.33 + 333.34. No cents may be invented or lost.
        val entries = InstallmentSchedule.generate(
            firstDueDateEpochDay = today,
            count = 3,
            frequency = Frequency.Monthly,
            customIntervalDays = 30,
            installmentMinor = null,
            totalPayableMinor = 100_000L,
        )
        assertEquals(listOf(33_333L, 33_333L, 33_334L), entries.map { it.amountMinor })
        assertEquals(100_000L, entries.sumOf { it.amountMinor })
    }

    @Test
    fun `a fixed installment amount is respected even when it does not divide the total`() {
        val entries = InstallmentSchedule.generate(
            firstDueDateEpochDay = today,
            count = 4,
            frequency = Frequency.Monthly,
            customIntervalDays = 30,
            installmentMinor = 25_000L,
            totalPayableMinor = 100_050L,
        )
        assertEquals(100_050L, entries.sumOf { it.amountMinor })
        assertEquals(25_000L, entries.first().amountMinor)
    }

    @Test
    fun `custom interval is bounded so a zero-day gap cannot loop forever`() {
        val entries = InstallmentSchedule.generate(
            firstDueDateEpochDay = today,
            count = 3,
            frequency = Frequency.Custom,
            customIntervalDays = 0,
            installmentMinor = 100L,
            totalPayableMinor = null,
        )
        assertEquals(3, entries.size)
        assertEquals(1L, entries[1].dueDateEpochDay - entries[0].dueDateEpochDay)
    }

    @Test
    fun `one-time frequency produces a single line`() {
        val entries = InstallmentSchedule.generate(
            firstDueDateEpochDay = today,
            count = 6,
            frequency = Frequency.OneTime,
            customIntervalDays = 30,
            installmentMinor = 100L,
            totalPayableMinor = null,
        )
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.all { it.dueDateEpochDay == today })
    }

    @Test
    fun `no first due date means no schedule rather than a fabricated one`() {
        assertEquals(
            emptyList<ScheduleEntry>(),
            InstallmentSchedule.generate(null, 12, Frequency.Monthly, 30, 100L, null),
        )
        assertEquals(
            emptyList<ScheduleEntry>(),
            InstallmentSchedule.generate(today, 0, Frequency.Monthly, 30, 100L, null),
        )
    }

    @Test
    fun `weekly and biweekly step by exact days`() {
        val weekly = InstallmentSchedule.generate(
            firstDueDateEpochDay = today,
            count = 3,
            frequency = Frequency.Weekly,
            customIntervalDays = 30,
            installmentMinor = 1L,
            totalPayableMinor = null,
        )
        assertEquals(listOf(0L, 7L, 14L), weekly.map { it.dueDateEpochDay - today })

        val biweekly = InstallmentSchedule.generate(
            firstDueDateEpochDay = today,
            count = 3,
            frequency = Frequency.Biweekly,
            customIntervalDays = 30,
            installmentMinor = 1L,
            totalPayableMinor = null,
        )
        assertEquals(listOf(0L, 14L, 28L), biweekly.map { it.dueDateEpochDay - today })
    }

    @Test
    fun `money sums use the exact minor-unit type`() {
        val total = MoneyMinor.ofMinor(1_000L) + MoneyMinor.ofMinor(2_500L)
        assertEquals(3_500L, total.minor)
        assertEquals(MoneyMinor.ZERO, total.subtractSaturating(total))
    }
}
