package com.adhd.routineplanner.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure date math for alarm scheduling (spec section 9).
 *
 * Kept in `domain/` with no Android imports so it is unit-testable. The
 * `AlarmScheduler` calls these to work out the next wall-clock instant for a
 * routine's start (and each task start), then hands the epoch millis to
 * `AlarmManager`.
 *
 * A routine may cross midnight (spec section 14.1), so a task's absolute start
 * is the routine start plus the task's offset, which may roll into the next
 * day. All arithmetic is done in a [ZoneId] so DST transitions are handled by
 * `java.time` rather than by adding raw milliseconds.
 */
object AlarmSchedule {

    /**
     * The next instant at or after [nowMs] at which [routine] starts. Returns
     * `null` when the routine is disabled, has no active days, or is a one-off
     * whose date has already passed.
     *
     * A **one-off** routine (one with a [Routine.scheduledDate]) has exactly one
     * possible start: that date at the routine's start time. If that instant is
     * in the past there is no next start, which is what lets a one-off disable
     * itself once it has run.
     *
     * A **recurring** routine looks at today and the next seven days and takes
     * the first active day whose start is not in the past.
     *
     * "At or after" means a start exactly equal to [nowMs] is returned, so a
     * reschedule triggered by an alarm firing does not skip the current
     * occurrence; callers that want strictly-future should pass `nowMs + 1`.
     */
    fun nextRoutineStart(
        routine: Routine,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        // An untimed routine has no start time at all — you run it when you
        // feel like it — so there is never a "next start" to schedule or to
        // count down to.
        if (routine.isUntimed) return null
        if (!routine.enabled) return null

        val startTime = LocalTime.of(
            (routine.startMinuteOfDay / 60) % 24,
            routine.startMinuteOfDay % 60,
        )

        // One-off: a single date, so either it is still ahead of us or it is done.
        val oneOffDate = RoutineDates.dateOf(routine)
        if (oneOffDate != null) {
            val candidate = ZonedDateTime.of(oneOffDate, startTime, zone)
            return if (candidate.toInstant().isBefore(Instant.ofEpochMilli(nowMs))) {
                null
            } else {
                candidate.toInstant().toEpochMilli()
            }
        }

        if (routine.daysOfWeek == DaysOfWeek.NONE) return null

        val now = Instant.ofEpochMilli(nowMs).atZone(zone)

        // Look at today and the next seven days; the first active day whose
        // start is not in the past wins.
        for (dayOffset in 0..7) {
            val date = now.toLocalDate().plusDays(dayOffset.toLong())
            if (!isActive(routine.daysOfWeek, date.dayOfWeek)) continue
            val candidate = ZonedDateTime.of(date, startTime, zone)
            if (!candidate.toInstant().isBefore(Instant.ofEpochMilli(nowMs))) {
                return candidate.toInstant().toEpochMilli()
            }
        }
        return null
    }

    /**
     * The next instant at or after [nowMs] at which task [taskIndex] of
     * [routine] starts, honouring active days. The task start is the routine
     * start plus the sum of the *rendered* durations of earlier tasks (buffer
     * included), which may fall on the following day for a routine that
     * crosses midnight.
     *
     * Returns `null` when the routine is disabled, has no active days, or the
     * index is out of range.
     */
    fun nextTaskStart(
        routine: Routine,
        tasks: List<PlacedTask>,
        taskIndex: Int,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        if (taskIndex !in tasks.indices) return null
        val routineStart = nextRoutineStart(routine, nowMs, zone) ?: return null
        val offsetMinutes = Schedule.startMinutes(routine, tasks)[taskIndex]
        // Add the offset in wall-clock terms so a DST change inside the routine
        // is respected rather than adding raw milliseconds.
        val startZoned = Instant.ofEpochMilli(routineStart).atZone(zone)
        return startZoned.plusMinutes(offsetMinutes.toLong()).toInstant().toEpochMilli()
    }

    /**
     * The next instant at or after [nowMs] for a one-off test alarm [minutes]
     * from now (spec section 15.10). Pure so the checklist's Test button and
     * its unit test share one definition.
     */
    fun testAlarmAt(nowMs: Long, minutes: Int): Long =
        nowMs + minutes.coerceAtLeast(1) * 60_000L

    /** True when [mask] includes the ISO [dayOfWeek]. */
    private fun isActive(mask: Int, dayOfWeek: DayOfWeek): Boolean =
        DaysOfWeek.isActiveOn(mask, dayOfWeek.value)

    /**
     * Formats an epoch-millis instant as a `H:MM` clock label in [zone]. Used by
     * the alarm screen and notification text.
     */
    fun clockLabel(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val time = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()
        return "%d:%02d".format(time.hour, time.minute)
    }

    /** Exposed for tests: the local date-time of [epochMs] in [zone]. */
    fun localDateTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDateTime()

    /** Exposed for tests: the local date of [epochMs] in [zone]. */
    fun localDate(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
}
