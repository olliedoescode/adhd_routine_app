package com.adhd.routineplanner

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.adhd.routineplanner.alarm.AlarmActivity
import com.adhd.routineplanner.data.RunSessionStore
import com.adhd.routineplanner.ui.AppNavHost
import com.adhd.routineplanner.ui.theme.AdhdRoutineAppTheme
import com.adhd.routineplanner.ui.theme.Background
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var sessionStore: RunSessionStore

    /**
     * The routine the nav host should open in run mode, or `null` for the
     * routine list. Held as Compose state so [onNewIntent] can redirect an
     * already-composed nav host when the app is brought to the front during a
     * run.
     */
    private var openRunRoutineId by mutableStateOf<Long?>(null)

    /** True once the initial session lookup has completed. */
    private var resolved by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // When launched from the alarm screen's "open Run" tap, start directly
        // on the Run destination for that routine (spec section 15.9).
        val explicitRunId = runIdFrom(intent)
        openRunRoutineId = explicitRunId
        resolved = explicitRunId != null

        // Consume the "open run" action. The activity is `singleTop`, so if the
        // user later closes the app with the back gesture and taps the launcher
        // icon, Android re-delivers this same intent. Leaving the action in
        // place would make that relaunch jump straight back into the run screen
        // — the "back starts a routine" symptom again, just via the launcher.
        // Clearing it means a relaunch is a plain cold start on the list.
        if (explicitRunId != null) {
            intent.action = null
            intent.removeExtra(AlarmActivity.EXTRA_ROUTINE_ID)
        }

        // Otherwise, if a run is already in progress (started by the routine's
        // start alarm), opening the app should land straight in run mode rather
        // than on the routine list (spec section 15.8). The session is read
        // asynchronously from DataStore, so the nav host is only composed once
        // we know whether a run is active; this avoids a flash of the list.
        //
        // A *restored* activity (the process was killed and the user came back)
        // must not do this: the nav host restores its own back stack, and
        // forcing run mode on top of it would push a duplicate Run entry. The
        // saved instance state is the signal that this is a restore, not a
        // fresh launch.
        if (explicitRunId == null) {
            if (savedInstanceState != null) {
                // Restored: let the nav host restore its own destination.
                resolved = true
            } else {
                lifecycleScope.launch {
                    val session = sessionStore.session.firstOrNull()
                    openRunRoutineId = session?.routineId
                    resolved = true
                }
            }
        }

        setContent {
            AdhdRoutineAppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Background) {
                    if (resolved) {
                        AppNavHost(openRunRoutineId = openRunRoutineId)
                    }
                }
            }
        }
    }

    /**
     * Handles the app being brought to the front while it is already running,
     * e.g. by [com.adhd.routineplanner.alarm.UserPresentReceiver] on unlock.
     *
     * Only an intent that explicitly names a routine switches the nav host to
     * that run. A plain "bring the app to the front" intent (launcher tap,
     * recents, a notification tap without a run id) is deliberately ignored.
     *
     * Re-checking the session here was the cause of the "back on the home
     * screen starts a routine" bug: the back gesture finished the activity, the
     * launcher re-delivered a bare intent, the session was still alive, and the
     * nav host was pushed straight into the run screen instead of the app
     * closing. A run that is genuinely in progress is still reachable from the
     * routine list, and the ongoing notification is what tells the user it is
     * running.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val explicitRunId = runIdFrom(intent) ?: return
        openRunRoutineId = explicitRunId
        resolved = true
    }

    /** Extracts the routine id from an "open run" intent, or `null`. */
    private fun runIdFrom(intent: Intent?): Long? {
        if (intent?.action != AlarmActivity.ACTION_OPEN_RUN) return null
        return intent.getLongExtra(AlarmActivity.EXTRA_ROUTINE_ID, -1L).takeIf { it >= 0 }
    }
}
