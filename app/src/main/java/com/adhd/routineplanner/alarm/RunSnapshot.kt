package com.adhd.routineplanner.alarm

import com.adhd.routineplanner.data.RoutineRepository
import com.adhd.routineplanner.data.RunSession
import com.adhd.routineplanner.domain.Phase
import com.adhd.routineplanner.domain.PlacedTask
import com.adhd.routineplanner.domain.Routine
import com.adhd.routineplanner.domain.RunState
import com.adhd.routineplanner.domain.runState

/**
 * A point-in-time readout of the active run, shared by the ongoing notification
 * and the lock-screen widget so both always say the same thing.
 *
 * Everything here is derived from the persisted [RunSession] and the wall clock
 * via [runState], so a killed process or a widget refresh recomputes the same
 * values the run screen shows.
 */
data class RunSnapshot(
    val routineId: Long,
    val routineName: String,
    val taskName: String?,
    val state: RunState,
    /**
     * The task that follows the current one, or `null` at the end of the
     * routine. Shown as a second line so one notification answers both "what am
     * I doing" and "what's next" without a second card in the shade.
     */
    val nextTaskName: String? = null,
    /**
     * The current task's stock-task icon key, so the ongoing notification can
     * use the task's own icon as its small icon. `null` before the start and
     * after the finish, when there is no current task.
     */
    val taskIcon: String? = null,
    /**
     * True while the user has paused the run. The countdown in [state] is
     * already frozen, so this only adds the word "Paused" to the copy — without
     * it a frozen countdown reads like a broken widget.
     */
    val paused: Boolean = false,
) {
    /** True once the routine has run past its end. */
    val isFinished: Boolean get() = state.phase == Phase.Finished

    /** True before the scheduled start. */
    val isBeforeStart: Boolean get() = state.phase == Phase.BeforeStart

    /**
     * The notification header: the task currently running, because that is the
     * thing worth seeing when you glance at your phone. Falls back to a
     * phase-appropriate label before the start and after the finish, when there
     * is no current task.
     */
    fun title(): String = when (state.phase) {
        Phase.BeforeStart -> taskName?.let { "Starting soon: $it" } ?: "Starting soon"
        Phase.Finished -> "Routine finished"
        Phase.Running -> taskName ?: "Task"
    }

    /**
     * The second line: time left on the current task, then the next task, on a
     * single line — "4:12 left · Next: Get dressed". Empty before the start and
     * once finished, where there is no countdown to show.
     */
    fun detailLine(): String = when (state.phase) {
        Phase.Running -> {
            val left = "${remainingLabel(state.secondsRemaining)} left"
            val next = nextTaskName?.let { "Next: $it" } ?: "Last task"
            if (paused) "Paused · $left · $next" else "$left · $next"
        }
        Phase.BeforeStart -> taskName?.let { "First up: $it" } ?: ""
        Phase.Finished -> ""
    }

    /**
     * The third line: the routine name. Deliberately last, because which
     * routine this is matters less than what to do now and what comes next.
     */
    fun routineLine(): String = routineName

    /**
     * The "what am I doing now" line: "Shower · 4:12 left", "Starting soon:
     * Shower", or "Routine finished".
     */
    fun body(): String = when (state.phase) {
        Phase.BeforeStart -> "Starting soon" + (taskName?.let { ": $it" } ?: "")
        Phase.Finished -> "Routine finished"
        Phase.Running -> {
            val name = taskName ?: "Task"
            val prefix = if (paused) "Paused · " else ""
            "$prefix$name · ${remainingLabel(state.secondsRemaining)} left"
        }
    }

    /**
     * The second line: "Next: Get dressed" while running, or "Last task" when
     * the current task is the final one. Empty before the start (the body
     * already names the first task) and once finished.
     */
    fun nextLine(): String = when (state.phase) {
        Phase.Running -> nextTaskName?.let { "Next: $it" } ?: "Last task"
        else -> ""
    }

    /**
     * The full two-line notification text. Android collapses a newline in the
     * collapsed view but expands it in the shade, which is exactly the
     * behaviour we want: one line at a glance, both lines when opened.
     */
    fun notificationText(): String {
        val next = nextLine()
        return if (next.isEmpty()) body() else "${body()}\n$next"
    }

    companion object {
        /**
         * Builds a snapshot for [session] at [nowMs], or `null` when the routine
         * no longer exists (e.g. it was deleted mid-run).
         */
        suspend fun load(
            repository: RoutineRepository,
            session: RunSession,
            nowMs: Long,
        ): RunSnapshot? {
            val routine: Routine = repository.getRoutine(session.routineId) ?: return null
            val tasks: List<PlacedTask> = repository.getTasks(session.routineId)
            // A paused run is evaluated at the pause instant, exactly like the
            // run screen does, so the widget and the notification freeze too.
            val state = runState(routine, tasks, session.startMs, session.pausedAtMs ?: nowMs)
            val current = tasks.getOrNull(state.currentIndex)
            // Before the start `currentIndex` is -1, so a naive `+1` would name
            // the first task as "next" while it is also the one about to run.
            val next = when (state.phase) {
                Phase.BeforeStart -> null
                Phase.Finished -> null
                Phase.Running -> tasks.getOrNull(state.currentIndex + 1)
            }
            return RunSnapshot(
                routineId = routine.id,
                routineName = routine.name.ifBlank { "Routine" },
                taskName = current?.name?.ifBlank { PlacedTask.DEFAULT_NAME },
                state = state,
                nextTaskName = next?.name?.ifBlank { PlacedTask.DEFAULT_NAME },
                taskIcon = current?.icon,
                paused = session.isPaused,
            )
        }

        /**
         * A countdown label in `m:ss` form — "1:59", "0:42", "12:05".
         *
         * Seconds are always shown, even above a minute: "1 minute left" hides
         * the fact that the task is about to end, and a live card that only
         * changes once a minute does not feel live.
         */
        fun remainingLabel(secondsRemaining: Int): String {
            val seconds = secondsRemaining.coerceAtLeast(0)
            val minutes = seconds / 60
            val rest = seconds % 60
            return "%d:%02d".format(minutes, rest)
        }
    }
}
