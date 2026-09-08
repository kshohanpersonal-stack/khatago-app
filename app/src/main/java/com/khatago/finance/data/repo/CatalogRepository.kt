package com.khatago.finance.data.repo

import androidx.room.withTransaction
import com.khatago.finance.data.db.KhataGoDatabase
import com.khatago.finance.data.db.entity.AppSettingEntity
import com.khatago.finance.data.db.entity.CategoryEntity
import com.khatago.finance.data.db.entity.PaymentMethodEntity
import com.khatago.finance.data.db.entity.ProfileEntity
import com.khatago.finance.data.db.entity.ReminderEntity
import kotlinx.coroutines.flow.Flow

/**
 * Profile, preferences, categories, payment methods and manual reminders.
 *
 * Settings are stored as typed rows in Room rather than in a `SharedPreferences` blob so they are
 * covered by backup/restore: a user who restores their ledger should not have to rebuild their
 * category list by hand. Sensitive values (the app-lock PIN) are the one exception and live in a
 * separate hashed store (see `SecurityRepository`).
 */
class CatalogRepository(private val database: KhataGoDatabase) {

    fun observeProfile(): Flow<ProfileEntity?> = database.catalogDao().observeProfile()

    suspend fun findProfile(): ProfileEntity? = database.catalogDao().findProfile()

    suspend fun saveProfile(profile: ProfileEntity): SaveResult = database.withTransaction {
        val name = profile.displayName?.trim().orEmpty()
        if (name.length > 40) {
            return@withTransaction SaveResult.Invalid("Keep your name under 40 characters.")
        }
        if (profile.currencyCode.isBlank()) {
            return@withTransaction SaveResult.Invalid("Pick a currency.")
        }
        // There is exactly one profile row, and it is keyed to id = 1. Callers routinely build a fresh
        // entity (id = 0), so the id is normalised here: with `@Insert(REPLACE)` an id of 0 would insert
        // a *second* profile row while leaving the first one in place, and "SELECT * FROM profile
        // LIMIT 1" would then show whichever row SQLite happened to read first.
        val existing = database.catalogDao().findProfile()
        database.catalogDao().upsertProfile(
            profile.copy(
                id = 1,
                displayName = name.takeIf { it.isNotEmpty() },
                createdAt = if (profile.createdAt == 0L) {
                    existing?.createdAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
                } else {
                    profile.createdAt
                },
                updatedAt = System.currentTimeMillis(),
            ),
        )
        SaveResult.Saved
    }

    /** Marks onboarding done. Separate from [saveProfile] because it must succeed even if the user
     entered nothing — "I'll tell the app my name later" is a valid first-run choice. */
    suspend fun completeOnboarding() = database.withTransaction {
        val existing = database.catalogDao().findProfile()
            ?: ProfileEntity(id = 1, createdAt = System.currentTimeMillis())
        database.catalogDao().upsertProfile(existing.copy(onboardingComplete = true, updatedAt = System.currentTimeMillis()))
    }

    // --- settings ------------------------------------------------------------

    fun observeSetting(key: String): Flow<String?> = database.catalogDao().observeSetting(key)

    suspend fun setting(key: String): String? = database.catalogDao().settingValue(key)

    /**
     * Lenient on purpose: "1"/"0" and "true"/"false" are both written into this table (the reminder
     * scheduler uses "1"/"0" so the value survives an sqlite query by hand), and a reader that only
     * accepted one spelling would silently flip a user's switch the next time they opened the app.
     */
    suspend fun boolSetting(key: String, default: Boolean): Boolean =
        database.catalogDao().settingValue(key)?.let { raw ->
            when {
                raw == "1" || raw.equals("true", ignoreCase = true) -> true
                raw == "0" || raw.equals("false", ignoreCase = true) -> false
                else -> default
            }
        } ?: default

    suspend fun intSetting(key: String, default: Int): Int =
        database.catalogDao().settingValue(key)?.toIntOrNull() ?: default

    suspend fun putSetting(key: String, value: String) = database.withTransaction {
        database.catalogDao().putSetting(AppSettingEntity(key = key, value = value, updatedAt = System.currentTimeMillis()))
    }

    suspend fun putBool(key: String, value: Boolean) = putSetting(key, value.toString())

    suspend fun putInt(key: String, value: Int) = putSetting(key, value.toString())

    fun observeSettings(): Flow<List<AppSettingEntity>> = database.catalogDao().observeSettings()

    // --- categories ----------------------------------------------------------

    fun observeCategories(kind: String): Flow<List<CategoryEntity>> =
        database.catalogDao().observeCategories(kind)

    suspend fun findAllCategories(kind: String): List<CategoryEntity> =
        database.catalogDao().findAllCategories(kind)

    /** Adds a custom category. A built-in name cannot be re-added, and duplicates are rejected. */
    suspend fun addCategory(kind: String, name: String): SaveResult = database.withTransaction {
        val trimmed = name.trim()
        when {
            trimmed.isEmpty() -> SaveResult.Invalid("Enter a category name.")
            trimmed.length > 30 -> SaveResult.Invalid("Keep the name under 30 characters.")
            database.catalogDao().findAllCategories(kind).any { it.name.equals(trimmed, ignoreCase = true) } ->
                SaveResult.Conflict("You already have a category called \"$trimmed\".")
            else -> {
                val order = database.catalogDao().categoryCount(kind)
                database.catalogDao().insertCategories(
                    listOf(
                        CategoryEntity(
                            kind = kind,
                            name = trimmed,
                            orderIndex = order,
                            builtIn = false,
                            enabled = true,
                        ),
                    ),
                )
                SaveResult.Saved
            }
        }
    }

    suspend fun setCategoryEnabled(id: Long, enabled: Boolean) = database.withTransaction {
        val existing = database.catalogDao().findAllCategories().firstOrNull { it.id == id } ?: return@withTransaction
        database.catalogDao().updateCategory(existing.copy(enabled = enabled))
    }

    suspend fun deleteCategory(id: Long): SaveResult = database.withTransaction {
        val existing = database.catalogDao().findAllCategories().firstOrNull { it.id == id }
            ?: return@withTransaction SaveResult.Invalid("That category no longer exists.")
        if (existing.builtIn) {
            return@withTransaction SaveResult.Invalid(
                "Built-in categories cannot be deleted, but you can switch them off.",
            )
        }
        // Deleting a category never deletes the records that used it: their `categoryName` is a
        // plain string, so the ledger stays intact and the record falls back to "Other"-style
        // grouping in analytics instead of vanishing from the user's history.
        database.catalogDao().deleteCategory(id)
        SaveResult.Saved
    }

    // --- payment methods ----------------------------------------------------

    fun observePaymentMethods(): Flow<List<PaymentMethodEntity>> =
        database.catalogDao().observePaymentMethods()

    suspend fun findEnabledPaymentMethods(): List<PaymentMethodEntity> =
        database.catalogDao().findAllPaymentMethods().filter { it.enabled }

    suspend fun addPaymentMethod(name: String): SaveResult = database.withTransaction {
        val trimmed = name.trim()
        when {
            trimmed.isEmpty() -> SaveResult.Invalid("Enter a method name.")
            trimmed.length > 30 -> SaveResult.Invalid("Keep the name under 30 characters.")
            database.catalogDao().findAllPaymentMethods().any { it.name.equals(trimmed, ignoreCase = true) } ->
                SaveResult.Conflict("You already have a method called \"$trimmed\".")
            else -> {
                database.catalogDao().insertPaymentMethods(
                    listOf(
                        PaymentMethodEntity(
                            name = trimmed,
                            orderIndex = database.catalogDao().findAllPaymentMethods().size,
                            builtIn = false,
                            enabled = true,
                        ),
                    ),
                )
                SaveResult.Saved
            }
        }
    }

    suspend fun setPaymentMethodEnabled(id: Long, enabled: Boolean) = database.withTransaction {
        val existing = database.catalogDao().findAllPaymentMethods().firstOrNull { it.id == id } ?: return@withTransaction
        database.catalogDao().updatePaymentMethod(existing.copy(enabled = enabled))
    }

    suspend fun deletePaymentMethod(id: Long): SaveResult = database.withTransaction {
        val existing = database.catalogDao().findAllPaymentMethods().firstOrNull { it.id == id }
            ?: return@withTransaction SaveResult.Invalid("That method no longer exists.")
        if (existing.builtIn) {
            return@withTransaction SaveResult.Invalid(
                "Cash and the standard methods are kept so old records still show how they were paid.",
            )
        }
        // Same rule as categories: the method name on a payment is a plain string, so removing the
        // option never rewrites or deletes the payment itself.
        database.catalogDao().deletePaymentMethod(id)
        SaveResult.Saved
    }

    // --- reminders -----------------------------------------------------------

    fun observeReminders(): Flow<List<ReminderEntity>> = database.catalogDao().observeReminders()

    suspend fun findAllReminders(): List<ReminderEntity> = database.catalogDao().findAllReminders()

    suspend fun saveReminder(reminder: ReminderEntity): SaveResult = database.withTransaction {
        if (reminder.title.isBlank()) return@withTransaction SaveResult.Invalid("Enter what you want to be reminded about.")
        if (reminder.dueTimeMinute !in 0..(24 * 60 - 1)) {
            return@withTransaction SaveResult.Invalid("Pick a time between 00:00 and 23:59.")
        }
        database.catalogDao().insertReminder(
            reminder.copy(createdAt = reminder.createdAt.takeIf { it > 0L } ?: System.currentTimeMillis()),
        )
        SaveResult.Saved
    }

    suspend fun deleteReminder(id: Long) = database.withTransaction { database.catalogDao().deleteReminder(id) }

    suspend fun setReminderEnabled(id: Long, enabled: Boolean) = database.withTransaction {
        val existing = database.catalogDao().findAllReminders().firstOrNull { it.id == id } ?: return@withTransaction
        database.catalogDao().updateReminder(existing.copy(enabled = enabled))
    }

    suspend fun findDueReminders(todayEpochDay: Long, minuteOfDay: Int): List<ReminderEntity> =
        database.catalogDao().findDueToday(todayEpochDay, minuteOfDay)

    suspend fun markReminderFired(id: Long, epochDay: Long) =
        database.catalogDao().markFired(id, epochDay)

    suspend fun recordCounts(): String = database.statsDao().recordCounts()
}
