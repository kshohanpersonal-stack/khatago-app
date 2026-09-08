package com.khatago.finance.core.time

import com.khatago.finance.core.money.MoneyMinor
import java.time.LocalDate

/** How often a repayment repeats. Persisted by name so a future entry cannot break old rows. */
enum class Frequency(val label: String, val shortLabel: String) {
    OneTime("One time", "Once"),
    Weekly("Weekly", "Weekly"),
    Biweekly("Every 2 weeks", "2 weeks"),
    Monthly("Monthly", "Monthly"),
    Custom("Custom interval", "Interval"),
    ;

    companion object {
        fun fromName(name: String?): Frequency = entries.firstOrNull { it.name == name } ?: Monthly
    }
}

data class ScheduleEntry(
    /** 1-based, matching what a loan agreement calls "installment 1". */
    val number: Int,
    val dueDateEpochDay: Long,
    val amountMinor: Long,
)

/**
 * Deterministic installment schedule generator.
 *
 * Rules that matter for money:
 *  - Day-of-month is anchored to the [firstDueDateEpochDay]. A 31st starting date never produces
 *    an invalid 31 Feb; it clamps to the last valid day of the shorter month and then *keeps*
 *    clamping for the remaining months (the due day does not drift back to the 31st). This is
 *    what banks do and it never creates a non-existent date.
 *  - The generator produces exactly [count] entries, never more, never invented ones.
 *  - Amounts are exact. If `installment x count` does not add up to [totalPayableMinor], the
 *    difference is placed on the **last** installment, which is how final balloon instalments
 *    actually work, and the total then reconciles to the cent.
 *  - If there is no installment amount, the schedule splits [totalPayableMinor] evenly and puts
 *    the indivisible remainder on the last installment.
 */
object InstallmentSchedule {

    fun generate(
        firstDueDateEpochDay: Long?,
        count: Int,
        frequency: Frequency,
        customIntervalDays: Int,
        installmentMinor: Long?,
        totalPayableMinor: Long?,
    ): List<ScheduleEntry> {
        if (count <= 0 || firstDueDateEpochDay == null) return emptyList()
        val stepDays = intervalDays(frequency, customIntervalDays)
        val anchorDay = AppDates.ofEpochDay(firstDueDateEpochDay).dayOfMonth
        val amounts = amountsFor(count, installmentMinor, totalPayableMinor)

        return (1..count).map { index ->
            ScheduleEntry(
                number = index,
                dueDateEpochDay = advance(firstDueDateEpochDay, index - 1, frequency, stepDays, anchorDay),
                amountMinor = amounts[index - 1],
            )
        }
    }

    fun intervalDays(frequency: Frequency, customIntervalDays: Int): Int = when (frequency) {
        Frequency.OneTime -> 0
        Frequency.Weekly -> 7
        Frequency.Biweekly -> 14
        Frequency.Monthly -> 0 // month-based, handled by calendar arithmetic
        Frequency.Custom -> customIntervalDays.coerceIn(1, 366)
    }

    /**
     * Advances [startEpochDay] by [steps] periods. Month-based stepping clamps the day-of-month;
     * day-based stepping is plain addition (which is why it can never create an invalid date).
     */
    fun advance(
        startEpochDay: Long,
        steps: Int,
        frequency: Frequency,
        intervalDays: Int,
        anchorDayOfMonth: Int = AppDates.ofEpochDay(startEpochDay).dayOfMonth,
    ): Long {
        if (steps <= 0) return startEpochDay
        return when (frequency) {
            Frequency.Monthly -> addMonthsClamped(startEpochDay, steps, anchorDayOfMonth)
            Frequency.OneTime -> startEpochDay
            Frequency.Weekly, Frequency.Biweekly, Frequency.Custom -> startEpochDay +
                steps * intervalDays.coerceAtLeast(1)
        }
    }

    fun addMonthsClamped(startEpochDay: Long, months: Int, anchorDayOfMonth: Int): Long {
        val start = AppDates.ofEpochDay(startEpochDay)
        val targetYearMonth = start.year * 12 + (start.monthValue - 1) + months
        val year = Math.floorDiv(targetYearMonth, 12)
        val month = Math.floorMod(targetYearMonth, 12) + 1
        val lengthOfMonth = LocalDate.of(year, month, 1).lengthOfMonth()
        return LocalDate.of(year, month, minOf(anchorDayOfMonth, lengthOfMonth)).toEpochDay()
    }

    /** Evenly splits the payable, remainder to the last installment. Exact; no floats. */
    fun amountsFor(count: Int, installmentMinor: Long?, totalPayableMinor: Long?): List<Long> {
        require(count > 0) { "count must be positive" }
        if (count == 1) {
            val single = when {
                totalPayableMinor != null && totalPayableMinor > 0L -> totalPayableMinor
                installmentMinor != null && installmentMinor > 0L -> installmentMinor
                else -> 0L
            }
            return listOf(single)
        }
        val perInstallment = if (installmentMinor != null && installmentMinor > 0L) {
            installmentMinor
        } else if (totalPayableMinor != null && totalPayableMinor > 0L) {
            totalPayableMinor / count
        } else {
            0L
        }
        val amounts = MutableList(count) { perInstallment }
        if (totalPayableMinor != null && totalPayableMinor > 0L) {
            val scheduled = perInstallment * (count - 1L)
            val last = totalPayableMinor - scheduled
            // A negative last entry would mean the agreed installments already exceed the total;
            // in that case we do not invent a credit — the schedule stops at the total.
            amounts[count - 1] = if (last < 0L) 0L else last
        }
        return amounts
    }

    /** Convenience for validation messages in forms. */
    fun projectedTotal(count: Int, installmentMinor: MoneyMinor): MoneyMinor =
        installmentMinor.times(count)
}
