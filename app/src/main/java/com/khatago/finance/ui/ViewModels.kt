package com.khatago.finance.ui

import androidx.lifecycle.ViewModel
import com.khatago.finance.AppContainer
import com.khatago.finance.core.money.CurrencySpec
import com.khatago.finance.core.money.MoneyFormat
import com.khatago.finance.core.time.AppDates
import com.khatago.finance.data.db.entity.AppSettingEntity
import com.khatago.finance.data.repo.SearchGroup
import com.khatago.finance.domain.calc.SnapshotCalculator
import com.khatago.finance.domain.model.DashboardSnapshot
import com.khatago.finance.domain.model.DueItem
import com.khatago.finance.domain.model.LedgerStatus
import com.khatago.finance.domain.model.Obligation
import com.khatago.finance.domain.model.PayableType
import com.khatago.finance.domain.model.PaymentCenterState
import com.khatago.finance.domain.model.PaymentEntry
import com.khatago.finance.data.db.dao.LedgerRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope

/**
 * Screen ViewModels, together in one file for a reason that outlives tidiness.
 *
 * These ViewModels do almost nothing on purpose: they observe a repository flow, add the two inputs a
 * repository must never hardcode — the device's *today* and the profile's *currency* — and expose the
 * result. There is no money arithmetic here because those rules live in `core/` and `domain/`, where
 * they are unit-tested on a JVM in milliseconds. A ViewModel that computed a balance would be a
 * second implementation of the balance, and second implementations drift.
 *
 * Two conventions are worth naming:
 *  - `SharingStarted.WhileSubscribed(5_000)` rather than `Eagerly`: holding Room observations open
 *    behind a locked screen or a backgrounded app is wasted work on the low-end devices this app is
 *    written for.
 *  - `today` is read **once per ViewModel**, not per frame, so a screen cannot roll over midnight
 *    mid-scroll and contradict its own header.
 */

/** Base for every KhataGo ViewModel: the container plus the two ambient inputs. */
/**
 * A settings row is a `String`, and KhataGo writes both `"1"/"0"` (reminder scheduler, hand-editable
 * from raw SQL) and `"true"/"false"` (repository helpers). One parser for both spellings is what stops
 * a switch flipping itself the next time the screen re-reads the row.
 */
internal fun String?.toSwitchOn(fallback: Boolean): Boolean = when (this) {
    null -> fallback
    "1", "true", "TRUE", "True" -> true
    "0", "false", "FALSE", "False" -> false
    else -> fallback
}

abstract class KhataGoViewModel(container: AppContainer) : ViewModel() {

    protected val app: AppContainer = container

    val todayEpochDay: Long = AppDates.today()

    /**
     * Currency is *not* cached here. Screens derive it from the profile flow together with their data
     * (see `withCurrency`), so a change in Settings re-renders every amount on screen at once. A
     * `var currency` on the ViewModel would let one screen re-render in ৳ and another in $ during the
     * same frame.
     */
    protected fun format(amountMinor: Long, currency: CurrencySpec): String =
        MoneyFormat.format(amountMinor, currency)
}

// --------------------------------------------------------------------------- home

data class HomeUiState(
    val dashboard: DashboardSnapshot,
    val currency: CurrencySpec,
    val onboardingComplete: Boolean,
    val widgetsHidden: Boolean,
)

class HomeViewModel(container: AppContainer) : KhataGoViewModel(container) {

    /**
     * The dashboard, the currency and the "hide widgets" switch arrive as one combined flow, so the
     * first frame already has the right symbol next to the right numbers. Reading them in three
     * separate collections is how an app renders ৳ totals for one frame and $ totals the next.
     */
    private val dashboard: Flow<DashboardSnapshot> = app.statsRepository.observeDashboard(
        todayEpochDay = todayEpochDay,
        monthRange = SnapshotCalculator.currentMonthRange(todayEpochDay),
        weekEndEpochDay = AppDates.endOfWeek(todayEpochDay),
        greetingName = null,
    )

    val state: StateFlow<HomeUiState> = combine(
        app.catalogRepository.observeProfile(),
        app.catalogRepository.observeSetting(AppSettingEntity.DASHBOARD_WIDGETS_HIDDEN),
        dashboard,
    ) { profile, hiddenValue, snapshot ->
        HomeUiState(
            dashboard = snapshot.copy(greetingName = profile?.displayName),
            currency = CurrencySpec.fromCode(profile?.currencyCode),
            onboardingComplete = profile?.onboardingComplete ?: false,
            widgetsHidden = hiddenValue.toSwitchOn(fallback = false),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(
            dashboard = emptyDashboard(),
            currency = CurrencySpec.DEFAULT,
            onboardingComplete = true,
            widgetsHidden = false,
        ),
    )

    /**
     * The first frame must not be blank. A dashboard that flashes an all-zero skeleton for 50 ms and
     * then shows real numbers reads as "the app lost my data"; `isLoading`-style empty state on the
     * *first* value is the alternative, so the real state is a zeroed snapshot that the UI labels
     * "Loading" until a record exists.
     */
    private fun emptyDashboard() = DashboardSnapshot(
        greetingName = null,
        totalIOweMinor = 0,
        totalOwedToMeMinor = 0,
        dueSoonCount = 0,
        dueSoonMinor = 0,
        overdueCount = 0,
        overdueMinor = 0,
        monthIncomeMinor = 0,
        monthExpenseMinor = 0,
        monthPaidMinor = 0,
        shopCreditOutstandingMinor = 0,
        loanOutstandingMinor = 0,
        emiOutstandingMinor = 0,
        borrowingOutstandingMinor = 0,
        lendingOutstandingMinor = 0,
        upcoming = emptyList(),
        recentActivity = emptyList(),
        todayEpochDay = todayEpochDay,
        hasAnyRecord = false,
    )
}

// --------------------------------------------------------------------------- records

/** The Records hub's module tabs. `key` is the same `typeKey` used by search, deep links and CSV. */
enum class RecordsModule(val key: String, val label: String) {
    ShopCredit("shop_credit", "Shop credit"),
    Loan("loan", "Loans"),
    Emi("emi", "EMI"),
    Borrowed("borrowing", "Borrowed"),
    Lent("lending", "Lent"),
    Income("income", "Income"),
    Expense("expense", "Expense"),
    ;

    val isLedger: Boolean get() = this == Income || this == Expense

    /** Where "Add" goes from this tab, so each tab offers the right form rather than a generic one. */
    fun addRoute(): String = when (this) {
        ShopCredit -> Routes.CREDIT_FORM
        Loan -> Routes.LOAN_FORM
        Emi -> Routes.EMI_FORM
        Borrowed, Lent -> Routes.PERSON_FORM
        Income -> Routes.INCOME_FORM
        Expense -> Routes.EXPENSE_FORM
    }

    companion object {
        fun fromKey(key: String?): RecordsModule = entries.firstOrNull { it.key == key } ?: ShopCredit
    }
}

enum class RecordsFilter(val key: String, val label: String) {
    All("all", "All"),
    Outstanding("outstanding", "Outstanding"),
    Overdue("overdue", "Overdue"),
    Settled("settled", "Settled"),
    Cancelled("cancelled", "Cancelled"),
    ;

    companion object {
        fun fromKey(key: String?): RecordsFilter = entries.firstOrNull { it.key == key } ?: All
    }
}

/**
 * A record as any list renders it, whatever module it came from.
 *
 * Mapping five different modules into one shape is what lets Records, search, the payment centre and
 * the CSV report share a single row composable and one set of tests. The per-module alternative is how
 * "Paid" ends up meaning "paid excluding the down payment" on one screen and "including it" on the
 * next — and that is a trust-destroying bug in a money app, not a cosmetic one.
 */
data class RecordListItem(
    val typeKey: String,
    val id: Long,
    val title: String,
    val subtitle: String,
    val totalMinor: Long,
    val paidMinor: Long,
    val remainingMinor: Long,
    val dueDateEpochDay: Long?,
    val statusLabel: String,
    val isOverdue: Boolean,
    val isSettled: Boolean,
    val cancelled: Boolean,
    val dateLabel: String,
    val installmentsPaid: Int? = null,
    val installmentsTotal: Int? = null,
) {
    val payoffFraction: Float
        get() = if (totalMinor <= 0L) 1f else (paidMinor.toFloat() / totalMinor.toFloat()).coerceIn(0f, 1f)
}

data class RecordsTotals(
    val originalMinor: Long,
    val paidMinor: Long,
    val remainingMinor: Long,
    val overdueMinor: Long,
)

data class RecordsUiState(
    val module: RecordsModule,
    val filter: RecordsFilter,
    val items: List<RecordListItem>,
    val totals: RecordsTotals,
    val currency: CurrencySpec,
    val isLoading: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
class RecordsViewModel(container: AppContainer) : KhataGoViewModel(container) {

    private val module = kotlinx.coroutines.flow.MutableStateFlow(RecordsModule.ShopCredit)
    private val filter = kotlinx.coroutines.flow.MutableStateFlow(RecordsFilter.All)

    fun select(module: RecordsModule) {
        this.module.value = module
    }

    fun select(filter: RecordsFilter) {
        this.filter.value = filter
    }

    val state: StateFlow<RecordsUiState> = combine(
        module,
        filter,
        app.catalogRepository.observeProfile().map { CurrencySpec.fromCode(it?.currencyCode) },
    ) { m, f, currency -> Triple(m, f, currency) }
        .flatMapLatest { (m, f, currency) ->
            rowsFor(m).map { rows ->
                val visible = rows.filter { matches(it, f) }
                RecordsUiState(
                    module = m,
                    filter = f,
                    items = visible,
                    totals = totalsOf(visible),
                    currency = currency,
                    isLoading = false,
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RecordsUiState(
                module = RecordsModule.ShopCredit,
                filter = RecordsFilter.All,
                items = emptyList(),
                totals = RecordsTotals(0, 0, 0, 0),
                currency = CurrencySpec.DEFAULT,
                isLoading = true,
            ),
        )

    /**
     * Filters are applied **in Kotlin over the module's rows**, not by duplicating SQL per module.
     *
     * A deliberate trade, not laziness: the five obligation queries already differ in how they derive
     * "paid" (payments by payable vs. installment allocation vs. a down payment counted at creation).
     * Re-implementing each of those again in a `WHERE` clause is precisely how a filter starts lying
     * about one module. A personal ledger holds hundreds or thousands of rows at most, so an exact
     * in-memory predicate is both correct and faster than the user can perceive.
     */
    private fun matches(item: RecordListItem, f: RecordsFilter): Boolean = when (f) {
        RecordsFilter.All -> true
        RecordsFilter.Outstanding -> !item.isSettled && !item.cancelled
        RecordsFilter.Overdue -> item.isOverdue && !item.cancelled
        RecordsFilter.Settled -> item.isSettled && !item.cancelled
        RecordsFilter.Cancelled -> item.cancelled
    }

    private fun totalsOf(items: List<RecordListItem>): RecordsTotals {
        val live = items.filterNot { it.cancelled }
        return RecordsTotals(
            originalMinor = live.sumOf { it.totalMinor },
            paidMinor = live.sumOf { it.paidMinor },
            remainingMinor = live.sumOf { it.remainingMinor },
            overdueMinor = live.filter { it.isOverdue }.sumOf { it.remainingMinor },
        )
    }

    private fun rowsFor(m: RecordsModule): Flow<List<RecordListItem>> = when (m) {
        RecordsModule.ShopCredit ->
            app.shopRepository.observeObligations(hideCancelled = false)
                .map { list -> list.map { it.toItem(PayableType.ShopCredit.displayName) } }

        RecordsModule.Loan ->
            app.obligationRepository.observeLoanObligations(todayEpochDay)
                .map { list -> list.map { it.toItem(PayableType.Loan.displayName) } }

        RecordsModule.Emi ->
            app.obligationRepository.observeEmiObligations(todayEpochDay)
                .map { list -> list.map { it.toItem(PayableType.Emi.displayName) } }

        RecordsModule.Borrowed ->
            app.personRepository.observeBorrowingObligations()
                .map { list -> list.map { it.toItem(PayableType.Borrowing.displayName) } }

        RecordsModule.Lent ->
            app.personRepository.observeLendingObligations()
                .map { list -> list.map { it.toItem(PayableType.Lending.displayName) } }

        RecordsModule.Income ->
            app.transactionRepository.observeLedger("income", null, null, null)
                .map { rows -> rows.map { it.toLedgerItem() } }

        RecordsModule.Expense ->
            app.transactionRepository.observeLedger("expense", null, null, null)
                .map { rows -> rows.map { it.toLedgerItem() } }
    }

    private fun Obligation.toItem(typeKey: String): RecordListItem {
        val status = balance.status(dueDateEpochDay, todayEpochDay)
        return RecordListItem(
            typeKey = typeKey,
            id = id,
            title = title,
            subtitle = subtitle,
            totalMinor = balance.originalMinor,
            paidMinor = balance.paidMinor,
            remainingMinor = remainingMinor,
            dueDateEpochDay = dueDateEpochDay,
            statusLabel = status.label,
            // Not `status == Overdue`: LedgerStatus reports "Partially paid" as soon as any money has
            // arrived, so a record that is half paid and three weeks late would never appear in the
            // Overdue filter. Overdue-ness is a *date* fact and must be derived from the date plus the
            // unsettled balance, exactly like the due queue does.
            isOverdue = !isSettled && remainingMinor > 0L &&
                dueDateEpochDay != null && dueDateEpochDay < todayEpochDay && !cancelled,
            isSettled = isSettled,
            cancelled = cancelled,
            dateLabel = AppDates.humanDay(dueDateEpochDay, todayEpochDay),
            installmentsPaid = installmentsPaid,
            installmentsTotal = installmentsTotal,
        )
    }

    private fun LedgerRow.toLedgerItem(): RecordListItem = RecordListItem(
        typeKey = kind,
        id = id,
        title = categoryName,
        subtitle = counterparty?.ifBlank { methodName } ?: methodName,
        totalMinor = amountMinor,
        paidMinor = amountMinor,
        remainingMinor = 0L,
        dueDateEpochDay = null,
        statusLabel = if (kind == "income") "Received" else "Spent",
        isOverdue = false,
        isSettled = true,
        cancelled = false,
        dateLabel = AppDates.formatMedium(transactionDateEpochDay),
    )
}

// --------------------------------------------------------------------------- payment centre

data class PaymentCenterUiState(
    val dueToday: List<DueItem>,
    val upcoming: List<DueItem>,
    val overdue: List<DueItem>,
    val recentlyPaid: List<PaymentEntry>,
    val overdueMinor: Long,
    val dueThisWeekMinor: Long,
    val currency: CurrencySpec,
    val todayEpochDay: Long,
) {
    val isEmpty: Boolean get() = dueToday.isEmpty() && upcoming.isEmpty() && overdue.isEmpty()
    val totalDueMinor: Long get() = (dueToday + upcoming + overdue).sumOf { it.amountMinor }
}

class PaymentCenterViewModel(container: AppContainer) : KhataGoViewModel(container) {

    val state: StateFlow<PaymentCenterUiState> = combine(
        app.paymentRepository.observeDueBetween(null, AppDates.endOfWeek(todayEpochDay)),
        app.paymentRepository.observeRecent(limit = 12),
        app.catalogRepository.observeProfile().map { CurrencySpec.fromCode(it?.currencyCode) },
    ) { dueRows, recent, currency ->
        val today = todayEpochDay
        PaymentCenterUiState(
            dueToday = dueRows.filter { it.dueDateEpochDay == today },
            upcoming = dueRows.filter { it.dueDateEpochDay > today },
            overdue = dueRows.filter { it.dueDateEpochDay < today },
            recentlyPaid = recent,
            // "Overdue" here is the sum of the overdue rows themselves. The list and the header are
            // computed from the same `combine` input, so they agree by construction; the *report*
            // uses PaymentRepository.overdueTotalsOnce for the same figure with a ceiling-free window.
            overdueMinor = dueRows.filter { it.dueDateEpochDay < today }.sumOf { it.amountMinor },
            dueThisWeekMinor = dueRows
                .filter { it.dueDateEpochDay in today..AppDates.endOfWeek(today) }
                .sumOf { it.amountMinor },
            currency = currency,
            todayEpochDay = today,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PaymentCenterUiState(
            dueToday = emptyList(),
            upcoming = emptyList(),
            overdue = emptyList(),
            recentlyPaid = emptyList(),
            overdueMinor = 0,
            dueThisWeekMinor = 0,
            currency = CurrencySpec.DEFAULT,
            todayEpochDay = todayEpochDay,
        ),
    )
}

// --------------------------------------------------------------------------- analytics

enum class ChartRange(val months: Int, val label: String) {
    Three(3, "3M"),
    Six(6, "6M"),
    Twelve(12, "12M"),
    ;

    companion object {
        fun of(months: Int): ChartRange = entries.minByOrNull { kotlin.math.abs(it.months - months) } ?: Six
    }
}

data class AnalyticsUiState(
    val range: ChartRange,
    val cashFlow: List<com.khatago.finance.domain.model.CashFlowPoint>,
    val expenseByCategory: List<com.khatago.finance.domain.model.CategorySlice>,
    val incomeBySource: List<com.khatago.finance.domain.model.CategorySlice>,
    val breakdown: com.khatago.finance.data.repo.OutstandingBreakdown,
    val snapshot: com.khatago.finance.domain.calc.SnapshotResult?,
    val paymentDays: List<com.khatago.finance.data.db.dao.PaymentDayTotalRow>,
    val insights: List<com.khatago.finance.domain.calc.Insight>,
    val monthIncomeMinor: Long,
    val monthExpenseMinor: Long,
    val currency: CurrencySpec,
    val monthLabel: String,
    val isLoading: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModel(container: AppContainer) : KhataGoViewModel(container) {

    private val range = kotlinx.coroutines.flow.MutableStateFlow(ChartRange.Six)

    fun select(range: ChartRange) {
        this.range.value = range
    }

    val state: StateFlow<AnalyticsUiState> = combine(
        range,
        app.catalogRepository.observeProfile().map { CurrencySpec.fromCode(it?.currencyCode) },
    ) { r, currency -> r to currency }
        .flatMapLatest { (r, currency) ->
            kotlinx.coroutines.flow.flow {
                val today = todayEpochDay
                val month = SnapshotCalculator.currentMonthRange(today)
                val format: (Long) -> String = { MoneyFormat.format(it, currency) }
                // One `today` for the whole screen: charts, snapshot and insight copy are all derived
                // from the same instant, so the page can never show a chart for this month next to an
                // insight computed for last month.
                emit(
                    AnalyticsUiState(
                        range = r,
                        cashFlow = app.statsRepository.cashFlow(r.months, today),
                        expenseByCategory = app.statsRepository.expenseByCategory(month.first, month.last),
                        incomeBySource = app.statsRepository.incomeBySource(month.first, month.last),
                        breakdown = app.statsRepository.observeOutstandingBreakdown().first(),
                        snapshot = runCatching { app.statsRepository.snapshot(today, format) }.getOrNull(),
                        paymentDays = app.statsRepository.observePaymentDays(month.first, month.last).first(),
                        insights = app.statsRepository.insights(today, format),
                        monthIncomeMinor = app.statsRepository.monthTotalsOnce(month).first,
                        monthExpenseMinor = app.statsRepository.monthTotalsOnce(month).second,
                        currency = currency,
                        monthLabel = AppDates.formatMonthYear(today),
                        isLoading = false,
                    ),
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AnalyticsUiState(
                range = ChartRange.Six,
                cashFlow = emptyList(),
                expenseByCategory = emptyList(),
                incomeBySource = emptyList(),
                breakdown = com.khatago.finance.data.repo.OutstandingBreakdown(
                    shopCreditMinor = 0,
                    loanMinor = 0,
                    emiMinor = 0,
                    borrowingMinor = 0,
                    lendingMinor = 0,
                ),
                snapshot = null,
                paymentDays = emptyList(),
                insights = emptyList(),
                monthIncomeMinor = 0,
                monthExpenseMinor = 0,
                currency = CurrencySpec.DEFAULT,
                monthLabel = AppDates.formatMonthYear(todayEpochDay),
                isLoading = true,
            ),
        )
}

// --------------------------------------------------------------------------- more / search

data class MoreUiState(
    val displayName: String?,
    val currency: CurrencySpec,
    val remindersOn: Boolean,
    val lockOn: Boolean,
    val hasSampleData: Boolean,
)

class MoreViewModel(container: AppContainer) : KhataGoViewModel(container) {

    val state: StateFlow<MoreUiState> = combine(
        app.catalogRepository.observeProfile(),
        app.catalogRepository.observeSetting(AppSettingEntity.NOTIFICATIONS_ENABLED),
        app.catalogRepository.observeSetting(AppSettingEntity.SAMPLE_DATA_LOADED),
    ) { profile, notifications, sample ->
        MoreUiState(
            displayName = profile?.displayName,
            currency = CurrencySpec.fromCode(profile?.currencyCode),
            // Absent means "on": the default must be the friendly one, and the switch is written as
            // soon as the user touches it, so an absent key never persists as a surprise.
            remindersOn = notifications.toSwitchOn(fallback = true),
            lockOn = app.securityRepository.isLockEnabled,
            hasSampleData = sample.toSwitchOn(fallback = false),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MoreUiState(null, CurrencySpec.DEFAULT, true, false, false),
    )
}

data class SearchUiState(
    val query: String,
    val groups: List<SearchGroup>,
    val isBusy: Boolean,
) {
    val hasResults: Boolean get() = groups.any { it.items.isNotEmpty() }
    val totalHits: Int get() = groups.sumOf { it.items.size }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(container: AppContainer) : KhataGoViewModel(container) {

    private val query = kotlinx.coroutines.flow.MutableStateFlow("")

    /**
     * The repository owns the debounce and the minimum-length rule; this class owns only "which query
     * text is current". Keeping the debounce in `SearchRepository` means the same behaviour applies to
     * any other caller (a voice-search entry point, for instance) instead of living in a ViewModel.
     */
    val state: StateFlow<SearchUiState> = query.flatMapLatest { text ->
        val trimmed = text.trim()
        if (trimmed.length < 2) {
            flowOf(SearchUiState(query = text, groups = emptyList(), isBusy = false))
        } else {
            app.searchRepository.search(text).map { groups ->
                SearchUiState(query = text, groups = groups, isBusy = false)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SearchUiState("", emptyList(), false),
    )

    fun onQuery(value: String) {
        query.value = value
    }
}
