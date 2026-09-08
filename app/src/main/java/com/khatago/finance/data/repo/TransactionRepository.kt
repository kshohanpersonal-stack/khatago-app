package com.khatago.finance.data.repo

import androidx.room.withTransaction
import com.khatago.finance.data.db.KhataGoDatabase
import com.khatago.finance.data.db.entity.ExpenseEntity
import com.khatago.finance.data.db.entity.IncomeEntity
import com.khatago.finance.data.db.dao.LedgerRow
import kotlinx.coroutines.flow.Flow

/**
 * Income and expense records.
 *
 * Writes here are cheap and independent: unlike payments, an income row is not derived from
 * anything else, so there is no cross-table consistency to defend — which is why the two kinds live
 * in their own tables and their own repository rather than in one polymorphic "transaction" table
 * where a sign convention could go wrong.
 */
class TransactionRepository(private val database: KhataGoDatabase) {

    fun observeIncomes(): Flow<List<IncomeEntity>> = database.transactionDao().observeIncomes()

    fun observeExpenses(): Flow<List<ExpenseEntity>> = database.transactionDao().observeExpenses()

    fun observeLedger(
        kind: String?,
        startEpochDay: Long?,
        endEpochDay: Long?,
        category: String?,
    ): Flow<List<LedgerRow>> = database.transactionDao()
        .observeLedgerFiltered(kind, startEpochDay, endEpochDay, category)
        .let { flow -> flow }

    /** One-shot ledger read for the CSV/report writers. */
    suspend fun ledgerOnce(
        kind: String?,
        startEpochDay: Long?,
        endEpochDay: Long?,
        category: String? = null,
    ): List<LedgerRow> = database.transactionDao().ledgerFiltered(kind, startEpochDay, endEpochDay, category)

    fun observeMonthIncome(startEpochDay: Long, endEpochDay: Long): Flow<Long> =
        database.transactionDao().observeIncomeBetween(startEpochDay, endEpochDay)

    fun observeMonthExpense(startEpochDay: Long, endEpochDay: Long): Flow<Long> =
        database.transactionDao().observeExpenseBetween(startEpochDay, endEpochDay)

    suspend fun findIncome(id: Long): IncomeEntity? = database.transactionDao().findIncome(id)

    suspend fun findExpense(id: Long): ExpenseEntity? = database.transactionDao().findExpense(id)

    suspend fun findAllIncomes(): List<IncomeEntity> = database.transactionDao().findAllIncomes()

    suspend fun findAllExpenses(): List<ExpenseEntity> = database.transactionDao().findAllExpenses()

    fun validate(amountMinor: Long, dateEpochDay: Long, todayEpochDay: Long): String? = when {
        amountMinor <= 0L -> "The amount must be greater than zero."
        dateEpochDay < 0L -> "Pick a real date."
        else -> null
    }

    suspend fun saveIncome(income: IncomeEntity): SaveResult = database.withTransaction {
        if (income.source.isNullOrBlank() && income.categoryName.isBlank()) {
            return@withTransaction SaveResult.Invalid("Add a source or pick a category.")
        }
        validate(income.amountMinor, income.transactionDateEpochDay, income.transactionDateEpochDay)
            ?.let { return@withTransaction SaveResult.Invalid(it) }
        val now = System.currentTimeMillis()
        if (income.id == 0L) {
            database.transactionDao().insertIncome(income.copy(createdAt = now, updatedAt = now))
        } else {
            database.transactionDao().updateIncome(
                id = income.id,
                amountMinor = income.amountMinor,
                source = income.source?.trim()?.takeIf { it.isNotEmpty() },
                categoryName = income.categoryName,
                transactionDateEpochDay = income.transactionDateEpochDay,
                methodName = income.methodName,
                note = income.note?.trim()?.takeIf { it.isNotEmpty() },
                updatedAt = now,
            )
        }
        SaveResult.Saved
    }

    suspend fun saveExpense(expense: ExpenseEntity): SaveResult = database.withTransaction {
        if (expense.categoryName.isBlank()) {
            return@withTransaction SaveResult.Invalid("Pick a category.")
        }
        validate(expense.amountMinor, expense.transactionDateEpochDay, expense.transactionDateEpochDay)
            ?.let { return@withTransaction SaveResult.Invalid(it) }
        val now = System.currentTimeMillis()
        if (expense.id == 0L) {
            database.transactionDao().insertExpense(expense.copy(createdAt = now, updatedAt = now))
        } else {
            database.transactionDao().updateExpense(
                id = expense.id,
                amountMinor = expense.amountMinor,
                categoryName = expense.categoryName,
                merchant = expense.merchant?.trim()?.takeIf { it.isNotEmpty() },
                transactionDateEpochDay = expense.transactionDateEpochDay,
                methodName = expense.methodName,
                note = expense.note?.trim()?.takeIf { it.isNotEmpty() },
                updatedAt = now,
            )
        }
        SaveResult.Saved
    }

    /**
     * Undo support: the exact row is handed back to the caller *before* deletion so undo restores
     * the same record with the same id. Re-reading it after the delete would be impossible, and
     * re-inserting with a new id would break attachment links.
     */
    suspend fun deleteIncome(id: Long): IncomeEntity? = database.withTransaction {
        val row = database.transactionDao().findIncome(id)
        if (row != null) database.transactionDao().deleteIncome(id)
        row
    }

    suspend fun restoreIncome(income: IncomeEntity): Long = database.withTransaction {
        database.transactionDao().insertIncome(income)
    }

    suspend fun deleteExpense(id: Long): ExpenseEntity? = database.withTransaction {
        val row = database.transactionDao().findExpense(id)
        if (row != null) database.transactionDao().deleteExpense(id)
        row
    }

    suspend fun restoreExpense(expense: ExpenseEntity): Long = database.withTransaction {
        database.transactionDao().insertExpense(expense)
    }
}
