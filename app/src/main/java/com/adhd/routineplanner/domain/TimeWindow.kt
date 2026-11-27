package com.adhd.routineplanner.domain

/**
 * Pure helpers for editing a routine's time window (start/end minute-of-day).
 *
 * These encode the rules the editor follows so the create screen and the
 * routine-settings sheet cannot drift apart:
 *
 *  - When the user picks a start time, the end time defaults to one hour later.
 *  - When the user moves the start past the end, the end is pushed forward so it
 *    is never before the start.
 *  - When tasks already exist, the end is set to cover the tasks' total length.
 *  - A window can never be shorter than the number of tasks (each task needs at
 *    least one minute), so a too-short window is rejected rather than silently
 *    dropping tasks.
 *
 * Everything works in minutes-of-day and wraps at midnight, matching
 * [Routine.capacityMinutes].
 */
object TimeWindow {

    /** Default length of a new routine window, in minutes. */
    const val DEFAULT_LENGTH_MINUTES: Int = 60

    /** Shortest a task may be, in minutes. */
    const val MIN_TASK_MINUTES: Int = 1

    /** The classic fallback start for a new routine: 07:00. */
    const val FALLBACK_START_MINUTE: Int = 7 * 60

    /**
     * A start time for a brand-new routine that is tailored to the moment the
     * user is creating it.
     *
     * The rule is deliberately simple and predictable:
     *  - Before 06:00, start at 07:00 (a morning routine planned late at night).
     *  - Otherwise, round the current time up to the next half hour, so the
     *    routine starts soon but on a tidy boundary.
     *
     * The result is always in `0..1439`.
     */
    fun suggestedStartMinute(nowMinuteOfDay: Int): Int {
        val now = wrap(nowMinuteOfDay)
        if (now < 6 * 60) return FALLBACK_START_MINUTE
        val rounded = ((now + 29) / 30) * 30
        return wrap(rounded)
    }

    /** The hour a routine defaults to when it is planned for "the morning". */
    const val MORNING_START_MINUTE: Int = 8 * 60

    /**
     * A smart default start time for a brand-new routine, together with whether
     * that time falls on the *next* day.
     *
     * The rule follows how people actually plan a routine:
     *
     *  - **Small hours (00:00–04:59)** — it is the middle of the night, so the
     *    user is almost certainly planning *today's* morning. Start at 08:00
     *    **today**.
     *  - **Early morning (05:00–08:59)** — the day is starting now. Round the
     *    current time up to the next half hour, **today**.
     *  - **Daytime (09:00–20:59)** — round the current time up to the next half
     *    hour, **today**.
     *  - **Late evening (21:00–23:59)** — the day is over. Start at 08:00
     *    **tomorrow**, which is the next realistic time to run a routine.
     *
     * @return a pair of the start minute-of-day and `true` when it is tomorrow.
     */
    fun smartDefaultStart(nowMinuteOfDay: Int): Pair<Int, Boolean> {
        val now = wrap(nowMinuteOfDay)
        return when {
            now < 5 * 60 -> MORNING_START_MINUTE to false
            now >= 21 * 60 -> MORNING_START_MINUTE to true
            else -> {
                val rounded = ((now + 29) / 30) * 30
                wrap(rounded) to false
            }
        }
    }

    /** Wraps a minute-of-day into `0..1439`. */
    fun wrap(minuteOfDay: Int): Int =
        ((minuteOfDay % Schedule.MINUTES_PER_DAY) + Schedule.MINUTES_PER_DAY) %
            Schedule.MINUTES_PER_DAY

    /**
     * The window length in minutes for [start] -> [end], wrapping at midnight.
     * A zero-length window is treated as a full day, matching
     * [Routine.capacityMinutes].
     */
    fun capacity(start: Int, end: Int): Int {
        val raw = wrap(end) - wrap(start)
        val wrapped = ((raw % Schedule.MINUTES_PER_DAY) + Schedule.MINUTES_PER_DAY) %
            Schedule.MINUTES_PER_DAY
        return if (wrapped == 0) Schedule.MINUTES_PER_DAY else wrapped
    }

    /**
     * The end time to use after the user sets [start], given the tasks already
     * placed.
     *
     *  - With no tasks, the end is one hour after the start.
     *  - With tasks, the end covers their total minimum length (never less than
     *    one hour, so a single short task does not produce a tiny window).
     *
     * The result is always strictly after [start] (wrapping at midnight).
     */
    fun endForStart(start: Int, taskMinutes: List<Int>): Int {
        val claimed = taskMinutes.sum()
        val length = if (claimed > 0) {
            maxOf(claimed, DEFAULT_LENGTH_MINUTES)
        } else {
            DEFAULT_LENGTH_MINUTES
        }
        return wrap(start + length)
    }

    /**
     * The end time to use after the user sets [start] when the previous end is
     * now before the start. If the tasks already fit in a sensible window, the
     * end is set to cover them; otherwise it is pushed to one hour past the
     * start.
     *
     * This is the "editing later" rule: moving the start past the end should
     * never leave an end before the start.
     */
    fun endAfterStartMoved(start: Int, currentEnd: Int, taskMinutes: List<Int>): Int {
        val currentCapacity = capacity(start, currentEnd)
        val claimed = taskMinutes.sum()
        // If the existing window still comfortably holds the tasks, keep its
        // length; otherwise fall back to the task-derived / one-hour default.
        return if (currentCapacity >= maxOf(claimed, MIN_TASK_MINUTES) &&
            currentCapacity > 0
        ) {
            wrap(start + currentCapacity)
        } else {
            endForStart(start, taskMinutes)
        }
    }

    /**
     * Whether a window of [capacity] minutes can hold [taskCount] tasks, each of
     * which needs at least [MIN_TASK_MINUTES]. When it cannot, the editor must
     * refuse the change and say "too many tasks".
     */
    fun canHoldTasks(capacity: Int, taskCount: Int): Boolean =
        capacity >= taskCount * MIN_TASK_MINUTES

    /**
     * The smallest window (in minutes) that can hold [taskCount] tasks.
     */
    fun minimumCapacityFor(taskCount: Int): Int = taskCount * MIN_TASK_MINUTES

    /**
     * Shrinks [taskMinutes] so their total fits [capacity], keeping every task
     * at least [MIN_TASK_MINUTES].
     *
     * Used when the user confirms a shorter window: the tasks are trimmed
     * proportionally (largest tasks lose the most) rather than deleted. If the
     * capacity cannot hold the task count at all, the caller should have refused
     * the change via [canHoldTasks]; this still returns a best-effort list with
     * every task at the minimum.
     */
    fun shrinkToFit(taskMinutes: List<Int>, capacity: Int): List<Int> {
        if (taskMinutes.isEmpty()) return taskMinutes
        val count = taskMinutes.size
        val minTotal = count * MIN_TASK_MINUTES
        if (capacity >= taskMinutes.sum()) return taskMinutes
        if (capacity <= minTotal) return List(count) { MIN_TASK_MINUTES }

        // Give every task its minimum, then distribute the remaining minutes
        // proportionally to how much each task would like to keep.
        val spare = capacity - minTotal
        val desired = taskMinutes.map { (it - MIN_TASK_MINUTES).coerceAtLeast(0) }
        val desiredTotal = desired.sum()
        if (desiredTotal <= 0) return List(count) { MIN_TASK_MINUTES }

        val result = IntArray(count) { MIN_TASK_MINUTES }
        var assigned = 0
        for (i in 0 until count) {
            val share = spare * desired[i] / desiredTotal
            result[i] += share
            assigned += share
        }
        // Hand out any rounding remainder one minute at a time, largest first.
        var remainder = spare - assigned
        var index = 0
        while (remainder > 0 && index < count) {
            result[index] += 1
            remainder -= 1
            index += 1
        }
        return result.toList()
    }
}
