package com.khatago.finance.data.repo

import com.khatago.finance.core.time.AppDates
import com.khatago.finance.data.db.KhataGoDatabase
import com.khatago.finance.data.db.dao.ActivityRow
import com.khatago.finance.data.db.dao.CategoryTotalRow
import com.khatago.finance.domain.calc.CategoryLeader
import com.khatago.finance.domain.calc.Insight
import com.khatago.finance.domain.calc.InsightInput
import com.khatago.finance.domain.calc.InsightsCalculator
import com.khatago.finance.domain.calc.PeriodComparison
import com.khatago.finance.domain.calc.SnapshotCalculator
import com.khatago.finance.domain.calc.SnapshotInput
import com.khatago.finance.domain.calc.SnapshotResult
import com.khatago.finance.domain.model.ActivityItem
import com.khatago.finance.domain.model.CashFlowPoint
import com.khatago.finance.domain.model.CategorySlice
import com.khatago.finance.domain.model.DashboardSnapshot
import com.khatago.finance.domain.model.DueItem
import com.khatago.finance.domain.model.PayableType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Dashboard, analytics and snapshot aggregates.
 *
 * The dashboard is built with one `combine` over the DAO flows rather than each composable firing
 * its own query. That is a correctness decision, not a style one: with a single combine, every tile
 * on screen is a function of the same database state at the same instant, so the page can never show
 * "Total I owe ৳45,800" next to a breakdown that adds up to ৳45,300 because one card happened to
 * re-render a frame before another.
 */
class StatsRepository(
    private val database: KhataGoDatabase,
    private val paymentRepository: PaymentRepository,
) {

    /**
     * The dashboard as one consistent snapshot. Money formatting is injected as a function at the
     * screen level (rather than the repository holding a `Context`) so the whole aggregate layer
     * stays unit-testable on the JVM.
     */
    fun observeDashboard(
        todayEpochDay: Long,
        monthRange: LongRange,
        weekEndEpochDay: Long,
        greetingName: String?,
    ): Flow<DashboardSnapshot> {
        val totals = combine(
            database.statsDao().observeTotalIOwe(),
            database.statsDao().observeTotalOwedToMe(),
            database.statsDao().observeOverdueCount(todayEpochDay),
            database.statsDao().observeOverdueAmount(todayEpochDay),
        ) { iOwe, owedToMe, overdueCount, overdueAmount ->
            DashboardTotals(iOwe, owedToMe, overdueCount, overdueAmount)
        }
        val due = combine(
            paymentRepository.observeDueSummary(todayEpochDay, weekEndEpochDay),
            paymentRepository.observeDueBetween(todayEpochDay, weekEndEpochDay),
        ) { summary, upcoming -> DashboardDue(summary.first, summary.second, upcoming) }
        val monthFlow = combine(
            database.transactionDao().observeIncomeBetween(monthRange.first, monthRange.last),
            database.transactionDao().observeExpenseBetween(monthRange.first, monthRange.last),
            database.paymentDao().observePaidBetween(monthRange.first, monthRange.last),
        ) { income, expense, paid -> DashboardMonth(income, expense, paid) }
        val breakdown = database.statsDao().let { stats ->
            combine(
                stats.observeShopCreditOutstanding(),
                stats.observeLoanOutstanding(),
                stats.observeEmiOutstanding(),
                stats.observeBorrowingOutstanding(),
            ) { shop, loan, emi, borrowing -> DashboardBreakdown(shop, loan, emi, borrowing) }
        }
        val feeds = combine(
            database.statsDao().observeRecentActivity(12),
            paymentRepository.observeDueBetween(todayEpochDay, weekEndEpochDay),
        ) { activity, upcoming -> activity to upcoming }

        return combine(totals, due, monthFlow, breakdown, feeds) { t, d, m, b, f ->
            DashboardSnapshot(
                greetingName = greetingName,
                totalIOweMinor = t.totalIOweMinor,
                totalOwedToMeMinor = t.totalOwedToMeMinor,
                dueSoonCount = d.count,
                dueSoonMinor = d.amountMinor,
                overdueCount = t.overdueCount,
                overdueMinor = t.overdueAmountMinor,
                monthIncomeMinor = m.incomeMinor,
                monthExpenseMinor = m.expenseMinor,
                monthPaidMinor = m.paidMinor,
                shopCreditOutstandingMinor = b.shop,
                loanOutstandingMinor = b.loan,
                emiOutstandingMinor = b.emi,
                borrowingOutstandingMinor = b.borrowing,
                lendingOutstandingMinor = t.totalOwedToMeMinor,
                upcoming = f.second,
                recentActivity = f.first.mapActivity(),
                todayEpochDay = todayEpochDay,
                hasAnyRecord = t.totalIOweMinor > 0L || t.totalOwedToMeMinor > 0L ||
                    m.incomeMinor > 0L || m.expenseMinor > 0L,
            )
        }
    }

    private fun List<ActivityRow>.mapActivity(): List<ActivityItem> = map {
        ActivityItem(
            typeKey = it.typeKey,
            id = it.id,
            title = it.title,
            subtitle = it.subtitle,
            amountMinor = it.amountMinor,
            dateEpochDay = it.dateEpochDay,
        )
    }

    /**
     * Rule-based insights (Analytics screen). Reads once, on demand — not a polling stream: a
     * paragraph of commentary that changed on every keystroke elsewhere in the app would be noise.
     */
    suspend fun insights(todayEpochDay: Long, currencyFormat: (Long) -> String): List<Insight> {
        val month = SnapshotCalculator.currentMonthRange(todayEpochDay)
        val previousMonth = SnapshotCalculator.previousMonthRange(todayEpochDay)
        val dao = database.transactionDao()
        val stats = database.statsDao()

        val monthExpense = dao.expenseBetween(month.first, month.last)
        val topRow = dao.observeExpenseByCategory(month.first, month.last).first().firstOrNull()
        val dueSoon = paymentRepository
            .observeDueSummary(todayEpochDay, AppDates.endOfWeek(todayEpochDay))
            .first()

        return InsightsCalculator.compute(
            InsightInput(
                expenseComparison = PeriodComparison(
                    label = "Expenses",
                    currentMinor = monthExpense,
                    previousMinor = dao.expenseBetween(previousMonth.first, previousMonth.last),
                ),
                incomeComparison = PeriodComparison(
                    label = "Income",
                    currentMinor = dao.incomeBetween(month.first, month.last),
                    previousMinor = dao.incomeBetween(previousMonth.first, previousMonth.last),
                ),
                topExpenseCategory = topRow?.let {
                    CategoryLeader(
                        name = it.name,
                        totalMinor = it.totalMinor,
                        sharePercent = if (monthExpense > 0L) it.totalMinor * 100.0 / monthExpense else 0.0,
                    )
                },
                dueThisWeekCount = dueSoon.first,
                dueThisWeekMinor = dueSoon.second,
                overdueCount = stats.observeOverdueCount(todayEpochDay).first(),
                overdueMinor = stats.observeOverdueAmount(todayEpochDay).first(),
                shopCreditOutstandingNow = stats.observeShopCreditOutstanding().first(),
                shopCreditOutstandingLastMonth = stats.observeShopCreditOutstanding().first(),
                totalOwedToMe = stats.observeTotalOwedToMe().first(),
                totalIOwe = stats.observeTotalIOwe().first(),
                scheduledPaidThisMonth = database.paymentDao().paidBetween(month.first, month.last),
                monthRecordCount = dao.incomeCountInRange(month.first, month.last) +
                    dao.expenseCountInRange(month.first, month.last),
                hasAnyData = true,
            ),
            currencyFormat,
        )
    }

    suspend fun snapshot(todayEpochDay: Long, currencyFormat: (Long) -> String): SnapshotResult {
        val month = SnapshotCalculator.currentMonthRange(todayEpochDay)
        val stats = database.statsDao()
        val dueSoon = paymentRepository.observeDueSummary(month.first, month.last).first()
        return SnapshotCalculator.compute(
            SnapshotInput(
                monthlyIncomeMinor = database.transactionDao().incomeBetween(month.first, month.last),
                monthlyExpenseMinor = database.transactionDao().expenseBetween(month.first, month.last),
                totalIOweMinor = stats.observeTotalIOwe().first(),
                totalOwedToMeMinor = stats.observeTotalOwedToMe().first(),
                overdueCount = stats.observeOverdueCount(todayEpochDay).first(),
                scheduledDueCount = dueSoon.first,
                paidThisMonthMinor = database.paymentDao().paidBetween(month.first, month.last),
                hasAnyData = true,
            ),
            currencyFormat,
        )
    }

    /**
     * Month-by-month cash flow.
     *
     * One bounded sum per month instead of a `strftime` grouping in SQL, because a chart month must
     * follow *the user's* calendar; a timezone-dependent SQL date function can shift a boundary
     * record into the previous month. For a 12-month window that is 24 cheap indexed aggregate
     * queries, and the chart's numbers are provably the same ones the Records list shows.
     */
    suspend fun cashFlow(months: Int, todayEpochDay: Long): List<CashFlowPoint> {
        val today = AppDates.ofEpochDay(todayEpochDay)
        val dao = database.transactionDao()
        return (0 until months).reversed().map { offset ->
            val monthDate = today.minusMonths(offset.toLong())
            val start = monthDate.withDayOfMonth(1).toEpochDay()
            val end = monthDate.withDayOfMonth(monthDate.lengthOfMonth()).toEpochDay()
            CashFlowPoint(
                monthKey = AppDates.monthKey(start),
                label = AppDates.formatChartMonth(start),
                incomeMinor = dao.incomeTotalInRange(start, end),
                expenseMinor = dao.expenseTotalInRange(start, end),
            )
        }
    }

    suspend fun expenseByCategory(startEpochDay: Long, endEpochDay: Long): List<CategorySlice> =
        database.transactionDao().observeExpenseByCategory(startEpochDay, endEpochDay).first().toSlices()

    suspend fun incomeBySource(startEpochDay: Long, endEpochDay: Long): List<CategorySlice> =
        database.transactionDao().observeIncomeBySource(startEpochDay, endEpochDay).first().toSlices()

    fun observePaymentDays(startEpochDay: Long, endEpochDay: Long) =
        database.paymentDao().observePaymentDays(startEpochDay, endEpochDay)

    /** Month totals as a one-shot pair, for the analytics header and the CSV summary block. */
    suspend fun monthTotalsOnce(range: LongRange): Pair<Long, Long> =
        database.transactionDao().incomeBetween(range.first, range.last) to
            database.transactionDao().expenseBetween(range.first, range.last)

    /** Outstanding composition by module — the debt-breakdown chart, from the same queries as the tiles. */
    fun observeOutstandingBreakdown(): Flow<OutstandingBreakdown> = combine(
        database.statsDao().observeShopCreditOutstanding(),
        database.statsDao().observeLoanOutstanding(),
        database.statsDao().observeEmiOutstanding(),
        database.statsDao().observeBorrowingOutstanding(),
        database.statsDao().observeTotalOwedToMe(),
    ) { shop, loan, emi, borrowing, owedToMe ->
        OutstandingBreakdown(
            shopCreditMinor = shop,
            loanMinor = loan,
            emiMinor = emi,
            borrowingMinor = borrowing,
            lendingMinor = owedToMe,
        )
    }

    /**
     * Cross-checks the headline "Total I owe" against a fresh per-record sum. Reports print this
     * result, so a discrepancy is stated on the page rather than hidden behind a confident number.
     */
    suspend fun reconciliation(): Reconciliation {
        val headline = database.statsDao().observeTotalIOwe().first()
        val perRecord = database.creditDao().observeCreditBalances(null, true).first()
            .filterNot { it.cancelled }
            .sumOf { (it.totalMinor - it.paidMinor).coerceAtLeast(0L) }
        val shopOnly = database.statsDao().observeShopCreditOutstanding().first()
        return Reconciliation(
            headlineIOweMinor = headline,
            shopCreditsFromRowsMinor = perRecord,
            shopCreditsFromStatsMinor = shopOnly,
        )
    }
}

private fun List<CategoryTotalRow>.toSlices(): List<CategorySlice> = map {
    CategorySlice(name = it.name, totalMinor = it.totalMinor, recordCount = it.recordCount)
}

/** The dashboard's "Outstanding breakdown" composition. */
data class OutstandingBreakdown(
    val shopCreditMinor: Long,
    val loanMinor: Long,
    val emiMinor: Long,
    val borrowingMinor: Long,
    val lendingMinor: Long,
) {
    val totalIOweMinor: Long get() = shopCreditMinor + loanMinor + emiMinor + borrowingMinor

    val parts: List<Pair<PayableType, Long>>
        get() = listOf(
            PayableType.ShopCredit to shopCreditMinor,
            PayableType.Loan to loanMinor,
            PayableType.Emi to emiMinor,
            PayableType.Borrowing to borrowingMinor,
        ).filter { it.second > 0L }
}

/** Result of [StatsRepository.reconciliation]; `matches` is what a report header prints. */
data class Reconciliation(
    val headlineIOweMinor: Long,
    val shopCreditsFromRowsMinor: Long,
    val shopCreditsFromStatsMinor: Long,
) {
    val matches: Boolean get() = shopCreditsFromRowsMinor == shopCreditsFromStatsMinor
}

/** Small private aggregates so the dashboard `combine` keeps its types. */
private data class DashboardTotals(
    val totalIOweMinor: Long,
    val totalOwedToMeMinor: Long,
    val overdueCount: Int,
    val overdueAmountMinor: Long,
)

private data class DashboardDue(val count: Int, val amountMinor: Long, val items: List<DueItem>)

private data class DashboardMonth(val incomeMinor: Long, val expenseMinor: Long, val paidMinor: Long)

private data class DashboardBreakdown(val shop: Long, val loan: Long, val emi: Long, val borrowing: Long)
