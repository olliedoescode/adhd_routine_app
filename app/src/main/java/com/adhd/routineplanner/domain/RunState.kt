package com.adhd.routineplanner.domain

/** Lifecycle phase of a run (spec sections 8.1, 8.7). */
enum class Phase { BeforeStart, Running, Finished }

/**
 * Derived run state (spec sections 8.1, 8.7).
 *
 * [currentIndex] is `-1` before the start and `tasks.size` when finished.
 * [secondsRemaining] is the time left in the current task (0 outside a task).
 * [progress] is the fraction of the current task elapsed, 0..1.
 */
data class RunState(
    val elapsedSeconds: Long,
    val currentIndex: Int,
    val secondsRemaining: Int,
    val progress: Float,
    val phase: Phase,
)

/**
 * Pure function: routine + task list + wall clock -> run state.
 *
 * Durations are the *rendered* ones (minimum plus buffer share), so the run
 * advances in step with what the grid draws: a task that looks 15 minutes long
 * on the plan screen also counts down from 15 here.
 *
 * Nothing about progress is stored, so a killed process resumes correctly by
 * recomputing from the wall clock (spec section 3).
 */
fun runState(
    routine: Routine,
    tasks: List<PlacedTask>,
    startMs: Long,
    nowMs: Long,
): RunState {
    val layout = Buffer.layout(tasks, routine.capacityMinutes, routine.useBuffer)
    val rendered = layout.slots.map { it.minutes }
    val totalSeconds = layout.renderedMinutes * 60L
    // Clamp at zero so a clock change cannot push the run before its start.
    val elapsedMs = (nowMs - startMs).coerceAtLeast(0L)

    if (elapsedMs < 0) {
        // Before start: report the time until the routine begins.
        val untilStart = (-elapsedMs) / 1000L
        return RunState(
            elapsedSeconds = -untilStart,
            currentIndex = -1,
            secondsRemaining = untilStart.toInt(),
            progress = 0f,
            phase = Phase.BeforeStart,
        )
    }

    val elapsedSeconds = elapsedMs / 1000L
    if (elapsedSeconds >= totalSeconds) {
        return RunState(
            elapsedSeconds = elapsedSeconds,
            currentIndex = tasks.size,
            secondsRemaining = 0,
            progress = 1f,
            phase = Phase.Finished,
        )
    }

    // Locate the current task via the prefix-sum array.
    val starts = Schedule.startMinutes(routine, tasks)
    val elapsedMinutes = (elapsedSeconds / 60L).toInt()
    val index = Schedule.taskAtMinute(starts, tasks.size, elapsedMinutes) ?: tasks.size

    val taskStartSeconds = starts[index] * 60L
    val taskDurationSeconds = (rendered.getOrNull(index) ?: tasks[index].minutes) * 60L
    val elapsedInTask = elapsedSeconds - taskStartSeconds
    val remaining = (taskDurationSeconds - elapsedInTask).coerceAtLeast(0L)
    val progress = if (taskDurationSeconds <= 0L) {
        1f
    } else {
        (elapsedInTask.toFloat() / taskDurationSeconds.toFloat()).coerceIn(0f, 1f)
    }

    return RunState(
        elapsedSeconds = elapsedSeconds,
        currentIndex = index,
        secondsRemaining = remaining.toInt(),
        progress = progress,
        phase = Phase.Running,
    )
}
