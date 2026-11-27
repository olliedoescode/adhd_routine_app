package com.adhd.routineplanner.alarm

import android.content.Context
import com.adhd.routineplanner.data.RunSessionStore
import com.adhd.routineplanner.widget.RunWidgetUpdater
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kills a run from outside the run UI.
 *
 * The one caller that needs this is deleting a routine: until now the delete
 * path only cancelled the routine's stored alarms and dropped the database row,
 * which left the *live* run untouched. The session stayed in DataStore, the
 * ongoing notification and its foreground service stayed up, the lock-screen
 * takeover stayed on screen showing a routine that no longer existed, and the
 * in-app run screen sat on a null routine. That is the "super fucky" behaviour
 * when a running routine is deleted.
 *
 * Stopping lives here rather than in [RunViewModel] because the delete can be
 * triggered from the routine list, where no run ViewModel is alive — and because
 * the removal must happen even if the UI that started the run has been swiped
 * away. It is the same teardown [RunViewModel.stop] performs, in the same order:
 *
 *  1. cancel the routine's scheduled alarms,
 *  2. cancel the alarms already armed for the running session,
 *  3. dismiss the run notification (which also stops the foreground service),
 *  4. clear the session, so every screen polling it sees "no run",
 *  5. refresh the widget.
 */
@Singleton
class RunStopper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionStore: RunSessionStore,
    private val alarmScheduler: AlarmScheduler,
    private val widgetUpdater: RunWidgetUpdater,
) {

    /**
     * Stops the active run **if** it belongs to [routineId].
     *
     * Returns `true` when a run was actually stopped, so the caller can tell a
     * plain delete from a delete-that-also-killed-a-run. A run belonging to a
     * different routine is left strictly alone.
     */
    suspend fun stopIfRunningFor(routineId: Long): Boolean {
        val session = sessionStore.session.firstOrNull() ?: return false
        if (session.routineId != routineId) return false

        // 1 + 2: no alarm for this routine may fire again, whether it was the
        // routine's own schedule or a task alarm armed by this run.
        alarmScheduler.cancel(routineId)
        alarmScheduler.cancelRunAlarms(routineId)

        // 3: the ongoing notification and the foreground service that keeps the
        // run alive while the app is backgrounded.
        RunNotification.cancel(context)

        // 4: the single source of truth. RunScreen, RunTakeoverActivity and the
        // notification refresher all poll this and will see "no run".
        sessionStore.clear()

        // 5: the home-screen widget mirrors the session.
        widgetUpdater.refresh()
        return true
    }
}
