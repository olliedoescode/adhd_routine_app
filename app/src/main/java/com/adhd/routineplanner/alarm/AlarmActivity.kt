package com.adhd.routineplanner.alarm

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.adhd.routineplanner.MainActivity
import com.adhd.routineplanner.data.AlarmSettings
import com.adhd.routineplanner.data.AlarmSettingsStore
import com.adhd.routineplanner.data.RoutineRepository
import com.adhd.routineplanner.data.RunSessionStore
import com.adhd.routineplanner.data.ThemeStore
import com.adhd.routineplanner.data.VibrationPattern
import com.adhd.routineplanner.domain.PlacedTask
import com.adhd.routineplanner.domain.Routine
import com.adhd.routineplanner.domain.Snooze
import com.adhd.routineplanner.ui.theme.AppThemeFromStore
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The full-screen alarm screen (spec section 15.9).
 *
 * Launched over the lock screen with `setShowWhenLocked` and `setTurnScreenOn`.
 * It is a *real* alarm, not a chime:
 *
 *  - the **headline is the task's own name** ("Shower"), because that is the
 *    thing the user needs to act on — the routine name is the small line
 *    underneath;
 *  - the tone **loops until the user acts** (see [AlarmTone.play]), so it cannot
 *    be slept through the way a five-second chime can;
 *  - it offers **Snooze** as well as **Dismiss**.
 *
 * Snooze is only offered when the routine can genuinely absorb five more
 * minutes — either the task has that much slack inside its rendered length, or
 * the routine's unclaimed buffer can cover it ([Snooze.canSnooze]). When it
 * cannot, the button is absent rather than present-and-broken, and the screen
 * says why.
 *
 * The activity is `singleInstance`, so a second task alarm arriving while this
 * screen is up is delivered to the existing instance through [onNewIntent].
 * That override re-reads the new task, replays the tone and re-arms the
 * vibration, so a back-to-back alarm is never silently swallowed.
 */
@AndroidEntryPoint
class AlarmActivity : ComponentActivity() {

    @Inject
    lateinit var repository: RoutineRepository

    @Inject
    lateinit var alarmSettingsStore: AlarmSettingsStore

    @Inject
    lateinit var sessionStore: RunSessionStore

    @Inject
    lateinit var scheduler: AlarmScheduler

    /** Only used to paint this screen in the user's chosen theme. */
    @Inject
    lateinit var themeStore: ThemeStore

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    /** The task name, which is the headline of a real alarm. */
    private val taskTitle = mutableStateOf("Alarm")

    /** The routine name, shown small underneath the task. */
    private val routineLine = mutableStateOf("")

    /** The "N min" duration line, or the snooze explanation. */
    private val detail = mutableStateOf("")

    /** Whether the Snooze button should be shown for the current task. */
    private val canSnooze = mutableStateOf(false)

    /** The routine/task currently ringing, so Snooze knows what to re-arm. */
    private var currentRoutineId: Long = -1L
    private var currentTaskIndex: Int = -1

    /**
     * Set before every *deliberate* exit (Snooze, Dismiss, Open run, test
     * finish) so [onUserLeaveHint] knows not to drag the screen back.
     */
    private var leavingIntentionally: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over the lock screen and turn the screen on (spec section 15.9).
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        keyguard.requestDismissKeyguard(this, null)

        // The alarm screen must not be dismissable except through Snooze or
        // Dismiss. Outside taps do nothing, and Back is swallowed, so the user
        // cannot "click off" a ringing alarm and leave it ringing in the
        // background with no obvious way to answer it.
        setFinishOnTouchOutside(false)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Ignored on purpose — answer the alarm with Snooze/Dismiss.
                }
            },
        )

        setContent {
            AppThemeFromStore(themeStore) {
                AlarmScreen(
                    title = taskTitle.value,
                    routineLine = routineLine.value,
                    detail = detail.value,
                    canSnooze = canSnooze.value,
                    onOpenRun = if (isTest()) {
                        null
                    } else {
                        {
                            leavingIntentionally = true
                            openRun(currentRoutineId)
                            finish()
                        }
                    },
                    onSnooze = { snooze() },
                    onDismiss = {
                        stopAlarm()
                        if (isTest()) {
                            leavingIntentionally = true
                            finish()
                        } else {
                            dismissToTakeover(currentRoutineId)
                        }
                    },
                )
            }
        }

        load(intent)
        playAlarm()
    }

    /**
     * Handles a second alarm arriving while this screen is already up.
     *
     * The activity is `singleInstance`, so a later task alarm reuses this
     * instance instead of creating a new one. A *different* task's alarm must
     * re-read the intent and replay the tone so it is not silently swallowed.
     *
     * But a re-delivery for the *same* task that is already ringing must not
     * restart the tone or vibration. Restarting it is exactly what made the
     * alarm "reset itself" and repeat its audio/vibration pattern. The re-pin
     * in [onUserLeaveHint] and any duplicated alarm delivery both land here, so
     * the guard keeps the current ring going instead of restarting it.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val newRoutineId = intent.getLongExtra(EXTRA_ROUTINE_ID, -1L)
        val newTaskIndex = intent.getIntExtra(EXTRA_TASK_INDEX, -1)
        setIntent(intent)
        // Same task, still ringing: ignore the re-delivery, keep ringing.
        if (newRoutineId == currentRoutineId &&
            newTaskIndex == currentTaskIndex &&
            player != null
        ) {
            return
        }
        stopAlarm()
        load(intent)
        playAlarm()
    }

    private fun isTest(): Boolean =
        intent.getLongExtra(EXTRA_ROUTINE_ID, -1L) == AlarmManagerScheduler.TEST_ROUTINE_ID

    /**
     * Reads the routine and task named by [source] and fills the screen.
     *
     * The test alarm uses a sentinel routine id: it shows generic copy and hides
     * the "Open run" and Snooze actions, since there is no routine to open or
     * re-arm.
     */
    private fun load(source: Intent) {
        val routineId = source.getLongExtra(EXTRA_ROUTINE_ID, -1L)
        val taskIndex = source.getIntExtra(EXTRA_TASK_INDEX, -1)
        currentRoutineId = routineId
        currentTaskIndex = taskIndex

        if (routineId == AlarmManagerScheduler.TEST_ROUTINE_ID) {
            taskTitle.value = "Test alarm"
            routineLine.value = ""
            detail.value = "Alarms are working."
            canSnooze.value = false
            return
        }

        // Sensible placeholders until the database read lands, so the screen is
        // never blank on the first frame.
        taskTitle.value = "Time to start"
        routineLine.value = ""
        detail.value = ""
        canSnooze.value = false

        lifecycleScope.launch {
            val routine: Routine? = repository.getRoutine(routineId)
            val tasks: List<PlacedTask> = repository.getTasks(routineId)
            val task = tasks.getOrNull(taskIndex)

            if (task != null) {
                taskTitle.value = task.name.ifBlank { PlacedTask.DEFAULT_NAME }
                detail.value = "${task.minutes} min"
            }
            if (routine != null) {
                routineLine.value = routine.name.ifBlank { "Routine" }
            }

            // Snooze is only offered when the routine can absorb five more
            // minutes without running past its end.
            if (routine != null && task != null) {
                val allowed = Snooze.canSnooze(routine, tasks, taskIndex)
                canSnooze.value = allowed
                if (!allowed) {
                    detail.value = "${task.minutes} min · no time to snooze"
                }
            }
        }
    }

    /**
     * Rings the alarm using the user's saved settings (spec section 15.9).
     *
     * The settings live in DataStore, so they are read on a coroutine and the
     * tone starts as soon as they arrive. That is a few milliseconds in
     * practice; the screen is already visible by then, so the alarm never
     * appears silent.
     *
     * The tone now loops (see [AlarmTone.play]) and is stopped only by Snooze,
     * Dismiss or [onDestroy] — a real alarm keeps ringing until it is answered.
     */
    private fun playAlarm() {
        lifecycleScope.launch {
            val settings = alarmSettingsStore.settings.firstOrNull() ?: AlarmSettings()
            player = AlarmTone.play(this@AlarmActivity, settings)
            if (settings.vibrate) {
                startVibration(
                    settings.durationSeconds,
                    settings.alarmVibrationStrength,
                    settings.vibrationPattern,
                )
            }
        }
    }

    /**
     * Runs the alarm's vibration for [seconds] at [strength] (0f..1f), in the
     * user's chosen [pattern].
     *
     * The strength comes from the "Alarm vibration strength" setting and the
     * rhythm from the "Vibration pattern" setting. [VibrationPattern.Solid] is
     * the original single unbroken buzz; the other patterns repeat for the whole
     * duration so the buzz keeps going until the alarm stops.
     */
    private fun startVibration(seconds: Int, strength: Float, pattern: VibrationPattern) {
        val service = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!service.hasVibrator()) return
        vibrator = service
        Vibration.alarm(this, seconds * 1_000L, strength, pattern)
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
        } catch (_: Throwable) {
            // Already cancelled.
        }
        vibrator = null
    }

    private fun stopAlarm() {
        AlarmTone.stop(player)
        player = null
        stopVibration()
    }

    /**
     * Snoozes the ringing task alarm by five minutes.
     *
     * The timeline is deliberately untouched: the snooze is a single re-arm of
     * this task's alarm ([AlarmScheduler.snoozeTaskAlarm]), so the run screen
     * keeps counting down exactly as before and the alarm simply rings again on
     * top of it. The button is only shown when [Snooze.canSnooze] allowed it, so
     * this never pushes the routine past its end.
     */
    private fun snooze() {
        leavingIntentionally = true
        stopAlarm()
        if (currentRoutineId >= 0 && currentTaskIndex >= 0) {
            scheduler.snoozeTaskAlarm(
                routineId = currentRoutineId,
                taskIndex = currentTaskIndex,
                minutes = Snooze.SNOOZE_MINUTES,
            )
        }
        finish()
    }

    /**
     * Handles the user stopping a real task alarm.
     *
     * The rule (see the state table in the plan):
     *
     *  - If the user has **not** unlocked the phone during this run
     *    ([RunSessionStore.userPresent] is `false`), they were locked when the
     *    alarm fired — either the routine had just started (state B) or they
     *    were still locked mid-run (state E). Either way, stopping the alarm
     *    must take them to the lock-screen takeover so they consciously
     *    acknowledge the routine.
     *  - If the user **has** unlocked the phone during this run (state D), they
     *    are already demonstrably on their phone and have acknowledged it, so a
     *    later task alarm must not drag them to the takeover again. We simply
     *    close the alarm screen.
     *
     * The presence flag is read once, off the main thread, then acted on.
     */
    private fun dismissToTakeover(routineId: Long) {
        leavingIntentionally = true
        lifecycleScope.launch {
            val present = sessionStore.userPresent.firstOrNull() ?: false
            if (!present) {
                // Launch the takeover *directly*. The alarm screen is in the
                // foreground, so a direct start is allowed and lands the user on
                // the over-keyguard takeover immediately.
                //
                // The run notification is deliberately *not* re-posted here. The
                // old code called `repostForTakeover`, which bumped the
                // full-screen intent's request code; Android treats a bumped code
                // as a brand-new intent and launches it at once — which is what
                // made the alarm re-fire the instant it was dismissed. The
                // notification already carries the stable full-screen intent for
                // the whole run, and [UserPresentReceiver] re-arms it on the next
                // genuine screen-on, so nothing needs to be re-posted now.
                val takeover = RunTakeoverActivity.intent(this@AlarmActivity)
                startActivity(takeover)
            }
            finish()
        }
    }

    private fun openRun(routineId: Long) {
        val intent = Intent(this, MainActivity::class.java).apply {
            // `setAction`, not `action =`: inside `apply` the name resolves to
            // the read-only `Intent.action` getter.
            setAction(ACTION_OPEN_RUN)
            putExtra(EXTRA_ROUTINE_ID, routineId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    /**
     * Keeps the ringing alarm in front when the user presses Home or Recents.
     *
     * Back is already blocked and outside-taps are ignored, but the system Home
     * button cannot be intercepted. Re-pinning the alarm screen is the standard
     * way to make an alarm genuinely non-dismissable, so the only ways out are
     * Snooze and Dismiss. [leavingIntentionally] is set before every legitimate
     * exit so this never fights the Snooze/Dismiss/Open-run paths, and it does
     * nothing while the screen is off (power button) or once the tone has
     * stopped.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (leavingIntentionally || isFinishing || player == null) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!power.isInteractive) return
        try {
            startActivity(
                Intent(this, AlarmActivity::class.java).apply {
                    putExtra(EXTRA_ROUTINE_ID, currentRoutineId)
                    putExtra(EXTRA_TASK_INDEX, currentTaskIndex)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                    )
                },
            )
        } catch (_: Throwable) {
            // Best-effort; the tone keeps ringing regardless.
        }
    }

    override fun onDestroy() {
        stopAlarm()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ROUTINE_ID: String = "extra_routine_id"
        const val EXTRA_TASK_INDEX: String = "extra_task_index"
        const val ACTION_OPEN_RUN: String = "com.adhd.routineplanner.OPEN_RUN"

        /** Builds the intent that opens this screen for a routine/task. */
        fun intent(context: Context, routineId: Long, taskIndex: Int): Intent =
            Intent(context, AlarmActivity::class.java).apply {
                putExtra(EXTRA_ROUTINE_ID, routineId)
                putExtra(EXTRA_TASK_INDEX, taskIndex)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
    }
}

/**
 * The alarm screen itself.
 *
 * Laid out like a phone alarm: a small "ALARM" label, the task name as the big
 * headline, the routine name and duration underneath, then Snooze and Dismiss.
 * Tapping the headline opens the run screen.
 */
@Composable
private fun AlarmScreen(
    title: String,
    routineLine: String,
    detail: String,
    canSnooze: Boolean,
    /** Null for the test alarm, which has no routine to open. */
    onOpenRun: (() -> Unit)?,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 28.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A clock mark anchors the screen as an alarm rather than a generic
        // prompt, and gives the label something to hang from.
        Icon(
            imageVector = Icons.Filled.Alarm,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "ALARM",
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = if (onOpenRun != null) {
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenRun)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            } else {
                Modifier
            },
        )
        if (routineLine.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = routineLine,
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
        if (detail.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(48.dp))

        // Snooze is the secondary action and only appears when the routine can
        // absorb five more minutes. Dismiss is always available.
        if (canSnooze) {
            OutlinedButton(
                onClick = onSnooze,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    text = "Snooze ${Snooze.SNOOZE_MINUTES} min",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        Button(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TextPrimary),
        ) {
            Text(
                text = "Dismiss",
                style = MaterialTheme.typography.titleMedium,
                color = Background,
            )
        }
    }
}
