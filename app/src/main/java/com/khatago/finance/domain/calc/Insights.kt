package com.khatago.finance.domain.calc

import com.khatago.finance.core.time.AppDates
import java.time.LocalDate

/**
 * Local, rule-based insights.
 *
 * No AI, no network, no score to impress anyone: each insight is a plain sentence derived from a
 * comparison the user could do themselves with the notebook, and each one is *withheld* unless the
 * underlying data is actually meaningful (a "+0%" change or a comparison against an empty month is
 * noise, so we do not print it). That is also what keeps the wording human: "Your expenses went up
 * 2,400 since last month", not "Negative spending variance detected".
 */

/** Two same-length windows compared against each other (this month vs last month, and so on). */
data class PeriodComparison(
    val label: String,
    val currentMinor: Long,
    val previousMinor: Long,
) {
    val changeMinor: Long get() = currentMinor - previousMinor

    /** Percentage change; null when there is nothing to compare against (avoids "infinite growth"). */
    val changePercent: Double?
        get() = if (previousMinor <= 0L) null
        else (changeMinor.toDouble() / previousMinor.toDouble()) * 100.0

    val hasMeaningfulChange: Boolean get() = previousMinor > 0L && changeMinor != 0L
}

data class CategoryLeader(val name: String, val totalMinor: Long, val sharePercent: Double)

data class InsightInput(
    val expenseComparison: PeriodComparison?,
    val incomeComparison: PeriodComparison?,
    val topExpenseCategory: CategoryLeader?,
    val dueThisWeekCount: Int,
    val dueThisWeekMinor: Long,
    val overdueCount: Int,
    val overdueMinor: Long,
    val shopCreditOutstandingNow: Long,
    val shopCreditOutstandingLastMonth: Long,
    val totalOwedToMe: Long,
    val totalIOwe: Long,
    val scheduledPaidThisMonth: Long,
    val monthRecordCount: Int,
    val hasAnyData: Boolean,
)

sealed interface Insight {
    val message: String
    val tone: Tone

    enum class Tone { Neutral, Positive, Attention }

    data class Neutral(override val message: String) : Insight {
        override val tone = Tone.Neutral
    }

    data class Positive(override val message: String) : Insight {
        override val tone = Tone.Positive
    }

    data class Attention(override val message: String) : Insight {
        override val tone = Tone.Attention
    }
}

object InsightsCalculator {

    /**
     * Ordered by what a person most needs to act on: overdue first, then what is due, then
     * direction-of-travel observations. Empty list is a valid, good answer — "no news" should show
     * the empty state, not invented commentary.
     */
    fun compute(input: InsightInput, currencyFormat: (Long) -> String): List<Insight> = buildList {
        if (!input.hasAnyData) return@buildList

        if (input.overdueCount > 0) {
            val noun = if (input.overdueCount == 1) "payment" else "payments"
            add(
                Insight.Attention(
                    "You have ${input.overdueCount} overdue $noun totalling " +
                        "${currencyFormat(input.overdueMinor)}.",
                ),
            )
        }
        if (input.dueThisWeekCount > 0) {
            add(
                Insight.Attention(
                    "You have ${input.dueThisWeekCount} payment${if (input.dueThisWeekCount == 1) "" else "s"} due this week, " +
                        "totalling ${currencyFormat(input.dueThisWeekMinor)}.",
                ),
            )
        }

        input.expenseComparison?.let { comparison ->
            if (comparison.hasMeaningfulChange) {
                val percent = comparison.changePercent ?: 0.0
                val amount = kotlin.math.abs(comparison.changeMinor)
                add(
                    if (comparison.changeMinor > 0) {
                        Insight.Neutral(
                            "Your expenses went up ${currencyFormat(amount)} " +
                                "(${formatPercent(percent)}) since last month.",
                        )
                    } else {
                        Insight.Positive(
                            "Your expenses came down ${currencyFormat(amount)} " +
                                "(${formatPercent(-percent)}) since last month.",
                        )
                    },
                )
            }
        }

        input.incomeComparison?.let { comparison ->
            if (comparison.hasMeaningfulChange) {
                val percent = comparison.changePercent ?: 0.0
                add(
                    Insight.Neutral(
                        "Your income is ${formatPercent(percent)} versus last month.",
                    ),
                )
            }
        }

        input.topExpenseCategory?.takeIf { it.totalMinor > 0L && it.sharePercent >= 15.0 }?.let { leader ->
            add(
                Insight.Neutral(
                    "${leader.name} is your biggest expense this month — " +
                        "${currencyFormat(leader.totalMinor)} (${leader.sharePercent.toInt()}% of spending).",
                ),
            )
        }

        val shopDelta = input.shopCreditOutstandingNow - input.shopCreditOutstandingLastMonth
        if (input.shopCreditOutstandingLastMonth > 0L && shopDelta < 0L) {
            add(
                Insight.Positive(
                    "Your shop credit went down ${currencyFormat(-shopDelta)} this month.",
                ),
            )
        } else if (shopDelta > 0L && input.shopCreditOutstandingLastMonth > 0L) {
            add(
                Insight.Neutral(
                    "Your shop credit went up ${currencyFormat(shopDelta)} this month.",
                ),
            )
        }

        if (input.totalOwedToMe > 0L && input.totalIOwe > 0L) {
            add(
                Insight.Neutral(
                    "You are owed ${currencyFormat(input.totalOwedToMe)} while you owe " +
                        "${currencyFormat(input.totalIOwe)} — these are tracked separately, never netted.",
                ),
            )
        }

        if (input.scheduledPaidThisMonth > 0L) {
            add(
                Insight.Positive(
                    "You have paid ${currencyFormat(input.scheduledPaidThisMonth)} towards your records this month.",
                ),
            )
        }
    }

    internal fun formatPercent(value: Double): String =
        "%.0f%%".format(java.util.Locale.US, kotlin.math.abs(value))
}

/**
 * The Financial Snapshot.
 *
 * A transparent, deterministic 0-100 overview computed from four things the user's own records
 * contain, with the contributing factors shown in the UI. It is *not* a credit score, is not a
 * bank's opinion, and must never be presented as financial advice — which is why the API returns
 * the reasons alongside the number, and the band labels are descriptive rather than judgemental.
 */
data class SnapshotInput(
    val monthlyIncomeMinor: Long,
    val monthlyExpenseMinor: Long,
    val totalIOweMinor: Long,
    val totalOwedToMeMinor: Long,
    val overdueCount: Int,
    val scheduledDueCount: Int,
    val paidThisMonthMinor: Long,
    val hasAnyData: Boolean,
)

data class SnapshotResult(
    val score: Int,
    val bandLabel: String,
    val reasons: List<String>,
)

object SnapshotCalculator {

    private const val BAND_GETTING_ORGANISED = "Getting organised"
    private const val BAND_STEADY = "Steady"
    private const val BAND_SOLID_GROUND = "Solid ground"
    private const val BAND_UNDER_PRESSURE = "Under pressure"

    fun compute(input: SnapshotInput, currencyFormat: (Long) -> String): SnapshotResult {
        if (!input.hasAnyData) {
            return SnapshotResult(
                score = 0,
                bandLabel = BAND_GETTING_ORGANISED,
                reasons = listOf("Add a few records and this will start reflecting your month."),
            )
        }
        var score = 0
        val reasons = mutableListOf<String>()

        // 1. Saving capacity (up to 40): the single most informative, least gameable signal.
        val income = input.monthlyIncomeMinor
        val expense = input.monthlyExpenseMinor
        if (income > 0L) {
            val savingsRate = (income - expense).toDouble() / income.toDouble()
            val savingsPoints = when {
                savingsRate >= 0.30 -> 40
                savingsRate >= 0.15 -> 32
                savingsRate >= 0.05 -> 22
                savingsRate >= 0.0 -> 12
                else -> 0
            }
            score += savingsPoints
            reasons += if (savingsRate >= 0) {
                "You kept ${"%.0f".format(java.util.Locale.US, savingsRate * 100)}% of what came in this month (+$savingsPoints)."
            } else {
                "You spent more than you earned this month (+0)."
            }
        } else {
            reasons += "No income recorded this month, so saving capacity is unknown (+0)."
        }

        // 2. Debt load relative to income (up to 25).
        if (income > 0L && input.totalIOweMinor > 0L) {
            val months = input.totalIOweMinor.toDouble() / income.toDouble()
            val debtPoints = when {
                months <= 0.5 -> 25
                months <= 1.0 -> 20
                months <= 2.0 -> 12
                months <= 4.0 -> 6
                else -> 0
            }
            score += debtPoints
            val shownMonths = "%.1f".format(java.util.Locale.US, months)
            reasons += "What you owe is about $shownMonths month(s) of income (+$debtPoints)."
        } else if (input.totalIOweMinor == 0L) {
            score += 25
            reasons += "No outstanding obligations recorded (+25)."
        }

        // 3. Receivables (up to 15): money out with friends is recoverable, so it earns some credit.
        if (input.totalOwedToMeMinor > 0L) {
            val points = minOf(15, (input.totalOwedToMeMinor * 15L / (input.totalIOweMinor.coerceAtLeast(1L))).toInt())
            score += points
            reasons += "You are owed ${currencyFormat(input.totalOwedToMeMinor)} (+$points)."
        }

        // 4. Timeliness (up to 20): overdue is penalised, paying on schedule is rewarded.
        if (input.overdueCount == 0) {
            score += if (input.scheduledDueCount > 0) 20 else 12
            reasons += if (input.scheduledDueCount > 0) {
                "Nothing is overdue and ${input.scheduledDueCount} upcoming payment(s) look on track (+20)."
            } else {
                "Nothing is overdue (+12)."
            }
        } else {
            val penalty = minOf(20, input.overdueCount * 7)
            reasons += "$penalty points off for ${input.overdueCount} overdue payment(s)."
            score = (score - penalty).coerceAtLeast(0)
        }

        val band = when {
            score >= 75 -> BAND_SOLID_GROUND
            score >= 50 -> BAND_STEADY
            input.overdueCount > 0 || (income > 0L && expense > income) -> BAND_UNDER_PRESSURE
            else -> BAND_STEADY
        }
        return SnapshotResult(score = score.coerceIn(0, 100), bandLabel = band, reasons = reasons)
    }

    /** Month bounds for the snapshot: always the user's current calendar month, local. */
    fun currentMonthRange(todayEpochDay: Long = AppDates.today()): LongRange {
        val date = AppDates.ofEpochDay(todayEpochDay)
        return date.withDayOfMonth(1).toEpochDay()..date.withDayOfMonth(date.lengthOfMonth()).toEpochDay()
    }

    fun previousMonthRange(todayEpochDay: Long = AppDates.today()): LongRange {
        val date = AppDates.ofEpochDay(todayEpochDay).minusMonths(1)
        return date.withDayOfMonth(1).toEpochDay()..date.withDayOfMonth(date.lengthOfMonth()).toEpochDay()
    }
}

/** Formats a delta of days into the phrasing the app uses for "overdue by N days". */
fun overdueDescription(dueDateEpochDay: Long, todayEpochDay: Long): String {
    val days = (todayEpochDay - dueDateEpochDay).coerceAtLeast(0L)
    return when {
        days == 0L -> "Due today"
        days == 1L -> "1 day overdue"
        days < 31 -> "$days days overdue"
        else -> "${LocalDate.ofEpochDay(dueDateEpochDay).until(LocalDate.ofEpochDay(todayEpochDay)).months} month(s) overdue"
    }
}
