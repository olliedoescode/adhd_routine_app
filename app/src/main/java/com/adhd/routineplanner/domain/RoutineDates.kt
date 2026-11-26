package com.adhd.routineplanner.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Date helpers for one-off routines.
 *
 * A routine is either a weekly recurrence or a one-off. The one-off case stores
 * its date as an ISO `yyyy-MM-dd` string in [Routine.scheduledDate]; `null`
 * means "recurring weekly on [Routine.daysOfWeek]". Keeping the conversion and
 * the display formatting here means the UI never parses dates itself and the
 * rules stay unit-testable.
 */
object RoutineDates {

    /** The storage format for [Routine.scheduledDate]. Sorts lexicographically. */
    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** Parses a stored `yyyy-MM-dd` string, or `null` if it is absent/invalid. */
    fun parse(stored: String?): LocalDate? {
        if (stored.isNullOrBlank()) return null
        return runCatching { LocalDate.parse(stored, ISO) }.getOrNull()
    }

    /** Formats a date for storage, or `null` for a recurring routine. */
    fun store(date: LocalDate?): String? = date?.format(ISO)

    /** The date a routine runs on, or `null` when it is weekly recurring. */
    fun dateOf(routine: Routine): LocalDate? = parse(routine.scheduledDate)

    /** True when the routine is a one-off rather than a weekly recurrence. */
    fun isOneOff(routine: Routine): Boolean = dateOf(routine) != null

    /**
     * The label shown on a routine card for a one-off: the weekday, the month
     * and the day of the month, for example `"Tue 25 Nov"`.
     */
    fun oneOffLabel(date: LocalDate, locale: Locale = Locale.getDefault()): String {
        val day = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        val month = date.month.getDisplayName(TextStyle.SHORT, locale)
        return "$day ${date.dayOfMonth} $month"
    }

    /** The label for a routine's date, or `null` when it is recurring. */
    fun oneOffLabel(routine: Routine, locale: Locale = Locale.getDefault()): String? =
        dateOf(routine)?.let { oneOffLabel(it, locale) }

    /**
     * True when a one-off routine's date has already passed, so it should be
     * treated as finished. Recurring routines are never expired.
     */
    fun hasExpired(routine: Routine, today: LocalDate = LocalDate.now()): Boolean {
        val date = dateOf(routine) ?: return false
        return date.isBefore(today)
    }

    /** Tomorrow, the default date for a new one-off routine. */
    fun tomorrow(today: LocalDate = LocalDate.now()): LocalDate = today.plusDays(1)
}
