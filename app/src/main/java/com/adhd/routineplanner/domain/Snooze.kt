package com.adhd.routineplanner.domain

/**
 * The rule for snoozing a task alarm by five minutes.
 *
 * A snooze is only allowed when the routine can genuinely absorb it. There are
 * two ways that can happen, and either is enough:
 *
 *  1. **The task has spare time of its own.** [Buffer.layout] stretches every
 *     task to fill the window, so a task whose rendered length exceeds its
 *     minimum by at least [SNOOZE_MINUTES] has that much slack inside it. The
 *     snooze simply eats into that slack and nothing else moves.
 *  2. **The routine has unclaimed buffer.** When buffers are switched off (or
 *     the tasks exactly fill the window) the spare minutes sit in
 *     [Buffer.Layout.buffer] rather than being handed to the tasks. A snooze can
 *     be taken out of that pool instead.
 *
 * If neither holds, snoozing would push the routine past its end, so it is
 * refused: the alarm screen simply does not offer the button.
 *
 * This is deliberately a pure function over the derived layout — nothing is
 * stored. The snooze itself is implemented by re-registering the task's alarm
 * five minutes later (see `AlarmScheduler.snoozeTaskAlarm`), so the timeline
 * never has to be rewritten.
 */
object Snooze {

    /** How long a snooze lasts, in minutes. */
    const val SNOOZE_MINUTES: Int = 5

    /**
     * Whether the task at [taskIndex] can be snoozed by [SNOOZE_MINUTES].
     *
     * @param routine supplies the window and the buffer toggle.
     * @param tasks the routine's tasks, in order.
     * @param taskIndex the task whose alarm is ringing.
     */
    fun canSnooze(routine: Routine, tasks: List<PlacedTask>, taskIndex: Int): Boolean {
        val task = tasks.getOrNull(taskIndex) ?: return false
        val layout = Buffer.layout(tasks, routine.capacityMinutes, routine.useBuffer)
        val slot = layout.slots.getOrNull(taskIndex) ?: return false
        // Slack inside the task itself: the rendered length beyond its minimum.
        val taskSlack = slot.minutes - task.minutes
        if (taskSlack >= SNOOZE_MINUTES) return true
        // Otherwise the routine's own unclaimed buffer can cover it.
        return layout.buffer >= SNOOZE_MINUTES
    }

    /**
     * The number of minutes a snooze would take from the buffer rather than from
     * the task's own slack, for the alarm screen's explanation line.
     *
     * Returns `0` when the task's own slack covers the whole snooze.
     */
    fun minutesFromBuffer(routine: Routine, tasks: List<PlacedTask>, taskIndex: Int): Int {
        val task = tasks.getOrNull(taskIndex) ?: return 0
        val layout = Buffer.layout(tasks, routine.capacityMinutes, routine.useBuffer)
        val slot = layout.slots.getOrNull(taskIndex) ?: return 0
        val taskSlack = (slot.minutes - task.minutes).coerceAtLeast(0)
        return (SNOOZE_MINUTES - taskSlack).coerceAtLeast(0)
    }
}
