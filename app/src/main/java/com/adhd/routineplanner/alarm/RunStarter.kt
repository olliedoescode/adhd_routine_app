package com.adhd.routineplanner.alarm

import android.content.Context
import android.content.Intent
import android.util.Log
import com.adhd.routineplanner.data.AlarmSettings
import com.adhd.routineplanner.data.AlarmSettingsStore
import com.adhd.routineplanner.data.RoutineRepository
import com.adhd.routineplanner.data.RunSessionStore
import com.adhd.routineplanner.domain.AlarmSchedule
import com.adhd.routineplanner.domain.Phase
import com.adhd.routineplanner.domain.PlacedTask
import com.adhd.routineplanner.domain.Routine
import com.adhd.routineplanner.domain.runState
import com.adhd.routineplanner.widget.RunWidgetUpdater
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.firstOrNull

/**
 * Starts a routine run *for real*: records the session, posts the ongoing
 * notification (with the lock-screen takeover full-screen intent when the
 * setting is on), refreshes the widget and buzzes the phone.
 *
 * This exists so a run started by hand from the plan screen behaves exactly
 * like a run started by the routine's start alarm. Previously the manual Run
 * button only wrote the session, so the notification, the takeover and the
 * vibration never happened — which made the app impossible to test without
 * waiting for a real scheduled alarm.
 *
 * The alarm path ([AlarmReceiver.beginRun]) and the manual path
 * ([com.adhd.routineplanner.viewmodel.PlanViewModel.startRunNow]) both go
 * through here, so there is one definition of "a run has begun".
 */
object RunStarter {

    private const val TAG = "RunStarter"

    /**
     * Begins a run of [routineId] starting at [startMs].
     *
     * @param startMs the wall-clock time the run is considered to have started.
     *   A scheduled run passes the routine's scheduled start (so a late alarm
     *   still shows the correct elapsed time); a manual/test run passes "now".
     * @param vibrate whether to buzz the phone. A scheduled start buzzes; the
     *   manual path also buzzes so the user can feel that it worked.
     * @param takeoverNow whether to launch the lock-screen takeover *directly*
     *   instead of relying on the notification's full-screen intent. This is
     *   for the manual Run button: **Android does not fire a full-screen intent
     *   while the app is already in the foreground**, so a run started from the
     *   plan screen never showed the takeover even though the notification
     *   carried one. Launching the activity directly works because the app is
     *   visible, so the background-activity-start restriction does not apply.
     *   A scheduled run leaves this `false` and uses the full-screen intent,
     *   which is the only path that works from the background.
     * @return `true` when the run was started, `false` when the routine no
     *   longer exists or has no tasks.
     */
    suspend fun begin(
        context: Context,
        routineId: Long,
        startMs: Long,
        vibrate: Boolean = true,
        takeoverNow: Boolean = false,
    ): Boolean {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            RunStarterEntryPoint::class.java,
        )
        val repository = entryPoint.routineRepository()
        val sessionStore = entryPoint.runSessionStore()
        val settingsStore = entryPoint.alarmSettingsStore()
        val widgetUpdater = entryPoint.runWidgetUpdater()
        val scheduler = entryPoint.alarmScheduler()

        val routine: Routine = repository.getRoutine(routineId) ?: run {
            Log.w(TAG, "Cannot start run: routine $routineId no longer exists")
            return false
        }
        val tasks: List<PlacedTask> = repository.getTasks(routineId)
        if (tasks.isEmpty()) {
            Log.w(TAG, "Cannot start run: routine $routineId has no tasks")
            return false
        }

        // Starting a run resets the presence flag (inside `start`), so the first
        // alarm or screen-on of this run always takes over the lock screen.
        sessionStore.start(routine.id, startMs)

        // Register the in-run task alarms relative to the actual session start.
        // This is what makes a *test run* raise alarms: the recurring schedule
        // only knows about the next calendar occurrence, which for a run started
        // "now" is usually tomorrow. Both the scheduled and the manual path go
        // through here, so both get the same alarms.
        scheduler.scheduleRunAlarms(routine.id, startMs)

        val now = System.currentTimeMillis()
        val state = runState(routine, tasks, startMs, now)
        val current = tasks.getOrNull(state.currentIndex)
        // Before the start `currentIndex` is -1, so a naive `+1` would name the
        // first task as "next" while it is also the one about to run.
        val next = when (state.phase) {
            Phase.BeforeStart -> null
            Phase.Finished -> null
            Phase.Running -> tasks.getOrNull(state.currentIndex + 1)
        }

        val settings = settingsStore.settings.firstOrNull()
        val takeover = settings?.takeoverEnabled ?: true
        RunNotification.show(
            context = context,
            routineId = routine.id,
            routineName = routine.name,
            taskName = current?.let { resolveTaskName(it) },
            state = state,
            nextTaskName = next?.let { resolveTaskName(it) },
            takeover = takeover,
        )

        // The app is likely closed when a scheduled run begins, so refresh the
        // lock-screen widget here too rather than waiting for the run screen.
        widgetUpdater.refresh()

        if (vibrate) {
            val strength = settings?.taskVibrationStrength
                ?: AlarmSettings.DEFAULT_TASK_VIBRATION_STRENGTH
            Vibration.taskCue(context, strength)
        }

        // The manual path: the app is in the foreground, so the notification's
        // full-screen intent will not fire (Android suppresses full-screen
        // intents while the app is visible). Launch the takeover directly
        // instead, which is allowed precisely because the app is visible.
        if (takeoverNow && takeover) {
            launchTakeover(context)
        }
        return true
    }

    /**
     * Launches [RunTakeoverActivity] directly.
     *
     * Only safe from a foreground context (the manual Run button). From the
     * background this would be blocked by the Android 10+ background-activity
     * start restriction, which is why scheduled runs use the notification's
     * full-screen intent instead.
     */
    private fun launchTakeover(context: Context) {
        try {
            context.startActivity(
                RunTakeoverActivity.intent(context).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } catch (t: Throwable) {
            // A refused start must not fail the run itself; the notification is
            // already posted and the user can still open the run screen.
            Log.w(TAG, "Could not launch the takeover directly", t)
        }
    }

    /**
     * Begins a run of [routineId] starting now, for the manual Run button and
     * the "Test run" action.
     *
     * Unlike a scheduled start this always uses the current time, so the run
     * screen opens on the first task with a full countdown — which is what makes
     * it useful for testing the notification, the takeover and the vibration
     * without waiting for a real alarm.
     *
     * It passes `takeoverNow = true` so the lock-screen takeover actually
     * appears: a full-screen intent is not fired while the app is in the
     * foreground, so the manual path launches the takeover directly instead.
     */
    suspend fun beginNow(context: Context, routineId: Long): Boolean =
        begin(
            context = context,
            routineId = routineId,
            startMs = System.currentTimeMillis(),
            vibrate = true,
            takeoverNow = true,
        )

    /**
     * Begins a run of [routineId] at its scheduled start time, so a scheduled
     * run shows the correct elapsed time even if the alarm is delivered late.
     */
    suspend fun beginScheduled(context: Context, routineId: Long): Boolean {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            RunStarterEntryPoint::class.java,
        )
        val routine = entryPoint.routineRepository().getRoutine(routineId) ?: return false
        val now = System.currentTimeMillis()
        // Look back a minute so an alarm delivered slightly late still resolves
        // to the occurrence that just fired rather than the next day's.
        val startMs = AlarmSchedule.nextRoutineStart(routine, now - 60_000L) ?: now
        return begin(context, routineId, startMs, vibrate = true)
    }

    /** The task's own display name; tasks are self-contained now. */
    private fun resolveTaskName(task: PlacedTask): String =
        task.name.ifBlank { PlacedTask.DEFAULT_NAME }

    /**
     * The Hilt bindings [begin] needs.
     *
     * This object is not injectable (it is called from a broadcast receiver and
     * a view model), so the dependencies are pulled from the application
     * component on demand rather than held as fields.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RunStarterEntryPoint {
        fun routineRepository(): RoutineRepository
        fun runSessionStore(): RunSessionStore
        fun alarmSettingsStore(): AlarmSettingsStore
        fun runWidgetUpdater(): RunWidgetUpdater
        fun alarmScheduler(): AlarmScheduler
    }
}
