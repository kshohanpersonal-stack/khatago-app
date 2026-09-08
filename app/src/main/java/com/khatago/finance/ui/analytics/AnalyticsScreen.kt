package com.khatago.finance.ui.analytics

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatago.finance.AppContainer
import com.khatago.finance.core.money.CurrencySpec
import com.khatago.finance.core.time.AppDates
import com.khatago.finance.domain.calc.Insight
import com.khatago.finance.ui.AnalyticsUiState
import com.khatago.finance.ui.AnalyticsViewModel
import com.khatago.finance.ui.ChartRange
import com.khatago.finance.ui.components.BarChart
import com.khatago.finance.ui.components.BarDatum
import com.khatago.finance.ui.components.DonutChart
import com.khatago.finance.ui.components.DonutSlice
import com.khatago.finance.ui.components.EmptyState
import com.khatago.finance.ui.components.KhataGoCard
import com.khatago.finance.ui.components.KhataGoIcons
import com.khatago.finance.ui.components.LineChart
import com.khatago.finance.ui.components.MoneyFigure
import com.khatago.finance.ui.components.PayoffBar
import com.khatago.finance.ui.components.SectionHeader
import com.khatago.finance.ui.components.SegmentedControl
import com.khatago.finance.ui.components.khataGoViewModel
import com.khatago.finance.ui.components.moneyText
import com.khatago.finance.ui.theme.KhataGoColors
import com.khatago.finance.ui.theme.KhataGoRadii
import com.khatago.finance.ui.theme.KhataGoSpacing
import com.khatago.finance.ui.theme.KhataGoTypography
import java.time.LocalDate

/**
 * Analytics: charts that answer a question, and only charts that answer a question.
 *
 * Every chart states its own window and its own maximum in the header text, because a chart without a
 * stated range is an invitation to over-read it. And every number on this screen is read from the same
 * aggregates the lists use, so "your biggest expense category was Housing, 41,000" can be verified by
 * opening the expense list and summing it — the first thing a careful user will do.
 *
 * The one thing this screen deliberately is not: a *score*. The Financial Snapshot below is named as
 * such and shows its own reasoning, precisely so nobody mistakes it for a credit score. KhataGo has no
 * bureau data, no external signal and no prediction, and saying so on the screen that could tempt a
 * user to believe otherwise is the honest place to say it.
 */
@Composable
fun AnalyticsRoute(
    container: AppContainer,
    onOpenInsights: () -> Unit,
    onOpenReports: () -> Unit,
) {
    val viewModel = khataGoViewModel(::AnalyticsViewModel)
    val state by viewModel.state.collectAsStateWithLifecycle()
    AnalyticsScreen(
        state = state,
        onRangeSelect = viewModel::select,
        onOpenInsights = onOpenInsights,
        onOpenReports = onOpenReports,
    )
}

@Composable
fun AnalyticsScreen(
    state: AnalyticsUiState,
    onRangeSelect: (ChartRange) -> Unit,
    onOpenInsights: () -> Unit = {},
    onOpenReports: () -> Unit = {},
) {
    val currency = state.currency
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
                verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.md),
            ) {
                Column {
                    Text(text = "Analytics", style = KhataGoTypography.headlineMoney)
                    Text(
                        text = "Computed on this phone from your own records. ${state.monthLabel}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SegmentedControl(
                    options = ChartRange.entries.toList(),
                    selected = state.range,
                    onSelect = onRangeSelect,
                    labelOf = { it.label },
                )
            }
        }

        item {
            KhataGoCard(modifier = Modifier.padding(horizontal = KhataGoSpacing.screen)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    MoneyFigure(
                        label = "Income this month",
                        amountText = moneyText(state.monthIncomeMinor, currency),
                        emphasis = com.khatago.finance.ui.components.MoneyEmphasis.Compact,
                        tint = KhataGoColors.Settled,
                        modifier = Modifier.weight(1f),
                    )
                    MoneyFigure(
                        label = "Expense this month",
                        amountText = moneyText(state.monthExpenseMinor, currency),
                        emphasis = com.khatago.finance.ui.components.MoneyEmphasis.Compact,
                        tint = KhataGoColors.Overdue,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(KhataGoSpacing.md))
                val net = state.monthIncomeMinor - state.monthExpenseMinor
                Text(
                    text = (if (net >= 0) "Net +" else "Net ") + moneyText(net, currency) +
                        " this month · " + state.range.label.lowercase() + " window below",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            ChartCard(
                title = "Cash flow",
                subtitle = if (state.cashFlow.isEmpty()) {
                    "No months with records yet"
                } else {
                    "Solid = income, dashed = expense. Tallest bar: " +
                        moneyText(state.cashFlow.maxOf { maxOf(it.incomeMinor, it.expenseMinor) }, currency)
                },
            ) {
                if (state.cashFlow.isEmpty()) {
                    AnalyticsEmpty("Income and expense records you add become this chart.")
                } else {
                    BarChart(
                        data = state.cashFlow.map { point ->
                            BarDatum(
                                label = point.label,
                                valueMinor = point.incomeMinor,
                                color = KhataGoColors.Emerald600,
                                secondValueMinor = point.expenseMinor,
                                secondColor = KhataGoColors.Overdue,
                            )
                        },
                        valueLabel = { moneyText(it, currency) },
                        showValues = true,
                    )
                }
            }
        }

        item {
            ChartCard(
                title = "Where the month went",
                subtitle = "Expense categories, ${state.monthLabel.lowercase()}",
            ) {
                if (state.expenseByCategory.isEmpty()) {
                    AnalyticsEmpty("Record a few expenses and the split appears here.")
                } else {
                    DonutChart(
                        slices = state.expenseByCategory.mapIndexed { index, slice ->
                            DonutSlice(
                                label = slice.name,
                                valueMinor = slice.totalMinor,
                                color = KhataGoColors.ChartRamp[index % KhataGoColors.ChartRamp.size],
                            )
                        },
                        centerTop = moneyText(state.monthExpenseMinor, currency),
                        centerBottom = "total spent",
                    )
                    Spacer(Modifier.height(KhataGoSpacing.md))
                    state.expenseByCategory.forEachIndexed { index, slice ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        KhataGoColors.ChartRamp[index % KhataGoColors.ChartRamp.size],
                                        CircleShape,
                                    ),
                            )
                            Spacer(Modifier.width(KhataGoSpacing.sm))
                            Text(
                                text = slice.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${slice.recordCount} · ${moneyText(slice.totalMinor, currency)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item {
            ChartCard(
                title = "Income by source",
                subtitle = "Only sources you have recorded; no assumptions about your salary",
            ) {
                if (state.incomeBySource.isEmpty()) {
                    AnalyticsEmpty("Add income entries to see the split by source.")
                } else {
                    LineChart(
                        values = state.cashFlow.map { it.incomeMinor },
                        secondValues = state.cashFlow.map { it.expenseMinor },
                        labels = state.cashFlow.map { it.label },
                    )
                    Spacer(Modifier.height(KhataGoSpacing.md))
                    state.incomeBySource.forEach { slice ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(slice.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(
                                text = moneyText(slice.totalMinor, currency),
                                style = KhataGoTypography.figure,
                            )
                        }
                    }
                }
            }
        }

        item {
            ChartCard(
                title = "Payment history",
                subtitle = "One square per day of ${state.monthLabel.lowercase()} — darker means more paid",
            ) {
                PaymentHeatmap(days = state.paymentDays, currency = currency)
            }
        }

        item {
            ChartCard(
                title = "Outstanding by module",
                subtitle = "Live remaining balances, not original amounts",
            ) {
                val parts = listOf(
                    "Shop credit" to state.breakdown.shopCreditMinor,
                    "Loans" to state.breakdown.loanMinor,
                    "EMI" to state.breakdown.emiMinor,
                    "Borrowed" to state.breakdown.borrowingMinor,
                ).filter { it.second > 0L }
                if (parts.isEmpty()) {
                    AnalyticsEmpty("Nothing outstanding — that is the best chart in this app.")
                } else {
                    val top = parts.maxOf { it.second }.coerceAtLeast(1L)
                    parts.forEach { (label, amount) ->
                        Column(modifier = Modifier.padding(vertical = KhataGoSpacing.sm)) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(moneyText(amount, currency), style = KhataGoTypography.figure)
                            }
                            PayoffBar(
                                fraction = amount.toFloat() / top.toFloat(),
                                color = KhataGoColors.Emerald600,
                            )
                        }
                    }
                }
            }
        }

        item {
            SnapshotCard(
                score = state.snapshot?.score,
                bandLabel = state.snapshot?.bandLabel,
                reasons = state.snapshot?.reasons.orEmpty(),
                onOpenInsights = onOpenInsights,
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = KhataGoSpacing.screen)
                    .clickable(onClick = onOpenReports),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = KhataGoIcons.Share,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(KhataGoSpacing.md))
                Text(
                    text = "Export these numbers as CSV",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    KhataGoCard(modifier = Modifier.padding(horizontal = KhataGoSpacing.screen)) {
        SectionHeader(title = title, subtitle = subtitle)
        Spacer(Modifier.height(KhataGoSpacing.md))
        content()
    }
}

@Composable
private fun AnalyticsEmpty(text: String) {
    EmptyState(
        title = "Nothing to chart yet",
        body = text,
        actionLabel = null,
        onAction = null,
    )
}

/**
 * Day-level payment heatmap.
 *
 * A calendar grid answers "am I actually consistent?" better than a line chart would, and it is built
 * from the same `payments` rows the payment centre lists — including empty days, which are the useful
 * half of the picture.
 */
@Composable
private fun PaymentHeatmap(
    days: List<com.khatago.finance.data.db.dao.PaymentDayTotalRow>,
    currency: CurrencySpec,
) {
    if (days.isEmpty()) {
        AnalyticsEmpty("Record a payment and its day lights up here.")
        return
    }
    val byDay = days.associate { it.paidDateEpochDay to it.totalMinor }
    val max = days.maxOf { it.totalMinor }.coerceAtLeast(1L)
    val start = LocalDate.now().minusDays(125).toEpochDay()
    val cells = (0L until 126L).map { start + it }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cells.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { epochDay ->
                    val amount = byDay[epochDay] ?: 0L
                    val intensity = if (amount <= 0L) 0f else (amount.toFloat() / max.toFloat()).coerceIn(0.18f, 1f)
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(
                                if (amount <= 0L) KhataGoColors.Ink100 else KhataGoColors.Emerald600.copy(alpha = intensity),
                                RoundedCornerShape(3.dp),
                            ),
                    )
                }
            }
        }
        Text(
            text = "${days.totalPaid()} paid across ${days.size} day(s) in this window · peak " +
                moneyText(max, currency),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = KhataGoSpacing.xs),
        )
    }
}

/** Money moved in the window; named rather than `sum()` so a reader never wonders which field. */
private fun List<com.khatago.finance.data.db.dao.PaymentDayTotalRow>.totalPaid(): Long =
    sumOf { it.totalMinor }

/**
 * The Financial Snapshot card.
 *
 * Named "snapshot" everywhere, with its reasoning printed underneath, because "score" implies an
 * external judgement with predictive power. This number is the opposite: an arithmetic summary of
 * what you already entered, which is why the reasons are visible rather than hidden behind a tap.
 */
@Composable
private fun SnapshotCard(
    score: Int?,
    bandLabel: String?,
    reasons: List<String>,
    onOpenInsights: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KhataGoSpacing.screen)
            .background(
                Brush.linearGradient(listOf(Color(0xFFF4FBF8), Color(0xFFE6F6EE))),
                RoundedCornerShape(26.dp),
            )
            .clickable(onClick = onOpenInsights)
            .padding(KhataGoSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "FINANCIAL SNAPSHOT",
                    style = MaterialTheme.typography.labelSmall,
                    color = KhataGoColors.Ink500,
                )
                Text(
                    text = bandLabel ?: "Not enough data yet",
                    style = KhataGoTypography.headlineMoney,
                    color = KhataGoColors.Emerald900,
                )
            }
            if (score != null) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .drawBehind {
                            val stroke = 7.dp.toPx()
                            val fraction = (score / 100f).coerceIn(0f, 1f)
                            drawArc(
                                color = KhataGoColors.Emerald200,
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = Offset(stroke / 2f, stroke / 2f),
                                size = Size(size.width - stroke, size.height - stroke),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                            )
                            drawArc(
                                color = KhataGoColors.Emerald700,
                                startAngle = -90f,
                                sweepAngle = 360f * fraction,
                                useCenter = false,
                                topLeft = Offset(stroke / 2f, stroke / 2f),
                                size = Size(size.width - stroke, size.height - stroke),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = stroke,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                ),
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$score",
                        style = MaterialTheme.typography.titleMedium,
                        color = KhataGoColors.Emerald900,
                    )
                }
            }
        }
        reasons.take(3).forEach { reason ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "·  ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = KhataGoColors.Emerald700,
                )
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = KhataGoColors.Ink800,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            text = "Not a credit score. No bureau, no external data, no prediction — a plain " +
                "summary of your own entries on this device.",
            style = MaterialTheme.typography.labelSmall,
            color = KhataGoColors.Ink500,
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
fun InsightLine(insight: Insight, currency: CurrencySpec) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = KhataGoSpacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = when (insight.tone) {
                Insight.Tone.Attention -> KhataGoIcons.DueSoon
                Insight.Tone.Positive -> KhataGoIcons.Check
                Insight.Tone.Neutral -> KhataGoIcons.Pie
            },
            contentDescription = null,
            tint = when (insight.tone) {
                Insight.Tone.Attention -> KhataGoColors.DueSoon
                Insight.Tone.Positive -> KhataGoColors.Settled
                Insight.Tone.Neutral -> KhataGoColors.Info
            },
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(KhataGoSpacing.md))
        Text(
            text = insight.message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}
