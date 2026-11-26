package com.adhd.routineplanner.viewmodel

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adhd.routineplanner.alarm.RunStarter
import com.adhd.routineplanner.data.RoutineRepository
import com.adhd.routineplanner.data.RunSession
import com.adhd.routineplanner.data.RunSessionStore
import com.adhd.routineplanner.data.StockTaskRepository
import com.adhd.routineplanner.domain.Buffer
import com.adhd.routineplanner.domain.Drag
import com.adhd.routineplanner.domain.Durations
import com.adhd.routineplanner.domain.Grid
import com.adhd.routineplanner.domain.PlacedTask
import com.adhd.routineplanner.domain.Reorder
import com.adhd.routineplanner.domain.Routine
import com.adhd.routineplanner.domain.Schedule
import com.adhd.routineplanner.domain.StockTask
import com.adhd.routineplanner.ui.plan.GridTask
import com.adhd.routineplanner.ui.plan.toGridTasks
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The transient interaction state machine (spec §17).
 *
 * Phase 5 only needs [Idle], [Scrolling] and [Resizing]; [DraggingTask] is
 * filled in by Phase 6.
 */
sealed interface GridGesture {
    /** Nothing is being touched. */
    data object Idle : GridGesture

    /** A vertical scroll is in progress. */
    data object Scrolling : GridGesture

    /** A pinch is in progress. */
    data object Zooming : GridGesture

    /** A long-press drag of a placed task is in progress. */
    data class DraggingTask(val taskId: Long) : GridGesture

    /** The resize handle of [taskId] is being dragged. */
    data class Resizing(val taskId: Long) : GridGesture
}

/**
 * A pending, uncommitted edit. Previews never touch Room or the alarm
 * scheduler (spec §16); they are folded into the task list for drawing only.
 */
data class PlanPreview(
    /** Task whose duration is being changed, or `null`. */
    val resizeTaskId: Long? = null,
    /** Duration in minutes to draw instead of the stored one. */
    val resizeMinutes: Int? = null,
    /** Task being dragged, or `null` (Phase 6). */
    val dragTaskId: Long? = null,
    /** Minute index the dragged task would be inserted at (Phase 6). */
    val dragInsertionMinute: Int? = null,
    /**
     * Slot index (into the list with the dragged task removed) the block is
     * currently clipped into. Held across moves so the ghost only flips slots
     * when the finger clearly crosses a midpoint (Phase 6).
     */
    val dragSlot: Int? = null,
    /** True while the finger is over the tray, meaning "remove" (Phase 6). */
    val overTray: Boolean = false,
) {
    val isEmpty: Boolean
        get() = resizeTaskId == null && dragTaskId == null

    /** True while a task is in flight. */
    val isDragging: Boolean
        get() = dragTaskId != null
}

/** One undoable step (spec §16). */
data class UndoSnapshot(
    val label: String,
    val tasks: List<PlacedTask>,
)

/** Everything the plan screen needs to draw, in one immutable snapshot. */
data class PlanUiState(
    val routine: Routine? = null,
    val tasks: List<GridTask> = emptyList(),
    val cols: Int = Grid.DEFAULT_COLS,
    val selectedId: Long? = null,
    val preview: PlanPreview = PlanPreview(),
    val gesture: GridGesture = GridGesture.Idle,
    val undo: UndoSnapshot? = null,
    val loading: Boolean = true,
    /** True while this routine has an active run; editing is disabled. */
    val runActive: Boolean = false,
) {
    /**
     * The task list with any pending edit folded in, for drawing.
     *
     * A resize replaces the duration in place. A drag removes the dragged task
     * from the list (so later tasks slide forward) and reinserts a ghost at the
     * hovered slot. The committed list is untouched.
     */
    val displayTasks: List<GridTask>
        get() {
            val resizeId = preview.resizeTaskId
            val resizeMinutes = preview.resizeMinutes
            if (resizeId != null && resizeMinutes != null) {
                return tasks.map { if (it.id == resizeId) it.copy(minutes = resizeMinutes) else it }
            }
            val dragId = preview.dragTaskId
            if (dragId != null) {
                val dragged = tasks.firstOrNull { it.id == dragId } ?: return tasks
                val remaining = tasks.filterNot { it.id == dragId }
                // Prefer the held slot so the block stays clipped in place;
                // fall back to the minute-derived index for the first frame.
                val index = (preview.dragSlot
                    ?: Reorder.insertionIndex(
                        remaining.map { it.toPlacedTask() },
                        preview.dragInsertionMinute ?: 0,
                    )).coerceIn(0, remaining.size)
                val liveGhost = dragged.copy(
                    id = DRAG_GHOST_ID,
                    position = index,
                )
                return remaining.toMutableList().also { it.add(index, liveGhost) }
            }
            return tasks
        }

    /** The task being dragged, for the floating ghost overlay (spec §15.5). */
    val dragGhostTask: GridTask?
        get() = preview.dragTaskId?.let { id -> tasks.firstOrNull { it.id == id } }

    /** Sum of the *minimum* durations, i.e. the time the tasks claim. */
    val totalMinutes: Int get() = tasks.sumOf { it.minutes }

    /** The routine's fixed capacity in minutes. */
    val capacityMinutes: Int get() = routine?.capacityMinutes ?: 0

    /** Spare minutes distributed across the tasks as buffer. */
    val bufferMinutes: Int get() = (capacityMinutes - totalMinutes).coerceAtLeast(0)

    /** Minutes by which the tasks exceed the routine window. */
    val overrunMinutes: Int get() = (totalMinutes - capacityMinutes).coerceAtLeast(0)

    /** The declared end of the routine, in minutes past midnight. */
    val endMinuteOfDay: Int get() = routine?.endMinuteOfDay ?: 0

    /** The selected task, if any. */
    val selectedTask: GridTask?
        get() = selectedId?.let { id -> tasks.firstOrNull { it.id == id } }

    /**
     * The longest duration a task may be given without over-subscribing the
     * routine, given the other tasks' minimums.
     */
    fun maxDurationFor(taskId: Long?): Int {
        val others = tasks.filter { it.id != taskId }.map { it.minutes }
        return Buffer.maxDurationFor(capacityMinutes, others)
    }
}

/**
 * Drives the plan screen (spec §16).
 *
 * All edits follow the same pipeline: snapshot for undo, write once through
 * [RoutineRepository.commitTasks] (which renumbers and reschedules alarms in a
 * single transaction), then clear the preview. Previews are pure UI state.
 */
@HiltViewModel
class PlanViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: RoutineRepository,
    private val sessionStore: RunSessionStore,
    stockTaskRepository: StockTaskRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val routineId: Long = savedStateHandle.get<Long>(ARG_ROUTINE_ID) ?: 0L

    /**
     * The user's reusable tasks, offered as one-tap presets in the new-task
     * dialog so a routine can be built from the usual steps quickly.
     */
    val stockTasks: StateFlow<List<StockTask>> = stockTaskRepository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val cols = MutableStateFlow(Grid.DEFAULT_COLS)
    private val selectedId = MutableStateFlow<Long?>(null)
    private val preview = MutableStateFlow(PlanPreview())
    private val gesture = MutableStateFlow<GridGesture>(GridGesture.Idle)
    private val undo = MutableStateFlow<UndoSnapshot?>(null)

    /** Latest committed task list, used to build undo snapshots. */
    private var lastCommitted: List<PlacedTask> = emptyList()

    // Eight flows exceeds the typed `combine` overloads, so the vararg form is
    // used and the array is destructured by position. The vararg overload
    // produces `Array<Any?>`; every element is cast explicitly below.
    private val inputs: Flow<Array<Any?>> = combine(
        repository.observeRoutine(routineId),
        repository.observeTasks(routineId),
        cols,
        selectedId,
        preview,
        gesture,
        undo,
        sessionStore.session,
    ) { values -> values }

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<PlanUiState> = inputs.map { values ->
        val routine = values[0] as Routine?
        val tasks = values[1] as List<PlacedTask>
        lastCommitted = tasks
        val session = values[7] as RunSession?
        PlanUiState(
            routine = routine,
            tasks = tasks.toGridTasks(),
            cols = values[2] as Int,
            selectedId = values[3] as Long?,
            preview = values[4] as PlanPreview,
            gesture = values[5] as GridGesture,
            undo = values[6] as UndoSnapshot?,
            loading = false,
            runActive = session?.routineId == routineId,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlanUiState(),
    )

    // -----------------------------------------------------------------------
    // Zoom (Phase 4)
    // -----------------------------------------------------------------------

    /** Index into [Grid.ZOOM_STEPS] for the current column count. */
    private fun stepIndexFor(colsValue: Int): Int {
        val index = Grid.ZOOM_STEPS.indexOf(colsValue)
        return if (index >= 0) index else 0
    }

    /** Step one level in (fewer columns, bigger cells). */
    fun zoomIn() {
        val next = (stepIndexFor(cols.value) + 1).coerceAtMost(Grid.ZOOM_STEPS.lastIndex)
        cols.value = Grid.ZOOM_STEPS[next]
    }

    /** Step one level out (more columns, smaller cells). */
    fun zoomOut() {
        val next = (stepIndexFor(cols.value) - 1).coerceAtLeast(0)
        cols.value = Grid.ZOOM_STEPS[next]
    }

    /**
     * Consume an accumulated pinch scale, stepping as many levels as it
     * contains and returning the leftover scale. This lets one continuous
     * spread/pinch cross several zoom levels without lifting the fingers.
     *
     * @return the leftover scale, or `null` when no step was taken.
     */
    fun consumePinch(accumulatedScale: Float): Float? {
        val current = stepIndexFor(cols.value)
        val result = Grid.consumePinch(current, accumulatedScale)
        if (result.steps == 0) return null
        cols.value = Grid.ZOOM_STEPS[result.step]
        return result.remainingScale
    }

    // -----------------------------------------------------------------------
    // Selection (Phase 5)
    // -----------------------------------------------------------------------

    fun select(id: Long?) {
        selectedId.value = id
        if (id == null) clearPreview()
    }

    /**
     * Persist edited routine settings (name, start time, end time, days,
     * enabled). The repository reschedules alarms for the routine (spec §9).
     */
    fun saveRoutine(routine: Routine) {
        if (editingLocked) return
        viewModelScope.launch { repository.saveRoutine(routine) }
    }

    /**
     * Start this routine right now as a test run.
     *
     * This goes through [RunStarter.beginNow], the same path a scheduled alarm
     * uses, so a manual run exercises every real feature: the session, the
     * ongoing lock-screen notification, the accountability takeover (when the
     * screen is off) and the start vibration. Previously the Run button only
     * wrote the session, so none of those happened.
     */
    fun startRunNow() {
        if (routineId <= 0L) return
        viewModelScope.launch { RunStarter.beginNow(appContext, routineId) }
    }

    fun clearSelection() = select(null)

    // -----------------------------------------------------------------------
    // Task editing (Phase 5)
    // -----------------------------------------------------------------------

    /**
     * True while this routine is running; all edits are refused so the run
     * cannot be invalidated mid-flight (spec section 8.7).
     */
    private val editingLocked: Boolean get() = uiState.value.runActive

    /**
     * Set a task's duration to an exact minute count and commit it. Used by the
     * task editor's time picker, which lets the user choose any h/m/s value
     * rather than stepping the ladder.
     *
     * The duration is a *minimum*: the buffer is redistributed on top of it, so
     * the value is clamped only to at least 1 minute. The UI is responsible for
     * warning when the total exceeds the routine window.
     *
     * @param minutes the new duration in whole minutes (clamped to at least 1).
     */
    fun setTaskDuration(taskId: Long, minutes: Int) {
        if (editingLocked) return
        val current = lastCommitted.firstOrNull { it.id == taskId } ?: return
        val next = minutes.coerceAtLeast(1)
        if (next == current.minutes) return
        val updated = lastCommitted.map { if (it.id == taskId) it.copy(minutes = next) else it }
        commit(updated, label = "Resize")
    }

    /**
     * Nudge a task's duration by one rung of the duration ladder. This is the
     * button equivalent of the resize gesture (spec §18).
     *
     * @param taskId the task to resize.
     * @param steps positive to lengthen, negative to shorten.
     */
    fun stepTaskDuration(taskId: Long, steps: Int) {
        if (editingLocked) return
        val current = lastCommitted.firstOrNull { it.id == taskId } ?: return
        val next = Durations.shift(current.minutes, steps)
        if (next == current.minutes) return
        val updated = lastCommitted.map { if (it.id == taskId) it.copy(minutes = next) else it }
        commit(updated, label = "Resize")
    }

    /**
     * Move a task one slot earlier or later in the routine. This is the button
     * equivalent of the drag gesture (spec §18).
     *
     * @param taskId the task to move.
     * @param delta -1 for earlier, +1 for later.
     */
    fun moveTask(taskId: Long, delta: Int) {
        if (editingLocked) return
        val from = lastCommitted.indexOfFirst { it.id == taskId }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, lastCommitted.lastIndex)
        if (to == from) return
        commit(Reorder.move(lastCommitted, from, to), label = "Move")
    }

    /**
     * Rename a placed task. The name lives on the task itself now, so this
     * edits only this occurrence.
     *
     * @param taskId the task to rename.
     * @param name the new name; blank names are ignored.
     */
    fun renameTask(taskId: Long, name: String) {
        if (editingLocked) return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val task = lastCommitted.firstOrNull { it.id == taskId } ?: return
        if (task.name == trimmed) return
        val updated = lastCommitted.map { if (it.id == taskId) it.copy(name = trimmed) else it }
        commit(updated, label = "Rename")
    }

    /**
     * Recolour a placed task. Like the name, the colour lives on the task.
     *
     * @param taskId the task to recolour.
     * @param color the new ARGB colour int.
     */
    fun setTaskColor(taskId: Long, color: Int) {
        if (editingLocked) return
        val task = lastCommitted.firstOrNull { it.id == taskId } ?: return
        if (task.color == color) return
        val updated = lastCommitted.map { if (it.id == taskId) it.copy(color = color) else it }
        commit(updated, label = "Recolour")
    }

    /**
     * Turn this task's alarm on or off. Alarms are per task, so any task in the
     * routine may have one (spec §9).
     */
    fun setTaskAlarm(taskId: Long, enabled: Boolean) {
        if (editingLocked) return
        val task = lastCommitted.firstOrNull { it.id == taskId } ?: return
        if (task.alarmEnabled == enabled) return
        val updated =
            lastCommitted.map { if (it.id == taskId) it.copy(alarmEnabled = enabled) else it }
        commit(updated, label = if (enabled) "Alarm on" else "Alarm off")
    }

    /**
     * Append a brand-new task to the end of the routine.
     *
     * @param name the task's name.
     * @param minutes the task's minimum duration.
     * @param color the task's ARGB colour.
     * @param alarmEnabled whether this task should raise an alarm.
     */
    fun createTask(
        name: String,
        minutes: Int,
        color: Int,
        alarmEnabled: Boolean = false,
    ) {
        if (editingLocked) return
        val trimmed = name.trim().ifEmpty { PlacedTask.DEFAULT_NAME }
        val newTask = PlacedTask(
            id = 0,
            minutes = minutes.coerceAtLeast(1),
            position = lastCommitted.size,
            name = trimmed,
            color = color,
            alarmEnabled = alarmEnabled,
        )
        commit(lastCommitted + newTask, label = "Add $trimmed")
    }

    // -----------------------------------------------------------------------
    // Drag and drop (Phase 6)
    // -----------------------------------------------------------------------

    /**
     * Begin dragging an already-placed task (spec §15.5). The task stays in the
     * committed list; the preview only records which task is in flight and the
     * minute it currently hovers, so the grid can draw the ghost and marker.
     */
    fun startTaskDrag(taskId: Long) {
        if (editingLocked) return
        if (lastCommitted.none { it.id == taskId }) return
        selectedId.value = null
        gesture.value = GridGesture.DraggingTask(taskId)
        val start = Schedule.startMinutes(
            uiState.value.routine ?: return,
            lastCommitted,
        )
        val index = lastCommitted.indexOfFirst { it.id == taskId }
        val minute = if (index >= 0) start[index] else 0
        // Seed the slot at the task's own position so the block starts clipped
        // exactly where it was lifted from.
        preview.value = PlanPreview(
            dragTaskId = taskId,
            dragInsertionMinute = minute,
            dragSlot = index.coerceAtLeast(0),
        )
    }

    /**
     * Update the hovered minute while dragging. [minute] is `null` when the
     * finger is outside the grid; [overTray] is true when it is over the tray,
     * which means "remove".
     */
    fun dragMove(minute: Int?, overTray: Boolean) {
        val current = preview.value
        if (!current.isDragging) return
        if (minute == null) {
            // Outside the grid: keep the last slot, only update the tray flag.
            preview.value = current.copy(overTray = overTray)
            return
        }
        val remaining = current.dragTaskId?.let { taskId ->
            lastCommitted.filterNot { it.id == taskId }
        } ?: lastCommitted
        val routine = uiState.value.routine ?: return
        // Resolve the slot with hysteresis so the ghost clips into place and
        // only flips when the finger clearly crosses a midpoint. Boundaries are
        // taken from the rendered timeline so they include the buffer.
        val slot = Drag.resolveSlot(routine, remaining, minute, current.dragSlot)
        val boundaries = Drag.boundariesOf(routine, remaining)
        val insertionMinute = boundaries[slot.coerceIn(0, boundaries.lastIndex)]
        preview.value = current.copy(
            dragInsertionMinute = insertionMinute,
            dragSlot = slot,
            overTray = overTray,
        )
    }

    /**
     * Drop the in-flight task (spec §15.5). Over the tray it is deleted;
     * anywhere else it is moved. A drop that changes nothing is a no-op and
     * shows no undo snackbar.
     */
    fun dropDrag() {
        val pending = preview.value
        val minute = pending.dragInsertionMinute
        val taskId = pending.dragTaskId

        if (taskId == null) {
            cancelDrag()
            return
        }
        if (pending.overTray) {
            deleteTask(taskId)
            return
        }
        val fromIndex = lastCommitted.indexOfFirst { it.id == taskId }
        if (fromIndex < 0) {
            cancelDrag()
            return
        }
        val remaining = lastCommitted.filterIndexed { i, _ -> i != fromIndex }
        // Drop exactly where the ghost was clipped: the held slot is an index
        // into the remaining list, which is what the preview drew.
        val target = (pending.dragSlot
            ?: minute?.let { Reorder.insertionIndex(remaining, it) }
            ?: fromIndex).coerceIn(0, remaining.size)
        val moved = remaining.toMutableList().also { it.add(target, lastCommitted[fromIndex]) }
        if (moved.map { it.id } == lastCommitted.map { it.id }) {
            cancelDrag()
            return
        }
        commit(moved, label = "Move")
    }

    /** Abandon the in-flight drag without writing anything (spec §15.5). */
    fun cancelDrag() {
        if (!preview.value.isDragging) return
        clearPreview()
    }

    /** Remove a placed task, with undo (spec §15.5, §14.7). */
    fun deleteTask(taskId: Long) {
        val index = lastCommitted.indexOfFirst { it.id == taskId }
        if (index < 0) {
            clearPreview()
            return
        }
        if (selectedId.value == taskId) selectedId.value = null
        commit(Reorder.remove(lastCommitted, index), label = "Delete")
    }

    // -----------------------------------------------------------------------
    // Undo (Phase 5)
    // -----------------------------------------------------------------------

    fun undoLast() {
        val snapshot = undo.value ?: return
        undo.value = null
        viewModelScope.launch {
            repository.commitTasks(routineId, snapshot.tasks)
        }
    }

    fun dismissUndo() {
        undo.value = null
    }

    // -----------------------------------------------------------------------
    // Shared commit pipeline (spec §16)
    // -----------------------------------------------------------------------

    private fun clearPreview() {
        preview.value = PlanPreview()
        gesture.value = GridGesture.Idle
    }

    /**
     * Snapshot for undo, then write once. [RoutineRepository.commitTasks]
     * renumbers positions and reschedules alarms inside one transaction.
     */
    private fun commit(tasks: List<PlacedTask>, label: String) {
        val snapshot = lastCommitted
        undo.value = UndoSnapshot(label = label, tasks = snapshot)
        clearPreview()
        viewModelScope.launch {
            repository.commitTasks(routineId, tasks)
        }
    }

    companion object {
        const val ARG_ROUTINE_ID: String = "routineId"
    }
}

/**
 * Synthetic id for the preview task drawn while a task is dragged. Never
 * persisted; real ids are positive.
 *
 * Declared at file level because [PlanUiState] (a separate top-level class in
 * this file) needs it, and a companion object's members are not in scope there.
 */
const val DRAG_GHOST_ID: Long = -1L
