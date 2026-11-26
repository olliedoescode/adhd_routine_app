package com.adhd.routineplanner.domain

/**
 * Pure drag-and-drop maths for the plan grid (spec §15.5, §17).
 *
 * The UI owns the gesture state machine and the ghost overlay; everything that
 * decides *where* a dragged task lands lives here so it is unit-testable and
 * free of Android imports (spec §5).
 *
 * A drag **moves** an already-placed task. The task is removed from the working
 * list while it is in flight, so the insertion index is computed against the
 * remaining tasks and then clamped back into the full list.
 */
object Drag {

    /**
     * Insertion index for a drop at [minute] while [draggedIndex] is lifted out
     * of [tasks].
     *
     * The dragged task is excluded before computing the index, so dragging a
     * task a single slot forward or backward lands where the user expects
     * rather than one position off. The result is an index into the *remaining*
     * list, which is exactly what [moveTo] expects.
     */
    fun moveInsertionIndex(tasks: List<PlacedTask>, draggedIndex: Int, minute: Int): Int {
        if (draggedIndex !in tasks.indices) return Reorder.insertionIndex(tasks, minute)
        val remaining = tasks.filterIndexed { index, _ -> index != draggedIndex }
        return Reorder.insertionIndex(remaining, minute)
    }

    /**
     * Moves the task at [fromIndex] to the drop described by [minute].
     *
     * Equivalent to removing the task, recomputing the insertion index against
     * the remaining list, and reinserting. Returns the input unchanged when the
     * index is out of range or the drop resolves to the same slot.
     */
    fun moveTo(tasks: List<PlacedTask>, fromIndex: Int, minute: Int): List<PlacedTask> {
        if (fromIndex !in tasks.indices) return tasks
        val remaining = tasks.filterIndexed { index, _ -> index != fromIndex }
        val target = Reorder.insertionIndex(remaining, minute)
        val moved = remaining.toMutableList().also { it.add(target, tasks[fromIndex]) }
        return moved
    }

    /**
     * Inserts [task] at the index implied by [minute] in [tasks].
     *
     * Used when a tray chip is dropped: the new task is appended to the empty
     * tail when [minute] is past the end, matching [Reorder.insertionIndex].
     */
    fun insertAt(tasks: List<PlacedTask>, task: PlacedTask, minute: Int): List<PlacedTask> {
        val target = Reorder.insertionIndex(tasks, minute)
        return tasks.toMutableList().also { it.add(target, task) }
    }

    /**
     * The minute at which a task dropped at [minute] would begin, i.e. the
     * start of the task currently occupying that minute, or the rendered total
     * when the drop is in the tail. Used to draw the insertion marker at a
     * block boundary rather than mid-block (spec §15.5).
     *
     * @param routine supplies the window, so boundaries include the buffer.
     */
    fun boundaryMinute(routine: Routine, tasks: List<PlacedTask>, minute: Int): Int {
        val index = Reorder.insertionIndex(tasks, minute)
        val starts = Schedule.startMinutes(routine, tasks)
        if (index >= tasks.size) return starts.last()
        return starts[index]
    }

    /**
     * Snaps a hover minute to the nearest task boundary. This gives drag-and-drop
     * a stable before/after insertion slot instead of leaving the ghost inside
     * whichever task happens to contain the finger.
     */
    fun nearestInsertionMinute(tasks: List<PlacedTask>, minute: Int): Int {
        if (tasks.isEmpty()) return 0

        val boundaries = mutableListOf(0)
        tasks.forEach { boundaries.add(boundaries.last() + it.minutes) }
        val clamped = minute.coerceAtLeast(0)

        return boundaries.minWithOrNull(
            compareBy<Int> { kotlin.math.abs(it - clamped) }.thenBy { it },
        ) ?: 0
    }

    /**
     * Resolves the insertion slot for a drag, with hysteresis so the ghost does
     * not flicker between two slots when the finger hovers near a boundary.
     *
     * The slot is the index into [tasks] (the list *with* the dragged task
     * removed) at which the dragged block would be inserted. A slot only
     * changes once the finger has clearly crossed the midpoint between the
     * current slot's boundary and its neighbour, which is what makes the block
     * "clip" into place and the surrounding tasks shift aside, home-screen
     * style.
     *
     * @param tasks the remaining tasks (dragged task already removed).
     * @param minute the hovered minute.
     * @param currentSlot the slot currently held, or `null` to resolve fresh.
     * @param hysteresis extra minutes the finger must travel past a midpoint
     *   before the slot flips. Defaults to a small fraction of the average
     *   task length so it scales with the routine.
     */
    fun resolveSlot(
        tasks: List<PlacedTask>,
        minute: Int,
        currentSlot: Int?,
        hysteresis: Int = defaultHysteresis(tasks),
    ): Int = resolveSlot(tasks, boundariesOf(tasks), minute, currentSlot, hysteresis)

    /**
     * Buffer-aware [resolveSlot]: the boundaries come from the rendered
     * timeline, so the ghost clips into the block the user actually sees
     * rather than into the un-buffered minimums.
     *
     * @param routine supplies the window that [Buffer] partitions.
     */
    fun resolveSlot(
        routine: Routine,
        tasks: List<PlacedTask>,
        minute: Int,
        currentSlot: Int?,
        hysteresis: Int = defaultHysteresis(tasks),
    ): Int = resolveSlot(tasks, boundariesOf(routine, tasks), minute, currentSlot, hysteresis)

    private fun resolveSlot(
        tasks: List<PlacedTask>,
        boundaries: List<Int>,
        minute: Int,
        currentSlot: Int?,
        hysteresis: Int,
    ): Int {
        val clamped = minute.coerceIn(0, boundaries.last())
        val fresh = slotForBoundary(boundaries, clamped)
        if (currentSlot == null) return fresh
        val held = currentSlot.coerceIn(0, tasks.size)
        if (fresh == held) return held

        // Only move one slot at a time, and only when the finger is past the
        // midpoint between the held slot and the next one, plus hysteresis.
        if (fresh > held) {
            val midpoint = (boundaries[held] + boundaries[held + 1]) / 2
            return if (clamped > midpoint + hysteresis) held + 1 else held
        } else {
            val midpoint = (boundaries[held - 1] + boundaries[held]) / 2
            return if (clamped < midpoint - hysteresis) held - 1 else held
        }
    }

    /** Cumulative boundary minutes for [tasks]: `[0, ..., total]`. */
    fun boundariesOf(tasks: List<PlacedTask>): List<Int> {
        val boundaries = ArrayList<Int>(tasks.size + 1)
        var acc = 0
        boundaries.add(0)
        for (t in tasks) {
            acc += t.minutes
            boundaries.add(acc)
        }
        return boundaries
    }

    /**
     * Cumulative boundary minutes on the rendered timeline, so the boundaries
     * include each task's buffer share and the last one is the routine end.
     */
    fun boundariesOf(routine: Routine, tasks: List<PlacedTask>): List<Int> =
        Schedule.startMinutes(routine, tasks).toList()

    /** The slot whose boundary is nearest [minute]. */
    private fun slotForBoundary(boundaries: List<Int>, minute: Int): Int {
        var best = 0
        var bestDistance = Int.MAX_VALUE
        for (i in boundaries.indices) {
            val distance = kotlin.math.abs(boundaries[i] - minute)
            if (distance < bestDistance) {
                best = i
                bestDistance = distance
            }
        }
        return best
    }

    /** Hysteresis in minutes: a quarter of the average task length, min 1. */
    private fun defaultHysteresis(tasks: List<PlacedTask>): Int {
        if (tasks.isEmpty()) return 1
        val average = Schedule.totalMinutes(tasks) / tasks.size
        return (average / 4).coerceAtLeast(1)
    }
}
