package com.adhd.routineplanner.domain

/**
 * Spoken descriptions for task blocks (spec §18).
 *
 * Kept in `domain/` so it stays Android-free and unit-testable, and so the plan
 * grid, the run grid and the selection actions all read the same wording.
 */
object TaskDescription {

    /**
     * Describes a task for a screen reader: name, start time and length.
     *
     * @param name the task's name.
     * @param startMinuteOfDay minute-of-day the task starts at.
     * @param minutes the task's length in minutes.
     */
    fun describe(name: String, startMinuteOfDay: Int, minutes: Int): String =
        "$name, starts ${clock(startMinuteOfDay)}, ${length(minutes)}"

    /**
     * Describes a task from its offset within the routine, which is how the
     * grids know it: `startOffset` minutes after the routine's start.
     *
     * @param name the task's name.
     * @param routineStartMinuteOfDay the routine's start minute-of-day.
     * @param startOffset minutes from the routine start to this task's start.
     * @param minutes the task's length in minutes.
     */
    fun describeAtOffset(
        name: String,
        routineStartMinuteOfDay: Int,
        startOffset: Int,
        minutes: Int,
    ): String = describe(name, routineStartMinuteOfDay + startOffset, minutes)

    /** A 24-hour clock label, wrapping past midnight. */
    fun clock(minuteOfDay: Int): String {
        val wrapped = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        val hour = wrapped / 60
        val minute = wrapped % 60
        return "%d:%02d".format(hour, minute)
    }

    /** "1 minute", "5 minutes", "1 hour", "1 hour 30 minutes". */
    fun length(minutes: Int): String {
        if (minutes <= 0) return "no time"
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> plural(rest, "minute")
            rest == 0 -> plural(hours, "hour")
            else -> "${plural(hours, "hour")} ${plural(rest, "minute")}"
        }
    }

    private fun plural(value: Int, unit: String): String =
        if (value == 1) "1 $unit" else "$value ${unit}s"

    private const val MINUTES_PER_DAY = 24 * 60
}
