package com.khatago.finance.core.time

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * KhataGo's calendar model.
 *
 * Financial dates (purchase date, due date, disbursement date) are **local calendar days**, not
 * instants: a payment "due on the 5th" is due on the 5th in the user's day, and must not shift
 * when the device crosses a timezone or a DST boundary. So every financial date is stored as an
 * epoch-day [Long] and only converted to [LocalDate] at the edges.
 *
 * Timestamps (created/updated, payment time) are epoch milliseconds in the system default zone,
 * which is the only zone KhataGo ever renders in — the user's own.
 */
object AppDates {

    /** Shared formatter: English-only product, pinned so `Mar` never becomes a localised word. */
    private val medium: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val mediumWithWeekday: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
    private val shortMonthYear: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
    private val compactMonthKey: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ENGLISH)

    fun ofEpochDay(epochDay: Long): LocalDate = LocalDate.ofEpochDay(epochDay)

    fun toEpochDay(date: LocalDate): Long = date.toEpochDay()

    /** "Today" in the device's local calendar — the one anchor every due calculation uses. */
    fun today(zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long =
        LocalDate.now(zoneId).toEpochDay()

    fun formatMedium(epochDay: Long?): String =
        epochDay?.let { medium.format(ofEpochDay(it)) } ?: "No due date"

    fun formatMediumWithWeekday(epochDay: Long): String = mediumWithWeekday.format(ofEpochDay(epochDay))

    /** `9:05 AM` — 12-hour, English, for reminders and schedule copy. */
    fun clockTime(time: java.time.LocalTime): String {
        val hour24 = time.hour
        val minute = time.minute
        val suffix = if (hour24 < 12) "AM" else "PM"
        val hour = when (hour24 % 12) {
            0 -> 12
            else -> hour24 % 12
        }
        return "%d:%02d %s".format(hour, minute, suffix)
    }

    fun formatMonthYear(epochDay: Long): String = shortMonthYear.format(ofEpochDay(epochDay))

    fun monthKey(epochDay: Long): String = compactMonthKey.format(ofEpochDay(epochDay))

    /** Bucket label for charts, e.g. `Feb 26`. */
    fun formatChartMonth(epochDay: Long): String {
        val date = ofEpochDay(epochDay)
        return "%s %02d".format(date.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH), date.year % 100)
    }

    /**
     * Friendly, non-robotic date label used in lists:
     * `Today`, `Tomorrow`, `Yesterday`, `in 3 days`, `3 days ago`, else a real date.
     */
    fun humanDay(epochDay: Long?, todayEpochDay: Long): String {
        if (epochDay == null) return "No due date"
        return when (val diff = epochDay - todayEpochDay) {
            0L -> "Today"
            1L -> "Tomorrow"
            -1L -> "Yesterday"
            in 2L..6L -> "in $diff days"
            in -6L..-2L -> "${-diff} days ago"
            else -> formatMedium(epochDay)
        }
    }

    /** Inclusive month bounds as epoch days, used for "this month" queries. */
    fun monthRange(year: Int, month: Month): LongRange =
        LocalDate.of(year, month, 1).toEpochDay()..LocalDate.of(year, month, month.length(isLeap(year)), 1)
            .let { it.minusDays(1).toEpochDay() }

    fun isLeap(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    /** Monday-based start of the user's week; matches the "Due This Week" requirement. */
    fun startOfWeek(todayEpochDay: Long): Long = ofEpochDay(todayEpochDay).with(DayOfWeek.MONDAY).toEpochDay()

    fun endOfWeek(todayEpochDay: Long): Long = startOfWeek(todayEpochDay) + 6
}
