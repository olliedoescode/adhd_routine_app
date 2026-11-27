package com.adhd.routineplanner.data

import android.content.Context
import android.media.AudioManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's alarm preferences: which tone rings, how loud, whether the phone
 * vibrates and how long the tone plays.
 *
 * These are app-wide rather than per-routine. A routine is a sequence of tasks
 * and the alarm is the same "hey, next thing" cue throughout, so one set of
 * choices is what a user actually wants to configure; per-task tones would be
 * a lot of UI for very little benefit.
 *
 * Stored in the same DataStore file as the run session (see
 * [com.adhd.routineplanner.di.DatabaseModule]) because both are small scalar
 * preferences and a second DataStore would mean a second file and a second
 * singleton for no gain.
 */
/**
 * The shape of the buzz the alarm uses when it fires.
 *
 * Strength (how hard) and pattern (what rhythm) are separate on purpose: some
 * people want a single unbroken buzz they cannot sleep through, others want a
 * pulsing rhythm that is harder to ignore than a flat tone, and some prefer the
 * classic "heartbeat" double-thump. All are played at the user's chosen
 * [AlarmSettings.alarmVibrationStrength].
 */
enum class VibrationPattern {
    /** One continuous, unbroken buzz — the default, hardest to sleep through. */
    Solid,

    /** A steady on/off pulse, like a ringing phone. */
    Pulse,

    /** A "heartbeat" double-thump followed by a pause, repeated. */
    Heartbeat,

    /** A rising ramp of short buzzes that gets more insistent. */
    Escalating,
    ;

    companion object {
        /** Maps a stored name back to a pattern, defaulting to [Solid]. */
        fun fromName(name: String?): VibrationPattern =
            entries.firstOrNull { it.name == name } ?: Solid
    }
}

data class AlarmSettings(
    /**
     * The tone to ring, as a content `Uri` string. `null` means "whatever the
     * system default alarm tone is", which is also what a fresh install uses.
     */
    val toneUri: String? = null,
    /** Display name of [toneUri], cached so the settings list can show it. */
    val toneName: String? = null,
    /**
     * Volume as a fraction of the alarm stream's maximum, 0f..1f. Applied on
     * top of the stream volume rather than replacing it, so the user's hardware
     * volume buttons still work as expected.
     */
    val volume: Float = DEFAULT_VOLUME,
    /** Whether to vibrate alongside the tone. */
    val vibrate: Boolean = true,
    /**
     * How hard the phone buzzes for a **task alarm** (a task that has its own
     * alarm enabled, which opens [com.adhd.routineplanner.alarm.AlarmActivity]).
     * 0f..1f, mapped onto the vibrator's amplitude range. 1f is the strongest
     * the hardware allows.
     */
    val alarmVibrationStrength: Float = DEFAULT_ALARM_VIBRATION_STRENGTH,
    /**
     * The rhythm of the alarm's buzz. [VibrationPattern.Solid] is the default
     * (one unbroken buzz); the others trade that for a pulse, a heartbeat or an
     * escalating ramp. Played at [alarmVibrationStrength].
     */
    val vibrationPattern: VibrationPattern = VibrationPattern.Solid,
    /**
     * How hard the phone buzzes for a **task boundary or run start** — the
     * short buzz when a task changes and the buzz when a run begins. Kept
     * separate from [alarmVibrationStrength] so the gentle "next thing" cue can
     * stay gentle while a real alarm is allowed to be brutal.
     */
    val taskVibrationStrength: Float = DEFAULT_TASK_VIBRATION_STRENGTH,
    /** How long the tone rings before stopping, in seconds. */
    val durationSeconds: Int = DEFAULT_DURATION_SECONDS,
    /**
     * Whether tapping a tone in the alarm-tone picker plays a short, quiet
     * preview of it, so the user can hear what they are choosing before
     * committing. On by default; off for anyone who finds it distracting.
     */
    val tonePreviewEnabled: Boolean = true,
    /**
     * Whether the routine takes over the lock screen while it runs
     * (plans/accountability-lockscreen.md). When on, the ongoing run
     * notification carries a full-screen intent, so turning the screen on
     * during a run shows the routine over the keyguard instead of the normal
     * lock screen. The user can always swipe it away.
     */
    val takeoverEnabled: Boolean = true,
) {
    companion object {
        /**
         * 80% of the alarm stream: loud enough to wake someone, quiet enough
         * that it is not the maximum on the first morning.
         */
        const val DEFAULT_VOLUME: Float = 0.8f

        /** Five seconds, matching the original fixed behaviour. */
        const val DEFAULT_DURATION_SECONDS: Int = 5

        /** Bounds for [durationSeconds]; the UI offers these as a slider. */
        const val MIN_DURATION_SECONDS: Int = 1
        const val MAX_DURATION_SECONDS: Int = 30

        /**
         * A task alarm defaults to full strength: an alarm is meant to wake you,
         * so the strongest buzz the hardware allows is the sensible default.
         */
        const val DEFAULT_ALARM_VIBRATION_STRENGTH: Float = 1f

        /**
         * A task boundary / run start defaults to a moderate buzz. It is a
         * "next thing" nudge, not a wake-up call, so it should not be as
         * aggressive as an alarm out of the box.
         */
        const val DEFAULT_TASK_VIBRATION_STRENGTH: Float = 0.6f

        /** Bounds for the vibration strengths; the UI offers these as sliders. */
        const val MIN_VIBRATION_STRENGTH: Float = 0.1f
        const val MAX_VIBRATION_STRENGTH: Float = 1f
    }
}

/**
 * Persists [AlarmSettings] in DataStore.
 *
 * Every read has a sensible default, so a fresh install or a corrupted
 * preference degrades to the original behaviour rather than failing.
 */
@Singleton
class AlarmSettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    /** Emits the current settings, filling in defaults for anything unset. */
    val settings: Flow<AlarmSettings> = dataStore.data.map { prefs ->
        AlarmSettings(
            toneUri = prefs[KEY_TONE_URI],
            toneName = prefs[KEY_TONE_NAME],
            volume = (prefs[KEY_VOLUME] ?: AlarmSettings.DEFAULT_VOLUME)
                .coerceIn(0f, 1f),
            vibrate = prefs[KEY_VIBRATE] ?: true,
            alarmVibrationStrength = (prefs[KEY_ALARM_VIBRATION] ?: AlarmSettings.DEFAULT_ALARM_VIBRATION_STRENGTH)
                .coerceIn(AlarmSettings.MIN_VIBRATION_STRENGTH, AlarmSettings.MAX_VIBRATION_STRENGTH),
            vibrationPattern = VibrationPattern.fromName(prefs[KEY_VIBRATION_PATTERN]),
            taskVibrationStrength = (prefs[KEY_TASK_VIBRATION] ?: AlarmSettings.DEFAULT_TASK_VIBRATION_STRENGTH)
                .coerceIn(AlarmSettings.MIN_VIBRATION_STRENGTH, AlarmSettings.MAX_VIBRATION_STRENGTH),
            durationSeconds = (prefs[KEY_DURATION] ?: AlarmSettings.DEFAULT_DURATION_SECONDS)
                .coerceIn(
                    AlarmSettings.MIN_DURATION_SECONDS,
                    AlarmSettings.MAX_DURATION_SECONDS,
                ),
            tonePreviewEnabled = prefs[KEY_TONE_PREVIEW] ?: true,
            takeoverEnabled = prefs[KEY_TAKEOVER] ?: true,
        )
    }

    /** Stores the chosen tone. `null` resets to the system default. */
    suspend fun setTone(uri: String?, name: String?) {
        dataStore.edit { prefs ->
            if (uri == null) {
                prefs.remove(KEY_TONE_URI)
                prefs.remove(KEY_TONE_NAME)
            } else {
                prefs[KEY_TONE_URI] = uri
                prefs[KEY_TONE_NAME] = name.orEmpty()
            }
        }
    }

    suspend fun setVolume(volume: Float) {
        dataStore.edit { prefs ->
            prefs[KEY_VOLUME] = volume.coerceIn(0f, 1f)
        }
    }

    suspend fun setVibrate(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_VIBRATE] = enabled }
    }

    /** Sets the rhythm of the alarm's buzz. */
    suspend fun setVibrationPattern(pattern: VibrationPattern) {
        dataStore.edit { prefs -> prefs[KEY_VIBRATION_PATTERN] = pattern.name }
    }

    /** Sets how hard a task alarm buzzes, 0f..1f. */
    suspend fun setAlarmVibrationStrength(strength: Float) {
        dataStore.edit { prefs ->
            prefs[KEY_ALARM_VIBRATION] = strength.coerceIn(
                AlarmSettings.MIN_VIBRATION_STRENGTH,
                AlarmSettings.MAX_VIBRATION_STRENGTH,
            )
        }
    }

    /** Sets how hard a task boundary / run start buzzes, 0f..1f. */
    suspend fun setTaskVibrationStrength(strength: Float) {
        dataStore.edit { prefs ->
            prefs[KEY_TASK_VIBRATION] = strength.coerceIn(
                AlarmSettings.MIN_VIBRATION_STRENGTH,
                AlarmSettings.MAX_VIBRATION_STRENGTH,
            )
        }
    }

    suspend fun setDurationSeconds(seconds: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_DURATION] = seconds.coerceIn(
                AlarmSettings.MIN_DURATION_SECONDS,
                AlarmSettings.MAX_DURATION_SECONDS,
            )
        }
    }

    /** Turns the lock-screen takeover on or off (plans/accountability-lockscreen.md). */
    suspend fun setTakeoverEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_TAKEOVER] = enabled }
    }

    /** Turns the quiet tone preview in the tone picker on or off. */
    suspend fun setTonePreviewEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_TONE_PREVIEW] = enabled }
    }

    private companion object {
        val KEY_TONE_URI = stringPreferencesKey("alarm_tone_uri")
        val KEY_TONE_NAME = stringPreferencesKey("alarm_tone_name")
        val KEY_VOLUME = floatPreferencesKey("alarm_volume")
        val KEY_VIBRATE = booleanPreferencesKey("alarm_vibrate")
        val KEY_VIBRATION_PATTERN = stringPreferencesKey("alarm_vibration_pattern")
        val KEY_ALARM_VIBRATION = floatPreferencesKey("alarm_vibration_strength")
        val KEY_TASK_VIBRATION = floatPreferencesKey("task_vibration_strength")
        val KEY_DURATION = intPreferencesKey("alarm_duration_seconds")
        val KEY_TONE_PREVIEW = booleanPreferencesKey("alarm_tone_preview_enabled")
        val KEY_TAKEOVER = booleanPreferencesKey("run_takeover_enabled")
    }
}

/**
 * The alarm stream's index range, so the UI can show "3 of 7" style steps and
 * the player can scale the user's fraction onto the real stream.
 *
 * Kept here rather than in the UI so the player and the slider agree on what
 * "80%" means.
 */
fun AudioManager.alarmVolumeRange(): IntRange {
    val max = getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
    return 0..max
}

/** Reads the current alarm stream volume as a 0f..1f fraction. */
fun AudioManager.alarmVolumeFraction(): Float {
    val max = getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
    return getStreamVolume(AudioManager.STREAM_ALARM).toFloat() / max
}

/** Sets the alarm stream volume from a 0f..1f fraction. */
fun AudioManager.setAlarmVolumeFraction(fraction: Float) {
    val max = getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
    val index = (fraction.coerceIn(0f, 1f) * max).toInt().coerceIn(0, max)
    setStreamVolume(AudioManager.STREAM_ALARM, index, 0)
}

/** Convenience for callers that only have a [Context]. */
fun Context.alarmAudioManager(): AudioManager =
    getSystemService(Context.AUDIO_SERVICE) as AudioManager
