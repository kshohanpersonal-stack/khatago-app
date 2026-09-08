package com.khatago.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.khatago.finance.data.db.entity.AppSettingEntity
import com.khatago.finance.data.db.entity.AttachmentEntity
import com.khatago.finance.data.db.entity.CategoryEntity
import com.khatago.finance.data.db.entity.PaymentMethodEntity
import com.khatago.finance.data.db.entity.ProfileEntity
import com.khatago.finance.data.db.entity.ReminderEntity
import com.khatago.finance.data.db.entity.ReminderLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * Profile, categories, payment methods, attachments, reminders and settings — the app's catalogue
 * tables. Grouped in one DAO because they share one trait: small, reference data that changes rarely
 * and is read by many screens.
 */
@Dao
interface CatalogDao {

    // --- profile --------------------------------------------------------------

    @Query("SELECT * FROM profile LIMIT 1")
    fun observeProfile(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profile LIMIT 1")
    suspend fun findProfile(): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: ProfileEntity): Long

    @Query("DELETE FROM profile")
    suspend fun clearProfile()

    // --- settings ------------------------------------------------------------

    @Query("SELECT * FROM app_settings")
    fun observeSettings(): Flow<List<AppSettingEntity>>

    @Query("SELECT * FROM app_settings")
    suspend fun findAllSettings(): List<AppSettingEntity>

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    suspend fun settingValue(key: String): String?

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    fun observeSetting(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSetting(setting: AppSettingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAllSettings(settings: List<AppSettingEntity>)

    @Query("DELETE FROM app_settings")
    suspend fun clearSettings()

    // --- categories ----------------------------------------------------------

    @Query("SELECT * FROM categories WHERE kind = :kind AND enabled = 1 ORDER BY orderIndex ASC, name ASC")
    fun observeCategories(kind: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY orderIndex ASC, name ASC")
    suspend fun findAllCategories(kind: String): List<CategoryEntity>

    @Query("SELECT * FROM categories ORDER BY kind ASC, orderIndex ASC, name ASC")
    suspend fun findAllCategories(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllCategories(categories: List<CategoryEntity>): List<Long>

    @Update
    suspend fun updateCategory(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: Long)

    @Query("DELETE FROM categories")
    suspend fun deleteAllCategories()

    @Query("SELECT COUNT(*) FROM categories WHERE kind = :kind")
    suspend fun categoryCount(kind: String): Int

    // --- payment methods ----------------------------------------------------

    @Query("SELECT * FROM payment_methods WHERE enabled = 1 ORDER BY orderIndex ASC, name ASC")
    fun observePaymentMethods(): Flow<List<PaymentMethodEntity>>

    @Query("SELECT * FROM payment_methods ORDER BY orderIndex ASC, name ASC")
    suspend fun findAllPaymentMethods(): List<PaymentMethodEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPaymentMethods(methods: List<PaymentMethodEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllPaymentMethods(methods: List<PaymentMethodEntity>): List<Long>

    @Update
    suspend fun updatePaymentMethod(method: PaymentMethodEntity)

    @Query("DELETE FROM payment_methods WHERE id = :id")
    suspend fun deletePaymentMethod(id: Long)

    @Query("DELETE FROM payment_methods")
    suspend fun deleteAllPaymentMethods()

    // --- attachments --------------------------------------------------------

    @Query("SELECT * FROM attachments WHERE targetType = :targetType AND targetId = :targetId ORDER BY id ASC")
    fun observeAttachments(targetType: String, targetId: Long): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE targetType = :targetType AND targetId = :targetId ORDER BY id ASC")
    suspend fun findAttachments(targetType: String, targetId: Long): List<AttachmentEntity>

    @Query("SELECT * FROM attachments")
    suspend fun findAllAttachments(): List<AttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachment(attachment: AttachmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllAttachments(attachments: List<AttachmentEntity>): List<Long>

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun deleteAttachment(id: Long)

    @Query("DELETE FROM attachments WHERE targetType = :targetType AND targetId = :targetId")
    suspend fun deleteAttachmentsFor(targetType: String, targetId: Long)

    @Query("DELETE FROM attachments")
    suspend fun deleteAllAttachments()

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM attachments")
    fun observeAttachmentBytes(): Flow<Long>

    // --- reminders ------------------------------------------------------------

    @Query("SELECT * FROM reminders WHERE enabled = 1 ORDER BY dueDateEpochDay ASC, dueTimeMinute ASC")
    fun observeReminders(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders ORDER BY dueDateEpochDay ASC, dueTimeMinute ASC")
    suspend fun findAllReminders(): List<ReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: ReminderEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllReminders(reminders: List<ReminderEntity>): List<Long>

    @Update
    suspend fun updateReminder(reminder: ReminderEntity)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminder(id: Long)

    @Query("DELETE FROM reminders")
    suspend fun deleteAllReminders()

    /** Reminders due today (by minute of day) that have not fired yet today. */
    @Query(
        """
        SELECT * FROM reminders
        WHERE enabled = 1 AND dueDateEpochDay = :today AND lastFiredEpochDay IS NOT :today
          AND dueTimeMinute <= :minuteOfDay
        """,
    )
    suspend fun findDueToday(today: Long, minuteOfDay: Int): List<ReminderEntity>

    @Query("UPDATE reminders SET lastFiredEpochDay = :epochDay WHERE id = :id")
    suspend fun markFired(id: Long, epochDay: Long)

    // --- notification dedupe -------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReminderLog(entry: ReminderLogEntity): Long

    @Query("SELECT dedupeKey FROM reminder_log WHERE dueDateEpochDay = :epochDay")
    suspend fun dedupeKeysFor(epochDay: Long): List<String>

    @Query("DELETE FROM reminder_log")
    suspend fun clearReminderLog()

    @Query("DELETE FROM reminder_log WHERE dueDateEpochDay < :olderThanEpochDay")
    suspend fun pruneReminderLog(olderThanEpochDay: Long)
}
