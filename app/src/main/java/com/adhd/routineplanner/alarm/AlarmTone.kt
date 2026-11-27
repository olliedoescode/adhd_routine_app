package com.adhd.routineplanner.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import com.adhd.routineplanner.data.AlarmSettings
import com.adhd.routineplanner.data.alarmAudioManager
import com.adhd.routineplanner.data.setAlarmVolumeFraction

/**
 * One selectable alarm tone: a stable [uri] plus the [title] to show in the
 * settings list.
 */
data class AlarmToneOption(
    val uri: String,
    val title: String,
)

/**
 * Lists, resolves and previews alarm tones.
 *
 * The list comes from [RingtoneManager]'s alarm cursor, which is what the
 * system Clock app shows too, so the user recognises the names. The first
 * entry is always "System default", represented by a `null` uri, so a user who
 * never picks anything keeps the platform's choice.
 */
object AlarmTone {

    private const val TAG = "AlarmTone"

    /** The label shown for the "no explicit choice" entry. */
    const val DEFAULT_TITLE: String = "System default"

    /**
     * Every alarm tone the device offers, with "System default" first.
     *
     * Runs a cursor, so call it off the main thread. A device with no alarm
     * tones at all yields just the default entry rather than an empty list, so
     * the settings screen always has something to show.
     */
    fun available(context: Context): List<AlarmToneOption> {
        val options = mutableListOf(AlarmToneOption(uri = "", title = DEFAULT_TITLE))
        val manager = RingtoneManager(context).apply {
            setType(RingtoneManager.TYPE_ALARM)
        }
        val cursor = try {
            manager.cursor
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read the alarm tone list", t)
            null
        }
        cursor?.use {
            while (it.moveToNext()) {
                val uri = manager.getRingtoneUri(it.position) ?: continue
                val title = it.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                    ?.takeIf { name -> name.isNotBlank() }
                    ?: continue
                options += AlarmToneOption(uri = uri.toString(), title = title)
            }
        }
        return options
    }

    /**
     * The uri to actually play for [settings]: the user's choice, or the
     * system default alarm tone, or the default notification tone as a last
     * resort. `null` when the device has neither.
     */
    fun resolveUri(settings: AlarmSettings): Uri? {
        settings.toneUri
            ?.takeIf { it.isNotBlank() }
            ?.let { return Uri.parse(it) }
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    /**
     * Plays [settings]' tone at the configured volume, **looping until stopped**.
     * Returns the player so the caller can stop it (the alarm screen does this
     * on Snooze, on Dismiss and on destroy).
     *
     * Looping is what makes this feel like a real alarm rather than a chime: a
     * five-second tone that then falls silent is easy to sleep through, whereas
     * one that keeps ringing forces the user to pick Snooze or Dismiss. The
     * caller is responsible for stopping it — [com.adhd.routineplanner.alarm.AlarmActivity]
     * does so from its buttons and from `onDestroy`, so the tone can never
     * outlive the screen.
     *
     * Volume is applied to the alarm stream itself rather than to the player,
     * because `MediaPlayer.setVolume` is relative to the stream and would be
     * inaudible at stream volume 0. Setting the stream is also what the user
     * expects from a volume slider next to an alarm tone.
     */
    fun play(context: Context, settings: AlarmSettings): MediaPlayer? {
        val uri = resolveUri(settings) ?: return null

        // Apply the chosen volume to the alarm stream before playing.
        context.alarmAudioManager().setAlarmVolumeFraction(settings.volume)

        val player = MediaPlayer()
        return try {
            player.setDataSource(context, uri)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            // Loop until the alarm screen stops us: a real alarm keeps ringing.
            player.isLooping = true
            player.prepare()
            player.start()
            player
        } catch (t: Throwable) {
            // A missing or unreadable tone must never crash the alarm screen.
            Log.w(TAG, "Could not play alarm tone $uri", t)
            runCatching { player.release() }
            null
        }
    }

    /**
     * Plays [uri] once as a short, quiet preview so the user can hear what a
     * tone sounds like before choosing it.
     *
     * This is deliberately *not* [play]: that one applies the user's alarm
     * volume to the alarm stream, which is far too loud for a tap in a settings
     * list. The preview instead leaves the stream alone and scales the player
     * itself down with [MediaPlayer.setVolume], so it is audible but gentle.
     *
     * A blank [uri] means "System default", which is resolved to the platform's
     * default alarm tone so the preview still plays something.
     *
     * @return the player, so the caller can stop it when the next tone is
     *   tapped or the dialog closes.
     */
    fun preview(context: Context, uri: String?): MediaPlayer? {
        val target = uri
            ?.takeIf { it.isNotBlank() }
            ?.let { Uri.parse(it) }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return null

        // A preview must be heard even when the alarm stream is turned down or
        // muted (a user auditioning tones in a quiet room often has it low).
        // Play it on the media stream instead, which is the stream a settings
        // preview is expected to use, and leave the alarm stream untouched.
        val player = MediaPlayer()
        return try {
            player.setDataSource(context, target)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            player.isLooping = false
            // Quiet on purpose: a preview is a "what does this sound like",
            // not an alarm. 0.6 is clearly audible without being startling.
            player.setVolume(PREVIEW_VOLUME, PREVIEW_VOLUME)
            player.prepare()
            player.start()
            player
        } catch (t: Throwable) {
            Log.w(TAG, "Could not preview alarm tone $target", t)
            runCatching { player.release() }
            null
        }
    }

    /** Stops and releases [player], tolerating an already-stopped player. */
    fun stop(player: MediaPlayer?) {
        player ?: return
        try {
            if (player.isPlaying) player.stop()
        } catch (_: IllegalStateException) {
            // Already stopped or never started.
        }
        runCatching { player.release() }
    }

    /** Player volume for [preview]: audible, but well below an alarm. */
    private const val PREVIEW_VOLUME: Float = 0.6f

    /**
     * How long a preview plays before it is stopped, in milliseconds.
     *
     * A preview is a "what does this sound like", not a listen-through, so it
     * is capped at three seconds. The caller schedules [stop] after this delay.
     */
    const val PREVIEW_MAX_MS: Long = 3_000L
}
