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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.adhd.routineplanner.MainActivity
import com.adhd.routineplanner.data.RoutineRepository
import com.adhd.routineplanner.data.RunSessionStore
import com.adhd.routineplanner.ui.theme.AdhdRoutineAppTheme
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.Surface as SurfaceColor
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary
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
 * It is deliberately *not* a lock: the user can always dismiss it with the
 * button or by swiping, which just calls [finish] and reveals the normal lock
 * screen. Nothing about the run is stopped or changed. The point is friction
 * and a reminder, not a barrier.
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
     * The snapshot currently on screen, or `null` while it loads.
     *
     * Compose state, not a plain field: the screen has to redraw when the
     * session finally arrives, and the "No routine running" branch has to
     * appear when it never does.
     */
    private var snapshot by mutableStateOf<RunSnapshot?>(null)

    /**
     * True once a snapshot has been shown; used to decide when to give up.
     *
     * Also Compose state, for the same reason as [snapshot]: the empty-state
     * branch is gated on it, so a plain field would never trigger the redraw
     * that shows it.
     */
    private var loaded by mutableStateOf(false)

    /**
     * The running session-refresh loop, so [onNewIntent] can restart it.
     *
     * A full-screen intent for an already-existing `singleInstance` activity is
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

        // Show over the lock screen and turn the screen on, exactly like the
        // alarm screen.
        applyLockScreenFlags()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            AdhdRoutineAppTheme {
                TakeoverScreen(
                    snapshot = snapshot,
                    loaded = loaded,
                    onOpenRun = { routineId ->
                        openRun(routineId)
                        finish()
                    },
                    onDismiss = { finish() },
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
     */
    private fun startRefreshing() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            var attempts = 0
            while (true) {
                val session = sessionStore.session.firstOrNull()
                if (session == null) {
                    // Nothing running. Give the store a generous moment to warm
                    // up (about three seconds) before concluding there is no run.
                    if (attempts++ >= 15) {
                        if (!loaded) finish()
                        break
                    }
                    delay(200L)
                    continue
                }
                val snap = RunSnapshot.load(repository, session, System.currentTimeMillis())
                if (snap == null) {
                    // The routine was deleted mid-run: nothing to show.
                    if (!loaded) finish()
                    break
                }
                snapshot = snap
                loaded = true
                // A finished run has nothing left to hold the screen for.
                if (snap.isFinished) break
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

    /** Opens the real run screen, then this overlay closes itself. */
    private fun openRun(routineId: Long) {
        val intent = Intent(this, MainActivity::class.java).apply {
            // `setAction`, not `action =`: inside `apply` the name resolves to
            // the read-only `Intent.action` getter.
            setAction(AlarmActivity.ACTION_OPEN_RUN)
            putExtra(AlarmActivity.EXTRA_ROUTINE_ID, routineId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    companion object {
        /**
         * Builds the intent the run notification's full-screen intent fires.
         *
         * `FLAG_ACTIVITY_NEW_TASK` is required for a full-screen intent;
         * `CLEAR_TOP` keeps a single copy if one is somehow already showing.
         */
        fun intent(context: Context): Intent =
            Intent(context, RunTakeoverActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
    }
}

/**
 * The over-lock-screen routine card.
 *
 * Shows the routine name, the current task, the time left on it and the next
 * task, plus a progress bar for the current task. Tapping the card opens the
 * full run screen; the Dismiss button (and the hint below it) closes the
 * overlay and reveals the normal lock screen.
 */
@Composable
private fun TakeoverScreen(
    snapshot: RunSnapshot?,
    loaded: Boolean,
    onOpenRun: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (snapshot == null) {
            // Brief blank state while the session loads; nothing to show yet.
            if (loaded) {
                Text(
                    text = "No routine running",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextSecondary,
                )
            }
            return@Column
        }

        Text(
            text = snapshot.routineName,
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))

        Surface(
            color = SurfaceColor,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenRun(snapshot.routineId) },
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = snapshot.taskName ?: "Routine",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        snapshot.isBeforeStart -> "Starting soon"
                        snapshot.isFinished -> "Routine finished"
                        else -> "${RunSnapshot.remainingLabel(snapshot.state.secondsRemaining)} left"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { snapshot.state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                val next = snapshot.nextLine()
                if (next.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = next,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        Text(
            text = "Tap to open the run · swipe or dismiss to use your phone",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.clickable { onDismiss() },
        )
    }
}
