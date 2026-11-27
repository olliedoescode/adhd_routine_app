package com.adhd.routineplanner.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.adhd.routineplanner.data.db.PlacedTaskDao
import com.adhd.routineplanner.data.db.RoutineDao
import com.adhd.routineplanner.data.model.toDomain
import com.adhd.routineplanner.domain.AlarmSchedule
import com.adhd.routineplanner.domain.PlacedTask
import com.adhd.routineplanner.domain.Routine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules and cancels the app's exact alarms (spec section 9).
 *
 * The repository calls this on every committed edit. The real implementation
 * uses `AlarmManager.setAlarmClock()` (treated as a real alarm, exempt from
 * Doze) with a fallback to `setExactAndAllowWhileIdle()` when the alarm-clock
 * permission is unavailable.
 */
interface AlarmScheduler {
    fun schedule(routine: Routine, tasks: List<PlacedTask>)
    fun cancel(routineId: Long)
    suspend fun rescheduleRoutine(routineId: Long)
    suspend fun rescheduleAll()

    /**
     * Schedules the *in-run* task alarms for a run that has just started at
     * [startMs].
     *
     * The recurring schedule ([schedule]) only ever registers the *next*
     * occurrence of each task, which for a run started "now" (a test run) is
     * usually tomorrow — so a test run never fired a single task alarm. This
     * method registers every alarm-enabled task at its offset from the actual
     * session start, so the alarms fire during *this* run whether it was
     * started by the scheduler or by hand.
     *
     * Tasks whose start is already in the past are skipped (they cannot fire).
     *
     * [firstTaskIndex] is the first task index that should be (re-)registered.
     * [AlarmReceiver] passes `firedTaskIndex + 1` when it self-heals the run
     * after a task alarm, so the task that has *just* fired is never registered
     * again. Without this the grace window below pulled the just-fired task's
     * alarm forward to `now + FIRST_TASK_LEAD_MS`, which re-fired the tone and
     * vibration every ~3 seconds.
     */
    suspend fun scheduleRunAlarms(
        routineId: Long,
        startMs: Long,
        firstTaskIndex: Int = 0,
    )

    /** Cancels the in-run task alarms for [routineId] (e.g. when a run stops). */
    fun cancelRunAlarms(routineId: Long)

    /**
     * Re-registers the alarm for one task [minutes] from now, so the alarm
     * screen's Snooze button rings again later.
     *
     * The snooze is a *single* re-arm of the same task's alarm, not a change to
     * the routine: the timeline is untouched, so the run screen keeps counting
     * down as before and the snoozed alarm simply fires again on top of it.
     * Registering it with the task's normal request code means a later
     * [cancelRunAlarms] (when the run stops) clears it too.
     */
    fun snoozeTaskAlarm(routineId: Long, taskIndex: Int, minutes: Int)

    /**
     * Schedules a one-off test alarm [minutes] from now (spec section 15.10).
     *
     * @return `true` when the alarm was registered as an *exact* alarm (so it
     * will fire on time), `false` when exact-alarm permission is missing and
     * the platform will only deliver it inexactly (possibly minutes late). The
     * caller should tell the user in the latter case rather than implying the
     * test proves on-time delivery.
     */
    fun scheduleTestAlarm(minutes: Int): Boolean
}

/**
 * Placeholder binding used by unit tests. Deliberately does nothing so that
 * committing tasks never fails on a missing alarm layer.
 */
class NoOpAlarmScheduler @Inject constructor() : AlarmScheduler {
    override fun schedule(routine: Routine, tasks: List<PlacedTask>) = Unit
    override fun cancel(routineId: Long) = Unit
    override suspend fun rescheduleRoutine(routineId: Long) = Unit
    override suspend fun rescheduleAll() = Unit
    override suspend fun scheduleRunAlarms(
        routineId: Long,
        startMs: Long,
        firstTaskIndex: Int,
    ) = Unit
    override fun cancelRunAlarms(routineId: Long) = Unit
    override fun snoozeTaskAlarm(routineId: Long, taskIndex: Int, minutes: Int) = Unit
    override fun scheduleTestAlarm(minutes: Int): Boolean = true
}

/**
 * Real `AlarmManager`-backed scheduler (spec section 9).
 *
 * Depends on the DAOs directly rather than the repository to avoid a
 * repository↔scheduler dependency cycle. Only the next occurrence of each
 * scheduled task is registered; when an alarm fires, [AlarmReceiver] posts the
 * notification and calls back here to schedule the following one.
 */
@Singleton
class AlarmManagerScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routineDao: RoutineDao,
    private val placedTaskDao: PlacedTaskDao,
) : AlarmScheduler {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun schedule(routine: Routine, tasks: List<PlacedTask>) {
        // Always clear first: a task may have had its alarm switched off since
        // the last schedule, and a stale PendingIntent would still fire.
        cancel(routine.id)
        // An untimed routine is run by hand from the routine list, never on a
        // clock, so it must never hold an alarm. The cancel above matters here
        // too: flipping an existing routine to untimed has to drop its alarms.
        if (routine.isUntimed) return
        if (!routine.enabled) return
        if (tasks.isEmpty()) return
        val now = System.currentTimeMillis()

        // The routine-start alarm (task index -1) is what actually begins the
        // run: it starts the session and posts the ongoing notification. It is
        // registered for every enabled routine, whether or not any individual
        // task has its own alarm, so a routine always runs at its start time.
        AlarmSchedule.nextRoutineStart(routine, now)?.let { trigger ->
            setAlarm(
                triggerAtMs = trigger,
                requestCode = requestCodeFor(routine.id, ROUTINE_START_TASK_INDEX),
                routineId = routine.id,
                taskIndex = ROUTINE_START_TASK_INDEX,
            )
        }

        // Alarms are per task: only the tasks the user flagged get one.
        tasks.forEachIndexed { index, task ->
            if (!task.alarmEnabled) return@forEachIndexed
            val trigger = AlarmSchedule.nextTaskStart(routine, tasks, index, now)
                ?: return@forEachIndexed
            setAlarm(
                triggerAtMs = trigger,
                requestCode = requestCodeFor(routine.id, index),
                routineId = routine.id,
                taskIndex = index,
            )
        }
    }

    override fun cancel(routineId: Long) {
        // Cancel the routine-start alarm and a generous range of task alarms.
        // Request codes are derived from (routineId, taskIndex); a routine can
        // hold at most 1440 one-minute tasks, so 0..1439 covers every index.
        for (index in ROUTINE_START_TASK_INDEX until MAX_TASKS_PER_ROUTINE) {
            val pi = pendingIntent(
                requestCode = requestCodeFor(routineId, index),
                routineId = routineId,
                taskIndex = index,
                flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) ?: continue
            alarmManager.cancel(pi)
            pi.cancel()
        }
    }

    override suspend fun rescheduleRoutine(routineId: Long) {
        withContext(Dispatchers.IO) {
            val routineEntity = routineDao.getById(routineId)
            if (routineEntity == null) {
                cancel(routineId)
                return@withContext
            }
            val routine = routineEntity.toDomain()
            val tasks = placedTaskDao.getForRoutine(routineId).map { it.toDomain() }
            cancel(routineId)
            schedule(routine, tasks)
        }
    }

    override suspend fun rescheduleAll() {
        withContext(Dispatchers.IO) {
            val routines = routineDao.getAll()
            // Cancel everything first so a deleted routine leaves no orphan alarms.
            routines.forEach { cancel(it.id) }
            routines.filter { it.enabled }.forEach { entity ->
                val routine = entity.toDomain()
                val tasks = placedTaskDao.getForRoutine(entity.id).map { it.toDomain() }
                schedule(routine, tasks)
            }
        }
    }

    /**
     * Registers every alarm-enabled task at its offset from [startMs], the
     * actual start of the run that is beginning now.
     *
     * This is what makes a *test run* raise alarms: the recurring [schedule]
     * only registers the next calendar occurrence of each task, which for a run
     * started "now" is usually tomorrow. Here the trigger is computed from the
     * session start, so the alarms fire during the run the user is watching.
     *
     * Tasks whose start is already in the past are skipped — they cannot fire,
     * and registering them would make AlarmManager deliver them immediately.
     */
    override suspend fun scheduleRunAlarms(
        routineId: Long,
        startMs: Long,
        firstTaskIndex: Int,
    ) {
        withContext(Dispatchers.IO) {
            val routineEntity = routineDao.getById(routineId) ?: return@withContext
            val routine = routineEntity.toDomain()
            val tasks = placedTaskDao.getForRoutine(routineId).map { it.toDomain() }
            if (tasks.isEmpty()) return@withContext

            val now = System.currentTimeMillis()
            // Absolute start of each task = session start + its rendered offset.
            val offsets = com.adhd.routineplanner.domain.Schedule.startMinutes(routine, tasks)
            tasks.forEachIndexed { index, task ->
                // Never re-register a task at or before the one that just fired.
                // The grace window below would otherwise pull it forward to
                // `now + FIRST_TASK_LEAD_MS` and make it ring again immediately.
                if (index < firstTaskIndex) return@forEachIndexed
                if (!task.alarmEnabled) return@forEachIndexed
                val offset = offsets.getOrNull(index) ?: return@forEachIndexed
                val trigger = startMs + offset * 60_000L
                // Skip tasks that have already started: an alarm in the past
                // would fire instantly and stack on the current task.
                //
                // The grace window matters for a *test run*: `beginNow` passes
                // `startMs = System.currentTimeMillis()`, so the first task's
                // trigger is a few milliseconds in the past by the time we get
                // here and would always be dropped. A task that started within
                // the last minute is still "the current task", so its alarm is
                // pulled forward to a moment from now instead of being skipped —
                // that is what makes the first alarm of a test run actually
                // fire. Anything older is genuinely in the past and is skipped.
                val effectiveTrigger = when {
                    trigger > now -> trigger
                    now - trigger <= FIRST_TASK_GRACE_MS -> now + FIRST_TASK_LEAD_MS
                    else -> return@forEachIndexed
                }
                setAlarm(
                    triggerAtMs = effectiveTrigger,
                    requestCode = requestCodeFor(routineId, index),
                    routineId = routineId,
                    taskIndex = index,
                )
            }
        }
    }

    /**
     * Cancels the in-run task alarms for [routineId].
     *
     * The request codes are the same ones [scheduleRunAlarms] used, so this
     * clears exactly the alarms a run registered. The routine-start alarm
     * (index -1) is left alone: it belongs to the recurring schedule, not the
     * run.
     */
    override fun cancelRunAlarms(routineId: Long) {
        for (index in 0 until MAX_TASKS_PER_ROUTINE) {
            val pi = pendingIntent(
                requestCode = requestCodeFor(routineId, index),
                routineId = routineId,
                taskIndex = index,
                flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) ?: continue
            alarmManager.cancel(pi)
            pi.cancel()
        }
    }

    /**
     * Re-arms one task's alarm [minutes] from now for the Snooze button.
     *
     * It reuses the task's normal request code, so the snoozed alarm replaces
     * whatever was registered for that task and is cleared by the usual
     * [cancelRunAlarms] when the run stops. The trigger is always in the future
     * (a snooze is by definition "later"), so it goes through the same
     * [setAlarm] path as every other alarm and keeps the exact-alarm fallbacks.
     */
    override fun snoozeTaskAlarm(routineId: Long, taskIndex: Int, minutes: Int) {
        val trigger = System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L
        setAlarm(
            triggerAtMs = trigger,
            requestCode = requestCodeFor(routineId, taskIndex),
            routineId = routineId,
            taskIndex = taskIndex,
        )
    }

    override fun scheduleTestAlarm(minutes: Int): Boolean {
        val trigger = AlarmSchedule.testAlarmAt(System.currentTimeMillis(), minutes)

        // Fire the alarm screen *immediately* so the user gets instant proof
        // that the alarm screen and tone work, without waiting on AlarmManager
        // (which Doze can defer by minutes when exact alarms are not allowed).
        // This is what makes the "Test alarm" button feel like it does
        // something the moment it is tapped.
        //
        // It uses the *preview* task index, distinct from the delayed alarm's
        // index below. AlarmActivity is `singleInstance`, so if both used the
        // same index the delayed alarm would be delivered to the already-open
        // preview instance and never show a fresh screen or play a tone.
        try {
            context.startActivity(
                AlarmActivity.intent(context, TEST_ROUTINE_ID, TEST_PREVIEW_TASK_INDEX).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } catch (t: Throwable) {
            // A background-activity-start restriction must not stop us from
            // also scheduling the real alarm below.
            Log.w(TAG, "Could not launch test alarm screen immediately", t)
        }

        val pi = pendingIntent(
            requestCode = TEST_ALARM_REQUEST_CODE,
            routineId = TEST_ROUTINE_ID,
            taskIndex = TEST_DELAYED_TASK_INDEX,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return false

        // The alarm-clock show intent opens the alarm screen itself, so tapping
        // the system alarm icon lands on the same screen a real alarm shows.
        val showIntent = PendingIntent.getActivity(
            context,
            TEST_ALARM_REQUEST_CODE,
            AlarmActivity.intent(context, TEST_ROUTINE_ID, TEST_DELAYED_TASK_INDEX),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        return try {
            if (canExact) {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(trigger, showIntent),
                    pi,
                )
                true
            } else {
                // Exact-alarm permission is missing. Register the alarm anyway
                // so the user still gets *something*, but report `false` so the
                // UI can warn that it may arrive late (Doze can defer inexact
                // alarms by several minutes).
                Log.w(TAG, "Exact-alarm permission missing; test alarm will be inexact")
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
                false
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "Exact test alarm denied; falling back to inexact", se)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            false
        }
    }

    /**
     * Registers one alarm. Prefers `setAlarmClock()` (spec section 17) and
     * falls back to `setExactAndAllowWhileIdle()` when exact alarms are not
     * permitted, so a denied permission degrades rather than crashes.
     */
    private fun setAlarm(
        triggerAtMs: Long,
        requestCode: Int,
        routineId: Long,
        taskIndex: Int,
    ) {
        val pi = pendingIntent(
            requestCode = requestCode,
            routineId = routineId,
            taskIndex = taskIndex,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return

        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        try {
            if (canExact) {
                // setAlarmClock shows the system alarm icon and is exempt from
                // Doze; it needs a PendingIntent that opens the alarm screen.
                val showIntent = PendingIntent.getActivity(
                    context,
                    requestCode,
                    AlarmActivity.intent(context, routineId, taskIndex),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAtMs, showIntent),
                    pi,
                )
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMs,
                    pi,
                )
            }
        } catch (se: SecurityException) {
            // Exact-alarm permission revoked between the check and the call.
            Log.w(TAG, "Exact alarm denied; falling back to inexact", se)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
    }

    private fun pendingIntent(
        requestCode: Int,
        routineId: Long,
        taskIndex: Int,
        flags: Int,
    ): PendingIntent? {
        val intent = AlarmReceiver.intent(context, routineId, taskIndex)
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    companion object {
        private const val TAG = "AlarmScheduler"

        /**
         * How far in the past a task's start may be and still have its alarm
         * registered (pulled forward) instead of skipped.
         *
         * A test run starts "now", so the first task's trigger is a few
         * milliseconds behind by the time [scheduleRunAlarms] runs. Without this
         * window that first alarm was always dropped, which is why a test run
         * never rang.
         */
        private const val FIRST_TASK_GRACE_MS: Long = 60_000L

        /** Lead-in applied to a task whose start has just passed. */
        private const val FIRST_TASK_LEAD_MS: Long = 3_000L

        /** Upper bound on tasks per routine (spec section 14.1: 1440 minutes). */
        const val MAX_TASKS_PER_ROUTINE: Int = 1440

        /**
         * Task index reserved for the routine-start alarm. It is not a real
         * task: [AlarmReceiver] treats it as "begin the run", and
         * [requestCodeFor] offsets by one so it maps to a distinct request code.
         */
        const val ROUTINE_START_TASK_INDEX: Int = -1

        /**
         * Request code for the one-off test alarm. Chosen from the top of the
         * positive int range so it can never collide with
         * [requestCodeFor] (which stays well below this for realistic routine
         * ids and task indices).
         */
        const val TEST_ALARM_REQUEST_CODE: Int = 0x7E57_0000

        /** Sentinel routine id used by the test alarm. */
        const val TEST_ROUTINE_ID: Long = -1L

        /**
         * Task index for the *immediate* preview screen the test button opens.
         * Distinct from [TEST_DELAYED_TASK_INDEX] so the two never share an
         * `AlarmActivity` instance (the activity is `singleInstance`).
         */
        const val TEST_PREVIEW_TASK_INDEX: Int = -1

        /**
         * Task index for the *delayed* real alarm the test schedules. Distinct
         * from [TEST_PREVIEW_TASK_INDEX] so the delayed alarm opens a fresh
         * alarm screen and plays its tone even if the preview is still open.
         */
        const val TEST_DELAYED_TASK_INDEX: Int = -2

        /**
         * Request code derived from the routine id and task index so an alarm
         * can be cancelled or updated (spec section 9). The task index is
         * offset by one so `-1` (routine start) maps to a distinct code.
         *
         * The multiplier is 2048 (not 1440) so the routine-start code (`-1`)
         * and the last task code (`1439`) never overlap between adjacent
         * routines. The result is masked to the positive int range and kept
         * below [TEST_ALARM_REQUEST_CODE].
         */
        fun requestCodeFor(routineId: Long, taskIndex: Int): Int {
            val base = (routineId * 2048L).toInt()
            return (base + (taskIndex + 1)) and 0x7FFF_FFFF
        }
    }
}
