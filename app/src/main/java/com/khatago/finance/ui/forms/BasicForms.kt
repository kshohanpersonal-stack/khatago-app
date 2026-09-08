package com.khatago.finance.ui.forms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.khatago.finance.AppContainer
import com.khatago.finance.core.money.CurrencySpec
import com.khatago.finance.core.money.MoneyParseResult
import com.khatago.finance.core.time.AppDates
import com.khatago.finance.data.db.entity.BorrowingEntity
import com.khatago.finance.data.db.entity.ExpenseEntity
import com.khatago.finance.data.db.entity.IncomeEntity
import com.khatago.finance.data.db.entity.LendingEntity
import com.khatago.finance.data.db.entity.PersonEntity
import com.khatago.finance.data.db.entity.ShopCreditEntity
import com.khatago.finance.data.db.entity.ShopEntity
import com.khatago.finance.data.repo.PersonSave
import com.khatago.finance.data.repo.SaveResult
import com.khatago.finance.data.repo.isSaved
import com.khatago.finance.ui.components.AmountField
import com.khatago.finance.ui.components.DateField
import com.khatago.finance.ui.components.DetailTopBar
import com.khatago.finance.ui.components.DropdownField
import com.khatago.finance.ui.components.FieldContext
import com.khatago.finance.ui.components.FormSection
import com.khatago.finance.ui.components.KhataGoIcons
import com.khatago.finance.ui.components.NoteField
import com.khatago.finance.ui.components.PrimaryButton
import com.khatago.finance.ui.components.TextFieldLine
import com.khatago.finance.ui.theme.KhataGoColors
import com.khatago.finance.ui.theme.KhataGoRadii
import com.khatago.finance.ui.theme.KhataGoSpacing
import kotlinx.coroutines.launch

/**
 * Add/edit screens for shops, credit records, people and income/expense entries.
 *
 * The forms share three behaviours, stated once because they are the difference between "usable" and
 * "trustworthy":
 *  - **Validation is the repository's, not the screen's.** Every form here calls the same
 *    `validateX`/`saveX` the backup importer and the sample-data loader use, so a rule cannot be
 *    bypassed by choosing a different entry point. (A UI-only validator is a suggestion.)
 *  - **Errors stay in the field that caused them**, and the typed value is preserved so the user
 *    corrects instead of retyping.
 *  - **The date of a record is always explicit** and defaults to today. Money that was "borrowed
 *    last Thursday" recorded today needs a backdated date or the ledger starts lying about time.
 */

@Composable
fun FormScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        DetailTopBar(title = title, onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = KhataGoSpacing.screen, vertical = KhataGoSpacing.md),
            verticalArrangement = Arrangement.spacedBy(KhataGoSpacing.lg),
            content = content,
        )
    }
}

private typealias ColumnScope = androidx.compose.foundation.layout.ColumnScope

@Composable
fun FormError(text: String?) {
    if (text.isNullOrBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .fillMaxWidth()
            .background(KhataGoColors.OverdueBg, RoundedCornerShape(KhataGoRadii.field))
            .padding(KhataGoSpacing.md),
    )
}

// --------------------------------------------------------------------------- shop

@Composable
fun ShopFormRoute(
    container: AppContainer,
    editId: Long? = null,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // The row being edited is kept as a value, not just an id: saving must preserve `archived`,
    // `createdAt` and any column this form does not show. Rebuilding the entity from the form fields
    // alone is how an edit silently resets the fields the form never had.
    var existing by remember { mutableStateOf<ShopEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(editId) {
        editId?.let { id ->
            container.shopRepository.findShop(id)?.let { shop ->
                existing = shop
                name = shop.name
                owner = shop.ownerName ?: ""
                phone = shop.phone ?: ""
                address = shop.address ?: ""
                category = shop.category ?: ""
                notes = shop.notes ?: ""
            }
        }
    }

    FormScaffold(title = if (editId == null) "Add a shop" else "Edit shop", onBack = onDone) {
        Text(
            text = "A shop is where you buy on credit — a mudi dokan, a pharmacy, a wholesale " +
                "supplier. Records hang off it, so its name should be how you would say it out loud.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FormSection(title = "Shop") {
            TextFieldLine(value = name, onValueChange = { name = it }, label = "Shop name", required = true, placeholder = "Rahman Store")
            TextFieldLine(value = owner, onValueChange = { owner = it }, label = "Owner", placeholder = "Optional")
            TextFieldLine(value = phone, onValueChange = { phone = it }, label = "Phone", keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone, placeholder = "Optional")
            TextFieldLine(value = address, onValueChange = { address = it }, label = "Address", placeholder = "Optional")
            TextFieldLine(value = category, onValueChange = { category = it }, label = "Category", placeholder = "Grocery, pharmacy…")
        }
        FormSection(title = "Notes") {
            NoteField(value = notes, onValueChange = { notes = it })
        }
        FormError(error)
        PrimaryButton(
            text = "Save shop",
            loading = saving,
            onClick = {
                error = null
                if (name.isBlank()) {
                    error = "A shop needs a name."
                    return@PrimaryButton
                }
                saving = true
                scope.launch {
                    val result = container.shopRepository.saveShop(
                        (existing ?: ShopEntity(
                            name = name.trim(),
                            createdAt = 0L,
                        )).copy(
                            name = name.trim(),
                            ownerName = owner.trim().takeIf { it.isNotEmpty() },
                            phone = phone.trim().takeIf { it.isNotEmpty() },
                            address = address.trim().takeIf { it.isNotEmpty() },
                            category = category.trim().takeIf { it.isNotEmpty() },
                            notes = notes.trim().takeIf { it.isNotEmpty() },
                        ),
                    )
                    saving = false
                    when (result) {
                        is SaveResult.Saved -> onDone()
                        is SaveResult.Invalid -> error = result.message
                        is SaveResult.Conflict -> error = result.message
                        is SaveResult.Mismatch -> error = result.message
                    }
                }
            },
        )
    }
}

// --------------------------------------------------------------------------- credit

@Composable
fun CreditFormRoute(
    container: AppContainer,
    preselectedShopId: Long?,
    creditId: Long?,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val today = remember { AppDates.today() }
    var shops by remember { mutableStateOf<List<ShopEntity>>(emptyList()) }
    var shopId by remember { mutableStateOf(preselectedShopId) }
    var product by remember { mutableStateOf("") }
    var quantityText by remember { mutableStateOf("1") }
    var unitPriceText by remember { mutableStateOf("") }
    var totalText by remember { mutableStateOf("") }
    var overrideTotal by remember { mutableStateOf(false) }
    var purchaseDate by remember { mutableStateOf(today) }
    var dueDate by remember { mutableStateOf<Long?>(null) }
    var notes by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var currency by remember { mutableStateOf(CurrencySpec.DEFAULT) }
    var saving by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(creditId) {
        shops = container.shopRepository.findAllShops().filterNot { it.archived }
        if (shopId == null) shopId = shops.firstOrNull()?.id
        currency = CurrencySpec.fromCode(container.catalogRepository.findProfile()?.currencyCode)
        creditId?.let { id ->
            container.shopRepository.findCredit(id)?.let { credit ->
                product = credit.productName
                quantityText = credit.quantity.toString()
                unitPriceText = com.khatago.finance.core.money.MoneyFormat.toCsvNumber(credit.unitPriceMinor, currency)
                totalText = com.khatago.finance.core.money.MoneyFormat.toCsvNumber(credit.totalAmountMinor, currency)
                overrideTotal = credit.overrideTotal
                purchaseDate = credit.purchaseDateEpochDay
                dueDate = credit.dueDateEpochDay
                notes = credit.notes ?: ""
                shopId = credit.shopId
            }
        }
        loaded = true
    }

    val context = FieldContext(currency = currency, todayEpochDay = today)
    val quantity = quantityText.toLongOrNull()?.takeIf { it > 0 }
    val unitParsed = MoneyParseResult.parse(unitPriceText, currency, allowZero = true)
    val totalParsed = MoneyParseResult.parse(totalText, currency, allowZero = true)
    val computedTotal = if (overrideTotal) {
        (totalParsed as? MoneyParseResult.Success)?.amount?.minor
    } else {
        ((unitParsed as? MoneyParseResult.Success)?.amount?.minor ?: 0L) * (quantity ?: 0L)
    }

    FormScaffold(title = if (creditId == null) "New credit record" else "Edit credit record", onBack = onDone) {
        if (shops.isEmpty() && loaded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KhataGoColors.DueSoonBg, RoundedCornerShape(KhataGoRadii.card))
                    .padding(KhataGoSpacing.lg),
            ) {
                Column {
                    Text(
                        text = "Add a shop first",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(KhataGoSpacing.xs))
                    Text(
                        text = "A credit record always belongs to a shop, so its balance can be " +
                            "totalled per supplier. Create the shop and come back.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@FormScaffold
        }

        FormSection(title = "What") {
            DropdownField(
                options = shops,
                selected = shops.firstOrNull { it.id == shopId },
                onSelect = { shopId = it.id },
                labelOf = { it.name },
                label = "Shop",
                required = true,
            )
            TextFieldLine(
                value = product,
                onValueChange = { product = it },
                label = "Item or description",
                required = true,
                placeholder = "5 bags of rice, medical supplies…",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(KhataGoSpacing.md)) {
                Box(modifier = Modifier.weight(1f)) {
                    TextFieldLine(
                        value = quantityText,
                        onValueChange = { quantityText = it.filter { ch -> ch.isDigit() } },
                        label = "Quantity",
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    AmountField(
                        value = unitPriceText,
                        onValueChange = { unitPriceText = it },
                        context = context,
                        label = "Unit price",
                        required = false,
                    )
                }
            }
            AmountField(
                value = totalText,
                onValueChange = { totalText = it; overrideTotal = true },
                context = context,
                label = "Total amount",
                helper = if (!overrideTotal && computedTotal != null) {
                    "Auto-calculated: " + com.khatago.finance.core.money.MoneyFormat.format(computedTotal, currency) +
                        " · edit this to override"
                } else {
                    "Overridden — quantity × unit price is ignored while you keep a manual total."
                },
            )
        }

        FormSection(title = "When") {
            DateField(epochDay = purchaseDate, onEpochDayChange = { purchaseDate = it ?: today }, label = "Purchase date", context = context)
            DateField(
                epochDay = dueDate,
                onEpochDayChange = { dueDate = it },
                label = "Due date",
                context = context,
                allowClear = true,
                helper = "Leave empty for “pay whenever”. A due date is what powers reminders and the overdue list.",
            )
        }

        FormSection(title = "Notes") {
            NoteField(value = notes, onValueChange = { notes = it })
        }

        FormError(error)

        PrimaryButton(
            text = if (creditId == null) "Save record" else "Save changes",
            loading = saving,
            onClick = {
                error = null
                scope.launch {
                    val result = container.shopRepository.saveCredit(
                        ShopCreditEntity(
                            id = creditId ?: 0L,
                            shopId = shopId ?: 0L,
                            productName = product.trim(),
                            quantity = quantity ?: 1L,
                            unitPriceMinor = (unitParsed as? MoneyParseResult.Success)?.amount?.minor ?: 0L,
                            totalAmountMinor = if (overrideTotal) {
                                (totalParsed as? MoneyParseResult.Success)?.amount?.minor ?: 0L
                            } else {
                                computedTotal ?: 0L
                            },
                            overrideTotal = overrideTotal,
                            purchaseDateEpochDay = purchaseDate,
                            dueDateEpochDay = dueDate,
                            notes = notes.trim().takeIf { it.isNotEmpty() },
                        ),
                    )
                    saving = false
                    when (result) {
                        is SaveResult.Saved -> onDone()
                        is SaveResult.Invalid -> error = result.message
                        is SaveResult.Conflict -> error = result.message
                        is SaveResult.Mismatch -> error = result.message
                    }
                }
            },
        )
    }
}

// --------------------------------------------------------------------------- person

@Composable
fun PersonFormRoute(
    container: AppContainer,
    initialDirection: String? = null,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val today = remember { AppDates.today() }
    var name by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf(PersonEntity.RELATIONSHIPS.first()) }
    var phone by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    // Preselected by the quick-add tile that opened this form; anything unrecognised falls back to
    // "borrowing" rather than an empty choice, because an unset direction would write to neither table.
    var direction by remember {
        mutableStateOf(if (initialDirection == "lending") "lending" else "borrowing")
    }
    var amountText by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(today) }
    var due by remember { mutableStateOf<Long?>(null) }
    var currency by remember { mutableStateOf(CurrencySpec.DEFAULT) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        currency = CurrencySpec.fromCode(container.catalogRepository.findProfile()?.currencyCode)
    }

    FormScaffold(title = "Add a person", onBack = onDone) {
        Text(
            text = "People are who you borrow from and lend to. The two directions are recorded " +
                "separately and never netted against each other.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FormSection(title = "Who") {
            TextFieldLine(value = name, onValueChange = { name = it }, label = "Name", required = true, placeholder = "Karim Bhaban")
            DropdownField(
                options = PersonEntity.RELATIONSHIPS,
                selected = relationship,
                onSelect = { relationship = it },
                labelOf = { it },
                label = "Relationship",
            )
            TextFieldLine(
                value = phone,
                onValueChange = { phone = it },
                label = "Phone",
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone,
            )
        }

        com.khatago.finance.ui.components.ChoiceTile(
            title = "I borrowed money from them",
            subtitle = "Adds to what you owe.",
            selected = direction == "borrowing",
            onClick = { direction = "borrowing" },
            icon = KhataGoIcons.Borrowed,
        )
        com.khatago.finance.ui.components.ChoiceTile(
            title = "I lent money to them",
            subtitle = "Adds to what you are owed — kept separate, never netted.",
            selected = direction == "lending",
            onClick = { direction = "lending" },
            icon = KhataGoIcons.Lent,
        )

        FormSection(title = "Amount") {
            AmountField(
                value = amountText,
                onValueChange = { amountText = it },
                context = FieldContext(currency, today),
                label = if (direction == "borrowing") "You received" : "You gave",
            )
            DateField(epochDay = date, onEpochDayChange = { date = it ?: today }, label = "Date", context = FieldContext(currency, today))
            DateField(
                epochDay = due,
                onEpochDayChange = { due = it },
                label = "Settlement date",
                context = FieldContext(currency, today),
                allowClear = true,
                helper = "Optional. When set, it drives the due list and reminders.",
            )
            NoteField(value = notes, onValueChange = { notes = it })
        }

        FormError(error)
        PrimaryButton(
            text = "Save",
            loading = saving,
            onClick = {
                error = null
                scope.launch {
                    if (name.isBlank()) {
                        error = "Enter a name."
                        saving = false
                        return@launch
                    }
                    val personResult = container.personRepository.savePerson(
                        PersonEntity(
                            name = name.trim(),
                            relationship = relationship,
                            phone = phone.trim().takeIf { it.isNotEmpty() },
                            notes = notes.trim().takeIf { it.isNotEmpty() },
                        ),
                    )
                    val personId = when (personResult) {
                        is PersonSave.Saved -> personResult.personId
                        is PersonSave.Invalid -> {
                            error = personResult.message
                            saving = false
                            return@launch
                        }
                    }
                    val amount = (MoneyParseResult.parse(amountText, currency, allowZero = false)
                        as? MoneyParseResult.Success)?.amount?.minor
                    if (amount == null) {
                        error = "Enter an amount greater than zero."
                        saving = false
                        return@launch
                    }
                    val obligation = if (direction == "borrowing") {
                        container.personRepository.saveBorrowing(
                            BorrowingEntity(
                                personId = personId,
                                amountMinor = amount,
                                borrowDateEpochDay = date,
                                dueDateEpochDay = due,
                                notes = notes.trim().takeIf { it.isNotEmpty() },
                            ),
                        )
                    } else {
                        container.personRepository.saveLending(
                            LendingEntity(
                                personId = personId,
                                amountMinor = amount,
                                lendDateEpochDay = date,
                                dueDateEpochDay = due,
                                notes = notes.trim().takeIf { it.isNotEmpty() },
                            ),
                        )
                    }
                    saving = false
                    if (obligation.isSaved) onDone() else {
                        error = (obligation as? SaveResult.Invalid)?.message
                            ?: "That record could not be saved."
                    }
                }
            },
        )
    }
}

// --------------------------------------------------------------------------- income / expense

@Composable
fun TransactionFormRoute(
    container: AppContainer,
    kind: String,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val today = remember { AppDates.today() }
    val isIncome = kind == "income"
    var amountText by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }
    var methodName by remember { mutableStateOf<String?>("Cash") }
    var methods by remember { mutableStateOf<List<String>>(listOf("Cash")) }
    var counterparty by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(today) }
    var note by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf(CurrencySpec.DEFAULT) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(kind) {
        currency = CurrencySpec.fromCode(container.catalogRepository.findProfile()?.currencyCode)
        categories = container.catalogRepository
            .findAllCategories(if (isIncome) "income" else "expense")
            .filter { it.enabled }
            .map { it.name }
            .ifEmpty { if (isIncome) listOf("Other") else listOf("Groceries", "Other") }
        category = categories.firstOrNull()
        methods = container.catalogRepository.findEnabledPaymentMethods().map { it.name }
            .ifEmpty { listOf("Cash", "bKash", "Nagad", "Bank transfer") }
        methodName = methods.firstOrNull()
    }

    FormScaffold(title = if (isIncome) "Add income" else "Add expense", onBack = onDone) {
        FormSection(title = if (isIncome) "Money in" else "Money out") {
            AmountField(
                value = amountText,
                onValueChange = { amountText = it },
                context = FieldContext(currency, today),
                label = if (isIncome) "Amount received" : "Amount spent",
            )
            DropdownField(
                options = categories,
                selected = category,
                onSelect = { category = it },
                labelOf = { it },
                label = "Category",
                required = true,
                helper = "Managed in Settings → Categories; built-in ones can be hidden, not deleted.",
            )
            DropdownField(
                options = methods,
                selected = methodName,
                onSelect = { methodName = it },
                labelOf = { it },
                label = "Payment method",
            )
            TextFieldLine(
                value = counterparty,
                onValueChange = { counterparty = it },
                label = if (isIncome) "Source" : "Merchant",
                placeholder = if (isIncome) "Salary, rice sold…" : "Shop name, rickshaw fare…",
            )
            DateField(
                epochDay = date,
                onEpochDayChange = { date = it ?: today },
                label = "Date",
                context = FieldContext(currency, today),
                helper = "Future dates are refused: an entry that has not happened yet belongs in a " +
                    "plan, not in a ledger of what happened.",
            )
            NoteField(value = note, onValueChange = { note = it })
        }
        FormError(error)
        PrimaryButton(
            text = "Save",
            loading = saving,
            onClick = {
                error = null
                scope.launch {
                    val amount = (MoneyParseResult.parse(amountText, currency, allowZero = false)
                        as? MoneyParseResult.Success)?.amount?.minor
                    if (amount == null) {
                        error = "Enter an amount greater than zero."
                        saving = false
                        return@launch
                    }
                    val result = if (isIncome) {
                        container.transactionRepository.saveIncome(
                            IncomeEntity(
                                amountMinor = amount,
                                source = counterparty.trim().takeIf { it.isNotEmpty() },
                                categoryName = category ?: "Other",
                                transactionDateEpochDay = date,
                                methodName = methodName ?: "Cash",
                                note = note.trim().takeIf { it.isNotEmpty() },
                            ),
                        )
                    } else {
                        container.transactionRepository.saveExpense(
                            ExpenseEntity(
                                amountMinor = amount,
                                categoryName = category ?: "Other",
                                merchant = counterparty.trim().takeIf { it.isNotEmpty() },
                                transactionDateEpochDay = date,
                                methodName = methodName ?: "Cash",
                                note = note.trim().takeIf { it.isNotEmpty() },
                            ),
                        )
                    }
                    saving = false
                    when (result) {
                        is SaveResult.Saved -> onDone()
                        is SaveResult.Invalid -> error = result.message
                        is SaveResult.Conflict -> error = result.message
                        is SaveResult.Mismatch -> error = result.message
                    }
                }
            },
        )
    }
}
