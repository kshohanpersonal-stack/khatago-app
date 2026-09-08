package com.khatago.finance.ui.detail

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khatago.finance.AppContainer
import com.khatago.finance.core.money.CurrencySpec
import com.khatago.finance.core.money.MoneyParseResult
import com.khatago.finance.core.time.AppDates
import com.khatago.finance.data.db.entity.PaymentEntity
import com.khatago.finance.data.repo.PaymentOutcome
import com.khatago.finance.domain.model.PayableType
import com.khatago.finance.ui.components.AmountField
import com.khatago.finance.ui.components.DateField
import com.khatago.finance.ui.components.DropdownField
import com.khatago.finance.ui.components.FieldContext
import com.khatago.finance.ui.components.KhataGoCard
import com.khatago.finance.ui.components.KhataGoIcons
import com.khatago.finance.ui.components.PrimaryButton
import com.khatago.finance.ui.components.StatusPill
import com.khatago.finance.ui.components.StatusTone
import com.khatago.finance.ui.components.moneyText
import com.khatago.finance.ui.theme.KhataGoColors
import com.khatago.finance.ui.theme.KhataGoRadii
import com.khatago.finance.ui.theme.KhataGoSpacing
import com.khatago.finance.ui.theme.KhataGoTypography
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Record a payment against *any* obligation — shop credit, loan, EMI, borrowing or lending.
 *
 * One screen for all five types because the engine behind it is one engine: an amount, a date, a
 * method, and a target that refuses overpayment. Five payment forms would each need their own
 * guard, and the day one of them forgets is the day a balance goes negative and the ledger lies.
 *
 * UI behaviours that carry weight here:
 *  - The amount is **prefilled with the exact remaining due**, because "pay what is due" is the
 *    common case and forcing a person to retype 4,500 invites a typo into their own ledger.
 *  - When a loan/EMI has installments, the user chooses *which line* to apply the money to; leaving
 *    it on "Auto (oldest first)" applies KhataGo's deterministic allocation. Explicit choice, with a
 *    sane default.
 *  - A rejected payment (overpayment, cancelled record) is shown **inline, in context**, not as a
 *    toast that vanishes: the message explains the maximum that can be recorded, and the field still
 *    holds the number they typed so they can correct it.
 */
@Composable
fun PaymentSheetRoute(
    container: AppContainer,
    type: String,
    id: Long,
    onDismiss: () -> Unit,
) {
    val payableType = PayableType.fromKey(type)
    val scope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf<PaymentTarget?>(null) }
    var methods by remember { mutableStateOf<List<String>>(emptyList()) }
    var installments by remember { mutableStateOf<List<InstallmentChoice>>(emptyList()) }
    var history by remember { mutableStateOf<List<PaymentEntity>>(emptyList()) }
    var currency by remember { mutableStateOf(CurrencySpec.DEFAULT) }
    var amountText by remember { mutableStateOf("") }
    var methodName by remember { mutableStateOf<String?>(null) }
    var reference by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var dueDate by remember { mutableStateOf<Long?>(null) }
    var installmentId by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    val today = remember { AppDates.today() }

    LaunchedEffect(type, id) {
        val resolved = container.paymentRepository.resolveOnce(payableType, id)
        snapshot = resolved
        currency = container.catalogRepository.findProfile().let { CurrencySpec.fromCode(it?.currencyCode) }
        methods = container.catalogRepository.findEnabledPaymentMethods().map { it.name }
        methodName = methods.firstOrNull() ?: "Cash"
        amountText = resolved?.let {
            // Prefill: the remaining amount, or the next installment's amount when there is one.
            com.khatago.finance.core.money.MoneyFormat.toCsvNumber(it.remainingMinor, currency)
        }.orEmpty()
        if (resolved?.payableType == PayableType.Loan || resolved?.payableType == PayableType.Emi) {
            val progress = container.obligationRepository.observeScheduleProgress(
                ownerType = resolved.payableType,
                ownerId = id,
                todayEpochDay = today,
            ).first()
            installments = progress.allocated
                .filter { !it.isSettled }
                .map { InstallmentChoice(it.id, it.number, it.scheduledMinor, it.dueDateEpochDay, it.allocatedMinor) }
        }
        history = container.paymentRepository.paymentsFor(resolved?.payableType ?: PayableType.ShopCredit, id)
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = KhataGoSpacing.screen, vertical = KhataGoSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = KhataGoIcons.Payments,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(KhataGoSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Record a payment", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = snapshot?.let { "${it.title} · ${it.subtitle}" } ?: "Loading…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "Close",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onDismiss).padding(KhataGoSpacing.sm),
            )
        }

        if (payableType == null) {
            Text(
                text = "This payment cannot be recorded: the record type is not recognised. Nothing " +
                    "has been changed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }

        snapshot?.let { target ->
            KhataGoCard {
                BalanceStrip(target = target, currency = currency)
            }

            val context = FieldContext(currency = currency, todayEpochDay = today)

            AmountField(
                value = amountText,
                onValueChange = { amountText = it },
                context = context,
                label = "Amount paid",
                helper = "Maximum ${moneyText(target.remainingMinor, currency)} — overpayments are refused so a balance can never go below zero.",
            )

            if (installments.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.xs)) {
                    Text(
                        text = "APPLY TO",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    InstallmentPicker(
                        installments = installments,
                        selectedId = installmentId,
                        currency = currency,
                        onSelect = { installmentId = it },
                    )
                }
            }

            DropdownField(
                options = methods.ifEmpty { listOf("Cash") },
                selected = methodName,
                onSelect = { methodName = it },
                labelOf = { it },
                label = "Payment method",
            )

            DateField(
                epochDay = dueDate ?: today,
                onEpochDayChange = { dueDate = it },
                label = "Date paid",
                context = context,
                helper = "Defaults to today. Backdating is allowed: it is how a payment made in a shop " +
                    "gets recorded that evening.",
            )

            com.khatago.finance.ui.components.TextFieldLine(
                value = reference,
                onValueChange = { reference = it },
                label = "Reference",
                placeholder = "Transaction id, receipt number…",
                maxLength = 80,
            )

            com.khatago.finance.ui.components.NoteField(value = note, onValueChange = { note = it })

            error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(KhataGoColors.OverdueBg, RoundedCornerShape(KhataGoRadii.field))
                        .padding(KhataGoSpacing.md),
                )
            }

            PrimaryButton(
                text = if (saved) "Payment recorded" else "Save payment",
                onClick = {
                    error = null
                    scope.launch {
                        when (
                            val parsed = MoneyParseResult.parse(amountText, currency, allowZero = false)
                        ) {
                            is MoneyParseResult.Invalid -> {
                                error = parsed.message
                            }

                            is MoneyParseResult.Success -> {
                                val outcome = container.paymentRepository.record(
                                    payableType = target.payableType,
                                    payableId = target.id,
                                    amountMinor = parsed.amount.minor,
                                    paidDateEpochDay = dueDate ?: today,
                                    methodName = methodName ?: "Cash",
                                    reference = reference.trim().takeIf { it.isNotEmpty() },
                                    note = note.trim().takeIf { it.isNotEmpty() },
                                    installmentId = installmentId,
                                )
                                when (outcome) {
                                    is PaymentOutcome.Recorded -> {
                                        saved = true
                                        onDismiss()
                                    }

                                    is PaymentOutcome.Rejected -> error = outcome.errorMessage()
                                    is PaymentOutcome.NotFound -> error = outcome.errorMessage()
                                }
                            }
                        }
                    }
                },
                enabled = !loading && snapshot != null,
                loading = loading,
            )

            if (history.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.sm)) {
                    Text(
                        text = "Already recorded on this ${target.payableType.displayName.replace('_', ' ')}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    history.forEach { payment ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = KhataGoIcons.Check,
                                contentDescription = null,
                                tint = KhataGoColors.Settled,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(KhataGoSpacing.sm))
                            Text(
                                text = AppDates.formatMedium(payment.paidDateEpochDay),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = moneyText(payment.amountMinor, currency),
                                style = KhataGoTypography.figure,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class PaymentTarget(
    val payableType: PayableType,
    val id: Long,
    val title: String,
    val subtitle: String,
    val originalMinor: Long,
    val paidMinor: Long,
    val remainingMinor: Long,
    val dueDateEpochDay: Long?,
    val cancelled: Boolean,
)

data class InstallmentChoice(
    val id: Long,
    val number: Int,
    val scheduledMinor: Long,
    val dueDateEpochDay: Long,
    val alreadyAllocatedMinor: Long,
)

@Composable
private fun BalanceStrip(target: PaymentTarget, currency: CurrencySpec) {
    Column(verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.sm)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "REMAINING",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = moneyText(target.remainingMinor, currency),
                    style = KhataGoTypography.headlineMoney,
                )
            }
            StatusPill(
                text = when {
                    target.cancelled -> "Cancelled"
                    target.remainingMinor == 0L -> "Settled"
                    else -> "Outstanding"
                },
                tone = when {
                    target.cancelled -> StatusTone.Cancelled
                    target.remainingMinor == 0L -> StatusTone.Settled
                    else -> StatusTone.Active
                },
            )
        }
        Text(
            text = "Original ${moneyText(target.originalMinor, currency)} · paid ${moneyText(target.paidMinor, currency)}" +
                (target.dueDateEpochDay?.let { " · due ${AppDates.formatMedium(it)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InstallmentPicker(
    installments: List<InstallmentChoice>,
    selectedId: Long?,
    currency: CurrencySpec,
    onSelect: (Long?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.xs),
    ) {
        ChoiceLine(
            title = "Auto (oldest open installment first)",
            subtitle = "KhataGo applies payments in due order, which keeps the schedule consistent.",
            selected = selectedId == null,
            onClick = { onSelect(null) },
        )
        installments.forEach { line ->
            ChoiceLine(
                title = "Installment ${line.number} · ${AppDates.formatMedium(line.dueDateEpochDay)}",
                subtitle = "Scheduled ${moneyText(line.scheduledMinor, currency)} · already applied " +
                    moneyText(line.alreadyAllocatedMinor, currency),
                selected = selectedId == line.id,
                onClick = { onSelect(line.id) },
            )
        }
    }
}

@Composable
private fun ChoiceLine(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) KhataGoColors.Emerald50 else Color_Transparent,
                RoundedCornerShape(KhataGoRadii.field),
            )
            .clickable(onClick = onClick)
            .padding(KhataGoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else KhataGoColors.Ink200,
                    RoundedCornerShape(5.dp),
                ),
        )
        Spacer(Modifier.width(KhataGoSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val Color_Transparent = androidx.compose.ui.graphics.Color.Transparent
