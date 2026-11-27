package com.adhd.routineplanner.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adhd.routineplanner.alarm.RunStarter
import com.adhd.routineplanner.alarm.RunStopper
import com.adhd.routineplanner.data.RoutineRepository
import com.adhd.routineplanner.data.RunSessionStore
import com.adhd.routineplanner.domain.AlarmSchedule
import com.adhd.routineplanner.domain.DaysOfWeek
import com.adhd.routineplanner.domain.PlacedTask
import com.adhd.routineplanner.domain.Routine
import com.adhd.routineplanner.domain.RoutineDates
import com.adhd.routineplanner.domain.Schedule
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One routine plus the derived fields the list card shows (spec §15.2):
 * total duration, end time, and — for a one-off routine — the date label to
 * show instead of the weekly day letters. Computed here so the composable
 * stays dumb.
 */
data class RoutineCardUi(
    val routine: Routine,
    val totalMinutes: Int,
    val endMinuteOfDay: Int,
    /** `"Tue 25 Nov"` for a one-off routine, `null` for a weekly recurrence. */
    val oneOffLabel: String? = null,
    /**
     * `"Starts in 2 hours 15 mins"` / `"Starts in 45 mins"` when the routine's
     * next start is within the next 24 hours, otherwise `null`.
     *
     * Only enabled routines get a countdown: a disabled routine will not start,
     * so promising a start time would be a lie.
     */
    val startsInLabel: String? = null,
)

/**
 * Backs the routine list (spec §15.2).
 *
 * Observes routines and their task lists, derives the card summary, and exposes
 * the mutations the list needs: toggle enabled (which reschedules alarms),
 * delete (with undo), and create.
 */
@HiltViewModel
class RoutineListViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: RoutineRepository,
    private val runStopper: RunStopper,
    private val runSessionStore: RunSessionStore,
) : ViewModel() {

    /** A routine deleted from the list, held so the snackbar can restore it. */
    data class DeletedRoutine(val routine: Routine)

    private val _deleted = MutableStateFlow<DeletedRoutine?>(null)
    val deleted: StateFlow<DeletedRoutine?> = _deleted

    /**
     * The routine of the run in progress, or `null` when nothing is running.
     *
     * Backs the "in progress" bar at the top of the list so returning to the
     * app during a run is one tap from the run page instead of a hunt through
     * the ongoing notification.
     */
    val activeRunId: StateFlow<Long?> = runSessionStore.session
        .map { it?.routineId }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    /**
     * A coarse clock that ticks once a minute while the list is on screen.
     *
     * The "Starts in …" label is derived from the wall clock, so without a tick
     * it would freeze at whatever it said when the list was first composed. A
     * minute is the finest granularity the label shows, so that is all we need.
     */
    private val minuteTicker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000L)
        }
    }

    val routines: StateFlow<List<RoutineCardUi>> = combine(
        repository.observeRoutines(),
        repository.observeAllTasksByRoutine(),
        minuteTicker,
    ) { routines, tasksByRoutine, nowMs ->
        routines.map { routine ->
            val tasks = tasksByRoutine[routine.id].orEmpty()
            RoutineCardUi(
                routine = routine,
                totalMinutes = Schedule.totalMinutes(tasks),
                endMinuteOfDay = Schedule.endMinuteOfDay(routine),
                oneOffLabel = RoutineDates.oneOffLabel(routine),
                startsInLabel = startsInLabel(routine, nowMs),
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /**
     * `"Starts in 2 hours 15 mins"` / `"Starts in 45 mins"` when the routine's
     * next start is within 24 hours, otherwise `null`.
     *
     * Reuses [AlarmSchedule.nextRoutineStart] — the same calculation the alarm
     * scheduler uses — so the countdown can never disagree with when the
     * routine actually fires. Disabled routines return `null`: they will not
     * start, so a countdown would be misleading.
     */
    private fun startsInLabel(routine: Routine, nowMs: Long): String? {
        if (!routine.enabled) return null
        val startMs = AlarmSchedule.nextRoutineStart(routine, nowMs) ?: return null
        val minutesUntil = (startMs - nowMs) / 60_000L
        // Only show it when it is in the future and under a day away.
        if (minutesUntil <= 0L || minutesUntil >= 24L * 60L) return null
        return "Starts in ${durationLabel(minutesUntil.toInt())}"
    }

    /** "2 hours 15 mins", "1 hour", "45 mins" — the largest two units only. */
    private fun durationLabel(totalMinutes: Int): String {
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours == 0 -> "$minutes min${plural(minutes)}"
            minutes == 0 -> "$hours hour${plural(hours)}"
            else -> "$hours hour${plural(hours)} $minutes min${plural(minutes)}"
        }
    }

    /** "s" for anything but 1, so "1 hour" / "2 hours". */
    private fun plural(value: Int): String = if (value == 1) "" else "s"

    /** Enables or disables a routine; disabling cancels its alarms (spec §9). */
    fun setEnabled(routineId: Long, enabled: Boolean) {
        viewModelScope.launch { repository.setRoutineEnabled(routineId, enabled) }
    }

    /**
     * Starts a run of [routineId] right now, from the card's Run button.
     *
     * This is the same path the plan screen's Run action uses
     * ([RunStarter.beginNow]): it records the session, posts the ongoing
     * notification, schedules the in-run task alarms (skipped for an untimed
     * routine) and buzzes the phone, so a run started from the list behaves
     * exactly like any other. The lock-screen takeover is launched directly
     * because the app is in the foreground.
     */
    fun startRun(routineId: Long) {
        if (routineId <= 0L) return
        viewModelScope.launch { RunStarter.beginNow(appContext, routineId) }
    }

    /**
     * Deletes a routine, keeping a copy for undo.
     *
     * If this routine is the one currently running, the run is torn down first:
     * a live session pointing at a deleted row leaves the run notification,
     * the lock-screen takeover and the run screen showing something that no
     * longer exists. [RunStopper] clears all of that before the row goes.
     */
    fun delete(routine: Routine) {
        _deleted.value = DeletedRoutine(routine)
        viewModelScope.launch {
            runStopper.stopIfRunningFor(routine.id)
            repository.deleteRoutine(routine.id)
        }
    }

    /**
     * Deletes several routines at once, from the list's multi-select mode.
     *
     * Undo is only offered for a single deletion: restoring a batch would need
     * to re-insert each routine's tasks too, and the tasks are cascade-deleted,
     * so a partial restore would be misleading. The caller confirms the batch
     * first, so this is a deliberate, irreversible action.
     */
    fun deleteMany(routines: List<Routine>) {
        if (routines.isEmpty()) return
        // A batch delete supersedes any pending single-delete undo.
        _deleted.value = null
        viewModelScope.launch {
            routines.forEach {
                // Same guard as a single delete: only the routine that is
                // actually running is stopped, and only before its row goes.
                runStopper.stopIfRunningFor(it.id)
                repository.deleteRoutine(it.id)
            }
        }
    }

    /**
     * Restores the last deleted routine. The routine is re-inserted with its
     * original id so any undo of a subsequent edit still lines up; its tasks
     * were cascade-deleted, so the routine comes back empty.
     */
    fun undoDelete() {
        val pending = _deleted.value ?: return
        _deleted.value = null
        viewModelScope.launch {
            repository.saveRoutine(pending.routine)
        }
    }

    fun dismissUndo() {
        _deleted.value = null
    }

    /**
     * Creates a new routine with a sensible default: a name, a 7:00 start and
     * every day active. Returns the new id via [onCreated].
     */
    fun createRoutine(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val routine = Routine(
                name = "New routine",
                startMinuteOfDay = 7 * 60,
                daysOfWeek = DaysOfWeek.ALL,
                enabled = true,
            )
            val id = repository.saveRoutine(routine)
            onCreated(id)
        }
    }

    /**
     * Creates a routine together with the tasks staged in the creation sheet.
     *
     * The routine and its tasks are written in one transaction, so a failure
     * cannot leave an empty routine behind. Task positions are taken from the
     * order the user arranged in the sheet.
     *
     * @param routine the routine to insert.
     * @param tasks the staged tasks, in display order.
     * @param onCreated called with the new routine id once the write succeeds.
     */
    fun createRoutineWithTasks(
        routine: Routine,
        tasks: List<PlacedTask>,
        onCreated: (Long) -> Unit,
    ) {
        viewModelScope.launch {
            // The repository renumbers positions from list order, so the order
            // the user arranged in the sheet is what gets persisted.
            val id = repository.insertRoutineWithTasks(routine, tasks)
            onCreated(id)
        }
    }
}
