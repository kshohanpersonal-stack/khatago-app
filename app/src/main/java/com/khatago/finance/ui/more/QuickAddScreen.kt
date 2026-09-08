package com.khatago.finance.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.khatago.finance.AppContainer
import com.khatago.finance.core.money.CurrencySpec
import com.khatago.finance.core.time.AppDates
import com.khatago.finance.domain.model.DueItem
import com.khatago.finance.ui.Routes
import com.khatago.finance.ui.components.KhataGoCard
import com.khatago.finance.ui.components.KhataGoIcons
import com.khatago.finance.ui.components.SectionHeader
import com.khatago.finance.ui.components.StatusPill
import com.khatago.finance.ui.components.moneyText
import com.khatago.finance.ui.components.toneForDue
import com.khatago.finance.ui.theme.KhataGoColors
import com.khatago.finance.ui.theme.KhataGoRadii
import com.khatago.finance.ui.theme.KhataGoSpacing
import kotlinx.coroutines.flow.first

/**
 * The quick-add sheet: one tap from the dashboard's centre button to "what do you want to record?".
 *
 * It is a *destination* rather than a modal dialog so the six actions can be deep-linked
 * (`khatago://add`) from a launcher shortcut, which is the whole point of a quick-add: recording a
 * credit while standing in the shop, holding the phone in one hand.
 *
 * The six choices are the six things that actually happen in a khata: buy on credit, record money in,
 * record money out, pay something, lend, borrow. Anything else is a settings change and does not
 * belong on this surface.
 */
@Composable
fun QuickAddRoute(
    container: AppContainer,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    var currency by remember { mutableStateOf(CurrencySpec.DEFAULT) }
    var due by remember { mutableStateOf<List<DueItem>>(emptyList()) }
    var outstandingCount by remember { mutableStateOf(0) }
    val today = remember { AppDates.today() }

    LaunchedEffect(Unit) {
        currency = CurrencySpec.fromCode(container.catalogRepository.findProfile()?.currencyCode)
        due = container.paymentRepository.observeDueBetween(null, AppDates.endOfWeek(today)).first()
        outstandingCount = due.size
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = KhataGoSpacing.screen, vertical = KhataGoSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "What happened?", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Six things a khata keeps. Pick one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Close",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(KhataGoSpacing.sm),
            )
        }

        QuickTile(
            title = "Buy something on credit",
            subtitle = "A shop khata entry: item, amount, and when it is due",
            icon = KhataGoIcons.ShopCredit,
            onClick = { onNavigate(Routes.CREDIT_FORM) },
        )
        QuickTile(
            title = "Record a payment",
            subtitle = if (outstandingCount > 0) {
                "$outstandingCount item(s) due this week — pick one and pay it off"
            } else {
                "Reduce what you owe or mark what you are owed as paid"
            },
            icon = KhataGoIcons.Payments,
            onClick = { onNavigate(Routes.PAYMENTS) },
        )
        QuickTile(
            title = "Money in",
            subtitle = "Salary, sales, a repayment received",
            icon = KhataGoIcons.Income,
            onClick = { onNavigate(Routes.INCOME_FORM) },
        )
        QuickTile(
            title = "Money out",
            subtitle = "Anything you paid for, from rent to rickshaw fare",
            icon = KhataGoIcons.Expense,
            onClick = { onNavigate(Routes.EXPENSE_FORM) },
        )
        QuickTile(
            title = "Borrow from someone",
            subtitle = "Tracked separately from what they owe you — never netted",
            icon = KhataGoIcons.Borrowed,
            onClick = { onNavigate(Routes.personForm("borrowing")) },
        )
        QuickTile(
            title = "Lend to someone",
            subtitle = "Adds to what you are owed, with its own due date",
            icon = KhataGoIcons.Lent,
            onClick = { onNavigate(Routes.personForm("lending")) },
        )
        QuickTile(
            title = "Start an EMI or a loan",
            subtitle = "Sets up an installment schedule you can pay early or partly",
            icon = KhataGoIcons.Emi,
            onClick = { onNavigate(Routes.EMI_FORM) },
        )

        if (due.isNotEmpty()) {
            SectionHeader(
                title = "Due this week",
                subtitle = "Tap to record the payment directly",
            )
            due.take(3).forEach { item ->
                KhataGoCard(onClick = { onNavigate(Routes.payment(item.type.displayName, item.obligationId)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = KhataGoIcons.forPayable(item.type),
                            contentDescription = null,
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
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = moneyText(item.amountMinor, currency),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            StatusPill(
                                text = AppDates.humanDay(item.dueDateEpochDay, today),
                                tone = toneForDue(item.status(today)),
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = "Nothing leaves this phone. KhataGo has no account, no server and no analytics.",
            style = MaterialTheme.typography.labelSmall,
            color = KhataGoColors.Ink400,
        )
    }
}

@Composable
private fun QuickTile(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(KhataGoRadii.card))
            .clickable(onClick = onClick)
            .padding(KhataGoSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(KhataGoColors.Emerald100, RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = KhataGoColors.Emerald700,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(KhataGoSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

    }
}
