package com.khatago.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.khatago.finance.data.db.entity.ExpenseEntity
import com.khatago.finance.data.db.entity.IncomeEntity
import kotlinx.coroutines.flow.Flow

/**
 * Income and expense records — the cash-flow half of KhataGo.
 *
 * Kept as two tables rather than one `transactions` table with a signed amount: a sign convention
 * is a rule, and rules get broken. Two tables mean "money in" and "money out" cannot be conflated
 * by an accidentally missing `-` anywhere in the app.
 */
@Dao
interface TransactionDao {

    // --- income ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIncome(income: IncomeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllIncomes(incomes: List<IncomeEntity>): List<Long>

    @Query("UPDATE incomes SET amountMinor = :amountMinor, source = :source, categoryName = :categoryName, " +
        "transactionDateEpochDay = :transactionDateEpochDay, methodName = :methodName, note = :note, " +
        "updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateIncome(
        id: Long,
        amountMinor: Long,
        source: String?,
        categoryName: String,
        transactionDateEpochDay: Long,
        methodName: String,
        note: String?,
        updatedAt: Long,
    )

    @Query("DELETE FROM incomes WHERE id = :id")
    suspend fun deleteIncome(id: Long)

    @Query("DELETE FROM incomes")
    suspend fun deleteAllIncomes()

    @Query("SELECT * FROM incomes WHERE id = :id")
    suspend fun findIncome(id: Long): IncomeEntity?

    @Query("SELECT * FROM incomes ORDER BY transactionDateEpochDay DESC, id DESC")
    fun observeIncomes(): Flow<List<IncomeEntity>>

    @Query("SELECT * FROM incomes")
    suspend fun findAllIncomes(): List<IncomeEntity>

    // --- expense --------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllExpenses(expenses: List<ExpenseEntity>): List<Long>

    @Query("UPDATE expenses SET amountMinor = :amountMinor, categoryName = :categoryName, merchant = :merchant, " +
        "transactionDateEpochDay = :transactionDateEpochDay, methodName = :methodName, note = :note, " +
        "updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateExpense(
        id: Long,
        amountMinor: Long,
        categoryName: String,
        merchant: String?,
        transactionDateEpochDay: Long,
        methodName: String,
        note: String?,
        updatedAt: Long,
    )

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun deleteExpense(id: Long)

    @Query("DELETE FROM expenses")
    suspend fun deleteAllExpenses()

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun findExpense(id: Long): ExpenseEntity?

    @Query("SELECT * FROM expenses ORDER BY transactionDateEpochDay DESC, id DESC")
    fun observeExpenses(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses")
    suspend fun findAllExpenses(): List<ExpenseEntity>

    // --- unified ledger ------------------------------------------------------

    /**
     * Income and expense as one stream, for Records, Recent Activity, search and CSV export.
     *
     * Filters are applied to the *union*, never inside a branch: a `WHERE` on the second SELECT of
     * a UNION ALL would silently filter only expenses and quietly leak unfiltered income — a real
     * correctness trap in ledger UIs, so this query exposes one filtered view only.
     */
    @Query("SELECT COUNT(*) FROM incomes")
    suspend fun incomeCount(): Int

    @Query("SELECT COUNT(*) FROM expenses")
    suspend fun expenseCount(): Int

    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM incomes
        WHERE transactionDateEpochDay BETWEEN :startEpochDay AND :endEpochDay
        """,
    )
    fun observeIncomeBetween(startEpochDay: Long, endEpochDay: Long): Flow<Long>
    /**
     * The unified income+expense feed, filtered in **one** place.
     *
     * Filters are applied to the *union*, never inside a branch: a `WHERE` on the second SELECT of
     * a UNION ALL would silently filter only expenses and quietly leak unfiltered income — a real
     * correctness trap in ledger UIs, so this query exposes one filtered view only. Amounts are
     * always positive and the `kind` column decides the sign, so no arithmetic here depends on a
     * convention that a future edit could break.
     */
    @Query(
        """
        SELECT * FROM (
            SELECT
                id AS id,
                'income' AS kind,
                amountMinor AS amountMinor,
                categoryName AS categoryName,
                COALESCE(source, '') AS counterparty,
                transactionDateEpochDay AS transactionDateEpochDay,
                methodName AS methodName,
                note AS note
            FROM incomes
            UNION ALL
            SELECT
                id AS id,
                'expense' AS kind,
                amountMinor AS amountMinor,
                categoryName AS categoryName,
                COALESCE(merchant, '') AS counterparty,
                transactionDateEpochDay AS transactionDateEpochDay,
                methodName AS methodName,
                note AS note
            FROM expenses
        )
        WHERE (:kind IS NULL OR kind = :kind)
          AND (:startEpochDay IS NULL OR transactionDateEpochDay >= :startEpochDay)
          AND (:endEpochDay IS NULL OR transactionDateEpochDay <= :endEpochDay)
          AND (:category IS NULL OR categoryName = :category)
        ORDER BY transactionDateEpochDay DESC, id DESC
        """,
    )
    fun observeLedgerFiltered(
        kind: String?,
        startEpochDay: Long?,
        endEpochDay: Long?,
        category: String?,
    ): Flow<List<LedgerRow>>

    /** One-shot twin of [observeLedgerFiltered], for CSV/report writers that must not hold a Flow. */
    @Query(
        """
        SELECT * FROM (
            SELECT
                id AS id,
                'income' AS kind,
                amountMinor AS amountMinor,
                categoryName AS categoryName,
                COALESCE(source, '') AS counterparty,
                transactionDateEpochDay AS transactionDateEpochDay,
                methodName AS methodName,
                note AS note
            FROM incomes
            UNION ALL
            SELECT
                id AS id,
                'expense' AS kind,
                amountMinor AS amountMinor,
                categoryName AS categoryName,
                COALESCE(merchant, '') AS counterparty,
                transactionDateEpochDay AS transactionDateEpochDay,
                methodName AS methodName,
                note AS note
            FROM expenses
        )
        WHERE (:kind IS NULL OR kind = :kind)
          AND (:startEpochDay IS NULL OR transactionDateEpochDay >= :startEpochDay)
          AND (:endEpochDay IS NULL OR transactionDateEpochDay <= :endEpochDay)
          AND (:category IS NULL OR categoryName = :category)
        ORDER BY transactionDateEpochDay DESC, id DESC
        """,
    )
    suspend fun ledgerFiltered(
        kind: String?,
        startEpochDay: Long?,
        endEpochDay: Long?,
        category: String?,
    ): List<LedgerRow>



    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM expenses
        WHERE transactionDateEpochDay BETWEEN :startEpochDay AND :endEpochDay
        """,
    )
    fun observeExpenseBetween(startEpochDay: Long, endEpochDay: Long): Flow<Long>

    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM incomes
        WHERE transactionDateEpochDay BETWEEN :startEpochDay AND :endEpochDay
        """,
    )
    suspend fun incomeBetween(startEpochDay: Long, endEpochDay: Long): Long

    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM expenses
        WHERE transactionDateEpochDay BETWEEN :startEpochDay AND :endEpochDay
        """,
    )
    suspend fun expenseBetween(startEpochDay: Long, endEpochDay: Long): Long

    /**
     * One-month totals for the cash-flow chart. Bucketing by calendar month is done by the
     * repository issuing one bounded query per month instead of using SQLite date functions:
     * `strftime` on an epoch day depends on the database's timezone handling, and a chart month
     * that silently shifts across a DST boundary is exactly the kind of wrong-but-plausible number
     * this app cannot afford.
     */
    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM incomes
        WHERE transactionDateEpochDay BETWEEN :startEpochDay AND :endEpochDay
        """,
    )
    suspend fun incomeTotalInRange(startEpochDay: Long, endEpochDay: Long): Long

    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM expenses
        WHERE transactionDateEpochDay BETWEEN :startEpochDay AND :endEpochDay
        """,
    )
    suspend fun expenseTotalInRange(startEpochDay: Long, endEpochDay: Long): Long

    @Query(
        """
        SELECT COUNT(*) FROM incomes WHERE transactionDateEpochDay BETWEEN :startEpochDay AND :endEpochDay
        """,
    )
    suspend fun incomeCountInRange(startEpochDay: Long, endEpochDay: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM expenses WHERE transactionDateEpochDay BETWEEN :startEpochDay AND :endEpochDay
        """,
    )
    suspend fun expenseCountInRange(startEpochDay: Long, endEpochDay: Long): Int

    @Query(
        """
        SELECT categoryName AS name, SUM(amountMinor) AS totalMinor, COUNT(*) AS recordCount
        FROM expenses
        WHERE transactionDateEpochDay BETWEEN :startEpochDay AND :endEpochDay
        GROUP BY categoryName
        ORDER BY totalMinor DESC
        """,
    )
    fun observeExpenseByCategory(startEpochDay: Long, endEpochDay: Long): Flow<List<CategoryTotalRow>>

    @Query(
        """
        SELECT COALESCE(source, categoryName) AS name, SUM(amountMinor) AS totalMinor, COUNT(*) AS recordCount
        FROM incomes
        WHERE transactionDateEpochDay BETWEEN :startEpochDay AND :endEpochDay
        GROUP BY name
        ORDER BY totalMinor DESC
        """,
    )
    fun observeIncomeBySource(startEpochDay: Long, endEpochDay: Long): Flow<List<CategoryTotalRow>>
}
