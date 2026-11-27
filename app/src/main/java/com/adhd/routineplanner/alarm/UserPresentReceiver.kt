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
 * Fires on `ACTION_SCREEN_ON` (screen transitions off→on) and
 * `ACTION_USER_PRESENT` (device unlocked), and the two actions do different
 * things so the takeover appears exactly once per "phone opened" event:
 *
 *  - **`ACTION_SCREEN_ON`:** the screen just turned on. With "Display over other
 *    apps" granted, [RunTakeoverActivity] is opened *directly* (the overlay
 *    permission exempts us from the background-activity-start restriction).
 *    Without it, the run notification's full-screen intent is re-armed
 *    ([RunNotification.rearmForScreenOn]) so the platform launches the takeover
 *    over the keyguard instead.
 *  - **`ACTION_USER_PRESENT`:** the device was unlocked. This is the biometric
 *    path where the screen was *already on* (so no `ACTION_SCREEN_ON` fired and
 *    no full-screen-intent slot was available). Record that the user is present
 *    ([RunSessionStore.markUserPresent]) and — with the overlay permission —
 *    open [RunTakeoverActivity] directly, which is what makes the takeover work
 *    on the fingerprint path. Without the overlay it falls back to bringing the
 *    run to the foreground directly (the "home screen variant").
 *
 * The two actions are mutually exclusive in practice: a power-button wake sends
 * `ACTION_SCREEN_ON` (then `ACTION_USER_PRESENT` only after unlock), while a
 * biometric unlock of an already-lit screen sends only `ACTION_USER_PRESENT`.
 * So the takeover fires once per open, in the right variant, and never twice.
 *
 * When the takeover setting is off, both paths simply bring the app to the run
 * screen, best-effort.
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
 * normal, and it only fires when the phone is opened, not on every interaction.
 */
@AndroidEntryPoint
class UserPresentReceiver : BroadcastReceiver() {

    @Inject
    lateinit var sessionStore: RunSessionStore

    @Inject
    lateinit var alarmSettingsStore: AlarmSettingsStore

    override fun onReceive(context: Context, intent: Intent) {
        // SCREEN_ON fires the moment the screen turns on (power button); it is
        // the over-keyguard takeover path. USER_PRESENT fires on unlock and is
        // the biometric path where the screen was already on — it brings the run
        // to the foreground directly (the "home screen variant"). See the class
        // KDoc for why the two never double-fire.
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
                val takeover = alarmSettingsStore.settings.firstOrNull()?.takeoverEnabled ?: true

                // "Display over other apps" is what lets this background
                // receiver open the takeover directly. Without it the start
                // would be blocked by the Android 10+ background-activity-start
                // restriction, so we must fall back to the full-screen intent.
                val canOverlay = AlarmPermissions.overlayGranted(context)

                if (action == Intent.ACTION_USER_PRESENT) {
                    // Biometric unlock of an already-lit screen: no SCREEN_ON
                    // fired, so this is the only signal. Record presence first.
                    sessionStore.markUserPresent()
                    if (takeover && canOverlay) {
                        // Open the takeover directly. This is the path that makes
                        // it work when the user unlocks with their fingerprint and
                        // no full-screen-intent slot was available.
                        launchTakeover(context)
                    } else {
                        // No overlay: bring the run to the foreground instead —
                        // the "home screen variant".
                        launchRunScreen(context, session.routineId)
                    }
                    return@launch
                }

                // From here on this is ACTION_SCREEN_ON: the screen just turned
                // on (power button, or a passwordless device waking straight to
                // the home screen).
                if (!takeover) {
                    // Takeover turned off: just bring the app to the run screen
                    // if the device is already interactive. Nothing is forced
                    // over the lock screen.
                    launchRunScreen(context, session.routineId)
                    return@launch
                }

                if (canOverlay) {
                    // Overlay granted: a direct start from the background is
                    // allowed, so the takeover appears on *every* wake even if
                    // the full-screen-intent slot was already consumed.
                    launchTakeover(context)
                } else {
                    // No overlay: re-arm the full-screen intent by cancelling
                    // and re-posting the notification. This is a genuinely fresh
                    // post (so the platform fires the FSI on this wake) that
                    // reuses the *stable* request code (so it never launches
                    // early and never collides with a previous wake).
                    RunNotification.rearmForScreenOn(context, session.routineId)
                }
            } catch (t: Throwable) {
                // Never let a receiver crash the process.
                Log.w(TAG, "Takeover attempt failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Opens [RunTakeoverActivity] directly.
     *
     * Only usable because "Display over other apps" is granted: that permission
     * is one of the documented exemptions from the Android 10+ background
     * activity-start restriction. When it is not granted, callers fall back to
     * the notification's full-screen intent or the foreground run screen.
     */
    private fun launchTakeover(context: Context) {
        try {
            context.startActivity(
                RunTakeoverActivity.intent(context).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Could not open the lock-screen takeover directly", t)
        }
    }

    /**
     * Brings the app to the run screen directly — the "home screen variant".
     *
     * Used when the takeover is off, or when "Display over other apps" is not
     * granted (so a direct background start of the takeover would be blocked).
     * The device is unlocked/interactive on the `ACTION_USER_PRESENT` path, and
     * this is best-effort on the `ACTION_SCREEN_ON` path — if Android refuses
     * the start, the user simply opens the app themselves.
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
