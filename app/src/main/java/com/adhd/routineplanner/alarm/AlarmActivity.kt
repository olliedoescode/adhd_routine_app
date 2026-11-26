package com.adhd.routineplanner.alarm

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.adhd.routineplanner.MainActivity
import com.adhd.routineplanner.data.AlarmSettings
import com.adhd.routineplanner.data.AlarmSettingsStore
import com.adhd.routineplanner.data.RoutineRepository
import com.adhd.routineplanner.data.RunSessionStore
import com.adhd.routineplanner.domain.PlacedTask
import com.adhd.routineplanner.ui.theme.AdhdRoutineAppTheme
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
 * Shows the routine name and "Next: task (N min)", plays a short `USAGE_ALARM`
 * tone for about five seconds, and offers a Dismiss button. Tapping the text
 * opens the Run screen.
 */
@AndroidEntryPoint
class AlarmActivity : ComponentActivity() {

    @Inject
    lateinit var repository: RoutineRepository

    @Inject
    lateinit var alarmSettingsStore: AlarmSettingsStore

    @Inject
    lateinit var sessionStore: RunSessionStore

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

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

        val routineId = intent.getLongExtra(EXTRA_ROUTINE_ID, -1L)
        val taskIndex = intent.getIntExtra(EXTRA_TASK_INDEX, -1)

        // The test alarm uses a sentinel routine id: show generic copy and hide
        // the "Open run" action, since there is no routine to open. Both the
        // immediate preview and the delayed real alarm share this id, so the
        // task index does not matter here.
        val isTest = routineId == AlarmManagerScheduler.TEST_ROUTINE_ID

        // Resolve the routine and task names off the main thread, then hand the
        // values to Compose as state (spec section 15.9).
        val title = mutableStateOf(if (isTest) "Test alarm" else "Routine")
        val body = mutableStateOf(if (isTest) "Alarms are working." else "Time to start")

        setContent {
            AdhdRoutineAppTheme {
                AlarmScreen(
                    title = title.value,
                    body = body.value,
                    onOpenRun = if (isTest) {
                        null
                    } else {
                        {
                            openRun(routineId)
                            finish()
                        }
                    },
                    onDismiss = {
                        stopAlarm()
                        if (isTest) {
                            finish()
                        } else {
                            dismissToTakeover(routineId)
                        }
                    },
                )
            }
        }

        if (!isTest) {
            lifecycleScope.launch {
                val routine = repository.getRoutine(routineId)
                val tasks = repository.getTasks(routineId)
                val task = tasks.getOrNull(taskIndex)
                if (routine != null) title.value = routine.name.ifBlank { "Routine" }
                if (task != null) {
                    val name = task.name.ifBlank { PlacedTask.DEFAULT_NAME }
                    body.value = "Next: $name (${task.minutes} min)"
                }
            }
        }

        playAlarm()
    }

    /**
     * Rings the alarm using the user's saved settings (spec section 15.9).
     *
     * The settings live in DataStore, so they are read on a coroutine and the
     * tone starts as soon as they arrive. That is a few milliseconds in
     * practice; the screen is already visible by then, so the alarm never
     * appears silent.
     */
    private fun playAlarm() {
        lifecycleScope.launch {
            val settings = alarmSettingsStore.settings.firstOrNull() ?: AlarmSettings()
            player = AlarmTone.play(this@AlarmActivity, settings)
            if (settings.vibrate) {
                startVibration(settings.durationSeconds, settings.alarmVibrationStrength)
            }
            // Stop after the configured duration.
            window.decorView.postDelayed(
                { stopAlarm() },
                settings.durationSeconds * 1_000L,
            )
        }
    }

    /**
     * Runs a solid vibration for [seconds] at [strength] (0f..1f).
     *
     * A solid buzz rather than a pulse pattern: an alarm should feel like one
     * continuous, unmissable vibration. The strength comes from the user's
     * "Alarm vibration strength" setting.
     */
    private fun startVibration(seconds: Int, strength: Float) {
        val service = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!service.hasVibrator()) return
        vibrator = service
        Vibration.solid(this, seconds * 1_000L, strength)
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
        lifecycleScope.launch {
            val present = sessionStore.userPresent.firstOrNull() ?: false
            if (!present) {
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

@Composable
private fun AlarmScreen(
    title: String,
    body: String,
    /** Null for the test alarm, which has no routine to open. */
    onOpenRun: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.titleMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = if (onOpenRun != null) {
                Modifier.clickable(onClick = onOpenRun)
            } else {
                Modifier
            },
        )
        Spacer(Modifier.height(40.dp))
        Button(onClick = onDismiss) {
            Text("Dismiss")
        }
    }
}
