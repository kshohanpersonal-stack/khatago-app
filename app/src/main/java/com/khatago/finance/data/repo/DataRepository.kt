package com.khatago.finance.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.khatago.finance.core.time.AppDates
import com.khatago.finance.core.time.Frequency
import com.khatago.finance.core.time.InstallmentSchedule
import com.khatago.finance.data.db.KhataGoDatabase
import com.khatago.finance.data.db.entity.BorrowingEntity
import com.khatago.finance.data.db.entity.EmiPurchaseEntity
import com.khatago.finance.data.db.entity.ExpenseEntity
import com.khatago.finance.data.db.entity.IncomeEntity
import com.khatago.finance.data.db.entity.InstallmentEntity
import com.khatago.finance.data.db.entity.LendingEntity
import com.khatago.finance.data.db.entity.LoanEntity
import com.khatago.finance.data.db.entity.PaymentEntity
import com.khatago.finance.data.db.entity.PersonEntity
import com.khatago.finance.data.db.entity.ShopCreditEntity
import com.khatago.finance.data.db.entity.ShopEntity
import java.time.LocalDate

/**
 * Data management: the two operations that touch everything, and nothing else.
 *
 * Kept separate from the module repositories because both need cross-module knowledge and both are
 * places where a careless implementation silently destroys records.
 */
class DataRepository(
    private val context: Context,
    private val database: KhataGoDatabase,
    private val attachmentRepository: AttachmentRepository,
) {

    /** Aggregate of what "Delete all data" will remove, so the dialog can be specific. */
    suspend fun deletionSummary(): DeletionSummary {
        val catalog = database.catalogDao()
        return DeletionSummary(
            shops = database.shopDao().count(),
            credits = database.creditDao().count(),
            loans = database.loanDao().count(),
            emis = database.emiDao().count(),
            payments = database.paymentDao().findAll().size,
            incomes = database.transactionDao().incomeCount(),
            expenses = database.transactionDao().expenseCount(),
            people = database.personDao().findAllPeople().size,
            attachmentFiles = attachmentRepository.fileCount(),
            categories = catalog.findAllCategories().size,
            reminders = catalog.findAllReminders().size,
        )
    }

    /**
     * Removes every row and every stored image, then restores only the reference lists (categories,
     * payment methods) so the app is immediately usable rather than empty in a broken way.
     *
     * The onboarding flag is deliberately reset: a user who wipes their ledger and restarts KhataGo
     * should be offered the setup flow again, not dropped into an empty dashboard with a name they
     * no longer have.
     */
    suspend fun deleteAllData(): DeleteAllResult = database.withTransaction {
        val summary = deletionSummary()
        database.paymentDao().deleteAll()
        database.installmentDao().deleteAll()
        database.creditDao().deleteAll()
        database.personDao().deleteAllBorrowings()
        database.personDao().deleteAllLendings()
        database.personDao().deleteAllPeople()
        database.loanDao().deleteAll()
        database.emiDao().deleteAll()
        database.transactionDao().deleteAllIncomes()
        database.transactionDao().deleteAllExpenses()
        database.catalogDao().deleteAllAttachments()
        database.catalogDao().deleteAllReminders()
        database.catalogDao().clearReminderLog()
        database.catalogDao().clearProfile()
        database.catalogDao().clearSettings()
        val filesRemoved = attachmentRepository.deleteAllFiles()
        DeleteAllResult(summary = summary, attachmentFilesRemoved = filesRemoved)
    }

    /**
     * Loads the demo ledger. Only ever called from an explicit Settings action, and it refuses to run
     * if the user already has records — mixing demo numbers into real data is the one way to make a
     * finance app actively dangerous.
     */
    suspend fun loadSampleData(todayEpochDay: Long = AppDates.today()): SampleResult {
        if (!isEmpty()) return SampleResult.NotEmpty
        val graph = SampleData.build(todayEpochDay)
        database.withTransaction {
            val shopIdByName = graph.shops.associate { shop ->
                shop.name to database.shopDao().insert(shop)
            }
            graph.credits.forEach { (shopName, credit, payments) ->
                val creditId = database.creditDao().insert(credit.copy(shopId = shopIdByName.getValue(shopName)))
                payments.forEach { payment ->
                    database.paymentDao().insert(
                        payment.copy(payableType = "shop_credit", payableId = creditId),
                    )
                }
            }
            val personIdByName = graph.people.associate { person ->
                person.name to database.personDao().insertPerson(person)
            }
            graph.borrowings.forEach { (personName, borrowing, payments) ->
                val id = database.personDao().insertBorrowing(borrowing.copy(personId = personIdByName.getValue(personName)))
                payments.forEach { database.paymentDao().insert(it.copy(payableType = "borrowing", payableId = id)) }
            }
            graph.lendings.forEach { (personName, lending, payments) ->
                val id = database.personDao().insertLending(lending.copy(personId = personIdByName.getValue(personName)))
                payments.forEach { database.paymentDao().insert(it.copy(payableType = "lending", payableId = id)) }
            }
            graph.loans.forEach { (loan, installments, payments) ->
                val id = database.loanDao().insert(loan.copy(id = 0))
                if (installments.isNotEmpty()) {
                    val lines = installments.map { it.copy(ownerId = id) }
                    database.loanDao().insertInstallments(lines)
                    // Attach each scheduled payment to the line it belongs to, by number.
                    payments.forEach { payment ->
                        val line = lines.firstOrNull { it.number == payment.reference?.toIntOrNull() }
                        database.paymentDao().insert(
                            payment.copy(
                                payableType = "loan",
                                payableId = id,
                                installmentId = line?.id,
                                reference = line?.let { "Installment ${it.number}" },
                            ),
                        )
                    }
                } else {
                    payments.forEach { database.paymentDao().insert(it.copy(payableType = "loan", payableId = id)) }
                }
            }
            graph.emis.forEach { (emi, installments, payments) ->
                val id = database.emiDao().insert(emi.copy(id = 0))
                if (installments.isNotEmpty()) {
                    val lines = installments.map { it.copy(ownerId = id) }
                    database.emiDao().insertInstallments(lines)
                    payments.forEach { payment ->
                        val line = lines.firstOrNull { it.number == payment.reference?.toIntOrNull() }
                        database.paymentDao().insert(
                            payment.copy(
                                payableType = "emi",
                                payableId = id,
                                installmentId = line?.id,
                                reference = line?.let { "EMI ${it.number}" },
                            ),
                        )
                    }
                } else {
                    payments.forEach { database.paymentDao().insert(it.copy(payableType = "emi", payableId = id)) }
                }
            }
            graph.incomes.forEach { database.transactionDao().insertIncome(it.copy(id = 0)) }
            graph.expenses.forEach { database.transactionDao().insertExpense(it.copy(id = 0)) }
            // Recorded in the same transaction: "sample data is loaded" is what lets the UI offer a
            // clean-up action, and a flag set outside the transaction could survive a failed load.
            database.catalogDao().putSetting(
                AppSettingEntity(
                    key = AppSettingEntity.SAMPLE_DATA_LOADED,
                    value = "1",
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        return SampleResult.Loaded(graph.recordCount)
    }

    suspend fun isEmpty(): Boolean =
        database.shopDao().count() == 0 &&
            database.creditDao().count() == 0 &&
            database.loanDao().count() == 0 &&
            database.emiDao().count() == 0 &&
            database.transactionDao().incomeCount() == 0 &&
            database.transactionDao().expenseCount() == 0 &&
            database.personDao().findAllPeople().isEmpty()
}

data class DeletionSummary(
    val shops: Int,
    val credits: Int,
    val loans: Int,
    val emis: Int,
    val payments: Int,
    val incomes: Int,
    val expenses: Int,
    val people: Int,
    val attachmentFiles: Int,
    val categories: Int,
    val reminders: Int,
) {
    val totalRecords: Int
        get() = shops + credits + loans + emis + payments + incomes + expenses + people + reminders

    /** Written the way you would say it to a person, because this is the scariest screen in the app. */
    fun describe(): String = buildString {
        append("This removes $totalRecords records from this device")
        if (attachmentFiles > 0) append(" and $attachmentFiles saved image(s)")
        append(", including $payments payment(s) and $credits shop credit(s).")
    }
}

data class DeleteAllResult(val summary: DeletionSummary, val attachmentFilesRemoved: Int)

sealed interface SampleResult {
    data class Loaded(val recordCount: Int) : SampleResult
    data object NotEmpty : SampleResult
}

/**
 * The demo ledger, used by Settings → Data management → Load sample data.
 *
 * Its shape is not decorative: it reproduces the acceptance scenario used by
 * `KhataGoRealWorldScenarioTest`, so the numbers that test the app are the same numbers a reviewer
 * can load on a device and check against the dashboard. Three shops (৳8,000/৳3,000, ৳4,500/৳4,500,
 * ৳12,000/৳2,000), two loans, two EMI plans, three borrowings, two lendings, and several months of
 * income and expense — with due dates spread across overdue / today / soon so the payment centre has
 * something true to show.
 *
 * Production first launch never touches this. A fresh install is empty.
 */
object SampleData {

    data class Graph(
        val shops: List<ShopEntity>,
        val credits: List<Triple<String, ShopCreditEntity, List<PaymentEntity>>>,
        val people: List<PersonEntity>,
        val borrowings: List<Triple<String, BorrowingEntity, List<PaymentEntity>>>,
        val lendings: List<Triple<String, LendingEntity, List<PaymentEntity>>>,
        val loans: List<Triple<LoanEntity, List<InstallmentEntity>, List<PaymentEntity>>>,
        val emis: List<Triple<EmiPurchaseEntity, List<InstallmentEntity>, List<PaymentEntity>>>,
        val incomes: List<IncomeEntity>,
        val expenses: List<ExpenseEntity>,
    ) {
        val recordCount: Int
            get() = shops.size + credits.size + people.size + borrowings.size + lendings.size +
                loans.size + emis.size + incomes.size + expenses.size +
                credits.sumOf { it.third.size } + borrowings.sumOf { it.third.size } +
                lendings.sumOf { it.third.size } + loans.sumOf { it.third.size } +
                emis.sumOf { it.third.size }
    }

    /**
     * Builds the demo ledger relative to [today] so the due-date engine always has something real to
     * classify (overdue, due today, due soon, upcoming). Every id is a placeholder: the loader
     * reassigns ids after insert and wires the relations up, exactly as it does for a restored file.
     */
    fun build(today: Long): Graph {
        val now = System.currentTimeMillis()
        fun day(offset: Long): Long = today + offset

        val shops = listOf(
            ShopEntity(
                id = 1, name = "Rahman Store", ownerName = "Abdur Rahman", phone = "01711-000001",
                category = "Grocery", notes = "Rice and lentils on the khata.", createdAt = now, updatedAt = now,
            ),
            ShopEntity(
                id = 2, name = "Karim Bhaban", ownerName = "Karim Uddin", phone = "01811-000002",
                category = "Grocery", createdAt = now, updatedAt = now,
            ),
            ShopEntity(
                id = 3, name = "New Fashion", ownerName = "Jashim Ahmed", phone = "01911-000003",
                category = "Clothing", createdAt = now, updatedAt = now,
            ),
        )

        // Shop A: 8,000 of credit, 3,000 paid, 5,000 remaining (three records, one paid down).
        val credits = listOf(
            Triple(
                "Rahman Store",
                ShopCreditEntity(
                    id = 1, shopId = 1, productName = "Rice (2 bags)", quantity = 2, unitPriceMinor = 120_000L,
                    totalAmountMinor = 240_000L, purchaseDateEpochDay = day(-40), dueDateEpochDay = day(-5),
                    createdAt = now, updatedAt = now,
                ),
                listOf(payable(1, 100_000L, day(-15), null)),
            ),
            Triple(
                "Rahman Store",
                ShopCreditEntity(
                    id = 2, shopId = 1, productName = "Soybean oil (5 L)", quantity = 5, unitPriceMinor = 64_000L,
                    totalAmountMinor = 320_000L, purchaseDateEpochDay = day(-20), dueDateEpochDay = day(3),
                    createdAt = now, updatedAt = now,
                ),
                listOf(payable(2, 100_000L, day(-2), "Half paid early")),
            ),
            Triple(
                "Rahman Store",
                ShopCreditEntity(
                    id = 3, shopId = 1, productName = "Lentils (3 kg)", quantity = 3, unitPriceMinor = 80_000L,
                    totalAmountMinor = 240_000L, purchaseDateEpochDay = day(-8), dueDateEpochDay = day(12),
                    createdAt = now, updatedAt = now,
                ),
                listOf(payable(3, 100_000L, day(-1), null)),
            ),
            // Shop B: 4,500 of credit, fully paid.
            Triple(
                "Karim Bhaban",
                ShopCreditEntity(
                    id = 4, shopId = 2, productName = "Monthly grocery", quantity = 1, unitPriceMinor = 450_000L,
                    totalAmountMinor = 450_000L, purchaseDateEpochDay = day(-35), dueDateEpochDay = day(-10),
                    createdAt = now, updatedAt = now,
                ),
                listOf(payable(4, 450_000L, day(-12), "Settled in full")),
            ),
            // Shop C: 12,000 of credit, 2,000 paid, 10,000 remaining.
            Triple(
                "New Fashion",
                ShopCreditEntity(
                    id = 5, shopId = 3, productName = "Winter clothes", quantity = 4, unitPriceMinor = 300_000L,
                    totalAmountMinor = 1_200_000L, purchaseDateEpochDay = day(-25), dueDateEpochDay = day(0),
                    createdAt = now, updatedAt = now,
                ),
                listOf(payable(5, 200_000L, day(-3), null)),
            ),
        )

        val people = listOf(
            PersonEntity(id = 1, name = "Shakib", relationship = "Friend", phone = "01611-000004", createdAt = now, updatedAt = now),
            PersonEntity(id = 2, name = "Rafiq", relationship = "Colleague", createdAt = now, updatedAt = now),
            PersonEntity(id = 3, name = "Nadia", relationship = "Relative", createdAt = now, updatedAt = now),
            PersonEntity(id = 4, name = "Karim Chacha", relationship = "Neighbor", createdAt = now, updatedAt = now),
            PersonEntity(id = 5, name = "Tanvir", relationship = "Friend", createdAt = now, updatedAt = now),
        )

        val borrowings = listOf(
            Triple(
                "Shakib",
                BorrowingEntity(
                    id = 1, personId = 1, amountMinor = 500_000L, borrowDateEpochDay = day(-60),
                    dueDateEpochDay = day(-3), notes = "For the fridge repair", createdAt = now, updatedAt = now,
                ),
                listOf(payable(1, 200_000L, day(-10), "Partly returned")),
            ),
            Triple(
                "Rafiq",
                BorrowingEntity(
                    id = 2, personId = 2, amountMinor = 1_500_000L, borrowDateEpochDay = day(-30),
                    dueDateEpochDay = day(15), createdAt = now, updatedAt = now,
                ),
                listOf(payable(2, 500_000L, day(-5), null)),
            ),
            Triple(
                "Nadia",
                BorrowingEntity(
                    id = 3, personId = 3, amountMinor = 800_000L, borrowDateEpochDay = day(-12),
                    dueDateEpochDay = day(40), createdAt = now, updatedAt = now,
                ),
                emptyList(),
            ),
        )

        val lendings = listOf(
            Triple(
                "Karim Chacha",
                LendingEntity(
                    id = 1, personId = 4, amountMinor = 2_000_000L, lendDateEpochDay = day(-45),
                    dueDateEpochDay = day(6), notes = "For his son's admission", createdAt = now, updatedAt = now,
                ),
                listOf(payable(1, 1_000_000L, day(-6), "Returned half")),
            ),
            Triple(
                "Tanvir",
                LendingEntity(
                    id = 2, personId = 5, amountMinor = 700_000L, lendDateEpochDay = day(-9),
                    dueDateEpochDay = day(21), createdAt = now, updatedAt = now,
                ),
                emptyList(),
            ),
        )

        val loanOneFirstDue = day(-90)
        val loanTwoFirstDue = day(10)
        val emiOneFirstDue = day(-120)
        val emiTwoFirstDue = day(-45)

        val loans = listOf(
            Triple(
                LoanEntity(
                    id = 1, institution = "Padma Bank", loanName = "Agriculture loan",
                    principalMinor = 10_000_000L, disbursementDateEpochDay = day(-200), interestRatePercent = 12.0,
                    totalPayableMinor = 12_000_000L, downPaymentMinor = 0L, installmentAmountMinor = 1_000_000L,
                    installmentCount = 12, installmentFrequency = Frequency.Monthly.name, customIntervalDays = 30,
                    startDateEpochDay = day(-200), firstDueDateEpochDay = loanOneFirstDue,
                    contactName = "Branch office", contactPhone = "02-9881122",
                    notes = "Flat instalments, no early-settlement discount.",
                    createdAt = now, updatedAt = now,
                ),
                schedule("loan", 1, loanOneFirstDue, 12, 1_000_000L, 12_000_000L, now),
                // Three installments paid, recorded against lines 1-3 so the schedule stays honest.
                (1..3).map { payable(1, 1_000_000L, day(-90 + (it - 1) * 30), it.toString()) },
            ),
            Triple(
                LoanEntity(
                    id = 2, institution = "Grameen Bank", loanName = "Income-generating loan",
                    principalMinor = 4_000_000L, disbursementDateEpochDay = day(-60), interestRatePercent = 8.0,
                    totalPayableMinor = 4_800_000L, downPaymentMinor = 0L, installmentAmountMinor = 200_000L,
                    installmentCount = 24, installmentFrequency = Frequency.Monthly.name, customIntervalDays = 30,
                    startDateEpochDay = day(-60), firstDueDateEpochDay = loanTwoFirstDue,
                    createdAt = now, updatedAt = now,
                ),
                schedule("loan", 2, loanTwoFirstDue, 24, 200_000L, 4_800_000L, now),
                emptyList(),
            ),
        )

        val emis = listOf(
            Triple(
                EmiPurchaseEntity(
                    id = 1, productName = "Walton 18-inch refrigerator", merchant = "Rahman Store",
                    purchaseDateEpochDay = day(-240), cashPriceMinor = 3_200_000L, totalPayableMinor = 3_600_000L,
                    downPaymentMinor = 400_000L, emiAmountMinor = 400_000L, installmentCount = 8,
                    installmentFrequency = Frequency.Monthly.name, customIntervalDays = 30,
                    firstDueDateEpochDay = emiOneFirstDue,
                    notes = "Down payment counted as the first payment.", createdAt = now, updatedAt = now,
                ),
                schedule("emi", 1, emiOneFirstDue, 8, 400_000L, 3_200_000L, now),
                // Down payment plus all 8 EMIs -> this plan is fully paid (the "EMI fully paid" case).
                (1..8).map { payable(1, 400_000L, emiOneFirstDue + (it - 1) * 30L, it.toString()) },
            ),
            Triple(
                EmiPurchaseEntity(
                    id = 2, productName = "Smartphone", merchant = "Smart Electronics",
                    purchaseDateEpochDay = day(-60), cashPriceMinor = 4_000_000L, totalPayableMinor = 4_500_000L,
                    downPaymentMinor = 900_000L, emiAmountMinor = 300_000L, installmentCount = 12,
                    installmentFrequency = Frequency.Monthly.name, customIntervalDays = 30,
                    firstDueDateEpochDay = emiTwoFirstDue, createdAt = now, updatedAt = now,
                ),
                schedule("emi", 2, emiTwoFirstDue, 12, 300_000L, 3_600_000L, now),
                (1..4).map { payable(2, 300_000L, emiTwoFirstDue + (it - 1) * 30L, it.toString()) },
            ),
        )

        val incomes = (0L..3L).map { back ->
            val monthDate = java.time.LocalDate.ofEpochDay(today).minusMonths(back)
            IncomeEntity(
                id = 0, amountMinor = 3_500_000L, source = "Monthly salary", categoryName = "Salary",
                transactionDateEpochDay = monthDate.withDayOfMonth(minOf(5, monthDate.lengthOfMonth())).toEpochDay(),
                methodName = "Bank transfer", note = if (back == 0L) "Current month" else null,
                createdAt = now, updatedAt = now,
            )
        } + IncomeEntity(
            id = 0, amountMinor = 1_200_000L, source = "Website project", categoryName = "Freelance",
            transactionDateEpochDay = day(-22), methodName = "Mobile banking", createdAt = now, updatedAt = now,
        )

        val expenseTemplates = listOf(
            Triple("Food", "Bazar", 450_000L),
            Triple("Transport", "CNG and bus", 200_000L),
            Triple("Bills", "Electricity and gas", 260_000L),
            Triple("Health", "Pharmacy", 120_000L),
            Triple("Education", "Coaching fee", 300_000L),
        )
        val expenses = (0L..3L).flatMap { back ->
            val monthDate = java.time.LocalDate.ofEpochDay(today).minusMonths(back)
            expenseTemplates.mapIndexed { index, (category, merchant, amount) ->
                ExpenseEntity(
                    id = 0, amountMinor = amount, categoryName = category, merchant = merchant,
                    transactionDateEpochDay = monthDate.withDayOfMonth(minOf(3 + index * 4, monthDate.lengthOfMonth())).toEpochDay(),
                    methodName = "Cash", createdAt = now, updatedAt = now,
                )
            }
        }

        return Graph(
            shops = shops,
            credits = credits,
            people = people,
            borrowings = borrowings,
            lendings = lendings,
            loans = loans,
            emis = emis,
            incomes = incomes,
            expenses = expenses,
        )
    }

    /**
     * A payment in the sample. `payableIndex` is stored as the obligation's placeholder id and
     * rewritten by the loader once the real id is known; for loans/EMIs it carries the installment
     * number instead.
     */
    private fun payable(payableIndex: Int, amountMinor: Long, paidDay: Long, reference: String?): PaymentEntity =
        PaymentEntity(
            id = 0,
            payableType = "",
            payableId = payableIndex.toLong(),
            amountMinor = amountMinor,
            paidDateEpochDay = paidDay,
            methodName = "Cash",
            reference = reference,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )

    private fun schedule(
        ownerType: String,
        ownerId: Long,
        firstDue: Long,
        count: Int,
        installmentMinor: Long,
        totalPayable: Long,
        now: Long,
    ): List<InstallmentEntity> = InstallmentSchedule.generate(
        firstDueDateEpochDay = firstDue,
        count = count,
        frequency = Frequency.Monthly,
        customIntervalDays = 30,
        installmentMinor = installmentMinor,
        totalPayableMinor = totalPayable,
    ).map {
        InstallmentEntity(
            ownerType = ownerType,
            ownerId = ownerId,
            number = it.number,
            dueDateEpochDay = it.dueDateEpochDay,
            scheduledAmountMinor = it.amountMinor,
            createdAt = now,
        )
    }

    /**
     * `referenceLabel` is a loader-only key, not data: it lets [DataRepository] find the freshly
     * assigned payable id for this payment without assuming the sample's own ids survive insert.
     */
    private fun payment(
        payableType: String,
        payableId: Long,
        label: String?,
        amountMinor: Long,
        paidDay: Long,
        now: Long,
    ): PaymentEntity = PaymentEntity(
        payableType = payableType,
        payableId = payableId,
        amountMinor = amountMinor,
        paidDateEpochDay = paidDay,
        methodName = "Cash",
        note = label,
        createdAt = now,
        updatedAt = now,
    ).let { entity -> entity.copy(reference = label ?: "$payableType#$payableId") }
}
