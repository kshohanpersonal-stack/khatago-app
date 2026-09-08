package com.khatago.finance.core.time

/**
 * Where an obligation sits relative to its due date. Centralised on purpose: no screen computes
 * overdue-ness from a raw date comparison, so "overdue" always means the same thing everywhere
 * (dashboard, payment centre, notifications, reports, CSV).
 *
 * Boundaries (all inclusive, evaluated against the user's local "today"):
 *  - [Overdue]      dueDate <  today
 *  - [DueToday]     dueDate == today
 *  - [DueTomorrow]  dueDate == today + 1
 *  - [DueThisWeek]  dueDate <= end-of-week (Monday-based) and not already covered above
 *  - [DueThisMonth] dueDate <= end-of-month and not already covered above
 *  - [Upcoming]     anything later in the future
 *  - [NoDueDate]    the record simply has no due date (allowed for shop credit, borrowing, ...)
 *
 * [isDueSoon] is the definition used by the dashboard's "Due Soon" tile and by reminders, so the
 * notification and the tile can never disagree.
 */
enum class DueStatus(val label: String, val shortLabel: String) {
    NoDueDate("No due date", "—"),
    Overdue("Overdue", "Overdue"),
    DueToday("Due today", "Today"),
    DueTomorrow("Due tomorrow", "Tomorrow"),
    DueThisWeek("Due this week", "This week"),
    DueThisMonth("Due this month", "This month"),
    Upcoming("Upcoming", "Upcoming"),
    ;

    val isActionable: Boolean get() = this == Overdue || this == DueToday
    val isDueSoon: Boolean get() = this == DueTomorrow || this == DueThisWeek
    val isUpcomingOrSooner: Boolean get() = ordinal in 1..5
}

fun dueStatusOf(dueDateEpochDay: Long?, todayEpochDay: Long): DueStatus {
    if (dueDateEpochDay == null) return DueStatus.NoDueDate
    val diff = dueDateEpochDay - todayEpochDay
    return when {
        diff < 0L -> DueStatus.Overdue
        diff == 0L -> DueStatus.DueToday
        diff == 1L -> DueStatus.DueTomorrow
        dueDateEpochDay <= AppDates.endOfWeek(todayEpochDay) -> DueStatus.DueThisWeek
        dueDateEpochDay <= endOfMonth(todayEpochDay) -> DueStatus.DueThisMonth
        else -> DueStatus.Upcoming
    }
}

private fun endOfMonth(todayEpochDay: Long): Long {
    val date = AppDates.ofEpochDay(todayEpochDay)
    return date.withDayOfMonth(date.lengthOfMonth()).toEpochDay()
}

/** Stable sort priority for mixed lists: overdue first, then soonest, then undated records. */
fun dueRank(status: DueStatus): Int = when (status) {
    DueStatus.Overdue -> 0
    DueStatus.DueToday -> 1
    DueStatus.DueTomorrow -> 2
    DueStatus.DueThisWeek -> 3
    DueStatus.DueThisMonth -> 4
    DueStatus.Upcoming -> 5
    DueStatus.NoDueDate -> 6
}
