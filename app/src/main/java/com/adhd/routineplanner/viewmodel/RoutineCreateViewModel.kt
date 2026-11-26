package com.adhd.routineplanner.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adhd.routineplanner.data.RoutineRepository
import com.adhd.routineplanner.data.StockTaskRepository
import com.adhd.routineplanner.domain.PlacedTask
import com.adhd.routineplanner.domain.Routine
import com.adhd.routineplanner.domain.StockTask
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
     * Inserts [routine] with [tasks] in one transaction and reports the new id.
     *
     * Task positions are taken from the order the user arranged on the screen,
     * because the repository renumbers them from list order.
     */
    fun create(
        routine: Routine,
        tasks: List<PlacedTask>,
        onCreated: (Long) -> Unit,
    ) {
        viewModelScope.launch {
            val id = routineRepository.insertRoutineWithTasks(routine, tasks)
            onCreated(id)
        }
    }
}
