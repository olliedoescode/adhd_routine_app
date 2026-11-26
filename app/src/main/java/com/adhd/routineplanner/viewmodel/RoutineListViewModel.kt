package com.adhd.routineplanner.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adhd.routineplanner.data.RoutineRepository
import com.adhd.routineplanner.domain.DaysOfWeek
import com.adhd.routineplanner.domain.PlacedTask
import com.adhd.routineplanner.domain.Routine
import com.adhd.routineplanner.domain.RoutineDates
import com.adhd.routineplanner.domain.Schedule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    private val repository: RoutineRepository,
) : ViewModel() {

    /** A routine deleted from the list, held so the snackbar can restore it. */
    data class DeletedRoutine(val routine: Routine)

    private val _deleted = MutableStateFlow<DeletedRoutine?>(null)
    val deleted: StateFlow<DeletedRoutine?> = _deleted

    val routines: StateFlow<List<RoutineCardUi>> = combine(
        repository.observeRoutines(),
        repository.observeAllTasksByRoutine(),
    ) { routines, tasksByRoutine ->
        routines.map { routine ->
            val tasks = tasksByRoutine[routine.id].orEmpty()
            RoutineCardUi(
                routine = routine,
                totalMinutes = Schedule.totalMinutes(tasks),
                endMinuteOfDay = Schedule.endMinuteOfDay(routine),
                oneOffLabel = RoutineDates.oneOffLabel(routine),
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /** Enables or disables a routine; disabling cancels its alarms (spec §9). */
    fun setEnabled(routineId: Long, enabled: Boolean) {
        viewModelScope.launch { repository.setRoutineEnabled(routineId, enabled) }
    }

    /** Deletes a routine, keeping a copy for undo. */
    fun delete(routine: Routine) {
        _deleted.value = DeletedRoutine(routine)
        viewModelScope.launch { repository.deleteRoutine(routine.id) }
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
            routines.forEach { repository.deleteRoutine(it.id) }
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
