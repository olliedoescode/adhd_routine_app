package com.adhd.routineplanner.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.adhd.routineplanner.data.AlarmSettings

/**
 * Notification channel setup (spec section 9, 15.9).
 *
 * Two channels:
 *  - [ALARM_CHANNEL_ID]: high importance with `USAGE_ALARM` audio so Do Not
 *    Disturb does not silence it. Fires at task boundaries.
 *  - [RUN_CHANNEL_ID]: high importance, muted per notification. Holds the single
 *    ongoing notification that shows the task currently running and the time
 *    left.
 *
 * Created idempotently; safe to call from the receiver, the application, the
 * run view model and the checklist.
 */
object NotificationChannels {

    const val ALARM_CHANNEL_ID: String = "alarm_channel"

    /**
     * Versioned id. Each bump was forced by a platform rule that makes an
     * existing channel immutable:
     *
     *  - `run_channel` was created at `IMPORTANCE_LOW`; the platform ignores
     *    re-creation of an existing channel, so a new id is the only way to
     *    change its importance.
     *  - `run_channel_v2` was also `IMPORTANCE_LOW`, and **Android refuses to
     *    fire a full-screen intent from a low-importance channel**, which is
     *    why the lock-screen takeover never appeared.
     *  - `run_channel_v3` was `IMPORTANCE_HIGH` but its lock-screen visibility
     *    was not applied on installs that had already created it, so the card
     *    did not show on the lock screen.
     *  - `run_channel_v4` was `IMPORTANCE_HIGH` with `VISIBILITY_PUBLIC`, but
     *    it was created with `setSound(null, null)`. A channel with no sound
     *    is treated as a *silent* channel by the platform, and silent channels
     *    are excluded from the lock screen on several OEM builds (Samsung
     *    included) regardless of `lockscreenVisibility`. `run_channel_v5`
     *    therefore keeps a real (but inaudible) sound so the channel is not
     *    classified as silent, and the notification itself is muted with
     *    `setSilent(true)` instead.
     *  - `run_channel_v5` had `enableVibration(false)`. While a notification
     *    from a vibration-disabled channel is showing, several OEM builds
     *    (Samsung included) suppress *app-initiated* vibrations too, which is
     *    why the task-start buzz only worked when no notification was on
     *    screen. `run_channel_v6` enables vibration on the channel (the
     *    notification itself is still silent and `setOnlyAlertOnce(true)`, so
     *    it never buzzes on its own) purely so the platform stops suppressing
     *    the app's own buzzes.
     *
     * The channel never actually makes a noise: the notification posted on it
     * is silent and `setOnlyAlertOnce(true)`, so the sound is only there to
     * keep the channel out of the platform's "silent" bucket.
     */
    const val RUN_CHANNEL_ID: String = "run_channel_v6"

    /**
     * Creates both channels if they do not already exist, using the default
     * alarm tone. Callers that have the user's settings should use the
     * [ensureCreated] overload below instead.
     */
    fun ensureCreated(context: Context) {
        ensureCreated(context, AlarmSettings())
    }

    /**
     * Creates the alarm channel, or updates its sound when the user has picked
     * a different tone.
     *
     * The platform only lets an app change a channel's sound by re-creating the
     * channel with the same id, and it ignores the change if the user has
     * customised the channel themselves. That is the documented behaviour and
     * is fine here: the full-screen [AlarmActivity] plays the tone directly, so
     * the channel sound is only the heads-up fallback.
     */
    fun ensureCreated(context: Context, settings: AlarmSettings) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        ensureAlarmChannel(manager, settings)
        ensureRunChannel(manager)
    }

    private fun ensureAlarmChannel(manager: NotificationManager, settings: AlarmSettings) {
        val soundUri = AlarmTone.resolveUri(settings)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val existing = manager.getNotificationChannel(ALARM_CHANNEL_ID)
        // Re-creating an existing channel is how the sound is changed; skip it
        // when nothing has changed so we do not fight the user's own edits.
        if (existing != null && existing.sound == soundUri) return

        val channel = NotificationChannel(
            ALARM_CHANNEL_ID,
            "Routine alarms",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Fires at task boundaries in your routines."
            enableVibration(settings.vibrate)
            setSound(soundUri, audioAttributes)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * The ongoing run channel. High importance, but it never actually makes a
     * noise: the per-task alarm channel is what alerts the user, this one is
     * purely the persistent "what am I doing now" readout.
     *
     * High importance is required, not cosmetic: **Android only fires a
     * notification's full-screen intent when its channel is `IMPORTANCE_HIGH`**.
     * The lock-screen takeover ([RunTakeoverActivity]) is delivered as a
     * full-screen intent on this notification, so a lower importance would
     * silently downgrade it to a heads-up notification and the takeover would
     * never appear.
     *
     * The channel deliberately keeps a sound configured. A channel created with
     * `setSound(null, null)` is classified by the platform as a *silent*
     * channel, and silent channels are dropped from the lock screen on several
     * OEM builds (Samsung included) no matter what `lockscreenVisibility` says.
     * The notification posted on this channel is muted per-notification
     * (`setSilent(true)` in [RunNotification]) and `setOnlyAlertOnce(true)`, so
     * the configured sound is never heard — it exists only to keep the channel
     * out of the silent bucket.
     *
     * Vibration is *enabled* on the channel even though the notification never
     * vibrates. While a notification from a vibration-disabled channel is
     * showing, several OEM builds suppress app-initiated vibrations as well,
     * which is why the task-start buzz only worked when no notification was on
     * screen. Enabling it here removes that suppression; the notification is
     * still silent and `setOnlyAlertOnce(true)`, so it never buzzes by itself.
     *
     * `setBypassDnd(true)` is intentionally *not* set: this channel must never
     * interrupt, only display.
     */
    private fun ensureRunChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(RUN_CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            RUN_CHANNEL_ID,
            "Routine in progress",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Shows the task currently running and the time left."
            setShowBadge(false)
            // Enabled so the platform does not suppress the app's own buzzes
            // while this notification is showing. The notification itself is
            // silent, so nothing vibrates on its own.
            enableVibration(true)
            vibrationPattern = longArrayOf(0L)
            // A real (but never-played) sound keeps this out of the platform's
            // silent-channel bucket, which is what stops it showing on the lock
            // screen. See the KDoc above.
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    /** A builder pre-configured for the alarm channel. */
    fun alarmBuilder(context: Context): NotificationCompat.Builder =
        NotificationCompat.Builder(context, ALARM_CHANNEL_ID)
            .setChannelId(ALARM_CHANNEL_ID)

    /** A builder pre-configured for the muted, ongoing run channel. */
    fun runBuilder(context: Context): NotificationCompat.Builder =
        NotificationCompat.Builder(context, RUN_CHANNEL_ID)
            .setChannelId(RUN_CHANNEL_ID)
}
