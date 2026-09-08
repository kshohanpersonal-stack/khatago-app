package com.khatago.finance.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khatago.finance.AppContainer
import com.khatago.finance.core.money.CurrencySpec
import com.khatago.finance.core.money.MoneyFormat
import com.khatago.finance.core.time.AppDates
import com.khatago.finance.data.db.entity.AttachmentEntity
import com.khatago.finance.data.repo.SaveResult
import com.khatago.finance.data.repo.isSaved
import com.khatago.finance.domain.model.InstallmentView
import com.khatago.finance.domain.model.LedgerStatus
import com.khatago.finance.domain.model.ObligationSnapshot
import com.khatago.finance.domain.model.PayableType
import com.khatago.finance.domain.model.PaymentEntry
import com.khatago.finance.domain.model.ScheduleProgress
import com.khatago.finance.ui.components.StatusTone
import com.khatago.finance.ui.components.toneForLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The detail model every record screen renders.
 *
 * Deliberately one shape for all seven kinds of record (shop, credit, loan, EMI, borrowing, lending,
 * ledger entry): a user opening a record from *search*, a *notification*, the *payment centre* or a
 * *CSV report header* must land on the same layout with the same numbers. Seven bespoke screens is
 * seven chances for the definition of "remaining" to diverge.
 */
data class MetaRow(val label: String, val value: String, val emphasise: Boolean = false)

data class DetailModel(
    val typeKey: String,
    val id: Long,
    val title: String,
    val subtitle: String,
    val originalMinor: Long,
    val paidMinor: Long,
    val remainingMinor: Long,
    val dueDateEpochDay: Long?,
    val statusLabel: String,
    val statusTone: StatusTone,
    val meta: List<MetaRow>,
    val note: String?,
    /** Only for loans/EMIs: installment lines with their allocation. */
    val schedule: List<InstallmentView>,
    val scheduleProgress: ScheduleProgress?,
    /** Only for flat obligations: the payment ledger for this record. */
    val payments: List<PaymentEntry>,
    val attachments: List<AttachmentEntity>,
    val currency: CurrencySpec,
    val todayEpochDay: Long,
    val canRecordPayment: Boolean,
    val canEdit: Boolean,
    val canDelete: Boolean,
    val isCancelled: Boolean,
    val isSettled: Boolean,
) {
    val payoffFraction: Float
        get() = if (originalMinor <= 0L) 1f else (paidMinor.toFloat() / originalMinor.toFloat()).coerceIn(0f, 1f)

    val remainingLabel: String get() = MoneyFormat.format(remainingMinor, currency)
    val money: (Long) -> String get() = { MoneyFormat.format(it, currency) }
}

/**
 * Loads one record.
 *
 * Note what this class does **not** do: no balance arithmetic. Every figure comes from
 * `ObligationSnapshot` / `ScheduleProgress` / the credit row projection — the same derivation the lists,
 * the reports and the payment guard use. A detail screen that recomputed "remaining" from the rows it
 * can see would be free to disagree with the payment engine, and the payment engine is the one that
 * decides what gets written.
 */
class DetailViewModel(
    private val container: AppContainer,
    private val typeKey: String,
    private val id: Long,
) : ViewModel() {

    val todayEpochDay: Long = AppDates.today()

    private val payable: PayableType? = PayableType.fromKey(typeKey)

    private val currencyFlow: Flow<CurrencySpec> =
        container.catalogRepository.observeProfile().map { CurrencySpec.fromCode(it?.currencyCode) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: StateFlow<DetailUiState> = currencyFlow.flatMapLatest { currency ->
        combine(
            modelFlow(currency),
            container.attachmentRepository.observe(attachmentTargetFor(typeKey), id),
        ) { model, attachments ->
            DetailUiState.Ready(model.copy(attachments = attachments))
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DetailUiState.Loading,
    )

    private fun modelFlow(currency: CurrencySpec): Flow<DetailModel> = when (typeKey) {
        "shop" -> container.shopRepository.observeSummary(id, todayEpochDay).map { row ->
            DetailModel(
                typeKey = "shop",
                id = id,
                title = row?.name ?: "Shop",
                subtitle = listOfNotNull(row?.ownerName?.let { "Owner: $it" }, row?.phone).joinToString(" · ")
                    .ifEmpty { "Shop account" },
                originalMinor = row?.totalMinor ?: 0L,
                paidMinor = row?.paidMinor ?: 0L,
                remainingMinor = (row?.totalMinor ?: 0L) - (row?.paidMinor ?: 0L),
                dueDateEpochDay = row?.nextDueDateEpochDay,
                statusLabel = if ((row?.overdueMinor ?: 0L) > 0L) "Overdue present" else "Active",
                statusTone = if ((row?.overdueMinor ?: 0L) > 0L) StatusTone.Overdue else StatusTone.Active,
                meta = listOfNotNull(
                    row?.category?.let { MetaRow("Category", it) },
                    row?.address?.let { MetaRow("Address", it) },
                    MetaRow("Credit records", "${row?.creditCount ?: 0}"),
                    MetaRow("Still open", "${row?.activeCount ?: 0}"),
                    MetaRow("Last purchase", AppDates.formatMedium(row?.lastCreditDateEpochDay)),
                    MetaRow("Next due", AppDates.humanDay(row?.nextDueDateEpochDay, todayEpochDay)),
                    if (row?.archived == true) MetaRow("Status", "Archived") else null,
                ),
                note = null,
                schedule = emptyList(),
                scheduleProgress = null,
                payments = emptyList(),
                attachments = emptyList(),
                currency = currency,
                todayEpochDay = todayEpochDay,
                canRecordPayment = false,
                canEdit = true,
                canDelete = true,
                isCancelled = false,
                isSettled = false,
            )
        }

        "shop_credit" -> container.shopRepository.observeCredits(null, hideCancelled = false)
            .map { rows ->
                val row = rows.firstOrNull { it.id == id }
                DetailModel(
                    typeKey = "shop_credit",
                    id = id,
                    title = row?.productName ?: "Credit record",
                    subtitle = row?.shopName ?: "",
                    originalMinor = row?.totalMinor ?: 0L,
                    paidMinor = row?.paidMinor ?: 0L,
                    remainingMinor = (row?.totalMinor ?: 0L) - (row?.paidMinor ?: 0L),
                    dueDateEpochDay = row?.dueDateEpochDay,
                    statusLabel = statusLabelFor(row?.totalMinor ?: 0L, row?.paidMinor ?: 0L, row?.dueDateEpochDay, row?.cancelled == true),
                    statusTone = toneFor(row?.totalMinor ?: 0L, row?.paidMinor ?: 0L, row?.dueDateEpochDay, row?.cancelled == true),
                    meta = listOfNotNull(
                        row?.let { MetaRow("Quantity", it.quantity.toString()) },
                        row?.let { MetaRow("Unit price", MoneyFormat.format(it.unitPriceMinor, currency)) },
                        MetaRow("Purchase date", AppDates.formatMedium(row?.purchaseDateEpochDay)),
                        MetaRow("Due date", AppDates.formatMedium(row?.dueDateEpochDay)),
                    ),
                    note = row?.notes,
                    schedule = emptyList(),
                    scheduleProgress = null,
                    payments = emptyList(),
                    attachments = emptyList(),
                    currency = currency,
                    todayEpochDay = todayEpochDay,
                    canRecordPayment = row?.cancelled != true,
                    canEdit = true,
                    canDelete = true,
                    isCancelled = row?.cancelled == true,
                    isSettled = (row?.totalMinor ?: 1L) - (row?.paidMinor ?: 0L) <= 0L,
                )
            }

        "loan", "emi" -> combine(
            container.paymentRepository.observeObligation(typeKey, id),
            container.obligationRepository.observeScheduleProgress(
                ownerType = if (typeKey == "loan") PayableType.Loan else PayableType.Emi,
                ownerId = id,
                todayEpochDay = todayEpochDay,
            ),
            container.paymentRepository.observePaymentEntries(
                payableType = if (typeKey == "loan") PayableType.Loan else PayableType.Emi,
                payableId = id,
            ),
        ) { snapshot, progress, entries ->
            val status = statusFor(snapshot)
            DetailModel(
                typeKey = typeKey,
                id = id,
                title = snapshot?.title ?: "Record",
                subtitle = snapshot?.subtitle ?: "",
                originalMinor = snapshot?.originalMinor ?: 0L,
                paidMinor = snapshot?.recordedPaidMinor ?: 0L,
                remainingMinor = snapshot?.remainingMinor ?: 0L,
                dueDateEpochDay = snapshot?.dueDateEpochDay,
                statusLabel = status.first,
                statusTone = status.second,
                meta = scheduleMeta(progress),
                note = null,
                schedule = progress.allocated,
                scheduleProgress = progress,
                payments = entries,
                attachments = emptyList(),
                currency = currency,
                todayEpochDay = todayEpochDay,
                canRecordPayment = snapshot?.cancelled != true && (snapshot?.remainingMinor ?: 0L) > 0L,
                canEdit = true,
                canDelete = true,
                isCancelled = snapshot?.cancelled == true,
                isSettled = (snapshot?.remainingMinor ?: 1L) <= 0L,
            )
        }

        "borrowing", "lending" -> combine(
            container.paymentRepository.observeObligation(typeKey, id),
            container.paymentRepository.observePaymentEntries(
                payableType = payable ?: PayableType.Borrowing,
                payableId = id,
            ),
        ) { snapshot, entries ->
            val status = statusFor(snapshot)
            val isLending = typeKey == "lending"
            DetailModel(
                typeKey = typeKey,
                id = id,
                title = snapshot?.title ?: if (isLending) "Lent" else "Borrowed",
                subtitle = snapshot?.subtitle ?: "",
                originalMinor = snapshot?.originalMinor ?: 0L,
                paidMinor = snapshot?.recordedPaidMinor ?: 0L,
                remainingMinor = snapshot?.remainingMinor ?: 0L,
                dueDateEpochDay = snapshot?.dueDateEpochDay,
                statusLabel = status.first,
                statusTone = status.second,
                meta = listOfNotNull(
                    MetaRow(
                        if (isLending) "They must return" else "You must return",
                        MoneyFormat.format(snapshot?.remainingMinor ?: 0L, currency),
                        emphasise = true,
                    ),
                    MetaRow("Due", AppDates.humanDay(snapshot?.dueDateEpochDay, todayEpochDay)),
                    MetaRow(
                        "Direction",
                        if (isLending) {
                            "Owed to you — never added to what you owe"
                        } else {
                            "You owe this — never netted against what you are owed"
                        },
                    ),
                ),
                note = null,
                schedule = emptyList(),
                scheduleProgress = null,
                payments = entries,
                attachments = emptyList(),
                currency = currency,
                todayEpochDay = todayEpochDay,
                canRecordPayment = snapshot?.cancelled != true,
                canEdit = false,
                canDelete = true,
                isCancelled = snapshot?.cancelled == true,
                isSettled = (snapshot?.remainingMinor ?: 1L) <= 0L,
            )
        }

        else -> flowOfMissing(currency)
    }

    private fun flowOfMissing(currency: CurrencySpec): Flow<DetailModel> =
        kotlinx.coroutines.flow.flowOf(
            DetailModel(
                typeKey = typeKey,
                id = id,
                title = "Record not found",
                subtitle = "",
                originalMinor = 0,
                paidMinor = 0,
                remainingMinor = 0,
                dueDateEpochDay = null,
                statusLabel = "Missing",
                statusTone = StatusTone.Info(),
                meta = emptyList(),
                note = "It may have been deleted on another screen. Nothing was changed here.",
                schedule = emptyList(),
                scheduleProgress = null,
                payments = emptyList(),
                attachments = emptyList(),
                currency = currency,
                todayEpochDay = todayEpochDay,
                canRecordPayment = false,
                canEdit = false,
                canDelete = false,
                isCancelled = false,
                isSettled = false,
            ),
        )

    private fun statusLabelFor(total: Long, paid: Long, due: Long?, cancelled: Boolean): String =
        statusPair(total, paid, due, cancelled).first

    private fun toneFor(total: Long, paid: Long, due: Long?, cancelled: Boolean): StatusTone =
        statusPair(total, paid, due, cancelled).second

    private fun statusPair(total: Long, paid: Long, due: Long?, cancelled: Boolean): Pair<String, StatusTone> {
        val remaining = total - paid
        val status = when {
            cancelled -> LedgerStatus.Cancelled
            remaining <= 0L -> LedgerStatus.Paid
            due != null && due < todayEpochDay -> LedgerStatus.Overdue
            else -> LedgerStatus.Unpaid
        }
        return status.label to toneForLedger(status)
    }

    private fun statusFor(snapshot: ObligationSnapshot?): Pair<String, StatusTone> {
        if (snapshot == null) return "Missing" to StatusTone.Info()
        return statusPair(
            total = snapshot.originalMinor,
            paid = snapshot.recordedPaidMinor,
            due = snapshot.dueDateEpochDay,
            cancelled = snapshot.cancelled,
        ).let { (label, tone) ->
            // A settled record is labelled settled even if its due date has passed; it is only shown
            // as overdue while money is still owed, so a paid-off loan is never printed in red.
            if (snapshot.remainingMinor <= 0L) LedgerStatus.Paid.label to StatusTone.Settled else label to tone
        }
    }

    private fun scheduleMeta(progress: ScheduleProgress): List<MetaRow> = listOf(
        MetaRow("Installments", "${progress.paidCount} of ${progress.totalCount} paid · ${progress.percentLabel}"),
        MetaRow("Next due", AppDates.humanDay(progress.nextDueEpochDay, todayEpochDay)),
    ) + if (progress.overdueCount > 0) {
        listOf(MetaRow("Overdue lines", "${progress.overdueCount}", emphasise = true))
    } else {
        emptyList()
    }

    // --- mutations ------------------------------------------------------------

    /** A one-line result message for the snackbar; set only by the actions below. */
    var message: String? = null
        private set

    fun dismissMessage() {
        message = null
    }

    fun cancelRecord(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val result: SaveResult = when (typeKey) {
                "shop_credit" -> container.shopRepository.findCredit(id)?.let { credit ->
                    container.shopRepository.saveCredit(credit.copy(cancelled = true))
                } ?: SaveResult.Invalid("That record no longer exists.")

                else -> SaveResult.Invalid("Cancellation for this record type is done from its edit screen.")
            }
            message = if (result.isSaved) "Marked as cancelled. It stays in your history and is excluded from totals." else (result as? SaveResult.Invalid)?.message
            if (result.isSaved) onDone()
        }
    }

    fun deleteRecord(onDeleted: () -> Unit) {
        viewModelScope.launch {
            val count: Int = when (typeKey) {
                "shop_credit" -> container.shopRepository.deleteCredit(id)
                "loan" -> {
                    container.obligationRepository.deleteLoan(id)
                    1
                }

                "emi" -> {
                    container.obligationRepository.deleteEmi(id)
                    1
                }

                "borrowing" -> {
                    container.personRepository.deleteBorrowing(id)
                    1
                }

                "lending" -> {
                    container.personRepository.deleteLending(id)
                    1
                }

                "shop" -> container.shopRepository.findShop(id)?.let { container.shopRepository.deleteShop(it) } ?: 0
                else -> 0
            }
            message = if (count > 0) {
                "Deleted. $count record(s) were removed with their payment history."
            } else {
                "Nothing was deleted."
            }
            onDeleted()
        }
    }

    /**
     * Deleting a payment *repairs* the derived state: the installment allocation is recomputed inside
     * the same transaction. That is why this path exists at all — an app that lets a payment vanish
     * without re-allocating would leave a schedule permanently marked "part paid".
     */
    fun deletePaymentById(paymentId: Long, model: DetailModel, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val payment = container.paymentRepository.findById(paymentId)
            if (payment == null) {
                message = "That payment is already gone."
                return@launch
            }
            runCatching { container.paymentRepository.delete(payment) }
                .onSuccess {
                    message = "Payment removed and the balance recalculated."
                    onDone()
                }
                .onFailure { message = "Could not remove that payment: ${it.message}" }
        }
    }

    fun deletePayment(payment: PaymentEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { container.paymentRepository.delete(payment) }
                .onSuccess {
                    message = "Payment removed and the balance recalculated."
                    onDone()
                }
                .onFailure { message = "That payment could not be removed: ${it.message}" }
        }
    }

    fun attachImage(uri: android.net.Uri, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            val saved = runCatching {
                container.attachmentRepository.import(uri, attachmentTargetFor(typeKey), id)
            }.getOrNull()
            message = when {
                saved != null -> "Image attached to this record."
                else -> "That image could not be stored. It may be too large or unreadable."
            }
            if (saved == null) onError("Could not attach that image.")
        }
    }

    fun deleteAttachment(attachment: AttachmentEntity) {
        viewModelScope.launch {
            runCatching { container.attachmentRepository.delete(attachment) }
            message = "Attachment removed."
        }
    }

    /** Deleting a person/shop needs to say what goes with it, so the UI asks the repository, not itself. */
    suspend fun deletionImpactText(): String = withContext(Dispatchers.IO) {
        when (typeKey) {
            "shop" -> container.shopRepository.shopDeletionImpact(id).describe()
            else -> "This record's payments are deleted with it, and totals update immediately."
        }
    }

    fun editInstallment(installment: InstallmentView, newDueEpochDay: Long?, newAmountMinor: Long?) {
        viewModelScope.launch {
            // Installment lines live in one table keyed by (ownerType, ownerId), so the id alone is
            // enough — no per-module lookup, and no chance of editing a loan line from an EMI screen.
            val entity = container.database.installmentDao().findById(installment.id) ?: return@launch
            container.obligationRepository.editInstallment(
                installment = entity,
                newDueDateEpochDay = newDueEpochDay,
                newScheduledMinor = newAmountMinor,
            )
            message = "Installment updated. Manual edits are marked as such, so a schedule rebuild " +
                "will not silently overwrite them."
        }
    }
}

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Ready(val model: DetailModel) : DetailUiState
}

/**
 * Attachment namespaces are stored as plain strings so a new module can be attached without a
 * migration; this is the single mapping between a record type and its folder-level key.
 */
fun attachmentTargetFor(typeKey: String): String = when (typeKey) {
    "shop" -> AttachmentEntity.TARGET_SHOP
    "shop_credit" -> AttachmentEntity.TARGET_SHOP_CREDIT
    "person" -> AttachmentEntity.TARGET_PERSON
    "loan" -> AttachmentEntity.TARGET_LOAN
    "emi" -> AttachmentEntity.TARGET_EMI
    "borrowing" -> AttachmentEntity.TARGET_BORROWING
    "lending" -> AttachmentEntity.TARGET_LENDING
    "income" -> AttachmentEntity.TARGET_INCOME
    "expense" -> AttachmentEntity.TARGET_EXPENSE
    else -> AttachmentEntity.TARGET_PAYMENT
}
