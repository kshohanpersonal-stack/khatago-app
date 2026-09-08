package com.khatago.finance.ui.detail

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.khatago.finance.AppContainer
import com.khatago.finance.core.money.MoneyFormat
import com.khatago.finance.core.time.AppDates
import com.khatago.finance.data.db.entity.AttachmentEntity
import com.khatago.finance.domain.model.PaymentEntry
import com.khatago.finance.ui.components.AttachmentViewer
import com.khatago.finance.ui.components.DetailTopBar
import com.khatago.finance.ui.components.EmptyState
import com.khatago.finance.ui.components.KhataGoCard
import com.khatago.finance.ui.components.KhataGoIcons
import com.khatago.finance.ui.components.MoneyFigure
import com.khatago.finance.ui.components.PayoffBar
import com.khatago.finance.ui.components.SectionHeader
import com.khatago.finance.ui.components.StatusPill
import com.khatago.finance.ui.theme.KhataGoColors
import com.khatago.finance.ui.theme.KhataGoRadii
import com.khatago.finance.ui.theme.KhataGoSpacing
import com.khatago.finance.ui.theme.KhataGoTypography

/**
 * One detail screen for every record type, plus thin route wrappers.
 *
 * Sections appear only when they have content, in a fixed order: *balance → facts → schedule (if
 * installments exist) → payment history → attachments → destructive actions*. That order is how the
 * screen reads: what you owe, why, what is scheduled, what you already paid, evidence, and only at
 * the bottom, the things that can hurt.
 */
@Composable
fun KhataGoDetailScreen(
    container: AppContainer,
    typeKey: String,
    id: Long,
    onBack: () -> Unit,
    onPay: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onOpenChild: ((Long) -> Unit)? = null,
    afterDelete: () -> Unit = onBack,
) {
    val viewModel = viewModel<DetailViewModel>(
        factory = viewModelFactory {
            initializer { DetailViewModel(container, typeKey, id) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteWarning by remember { mutableStateOf("") }
    var snackbar by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel.message) {
        snackbar = viewModel.message
        if (viewModel.message != null) viewModel.dismissMessage()
    }

    when (val current = state) {
        DetailUiState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }

        is DetailUiState.Ready -> {
            val model = current.model
            LaunchedEffect(Unit) { if (model.title == "Record not found") Unit }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.md),
            ) {
                item {
                    DetailTopBar(
                        title = model.typeKey.replace("_", " ").replaceFirstChar { it.uppercase() },
                        onBack = onBack,
                        actions = {
                            if (model.canEdit && onEdit != null) {
                                androidx.compose.material3.IconButton(onClick = onEdit) {
                                    Icon(
                                        imageVector = KhataGoIcons.Edit,
                                        contentDescription = "Edit",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                    )
                }

                item {
                    Column(modifier = Modifier.padding(horizontal = KhataGoSpacing.screen)) {
                        Text(text = model.title, style = KhataGoTypography.headlineMoney)
                        Spacer(Modifier.height(KhataGoSpacing.xs))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = model.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(KhataGoSpacing.sm))
                            StatusPill(text = model.statusLabel, tone = model.statusTone)
                        }
                    }
                }

                item {
                    KhataGoCard(
                        modifier = Modifier.padding(horizontal = KhataGoSpacing.screen),
                        content = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(KhataGoColors.HeroGradient, RoundedCornerShape(KhataGoRadii.card))
                                    .padding(KhataGoSpacing.lg),
                                verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.md),
                            ) {
                                MoneyFigure(
                                    label = if (model.remainingMinor > 0) "Still to pay" else "Settled",
                                    amountText = model.remainingLabel,
                                    emphasis = com.khatago.finance.ui.components.MoneyEmphasis.Standard,
                                    tint = when {
                                        model.isCancelled -> KhataGoColors.Ink500
                                        model.remainingMinor == 0L -> KhataGoColors.Settled
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                PayoffBar(
                                    fraction = model.payoffFraction,
                                    color = if (model.isCancelled) KhataGoColors.Ink400 else KhataGoColors.Emerald600,
                                    label = "Paid ${model.money(model.paidMinor)} of ${model.money(model.originalMinor)}",
                                )
                                if (model.canRecordPayment) {
                                    com.khatago.finance.ui.components.PrimaryButton(
                                        text = "Record a payment",
                                        onClick = onPay,
                                    )
                                } else if (model.remainingMinor == 0L && !model.isCancelled) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = KhataGoIcons.Paid,
                                            contentDescription = null,
                                            tint = KhataGoColors.Settled,
                                        )
                                        Spacer(Modifier.width(KhataGoSpacing.sm))
                                        Text(
                                            text = "Fully settled — nothing further is due on this record.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = KhataGoColors.Settled,
                                        )
                                    }
                                }
                            }
                        },
                    )
                }

                if (model.meta.isNotEmpty()) {
                    item {
                        KhataGoCard(modifier = Modifier.padding(horizontal = KhataGoSpacing.screen)) {
                            SectionHeader(title = "Details")
                            Spacer(Modifier.height(KhataGoSpacing.sm))
                            model.meta.forEach { row ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Text(
                                        text = row.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(KhataGoSpacing.md))
                                    Text(
                                        text = row.value,
                                        style = if (row.emphasise) {
                                            MaterialTheme.typography.titleMedium
                                        } else {
                                            MaterialTheme.typography.bodyMedium
                                        },
                                        color = if (row.emphasise) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }

                if (model.schedule.isNotEmpty()) {
                    item {
                        KhataGoCard(modifier = Modifier.padding(horizontal = KhataGoSpacing.screen)) {
                            SectionHeader(
                                title = "Installment schedule",
                                subtitle = model.scheduleProgress?.let {
                                    "${it.paidCount} of ${it.totalCount} settled · next due " +
                                        AppDates.humanDay(it.nextDueEpochDay, model.todayEpochDay)
                                },
                            )
                            Spacer(Modifier.height(KhataGoSpacing.sm))
                            model.schedule.forEach { line ->
                                ScheduleLine(
                                    number = line.number,
                                    dueEpochDay = line.dueDateEpochDay,
                                    scheduledMinor = line.scheduledMinor,
                                    allocatedMinor = line.allocatedMinor,
                                    statusLabel = line.statusLabel,
                                    isSettled = line.isSettled,
                                    currency = model.currency,
                                    todayEpochDay = model.todayEpochDay,
                                )
                            }
                        }
                    }
                }

                if (model.payments.isNotEmpty()) {
                    item {
                        KhataGoCard(modifier = Modifier.padding(horizontal = KhataGoSpacing.screen)) {
                            SectionHeader(
                                title = "Payment history",
                                subtitle = "${model.payments.size} payment(s) recorded against this record",
                            )
                            Spacer(Modifier.height(KhataGoSpacing.sm))
                            model.payments.forEach { entry ->
                                PaymentHistoryLine(
                                    entry = entry,
                                    money = model.money,
                                    todayEpochDay = model.todayEpochDay,
                                    onDelete = { viewModel.deletePaymentById(entry.id, model) },
                                )
                            }
                        }
                    }
                }

                if (model.attachments.isNotEmpty()) {
                    item {
                        KhataGoCard(modifier = Modifier.padding(horizontal = KhataGoSpacing.screen)) {
                            SectionHeader(
                                title = "Photos and documents",
                                subtitle = "Stored inside the app's private folder, not in your gallery",
                            )
                            Spacer(Modifier.height(KhataGoSpacing.sm))
                            AttachmentStrip(
                                attachments = model.attachments,
                                onDelete = { viewModel.deleteAttachment(it) },
                            )
                        }
                    }
                }

                if (model.canDelete) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = KhataGoSpacing.screen)
                                .background(KhataGoColors.OverdueBg, RoundedCornerShape(KhataGoRadii.card))
                                .padding(KhataGoSpacing.lg),
                            verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.sm),
                        ) {
                            Text(
                                text = "Destructive actions",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF7A1C17),
                            )
                            Text(
                                text = "Cancelling keeps the record visible and excludes it from totals — " +
                                    "it is what a crossed-out line in a paper khata means. Deleting removes " +
                                    "the record and its payment history permanently.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF8E3A34),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(KhataGoSpacing.md)) {
                                if (!model.isCancelled) {
                                    com.khatago.finance.ui.components.TonalButton(
                                        text = "Cancel record",
                                        onClick = { viewModel.cancelRecord() },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                com.khatago.finance.ui.components.TonalButton(
                                    text = "Delete…",
                                    onClick = {
                                        deleteWarning = ""
                                        confirmDelete = true
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(KhataGoSpacing.lg)) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this record?") },
            text = {
                Text(
                    "Payments recorded against it will be deleted too, and every total in the app " +
                        "will change. This cannot be undone — if you only want it out of your totals, " +
                        "cancel it instead.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.deleteRecord(onDeleted = afterDelete)
                    },
                ) { Text("Delete permanently", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep it") }
            },
        )
    }

    snackbar?.let { message ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = KhataGoSpacing.xxl),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier
                    .background(KhataGoColors.Ink900, RoundedCornerShape(14.dp))
                    .clickable { snackbar = null }
                    .padding(horizontal = KhataGoSpacing.lg, vertical = KhataGoSpacing.md),
            )
        }
    }
}

@Composable
private fun ScheduleLine(
    number: Int,
    dueEpochDay: Long,
    scheduledMinor: Long,
    allocatedMinor: Long,
    statusLabel: String,
    isSettled: Boolean,
    currency: com.khatago.finance.core.money.CurrencySpec,
    todayEpochDay: Long,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$number",
            style = MaterialTheme.typography.labelMedium,
            color = if (isSettled) KhataGoColors.Settled else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = AppDates.humanDay(dueEpochDay, todayEpochDay),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = MoneyFormat.format(allocatedMinor, currency) +
                " / " + MoneyFormat.format(scheduledMinor, currency),
            style = KhataGoTypography.figure,
        )
    }
}

@Composable
private fun PaymentHistoryLine(
    entry: PaymentEntry,
    money: (Long) -> String,
    todayEpochDay: Long,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = KhataGoIcons.Check,
            contentDescription = null,
            tint = KhataGoColors.Settled,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(KhataGoSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.methodName + (entry.reference?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = (entry.installmentNumber?.let { "installment $it · " } ?: "") +
                    AppDates.humanDay(entry.paidDateEpochDay, todayEpochDay),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(text = money(entry.amountMinor), style = KhataGoTypography.figure)
        Spacer(Modifier.width(KhataGoSpacing.sm))
        Icon(
            imageVector = KhataGoIcons.Delete,
            contentDescription = "Delete this payment",
            tint = KhataGoColors.Ink400,
            modifier = Modifier
                .size(16.dp)
                .clickable(onClick = onDelete),
        )
    }
}

@Composable
private fun AttachmentStrip(
    attachments: List<AttachmentEntity>,
    onDelete: (AttachmentEntity) -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.sm)) {
        attachments.forEach { attachment ->
            var open by remember(attachment.id) { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KhataGoColors.SurfaceTinted, RoundedCornerShape(KhataGoRadii.field))
                    .clickable { open = true }
                    .padding(KhataGoSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = KhataGoIcons.Documents,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(KhataGoSpacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = attachment.originalName ?: attachment.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${attachment.sizeBytes / 1024} KB · stored on this device",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = KhataGoIcons.Delete,
                    contentDescription = "Remove attachment",
                    tint = KhataGoColors.Ink400,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onDelete(attachment) },
                )
            }
            if (open) {
                AttachmentViewer(
                    attachment = attachment,
                    onDismiss = { open = false },
                )
            }
            @Suppress("UNUSED_EXPRESSION")
            context
        }
    }
}

// --------------------------------------------------------------------------- routes

@Composable
fun ShopDetailRoute(
    container: AppContainer,
    shopId: Long,
    onBack: () -> Unit,
    onOpenCredit: (Long) -> Unit,
    onAddCredit: () -> Unit,
    onPay: () -> Unit,
    onEdit: () -> Unit = onAddCredit,
) {
    KhataGoDetailScreen(
        container = container,
        typeKey = "shop",
        id = shopId,
        onBack = onBack,
        // A shop has no payment of its own: its balance is the sum of its credits, so the pay action
        // routes to the first open credit rather than inventing a "pay the shop as a whole" rule.
        onPay = onPay,
        onEdit = onEdit,
        onOpenChild = onOpenCredit,
    )
    // A shop has no payment of its own: its balance is the sum of its credits. The action offered is
    // therefore "add a credit" — a payment against a shop as a whole would not know which record to
    // reduce, and inventing that rule is exactly how a ledger's totals stop adding up.
    Column(modifier = Modifier.padding(horizontal = KhataGoSpacing.screen)) {
        com.khatago.finance.ui.components.TonalButton(
            text = "Add a credit to this shop",
            onClick = onAddCredit,
        )
    }
}

@Composable
fun CreditDetailRoute(
    container: AppContainer,
    creditId: Long,
    onBack: () -> Unit,
    onPay: () -> Unit,
    onEdit: () -> Unit,
) {
    KhataGoDetailScreen(
        container = container,
        typeKey = "shop_credit",
        id = creditId,
        onBack = onBack,
        onPay = onPay,
        onEdit = onEdit,
    )
}

@Composable
fun LoanDetailRoute(
    container: AppContainer,
    obligationId: Long,
    kind: String,
    onBack: () -> Unit,
    onPay: () -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    KhataGoDetailScreen(
        container = container,
        typeKey = if (kind == "emi") "emi" else "loan",
        id = obligationId,
        onBack = onBack,
        onPay = onPay,
        // Editing a schedule is allowed, but only through the form: it is the single place that
        // regenerates installment lines, so a detail screen must never offer an inline amount edit.
        onEdit = onEdit,
    )
}

@Composable
fun EmiDetailRoute(
    container: AppContainer,
    emiId: Long,
    onBack: () -> Unit,
    onPay: () -> Unit,
) {
    LoanDetailRoute(
        container = container,
        obligationId = emiId,
        kind = "emi",
        onBack = onBack,
        onPay = onPay,
    )
}

@Composable
fun ObligationDetailRoute(
    container: AppContainer,
    type: String,
    id: Long,
    onBack: () -> Unit,
    onPay: () -> Unit,
) {
    KhataGoDetailScreen(
        container = container,
        typeKey = type,
        id = id,
        onBack = onBack,
        onPay = onPay,
    )
}

@Composable
fun PersonDetailRoute(
    container: AppContainer,
    personId: Long,
    onBack: () -> Unit,
    onOpenRecord: (String, Long) -> Unit,
) {
    PersonDetailScreen(container = container, personId = personId, onBack = onBack, onOpenRecord = onOpenRecord)
}

@Composable
fun LedgerDetailRoute(
    container: AppContainer,
    kind: String,
    id: Long,
    onBack: () -> Unit,
) {
    LedgerDetailScreen(container = container, kind = kind, id = id, onBack = onBack)
}
