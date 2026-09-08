package com.khatago.finance.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.khatago.finance.AppContainer
import com.khatago.finance.ui.analytics.AnalyticsRoute
import com.khatago.finance.ui.analytics.InsightsRoute
import com.khatago.finance.ui.components.EmptyState
import com.khatago.finance.ui.detail.AboutRoute
import com.khatago.finance.ui.detail.BackupRestoreRoute
import com.khatago.finance.ui.detail.CreditDetailRoute
import com.khatago.finance.ui.detail.DataManagementRoute
import com.khatago.finance.ui.detail.EmiDetailRoute
import com.khatago.finance.ui.detail.LedgerDetailRoute
import com.khatago.finance.ui.detail.LoanDetailRoute
import com.khatago.finance.ui.detail.ObligationDetailRoute
import com.khatago.finance.ui.detail.OnboardingRoute
import com.khatago.finance.ui.detail.PaymentSheetRoute
import com.khatago.finance.ui.detail.PersonDetailRoute
import com.khatago.finance.ui.detail.ReportsRoute
import com.khatago.finance.ui.detail.SearchRoute
import com.khatago.finance.ui.detail.SecurityRoute
import com.khatago.finance.ui.detail.SettingsRoute
import com.khatago.finance.ui.detail.ShopDetailRoute
import com.khatago.finance.ui.forms.CreditFormRoute
import com.khatago.finance.ui.forms.EmiFormRoute
import com.khatago.finance.ui.forms.LoanFormRoute
import com.khatago.finance.ui.forms.PersonFormRoute
import com.khatago.finance.ui.forms.ShopFormRoute
import com.khatago.finance.ui.forms.TransactionFormRoute
import com.khatago.finance.ui.home.DashboardRoute
import com.khatago.finance.ui.more.MoreRoute
import com.khatago.finance.ui.more.QuickAddRoute
import com.khatago.finance.ui.payments.PaymentCenterRoute
import com.khatago.finance.ui.records.RecordsRoute

/**
 * Routes in one file, because navigation is the one place where a stringly-typed API is worth it:
 * deep links (`khatago://add`) arrive from the *system* as strings, so keeping every pattern here
 * means launcher shortcuts, notification taps and the nav host cannot drift apart from each other.
 *
 * Shape: `detail/{type}/{id}` — one route for "open a record", whichever module owns it. That is
 * what stops Payment centre, Search, Reports and a reminder notification from each inventing their
 * own navigation for the same tap.
 */
object Routes {
    const val HOME = "home"
    const val RECORDS = "records"
    const val PAYMENTS = "payments"
    const val ANALYTICS = "analytics"
    const val MORE = "more"

    const val QUICK_ADD = "quickAdd"
    const val SEARCH = "search"

    const val ARG_TYPE = "type"
    const val ARG_ID = "id"
    const val ARG_SHOP_ID = "shopId"
    const val ARG_MODULE = "module"
    const val ARG_FILTER = "filter"

    /** Optional arguments, e.g. `records?module=shop_credit&filter=overdue`. */
    fun records(module: String? = null, filter: String? = null): String = when {
        module != null && filter != null -> "$RECORDS?module=$module&filter=$filter"
        module != null -> "$RECORDS?module=$module"
        filter != null -> "$RECORDS?filter=$filter"
        else -> RECORDS
    }

    const val RECORD_DETAIL_PATTERN = "detail/{$ARG_TYPE}/{$ARG_ID}"
    fun recordDetail(type: String, id: Long): String = "detail/$type/$id"

    const val PAYMENT_PATTERN = "pay/{$ARG_TYPE}/{$ARG_ID}"
    fun payment(type: String, id: Long): String = "pay/$type/$id"

    const val SHOP_FORM = "form/shop"
    const val CREDIT_FORM = "form/credit"
    const val LOAN_FORM = "form/loan"
    const val EMI_FORM = "form/emi"
    const val PERSON_FORM = "form/person"
    const val INCOME_FORM = "form/income"
    const val EXPENSE_FORM = "form/expense"

    /**
     * `editId` is a query argument rather than a second route per form because the *form itself* is
     * identical in both modes — only the prefilled values and the title differ. Two routes per form
     * would mean two places to keep in sync every time a field is added, and a field that is saved by
     * "add" but dropped by "edit" is the kind of bug that silently loses money.
     */
    const val ARG_EDIT_ID = "editId"
    const val ARG_DIRECTION = "direction"

    const val SHOP_FORM_PATTERN =
        "$SHOP_FORM?$ARG_EDIT_ID={$ARG_EDIT_ID}"

    fun shopForm(editId: Long? = null): String =
        if (editId != null) "$SHOP_FORM?$ARG_EDIT_ID=$editId" else SHOP_FORM

    const val CREDIT_FORM_PATTERN = "$CREDIT_FORM?$ARG_SHOP_ID={$ARG_SHOP_ID}&$ARG_EDIT_ID={$ARG_EDIT_ID}"
    fun creditForm(shopId: Long? = null, editId: Long? = null): String = buildString {
        append(CREDIT_FORM)
        val args = listOfNotNull(shopId?.let { "$ARG_SHOP_ID=$it" }, editId?.let { "$ARG_EDIT_ID=$it" })
        if (args.isNotEmpty()) {
            append('?')
            append(args.joinToString("&"))
        }
    }

    const val LOAN_FORM_PATTERN = "$LOAN_FORM?$ARG_EDIT_ID={$ARG_EDIT_ID}"
    fun loanForm(editId: Long? = null): String =
        if (editId != null) "$LOAN_FORM?$ARG_EDIT_ID=$editId" else LOAN_FORM

    const val EMI_FORM_PATTERN = "$EMI_FORM?$ARG_EDIT_ID={$ARG_EDIT_ID}"
    fun emiForm(editId: Long? = null): String =
        if (editId != null) "$EMI_FORM?$ARG_EDIT_ID=$editId" else EMI_FORM

    const val PERSON_FORM_PATTERN = "$PERSON_FORM?$ARG_DIRECTION={$ARG_DIRECTION}"

    /** `direction` is `borrowing` or `lending` — the quick-add tiles preselect it so one tap is enough. */
    fun personForm(direction: String? = null): String =
        if (direction != null) "$PERSON_FORM?$ARG_DIRECTION=$direction" else PERSON_FORM

    const val INSIGHTS = "analytics/insights"
    const val REPORTS = "reports"
    const val BACKUP = "settings/backup"
    const val SETTINGS = "settings"
    const val SETTINGS_SECURITY = "settings/security"
    const val SETTINGS_DATA = "settings/data"
    const val ABOUT = "about"
    const val ONBOARDING = "onboarding"

    /** Routes that show the bottom bar. Everything else is a task screen that needs the height. */
    val tabs = listOf(HOME, RECORDS, PAYMENTS, ANALYTICS, MORE)
}

/** Deep-link URIs the app accepts, declared here so MainActivity and notifications agree. */
object DeepLinks {
    const val SCHEME = "khatago"
    const val ADD = "khatago://add"
    const val SEARCH = "khatago://search"
    const val DUE_TODAY = "khatago://due/today"
    const val DUE_TOMORROW = "khatago://due/tomorrow"
    const val RECORDS_OVERDUE = "khatago://records/overdue"
    const val HOME = "khatago://home"

    /**
     * Maps an incoming intent URI to an internal route, or null when it is not ours.
     *
     * Unknown hosts are *ignored* rather than routed to home: a malformed link must not silently
     * navigate a user into a screen they did not ask for (and a foreign app can send anything,
     * because the activity is exported for the launcher).
     */
    fun routeFor(uri: String?): String? = when (uri?.trim()?.lowercase()) {
        ADD, "$SCHEME://quickadd", "$SCHEME://add/credit" -> Routes.QUICK_ADD
        SEARCH -> Routes.SEARCH
        DUE_TODAY, DUE_TOMORROW -> Routes.PAYMENTS
        RECORDS_OVERDUE -> Routes.records(filter = "overdue")
        HOME -> Routes.HOME
        else -> null
    }
}

/**
 * The app frame: one NavHost, five tabs, a global quick-add surface and a payment sheet.
 *
 * Two structural choices that change how the app feels:
 *  - **Tab state is preserved** (`saveState`/`restoreState`) and re-tapping a tab only pops back to
 *    the start destination. A ledger app that loses your scroll position and filters when you peek
 *    at another module is unusable for cross-checking two numbers.
 *  - **The bottom bar is hidden everywhere except the five tabs.** A keyboard plus a floating bar is
 *    how an amount field ends up unreachable on a small phone, and forms are where amounts are typed.
 */
@Composable
fun KhataGoApp(
    container: AppContainer,
    lockEnabled: Boolean,
    unlocked: Boolean,
    onUnlocked: () -> Unit,
    startRoute: String? = null,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()

    // The lock gate wraps *everything*: a locked app must not render a single ledger value into the
    // view hierarchy, because a rendered Composable is a value in memory and in the a11y tree.
    if (lockEnabled && !unlocked) {
        AppLockScreen(container = container, onUnlocked = onUnlocked, modifier = modifier)
        return
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBars = Routes.tabs.any { it == currentRoute }

    Scaffold(
        modifier = modifier,
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBars) {
                KhataGoBottomBar(
                    selectedRoute = currentRoute,
                    onSelect = { tab ->
                        navController.navigate(tab) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startRoute ?: Routes.HOME,
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            composable(Routes.HOME) {
                DashboardRoute(
                    container = container,
                    onOpenRecord = { type, id -> navController.navigate(Routes.recordDetail(type, id)) },
                    onQuickAdd = { navController.navigate(Routes.QUICK_ADD) },
                    onOpenPayments = { navController.navigate(Routes.PAYMENTS) },
                    onOpenAnalytics = { navController.navigate(Routes.ANALYTICS) },
                    onOpenSearch = { navController.navigate(Routes.SEARCH) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenOnboarding = { navController.navigate(Routes.ONBOARDING) },
                )
            }
            composable(
                route = "${Routes.RECORDS}?${Routes.ARG_MODULE}={${Routes.ARG_MODULE}}&${Routes.ARG_FILTER}={${Routes.ARG_FILTER}}",
                arguments = listOf(
                    // `nullable = true` + a "-1"/empty sentinel is deliberate: Navigation's optional
                    // query args only round-trip cleanly for strings, and an absent filter must mean
                    // "no filter", not "filter equal to the empty string".
                    navArgument(Routes.ARG_MODULE) { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument(Routes.ARG_FILTER) { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                RecordsRoute(
                    container = container,
                    initialModule = entry.arguments?.getString(Routes.ARG_MODULE),
                    initialFilter = entry.arguments?.getString(Routes.ARG_FILTER),
                    onOpenRecord = { type, id -> navController.navigate(Routes.recordDetail(type, id)) },
                    onAdd = { route -> navController.navigate(route) },
                    onPay = { type, id -> navController.navigate(Routes.payment(type, id)) },
                )
            }
            composable(Routes.PAYMENTS) {
                PaymentCenterRoute(
                    container = container,
                    onOpenRecord = { type, id -> navController.navigate(Routes.recordDetail(type, id)) },
                    onPay = { type, id -> navController.navigate(Routes.payment(type, id)) },
                )
            }
            composable(Routes.ANALYTICS) {
                AnalyticsRoute(
                    container = container,
                    onOpenInsights = { navController.navigate(Routes.INSIGHTS) },
                    onOpenReports = { navController.navigate(Routes.REPORTS) },
                )
            }
            composable(Routes.MORE) {
                MoreRoute(
                    container = container,
                    onOpen = { route -> navController.navigate(route) },
                    onOpenRecords = { module -> navController.navigate(Routes.records(module)) },
                )
            }
            composable(Routes.QUICK_ADD) {
                QuickAddRoute(
                    container = container,
                    onDismiss = { navController.popBackStack() },
                    onNavigate = { route ->
                        navController.popBackStack()
                        navController.navigate(route)
                    },
                )
            }
            composable(Routes.SEARCH) {
                SearchRoute(
                    container = container,
                    onOpenRecord = { type, id -> navController.navigate(Routes.recordDetail(type, id)) },
                    onDismiss = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.RECORD_DETAIL_PATTERN,
                arguments = listOf(
                    navArgument(Routes.ARG_TYPE) { type = NavType.StringType },
                    navArgument(Routes.ARG_ID) { type = NavType.LongType },
                ),
            ) { entry ->
                val type = entry.arguments?.getString(Routes.ARG_TYPE).orEmpty()
                val id = entry.arguments?.getLong(Routes.ARG_ID) ?: 0L
                RecordDetailDispatcher(
                    container = container,
                    navController = navController,
                    type = type,
                    id = id,
                )
            }
            composable(
                route = Routes.PAYMENT_PATTERN,
                arguments = listOf(
                    navArgument(Routes.ARG_TYPE) { type = NavType.StringType },
                    navArgument(Routes.ARG_ID) { type = NavType.LongType },
                ),
            ) { entry ->
                PaymentSheetRoute(
                    container = container,
                    type = entry.arguments?.getString(Routes.ARG_TYPE).orEmpty(),
                    id = entry.arguments?.getLong(Routes.ARG_ID) ?: 0L,
                    onDismiss = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.SHOP_FORM_PATTERN,
                arguments = listOf(idArg(Routes.ARG_EDIT_ID)),
            ) { entry ->
                ShopFormRoute(
                    container = container,
                    editId = entry.positiveId(Routes.ARG_EDIT_ID),
                    onDone = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.CREDIT_FORM_PATTERN,
                arguments = listOf(idArg(Routes.ARG_SHOP_ID), idArg(Routes.ARG_EDIT_ID)),
            ) { entry ->
                CreditFormRoute(
                    container = container,
                    preselectedShopId = entry.positiveId(Routes.ARG_SHOP_ID),
                    creditId = entry.positiveId(Routes.ARG_EDIT_ID),
                    onDone = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.LOAN_FORM_PATTERN,
                arguments = listOf(idArg(Routes.ARG_EDIT_ID)),
            ) { entry ->
                LoanFormRoute(
                    container = container,
                    editId = entry.positiveId(Routes.ARG_EDIT_ID),
                    onDone = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.EMI_FORM_PATTERN,
                arguments = listOf(idArg(Routes.ARG_EDIT_ID)),
            ) { entry ->
                EmiFormRoute(
                    container = container,
                    editId = entry.positiveId(Routes.ARG_EDIT_ID),
                    onDone = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.PERSON_FORM_PATTERN,
                arguments = listOf(
                    navArgument(Routes.ARG_DIRECTION) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                PersonFormRoute(
                    container = container,
                    initialDirection = entry.arguments?.getString(Routes.ARG_DIRECTION),
                    onDone = { navController.popBackStack() },
                )
            }
            composable(Routes.INCOME_FORM) {
                TransactionFormRoute(
                    container = container,
                    kind = "income",
                    onDone = { navController.popBackStack() },
                )
            }
            composable(Routes.EXPENSE_FORM) {
                TransactionFormRoute(
                    container = container,
                    kind = "expense",
                    onDone = { navController.popBackStack() },
                )
            }
            composable(Routes.INSIGHTS) {
                InsightsRoute(container = container, onBack = { navController.popBackStack() })
            }
            composable(Routes.REPORTS) {
                ReportsRoute(container = container, onBack = { navController.popBackStack() })
            }
            composable(Routes.BACKUP) {
                BackupRestoreRoute(container = container, onBack = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsRoute(
                    container = container,
                    onBack = { navController.popBackStack() },
                    onOpen = { route -> navController.navigate(route) },
                )
            }
            composable(Routes.SETTINGS_SECURITY) {
                SecurityRoute(container = container, onBack = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS_DATA) {
                DataManagementRoute(container = container, onBack = { navController.popBackStack() })
            }
            composable(Routes.ABOUT) {
                AboutRoute(container = container, onBack = { navController.popBackStack() })
            }
            composable(Routes.ONBOARDING) {
                OnboardingRoute(
                    container = container,
                    onFinished = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}

/**
 * Picks the detail screen for `(type, id)`. Every entry point — search, payment centre, a dashboard
 * tile, a reminder tap — arrives here, so one tap on one row means the same screen from anywhere.
 */
@Composable
private fun RecordDetailDispatcher(
    container: AppContainer,
    navController: NavHostController,
    type: String,
    id: Long,
) {
    val back = { navController.popBackStack() }
    val pay = { navController.navigate(Routes.payment(type, id)) }
    when (type) {
        "shop" -> ShopDetailRoute(
            container = container,
            shopId = id,
            onBack = back,
            onOpenCredit = { creditId -> navController.navigate(Routes.recordDetail("shop_credit", creditId)) },
            onAddCredit = { navController.navigate(Routes.creditForm(id)) },
            onPay = pay,
            onEdit = { navController.navigate(Routes.shopForm(id)) },
        )
        "shop_credit" -> CreditDetailRoute(
            container = container,
            creditId = id,
            onBack = back,
            onPay = pay,
            onEdit = { navController.navigate(Routes.CREDIT_FORM) },
        )
        "loan" -> LoanDetailRoute(
            container = container,
            obligationId = id,
            kind = "loan",
            onBack = back,
            onPay = pay,
            onEdit = { navController.navigate(Routes.loanForm(id)) },
        )
        "emi" -> LoanDetailRoute(
            container = container,
            obligationId = id,
            kind = "emi",
            onBack = back,
            onPay = pay,
            onEdit = { navController.navigate(Routes.emiForm(id)) },
        )
        "person" -> PersonDetailRoute(
            container = container,
            personId = id,
            onBack = back,
            onOpenRecord = { childType, childId ->
                navController.navigate(Routes.recordDetail(childType, childId))
            },
        )
        "borrowing", "lending" -> ObligationDetailRoute(
            container = container,
            type = type,
            id = id,
            onBack = back,
            onPay = pay,
        )
        "income", "expense" -> LedgerDetailRoute(
            container = container,
            kind = type,
            id = id,
            onBack = back,
        )
        else -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                title = "Nothing to show",
                body = "That record type (“$type”) is not known to this version of KhataGo, so " +
                    "nothing was changed. Go back and try again.",
                actionLabel = "Go back",
                onAction = back,
            )
        }
    }
}

/**
 * Optional Long argument. `-1` is the sentinel for "absent": Navigation Compose has no nullable
 * LongType with a null default that round-trips through a saved instance state bundle, and every real
 * id in this app is autogenerated and therefore strictly positive.
 */
private fun idArg(name: String) = navArgument(name) { type = NavType.LongType; defaultValue = -1L }

/** The id argument, or null when it was not supplied (see [idArg] for why -1 is safe as "absent"). */
private fun NavBackStackEntry?.positiveId(name: String): Long? =
    this?.arguments?.getLong(name)?.takeIf { it > 0L }

/** True when [route] is one of the tab destinations (used by the bottom bar for selected state). */
internal fun isTabDestination(destination: androidx.navigation.NavDestination?, route: String?): Boolean =
    destination?.hierarchy?.any { it.route == route } == true
