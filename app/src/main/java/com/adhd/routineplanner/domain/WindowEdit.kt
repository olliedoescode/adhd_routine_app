package com.adhd.routineplanner.domain

/**
 * The state machine that decides what happens to a routine's tasks when the
 * user edits the routine's time window (start time, end time, or both).
 *
 * ## Why this exists
 *
 * Editing the window used to be two independent, racing writes: the routine row
 * was saved on one coroutine and the tasks were trimmed on another, and the trim
 * read `uiState.value.tasks` — a value that had not yet caught up with the
 * routine write. When the read came back empty the trim bailed out, and when it
 * came back stale it wrote a stale list. Either way the user saw their tasks
 * vanish. The fix is to make the decision **pure and total**: given the old
 * routine, the new routine and the current tasks, produce exactly one outcome
 * that the caller applies in a single transaction.
 *
 * ## The states
 *
 * The machine is driven by two numbers:
 *
 *  - `claimed` — the sum of the tasks' minimum minutes.
 *  - `capacity` — the new window length in minutes.
 *
 * and by whether the window actually changed at all.
 *
 * | # | Condition                          | State                | Tasks                        |
 * |---|------------------------------------|----------------------|------------------------------|
 * | 0 | no tasks                           | [State.NoTasks]      | nothing to do                |
 * | 1 | window unchanged                   | [State.Unchanged]    | untouched                    |
 * | 2 | `capacity >= claimed`              | [State.Fits]         | untouched (buffer absorbs)   |
 * | 3 | `capacity < claimed`, `capacity >= count` | [State.Shrink] | shortened, never deleted     |
 * | 4 | `capacity < count`                 | [State.Refuse]       | untouched, change rejected   |
 *
 * State 2 is the important one for "keep the tasks as much as possible": when
 * the window grows, or shrinks but still holds every task at its minimum, the
 * tasks are left **exactly** as they are. The buffer redistribution already
 * stretches them to fill the new window, so rewriting them would only lose
 * information (and, historically, lose the tasks entirely).
 *
 * State 3 shortens tasks proportionally via [TimeWindow.shrinkToFit], which
 * keeps every task at least one minute and never drops one.
 *
 * State 4 is the only refusal: a window with fewer minutes than tasks cannot
 * hold them all at one minute each, so the edit is rejected and the sheet tells
 * the user why. Nothing is written.
 */
object WindowEdit {

    /** What the caller should do with the tasks after a window edit. */
    enum class State {
        /** The routine has no tasks; only the routine row needs saving. */
        NoTasks,

        /** The window did not change; nothing needs saving at all. */
        Unchanged,

        /** The new window still holds every task at its minimum; leave them. */
        Fits,

        /** The new window is shorter than the tasks need; shorten them. */
        Shrink,

        /** The new window cannot hold every task; reject the edit. */
        Refuse,
    }

    /**
     * The result of evaluating a window edit.
     *
     * @param state what happened, for the caller to branch on.
     * @param tasks the task list to persist. For [State.Unchanged] and
     *   [State.Refuse] this is the original list, so a caller that writes it
     *   anyway is still safe.
     * @param capacity the new window length in minutes.
     * @param claimed the tasks' total minimum minutes before the edit.
     */
    data class Outcome(
        val state: State,
        val tasks: List<PlacedTask>,
        val capacity: Int,
        val claimed: Int,
    ) {
        /** True when the routine row and/or the tasks must be written. */
        val shouldPersist: Boolean
            get() = state == State.NoTasks || state == State.Fits || state == State.Shrink

        /** True when the tasks themselves changed and must be rewritten. */
        val tasksChanged: Boolean get() = state == State.Shrink
    }

    /**
     * Evaluates a window edit.
     *
     * @param previous the routine as it was before the edit.
     * @param updated the routine as the user has just configured it.
     * @param tasks the routine's current tasks, in order.
     */
    fun evaluate(
        previous: Routine,
        updated: Routine,
        tasks: List<PlacedTask>,
    ): Outcome {
        val capacity = updated.capacityMinutes
        val claimed = tasks.sumOf { it.minutes }

        if (tasks.isEmpty()) {
            return Outcome(State.NoTasks, tasks, capacity, claimed)
        }
        if (capacity == previous.capacityMinutes) {
            return Outcome(State.Unchanged, tasks, capacity, claimed)
        }
        if (capacity >= claimed) {
            // The window still holds every task at its minimum. Leave the tasks
            // exactly as they are: the buffer stretches them to fill the window,
            // so there is nothing to rewrite and nothing to lose.
            return Outcome(State.Fits, tasks, capacity, claimed)
        }
        if (!TimeWindow.canHoldTasks(capacity, tasks.size)) {
            // Fewer minutes than tasks: at least one task would have to be
            // dropped. Refuse rather than silently delete anything.
            return Outcome(State.Refuse, tasks, capacity, claimed)
        }
        val trimmed = TimeWindow.shrinkToFit(tasks.map { it.minutes }, capacity)
        val shortened = tasks.mapIndexed { index, task ->
            task.copy(minutes = trimmed[index])
        }
        return Outcome(State.Shrink, shortened, capacity, claimed)
    }
}
