package com.adhd.routineplanner.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.adhd.routineplanner.data.AlarmSettingsStore
import com.adhd.routineplanner.data.RunSessionStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Take over the phone" while a routine is running.
 *
 * Fires on `ACTION_SCREEN_ON` (screen wakes) and `ACTION_USER_PRESENT` (device
 * unlocked). Two paths, depending on the takeover setting:
 *
 *  - **Takeover on:** re-post the run notification with its full-screen intent
 *    ([RunNotification.repostForTakeover]). Android 10+ blocks background
 *    activity starts, so a receiver cannot call `startActivity` here; a
 *    full-screen intent is the sanctioned exception and the platform launches
 *    [RunTakeoverActivity] over the keyguard itself.
 *  - **Takeover off:** open the run screen in
 *    [com.adhd.routineplanner.MainActivity], best-effort.
 *
 * The keyguard state is deliberately *not* consulted. The old code only took
 * the full-screen-intent path when `isKeyguardLocked` was true and fell back to
 * `startActivity` otherwise; that fallback is blocked by the Android 10+
 * background-activity-start restriction, and Samsung reports "not locked" at
 * the instant `ACTION_SCREEN_ON` arrives, so the takeover silently did nothing.
 * Re-posting the notification is the one path the platform always honours, and
 * it behaves correctly in both states.
 *
 * It is deliberately escapable: the user can swipe away from the activity as
 * normal, and it only fires when the screen wakes, not on every interaction.
 */
@AndroidEntryPoint
class UserPresentReceiver : BroadcastReceiver() {

    @Inject
    lateinit var sessionStore: RunSessionStore

    @Inject
    lateinit var alarmSettingsStore: AlarmSettingsStore

    override fun onReceive(context: Context, intent: Intent) {
        // SCREEN_ON fires the moment the screen wakes, which is when the
        // takeover needs to appear. USER_PRESENT fires after unlock and is the
        // fallback for devices that do not deliver SCREEN_ON to receivers.
        val action = intent.action
        if (action != Intent.ACTION_SCREEN_ON && action != Intent.ACTION_USER_PRESENT) return

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val session = sessionStore.session.firstOrNull() ?: return@launch

                // Record that the user has actually unlocked the phone during
                // this run. This is the discriminator between the two "an alarm
                // fired" states:
                //
                //  - USER_PRESENT means the device was unlocked. If this happens
                //    while a run is active, the user is demonstrably on their
                //    phone, so a *later* task alarm must not drag them to the
                //    takeover again (state D).
                //  - SCREEN_ON alone does not mean unlocked (the phone may still
                //    be on the keyguard), so it must NOT set the flag. That keeps
                //    the first alarm of a run — even one the user was already
                //    holding the phone for before the routine started (state B) —
                //    routing to the takeover, because the routine has started.
                if (action == Intent.ACTION_USER_PRESENT) {
                    sessionStore.markUserPresent()
                }

                val takeover = alarmSettingsStore.settings.firstOrNull()?.takeoverEnabled ?: true

                if (!takeover) {
                    // Takeover turned off: just bring the app to the run screen
                    // if the device is already interactive. Nothing is forced
                    // over the lock screen.
                    launchRunScreen(context, session.routineId)
                    return@launch
                }

                // Always go through the full-screen-intent path, whether or not
                // the keyguard currently reports itself locked.
                //
                // The old code only did this when `isKeyguardLocked` was true,
                // and fell back to `startActivity` otherwise. That fallback is
                // blocked by the Android 10+ background-activity-start
                // restriction, so on a device that reports "not locked" at the
                // instant SCREEN_ON arrives (common on Samsung, where the
                // keyguard state settles a moment later) the takeover silently
                // did nothing. Re-posting the notification is the one path the
                // platform always honours, and it works in both states: over the
                // keyguard it launches the takeover, and when already unlocked
                // it simply brings the routine up.
                RunNotification.repostForTakeover(context, session.routineId)
            } catch (t: Throwable) {
                // Never let a receiver crash the process.
                Log.w(TAG, "Takeover attempt failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Brings the app to the run screen directly.
     *
     * Only used when the takeover setting is off. Best-effort: if Android
     * refuses the background start, the user still lands in run mode when they
     * open the app themselves.
     */
    private fun launchRunScreen(context: Context, routineId: Long) {
        val launch = Intent(context, com.adhd.routineplanner.MainActivity::class.java).apply {
            // `setAction`, not `action =`: inside `apply` the name resolves to
            // the read-only `Intent.action` getter.
            setAction(AlarmActivity.ACTION_OPEN_RUN)
            putExtra(AlarmActivity.EXTRA_ROUTINE_ID, routineId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        try {
            context.startActivity(launch)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not bring the routine to the front", t)
        }
    }

    private companion object {
        private const val TAG = "UserPresentReceiver"
    }
}
