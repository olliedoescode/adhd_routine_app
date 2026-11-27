package com.adhd.routineplanner.alarm

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.adhd.routineplanner.data.RoutineRepository
import com.adhd.routineplanner.data.RunSessionStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the app process alive for the whole duration of a run.
 *
 * ## Why this exists
 *
 * The lock-screen takeover is driven by [UserPresentReceiver], which listens for
 * `ACTION_SCREEN_ON` and `ACTION_USER_PRESENT`. Those actions cannot be delivered
 * to a manifest-declared receiver, so the receiver is registered at runtime in
 * `App.onCreate`. A runtime receiver only exists while the process does — and
 * swiping the app away from Recents killed the process, so from then on the
 * takeover silently stopped working until the app was reopened ("works once,
 * then stops after I swipe it away").
 *
 * A foreground service is the one sanctioned way to keep a process alive after
 * its task is removed. While a run is active this service holds the same
 * ongoing notification the user already sees, so there is nothing new on
 * screen; it simply keeps the process (and therefore the screen-on receiver)
 * alive so the takeover keeps firing on every wake.
 *
 * ## Contract
 *
 *  - [ensureRunning] is called from [RunNotification.show] for every run update,
 *    so the service starts with the first post and stays up.
 *  - [stop] is called from [RunNotification.cancel] when the run ends.
 *  - The service is `START_STICKY`. If the platform ever restarts it with a
 *    null intent (process reclaimed), it rebuilds the live card from the
 *    persisted session rather than showing nothing.
 */
@AndroidEntryPoint
class RunForegroundService : Service() {

    @Inject
    lateinit var sessionStore: RunSessionStore

    @Inject
    lateinit var repository: RoutineRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val provided: Notification? = intent?.getParcelableExtra(EXTRA_NOTIFICATION)
        if (provided != null) {
            // Normal path: we were handed the freshly-built live card.
            startForeground(RunNotification.NOTIFICATION_ID, provided)
            return START_STICKY
        }

        // System restart with no intent (START_STICKY after process death).
        // Post a minimal placeholder *synchronously* to satisfy the
        // startForeground deadline, then rebuild the real card from the session.
        startForeground(RunNotification.NOTIFICATION_ID, RunNotification.buildPlaceholder(this))
        scope.launch {
            val session = sessionStore.session.firstOrNull()
            val snapshot = session?.let {
                RunSnapshot.load(repository, it, System.currentTimeMillis())
            }
            if (snapshot == null || snapshot.isFinished) {
                stopSelf()
            } else {
                RunNotification.postSnapshot(
                    context = this@RunForegroundService,
                    snapshot = snapshot,
                    takeover = true,
                )
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RunForegroundService"
        private const val EXTRA_NOTIFICATION = "extra_notification"

        /**
         * True while this process believes the service is alive. Only an
         * optimisation: calling `startForegroundService` again for a service
         * that is already running is harmless.
         */
        @Volatile
        private var running: Boolean = false

        /**
         * Starts (or nudges) the foreground service, handing it [notification]
         * so the first `startForeground` call needs no extra work.
         */
        fun ensureRunning(context: Context, notification: Notification) {
            if (running) return
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RunForegroundService::class.java)
                        .putExtra(EXTRA_NOTIFICATION, notification),
                )
            } catch (t: Throwable) {
                // A refused background start must never break the run; the
                // notification is still posted and the alarm path keeps working.
                Log.w(TAG, "Could not start the run foreground service", t)
            }
        }

        /** Stops the service when the run ends. */
        fun stop(context: Context) {
            running = false
            try {
                context.stopService(Intent(context, RunForegroundService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "Could not stop the run foreground service", t)
            }
        }
    }
}
