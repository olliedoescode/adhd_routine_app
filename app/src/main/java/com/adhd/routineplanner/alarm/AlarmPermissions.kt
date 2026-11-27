package com.adhd.routineplanner.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Status of every alarm-related permission (spec section 9, 15.10).
 *
 * Pure data so the checklist UI can render one row per requirement and the
 * statuses can be re-checked on resume.
 */
data class AlarmPermissionStatus(
    val notificationsGranted: Boolean,
    val exactAlarmsGranted: Boolean,
    val fullScreenIntentGranted: Boolean,
    val batteryUnrestricted: Boolean,
    val overlayGranted: Boolean,
)

/**
 * Checks and deep-links the alarm permissions (spec section 9, 15.10).
 *
 * Each `*Intent` returns the settings screen that grants the permission, or
 * `null` when the platform does not expose one. The checklist uses these for
 * its per-row buttons.
 */
object AlarmPermissions {

    /** Reads the current status of every requirement. */
    fun status(context: Context): AlarmPermissionStatus = AlarmPermissionStatus(
        notificationsGranted = notificationsGranted(context),
        exactAlarmsGranted = exactAlarmsGranted(context),
        fullScreenIntentGranted = fullScreenIntentGranted(context),
        batteryUnrestricted = batteryUnrestricted(context),
        overlayGranted = overlayGranted(context),
    )

    /** `POST_NOTIFICATIONS` on Android 13+, always true below that. */
    fun notificationsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** `canScheduleExactAlarms()` on Android 12+, always true below that. */
    fun exactAlarmsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return manager.canScheduleExactAlarms()
    }

    /** `canUseFullScreenIntent()` on Android 14+, always true below that. */
    fun fullScreenIntentGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.canUseFullScreenIntent()
    }

    /** True when the app is exempt from battery optimisation. */
    fun batteryUnrestricted(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * "Display over other apps" (`SYSTEM_ALERT_WINDOW`) on Android 6+, always
     * true below that.
     *
     * The screen-on / unlock receiver uses this permission to open the
     * lock-screen takeover *directly* from the background. Without it, a plain
     * broadcast receiver is blocked by the Android 10+ background-activity-start
     * restriction, which is exactly why the takeover did nothing on the
     * biometric-unlock path.
     */
    fun overlayGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return Settings.canDrawOverlays(context)
    }

    /** Deep link to the app's notification settings. */
    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Deep link to Special app access > Alarms and reminders. */
    fun exactAlarmSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Deep link to Special app access > Full-screen notifications. */
    fun fullScreenIntentSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Deep link to the battery-optimisation exemption prompt. */
    fun batterySettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Deep link to Special app access > Display over other apps. */
    fun overlaySettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
