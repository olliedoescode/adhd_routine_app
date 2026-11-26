package com.adhd.routineplanner.domain

/**
 * Distributes a routine's spare time across its tasks (spec section 3).
 *
 * A routine is a fixed window: [Routine.startMinuteOfDay] to
 * [Routine.endMinuteOfDay]. Each task stores a *minimum* duration — the time it
 * genuinely needs. Whatever the window has left over after every minimum is
 * claimed is **buffer**, and the buffer is handed out in proportion to each
 * task's minimum, so a long task absorbs more slack than a short one.
 *
 * The result always sums to exactly the window's capacity, so the grid is a
 * complete partition of the routine: there are no empty cells inside it. Any
 * leftover minutes from integer division are handed out one at a time to the
 * tasks with the largest fractional shares, which keeps the split deterministic
 * (the same input always produces the same output, so the grid never jitters).
 *
 * Everything here is pure integer arithmetic so it can be unit-tested without
 * Android.
 */
object Buffer {

    /**
     * One task's rendered position and length.
     *
     * @param taskIndex index into the task list this slot came from.
     * @param startMinute minutes from the routine start, not minute-of-day.
     * @param minutes rendered length: the task's minimum plus its buffer share.
     */
    data class Slot(
        val taskIndex: Int,
        val startMinute: Int,
        val minutes: Int,
    )

    /**
     * The full layout of a routine: one [Slot] per task, in order, plus the
     * numbers the UI needs to explain itself.
     *
     * @param slots rendered slots, in task order.
     * @param capacity the routine window in minutes.
     * @param claimed the sum of the tasks' minimum durations.
     * @param buffer `capacity - claimed`, clamped at zero.
     * @param overrun `claimed - capacity` when the tasks do not fit, else zero.
     */
    data class Layout(
        val slots: List<Slot>,
        val capacity: Int,
        val claimed: Int,
        val buffer: Int,
        val overrun: Int,
    ) {
        /** True when the tasks need more time than the window provides. */
        val isOverSubscribed: Boolean get() = overrun > 0

        /** Total rendered minutes; equals [capacity] unless over-subscribed. */
        val renderedMinutes: Int get() = slots.sumOf { it.minutes }
    }

    /**
     * Lays out [tasks] inside a window of [capacity] minutes.
     *
     * When the tasks do not fit, they are **not** shrunk: each renders at its
     * minimum and the layout reports the [Layout.overrun]. Silently shortening
     * a task would turn a 10-minute shower into 6 minutes without telling
     * anyone, which is worse than showing an honest overrun.
     *
     * @param tasks the routine's tasks, in order.
     * @param capacity the routine window in minutes; values below 1 yield an
     *   empty layout.
     * @param useBuffer when `false`, the spare time is **not** distributed:
     *   every task renders at exactly its minimum and the leftover minutes are
     *   simply left unclaimed (the grid draws them as empty cells). This is the
     *   routine-level "buffers off" setting. Defaults to `true` so callers that
     *   predate the toggle keep the stretched layout.
     */
    fun layout(tasks: List<PlacedTask>, capacity: Int, useBuffer: Boolean = true): Layout {
        if (tasks.isEmpty() || capacity <= 0) {
            return Layout(
                slots = emptyList(),
                capacity = capacity.coerceAtLeast(0),
                claimed = tasks.sumOf { it.minutes },
                buffer = 0,
                overrun = 0,
            )
        }

        val claimed = tasks.sumOf { it.minutes }
        val buffer = capacity - claimed

        // Over-subscribed, or buffers switched off: render every task at its
        // minimum. With buffers off the spare time is reported as `buffer` but
        // not handed out, so the rendered total is the claimed total and the
        // grid shows the remainder as empty cells.
        if (buffer <= 0 || !useBuffer) {
            var minute = 0
            val slots = tasks.mapIndexed { index, task ->
                Slot(taskIndex = index, startMinute = minute, minutes = task.minutes)
                    .also { minute += task.minutes }
            }
            return Layout(
                slots = slots,
                capacity = capacity,
                claimed = claimed,
                buffer = buffer.coerceAtLeast(0),
                overrun = (-buffer).coerceAtLeast(0),
            )
        }

        // Nothing claimed but there is capacity: nothing to distribute across.
        if (claimed == 0) {
            return Layout(
                slots = emptyList(),
                capacity = capacity,
                claimed = 0,
                buffer = buffer,
                overrun = 0,
            )
        }

        val shares = distribute(buffer, tasks.map { it.minutes })

        var minute = 0
        val slots = tasks.mapIndexed { index, task ->
            val rendered = task.minutes + shares[index]
            Slot(taskIndex = index, startMinute = minute, minutes = rendered)
                .also { minute += rendered }
        }
        return Layout(
            slots = slots,
            capacity = capacity,
            claimed = claimed,
            buffer = buffer,
            overrun = 0,
        )
    }

    /**
     * Splits [buffer] across [weights] proportionally, returning one share per
     * weight such that the shares sum to exactly [buffer].
     *
     * Uses the largest-remainder method: every weight gets the floor of its
     * exact share, then the leftover minutes go one each to the tasks with the
     * biggest fractional parts. Ties break on the lower index so the result is
     * deterministic.
     *
     * @param buffer minutes to hand out; must be non-negative.
     * @param weights each task's minimum duration; must sum to more than zero.
     */
    fun distribute(buffer: Int, weights: List<Int>): IntArray {
        val shares = IntArray(weights.size)
        if (buffer <= 0 || weights.isEmpty()) return shares
        val total = weights.sum()
        if (total <= 0) return shares

        // Floor each exact share, tracking the remainder as a numerator so the
        // comparison stays in integer arithmetic (no floating-point drift).
        val remainders = IntArray(weights.size)
        var handedOut = 0
        for (i in weights.indices) {
            val numerator = buffer * weights[i]
            shares[i] = numerator / total
            remainders[i] = numerator % total
            handedOut += shares[i]
        }

        // Hand the leftover minutes to the largest remainders, lowest index first.
        var leftover = buffer - handedOut
        if (leftover > 0) {
            val order = weights.indices.sortedWith(
                compareByDescending<Int> { remainders[it] }.thenBy { it },
            )
            var cursor = 0
            while (leftover > 0 && cursor < order.size) {
                shares[order[cursor]] += 1
                leftover -= 1
                cursor += 1
            }
        }
        return shares
    }

    /**
     * The longest a single task may be, given the other tasks' minimums and the
     * window. Used to clamp the duration picker so a task can never push the
     * routine past its end.
     *
     * @param capacity the routine window in minutes.
     * @param otherTasksMinimums the minimum durations of every *other* task.
     */
    fun maxDurationFor(capacity: Int, otherTasksMinimums: List<Int>): Int {
        val others = otherTasksMinimums.sum()
        return (capacity - others).coerceAtLeast(1)
    }
}
