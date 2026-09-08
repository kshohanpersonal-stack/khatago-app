package com.khatago.finance.data.db.dao

/**
 * Read-only projections returned by aggregate SQL.
 *
 * These exist so that "sum of the payments recorded against every credit of this shop" is computed
 * by SQLite in one pass rather than by loading rows into memory and adding them in Kotlin. Two
 * consequences matter for a financial app:
 *  - the dashboard and the ledger view run off the *same* arithmetic, so they cannot disagree;
 *  - the app stays fast with thousands of records instead of degrading linearly.
 *
 * Every projection holds `Long` minor units; none holds a "remaining" value, because remaining is
 * always derived (original - paid) in the domain layer.
 */

/** Sum of a set of obligations and the payments recorded against them. */
data class BalanceRow(
    val id: Long,
    val totalMinor: Long,
    val paidMinor: Long,
)

/** Balance joined with the descriptive fields a list row needs, so lists avoid N+1 queries. */
data class CreditRow(
    val id: Long,
    val shopId: Long,
    val shopName: String,
    val productName: String,
    val quantity: Long,
    val unitPriceMinor: Long,
    val totalMinor: Long,
    val paidMinor: Long,
    val purchaseDateEpochDay: Long,
    val dueDateEpochDay: Long?,
    val cancelled: Boolean,
    val notes: String?,
)

data class ObligationRow(
    val id: Long,
    val title: String,
    val subtitle: String,
    val totalMinor: Long,
    val paidMinor: Long,
    val dueDateEpochDay: Long?,
    val cancelled: Boolean,
)

/** Per-shop rollup used by the shop list and by the shop account header. */
data class ShopSummaryRow(
    val id: Long,
    val name: String,
    val phone: String?,
    val ownerName: String?,
    val archived: Boolean,
    val creditCount: Int,
    val activeCount: Int,
    val totalMinor: Long,
    val paidMinor: Long,
    val overdueMinor: Long,
    val lastCreditDateEpochDay: Long?,
    val nextDueDateEpochDay: Long?,
)

/** One row of the unified "due / overdue" queue that feeds Payments, the dashboard and reminders. */
data class DueRow(
    val payableType: String,
    val payableId: Long,
    val title: String,
    val subtitle: String,
    val dueDateEpochDay: Long,
    val amountMinor: Long,
    val totalMinor: Long,
    val paidMinor: Long,
)

data class DueSummaryRow(
    val count: Int,
    val totalMinor: Long,
)

/** A ledger entry (income or expense) in unified form, for Records / Recent activity / search. */
data class LedgerRow(
    val id: Long,
    val kind: String,
    val amountMinor: Long,
    val categoryName: String,
    val counterparty: String?,
    val transactionDateEpochDay: Long,
    val methodName: String,
    val note: String?,
)

data class MonthlyTotalRow(
    val monthKey: String,
    val totalMinor: Long,
    val recordCount: Int,
)

data class CategoryTotalRow(
    val name: String,
    val totalMinor: Long,
    val recordCount: Int,
)

/** Payment history bucketed by day for the "Payment history" chart. */
data class PaymentDayTotalRow(
    val paidDateEpochDay: Long,
    val totalMinor: Long,
    val recordCount: Int,
)

/** A row of the unified activity stream (obligations + payments + income + expense). */
data class ActivityRow(
    val id: Long,
    val typeKey: String,
    val title: String,
    val subtitle: String,
    val amountMinor: Long,
    val dateEpochDay: Long,
)

/** Global search hit. `typeKey` + `id` is enough to build a navigation route. */
data class SearchRow(
    val typeKey: String,
    val id: Long,
    val title: String,
    val subtitle: String,
    val amountMinor: Long,
)

/** Person rollup for the borrowed / lent lists. */
data class PersonBalanceRow(
    val id: Long,
    val name: String,
    val relationship: String,
    val phone: String?,
    val owedToThemMinor: Long,
    val owedToMeMinor: Long,
    val nextDueDateEpochDay: Long?,
)

/** Scalar total for headline tiles; nullable so "no rows" is distinguishable from zero. */
data class TotalRow(
    val totalMinor: Long,
)

/** Row shape for [ShopDao.deletionImpact]. */
data class ShopDeletionRow(
    val creditCount: Int,
    val paymentCount: Int,
)
