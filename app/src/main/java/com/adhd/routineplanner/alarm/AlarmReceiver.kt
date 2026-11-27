package com.adhd.routineplanner.alarm

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.adhd.routineplanner.data.AlarmSettings
import com.adhd.routineplanner.data.AlarmSettingsStore
import com.adhd.routineplanner.data.RoutineRepository
import com.adhd.routineplanner.data.RunSessionStore
import com.adhd.routineplanner.domain.Routine
import com.adhd.routineplanner.domain.RoutineDates
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fires when an alarm goes off (spec section 9).
 *
 * Two kinds of alarm share this receiver:
 *
 *  - **Routine start** (`taskIndex == -1`): begins the run session and posts
 *    the ongoing run notification. Silent apart from a short vibration.
 *  - **Task start**: every task boundary. If the task has its own alarm
 *    enabled, this posts a high-priority `CATEGORY_ALARM` notification with a
 *    full-screen intent that opens [AlarmActivity] (tone + vibration). If the
 *    task has no alarm, the phone just vibrates briefly so the user still feels
 *    the boundary without a full alarm.
 *
 * The receiver is `@AndroidEntryPoint` so it can inject the repository to look
 * up the routine and task names and to reschedule.
 */
@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {

    @Inject
    lateinit var repository: RoutineRepository

    @Inject
    lateinit var scheduler: AlarmScheduler

    @Inject
    lateinit var alarmSettingsStore: AlarmSettingsStore

    @Inject
    lateinit var runSessionStore: RunSessionStore

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DISMISS_TEST) {
            // Dismiss action on the test notification: just clear it.
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(TEST_NOTIFICATION_ID)
            return
        }

        val routineId = intent.getLongExtra(EXTRA_ROUTINE_ID, -1L)
        val taskIndex = intent.getIntExtra(EXTRA_TASK_INDEX, -1)
        if (routineId == AlarmManagerScheduler.TEST_ROUTINE_ID) {
            // Test alarm: post a generic notification and stop.
            postTestNotification(context)
            return
        }
        if (routineId < 0) return

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                handleAlarm(context, routineId, taskIndex)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to handle alarm for routine $routineId", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleAlarm(context: Context, routineId: Long, taskIndex: Int) {
        val routine = repository.getRoutine(routineId) ?: run {
            scheduler.cancel(routineId)
            return
        }
        val tasks = repository.getTasks(routineId)

        // The routine-start alarm (index -1) begins the run: it records the
        // session start time, schedules the in-run task alarms and posts the
        // ongoing notification. It is not a task alarm, so it does not ring.
        if (taskIndex == AlarmManagerScheduler.ROUTINE_START_TASK_INDEX) {
            beginRun(context, routine)
            rescheduleAfterFire(routine)
            return
        }

        val task = tasks.getOrNull(taskIndex)

        // A task boundary. A task with its own alarm rings the full alarm screen
        // (tone + vibration, over the lock screen). A task without an alarm just
        // buzzes briefly so the boundary is still felt.
        //
        // The alarm screen is launched *directly* rather than via a second
        // notification: the ongoing run notification is the single notification
        // for the run, and posting a separate CATEGORY_ALARM notification on top
        // of it was the "double notification" the user saw. The alarm screen is
        // the alert; the ongoing card is the readout.
        if (task != null && task.alarmEnabled) {
            launchAlarmScreen(context, routineId, taskIndex)
        } else {
            vibrateForTaskBoundary(context)
        }

        // Do *not* reschedule the recurring alarms here. During a run the in-run
        // alarms (registered by RunStarter.scheduleRunAlarms) are what fire the
        // remaining tasks; rescheduling the recurring schedule mid-run would
        // register tomorrow's alarms on top of them. The recurring schedule is
        // rebuilt when the run stops or when the routine is next edited.
        //
        // *Do* re-arm the remaining in-run alarms, though. This is the
        // self-healing step that makes alarms reliable: if a previous alarm was
        // dropped (Doze, an inexact fallback firing late, the process being
        // killed, or the phone being off at the trigger time), the remaining
        // tasks would otherwise never fire for the rest of the run. Re-running
        // scheduleRunAlarms from the live session start re-registers every
        // still-future task alarm, so one missed alarm no longer breaks the run.
        rearmRunAlarms(context, routineId, taskIndex + 1)
    }

    /**
     * Re-registers the in-run task alarms from the live session start.
     *
     * Only does anything while a run is actually active for [routineId]: the
     * session start is the anchor the in-run alarms are computed from, and
     * without it there is nothing to re-arm. [AlarmManagerScheduler.scheduleRunAlarms]
     * already skips tasks whose start is in the past, so re-running it is safe
     * and idempotent — it simply refreshes the alarms for the tasks still to
     * come.
     */
    private suspend fun rearmRunAlarms(
        context: Context,
        routineId: Long,
        firstTaskIndex: Int,
    ) {
        val session = runSessionStore.session.firstOrNull() ?: return
        if (session.routineId != routineId) return
        // Start the re-arm *after* the task that just fired. Re-registering that
        // task would pull its past trigger forward to `now + 3s` (the grace
        // window in scheduleRunAlarms) and make it ring again immediately — the
        // "audio and vibration repeat every 3 seconds" bug.
        scheduler.scheduleRunAlarms(routineId, session.startMs, firstTaskIndex)
    }

    /**
     * Opens [AlarmActivity] for a task alarm.
     *
     * A direct `startActivity` is used (rather than a notification's
     * full-screen intent) because the receiver is running as the result of an
     * exact alarm, which grants a temporary background-activity-start allowance
     * on modern Android. If the platform refuses it, the ongoing run
     * notification is still on screen, so the user is not left without a cue.
     */
    private fun launchAlarmScreen(context: Context, routineId: Long, taskIndex: Int) {
        try {
            context.startActivity(
                AlarmActivity.intent(context, routineId, taskIndex).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Could not launch the alarm screen directly", t)
        }
    }

    /**
     * Starts the run session for [routine] and posts the ongoing notification.
     *
     * Delegates to [RunStarter] so a scheduled start and a manual "test run"
     * from the plan screen go through exactly the same code path: session,
     * ongoing notification, lock-screen takeover and the start vibration.
     *
     * The session start is the routine's scheduled start time (not "now"), so
     * if the alarm is delivered a little late the run screen still shows the
     * correct elapsed time and current task.
     */
    private suspend fun beginRun(context: Context, routine: Routine) {
        RunStarter.beginScheduled(context, routine.id)
    }

    /**
     * Turns a one-off routine off once its date has passed, otherwise schedules
     * the next occurrence: the next task, or the next valid day's routine start
     * (spec section 9).
     */
    private suspend fun rescheduleAfterFire(routine: Routine) {
        // A one-off routine runs on a single date and then turns itself off, so
        // it never fires again. The date check keeps a routine that was already
        // rescheduled for a future date from being disabled early.
        if (RoutineDates.isOneOff(routine) && RoutineDates.hasExpired(routine)) {
            repository.setRoutineEnabled(routine.id, false)
            scheduler.cancel(routine.id)
            return
        }
        scheduler.rescheduleRoutine(routine.id)
    }

    /**
     * A 1.5 s solid buzz for a task boundary that has no alarm of its own, so
     * the user still notices the task changed without a full alarm going off.
     *
     * The strength comes from the user's "Task vibration strength" setting, so
     * the "next thing" cue can be tuned independently of a real alarm.
     */
    private suspend fun vibrateForTaskBoundary(context: Context) {
        val strength = alarmSettingsStore.settings.firstOrNull()?.taskVibrationStrength
            ?: AlarmSettings.DEFAULT_TASK_VIBRATION_STRENGTH
        Vibration.taskCue(context, strength)
    }

    /**
     * Posts the test alarm as a *real* alarm: a full-screen intent that launches
     * [AlarmActivity] (which turns the screen on, shows over the lock screen and
     * plays the alarm tone), plus a heads-up notification as the fallback when
     * the platform withholds full-screen intent permission.
     *
     * The test uses the sentinel routine id, so [AlarmActivity] shows generic
     * "Test alarm" copy instead of looking up a routine.
     */
    private fun postTestNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        NotificationChannels.ensureCreated(context)

        // The full-screen intent opens the alarm screen itself, so the test
        // behaves exactly like a real alarm rather than a silent notification.
        // It uses the *delayed* task index, distinct from the immediate preview
        // the scheduler already opened, so this launches a fresh AlarmActivity
        // instance (the activity is `singleInstance`) and plays its tone.
        val alarmScreen = AlarmActivity.intent(
            context,
            AlarmManagerScheduler.TEST_ROUTINE_ID,
            AlarmManagerScheduler.TEST_DELAYED_TASK_INDEX,
        )
        val fullScreenPi = PendingIntent.getActivity(
            context,
            AlarmManagerScheduler.TEST_ALARM_REQUEST_CODE,
            alarmScreen,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Belt and braces: explicitly start the alarm screen as well. A
        // full-screen intent is only honoured when the platform grants it and
        // the device is locked/idle; launching directly guarantees the user
        // actually sees and hears the delayed test alarm. A background start
        // restriction is swallowed so the notification below still posts.
        try {
            context.startActivity(alarmScreen)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not launch delayed test alarm screen directly", t)
        }
        val dismiss = PendingIntent.getBroadcast(
            context,
            AlarmManagerScheduler.TEST_ALARM_REQUEST_CODE + 1,
            Intent(context, AlarmReceiver::class.java).apply {
                // `setAction`, not `action =`: inside `apply` the name resolves
                // to the read-only `Intent.action` getter.
                setAction(ACTION_DISMISS_TEST)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationChannels.alarmBuilder(context)
            .setContentTitle("Test alarm")
            .setContentText("Alarms are working.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setCategory(android.app.Notification.CATEGORY_ALARM)
            .setPriority(android.app.Notification.PRIORITY_MAX)
            .setAutoCancel(true)
            .setContentIntent(fullScreenPi)
            .addAction(0, "Dismiss", dismiss)

        // Full-screen intent only when the platform allows it (Android 14+ can
        // revoke it); otherwise the heads-up notification is the fallback.
        if (canUseFullScreenIntent(context)) {
            builder.setFullScreenIntent(fullScreenPi, true)
        }

        manager.notify(TEST_NOTIFICATION_ID, builder.build())
    }

    private fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.canUseFullScreenIntent()
    }

    companion object {
        private const val TAG = "AlarmReceiver"

        const val EXTRA_ROUTINE_ID: String = "extra_routine_id"
        const val EXTRA_TASK_INDEX: String = "extra_task_index"

        /** Action for the test notification's Dismiss button. */
        const val ACTION_DISMISS_TEST: String = "com.adhd.routineplanner.DISMISS_TEST_ALARM"

        private const val TEST_NOTIFICATION_ID: Int = 0x7E57

        /** Builds the broadcast intent the scheduler registers. */
        fun intent(context: Context, routineId: Long, taskIndex: Int): Intent =
            Intent(context, AlarmReceiver::class.java).apply {
                putExtra(EXTRA_ROUTINE_ID, routineId)
                putExtra(EXTRA_TASK_INDEX, taskIndex)
            }

        /** Stable notification id per (routine, task). */
        fun notificationId(routineId: Long, taskIndex: Int): Int =
            AlarmManagerScheduler.requestCodeFor(routineId, taskIndex)
    }
}
