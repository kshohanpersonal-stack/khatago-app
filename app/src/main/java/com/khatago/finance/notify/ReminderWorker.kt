package com.khatago.finance.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.khatago.finance.KhataGoApplication
import com.khatago.finance.data.db.entity.AppSettingEntity
import com.khatago.finance.data.db.entity.ReminderLogEntity
import java.time.LocalDate
import java.time.ZoneId

/**
 * The daily reminder pass.
 *
 * What it does, and what it refuses to do:
 *  - Computes **today on the device** (never a server date — there is no server), then asks the
 *    unified due queue for rows due today/tomorrow and the overdue totals for the tail.
 *  - Posts **at most one notification per category per day**, deduplicated through `reminder_log`'s
 *    UNIQUE key. A re-run (retry, manual "notify now", a device that woke late) cannot double-post,
 *    because the log row is written first and the insert is `IGNORE`.
 *  - **Stays silent when there is nothing due.** A reminder app that writes "all clear" every day is
 *    an app the user disables in a week.
 *  - Respects the per-category toggles in Settings; the switches are real, not cosmetic.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    /**
     * Resolved lazily rather than injected: WorkManager instantiates this class reflectively, and a
     * custom `WorkerFactory` would require overriding the androidx.startup Initialiser in the
     * manifest. Reading the already-built [KhataGoApplication] container is the same object graph
     * without that moving part.
     */
    private fun container() = (applicationContext as KhataGoApplication).container

    override suspend fun doWork(): Result {
        val db = container().database
        val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        val settings = db.catalogDao().findAllSettings().associate { it.key to it.value }

        fun on(key: String, default: Boolean): Boolean =
            settings[key]?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: default

        val notificationsOn = on(AppSettingEntity.NOTIFICATIONS_ENABLED, true)
        if (!notificationsOn || !Notifications.hasPermission(applicationContext)) return Result.success()

        if (on(AppSettingEntity.REMIND_DUE_TODAY, true)) {
            val dueToday = db.dueDao().findBetween(today, today)
            if (dueToday.isNotEmpty()) {
                val key = "due-today:$today"
                if (claim(key, "due", 0, today)) {
                    Notifications.post(
                        applicationContext,
                        Notifications.ID_DUE_TODAY,
                        title = "${dueToday.size} payment${plural(dueToday.size)} due today",
                        text = "Open KhataGo to see what is due and record a payment.",
                        deepLink = "khatago://due/today",
                    )
                }
            }
        }

        if (on(AppSettingEntity.REMIND_DUE_TOMORROW, true)) {
            val tomorrow = today + 1
            val dueTomorrow = db.dueDao().findBetween(tomorrow, tomorrow)
            if (dueTomorrow.isNotEmpty()) {
                val key = "due-tomorrow:$tomorrow"
                if (claim(key, "due", 1, tomorrow)) {
                    Notifications.post(
                        applicationContext,
                        Notifications.ID_DUE_TOMORROW,
                        title = "${dueTomorrow.size} payment${plural(dueTomorrow.size)} due tomorrow",
                        text = "Plan for ${ReminderScheduler.hourLabel(hourOf(settings))} so it is settled before then.",
                        deepLink = "khatago://due/tomorrow",
                    )
                }
            }
        }

        if (on(AppSettingEntity.REMIND_OVERDUE, true)) {
            // One source of truth for "overdue": the unified due queue, not a per-module guess. The
            // StatsDao figure is still cross-checked, because if the two ever disagree the user should
            // get a reminder (they have money sitting overdue) rather than silence.
            val overdue = db.dueDao().findOverdueOnce(today)
            if (overdue.isNotEmpty() || db.statsDao().overdueAmountOnce(today) > 0L) {
                val key = "overdue:$today"
                if (claim(key, "overdue", 2, today)) {
                    Notifications.post(
                        applicationContext,
                        Notifications.ID_OVERDUE,
                        title = "${overdue.size} overdue payment${plural(overdue.size)} to review",
                        text = "Nothing leaves your phone — open KhataGo to review the list.",
                        deepLink = "khatago://records/overdue",
                    )
                }
            }
        }

        // A day with nothing due posts nothing; that is the whole retention strategy for this feature.
        return Result.success()
    }

    private suspend fun claim(key: String, entityType: String, entityId: Long, epochDay: Long): Boolean {
        val db = container().database
        val inserted = db.catalogDao().insertReminderLog(
            ReminderLogEntity(
                dedupeKey = key,
                entityType = entityType,
                entityId = entityId,
                dueDateEpochDay = epochDay,
                postedAt = System.currentTimeMillis(),
            ),
        )
        // IGNORE returns -1 when the UNIQUE key already existed: this day has already been reported.
        return inserted != -1L
    }

    private fun plural(count: Int) = if (count == 1) "" else "s"

    private fun hourOf(settings: Map<String, String>): Int =
        settings[AppSettingEntity.REMINDER_HOUR]?.toIntOrNull()?.coerceIn(0, 23)
            ?: ReminderScheduler.DEFAULT_HOUR
}
