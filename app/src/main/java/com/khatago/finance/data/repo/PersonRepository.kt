package com.khatago.finance.data.repo

import androidx.room.withTransaction
import com.khatago.finance.data.db.KhataGoDatabase
import com.khatago.finance.data.db.entity.BorrowingEntity
import com.khatago.finance.data.db.entity.LendingEntity
import com.khatago.finance.data.db.entity.PersonEntity
import com.khatago.finance.data.db.dao.ObligationRow
import com.khatago.finance.data.db.dao.PersonBalanceRow
import com.khatago.finance.domain.calc.FinancialBalance
import com.khatago.finance.domain.model.Obligation
import com.khatago.finance.domain.model.PayableType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * People, and the two money directions they can sit in.
 *
 * The "person" form is deliberately forgiving: the user can type any name and record money in one
 * step, and a person row is created for them. That is how a khata actually gets used — in the shop,
 * in a message thread, in a hurry — and an app that forces you to create a contact before you can
 * note ৳500 loses the note.
 */
class PersonRepository(private val database: KhataGoDatabase) {

    fun observePeople(): Flow<List<PersonEntity>> = database.personDao().observeAllPeople()

    fun observePersonBalances(todayEpochDay: Long): Flow<List<PersonBalanceRow>> =
        database.personDao().observePersonBalances(todayEpochDay)

    fun observePerson(id: Long): Flow<PersonEntity?> = database.personDao().observePerson(id)

    suspend fun findPerson(id: Long): PersonEntity? = database.personDao().findPerson(id)

    suspend fun findAllPeople(): List<PersonEntity> = database.personDao().findAllPeople()

    /**
     * Saves a person, and if none exists yet, creates one. Returns the id the caller must use for
     * the obligation it is about to write — never "look it up again later", which is a race that
     * can attach a borrowing to the wrong person.
     */
    suspend fun savePerson(person: PersonEntity): PersonSave = database.withTransaction {
        val name = person.name.trim()
        if (name.isEmpty()) return@withTransaction PersonSave.Invalid("Enter the person's name.")
        if (name.length > 60) return@withTransaction PersonSave.Invalid("Keep the name under 60 characters.")
        val existing = database.personDao().findPersonId(name, person.relationship)
        val now = System.currentTimeMillis()
        when {
            person.id != 0L -> {
                database.personDao().updatePerson(person.copy(name = name, updatedAt = now))
                PersonSave.Saved(person.id, createdNew = false)
            }

            existing != null -> PersonSave.Saved(existing, createdNew = false)

            else -> {
                val id = database.personDao().insertPerson(
                    person.copy(name = name, createdAt = now, updatedAt = now),
                )
                PersonSave.Saved(id, createdNew = true)
            }
        }
    }

    suspend fun deletePersonWithObligations(person: PersonEntity): Int = database.withTransaction {
        database.personDao().deletePersonWithObligations(person)
    }

    // --- borrowings / lendings ------------------------------------------------

    fun observeBorrowings(personId: Long?, hideCancelled: Boolean): Flow<List<ObligationRow>> =
        database.personDao().observeBorrowingBalances(personId, hideCancelled)

    fun observeLendings(personId: Long?, hideCancelled: Boolean): Flow<List<ObligationRow>> =
        database.personDao().observeLendingBalances(personId, hideCancelled)

    fun observeBorrowingObligations(): Flow<List<Obligation>> =
        database.personDao().observeBorrowingBalances(null, hideCancelled = false).map { rows ->
            rows.map { it.toObligation(PayableType.Borrowing) }
        }

    fun observeLendingObligations(): Flow<List<Obligation>> =
        database.personDao().observeLendingBalances(null, hideCancelled = false).map { rows ->
            rows.map { it.toObligation(PayableType.Lending) }
        }

    fun observeTotalOwedToPeople(): Flow<Long> = database.personDao().observeTotalOwedToPeople()

    fun observeTotalOwedToMe(): Flow<Long> = database.personDao().observeTotalOwedToMe()

    suspend fun findBorrowing(id: Long): BorrowingEntity? = database.personDao().findBorrowing(id)

    suspend fun findLending(id: Long): LendingEntity? = database.personDao().findLending(id)

    suspend fun findAllBorrowings(): List<BorrowingEntity> = database.personDao().findAllBorrowings()

    suspend fun findAllLendings(): List<LendingEntity> = database.personDao().findAllLendings()

    suspend fun saveBorrowing(entity: BorrowingEntity): SaveResult = database.withTransaction {
        if (entity.amountMinor <= 0L) {
            return@withTransaction SaveResult.Invalid("The amount must be greater than zero.")
        }
        if (entity.dueDateEpochDay != null && entity.dueDateEpochDay < entity.borrowDateEpochDay) {
            return@withTransaction SaveResult.Invalid("The return date cannot be before the day you borrowed.")
        }
        if (entity.id != 0L) {
            val existing = database.personDao().findBorrowing(entity.id)
                ?: return@withTransaction SaveResult.Invalid("That record no longer exists.")
            val paid = database.paymentDao().paidTotal("borrowing", entity.id)
            if (paid > entity.amountMinor) {
                return@withTransaction SaveResult.Invalid(
                    "This borrowing already has repayments recorded, so the amount cannot go below them.",
                )
            }
            database.personDao().updateBorrowing(entity.copy(updatedAt = System.currentTimeMillis()))
        } else {
            val now = System.currentTimeMillis()
            database.personDao().insertBorrowing(entity.copy(createdAt = now, updatedAt = now))
        }
        SaveResult.Saved
    }

    suspend fun saveLending(entity: LendingEntity): SaveResult = database.withTransaction {
        if (entity.amountMinor <= 0L) {
            return@withTransaction SaveResult.Invalid("The amount must be greater than zero.")
        }
        if (entity.dueDateEpochDay != null && entity.dueDateEpochDay < entity.lendDateEpochDay) {
            return@withTransaction SaveResult.Invalid("The return date cannot be before the day you lent.")
        }
        if (entity.id != 0L) {
            val existing = database.personDao().findLending(entity.id)
                ?: return@withTransaction SaveResult.Invalid("That record no longer exists.")
            val paid = database.paymentDao().paidTotal("lending", entity.id)
            if (paid > entity.amountMinor) {
                return@withTransaction SaveResult.Invalid(
                    "This lending already has returns recorded, so the amount cannot go below them.",
                )
            }
            database.personDao().updateLending(entity.copy(updatedAt = System.currentTimeMillis()))
        } else {
            val now = System.currentTimeMillis()
            database.personDao().insertLending(entity.copy(createdAt = now, updatedAt = now))
        }
        SaveResult.Saved
    }

    suspend fun deleteBorrowing(id: Long) = database.withTransaction {
        val payments = database.paymentDao().findForPayable("borrowing", id)
        payments.forEach { database.paymentDao().delete(it) }
        database.personDao().deleteBorrowingById(id)
    }

    suspend fun deleteLending(id: Long) = database.withTransaction {
        val payments = database.paymentDao().findForPayable("lending", id)
        payments.forEach { database.paymentDao().delete(it) }
        database.personDao().deleteLendingById(id)
    }

    suspend fun findBorrowingPayments(id: Long) = database.paymentDao().findForPayable("borrowing", id)

    suspend fun findLendingPayments(id: Long) = database.paymentDao().findForPayable("lending", id)
}

private fun ObligationRow.toObligation(type: PayableType) = Obligation(
    type = type,
    id = id,
    title = title,
    subtitle = subtitle,
    balance = FinancialBalance(totalMinor, paidMinor),
    dueDateEpochDay = dueDateEpochDay,
    cancelled = cancelled,
)

/** [PersonSave] distinguishes "created" from "matched an existing person" so the UI can say so. */
sealed interface PersonSave {
    data class Saved(val personId: Long, val createdNew: Boolean) : PersonSave
    data class Invalid(val message: String) : PersonSave
}
