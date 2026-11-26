package com.adhd.routineplanner.domain

/**
 * List-editing operations for the plan grid (spec sections 7.3, 14.7).
 *
 * Every operation returns a new list; the input is never mutated. Callers
 * commit the result to Room and reschedule alarms only on release, never
 * during a drag or resize (spec section 6).
 */
object Reorder {

    /**
     * Index at which a task dropped at [minute] should be inserted.
     *
     * Reference implementation from spec section 7.3: walk the tasks
     * accumulating start minutes; the first task whose span contains [minute]
     * wins, otherwise the drop is appended to the end (empty tail).
     */
    fun insertionIndex(tasks: List<PlacedTask>, minute: Int): Int {
        var start = 0
        tasks.forEachIndexed { i, t ->
            if (minute < start + t.minutes) return i
            start += t.minutes
        }
        return tasks.size
    }

    /**
     * Moves the task at [fromIndex] to [toIndex], shifting the tasks in between.
     * Out-of-range indices are clamped; an unchanged index returns the list
     * as-is. Positions are not renumbered here — call [renumber] before commit.
     */
    fun move(tasks: List<PlacedTask>, fromIndex: Int, toIndex: Int): List<PlacedTask> {
        if (tasks.isEmpty()) return tasks
        val from = fromIndex.coerceIn(0, tasks.lastIndex)
        val to = toIndex.coerceIn(0, tasks.lastIndex)
        if (from == to) return tasks
        val mutable = tasks.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        return mutable
    }

    /**
     * Sets the duration of the task at [index] to [minutes], clamped to at
     * least 1 minute (spec section 14.7). Out-of-range indices return the list
     * unchanged.
     */
    fun resize(tasks: List<PlacedTask>, index: Int, minutes: Int): List<PlacedTask> {
        if (index !in tasks.indices) return tasks
        val clamped = minutes.coerceAtLeast(1)
        if (tasks[index].minutes == clamped) return tasks
        return tasks.toMutableList().also { it[index] = it[index].copy(minutes = clamped) }
    }

    /** Removes the task at [index]. Out-of-range indices return the list unchanged. */
    fun remove(tasks: List<PlacedTask>, index: Int): List<PlacedTask> {
        if (index !in tasks.indices) return tasks
        return tasks.toMutableList().also { it.removeAt(index) }
    }

    /**
     * Reassigns `position` to 0..n-1 in list order. Called on every committed
     * edit before writing to Room (spec section 14.7).
     */
    fun renumber(tasks: List<PlacedTask>): List<PlacedTask> =
        tasks.mapIndexed { i, t -> if (t.position == i) t else t.copy(position = i) }
}
