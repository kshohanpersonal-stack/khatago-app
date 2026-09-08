package com.khatago.finance.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khatago.finance.AppContainer
import com.khatago.finance.core.money.CurrencySpec
import com.khatago.finance.core.money.MoneyFormat
import com.khatago.finance.core.time.AppDates
import com.khatago.finance.data.db.entity.BorrowingEntity
import com.khatago.finance.data.db.entity.ExpenseEntity
import com.khatago.finance.data.db.entity.IncomeEntity
import com.khatago.finance.data.db.entity.LendingEntity
import com.khatago.finance.data.db.entity.PersonEntity
import com.khatago.finance.ui.components.DetailTopBar
import com.khatago.finance.ui.components.EmptyState
import com.khatago.finance.ui.components.KhataGoCard
import com.khatago.finance.ui.components.KhataGoIcons
import com.khatago.finance.ui.components.MoneyFigure
import com.khatago.finance.ui.components.SectionHeader
import com.khatago.finance.ui.components.StatusPill
import com.khatago.finance.ui.components.StatusTone
import com.khatago.finance.ui.components.moneyText
import com.khatago.finance.ui.components.toneForDue
import com.khatago.finance.ui.theme.KhataGoColors
import com.khatago.finance.ui.theme.KhataGoRadii
import com.khatago.finance.ui.theme.KhataGoSpacing
import com.khatago.finance.ui.theme.KhataGoTypography
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A person's page: what they owe you and what you owe them, **side by side and never netted**.
 *
 * This is the screen where a tempting shortcut would be fatal. Showing one figure, "Balance with
 * Karim: ৳2,000", is friendlier — and it destroys information: a ৳5,000 loan you made and a ৳3,000
 * you borrowed are two obligations with two due dates and two settlement events, not one number. A
 * paper khata keeps them in separate columns for exactly this reason, so KhataGo does too, and the
 * only "net" figure it ever prints is labelled as a summary line, never as a balance to settle.
 */
@Composable
fun PersonDetailScreen(
    container: AppContainer,
    personId: Long,
    onBack: () -> Unit,
    onOpenRecord: (String, Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var person by remember { mutableStateOf<PersonEntity?>(null) }
    var borrowings by remember { mutableStateOf<List<BorrowingEntity>>(emptyList()) }
    var lendings by remember { mutableStateOf<List<LendingEntity>>(emptyList()) }
    var paidTo by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    var paidFrom by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    var currency by remember { mutableStateOf(CurrencySpec.DEFAULT) }
    var confirmDelete by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val today = remember { AppDates.today() }

    LaunchedEffect(personId) {
        currency = CurrencySpec.fromCode(container.catalogRepository.findProfile()?.currencyCode)
        person = container.personRepository.findPerson(personId)
        borrowings = container.personRepository.findAllBorrowings().filter { it.personId == personId }
        lendings = container.personRepository.findAllLendings().filter { it.personId == personId }
        paidTo = borrowings.associate { b ->
            b.id to container.personRepository.findBorrowingPayments(b.id).sumOf { it.amountMinor }
        }
        paidFrom = lendings.associate { l ->
            l.id to container.personRepository.findLendingPayments(l.id).sumOf { it.amountMinor }
        }
    }

    val owedToThem = borrowings.sumOf { (it.amountMinor - (paidTo[it.id] ?: 0L)).coerceAtLeast(0L) }
    val owedToMe = lendings.sumOf { (it.amountMinor - (paidFrom[it.id] ?: 0L)).coerceAtLeast(0L) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        DetailTopBar(title = person?.name ?: "Person", onBack = onBack)
        LazyColumn(
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.md),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = KhataGoSpacing.screen)) {
                    Text(
                        text = person?.relationship ?: "Connection",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = person?.name ?: "…",
                        style = KhataGoTypography.headlineMoney,
                    )
                    person?.phone?.let { phone ->
                        Spacer(Modifier.height(KhataGoSpacing.xs))
                        Text(
                            text = phone,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                        label = "You owe them",
                        amountText = moneyText(owedToThem, currency),
                        emphasis = com.khatago.finance.ui.components.MoneyEmphasis.Compact,
                        tint = KhataGoColors.Overdue,
                        modifier = Modifier.weight(1f),
                    )
                    MoneyFigure(
                        label = "They owe you",
                        amountText = moneyText(owedToMe, currency),
                        emphasis = com.khatago.finance.ui.components.MoneyEmphasis.Compact,
                        tint = KhataGoColors.OwedToMe,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                Text(
                    text = "These two are kept apart on purpose. Paying one never reduces the other, " +
                        "so your records stay true to what actually happened.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = KhataGoSpacing.screen),
                )
            }

            if (lendings.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "You lent",
                        subtitle = "${lendings.size} record(s)",
                        modifier = Modifier.padding(horizontal = KhataGoSpacing.screen),
                    )
                }
                items(lendings, key = { "lend-${it.id}" }) { lending ->
                    PersonObligationCard(
                        title = "Lent on ${AppDates.formatMedium(lending.lendDateEpochDay)}",
                        due = lending.dueDateEpochDay,
                        totalMinor = lending.amountMinor,
                        paidMinor = paidFrom[lending.id] ?: 0L,
                        currency = currency,
                        today = today,
                        cancelled = lending.cancelled,
                        onClick = { onOpenRecord("lending", lending.id) },
                    )
                }
            }

            if (borrowings.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "You borrowed",
                        subtitle = "${borrowings.size} record(s)",
                        modifier = Modifier.padding(horizontal = KhataGoSpacing.screen),
                    )
                }
                items(borrowings, key = { "bor-${it.id}" }) { borrowing ->
                    PersonObligationCard(
                        title = "Borrowed on ${AppDates.formatMedium(borrowing.borrowDateEpochDay)}",
                        due = borrowing.dueDateEpochDay,
                        totalMinor = borrowing.amountMinor,
                        paidMinor = paidTo[borrowing.id] ?: 0L,
                        currency = currency,
                        today = today,
                        cancelled = borrowing.cancelled,
                        onClick = { onOpenRecord("borrowing", borrowing.id) },
                    )
                }
            }

            if (borrowings.isEmpty() && lendings.isEmpty()) {
                item {
                    EmptyState(
                        title = "No money recorded with ${person?.name ?: "this person"}",
                        body = "Add a borrowing (money you received) or a lending (money you gave). " +
                            "Each is tracked separately with its own due date.",
                        actionLabel = null,
                        onAction = null,
                    )
                }
            }

            person?.notes?.takeIf { it.isNotBlank() }?.let { note ->
                item {
                    KhataGoCard(modifier = Modifier.padding(horizontal = KhataGoSpacing.screen)) {
                        SectionHeader(title = "Note")
                        Text(text = note, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = KhataGoSpacing.screen),
                    horizontalArrangement = Arrangement.spacedBy(KhataGoSpacing.md),
                ) {
                    com.khatago.finance.ui.components.TonalButton(
                        text = "Edit person",
                        onClick = { },
                        modifier = Modifier.weight(1f),
                    )
                    com.khatago.finance.ui.components.TonalButton(
                        text = "Delete person…",
                        onClick = { confirmDelete = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            notice?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = KhataGoSpacing.screen),
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        val extra = borrowings.size + lendings.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${person?.name ?: "this person"}?") },
            text = {
                Text(
                    if (extra == 0) {
                        "This person has no records, so nothing else will change."
                    } else {
                        "$extra obligation record(s) with their payment history will be deleted too. " +
                            "Totals in the app will change immediately. This cannot be undone."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val target = person
                    if (target != null) {
                        scope.launch {
                            val removed = container.personRepository.deletePersonWithObligations(target)
                            notice = "Removed $removed record(s)."
                            if (removed >= 0) onBack()
                        }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun PersonObligationCard(
    title: String,
    due: Long?,
    totalMinor: Long,
    paidMinor: Long,
    currency: CurrencySpec,
    today: Long,
    cancelled: Boolean,
    onClick: () -> Unit,
) {
    val remaining = (totalMinor - paidMinor).coerceAtLeast(0L)
    val isOverdue = !cancelled && remaining > 0L && due != null && due < today
    KhataGoCard(
        modifier = Modifier.padding(horizontal = KhataGoSpacing.screen),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(KhataGoSpacing.xs))
                StatusPill(
                    text = when {
                        cancelled -> "Cancelled"
                        remaining == 0L -> "Settled"
                        isOverdue -> "Overdue · ${AppDates.humanDay(due, today)}"
                        else -> "Due ${AppDates.humanDay(due, today)}"
                    },
                    tone = when {
                        cancelled -> StatusTone.Cancelled
                        remaining == 0L -> StatusTone.Settled
                        isOverdue -> StatusTone.Overdue
                        else -> toneForDue(com.khatago.finance.core.time.dueStatusOf(due, today))
                    },
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = moneyText(remaining, currency),
                    style = KhataGoTypography.sectionMoney,
                )
                Text(
                    text = "of ${moneyText(totalMinor, currency)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Income / expense detail.
 *
 * Small on purpose: a ledger entry has no balance, no schedule and no payments. What it does have is
 * a category and a method, and those are shown here so a person can correct a misfiled expense without
 * leaving the record they were already reading.
 */
@Composable
fun LedgerDetailScreen(
    container: AppContainer,
    kind: String,
    id: Long,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var currency by remember { mutableStateOf(CurrencySpec.DEFAULT) }
    var amount by remember { mutableStateOf(0L) }
    var categoryName by remember { mutableStateOf("") }
    var methodName by remember { mutableStateOf("") }
    var counterparty by remember { mutableStateOf<String?>(null) }
    var dateEpochDay by remember { mutableStateOf(0L) }
    var note by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(kind, id) {
        currency = CurrencySpec.fromCode(container.catalogRepository.findProfile()?.currencyCode)
        if (kind == "income") {
            container.transactionRepository.findIncome(id)?.let { income: IncomeEntity ->
                amount = income.amountMinor
                categoryName = income.categoryName
                methodName = income.methodName
                counterparty = income.source
                dateEpochDay = income.transactionDateEpochDay
                note = income.note
            } ?: run { missing = true }
        } else {
            container.transactionRepository.findExpense(id)?.let { expense: ExpenseEntity ->
                amount = expense.amountMinor
                categoryName = expense.categoryName
                methodName = expense.methodName
                counterparty = expense.merchant
                dateEpochDay = expense.transactionDateEpochDay
                note = expense.note
            } ?: run { missing = true }
        }
        loaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        DetailTopBar(title = if (kind == "income") "Income" else "Expense", onBack = onBack)
        if (missing) {
            EmptyState(
                title = "Record not found",
                body = "It may have been deleted already. Nothing was changed here.",
                actionLabel = "Go back",
                onAction = onBack,
            )
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.md),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = KhataGoSpacing.screen)
                        .background(
                            if (kind == "income") {
                                Brush_income
                            } else {
                                Brush_expense
                            },
                            RoundedCornerShape(KhataGoRadii.card),
                        )
                        .padding(KhataGoSpacing.xl),
                    verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.xs),
                ) {
                    Text(
                        text = if (kind == "income") "RECEIVED" else "SPENT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = MoneyFormat.format(amount, currency),
                        style = KhataGoTypography.displayLarge,
                        color = if (kind == "income") KhataGoColors.Settled else KhataGoColors.Overdue,
                    )
                    Text(
                        text = AppDates.formatMediumWithWeekday(dateEpochDay),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                KhataGoCard(modifier = Modifier.padding(horizontal = KhataGoSpacing.screen)) {
                    SectionHeader(title = "Details")
                    Spacer(Modifier.height(KhataGoSpacing.xs))
                    DetailLine("Category", categoryName)
                    DetailLine(if (kind == "income") "Source" else "Merchant", counterparty?.takeIf { it.isNotBlank() } ?: "—")
                    DetailLine("Method", methodName)
                    if (!note.isNullOrBlank()) DetailLine("Note", note!!)
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = KhataGoSpacing.screen),
                    horizontalArrangement = Arrangement.spacedBy(KhataGoSpacing.md),
                ) {
                    com.khatago.finance.ui.components.TonalButton(
                        text = "Delete…",
                        onClick = { confirmDelete = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this ${if (kind == "income") "income" else "expense"}?") },
            text = {
                Text(
                    "This entry is not referenced by any payment, so deleting it changes only your " +
                        "cash-flow totals for ${AppDates.formatMonthYear(dateEpochDay)}.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        if (kind == "income") container.transactionRepository.deleteIncome(id)
                        else container.transactionRepository.deleteExpense(id)
                        onBack()
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(KhataGoSpacing.md))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

private val Brush_income = androidx.compose.ui.graphics.Brush.linearGradient(
    listOf(androidx.compose.ui.graphics.Color(0xFFEFF9F4), androidx.compose.ui.graphics.Color(0xFFE1F6EC)),
)

private val Brush_expense = androidx.compose.ui.graphics.Brush.linearGradient(
    listOf(androidx.compose.ui.graphics.Color(0xFFFDF3F2), androidx.compose.ui.graphics.Color(0xFFFBE7E4)),
)
