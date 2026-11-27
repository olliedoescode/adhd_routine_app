package com.adhd.routineplanner.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adhd.routineplanner.data.RoutineRepository
import com.adhd.routineplanner.data.StockTaskRepository
import com.adhd.routineplanner.domain.PlacedTask
import com.adhd.routineplanner.domain.Routine
import com.adhd.routineplanner.domain.StockTask
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the full-page routine creation screen (spec §15.6).
 *
 * Supplies the user's stock tasks for the add-task picker and writes the new
 * routine and its tasks in one transaction, so a failure cannot leave an empty
 * routine behind.
 */
@HiltViewModel
class RoutineCreateViewModel @Inject constructor(
    private val routineRepository: RoutineRepository,
    stockTaskRepository: StockTaskRepository,
) : ViewModel() {

    /** The stock tasks offered as presets in the add-task dialog. */
    val stockTasks: StateFlow<List<StockTask>> = stockTaskRepository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * The names of the routines that already exist.
     *
     * The create screen uses these to make a suggested name unique, so a second
     * morning routine becomes "Morning Routine #2" rather than colliding with
     * the first.
     */
    val existingNames: StateFlow<List<String>> = routineRepository.observeRoutines()
        .map { routines -> routines.map { it.name } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * True while a routine is being written.
     *
     * The create screen shows a full-screen progress overlay while this is set.
     * Inserting is a database round-trip that can take a moment on a cold start;
     * without feedback the Save tap looks ignored and invites a second tap.
     */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /**
     * Inserts [routine] with [tasks] in one transaction and reports the new id.
     *
     * Task positions are taken from the order the user arranged on the screen,
     * because the repository renumbers them from list order. Re-entrant calls
     * are ignored so a double tap cannot create two routines.
     */
    fun create(
        routine: Routine,
        tasks: List<PlacedTask>,
        onCreated: (Long) -> Unit,
    ) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                val id = routineRepository.insertRoutineWithTasks(routine, tasks)
                onCreated(id)
            } finally {
                // Cleared even on failure: on success the caller navigates away,
                // and on error the screen must not stay stuck behind the overlay.
                _saving.value = false
            }
        }
    }
}
