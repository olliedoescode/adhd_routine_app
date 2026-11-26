package com.adhd.routineplanner.domain

/**
 * Derived-timeline core (spec sections 3, 14.5).
 *
 * A task's start minute is the sum of the *rendered* durations of all earlier
 * tasks. Rendered durations come from [Buffer], which distributes the routine's
 * spare time proportionally, so the timeline always fills the routine window
 * exactly. The grid is only a way to draw that timeline: `row = minute / cols`,
 * `col = minute % cols`. Nothing here stores a start time.
 *
 * All functions are pure and allocation-light: the prefix-sum array is built
 * once per task-list change and reused for hit-testing.
 */
object Schedule {

    /** Minutes in a day; routines may cross midnight (spec section 14.1). */
    const val MINUTES_PER_DAY: Int = 24 * 60

    /**
     * Prefix sums of the *rendered* task durations. `result[i]` is the start
     * minute of task `i`; `result.size == tasks.size + 1`, with the final
     * element equal to the rendered total. The extra trailing element makes the
     * "minute is in task i" test `start[i] <= minute < start[i + 1]` uniform.
     *
     * @param routine supplies the window that [Buffer] partitions.
     * @param tasks the routine's tasks, in order.
     */
    fun startMinutes(routine: Routine, tasks: List<PlacedTask>): IntArray {
        val layout = Buffer.layout(tasks, routine.capacityMinutes, routine.useBuffer)
        val starts = IntArray(layout.slots.size + 1)
        var acc = 0
        for (i in layout.slots.indices) {
            starts[i] = acc
            acc += layout.slots[i].minutes
        }
        starts[layout.slots.size] = acc
        return starts
    }

    /**
     * Prefix sums over the tasks' *minimum* durations, ignoring the buffer.
     *
     * Only for callers that genuinely want the un-buffered timeline (the
     * duration picker's capacity maths). Everything that draws or hit-tests the
     * grid must use [startMinutes] instead.
     */
    fun minimumStartMinutes(tasks: List<PlacedTask>): IntArray {
        val starts = IntArray(tasks.size + 1)
        var acc = 0
        for (i in tasks.indices) {
            starts[i] = acc
            acc += tasks[i].minutes
        }
        starts[tasks.size] = acc
        return starts
    }

    /** Sum of the tasks' minimum durations, before any buffer is added. */
    fun totalMinutes(tasks: List<PlacedTask>): Int {
        var acc = 0
        for (t in tasks) acc += t.minutes
        return acc
    }

    /**
     * Rendered total: the routine capacity when buffers are on, or the sum of
     * the minimums when buffers are off or the tasks are over-subscribed.
     */
    fun renderedMinutes(routine: Routine, tasks: List<PlacedTask>): Int =
        Buffer.layout(tasks, routine.capacityMinutes, routine.useBuffer).renderedMinutes

    /**
     * Index of the task covering [minute], or `null` if [minute] is negative or
     * at/after the end of the routine.
     *
     * Binary search over the prefix array (spec section 14.5). Accepts a
     * precomputed [starts] array to avoid rebuilding it on every hit-test.
     */
    fun taskAtMinute(starts: IntArray, taskCount: Int, minute: Int): Int? {
        if (minute < 0) return null
        if (taskCount == 0) return null
        if (minute >= starts[taskCount]) return null
        // Find the last index i in [0, taskCount) with starts[i] <= minute.
        var lo = 0
        var hi = taskCount - 1
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= minute) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /**
     * Builds a `minute -> task index` lookup array of length [totalMinutes],
     * where each entry is the index of the task covering that minute. Rebuilt
     * only when the task list changes (spec section 14.5).
     *
     * @param renderedMinutes the length of the array; pass the routine capacity
     *   so the buffer is included.
     */
    fun colorOfMinute(tasks: List<PlacedTask>, renderedMinutes: Int): IntArray {
        val lookup = IntArray(renderedMinutes)
        var minute = 0
        for (i in tasks.indices) {
            val end = minute + tasks[i].minutes
            while (minute < end && minute < renderedMinutes) {
                lookup[minute] = i
                minute++
            }
        }
        return lookup
    }

    /**
     * Minute-of-day at which the routine ends, wrapping at 24h so a routine
     * that crosses midnight reports a small number (spec section 14.1).
     *
     * This is the routine's declared end, not the sum of its tasks: the window
     * is authoritative, and the tasks are stretched to fill it.
     */
    fun endMinuteOfDay(routine: Routine): Int =
        ((routine.endMinuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY

    /**
     * Clock label for the left gutter of [row]: the routine start plus
     * `row * cols` minutes, wrapped at 24h and formatted `H:MM` (spec section
     * 14.2). Negative inputs wrap too.
     */
    fun clockLabel(routineStartMinuteOfDay: Int, row: Int, cols: Int): String {
        val raw = routineStartMinuteOfDay + row * cols
        val wrapped = ((raw % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        val hours = wrapped / 60
        val minutes = wrapped % 60
        return "%d:%02d".format(hours, minutes)
    }

    /** Formats a minute-of-day as `H:MM`, wrapping at 24h. */
    fun formatMinuteOfDay(minuteOfDay: Int): String {
        val wrapped = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return "%d:%02d".format(wrapped / 60, wrapped % 60)
    }
}
