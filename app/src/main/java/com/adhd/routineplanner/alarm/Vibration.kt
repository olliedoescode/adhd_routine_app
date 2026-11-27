package com.adhd.routineplanner.alarm

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.adhd.routineplanner.data.VibrationPattern

/**
 * One place that knows how to buzz the phone, so every vibration in the app
 * honours the user's strength settings and behaves the same way.
 *
 * Two kinds of buzz exist:
 *
 *  - **Solid** ([solid]): one continuous vibration held for a duration. This is
 *    what an alarm uses — a steady, unbroken buzz is far harder to sleep
 *    through than a pattern of pulses.
 *  - **Pulse** ([pulse]): a short double-buzz. This is the "next thing" cue for
 *    a task boundary or a run start: noticeable, but not a wake-up call.
 *
 * Both take a `strength` in 0f..1f which is mapped onto the vibrator's amplitude
 * range. On devices without amplitude control (or below API 26) the strength is
 * ignored and the vibration runs at the hardware's default intensity.
 *
 * ## Why a buzz sometimes did not happen
 *
 * Two separate platform behaviours used to swallow the buzz:
 *
 *  1. **A previous vibration was still "current".** `Vibrator.vibrate` replaces
 *     the running effect, but on several OEM builds (Samsung included) a new
 *     effect is dropped while the previous one is still registered — notably
 *     when a notification's own vibration is in flight. Every call here now
 *     [Vibrator.cancel]s first, so the new effect always starts from a clean
 *     state.
 *  2. **The notification channel had vibration disabled.** The ongoing run
 *     notification is posted on a channel created with `enableVibration(false)`,
 *     and while a notification from such a channel is showing, the platform can
 *     suppress app-initiated vibrations. The run channel now enables vibration
 *     (the notification itself is still silent, so nothing buzzes on its own),
 *     and every buzz here is tagged with [VibrationAttributes.USAGE_ALARM] so
 *     it is treated as an alarm-class vibration rather than a notification one.
 *
 * The net effect: the buzz happens whether or not a notification is already on
 * screen.
 */
object Vibration {

    private const val TAG = "Vibration"

    /**
     * How long the task-boundary / run-start cue buzzes: 1.5 seconds. Long
     * enough to be felt, short enough not to be mistaken for an alarm.
     */
    private const val TASK_CUE_DURATION_MS: Long = 1_500L

    /**
     * How long the "Solid" pattern buzzes in a settings preview: 600 ms. Long
     * enough to feel the character of a solid buzz, short enough that tapping
     * through the chips does not become a drone.
     */
    private const val PREVIEW_SOLID_MS: Long = 600L

    /** The vibrator, or `null` when the device has none. */
    private fun vibrator(context: Context): Vibrator? {
        val service = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return null
        return if (service.hasVibrator()) service else null
    }

    /**
     * A continuous buzz for [durationMs], at [strength] (0f..1f).
     *
     * Used for alarms. The vibration does not repeat: it runs once for the full
     * duration, which is the "solid" feel an alarm should have.
     */
    fun solid(context: Context, durationMs: Long, strength: Float) {
        val service = vibrator(context) ?: return
        val amplitude = amplitudeFor(service, strength)
        try {
            // Cancel first: a still-registered effect (e.g. a notification's
            // own vibration) can make the platform drop this one.
            service.cancel()
            vibrate(service, VibrationEffect.createOneShot(durationMs, amplitude))
        } catch (t: Throwable) {
            Log.w(TAG, "Could not run a solid vibration", t)
        }
    }

    /**
     * Plays the alarm's buzz in the user's chosen [pattern] for [durationMs], at
     * [strength] (0f..1f).
     *
     * This is the alarm's vibration entry point. [VibrationPattern.Solid] is the
     * original single unbroken buzz; the other patterns are waveforms that
     * repeat for the whole duration so the buzz keeps going until the alarm
     * stops, exactly like the solid version. Every pattern is tagged as an
     * alarm-class vibration (see [vibrate]) so it is never suppressed while the
     * run notification is on screen.
     */
    fun alarm(context: Context, durationMs: Long, strength: Float, pattern: VibrationPattern) {
        if (pattern == VibrationPattern.Solid) {
            solid(context, durationMs, strength)
            return
        }
        val service = vibrator(context) ?: return
        val amplitude = amplitudeFor(service, strength)
        try {
            service.cancel()
            val (timings, amplitudes) = waveformFor(pattern, amplitude)
            // Repeat from index 0 until cancelled: the alarm stops the vibration
            // itself when its duration elapses, so an endless repeat is correct.
            vibrate(service, VibrationEffect.createWaveform(timings, amplitudes, 0))
        } catch (t: Throwable) {
            Log.w(TAG, "Could not run a patterned vibration", t)
        }
    }

    /**
     * Plays a short, one-off sample of [pattern] at [strength] (0f..1f) so the
     * user can feel what a rhythm is like before choosing it in settings.
     *
     * Unlike [alarm], this does **not** repeat: it plays exactly one cycle of
     * the pattern and stops, which is what a preview should do. [Solid] is a
     * single short buzz rather than the full alarm-length one.
     */
    fun preview(context: Context, strength: Float, pattern: VibrationPattern) {
        val service = vibrator(context) ?: return
        val amplitude = amplitudeFor(service, strength)
        try {
            service.cancel()
            if (pattern == VibrationPattern.Solid) {
                vibrate(service, VibrationEffect.createOneShot(PREVIEW_SOLID_MS, amplitude))
                return
            }
            val (timings, amplitudes) = waveformFor(pattern, amplitude)
            // -1 means "do not repeat": one cycle, then stop.
            vibrate(service, VibrationEffect.createWaveform(timings, amplitudes, -1))
        } catch (t: Throwable) {
            Log.w(TAG, "Could not preview a vibration pattern", t)
        }
    }

    /**
     * Builds the on/off timing and amplitude arrays for a non-solid [pattern].
     *
     * Timings are in milliseconds and alternate off/on starting with off (the
     * platform's convention); amplitudes are 0 for the off segments and the
     * computed [amplitude] for the on segments.
     */
    private fun waveformFor(
        pattern: VibrationPattern,
        amplitude: Int,
    ): Pair<LongArray, IntArray> = when (pattern) {
        // A steady ring: 400ms on, 400ms off.
        VibrationPattern.Pulse -> Pair(
            longArrayOf(0L, 400L, 400L),
            intArrayOf(0, amplitude, 0),
        )
        // A heartbeat: two quick thumps, then a longer pause.
        VibrationPattern.Heartbeat -> Pair(
            longArrayOf(0L, 120L, 90L, 120L, 600L),
            intArrayOf(0, amplitude, 0, amplitude, 0),
        )
        // An escalating ramp: short buzzes that grow longer, then reset.
        VibrationPattern.Escalating -> Pair(
            longArrayOf(0L, 120L, 120L, 200L, 120L, 320L, 120L, 480L, 500L),
            intArrayOf(0, amplitude, 0, amplitude, 0, amplitude, 0, amplitude, 0),
        )
        // Solid is handled before this is called; keep a sane fallback.
        VibrationPattern.Solid -> Pair(
            longArrayOf(0L, 1_000L),
            intArrayOf(0, amplitude),
        )
    }

    /**
     * The "next thing" cue for a task boundary or a run start: one solid
     * vibration held for [TASK_CUE_DURATION_MS] (1.5 s) at [strength] (0f..1f).
     *
     * This is deliberately a single continuous buzz rather than a pattern of
     * pulses: it is long enough to be felt through a pocket or a desk, but it is
     * not an alarm — the strength comes from the user's "Task vibration
     * strength" setting, which defaults to a moderate 0.6, so it stays a nudge
     * rather than a wake-up call.
     */
    fun taskCue(context: Context, strength: Float) {
        solid(context, TASK_CUE_DURATION_MS, strength)
    }

    /**
     * A short double-buzz at [strength] (0f..1f).
     *
     * Kept for callers that want a distinct two-pulse pattern; the task
     * boundary / run start now uses [taskCue] instead.
     */
    fun pulse(context: Context, strength: Float) {
        val service = vibrator(context) ?: return
        val amplitude = amplitudeFor(service, strength)
        try {
            service.cancel()
            val timings = longArrayOf(0L, 120L, 100L, 120L)
            val amplitudes = intArrayOf(0, amplitude, 0, amplitude)
            vibrate(service, VibrationEffect.createWaveform(timings, amplitudes, -1))
        } catch (t: Throwable) {
            Log.w(TAG, "Could not run a pulse vibration", t)
        }
    }

    /**
     * Plays [effect] tagged as an alarm-class vibration.
     *
     * The attributes matter: an untagged vibration is treated as a generic
     * "touch" vibration, which the platform is free to suppress while a
     * notification is showing. `USAGE_ALARM` is never suppressed that way, so
     * the buzz is felt even when the ongoing run notification is already on
     * screen. The attribute overload only exists from API 33; older versions
     * fall back to the plain call.
     */
    private fun vibrate(service: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.vibrate(
                effect,
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
            )
        } else {
            @Suppress("DEPRECATION")
            service.vibrate(effect)
        }
    }

    /**
     * Maps a 0f..1f strength onto the vibrator's amplitude range.
     *
     * [VibrationEffect.DEFAULT_AMPLITUDE] (which means "the hardware default")
     * is used when the device cannot control amplitude, so the buzz still
     * happens rather than being silently dropped.
     */
    private fun amplitudeFor(service: Vibrator, strength: Float): Int {
        if (!service.hasAmplitudeControl()) return VibrationEffect.DEFAULT_AMPLITUDE
        val clamped = strength.coerceIn(0f, 1f)
        // 1..255 is the valid amplitude range; never return 0 (off).
        return (clamped * 255f).toInt().coerceIn(1, 255)
    }
}
