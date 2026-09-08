package com.khatago.finance.ui.home

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatago.finance.AppContainer
import com.khatago.finance.core.money.MoneyFormat
import com.khatago.finance.core.time.AppDates
import com.khatago.finance.domain.model.ActivityItem
import com.khatago.finance.domain.model.DashboardSnapshot
import com.khatago.finance.domain.model.DueItem
import com.khatago.finance.domain.model.PayableType
import com.khatago.finance.ui.HomeUiState
import com.khatago.finance.ui.HomeViewModel
import com.khatago.finance.ui.Routes
import com.khatago.finance.ui.components.EmptyState
import com.khatago.finance.ui.components.InfoTile
import com.khatago.finance.ui.components.KhataGoCard
import com.khatago.finance.ui.components.KhataGoIcons
import com.khatago.finance.ui.components.MoneyFigure
import com.khatago.finance.ui.components.QuickAddIcon
import com.khatago.finance.ui.components.RecordRow
import com.khatago.finance.ui.components.SectionHeader
import com.khatago.finance.ui.components.StatusPill
import com.khatago.finance.ui.components.StatusTone
import com.khatago.finance.ui.components.khataGoViewModel
import com.khatago.finance.ui.components.moneyText
import com.khatago.finance.ui.components.toneForDue
import com.khatago.finance.ui.theme.KhataGoColors
import com.khatago.finance.ui.theme.KhataGoSpacing
import com.khatago.finance.ui.theme.KhataGoTypography

/**
 * The dashboard: one screen, four questions, in the order a person actually asks them.
 *
 *  1. *What do I owe?* — the hero figure, because that is what makes someone open a khata app.
 *  2. *What is due now?* — the actionable list, with a Pay button on each row.
 *  3. *How is this month going?* — income, expense and what has been paid.
 *  4. *What happened lately?* — recent activity, so a missed entry is noticed the same day.
 *
 * Nothing here is a marketing widget: no motivational rings, no "you're doing great!" without a
 * number behind it. And the greeting is genuinely a greeting (name + time of day) rather than a
 * data point, because the one place an app can feel human is the line above the numbers.
 */
@Composable
fun DashboardRoute(
    container: AppContainer,
    onOpenRecord: (String, Long) -> Unit,
    onQuickAdd: () -> Unit,
    onOpenPayments: () -> Unit,
    onOpenAnalytics: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOnboarding: () -> Unit,
) {
    val viewModel = khataGoViewModel(::HomeViewModel)
    val state by viewModel.state.collectAsStateWithLifecycle()
    DashboardScreen(
        state = state,
        onOpenRecord = onOpenRecord,
        onQuickAdd = onQuickAdd,
        onOpenPayments = onOpenPayments,
        onOpenAnalytics = onOpenAnalytics,
        onOpenSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        onOpenOnboarding = onOpenOnboarding,
    )
}

@Composable
fun DashboardScreen(
    state: HomeUiState,
    onOpenRecord: (String, Long) -> Unit = { _, _ -> },
    onQuickAdd: () -> Unit = {},
    onOpenPayments: () -> Unit = {},
    onOpenAnalytics: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenOnboarding: () -> Unit = {},
) {
    val snapshot = state.dashboard
    val currency = state.currency
    val today = snapshot.todayEpochDay

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(top = KhataGoSpacing.lg, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.lg),
    ) {
        item {
            DashboardHeader(
                name = snapshot.greetingName,
                todayEpochDay = today,
                onSearch = onOpenSearch,
                onSettings = onOpenSettings,
            )
        }

        if (!state.onboardingComplete) {
            item {
                KhataGoCard(onClick = onOpenOnboarding) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Set up KhataGo", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Two minutes: your name, your currency, and what you mostly track.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(KhataGoSpacing.sm))
                        QuickAddIcon()
                    }
                }
            }
        }

        item {
            HeroBalanceCard(
                snapshot = snapshot,
                currency = currency,
                onOpenAnalytics = onOpenAnalytics,
                onQuickAdd = onQuickAdd,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(KhataGoSpacing.md)) {
                Box(modifier = Modifier.weight(1f)) {
                    InfoTile(
                        label = "Owed to me",
                        value = moneyText(snapshot.totalOwedToMeMinor, currency),
                        hint = if (snapshot.lendingOutstandingMinor > 0) {
                            "People and shops that owe you"
                        } else {
                            "Nothing recorded yet"
                        },
                        valueColor = KhataGoColors.OwedToMe,
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    InfoTile(
                        label = "This month",
                        value = moneyText(snapshot.netCashFlowMinor, currency),
                        hint = "Income ${moneyText(snapshot.monthIncomeMinor, currency)} · " +
                            "expense ${moneyText(snapshot.monthExpenseMinor, currency)}",
                        valueColor = if (snapshot.netCashFlowMinor >= 0) {
                            KhataGoColors.Settled
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }

        // Due soon first: an app about money earns its keep by preventing a missed payment, not by
        // displaying a pretty history.
        item {
            SectionHeader(
                title = "Due this week",
                subtitle = if (snapshot.dueSoonCount > 0) {
                    "${snapshot.dueSoonCount} payment(s) · ${moneyText(snapshot.dueSoonMinor, currency)}"
                } else {
                    "Nothing scheduled for the next seven days"
                },
                action = {
                    if (snapshot.upcoming.isNotEmpty()) {
                        Text(
                            text = "All payments",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable(onClick = onOpenPayments)
                                .padding(KhataGoSpacing.xs),
                        )
                    }
                },
            )
        }

        if (snapshot.overdueCount > 0) {
            item {
                OverdueBanner(
                    count = snapshot.overdueCount,
                    amountMinor = snapshot.overdueMinor,
                    currency = currency,
                    onClick = onOpenPayments,
                )
            }
        }

        if (snapshot.upcoming.isEmpty()) {
            item {
                EmptyState(
                    title = if (snapshot.hasAnyRecord) "Nothing due this week" else "Start your ledger",
                    body = if (snapshot.hasAnyRecord) {
                        "No payments fall due in the next seven days. KhataGo will remind you the day " +
                            "before anything is due."
                    } else {
                        "Add your first shop credit, loan or EMI and this page becomes your payment " +
                            "plan. Everything stays on this phone."
                    },
                    actionLabel = "Add a record",
                    onAction = onQuickAdd,
                )
            }
        } else {
            items(snapshot.upcoming, key = { "${it.type.displayName}-${it.obligationId}" }) { item ->
                KhataGoCard(
                    modifier = Modifier.padding(horizontal = KhataGoSpacing.screen),
                    onClick = { onOpenRecord(item.type.displayName, item.obligationId) },
                ) {
                    DueRowContent(
                        item = item,
                        currency = currency,
                        todayEpochDay = today,
                        onOpenRecord = { onOpenRecord(item.type.displayName, item.obligationId) },
                    )
                }
            }
        }

        if (!state.widgetsHidden && snapshot.recentActivity.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Recent activity",
                    subtitle = "Records and payments, newest first",
                )
            }
            item {
                KhataGoCard(modifier = Modifier.padding(horizontal = KhataGoSpacing.screen)) {
                    snapshot.recentActivity.take(6).forEach { activity ->
                        ActivityLine(
                            activity = activity,
                            currency = currency,
                            todayEpochDay = today,
                            onClick = { onOpenRecord(activity.typeKey, activity.id) },
                        )
                    }
                }
            }
        }

        item {
            OutstandingBreakdownSection(
                snapshot = snapshot,
                currency = currency,
                onOpenRecordModule = { module -> onOpenAnalytics() },
            )
        }

        item {
            OfflineFootnote()
        }
    }
}

@Composable
private fun DashboardHeader(
    name: String?,
    todayEpochDay: Long,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = KhataGoSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = greetingFor(java.time.LocalTime.now().hour),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = name?.takeIf { it.isNotBlank() } ?: "Your ledger",
                    style = KhataGoTypography.headlineMoney,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconAction(icon = KhataGoIcons.Search, description = "Search", onClick = onSearch)
            Spacer(Modifier.width(KhataGoSpacing.sm))
            IconAction(icon = KhataGoIcons.Lock, description = "Settings", onClick = onSettings)
        }
        Text(
            text = AppDates.formatMediumWithWeekday(todayEpochDay),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Time-of-day greeting. English-only product, so the copy is written here rather than localised. */
internal fun greetingFor(hour: Int): String = when (hour) {
    in 0..4 -> "Still up"
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..20 -> "Good evening"
    else -> "Wrapping up the day"
}

@Composable
private fun IconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun HeroBalanceCard(
    snapshot: DashboardSnapshot,
    currency: com.khatago.finance.core.money.CurrencySpec,
    onOpenAnalytics: () -> Unit,
    onQuickAdd: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KhataGoSpacing.screen)
            .background(KhataGoColors.HeroGradient, RoundedCornerShape(28.dp))
            .clickable(onClick = onOpenAnalytics)
            .padding(KhataGoSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.lg),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                MoneyFigure(
                    label = "Total I owe",
                    amountText = MoneyFormat.format(snapshot.totalIOweMinor, currency),
                    emphasis = com.khatago.finance.ui.components.MoneyEmphasis.Hero,
                    supportingText = if (snapshot.overdueCount > 0) {
                        "${snapshot.overdueCount} overdue · ${moneyText(snapshot.overdueMinor, currency)}"
                    } else {
                        "Nothing overdue right now"
                    },
                    tint = if (snapshot.overdueCount > 0) KhataGoColors.Overdue else KhataGoColors.Emerald900,
                )
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.White.copy(alpha = 0.75f), CircleShape)
                    .clickable(onClick = onQuickAdd),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = KhataGoIcons.Add,
                    contentDescription = "Quick add",
                    tint = KhataGoColors.Emerald700,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KhataGoSpacing.md)) {
            // The share-of-row weight is applied *here*: `Modifier.weight` is a RowScope member, so a
            // child composable cannot reach it on its own — the scope has to be the caller.
            MiniMetric("Paid this month", moneyText(snapshot.monthPaidMinor, currency), Modifier.weight(1f))
            MiniMetric("Owed to me", moneyText(snapshot.totalOwedToMeMinor, currency), Modifier.weight(1f))
            MiniMetric("Due soon", "${snapshot.dueSoonCount}", Modifier.weight(1f))
        }
    }
}

@Composable
private fun MiniMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.62f), RoundedCornerShape(14.dp))
            .padding(horizontal = KhataGoSpacing.md, vertical = KhataGoSpacing.sm),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = KhataGoColors.Ink500,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = KhataGoColors.Ink900,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OverdueBanner(
    count: Int,
    amountMinor: Long,
    currency: com.khatago.finance.core.money.CurrencySpec,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KhataGoSpacing.screen)
            .background(KhataGoColors.OverdueBg, RoundedCornerShape(KhataGoRadiiLocal.card))
            .clickable(onClick = onClick)
            .padding(KhataGoSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = KhataGoIcons.Overdue,
            contentDescription = null,
            tint = KhataGoColors.Overdue,
        )
        Spacer(Modifier.width(KhataGoSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$count payment(s) overdue",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF7A1C17),
            )
            Text(
                text = "${moneyText(amountMinor, currency)} still outstanding. Tapping opens the " +
                    "payment centre.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8E3A34),
            )
        }
        Icon(
            imageVector = KhataGoIcons.Payments,
            contentDescription = null,
            tint = KhataGoColors.Overdue,
        )
    }
}

private object KhataGoRadiiLocal {
    val card = 20.dp
}

@Composable
private fun DueRowContent(
    item: DueItem,
    currency: com.khatago.finance.core.money.CurrencySpec,
    todayEpochDay: Long,
    onOpenRecord: () -> Unit,
) {
    val dueStatus = item.status(todayEpochDay)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = KhataGoIcons.forPayable(item.type),
            contentDescription = item.type.displayName,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
            Spacer(Modifier.height(KhataGoSpacing.xs))
            StatusPill(
                text = "${AppDates.humanDay(item.dueDateEpochDay, todayEpochDay)} · " +
                    moneyText(item.amountMinor, currency),
                tone = toneForDue(dueStatus),
            )
        }
        TextActionButtonLocal(text = "Pay", onClick = onOpenRecord)
    }
}

@Composable
private fun TextActionButtonLocal(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(KhataGoColors.Emerald100, RoundedCornerShape(12.dp))
            .padding(horizontal = KhataGoSpacing.md, vertical = KhataGoSpacing.sm),
    )
}

@Composable
private fun ActivityLine(
    activity: ActivityItem,
    currency: com.khatago.finance.core.money.CurrencySpec,
    todayEpochDay: Long,
    onClick: () -> Unit,
) {
    RecordRow(
        title = activity.title,
        subtitle = activity.subtitle,
        amountText = moneyText(activity.amountMinor, currency),
        dateText = AppDates.humanDay(activity.dateEpochDay, todayEpochDay),
        leading = {
            Icon(
                imageVector = KhataGoIcons.forTarget(activity.typeKey),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        status = activity.typeKey.replace("_", " ").replaceFirstChar { it.uppercase() },
        statusTone = StatusTone.Active,
        onClick = onClick,
    )
}

@Composable
private fun OutstandingBreakdownSection(
    snapshot: DashboardSnapshot,
    currency: com.khatago.finance.core.money.CurrencySpec,
    onOpenRecordModule: (PayableType) -> Unit,
) {
    val parts = snapshot.outstandingBreakdown
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KhataGoSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.sm),
    ) {
        SectionHeader(
            title = "Where the money is owed",
            subtitle = if (parts.isEmpty()) {
                "No outstanding obligations"
            } else {
                "Each figure is the live remaining balance, not an original amount"
            },
        )
        if (parts.isEmpty()) {
            KhataGoCard {
                Text(
                    text = "Nothing outstanding. When you add a shop credit, loan or EMI it appears " +
                        "here with its remaining balance.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            parts.forEach { (type, amountMinor) ->
                KhataGoCard(onClick = { onOpenRecordModule(type) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = KhataGoIcons.forPayable(type),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(KhataGoSpacing.md))
                        Text(
                            text = type.displayName.replace("_", " ")
                                .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = moneyText(amountMinor, currency),
                            style = KhataGoTypography.figure,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The privacy line, on the screen users see most. A ledger app should not rely on a Play Store
 * listing to communicate "no cloud"; the app says it where the data is visible.
 */
@Composable
private fun OfflineFootnote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KhataGoSpacing.screen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = KhataGoIcons.Lock,
            contentDescription = null,
            tint = KhataGoColors.Ink400,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(KhataGoSpacing.sm))
        Text(
            text = "Stored on this phone only · no account, no sync, no ads",
            style = MaterialTheme.typography.labelSmall,
            color = KhataGoColors.Ink400,
            textAlign = TextAlign.Center,
        )
    }
}
