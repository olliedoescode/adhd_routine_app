package com.adhd.routineplanner

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import com.adhd.routineplanner.alarm.AlarmActivity
import com.adhd.routineplanner.data.RunSessionStore
import com.adhd.routineplanner.data.ThemeStore
import com.adhd.routineplanner.ui.AppNavHost
import com.adhd.routineplanner.ui.theme.AdhdRoutineAppTheme
import com.adhd.routineplanner.ui.theme.AppTheme
import com.adhd.routineplanner.ui.theme.Background
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var sessionStore: RunSessionStore

    @Inject
    lateinit var themeStore: ThemeStore

    /**
     * The theme the user picked in Settings. Collected from [themeStore] into
     * activity state so a change repaints the whole app immediately, rather
     * than waiting for the next cold start.
     */
    private var appTheme by mutableStateOf(AppTheme.DEFAULT)

    /**
     * The routine the nav host should open in run mode, or `null` for the
     * routine list. Held as Compose state so [onNewIntent] can redirect an
     * already-composed nav host when the app is brought to the front during a
     * run.
     */
    private var openRunRoutineId by mutableStateOf<Long?>(null)

    /** True once the initial session lookup has completed. */
    private var resolved by mutableStateOf(false)

    /**
     * True once the stored theme has been read.
     *
     * The first frame is held back until then: the default palette is dark, so
     * drawing before the read completes would flash a dark page at every cold
     * start for someone who chose a light theme. The read is one DataStore
     * lookup, done in parallel with the session lookup above, so it costs
     * nothing that was not already being waited on.
     */
    private var themeLoaded by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The status and navigation bar icon colours follow the chosen theme:
        // light content on a dark palette, dark content on a light one.
        applyBars(appTheme)
        lifecycleScope.launch {
            themeStore.theme.collect { theme ->
                appTheme = theme
                themeLoaded = true
                applyBars(theme)
            }
        }

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
            AdhdRoutineAppTheme(theme = appTheme) {
                if (themeLoaded) {
                    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
                        if (resolved) {
                            AppNavHost(openRunRoutineId = openRunRoutineId)
                        }
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

    /**
     * Tints the system bars and the window background to match [theme]. Both bars
     * are drawn over the app's own background (edge-to-edge), so the scrim stays
     * transparent and only the icon colour changes.
     */
    private fun applyBars(theme: AppTheme) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (theme.isDark) {
            SystemBarStyle.dark(transparent)
        } else {
            SystemBarStyle.light(transparent, transparent)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)

        // The XML window background is the dark launch colour. Repaint it so a
        // light theme does not flash a dark rectangle while the activity comes
        // back from recents. (A genuinely cold start still uses the XML colour
        // until the stored theme has been read.)
        window.setBackgroundDrawable(ColorDrawable(theme.palette.background.toArgb()))
    }

    /** Extracts the routine id from an "open run" intent, or `null`. */
    private fun runIdFrom(intent: Intent?): Long? {
        if (intent?.action != AlarmActivity.ACTION_OPEN_RUN) return null
        return intent.getLongExtra(AlarmActivity.EXTRA_ROUTINE_ID, -1L).takeIf { it >= 0 }
    }
}
