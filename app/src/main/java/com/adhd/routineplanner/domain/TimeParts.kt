package com.adhd.routineplanner.domain

/**
 * A duration split into hours, minutes and seconds, for the task editor's
 * scrollable time picker (Samsung clock-app style).
 *
 * The rest of the app stores durations as whole minutes, so this type is only
 * used at the editing boundary: it converts to and from a total minute count
 * and clamps each field to a sane range. It lives in `domain/` so the picker
 * maths stays plain Kotlin and unit-testable (spec section 5).
 */
data class TimeParts(
    val hours: Int = 0,
    val minutes: Int = 0,
    val seconds: Int = 0,
) {

    /** Total duration in whole minutes, rounded up so a stray second is kept. */
    val totalMinutes: Int
        get() {
            val totalSeconds = hours * SECONDS_PER_HOUR + minutes * SECONDS_PER_MINUTE + seconds
            // Round up: 1m 1s must not silently become 1m and lose the second.
            return (totalSeconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE
        }

    /** Total duration in seconds, for exact arithmetic. */
    val totalSeconds: Int
        get() = hours * SECONDS_PER_HOUR + minutes * SECONDS_PER_MINUTE + seconds

    /** A copy with every field clamped to its valid range. */
    fun clamped(): TimeParts = TimeParts(
        hours = hours.coerceIn(0, MAX_HOURS),
        minutes = minutes.coerceIn(0, 59),
        seconds = seconds.coerceIn(0, 59),
    )

    companion object {
        const val SECONDS_PER_MINUTE: Int = 60
        const val SECONDS_PER_HOUR: Int = 3600

        /** Upper bound for the hours wheel; 24h is plenty for a routine task. */
        const val MAX_HOURS: Int = 24

        /** Hours wheel values, `0..MAX_HOURS`. */
        val HOUR_VALUES: List<Int> = (0..MAX_HOURS).toList()

        /** Minutes and seconds wheels, `0..59`. */
        val SIXTY_VALUES: List<Int> = (0..59).toList()

        /**
         * Split [totalMinutes] into hours/minutes/seconds. Seconds are always
         * zero here because stored durations are whole minutes; the picker can
         * still add seconds, which are folded back in via [totalMinutes].
         */
        fun fromMinutes(totalMinutes: Int): TimeParts {
            val safe = totalMinutes.coerceAtLeast(0)
            return TimeParts(
                hours = safe / 60,
                minutes = safe % 60,
                seconds = 0,
            ).clamped()
        }

        /** Split an exact [totalSeconds] count into hours/minutes/seconds. */
        fun fromSeconds(totalSeconds: Int): TimeParts {
            val safe = totalSeconds.coerceAtLeast(0)
            return TimeParts(
                hours = safe / SECONDS_PER_HOUR,
                minutes = (safe % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE,
                seconds = safe % SECONDS_PER_MINUTE,
            ).clamped()
        }
    }
}
