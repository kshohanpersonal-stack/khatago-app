package com.khatago.finance.ui.records

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatago.finance.AppContainer
import com.khatago.finance.core.money.CurrencySpec
import com.khatago.finance.ui.RecordListItem
import com.khatago.finance.ui.RecordsFilter
import com.khatago.finance.ui.RecordsModule
import com.khatago.finance.ui.RecordsUiState
import com.khatago.finance.ui.RecordsViewModel
import com.khatago.finance.ui.Routes
import com.khatago.finance.ui.components.EmptyState
import com.khatago.finance.ui.components.KhataGoCard
import com.khatago.finance.ui.components.KhataGoIcons
import com.khatago.finance.ui.components.StatusPill
import com.khatago.finance.ui.components.StatusTone
import com.khatago.finance.ui.components.khataGoViewModel
import com.khatago.finance.ui.components.moneyText
import com.khatago.finance.ui.theme.KhataGoColors
import com.khatago.finance.ui.theme.KhataGoRadii
import com.khatago.finance.ui.theme.KhataGoSpacing
import com.khatago.finance.ui.theme.KhataGoTypography

/**
 * The Records hub: one screen for all five obligation modules plus income and expense.
 *
 * Deliberately a *hub* rather than seven separate lists. A user with a shop khata, two loans and an
 * EMI does not think in tables; they think "what is my situation with Karim?" — so the module switch
 * is at the top, the filter under it, and the totals bar directly above the list where it can be
 * compared against the rows it summarises.
 */
@Composable
fun RecordsRoute(
    container: AppContainer,
    initialModule: String?,
    initialFilter: String?,
    onOpenRecord: (String, Long) -> Unit,
    onAdd: (String) -> Unit,
    onPay: (String, Long) -> Unit,
) {
    val viewModel = khataGoViewModel(::RecordsViewModel)
    var module by remember { mutableStateOf(RecordsModule.fromKey(initialModule)) }
    var filter by remember { mutableStateOf(RecordsFilter.fromKey(initialFilter)) }

    // The ViewModel owns the *data* selection so it survives rotation; the composable owns nothing that
    // could end up out of sync with the list it renders.
    LaunchedEffect(module, filter) {
        viewModel.select(module)
        viewModel.select(filter)
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val shownModule = RecordsModule.fromKey(initialModule)

    RecordsScreen(
        state = state.copy(module = module, filter = filter),
        onModuleSelect = { module = it },
        onFilterSelect = { filter = it },
        onOpenRecord = onOpenRecord,
        onAdd = { onAdd(module.addRoute()) },
        onPay = onPay,
        shownModuleLabel = shownModule.label,
    )
}

@Composable
fun RecordsScreen(
    state: RecordsUiState,
    onModuleSelect: (RecordsModule) -> Unit,
    onFilterSelect: (RecordsFilter) -> Unit,
    onOpenRecord: (String, Long) -> Unit,
    onAdd: () -> Unit,
    onPay: (String, Long) -> Unit,
    shownModuleLabel: String = "",
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(top = KhataGoSpacing.md, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.md),
    ) {
        item {
            Column(
                modifier = Modifier.padding(horizontal = KhataGoSpacing.screen),
                verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.md),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Records",
                        style = KhataGoTypography.headlineMoney,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "Add",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onAdd)
                            .padding(KhataGoSpacing.sm),
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = KhataGoSpacing.screen),
                horizontalArrangement = Arrangement.spacedBy(KhataGoSpacing.sm),
            ) {
                RecordsModule.entries.forEach { option ->
                    ModuleTab(
                        label = option.label,
                        selected = option == state.module,
                        onClick = { onModuleSelect(option) },
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = KhataGoSpacing.screen),
                horizontalArrangement = Arrangement.spacedBy(KhataGoSpacing.sm),
            ) {
                RecordsFilter.entries.forEach { option ->
                    FilterTab(
                        label = option.label,
                        selected = option == state.filter,
                        onClick = { onFilterSelect(option) },
                    )
                }
            }
        }

        if (!state.module.isLedger) {
            item {
                TotalsBar(
                    originalMinor = state.totals.originalMinor,
                    paidMinor = state.totals.paidMinor,
                    remainingMinor = state.totals.remainingMinor,
                    overdueMinor = state.totals.overdueMinor,
                    currency = state.currency,
                    isLedger = false,
                )
            }
        } else {
            item {
                TotalsBar(
                    originalMinor = state.totals.originalMinor,
                    paidMinor = state.totals.paidMinor,
                    remainingMinor = 0L,
                    overdueMinor = 0L,
                    currency = state.currency,
                    isLedger = true,
                )
            }
        }

        if (state.items.isEmpty()) {
            item {
                EmptyState(
                    title = emptyTitleFor(state),
                    body = emptyBodyFor(state),
                    actionLabel = "Add ${state.module.label.lowercase()}",
                    onAction = onAdd,
                )
            }
        } else {
            items(state.items, key = { "${it.typeKey}-${it.id}" }) { item ->
                KhataGoCard(
                    modifier = Modifier.padding(horizontal = KhataGoSpacing.screen),
                    onClick = { onOpenRecord(item.typeKey, item.id) },
                ) {
                    RecordCardRow(
                        item = item,
                        currency = state.currency,
                        onPay = { onPay(item.typeKey, item.id) },
                    )
                }
            }
        }
    }
}

/**
 * Totals bar. Placed *above* the list, not below it: a user checks a list by comparing the first
 * rows against the total, and scrolling to the bottom for the sum makes the number feel like a
 * footer rather than a control.
 */
@Composable
private fun TotalsBar(
    originalMinor: Long,
    paidMinor: Long,
    remainingMinor: Long,
    overdueMinor: Long,
    currency: CurrencySpec,
    isLedger: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KhataGoSpacing.screen)
            .background(KhataGoColors.SurfaceTinted, RoundedCornerShape(KhataGoRadii.card))
            .padding(KhataGoSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.sm),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TotalCell("Original", moneyText(originalMinor, currency), Modifier.weight(1f))
            TotalCell("Paid", moneyText(paidMinor, currency), Modifier.weight(1f))
            if (!isLedger) {
                TotalCell("Remaining", moneyText(remainingMinor, currency), Modifier.weight(1f))
            }
        }
        if (!isLedger && overdueMinor > 0L) {
            Text(
                text = "${moneyText(overdueMinor, currency)} of that is overdue",
                style = MaterialTheme.typography.labelSmall,
                color = KhataGoColors.Overdue,
            )
        }
    }
}

@Composable
private fun TotalCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ModuleTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = KhataGoSpacing.lg, vertical = KhataGoSpacing.sm),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FilterTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(
                if (selected) KhataGoColors.Emerald100 else Color_Transparent,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = KhataGoSpacing.md, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun RecordCardRow(
    item: RecordListItem,
    currency: CurrencySpec,
    onPay: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = KhataGoIcons.forTarget(item.typeKey),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
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
                    text = moneyText(
                        // Ledger rows show the amount itself; obligation rows show what is *left*.
                        if (item.typeKey == "income" || item.typeKey == "expense") item.totalMinor
                        else item.remainingMinor,
                        currency,
                    ),
                    style = KhataGoTypography.sectionMoney,
                )
                Text(
                    text = item.dateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!isLedgerKey(item.typeKey)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(KhataGoSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(
                        text = item.statusLabel,
                        tone = when {
                            item.cancelled -> StatusTone.Cancelled
                            item.isSettled -> StatusTone.Settled
                            item.isOverdue -> StatusTone.Overdue
                            else -> StatusTone.Active
                        },
                    )
                    if (item.installmentsTotal != null && item.installmentsPaid != null) {
                        Text(
                            text = "${item.installmentsPaid}/${item.installmentsTotal} installments",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!item.isSettled && !item.cancelled) {
                    Text(
                        text = "Record payment",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onPay)
                            .padding(KhataGoSpacing.xs),
                    )
                }
            }
            com.khatago.finance.ui.components.PayoffBar(
                fraction = item.payoffFraction,
                label = "Paid ${moneyText(item.paidMinor, currency)} of ${moneyText(item.totalMinor, currency)}",
            )
        }
    }
}

private fun isLedgerKey(key: String): Boolean = key == "income" || key == "expense"

private fun emptyTitleFor(state: RecordsUiState): String = when (state.filter) {
    RecordsFilter.All -> "No ${state.module.label.lowercase()} records yet"
    RecordsFilter.Overdue -> "Nothing overdue here"
    RecordsFilter.Outstanding -> "Nothing outstanding here"
    RecordsFilter.Settled -> "Nothing settled yet"
    RecordsFilter.Cancelled -> "Nothing cancelled"
}

private fun emptyBodyFor(state: RecordsUiState): String = when (state.filter) {
    RecordsFilter.All ->
        "Add a record and it will appear here with its live balance, due date and payment history."
    RecordsFilter.Overdue ->
        "Good news: every ${state.module.label.lowercase()} record in this view is on schedule."
    RecordsFilter.Outstanding ->
        "Every ${state.module.label.lowercase()} record here has been paid off."
    RecordsFilter.Settled ->
        "Settled records stay in the ledger so your history is complete — they are hidden in this view " +
            "because nothing is finished yet."
    RecordsFilter.Cancelled ->
        "Cancelled records are kept and excluded from totals; none exist for this filter."
}

private val Color_Transparent = androidx.compose.ui.graphics.Color.Transparent
