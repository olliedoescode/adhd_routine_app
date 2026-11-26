package com.adhd.routineplanner.viewmodel

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adhd.routineplanner.alarm.AlarmScheduler
import com.adhd.routineplanner.alarm.RunNotification
import com.adhd.routineplanner.widget.RunWidgetUpdater
import com.adhd.routineplanner.data.RoutineRepository
import com.adhd.routineplanner.data.RunSession
import com.adhd.routineplanner.data.RunSessionStore
import com.adhd.routineplanner.domain.AlarmSchedule
import com.adhd.routineplanner.domain.Buffer
import com.adhd.routineplanner.domain.Phase
import com.adhd.routineplanner.domain.PlacedTask
import com.adhd.routineplanner.domain.Routine
import com.adhd.routineplanner.domain.RunState
import com.adhd.routineplanner.domain.runState
import com.adhd.routineplanner.ui.plan.GridTask
import com.adhd.routineplanner.ui.plan.toGridTasks
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Everything the run screen needs to draw, in one immutable snapshot
 * (spec sections 8, 15.8, 16).
 *
 * [state] is derived from the wall clock, never stored, so a killed process
 * resumes correctly and a clock change self-corrects on the next tick.
 */
data class RunUiState(
    val routine: Routine? = null,
    val tasks: List<GridTask> = emptyList(),
    val session: RunSession? = null,
    val state: RunState = RunState(
        elapsedSeconds = 0,
        currentIndex = -1,
        secondsRemaining = 0,
        progress = 0f,
        phase = Phase.BeforeStart,
    ),
    val loading: Boolean = true,
) {
    /**
     * Each task's rendered length in minutes, buffer included, in task order.
     * Falls back to the raw minimums before the routine has loaded.
     */
    val renderedMinutes: List<Int>
        get() = routine?.let { r ->
            Buffer.layout(tasks.map { it.toPlacedTask() }, r.capacityMinutes, r.useBuffer)
                .slots.map { it.minutes }
        } ?: tasks.map { it.minutes }

    /**
     * Total routine duration in minutes, including the buffer share. This is
     * the rendered total, so the grid, the cursor and the countdown all agree
     * on where the routine ends.
     */
    val totalMinutes: Int get() = renderedMinutes.sum()

    /** The task currently running, or `null` before start / after finish. */
    val currentTask: GridTask?
        get() = tasks.getOrNull(state.currentIndex)

    /**
     * The current task's rendered length, buffer included. This is what the
     * countdown is actually counting down, so it is what should be spoken.
     */
    val currentTaskMinutes: Int
        get() = renderedMinutes.getOrNull(state.currentIndex)
            ?: currentTask?.minutes
            ?: 0

    /**
     * The task that follows the current one, or `null` at the end.
     *
     * Before the start `currentIndex` is `-1`, so a naive `+1` would return the
     * *first* task and label it "next" while it is also the one about to run.
     * Before the start the "next" task is therefore the first task, and once
     * finished there is none.
     */
    val nextTask: GridTask?
        get() = when (state.phase) {
            Phase.BeforeStart -> tasks.firstOrNull()
            Phase.Finished -> null
            Phase.Running -> tasks.getOrNull(state.currentIndex + 1)
        }

    /** True once the routine has finished. */
    val isFinished: Boolean get() = state.phase == Phase.Finished

    /** True before the scheduled start. */
    val isBeforeStart: Boolean get() = state.phase == Phase.BeforeStart
}

/**
 * Drives the run screen (spec sections 8, 16).
 *
 * Combines the persisted [RunSession], the routine's task list and a
 * once-per-second wall-clock flow, and derives everything through
 * [runState]. The clock is a `Flow` here rather than `produceState` so the
 * derivation lives in one place and is testable; the screen still reads the
 * value inside the draw scope so only the Canvas redraws each second
 * (spec section 18).
 */
@HiltViewModel
class RunViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: RoutineRepository,
    private val sessionStore: RunSessionStore,
    private val alarmScheduler: AlarmScheduler,
    private val widgetUpdater: RunWidgetUpdater,
    /**
     * Application-scoped coroutine scope, used for work that must outlive this
     * screen (clearing the session when the user stops a run and the screen is
     * popped immediately afterwards).
     */
    private val appScope: CoroutineScope,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val routineId: Long = savedStateHandle.get<Long>(ARG_ROUTINE_ID) ?: 0L

    /** Wall clock, recomputed every second (spec section 8.1). */
    private val clock: Flow<Long> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(now)
            delay(1000L - now % 1000L)
        }
    }

    /**
     * Ensures a session exists for this routine. A manual Run (no session yet,
     * or a session for a different routine) starts now; a scheduled run keeps
     * the start time already stored by the alarm flow.
     */
    init {
        viewModelScope.launch {
            val existing = sessionStore.session.firstOrNull()
            if (existing == null || existing.routineId != routineId) {
                sessionStore.start(routineId, System.currentTimeMillis())
            }
        }
    }

    val uiState: StateFlow<RunUiState> = combine(
        repository.observeRoutine(routineId),
        repository.observeTasks(routineId),
        sessionStore.session,
        clock,
    ) { routine, tasks, session, nowMs ->
        val gridTasks = tasks.toGridTasks()
        val startMs = session?.takeIf { it.routineId == routineId }?.startMs
        val state = if (startMs != null && routine != null) {
            runState(routine, tasks, startMs, nowMs)
        } else {
            RunState(0, -1, 0, 0f, Phase.BeforeStart)
        }
        RunUiState(
            routine = routine,
            tasks = gridTasks,
            session = session,
            state = state,
            loading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RunUiState(),
    )

    /**
     * Keeps the ongoing notification in step with the live run (spec §15.8).
     *
     * Only the fields the notification actually displays are compared, so the
     * once-per-second clock tick does not re-post it: the body changes only
     * when the current task or the whole-minute countdown changes.
     */
    init {
        viewModelScope.launch {
            uiState
                .map { state ->
                    val routine = state.routine
                    when {
                        // No routine, or a session for a different routine: the
                        // notification does not belong to this screen.
                        routine == null || state.session?.routineId != routineId -> null
                        // The run has finished: drop the ongoing notification
                        // rather than leaving a stale "Routine finished" card
                        // pinned in the shade forever.
                        state.isFinished -> null
                        else -> NotificationSnapshot(
                            routineId = routine.id,
                            routineName = routine.name,
                            taskName = state.currentTask?.name,
                            state = state.state,
                            nextTaskName = state.nextTask?.name,
                        )
                    }
                }
                .distinctUntilChanged()
                .collect { snapshot ->
                    if (snapshot == null) {
                        RunNotification.cancel(appContext)
                    } else {
                        // `takeover = false` on purpose. This collector runs
                        // every second, and re-posting the notification with a
                        // full-screen intent would be an *update* — Android does
                        // not re-fire a full-screen intent for an update, so it
                        // would achieve nothing while racing the deliberate
                        // cancel-then-post in RunNotification.repostForTakeover
                        // (which is what actually launches the takeover on each
                        // screen wake). The takeover intent is attached only by
                        // that re-post and by the initial RunStarter.begin post.
                        RunNotification.show(
                            context = appContext,
                            routineId = snapshot.routineId,
                            routineName = snapshot.routineName,
                            taskName = snapshot.taskName,
                            state = snapshot.state,
                            nextTaskName = snapshot.nextTaskName,
                            takeover = false,
                        )
                    }
                    // Keep the lock-screen widget in step with the same
                    // snapshot: it flips to the new task at each boundary and
                    // clears when the run ends.
                    widgetUpdater.refresh()
                }
        }
    }

    /**
     * Start the run now (manual Run). Used when the screen is opened from the
     * plan screen rather than from a scheduled alarm.
     */
    fun startNow() {
        viewModelScope.launch {
            sessionStore.start(routineId, System.currentTimeMillis())
        }
    }

    /**
     * Start the run at today's occurrence of the routine's start time, so a
     * scheduled run shows the correct elapsed time even if opened late
     * (spec section 16).
     */
    fun startScheduled() {
        val routine = uiState.value.routine ?: return
        val start = AlarmSchedule.nextRoutineStart(routine, System.currentTimeMillis())
            ?: System.currentTimeMillis()
        viewModelScope.launch { sessionStore.start(routineId, start) }
    }

    /**
     * Stop the run, clear the session and cancel the routine's remaining
     * alarms (spec section 15.8 Exit).
     *
     * The session is cleared on an application-scoped coroutine rather than
     * [viewModelScope]: the caller pops the run screen straight after calling
     * this, which clears the view model and cancels its scope. Clearing on that
     * scope raced the pop and often never ran, leaving the session behind so
     * the app immediately reopened the run screen — the "Stop run does
     * nothing" bug. The app scope outlives the screen, so the clear always
     * lands.
     */
    fun stop() {
        alarmScheduler.cancel(routineId)
        // Cancel the in-run task alarms too, so stopping a run does not leave
        // its per-task alarms armed to fire later.
        alarmScheduler.cancelRunAlarms(routineId)
        // `cancel` also clears the takeover guards, so a later run is not
        // blocked from posting its notification.
        RunNotification.cancel(appContext)
        appScope.launch {
            // Clearing the session also removes the presence flag, so the next
            // run starts in the "not present" state and its first alarm takes
            // over the lock screen.
            sessionStore.clear()
            // The notification collector above does not see this clear (the
            // screen is being popped), so refresh the widget explicitly to flip
            // it back to "No routine running".
            widgetUpdater.refresh()
        }
    }

    /**
     * The subset of the run state the ongoing notification displays. Comparing
     * this rather than the whole [RunUiState] keeps the notification from being
     * re-posted on every clock tick.
     */
    private data class NotificationSnapshot(
        val routineId: Long,
        val routineName: String,
        val taskName: String?,
        val state: RunState,
        val nextTaskName: String?,
    )

    companion object {
        const val ARG_ROUTINE_ID: String = "routineId"
    }
}
