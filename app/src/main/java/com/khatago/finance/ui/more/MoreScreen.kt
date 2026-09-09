package com.khatago.finance.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatago.finance.AppContainer
import com.khatago.finance.BuildConfig
import com.khatago.finance.core.money.CurrencySpec
import com.khatago.finance.core.time.AppDates
import com.khatago.finance.ui.Routes
import com.khatago.finance.ui.components.KhataGoCard
import com.khatago.finance.ui.components.KhataGoIcons
import com.khatago.finance.ui.components.SectionHeader
import com.khatago.finance.ui.components.moneyText
import com.khatago.finance.ui.theme.KhataGoColors
import com.khatago.finance.ui.theme.KhataGoRadii
import com.khatago.finance.ui.theme.KhataGoSpacing

/**
 * The "More" tab: a hub, not a settings dump.
 *
 * Everything on it either (a) opens a module the bottom bar has no room for, or (b) touches the data
 * itself — export, backup, lock, storage. The ordering follows how often a person needs it: shops and
 * people first (they are the two lists that grow), then reports, then the rarely-visited settings.
 */
@Composable
fun MoreRoute(
    container: AppContainer,
    onOpen: (String) -> Unit,
    onOpenRecords: (String) -> Unit,
) {
    var currency by remember { mutableStateOf(CurrencySpec.DEFAULT) }
    var name by remember { mutableStateOf("KhataGo") }
    var shopCount by remember { mutableIntStateOf(0) }
    var personCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        currency = CurrencySpec.fromCode(container.catalogRepository.findProfile()?.currencyCode)
        name = container.catalogRepository.findProfile()?.displayName?.takeIf { it.isNotBlank() } ?: "KhataGo"
        shopCount = container.shopRepository.findAllShops().count { !it.archived }
        personCount = container.personRepository.findAllBorrowings().size +
            container.personRepository.findAllLendings().size
    }

    // Balances come from the same derived flow the dashboard uses. Nothing here re-adds rows: a
    // second implementation of "total I owe" is how a hub screen starts disagreeing with the ledger.
    val breakdown by container.statsRepository.observeOutstandingBreakdown()
        .collectAsStateWithLifecycle(initialValue = null)
    val iOwe = breakdown?.totalIOweMinor ?: 0L
    val owedToMe = breakdown?.lendingMinor ?: 0L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = KhataGoSpacing.screen, vertical = KhataGoSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.lg),
    ) {
        Column {
            Text(text = name, style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "v${BuildConfig.VERSION_NAME} · everything stays on this device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(KhataGoSpacing.md)) {
            MiniBalance(
                modifier = Modifier.weight(1f),
                label = "You owe",
                amount = moneyText(iOwe, currency),
                tint = KhataGoColors.OwedToMe,
            )
            MiniBalance(
                modifier = Modifier.weight(1f),
                label = "Owed to you",
                amount = moneyText(owedToMe, currency),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        SectionHeader(title = "Lists")
        KhataGoCard {
            HubRow(
                icon = KhataGoIcons.ShopCredit,
                title = "Shops",
                subtitle = "$shopCount active · add with the button below",
                onClick = { onOpenRecords("shop_credit") },
            )
            HubRow(
                icon = KhataGoIcons.Person,
                title = "People",
                subtitle = "$personCount obligation record(s) across borrowings and lendings",
                onClick = { onOpen(Routes.personForm()) },
            )
            HubRow(
                icon = KhataGoIcons.Loan,
                title = "Loans",
                subtitle = "Bank and money-lender schedules",
                onClick = { onOpenRecords("loan") },
            )
            HubRow(
                icon = KhataGoIcons.Emi,
                title = "EMI plans",
                subtitle = "Phone, appliance and shop instalments",
                onClick = { onOpenRecords("emi") },
            )
            HubRow(
                icon = KhataGoIcons.Income,
                title = "Income",
                subtitle = "Money in, by source",
                onClick = { onOpenRecords("income") },
            )
            HubRow(
                icon = KhataGoIcons.Expense,
                title = "Expenses",
                subtitle = "Money out, by category",
                onClick = { onOpenRecords("expense") },
            )
        }

        SectionHeader(title = "Add")
        KhataGoCard {
            HubRow(
                icon = KhataGoIcons.Add,
                title = "New shop",
                subtitle = "Name, owner, category, note",
                onClick = { onOpen(Routes.SHOP_FORM) },
            )
            HubRow(
                icon = KhataGoIcons.AddPerson,
                title = "New person",
                subtitle = "Someone you lend to or borrow from",
                onClick = { onOpen(Routes.PERSON_FORM) },
            )
            HubRow(
                icon = KhataGoIcons.Wallet,
                title = "New credit purchase",
                subtitle = "An item from a shop, with what you have already paid",
                onClick = { onOpen(Routes.creditForm()) },
            )
            HubRow(
                icon = KhataGoIcons.Borrowed,
                title = "New loan",
                subtitle = "Principal, instalments, first due date",
                onClick = { onOpen(Routes.loanForm()) },
            )
        }

        SectionHeader(title = "Your data")
        KhataGoCard {
            HubRow(
                icon = KhataGoIcons.Documents,
                title = "Reports & CSV export",
                subtitle = "Ten sheets, each labelled with its period",
                onClick = { onOpen(Routes.REPORTS) },
            )
            HubRow(
                icon = KhataGoIcons.Share,
                title = "Backup & restore",
                subtitle = "One JSON file you keep; the whole ledger in it",
                onClick = { onOpen(Routes.BACKUP) },
            )
            HubRow(
                icon = KhataGoIcons.Delete,
                title = "Data management",
                subtitle = "Sample data, attachment storage, delete everything",
                onClick = { onOpen(Routes.SETTINGS_DATA) },
            )
        }

        SectionHeader(title = "App")
        KhataGoCard {
            HubRow(
                icon = KhataGoIcons.Lock,
                title = "App lock",
                subtitle = "PIN and biometrics, and what they do not protect",
                onClick = { onOpen(Routes.SETTINGS_SECURITY) },
            )
            HubRow(
                icon = KhataGoIcons.Notifications,
                title = "Settings",
                subtitle = "Reminders, currency, categories, payment methods",
                onClick = { onOpen(Routes.SETTINGS) },
            )
            HubRow(
                icon = KhataGoIcons.Verified,
                title = "About & privacy",
                subtitle = "Licence, offline guarantees, known limits",
                onClick = { onOpen(Routes.ABOUT) },
            )
        }

        Text(
            text = "Today is ${AppDates.formatMediumWithWeekday(AppDates.today())}.",
            style = MaterialTheme.typography.labelSmall,
            color = KhataGoColors.Ink400,
        )
    }
}

@Composable
private fun HubRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = KhataGoSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(KhataGoColors.Ink100, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(KhataGoSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = KhataGoIcons.ChevronRight,
            contentDescription = null,
            tint = KhataGoColors.Ink400,
        )
    }
}

@Composable
private fun MiniBalance(modifier: Modifier, label: String, amount: String, tint: androidx.compose.ui.graphics.Color) {
    Column(
        modifier = modifier
            .background(tint.copy(alpha = 0.10f), RoundedCornerShape(KhataGoRadii.card))
            .padding(KhataGoSpacing.md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(KhataGoSpacing.xxs))
        Text(text = amount, style = MaterialTheme.typography.titleLarge, color = tint)
    }
}

