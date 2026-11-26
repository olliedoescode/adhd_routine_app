package com.adhd.routineplanner.domain

/**
 * A routine as seen by the pure domain layer.
 *
 * Mirrors the Room `Routine` entity (spec section 4) without Room annotations so
 * `domain/` stays Android-free. [startMinuteOfDay] and [endMinuteOfDay] are
 * minutes since midnight; [daysOfWeek] is a bitmask with Mon=1, Tue=2, Wed=4 ...
 * Sun=64 (see [DaysOfWeek]).
 *
 * The routine is a **fixed window**: it starts at [startMinuteOfDay] and must be
 * finished by [endMinuteOfDay]. The window's length is the capacity that
 * [Buffer] partitions across the tasks, so the grid is always completely full.
 *
 * Alarms are per-task, not per-routine: see [PlacedTask.alarmEnabled]. The old
 * routine-level `alarmMode` is gone, because "which tasks wake me up" is now
 * expressed directly on the tasks themselves.
 */
data class Routine(
    val id: Long = 0,
    val name: String = "",
    val startMinuteOfDay: Int = 0,
    val endMinuteOfDay: Int = DEFAULT_LENGTH_MINUTES,
    val daysOfWeek: Int = DaysOfWeek.ALL,
    val enabled: Boolean = true,
    /**
     * Whether the spare time in the window is spread across the tasks as
     * buffer. When `false`, each task renders at exactly its minimum and any
     * spare time is simply left as empty grid cells at the end of the routine.
     *
     * Defaults to `true` so existing routines keep their current behaviour.
     */
    val useBuffer: Boolean = true,
    /**
     * The single calendar date this routine runs on, as an ISO `yyyy-MM-dd`
     * string, or `null` for a weekly recurring routine.
     *
     * A routine is **either** a one-off or a weekly recurrence, never both:
     * when this is non-null the routine runs once on that date and then
     * disables itself; when it is `null` the routine repeats on the days in
     * [daysOfWeek]. Stored as text so `domain/` stays free of Android types and
     * the value sorts and compares as a plain string.
     */
    val scheduledDate: String? = null,
) {
    /** True when this routine repeats weekly rather than running once. */
    val isRecurring: Boolean get() = scheduledDate == null
    /**
     * Length of the routine window in minutes, wrapping at midnight so a
     * routine that crosses 00:00 still reports a positive length.
     */
    val capacityMinutes: Int
        get() {
            val raw = endMinuteOfDay - startMinuteOfDay
            val wrapped = ((raw % Schedule.MINUTES_PER_DAY) + Schedule.MINUTES_PER_DAY) %
                Schedule.MINUTES_PER_DAY
            // A zero-length window is meaningless; treat it as a full day.
            return if (wrapped == 0) Schedule.MINUTES_PER_DAY else wrapped
        }

    companion object {
        /** Default window length for a new routine: one hour. */
        const val DEFAULT_LENGTH_MINUTES: Int = 60
    }
}
