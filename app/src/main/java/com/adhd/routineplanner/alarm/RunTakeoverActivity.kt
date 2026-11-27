package com.adhd.routineplanner.alarm

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.lifecycleScope
import com.adhd.routineplanner.data.RoutineRepository
import com.adhd.routineplanner.data.RunSession
import com.adhd.routineplanner.data.RunSessionStore
import com.adhd.routineplanner.data.ThemeStore
import com.adhd.routineplanner.domain.Phase
import com.adhd.routineplanner.domain.runState
import com.adhd.routineplanner.ui.plan.toGridTasks
import com.adhd.routineplanner.ui.run.RunBody
import com.adhd.routineplanner.ui.theme.AppThemeFromStore
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.TextSecondary
import com.adhd.routineplanner.viewmodel.RunUiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The accountability lock-screen takeover (plans/accountability-lockscreen.md).
 *
 * While a routine is running, the ongoing run notification carries a
 * full-screen intent pointing here. Android therefore launches this activity
 * over the keyguard whenever the screen turns on, so picking up the phone
 * during a run shows the routine — the current task, the time left and the next
 * task — instead of the normal lock screen.
 *
 * The takeover now renders the *same* [RunNowNextCard] as the in-app run
 * screen, so the two are visually identical: the colourful card, the slow
 * fill-up and the corner mini-grid all appear here too. Because it is the same
 * page, tapping it no longer opens the run screen (that would just show the
 * same thing twice). Instead a tap — like the Dismiss hint — closes the overlay
 * and drops the user back to their own lock screen / home screen.
 *
 * It is deliberately *not* a lock: the user can always dismiss it, which just
 * calls [finishAndRemoveTask] and reveals the normal lock screen. Nothing about
 * the run is stopped or changed. The point is friction and a reminder, not a
 * barrier.
 *
 * ## Why this used to never appear
 *
 * Three bugs combined to make the takeover look like it never fired:
 *
 *  1. The session-loading loop called `finish()` on its *first* read when the
 *     session was not there yet. A full-screen intent is delivered the instant
 *     the screen wakes, which is well before DataStore has warmed up, so the
 *     activity closed itself within a few milliseconds — usually before it had
 *     even drawn a frame. It now waits for the session instead of giving up.
 *  2. `requestDismissKeyguard` was called from `onResume`. On a secure lock
 *     screen that hands control to the keyguard's own PIN screen, which is
 *     drawn *above* this activity, so the routine was hidden behind it. The
 *     activity now stays on top of the keyguard (`showWhenLocked`) and never
 *     asks the keyguard to take over.
 *  3. The activity was `singleInstance`, so a second full-screen intent was
 *     delivered to the *existing* instance through `onNewIntent` instead of
 *     creating a new one. `onNewIntent` was not overridden, so after the first
 *     dismissal every later screen wake reused a finished instance and showed
 *     nothing. It is now `singleTop`: a full-screen intent delivered while it is
 *     on top reuses the instance via [onNewIntent] (which re-asserts the
 *     lock-screen flags and restarts the refresh loop), and one delivered after
 *     it was dismissed creates a fresh instance.
 */
@AndroidEntryPoint
class RunTakeoverActivity : ComponentActivity() {

    @Inject
    lateinit var repository: RoutineRepository

    @Inject
    lateinit var sessionStore: RunSessionStore

    /**
     * Needed for Pause/Resume: pausing must drop the pending in-run task alarms
     * so a paused run cannot start ringing mid-pause, and resuming must re-arm
     * them from the shifted start.
     */
    @Inject
    lateinit var alarmScheduler: AlarmScheduler

    /** Only used to paint this overlay in the user's chosen theme. */
    @Inject
    lateinit var themeStore: ThemeStore

    /**
     * The run state currently on screen, or `null` while it loads.
     *
     * Compose state, not a plain field: the screen has to redraw when the
     * session finally arrives, and the "No routine running" branch has to
     * appear when it never does.
     */
    private var uiState by mutableStateOf<RunUiState?>(null)

    /**
     * True once a state has been shown; used to decide when to give up.
     *
     * Also Compose state, for the same reason as [uiState]: the empty-state
     * branch is gated on it, so a plain field would never trigger the redraw
     * that shows it.
     */
    private var loaded by mutableStateOf(false)

    /**
     * The running session-refresh loop, so [onNewIntent] can restart it.
     *
     * A full-screen intent for an already-existing `singleTop` activity is
     * delivered to the *existing* instance through [onNewIntent] rather than
     * creating a new one. Without cancelling and restarting this loop, the
     * second and every later screen wake would reuse a loop that had already
     * finished (or a screen the user had dismissed), so the takeover would only
     * ever appear once per run.
     */
    private var refreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The takeover has now been shown, so the per-second notification
        // updater may resume refreshing the ongoing card. Until this point it
        // deliberately left the armed notification alone so the full-screen
        // intent was not stripped before the platform launched this activity.
        RunNotification.clearTakeoverArmed()

        // A *manual* run (the plan screen's play button / test run) starts the
        // takeover directly from the foreground, so the app's own MainActivity
        // is still sitting underneath it on the run screen. Dismissing the
        // overlay would then drop the user onto that identical run page — the
        // "exit the overlay and it's the same screen" complaint. Finishing the
        // app's task as well means dismissing lands on the launcher / keyguard
        // instead. A scheduled run has no app task underneath (the app is
        // usually closed), so this is a no-op there.
        //
        // This must run *after* the content is set and the refresh loop is
        // started, and it must never finish this activity: see
        // [finishUnderlyingAppTask].
        finishUnderlyingAppTask()

        // Show over the lock screen and turn the screen on, exactly like the
        // alarm screen.
        applyLockScreenFlags()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            AppThemeFromStore(themeStore) {
                TakeoverScreen(
                    state = uiState,
                    loaded = loaded,
                    onDismiss = { dismissToHome() },
                    onEndRoutine = { endRoutine() },
                    onTogglePause = { togglePause() },
                )
            }
        }

        startRefreshing()
    }

    /**
     * Handles a second (and later) full-screen intent that arrives while this
     * instance is still on top.
     *
     * The activity is `singleTop`, so a full-screen intent delivered while it is
     * already the top activity reuses this instance via `onNewIntent` instead of
     * creating a new one. (If the user dismissed it, the instance is gone and a
     * later full-screen intent creates a fresh one — which is what makes the
     * takeover appear again on every screen wake rather than only the first.)
     * The user may have dismissed the overlay minutes ago, leaving [refreshJob]
     * finished and the screen stale; re-asserting the lock-screen flags and
     * restarting the refresh loop keeps it correct.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A fresh takeover is being shown; let the updater resume afterwards.
        RunNotification.clearTakeoverArmed()
        applyLockScreenFlags()
        startRefreshing()
    }

    /**
     * Starts (or restarts) the once-per-second session refresh.
     *
     * Waits for the session rather than giving up on the first read. A
     * full-screen intent arrives the moment the screen wakes, which is before
     * DataStore has warmed up, so the old "finish on first null" behaviour
     * closed the activity before it could draw anything — the reason the
     * takeover appeared not to work at all.
     *
     * Builds a full [RunUiState] (routine + grid tasks + derived run state),
     * mirroring [com.adhd.routineplanner.viewmodel.RunViewModel], so the same
     * [RunNowNextCard] can be drawn here as on the run screen.
     */
    private fun startRefreshing() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            var attempts = 0
            while (true) {
                val session: RunSession? = sessionStore.session.firstOrNull()
                if (session == null) {
                    // Once a run has been drawn, a missing session is final: the
                    // user stopped the run, or the routine was deleted from
                    // under us ([com.adhd.routineplanner.alarm.RunStopper] clears
                    // the session on delete). The old code only finished while
                    // still unloaded, which left the overlay frozen on screen
                    // showing a routine that no longer existed.
                    if (loaded) {
                        finish()
                        break
                    }
                    // Nothing drawn yet. Give the store a generous moment to
                    // warm up (about three seconds) before concluding there is
                    // no run at all — a full-screen intent arrives before
                    // DataStore has woken up.
                    if (attempts++ >= 15) {
                        finish()
                        break
                    }
                    delay(200L)
                    continue
                }

                val routine = repository.getRoutine(session.routineId)
                if (routine == null) {
                    // The routine was deleted mid-run: there is nothing to show,
                    // and there never will be. Close regardless of whether it was
                    // already drawn.
                    finish()
                    break
                }

                val placedTasks = repository.getTasks(session.routineId)
                // While paused the run is evaluated at the pause instant rather
                // than the clock, so the card freezes exactly like the in-app run
                // screen (and the notification) instead of keeping count.
                val nowMs = session.pausedAtMs ?: System.currentTimeMillis()
                val state = runState(routine, placedTasks, session.startMs, nowMs)
                uiState = RunUiState(
                    routine = routine,
                    tasks = placedTasks.toGridTasks(),
                    session = session,
                    state = state,
                    loading = false,
                )
                loaded = true
                // A finished run has nothing left to hold the screen for.
                if (state.phase == Phase.Finished) break
                delay(1_000L)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Some OEM skins (Samsung included) clear the show-when-locked flag when
        // the activity is resumed from the background. Re-assert it so the
        // takeover actually draws over the keyguard rather than behind it.
        //
        // The keyguard is deliberately *not* dismissed here. Asking the system
        // to dismiss it hands the screen to the keyguard's own PIN entry, which
        // is drawn above this activity — that is what hid the routine behind the
        // lock screen. Staying on top of the keyguard is the whole point.
        //
        // The full-screen intent is deliberately *not* re-armed here. Re-arming
        // from onResume created a feedback loop: the takeover appearing is
        // itself a resume, so it re-posted the notification with a fresh
        // full-screen intent, which the platform then launched again — the
        // "notification re-fires whenever I open the app" bug. The intent is
        // re-armed only by [UserPresentReceiver] on a genuine screen-on.
        applyLockScreenFlags()
    }

    /**
     * Puts this activity over the keyguard and wakes the screen.
     *
     * Called from both [onCreate] and [onResume] because Samsung's One UI
     * clears the flags when the activity is resumed from the background, which
     * is exactly the case when it is launched from `ACTION_SCREEN_ON`.
     */
    private fun applyLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    /**
     * Closes the takeover and drops the user back to their own lock screen /
     * home screen.
     *
     * The takeover is now the same page as the run screen, so there is nothing
     * to "open" — tapping it just gets out of the way. `finishAndRemoveTask`
     * removes this task from the recents stack as well, so the user lands on
     * the launcher / keyguard rather than on a stale copy of the overlay.
     */
    private fun dismissToHome() {
        finishAndRemoveTask()
    }

    /**
     * Ensures the takeover is not stacked on top of the app's own run screen.
     *
     * A *manual* run (the plan screen's play button / test run) launches this
     * activity directly from the foreground, so `MainActivity` — already showing
     * the run screen — is left underneath. Dismissing the overlay then revealed
     * that identical run page, which is the "why am I looking at the same screen
     * again" complaint.
     *
     * ## Why this is now a no-op
     *
     * The takeover is declared `singleInstance` in the manifest, so it always
     * lives in its own task, separate from the app's `MainActivity` task. That
     * means:
     *
     *  - It is always the root of its own task, so there is never an app
     *    activity *underneath it in the same task* to clear.
     *  - Dismissing it with [finishAndRemoveTask] removes its task and lands the
     *    user on the launcher / keyguard, not on the app's run screen — because
     *    that run screen is in a different task.
     *
     * The previous implementation called `finishAffinity()` here, which finished
     * **every** activity in the task including the takeover itself. Because the
     * takeover shared the app's task on the manual-run path, that tore the
     * takeover down before it could draw a frame — the "takeover still isn't
     * working" bug. With `singleInstance` the whole problem disappears, so this
     * is deliberately left as a no-op rather than finishing anything.
     */
    private fun finishUnderlyingAppTask() {
        // Intentionally empty: `singleInstance` keeps the takeover in its own
        // task, so there is nothing underneath it to finish and nothing that
        // could accidentally finish the takeover itself.
    }

    /**
     * Pause/resume from the takeover's Pause pill.
     *
     * Mirrors [com.adhd.routineplanner.viewmodel.RunViewModel.togglePause] so the
     * two surfaces behave identically even when the app itself is not running:
     *
     *  - pausing stores the pause instant, which freezes every derived value,
     *    and cancels the pending in-run task alarms (a paused run must not start
     *    ringing part-way through);
     *  - resuming shifts the session start forward by the paused span so the
     *    elapsed time is preserved, then re-arms the alarms for the tasks *after*
     *    the current one — the current task keeps its remaining time, so
     *    re-registering it would either fire instantly or be swallowed by the
     *    first-task grace window. An untimed routine has no alarms to re-arm.
     *
     * The ongoing notification is re-posted immediately from the new state so
     * the shade says "Paused" without waiting for the app to be opened.
     */
    private fun togglePause() {
        val current = uiState ?: return
        val routine = current.routine ?: return
        // The setting lives on the routine; without it the control is not even
        // shown, but re-check so a stale surface can never pause anyway.
        if (!routine.allowPause) return
        val session = current.session ?: return

        lifecycleScope.launch {
            if (session.isPaused) {
                val fromIndex = current.state.currentIndex + 1
                val shiftedStart = sessionStore.resume(System.currentTimeMillis()) ?: return@launch
                if (!routine.isUntimed) {
                    alarmScheduler.cancelRunAlarms(routine.id)
                    alarmScheduler.scheduleRunAlarms(routine.id, shiftedStart, fromIndex)
                }
            } else {
                sessionStore.pause(System.currentTimeMillis())
                alarmScheduler.cancelRunAlarms(routine.id)
            }
            repostNotification()
            // Redraw now instead of waiting up to a second for the next tick.
            startRefreshing()
        }
    }

    /**
     * Re-posts the ongoing run notification from the persisted session, so the
     * shade and the lock-screen card match what the takeover just did.
     */
    private suspend fun repostNotification() {
        val session = sessionStore.session.firstOrNull() ?: return
        val snapshot = RunSnapshot.load(repository, session, System.currentTimeMillis()) ?: return
        RunNotification.postSnapshot(
            context = this,
            snapshot = snapshot,
            takeover = true,
        )
    }

    /**
     * Ends the run from the takeover's End routine button.
     *
     * The takeover is the same page as the run screen, so it carries the same
     * End routine control. Ending here has to do the same teardown the run
     * screen's `RunViewModel.stop()` does — cancel the ongoing notification
     * (which also clears the takeover guards) and clear the session — otherwise
     * the notification would keep re-arming the full-screen intent and the
     * takeover would immediately reappear on the next screen wake.
     *
     * The session clear runs on [lifecycleScope] but the activity is finished
     * straight after; `clear()` is a DataStore write that completes quickly, and
     * the notification is cancelled synchronously first so the full-screen
     * intent cannot fire again in the meantime.
     */
    private fun endRoutine() {
        refreshJob?.cancel()
        RunNotification.cancel(this)
        lifecycleScope.launch {
            sessionStore.clear()
            // The takeover is `singleInstance`, so it lives in its own task and
            // `finishAndRemoveTask` removes just that task. The app's own task
            // (if any) is separate and is left alone — ending the run from the
            // overlay must not tear down the app the user may return to.
            finishAndRemoveTask()
        }
    }

    companion object {
        /**
         * Builds the intent the run notification's full-screen intent fires.
         *
         * `FLAG_ACTIVITY_NEW_TASK` is required for a full-screen intent. The
         * activity is `singleInstance`, so a second full-screen intent is
         * delivered to the existing instance via [onNewIntent] rather than
         * stacking a copy — `CLEAR_TOP` is therefore unnecessary and was
         * removed, because on a `singleInstance` activity it could finish and
         * recreate the instance unexpectedly.
         */
        fun intent(context: Context): Intent =
            Intent(context, RunTakeoverActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
    }
}

/**
 * The over-lock-screen routine card.
 *
 * Renders the exact same [RunBody] as the in-app run screen — the colourful
 * card, the slow fill-up, the corner mini-grid and the End routine pill — so the
 * takeover and the run screen are literally the same page. Tapping anywhere, or
 * swiping down, dismisses the overlay and returns the user to their own lock
 * screen / home screen; the End routine pill ends the run outright.
 */
@Composable
private fun TakeoverScreen(
    state: RunUiState?,
    loaded: Boolean,
    onDismiss: () -> Unit,
    onEndRoutine: () -> Unit,
    onTogglePause: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
    ) {
        if (state == null) {
            // Brief blank state while the session loads; nothing to show yet.
            if (loaded) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No routine running",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary,
                    )
                }
            }
            return@Box
        }

        // The shared run body: the same card, fill animation, corner grid and
        // End routine pill as the in-app run screen. The takeover and the run
        // screen are now literally the same page.
        //
        // Tap-to-dismiss and swipe-down-to-dismiss are attached to the *card*
        // only, not the whole screen, so they do not swallow taps on the End
        // routine pill underneath it. Tapping the card still gets out of the
        // way; the pill is a deliberate, separate control.
        RunBody(
            state = state,
            onExit = onEndRoutine,
            cardModifier = Modifier
                .clickable { onDismiss() }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount > 0f) onDismiss()
                    }
                },
            // The pause pill, when the routine allows one. It lives outside the
            // card's tap/drag modifier, so pausing does not dismiss the overlay.
            onTogglePause = onTogglePause,
        )
    }
}
