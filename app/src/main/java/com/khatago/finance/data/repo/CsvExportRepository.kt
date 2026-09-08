package com.khatago.finance.data.repo

import android.content.Context
import android.net.Uri
import com.khatago.finance.core.csv.CsvWriter
import com.khatago.finance.core.money.CurrencySpec
import com.khatago.finance.core.money.MoneyFormat
import com.khatago.finance.core.time.AppDates
import com.khatago.finance.data.db.KhataGoDatabase
import com.khatago.finance.data.db.entity.ExpenseEntity
import com.khatago.finance.data.db.entity.IncomeEntity
import com.khatago.finance.domain.calc.FinancialBalance
import com.khatago.finance.domain.model.LedgerStatus
import com.khatago.finance.domain.model.PayableType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CSV export, per module, with the same numbers the app shows.
 *
 * Two rules that a lot of "export" features get wrong:
 *  - **Amounts are exported as plain major-unit numbers** (`1400.00`, no currency symbol, no
 *    thousands separators), so a spreadsheet reads them as numbers instead of text. The currency is
 *    stated in the header instead.
 *  - **Balances come from the same derivation the UI uses** ([FinancialBalance]), so an exported
 *    file can be reconciled against the screen. An export that disagrees with the app is worse than
 *    no export.
 *
 * Text fields are quoted per RFC 4180 by [CsvWriter], which is what makes a shop called
 * `Rahman's Store, Main Rd` survive the round trip.
 */
class CsvExportRepository(
    private val context: Context,
    private val database: KhataGoDatabase,
) {

    enum class Report(val fileName: String, val title: String) {
        Overall("khatago-overall.csv", "Overall financial summary"),
        ShopCredits("khatago-shop-credits.csv", "Shop credits"),
        Loans("khatago-loans.csv", "Loans"),
        Emis("khatago-emi.csv", "EMI purchases"),
        Borrowings("khatago-borrowed.csv", "Borrowed money"),
        Lendings("khatago-lent.csv", "Money lent"),
        Payments("khatago-payments.csv", "Payments"),
        Income("khatago-income.csv", "Income"),
        Expenses("khatago-expenses.csv", "Expenses"),
        Outstanding("khatago-outstanding.csv", "Outstanding obligations"),
    }

    /** Builds a report in memory; exposed for tests. */
    suspend fun buildCsv(
        report: Report,
        currency: CurrencySpec,
        range: LongRange?,
        todayEpochDay: Long,
    ): String = withContext(Dispatchers.IO) {
        val writer = CsvWriter()
        writer.header("# KhataGo — ${report.title}")
        writer.header("Currency", currency.code, "Generated", DATE_TIME.format(Date()))
        writer.header(
            "Range",
            if (range == null) "All time" else "${AppDates.formatMedium(range.first)} to ${AppDates.formatMedium(range.last)}",
        )
        writer.row(emptyList<String?>())
        when (report) {
            Report.Overall -> overall(writer, currency, todayEpochDay)
            Report.ShopCredits -> shopCredits(writer, currency, range)
            Report.Loans -> loans(writer, currency, todayEpochDay)
            Report.Emis -> emis(writer, currency, todayEpochDay)
            Report.Borrowings -> borrowings(writer, currency, todayEpochDay)
            Report.Lendings -> lendings(writer, currency, todayEpochDay)
            Report.Payments -> payments(writer, currency, range)
            Report.Income -> incomes(writer, currency, range)
            Report.Expenses -> expenses(writer, currency, range)
            Report.Outstanding -> outstanding(writer, currency, todayEpochDay)
        }
        // BOM: Excel on Windows needs it to read the ৳ glyph in the header rows.
        CsvWriter.Bom + writer.build()
    }

    suspend fun write(uri: Uri, csv: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(csv.toByteArray(Charsets.UTF_8))
                stream.flush()
            } ?: false
        }.getOrDefault(false)
    }

    // --- reports --------------------------------------------------------------

    private suspend fun overall(writer: CsvWriter, currency: CurrencySpec, today: Long) {
        writer.row("Metric", "Amount", "Unit")
        val month = com.khatago.finance.domain.calc.SnapshotCalculator.currentMonthRange(today)
        writer.row("Total I owe", MoneyFormat.toCsvNumber(database.statsDao().totalIOweOnce(), currency), currency.code)
        writer.row("Total owed to me", MoneyFormat.toCsvNumber(database.statsDao().totalOwedToMeOnce(), currency), currency.code)
        writer.row("Overdue amount", MoneyFormat.toCsvNumber(database.statsDao().overdueAmountOnce(today), currency), currency.code)
        writer.row("Income this month", MoneyFormat.toCsvNumber(database.transactionDao().incomeBetween(month.first, month.last), currency), currency.code)
        writer.row("Expense this month", MoneyFormat.toCsvNumber(database.transactionDao().expenseBetween(month.first, month.last), currency), currency.code)
        writer.row("Paid against records this month", MoneyFormat.toCsvNumber(database.paymentDao().paidBetween(month.first, month.last), currency), currency.code)
        writer.row(emptyList<String?>())
        writer.row(
            "Record counts",
            "shops/credits/people/borrowings/lendings/loans/EMIs/instalments/payments/income/expense/attachments/reminders",
        )
        writer.row("Counts", database.statsDao().recordCounts(), "")
    }

    private suspend fun shopCredits(writer: CsvWriter, currency: CurrencySpec, range: LongRange?) {
        writer.row("Shop", "Item", "Quantity", "Unit price", "Total", "Paid", "Remaining", "Status", "Purchase date", "Due date")
        database.shopDao().findAllCreditRows()
            .filter { range == null || it.purchaseDateEpochDay in range }
            .forEach { row ->
                val balance = FinancialBalance(row.totalMinor, row.paidMinor)
                writer.row(
                    row.shopName,
                    row.productName,
                    row.quantity.toString(),
                    MoneyFormat.toCsvNumber(row.unitPriceMinor, currency),
                    MoneyFormat.toCsvNumber(balance.originalMinor, currency),
                    MoneyFormat.toCsvNumber(balance.paidMinor, currency),
                    MoneyFormat.toCsvNumber(balance.remainingMinor, currency),
                    LedgerStatus.of(balance.originalMinor, balance.paidMinor, row.dueDateEpochDay, 0L, row.cancelled).label,
                    AppDates.formatMedium(row.purchaseDateEpochDay),
                    row.dueDateEpochDay?.let(AppDates::formatMedium) ?: "",
                )
            }
    }

    private suspend fun loans(writer: CsvWriter, currency: CurrencySpec, today: Long) {
        writer.row("Institution", "Loan", "Principal", "Total payable", "Down payment", "Paid", "Remaining", "EMIs paid", "EMIs total", "Status")
        database.loanDao().findAll().forEach { loan ->
            val installments = database.loanDao().findInstallments(loan.id)
            val paid = loan.downPaymentMinor + database.paymentDao().paidTotal("loan", loan.id)
            val balance = FinancialBalance(loan.totalPayableMinor + loan.downPaymentMinor, paid)
            writer.row(
                loan.institution,
                loan.loanName,
                MoneyFormat.toCsvNumber(loan.principalMinor, currency),
                MoneyFormat.toCsvNumber(loan.totalPayableMinor, currency),
                MoneyFormat.toCsvNumber(loan.downPaymentMinor, currency),
                MoneyFormat.toCsvNumber(balance.paidMinor, currency),
                MoneyFormat.toCsvNumber(balance.remainingMinor, currency),
                installments.count { it.scheduledAmountMinor > 0L && it.paidMinor >= it.scheduledAmountMinor }.toString(),
                installments.size.toString(),
                balance.status(loan.endDateEpochDay, today, loan.cancelled).label,
            )
        }
    }

    private suspend fun emis(writer: CsvWriter, currency: CurrencySpec, today: Long) {
        writer.row("Product", "Merchant", "Cash price", "Total payable", "Down payment", "Paid", "Remaining", "EMIs paid", "EMIs total", "Status")
        database.emiDao().findAll().forEach { emi ->
            val installments = database.emiDao().findInstallments(emi.id)
            val paid = emi.downPaymentMinor + database.paymentDao().paidTotal("emi", emi.id)
            val balance = FinancialBalance(emi.totalPayableMinor, paid)
            writer.row(
                emi.productName,
                emi.merchant,
                MoneyFormat.toCsvNumber(emi.cashPriceMinor, currency),
                MoneyFormat.toCsvNumber(emi.totalPayableMinor, currency),
                MoneyFormat.toCsvNumber(emi.downPaymentMinor, currency),
                MoneyFormat.toCsvNumber(balance.paidMinor, currency),
                MoneyFormat.toCsvNumber(balance.remainingMinor, currency),
                installments.count { it.scheduledAmountMinor > 0L && it.paidMinor >= it.scheduledAmountMinor }.toString(),
                installments.size.toString(),
                balance.status(emi.endDateEpochDay, today, emi.cancelled).label,
            )
        }
    }

    private suspend fun borrowings(writer: CsvWriter, currency: CurrencySpec, today: Long) {
        writer.row("Person", "Amount", "Repaid", "Remaining", "Borrowed on", "Expected return", "Status")
        database.personDao().findAllBorrowings().forEach { borrowing ->
            val person = database.personDao().findPerson(borrowing.personId)
            val paid = database.paymentDao().paidTotal("borrowing", borrowing.id)
            val balance = FinancialBalance(borrowing.amountMinor, paid)
            writer.row(
                person?.name ?: "Unknown",
                MoneyFormat.toCsvNumber(balance.originalMinor, currency),
                MoneyFormat.toCsvNumber(balance.paidMinor, currency),
                MoneyFormat.toCsvNumber(balance.remainingMinor, currency),
                AppDates.formatMedium(borrowing.borrowDateEpochDay),
                borrowing.dueDateEpochDay?.let(AppDates::formatMedium) ?: "",
                balance.status(borrowing.dueDateEpochDay, today, borrowing.cancelled).label,
            )
        }
    }

    private suspend fun lendings(writer: CsvWriter, currency: CurrencySpec, today: Long) {
        writer.row("Person", "Amount", "Returned", "Outstanding", "Lent on", "Expected return", "Status")
        database.personDao().findAllLendings().forEach { lending ->
            val person = database.personDao().findPerson(lending.personId)
            val paid = database.paymentDao().paidTotal("lending", lending.id)
            val balance = FinancialBalance(lending.amountMinor, paid)
            writer.row(
                person?.name ?: "Unknown",
                MoneyFormat.toCsvNumber(balance.originalMinor, currency),
                MoneyFormat.toCsvNumber(balance.paidMinor, currency),
                MoneyFormat.toCsvNumber(balance.remainingMinor, currency),
                AppDates.formatMedium(lending.lendDateEpochDay),
                lending.dueDateEpochDay?.let(AppDates::formatMedium) ?: "",
                balance.status(lending.dueDateEpochDay, today, lending.cancelled).label,
            )
        }
    }

    private suspend fun payments(writer: CsvWriter, currency: CurrencySpec, range: LongRange?) {
        writer.row("Date", "Against", "Type", "Amount", "Method", "Reference", "Note")
        database.paymentDao().findAll().filter { range == null || it.paidDateEpochDay in range }
            .sortedByDescending { it.paidDateEpochDay }
            .forEach { payment ->
                val type = PayableType.fromKey(payment.payableType)
                writer.row(
                    AppDates.formatMedium(payment.paidDateEpochDay),
                    paymentLabel(type, payment.payableId),
                    type?.displayName ?: payment.payableType,
                    MoneyFormat.toCsvNumber(payment.amountMinor, currency),
                    payment.methodName,
                    payment.reference.orEmpty(),
                    payment.note.orEmpty(),
                )
            }
    }

    private suspend fun paymentLabel(type: PayableType?, payableId: Long): String = when (type) {
        PayableType.ShopCredit -> database.creditDao().findById(payableId)?.productName ?: "#$payableId"
        PayableType.Loan -> database.loanDao().findById(payableId)?.loanName ?: "#$payableId"
        PayableType.Emi -> database.emiDao().findById(payableId)?.productName ?: "#$payableId"
        PayableType.Borrowing -> database.personDao().findBorrowing(payableId)?.let {
            database.personDao().findPerson(it.personId)?.name
        } ?: "#$payableId"
        PayableType.Lending -> database.personDao().findLending(payableId)?.let {
            database.personDao().findPerson(it.personId)?.name
        } ?: "#$payableId"
        null -> "#$payableId"
    }

    private suspend fun incomes(writer: CsvWriter, currency: CurrencySpec, range: LongRange?) {
        writer.row("Date", "Amount", "Category", "Source", "Method", "Note")
        database.transactionDao().findAllIncomes()
            .filter { range == null || it.transactionDateEpochDay in range }
            .sortedByDescending(IncomeEntity::transactionDateEpochDay)
            .forEach { income ->
                writer.row(
                    AppDates.formatMedium(income.transactionDateEpochDay),
                    MoneyFormat.toCsvNumber(income.amountMinor, currency),
                    income.categoryName,
                    income.source.orEmpty(),
                    income.methodName,
                    income.note.orEmpty(),
                )
            }
    }

    private suspend fun expenses(writer: CsvWriter, currency: CurrencySpec, range: LongRange?) {
        writer.row("Date", "Amount", "Category", "Merchant/place", "Method", "Note")
        database.transactionDao().findAllExpenses()
            .filter { range == null || it.transactionDateEpochDay in range }
            .sortedByDescending(ExpenseEntity::transactionDateEpochDay)
            .forEach { expense ->
                writer.row(
                    AppDates.formatMedium(expense.transactionDateEpochDay),
                    MoneyFormat.toCsvNumber(expense.amountMinor, currency),
                    expense.categoryName,
                    expense.merchant.orEmpty(),
                    expense.methodName,
                    expense.note.orEmpty(),
                )
            }
    }

    private suspend fun outstanding(writer: CsvWriter, currency: CurrencySpec, today: Long) {
        writer.row("Record", "Type", "Original", "Paid", "Remaining", "Due", "Status")
        database.shopDao().findAllCreditRows().filter { !it.cancelled }.forEach { row ->
            val balance = FinancialBalance(row.totalMinor, row.paidMinor)
            if (!balance.isSettled) {
                writer.row(
                    row.productName,
                    "Shop credit (${row.shopName})",
                    MoneyFormat.toCsvNumber(balance.originalMinor, currency),
                    MoneyFormat.toCsvNumber(balance.paidMinor, currency),
                    MoneyFormat.toCsvNumber(balance.remainingMinor, currency),
                    row.dueDateEpochDay?.let { AppDates.formatMedium(it) } ?: "",
                    balance.status(row.dueDateEpochDay, today).label,
                )
            }
        }
        listOf(PayableType.Loan, PayableType.Emi, PayableType.Borrowing, PayableType.Lending)
            .flatMap { type -> outstandingOf(type, today, currency) }
            .forEach { writer.row(it.first, it.second, it.third, it.fourth, it.fifth, it.sixth, it.seventh) }
    }

    private suspend fun outstandingOf(
        type: PayableType,
        today: Long,
        currency: CurrencySpec,
    ): List<List<String>> = when (type) {
        PayableType.Loan -> database.loanDao().findAll().mapNotNull { loan ->
            val paid = loan.downPaymentMinor + database.paymentDao().paidTotal("loan", loan.id)
            val balance = FinancialBalance(loan.totalPayableMinor + loan.downPaymentMinor, paid)
            if (balance.isSettled || loan.cancelled) null else listOf(
                loan.loanName,
                "Loan (${loan.institution})",
                MoneyFormat.toCsvNumber(balance.originalMinor, currency),
                MoneyFormat.toCsvNumber(balance.paidMinor, currency),
                MoneyFormat.toCsvNumber(balance.remainingMinor, currency),
                "",
                balance.status(null, today, loan.cancelled).label,
            )
        }
        PayableType.Emi -> database.emiDao().findAll().mapNotNull { emi ->
            val paid = emi.downPaymentMinor + database.paymentDao().paidTotal("emi", emi.id)
            val balance = FinancialBalance(emi.totalPayableMinor, paid)
            if (balance.isSettled || emi.cancelled) null else listOf(
                emi.productName,
                "EMI (${emi.merchant})",
                MoneyFormat.toCsvNumber(balance.originalMinor, currency),
                MoneyFormat.toCsvNumber(balance.paidMinor, currency),
                MoneyFormat.toCsvNumber(balance.remainingMinor, currency),
                "",
                balance.status(emi.endDateEpochDay, today, emi.cancelled).label,
            )
        }
        else -> emptyList()
    }

    private companion object {
        val DATE_TIME = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.ENGLISH)
    }
}
