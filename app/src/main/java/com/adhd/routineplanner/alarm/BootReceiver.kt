package com.adhd.routineplanner.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Rebuilds every alarm after a reboot or a clock/timezone change
 * (spec section 9).
 *
 * Listens for `BOOT_COMPLETED`, `TIME_SET` and `TIMEZONE_CHANGED`; all three
 * call `rescheduleAll()`, which cancels and rebuilds from the database. The
 * app must have been opened once after install to receive these broadcasts.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var scheduler: AlarmScheduler

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> Unit
            else -> return
        }

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                scheduler.rescheduleAll()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to reschedule alarms after ${intent.action}", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
