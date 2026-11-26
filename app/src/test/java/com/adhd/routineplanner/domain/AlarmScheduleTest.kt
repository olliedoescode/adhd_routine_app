package com.adhd.routineplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Tests for [AlarmSchedule] (spec section 9, 19).
 *
 * Covers the next-occurrence maths, active-day filtering, routines crossing
 * midnight, and a DST transition. All times are computed in an explicit
 * [ZoneId] so the tests are deterministic regardless of the machine's zone.
 */
class AlarmScheduleTest {

    private val london: ZoneId = ZoneId.of("Europe/London")

    private fun ms(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        zone: ZoneId = london,
    ): Long = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
        .toInstant()
        .toEpochMilli()

    private fun routine(
        startMinuteOfDay: Int,
        days: Int = DaysOfWeek.ALL,
        enabled: Boolean = true,
        lengthMinutes: Int = 60,
    ) = Routine(
        id = 1,
        name = "Morning",
        startMinuteOfDay = startMinuteOfDay,
        endMinuteOfDay = startMinuteOfDay + lengthMinutes,
        daysOfWeek = days,
        enabled = enabled,
    )

    @Test
    fun `next start today when the time is still ahead`() {
        // 2026-03-02 is a Monday. Now 06:00, routine starts 07:00.
        val now = ms(2026, 3, 2, 6, 0)
        val next = AlarmSchedule.nextRoutineStart(routine(7 * 60), now, london)
        assertEquals(ms(2026, 3, 2, 7, 0), next)
    }

    @Test
    fun `next start tomorrow when today's time has passed`() {
        val now = ms(2026, 3, 2, 8, 0)
        val next = AlarmSchedule.nextRoutineStart(routine(7 * 60), now, london)
        assertEquals(ms(2026, 3, 3, 7, 0), next)
    }

    @Test
    fun `start exactly now is returned`() {
        val now = ms(2026, 3, 2, 7, 0)
        val next = AlarmSchedule.nextRoutineStart(routine(7 * 60), now, london)
        assertEquals(now, next)
    }

    @Test
    fun `disabled routine has no next start`() {
        val now = ms(2026, 3, 2, 6, 0)
        assertNull(AlarmSchedule.nextRoutineStart(routine(7 * 60, enabled = false), now, london))
    }

    @Test
    fun `routine with no active days has no next start`() {
        val now = ms(2026, 3, 2, 6, 0)
        assertNull(
            AlarmSchedule.nextRoutineStart(routine(7 * 60, days = DaysOfWeek.NONE), now, london),
        )
    }

    @Test
    fun `only active days are considered`() {
        // 2026-03-02 is a Monday. Active only on Wednesday (2026-03-04).
        val now = ms(2026, 3, 2, 6, 0)
        val next = AlarmSchedule.nextRoutineStart(
            routine(7 * 60, days = DaysOfWeek.WEDNESDAY),
            now,
            london,
        )
        assertEquals(ms(2026, 3, 4, 7, 0), next)
    }

    @Test
    fun `task start is routine start plus earlier rendered durations`() {
        // Minimums 10 + 15 + 5 = 30 in a 30-minute window: no buffer, so the
        // rendered offsets equal the minimums.
        val tasks = listOf(
            PlacedTask(id = 1, minutes = 10),
            PlacedTask(id = 2, minutes = 15),
            PlacedTask(id = 3, minutes = 5),
        )
        val now = ms(2026, 3, 2, 6, 0)
        val next = AlarmSchedule.nextTaskStart(routine(7 * 60, lengthMinutes = 30), tasks, 2, now, london)
        // 07:00 + 10 + 15 = 07:25.
        assertEquals(ms(2026, 3, 2, 7, 25), next)
    }

    @Test
    fun `task start includes the buffer share of earlier tasks`() {
        // Minimums 10 + 10 = 20 in a 30-minute window: buffer 10 split 5/5, so
        // the second task starts 15 minutes in rather than 10.
        val tasks = listOf(
            PlacedTask(id = 1, minutes = 10),
            PlacedTask(id = 2, minutes = 10),
        )
        val now = ms(2026, 3, 2, 6, 0)
        val next = AlarmSchedule.nextTaskStart(routine(7 * 60, lengthMinutes = 30), tasks, 1, now, london)
        assertEquals(ms(2026, 3, 2, 7, 15), next)
    }

    @Test
    fun `routine crossing midnight rolls the task into the next day`() {
        // Start 23:50, first task 20 min -> second task starts 00:10 next day.
        val tasks = listOf(
            PlacedTask(id = 1, minutes = 20),
            PlacedTask(id = 2, minutes = 10),
        )
        val now = ms(2026, 3, 2, 23, 0)
        val next = AlarmSchedule.nextTaskStart(
            routine(23 * 60 + 50, lengthMinutes = 30),
            tasks,
            1,
            now,
            london,
        )
        assertEquals(ms(2026, 3, 3, 0, 10), next)
    }

    @Test
    fun `task index out of range returns null`() {
        val tasks = listOf(PlacedTask(id = 1, minutes = 10))
        val now = ms(2026, 3, 2, 6, 0)
        assertNull(AlarmSchedule.nextTaskStart(routine(7 * 60), tasks, 5, now, london))
    }

    @Test
    fun `DST spring forward keeps the wall-clock start time`() {
        // UK clocks go forward at 01:00 on 2026-03-29. A 07:00 routine should
        // still fire at 07:00 local, which is 06:00 UTC that day.
        val now = ms(2026, 3, 28, 12, 0)
        val next = AlarmSchedule.nextRoutineStart(routine(7 * 60), now, london)!!
        val local = AlarmSchedule.localDateTime(next, london)
        assertEquals(LocalDateTime.of(2026, 3, 29, 7, 0), local)
    }

    @Test
    fun `test alarm is the requested minutes ahead`() {
        val now = 1_000_000L
        assertEquals(now + 60_000L, AlarmSchedule.testAlarmAt(now, 1))
        assertEquals(now + 120_000L, AlarmSchedule.testAlarmAt(now, 2))
    }

    @Test
    fun `test alarm clamps to at least one minute`() {
        val now = 1_000_000L
        assertEquals(now + 60_000L, AlarmSchedule.testAlarmAt(now, 0))
    }

    @Test
    fun `clock label formats hours and minutes`() {
        val at = ms(2026, 3, 2, 7, 5)
        assertEquals("7:05", AlarmSchedule.clockLabel(at, london))
    }

    @Test
    fun `next start is never before now`() {
        val now = ms(2026, 3, 2, 6, 30)
        val next = AlarmSchedule.nextRoutineStart(routine(7 * 60), now, london)!!
        assertTrue(next >= now)
    }
}
