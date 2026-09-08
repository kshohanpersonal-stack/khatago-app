package com.khatago.finance.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.khatago.finance.core.money.CurrencySpec
import com.khatago.finance.core.money.MoneyFormat
import com.khatago.finance.core.time.AppDates
import com.khatago.finance.core.time.DueStatus
import com.khatago.finance.domain.model.LedgerStatus
import com.khatago.finance.domain.model.PayableType

/**
 * Display glue between the pure core and Compose.
 *
 * Kept tiny on purpose: the formatting *rules* live in `core/money` and `core/time` where they are
 * unit-tested on the JVM, and this file only remembers the user's currency and forwards to them.
 * A screen that formatted money itself would be a screen that could round differently from the
 * report the user exports.
 */
@Composable
fun rememberMoneyFormatter(currency: CurrencySpec): (Long) -> String =
    remember(currency) { { minor: Long -> MoneyFormat.format(minor, currency) } }

@Composable
fun moneyText(amountMinor: Long, currency: CurrencySpec): String =
    MoneyFormat.format(amountMinor, currency)

fun statusLabel(status: LedgerStatus): String = status.label

/**
 * One colour decision per status, applied everywhere. Note that colour is *paired* with the text
 * label in every usage — a user who cannot distinguish green from amber still reads "Overdue".
 */
fun StatusTone.Companion.forLedger(status: LedgerStatus): StatusTone = when (status) {
    LedgerStatus.Paid -> StatusTone.Settled
    LedgerStatus.PartiallyPaid -> StatusTone.Active
    LedgerStatus.Overdue -> StatusTone.Overdue
    LedgerStatus.Cancelled -> StatusTone.Cancelled
    LedgerStatus.Unpaid -> StatusTone.Active
}

fun StatusTone.Companion.forDue(status: DueStatus): StatusTone = when (status) {
    DueStatus.Overdue -> StatusTone.Overdue
    DueStatus.DueToday, DueStatus.DueTomorrow -> StatusTone.DueSoon
    DueStatus.DueThisWeek, DueStatus.DueThisMonth -> StatusTone.DueSoon
    DueStatus.Upcoming -> StatusTone.Active
    DueStatus.NoDueDate -> StatusTone.Info
}

fun StatusTone.Companion.forDirection(isOwedToMe: Boolean): StatusTone =
    if (isOwedToMe) StatusTone.OwedToMe else StatusTone.Active

/** Short, human module name for chips and subtitles. */
fun PayableType.shortLabel(): String = when (this) {
    PayableType.ShopCredit -> "Shop"
    PayableType.Loan -> "Loan"
    PayableType.Emi -> "EMI"
    PayableType.Borrowing -> "Borrowed"
    PayableType.Lending -> "Lent"
}

fun dueDateText(dueDateEpochDay: Long?, todayEpochDay: Long): String =
    AppDates.humanDay(dueDateEpochDay, todayEpochDay)

/** Alias kept for call-site readability in list rows (`toneForDue(status)` reads better than `forDue`). */
fun toneForDue(status: DueStatus): StatusTone = StatusTone.forDue(status)

fun toneForLedger(status: LedgerStatus): StatusTone = StatusTone.forLedger(status)

/**
 * Resolves the `typeKey` used by deep links, search hits, activity rows and CSV filenames back to a
 * module. Every one of those carries a *string* because they cross a persistence or OS boundary, so
 * there must be exactly one place that turns it back into an enum — otherwise a rename in one caller
 * silently becomes "unknown record type" in another.
 */
fun payableFromKey(key: String?): PayableType? = when (key?.trim()?.lowercase()) {
    "shop_credit", "shopcredit", "shop" -> PayableType.ShopCredit
    "loan" -> PayableType.Loan
    "emi", "emi_purchase" -> PayableType.Emi
    "borrowing", "borrowed" -> PayableType.Borrowing
    "lending", "lent" -> PayableType.Lending
    else -> null
}

/**
 * Byte sizes for Data management and About.
 *
 * Deliberately binary (1024) and single-decimal: these numbers exist so a person can decide whether
 * to clear attachments, and "1.4 MB" matches what the OS storage screen shows them.
 */
fun humanBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
