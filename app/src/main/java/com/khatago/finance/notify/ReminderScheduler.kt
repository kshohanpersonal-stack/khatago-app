package com.khatago.finance.notify

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.khatago.finance.AppContainer
import com.khatago.finance.core.time.AppDates
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Schedules the daily reminder pass with WorkManager.
 *
 * Why a *periodic* job plus an exact-ish initial delay, rather than `AlarmManager`:
 *  - KhataGo has no INTERNET permission and no boot receiver with `RECEIVE_BOOT_COMPLETED`; asking
 *    for either to wake a notification is disproportionate. WorkManager persists its schedule in the
 *    app's own storage and re-arms after reboot by itself, which gives "it just works again tomorrow"
 *    without a permission the Play listing would have to justify.
 *  - A daily ledger reminder is a *flexible* window by nature. `setPeriodic(24h)` is allowed to be
 *    batched by the OS; that is acceptable here and is exactly what saves battery. The initial delay
 *    aligns the first run to the user's chosen hour so the pattern settles on the right time.
 *
 * Idempotency is deliberate: `KEEP` means calling `sync()` on every app start (and after every
 * settings change) never resets the schedule and never produces two chains of work.
 */
class ReminderScheduler(
    context: Context,
    private val container: AppContainer,
) {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    suspend fun syncFromPreferences() {
        val db = container.database
        val settings = runCatching { db.catalogDao().findAllSettings() }.getOrDefault(emptyList())
            .associate { it.key to it.value }
        val enabled = settings[com.khatago.finance.data.db.entity.AppSettingEntity.NOTIFICATIONS_ENABLED]
            ?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: true
        if (!enabled) {
            cancel()
            return
        }
        schedule(hour = hourOf(settings))
    }

    /** Called from Settings when the switch or the hour changes. */
    suspend fun setEnabled(enabled: Boolean, hour: Int) {
        val settings = container.database.catalogDao()
        settings.putSetting(
            com.khatago.finance.data.db.entity.AppSettingEntity(
                key = com.khatago.finance.data.db.entity.AppSettingEntity.NOTIFICATIONS_ENABLED,
                value = if (enabled) "1" else "0",
                updatedAt = System.currentTimeMillis(),
            ),
        )
        settings.putSetting(
            com.khatago.finance.data.db.entity.AppSettingEntity(
                key = com.khatago.finance.data.db.entity.AppSettingEntity.REMINDER_HOUR,
                value = hour.coerceIn(0, 23).toString(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        if (enabled) schedule(hour) else cancel()
    }

    private fun schedule(hour: Int) {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayToNext(hour), TimeUnit.MINUTES)
            .addTag(WORK_TAG)
            // No constraints at all, on purpose: everything the worker reads is already on the
            // device, so any constraint (network, charging, battery) would only delay a reminder
            // that is worthless once the morning has passed. `Constraints.Builder` also has no
            // `setRequiresNetworkConnectivity` in WorkManager 2.9 — "not required" is the default.
            .setConstraints(androidx.work.Constraints.Builder().build())
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    /** Immediate manual run, used by Settings' "Send a test reminder" and by instrumented tests. */
    fun runNow() {
        workManager.cancelAllWorkByTag(WORK_TAG)
        workManager.enqueue(
            androidx.work.OneTimeWorkRequestBuilder<ReminderWorker>()
                .addTag(WORK_TAG)
                .build(),
        )
    }

    /** Minutes until the next occurrence of [hour]:00 local time (never negative, never > 24h). */
    private fun delayToNext(hour: Int): Long {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val target = now.toLocalDate().atTime(LocalTime.of(hour.coerceIn(0, 23), 5))
        val next = if (target.isAfter(now)) target else target.plusDays(1)
        return java.time.Duration.between(now, next).toMinutes().coerceAtLeast(1L)
    }

    private fun hourOf(settings: Map<String, String>): Int =
        settings[com.khatago.finance.data.db.entity.AppSettingEntity.REMINDER_HOUR]?.toIntOrNull()?.coerceIn(0, 23)
            ?: DEFAULT_HOUR

    companion object {
        const val WORK_NAME = "khatago-daily-reminders"
        const val WORK_TAG = "khatago-reminders"
        const val DEFAULT_HOUR = 9

        /** Human label reused by the worker copy and by the Settings screen. */
        fun hourLabel(hour: Int): String {
            val time = LocalTime.of(hour.coerceIn(0, 23), 5)
            return "around ${AppDates.clockTime(time)}"
        }
    }
}
