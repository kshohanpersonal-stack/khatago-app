package com.khatago.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.khatago.finance.data.db.entity.BorrowingEntity
import com.khatago.finance.data.db.entity.LendingEntity
import com.khatago.finance.data.db.entity.PersonEntity
import kotlinx.coroutines.flow.Flow

/**
 * People and their two-sided balances.
 *
 * Borrowings (money the user owes) and lendings (money owed to the user) live in **separate
 * tables**, never one table with a direction flag. That is a deliberate structural choice: a
 * single `direction` column eventually gets summed with the wrong sign in some clever query, and
 * the user sees "You owe ৳0" on a screen that silently netted two debts against two loans. Separate
 * tables make that class of bug impossible.
 */
@Dao
interface PersonDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPerson(person: PersonEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllPeople(people: List<PersonEntity>): List<Long>

    @Update
    suspend fun updatePerson(person: PersonEntity)

    @Query("DELETE FROM people WHERE id = :id")
    suspend fun deletePersonById(id: Long)

    @Query("DELETE FROM people")
    suspend fun deleteAllPeople()

    @Query("SELECT * FROM people WHERE id = :id")
    suspend fun findPerson(id: Long): PersonEntity?

    @Query("SELECT * FROM people WHERE id = :id")
    fun observePerson(id: Long): Flow<PersonEntity?>

    @Query("SELECT * FROM people ORDER BY name COLLATE NOCASE ASC")
    fun observeAllPeople(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM people ORDER BY name COLLATE NOCASE ASC")
    suspend fun findAllPeople(): List<PersonEntity>

    @Query(
        """
        SELECT id FROM people
        WHERE name = :name COLLATE NOCASE AND relationship = :relationship
        LIMIT 1
        """,
    )
    suspend fun findPersonId(name: String, relationship: String): Long?

    // --- Borrowings ----------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBorrowing(borrowing: BorrowingEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllBorrowings(borrowings: List<BorrowingEntity>): List<Long>

    @Update
    suspend fun updateBorrowing(borrowing: BorrowingEntity)

    @Query("SELECT * FROM borrowings WHERE id = :id")
    suspend fun findBorrowing(id: Long): BorrowingEntity?

    @Query("SELECT * FROM borrowings")
    suspend fun findAllBorrowings(): List<BorrowingEntity>

    @Query("DELETE FROM borrowings")
    suspend fun deleteAllBorrowings()

    @Query("DELETE FROM borrowings WHERE id = :id")
    suspend fun deleteBorrowingById(id: Long)

    @Query("SELECT * FROM borrowings WHERE personId = :personId ORDER BY borrowDateEpochDay DESC, id DESC")
    fun observeBorrowingsForPerson(personId: Long): Flow<List<BorrowingEntity>>

    @Query(
        """
        SELECT
            b.id AS id,
            p.name || ' · borrowed' AS title,
            COALESCE(b.notes, '') AS subtitle,
            b.amountMinor AS totalMinor,
            COALESCE((SELECT SUM(pm.amountMinor) FROM payments pm
                       WHERE pm.payableType = 'borrowing' AND pm.payableId = b.id), 0) AS paidMinor,
            b.dueDateEpochDay AS dueDateEpochDay,
            b.cancelled AS cancelled
        FROM borrowings b
        JOIN people p ON p.id = b.personId
        WHERE (:personId IS NULL OR b.personId = :personId)
          AND (:hideCancelled = 0 OR b.cancelled = 0)
        ORDER BY b.borrowDateEpochDay DESC, b.id DESC
        """,
    )
    fun observeBorrowingBalances(personId: Long?, hideCancelled: Boolean): Flow<List<ObligationRow>>

    // --- Lendings ------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLending(lending: LendingEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllLendings(lendings: List<LendingEntity>): List<Long>

    @Update
    suspend fun updateLending(lending: LendingEntity)

    @Query("SELECT * FROM lendings WHERE id = :id")
    suspend fun findLending(id: Long): LendingEntity?

    @Query("SELECT * FROM lendings")
    suspend fun findAllLendings(): List<LendingEntity>

    @Query("DELETE FROM lendings")
    suspend fun deleteAllLendings()

    @Query("DELETE FROM lendings WHERE id = :id")
    suspend fun deleteLendingById(id: Long)

    @Query("SELECT * FROM lendings WHERE personId = :personId ORDER BY lendDateEpochDay DESC, id DESC")
    fun observeLendingsForPerson(personId: Long): Flow<List<LendingEntity>>

    @Query(
        """
        SELECT
            l.id AS id,
            p.name || ' · lent' AS title,
            COALESCE(l.notes, '') AS subtitle,
            l.amountMinor AS totalMinor,
            COALESCE((SELECT SUM(pm.amountMinor) FROM payments pm
                       WHERE pm.payableType = 'lending' AND pm.payableId = l.id), 0) AS paidMinor,
            l.dueDateEpochDay AS dueDateEpochDay,
            l.cancelled AS cancelled
        FROM lendings l
        JOIN people p ON p.id = l.personId
        WHERE (:personId IS NULL OR l.personId = :personId)
          AND (:hideCancelled = 0 OR l.cancelled = 0)
        ORDER BY l.lendDateEpochDay DESC, l.id DESC
        """,
    )
    fun observeLendingBalances(personId: Long?, hideCancelled: Boolean): Flow<List<ObligationRow>>

    @Query(
        """
        SELECT COALESCE(SUM(owed.amountMinor - owed.paidMinor), 0) AS totalMinor
        FROM (
            SELECT b.amountMinor AS amountMinor,
                   (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                     WHERE p.payableType = 'borrowing' AND p.payableId = b.id) AS paidMinor
            FROM borrowings b
            WHERE b.cancelled = 0
        ) owed
        WHERE owed.amountMinor - owed.paidMinor > 0
        """,
    )
    fun observeTotalOwedToPeople(): Flow<Long>

    @Query(
        """
        SELECT COALESCE(SUM(owed.amountMinor - owed.paidMinor), 0) AS totalMinor
        FROM (
            SELECT l.amountMinor AS amountMinor,
                   (SELECT COALESCE(SUM(p.amountMinor), 0) FROM payments p
                     WHERE p.payableType = 'lending' AND p.payableId = l.id) AS paidMinor
            FROM lendings l
            WHERE l.cancelled = 0
        ) owed
        WHERE owed.amountMinor - owed.paidMinor > 0
        """,
    )
    fun observeTotalOwedToMe(): Flow<Long>

    /**
     * Per-person rollup for the people list. Two independent subselects keep "I owe" and
     * "they owe me" apart even for a person who is on both sides of the ledger.
     */
    @Query(
        """
        SELECT
            p.id AS id,
            p.name AS name,
            p.relationship AS relationship,
            p.phone AS phone,
            COALESCE((
                SELECT SUM(b.amountMinor - (
                    SELECT COALESCE(SUM(pm.amountMinor), 0) FROM payments pm
                    WHERE pm.payableType = 'borrowing' AND pm.payableId = b.id))
                FROM borrowings b WHERE b.personId = p.id AND b.cancelled = 0
                  AND b.amountMinor > (SELECT COALESCE(SUM(pm.amountMinor), 0) FROM payments pm
                                       WHERE pm.payableType = 'borrowing' AND pm.payableId = b.id)
            ), 0) AS owedToThemMinor,
            COALESCE((
                SELECT SUM(l.amountMinor - (
                    SELECT COALESCE(SUM(pm.amountMinor), 0) FROM payments pm
                    WHERE pm.payableType = 'lending' AND pm.payableId = l.id))
                FROM lendings l WHERE l.personId = p.id AND l.cancelled = 0
                  AND l.amountMinor > (SELECT COALESCE(SUM(pm.amountMinor), 0) FROM payments pm
                                       WHERE pm.payableType = 'lending' AND pm.payableId = l.id)
            ), 0) AS owedToMeMinor,
            (SELECT MIN(dueDates.dueDate) FROM (
                SELECT b.dueDateEpochDay AS dueDate FROM borrowings b
                  WHERE b.personId = p.id AND b.cancelled = 0 AND b.dueDateEpochDay >= :today
                UNION ALL
                SELECT l.dueDateEpochDay AS dueDate FROM lendings l
                  WHERE l.personId = p.id AND l.cancelled = 0 AND l.dueDateEpochDay >= :today
            ) AS dueDates) AS nextDueDateEpochDay
        FROM people p
        ORDER BY owedToThemMinor + owedToMeMinor DESC, p.name COLLATE NOCASE ASC
        """,
    )
    fun observePersonBalances(today: Long): Flow<List<PersonBalanceRow>>

    /**
     * Deleting a person removes their obligations *and* those obligations' payments atomically,
     * because the payment ledger's payableId is intentionally not a foreign key.
     */
    @Transaction
    suspend fun deletePersonWithObligations(person: PersonEntity): Int {
        val borrowingIds = findBorrowingIds(person.id)
        val lendingIds = findLendingIds(person.id)
        val paymentIds = if (borrowingIds.isEmpty() && lendingIds.isEmpty()) {
            emptyList()
        } else {
            collectPaymentIds(borrowingIds, lendingIds)
        }
        if (paymentIds.isNotEmpty()) deletePaymentsByIds(paymentIds)
        deleteBorrowingsForPerson(person.id)
        deleteLendingsForPerson(person.id)
        deletePersonById(person.id)
        return borrowingIds.size + lendingIds.size
    }

    @Query("SELECT id FROM borrowings WHERE personId = :personId")
    suspend fun findBorrowingIds(personId: Long): List<Long>

    @Query("SELECT id FROM lendings WHERE personId = :personId")
    suspend fun findLendingIds(personId: Long): List<Long>

    @Query(
        """
        SELECT id FROM payments
        WHERE (payableType = 'borrowing' AND payableId IN (:borrowingIds))
           OR (payableType = 'lending' AND payableId IN (:lendingIds))
        """,
    )
    suspend fun collectPaymentIds(borrowingIds: List<Long>, lendingIds: List<Long>): List<Long>

    @Query("DELETE FROM payments WHERE id IN (:ids)")
    suspend fun deletePaymentsByIds(ids: List<Long>)

    @Query("DELETE FROM borrowings WHERE personId = :personId")
    suspend fun deleteBorrowingsForPerson(personId: Long)

    @Query("DELETE FROM lendings WHERE personId = :personId")
    suspend fun deleteLendingsForPerson(personId: Long)
}
