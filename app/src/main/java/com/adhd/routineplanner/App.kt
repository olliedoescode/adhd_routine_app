package com.adhd.routineplanner

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.adhd.routineplanner.alarm.AlarmScheduler
import com.adhd.routineplanner.alarm.NotificationChannels
import com.adhd.routineplanner.alarm.UserPresentReceiver
import com.adhd.routineplanner.data.AlarmSettings
import com.adhd.routineplanner.data.AlarmSettingsStore
import com.adhd.routineplanner.data.DatabaseSeeder
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point. [HiltAndroidApp] generates the Hilt component that
 * the rest of the app injects from.
 *
 * On startup it seeds the database on first run, creates the alarm notification
 * channel (spec section 9), and rebuilds every alarm so a process restart never
 * leaves the schedule stale.
 */
@HiltAndroidApp
class App : Application() {

    @Inject
    lateinit var seeder: DatabaseSeeder

    @Inject
    lateinit var alarmScheduler: AlarmScheduler

    @Inject
    lateinit var alarmSettingsStore: AlarmSettingsStore

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // ACTION_SCREEN_ON cannot be declared in the manifest, so it has to be
        // registered at runtime. It is what lets the lock-screen takeover appear
        // the moment the screen wakes, rather than only after unlock.
        ContextCompat.registerReceiver(
            this,
            UserPresentReceiver(),
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        appScope.launch {
            // Create the channels with the user's saved tone rather than the
            // system default, so a tone picked in Settings is what the alarm
            // notification actually rings with.
            val settings = alarmSettingsStore.settings.firstOrNull() ?: AlarmSettings()
            NotificationChannels.ensureCreated(this@App, settings)

            seeder.seedIfEmpty()
            // Rebuild alarms after seeding so the sample routine is scheduled.
            alarmScheduler.rescheduleAll()
        }
    }
}
