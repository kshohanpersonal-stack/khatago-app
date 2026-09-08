package com.khatago.finance.ui.payments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatago.finance.AppContainer
import com.khatago.finance.core.money.CurrencySpec
import com.khatago.finance.core.time.AppDates
import com.khatago.finance.domain.model.DueItem
import com.khatago.finance.domain.model.PaymentEntry
import com.khatago.finance.ui.PaymentCenterUiState
import com.khatago.finance.ui.PaymentCenterViewModel
import com.khatago.finance.ui.components.EmptyState
import com.khatago.finance.ui.components.KhataGoCard
import com.khatago.finance.ui.components.KhataGoIcons
import com.khatago.finance.ui.components.MoneyFigure
import com.khatago.finance.ui.components.SectionHeader
import com.khatago.finance.ui.components.StatusPill
import com.khatago.finance.ui.components.StatusTone
import com.khatago.finance.ui.components.khataGoViewModel
import com.khatago.finance.ui.components.moneyText
import com.khatago.finance.ui.theme.KhataGoColors
import com.khatago.finance.ui.theme.KhataGoRadii
import com.khatago.finance.ui.theme.KhataGoSpacing
import com.khatago.finance.ui.theme.KhataGoTypography

/**
 * The payment centre: everything due, overdue, and recently paid, in one queue.
 *
 * Grouping order is **overdue → due today → upcoming**, because acting on a missed payment matters
 * more than planning a future one, and the most urgent thing on the screen must be the first thing
 * the thumb reaches. Each row offers a one-tap "Pay" that opens the payment sheet prefilled with the
 * exact remaining amount — the most common action is "pay exactly what is due", and making that
 * require typing is a small cruelty repeated thousands of times.
 */
@Composable
fun PaymentCenterRoute(
    container: AppContainer,
    onOpenRecord: (String, Long) -> Unit,
    onPay: (String, Long) -> Unit,
) {
    val viewModel = khataGoViewModel(::PaymentCenterViewModel)
    val state by viewModel.state.collectAsStateWithLifecycle()
    PaymentCenterScreen(
        state = state,
        onOpenRecord = onOpenRecord,
        onPay = onPay,
    )
}

@Composable
fun PaymentCenterScreen(
    state: PaymentCenterUiState,
    onOpenRecord: (String, Long) -> Unit,
    onPay: (String, Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(top = KhataGoSpacing.md, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.lg),
    ) {
        item {
            Column(
                modifier = Modifier.padding(horizontal = KhataGoSpacing.screen),
                verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.xs),
            ) {
                Text(text = "Payments", style = KhataGoTypography.headlineMoney)
                Text(
                    text = "Everything that needs money, and everything you have paid lately.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = KhataGoSpacing.screen)
                    .background(KhataGoColors.HeroGradient, RoundedCornerShape(KhataGoRadii.card))
                    .padding(KhataGoSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(KhataGoSpacing.lg),
            ) {
                MoneyFigure(
                    label = "Due this week",
                    amountText = moneyText(state.dueThisWeekMinor, state.currency),
                    emphasis = com.khatago.finance.ui.components.MoneyEmphasis.Compact,
                    tint = KhataGoColors.Emerald900,
                    modifier = Modifier.weight(1f),
                )
                MoneyFigure(
                    label = "Overdue",
                    amountText = moneyText(state.overdueMinor, state.currency),
                    emphasis = com.khatago.finance.ui.components.MoneyEmphasis.Compact,
                    tint = if (state.overdueMinor > 0) KhataGoColors.Overdue else KhataGoColors.Ink500,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (state.isEmpty) {
            item {
                EmptyState(
                    title = "Nothing due",
                    body = "No payment falls due within the scheduled window. Add an obligation with a " +
                        "due date and it will show up here automatically.",
                    actionLabel = null,
                    onAction = null,
                )
            }
        }

        if (state.overdue.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Overdue",
                    subtitle = "${state.overdue.size} item(s) · ${moneyText(state.overdue.sumOf { it.amountMinor }, state.currency)}",
                )
            }
            items(state.overdue, key = { "overdue-${it.type.displayName}-${it.obligationId}" }) { item ->
                DueCard(
                    item = item,
                    currency = state.currency,
                    todayEpochDay = state.todayEpochDay,
                    tone = StatusTone.Overdue,
                    onOpenRecord = onOpenRecord,
                    onPay = onPay,
                )
            }
        }

        if (state.dueToday.isNotEmpty()) {
            item {
                SectionHeader(title = "Due today", subtitle = "Today is ${AppDates.formatMedium(state.todayEpochDay)}")
            }
            items(state.dueToday, key = { "today-${it.type.displayName}-${it.obligationId}" }) { item ->
                DueCard(
                    item = item,
                    currency = state.currency,
                    todayEpochDay = state.todayEpochDay,
                    tone = StatusTone.DueSoon,
                    onOpenRecord = onOpenRecord,
                    onPay = onPay,
                )
            }
        }

        if (state.upcoming.isNotEmpty()) {
            item { SectionHeader(title = "Upcoming", subtitle = "Next seven days") }
            items(state.upcoming, key = { "up-${it.type.displayName}-${it.obligationId}" }) { item ->
                DueCard(
                    item = item,
                    currency = state.currency,
                    todayEpochDay = state.todayEpochDay,
                    tone = StatusTone.Active,
                    onOpenRecord = onOpenRecord,
                    onPay = onPay,
                )
            }
        }

        if (state.recentlyPaid.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Recently paid",
                    subtitle = "The last ${state.recentlyPaid.size} payment(s) you recorded",
                )
            }
            item {
                KhataGoCard(modifier = Modifier.padding(horizontal = KhataGoSpacing.screen)) {
                    state.recentlyPaid.forEach { entry ->
                        PaymentLine(
                            entry = entry,
                            currency = state.currency,
                            todayEpochDay = state.todayEpochDay,
                            onClick = { onOpenRecord(entry.installmentId?.toString() ?: "", entry.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DueCard(
    item: DueItem,
    currency: CurrencySpec,
    todayEpochDay: Long,
    tone: StatusTone,
    onOpenRecord: (String, Long) -> Unit,
    onPay: (String, Long) -> Unit,
) {
    KhataGoCard(
        modifier = Modifier.padding(horizontal = KhataGoSpacing.screen),
        onClick = { onOpenRecord(item.type.displayName, item.obligationId) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(tone.background, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = KhataGoIcons.forPayable(item.type),
                    contentDescription = null,
                    tint = tone.content,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(KhataGoSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = moneyText(item.amountMinor, currency),
                    style = KhataGoTypography.sectionMoney,
                    color = if (tone === StatusTone.Overdue) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
                Text(
                    text = AppDates.humanDay(item.dueDateEpochDay, todayEpochDay),
                    style = MaterialTheme.typography.labelSmall,
                    color = tone.content,
                )
            }
        }
        Spacer(Modifier.height(KhataGoSpacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(
                text = if (item.paidMinor > 0L) {
                    "Part paid · ${moneyText(item.paidMinor, currency)} of ${moneyText(item.totalMinor, currency)}"
                } else {
                    "Nothing paid yet"
                },
                tone = tone,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "Pay now",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { onPay(item.type.displayName, item.obligationId) }
                    .background(KhataGoColors.Emerald100, RoundedCornerShape(12.dp))
                    .padding(horizontal = KhataGoSpacing.md, vertical = KhataGoSpacing.sm),
            )
        }
    }
}

@Composable
private fun PaymentLine(
    entry: PaymentEntry,
    currency: CurrencySpec,
    todayEpochDay: Long,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = KhataGoSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = KhataGoIcons.Paid,
            contentDescription = null,
            tint = KhataGoColors.Settled,
        )
        Spacer(Modifier.width(KhataGoSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.methodName + (entry.reference?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.installmentNumber?.let { "Installment $it · " }
                    .orEmpty() + AppDates.humanDay(entry.paidDateEpochDay, todayEpochDay),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = moneyText(entry.amountMinor, currency),
            style = KhataGoTypography.figure,
        )
    }
}
