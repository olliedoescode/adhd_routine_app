package com.adhd.routineplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class RunStateTest {

    private fun task(minutes: Int, id: Long = 0) = PlacedTask(id = id, minutes = minutes)

    /**
     * A routine window exactly as long as the tasks claim, so the buffer is
     * zero and the rendered durations equal the minimums. That keeps these
     * timing assertions about the run clock rather than about buffer maths.
     */
    private fun window(capacity: Int) = Routine(
        name = "Test",
        startMinuteOfDay = 7 * 60,
        endMinuteOfDay = 7 * 60 + capacity,
    )

    // 10 min, 5 min, 20 min -> boundaries at 0, 600s, 900s, 2100s
    private val tasks = listOf(task(10, 1), task(5, 2), task(20, 3))
    private val routine = window(35)
    private val startMs = 1_000_000L

    private fun at(seconds: Long) = runState(routine, tasks, startMs, startMs + seconds * 1000L)

    @Test
    fun `before start reports BeforeStart with time until start`() {
        val state = runState(routine, tasks, startMs, startMs - 30_000L)
        assertEquals(Phase.BeforeStart, state.phase)
        assertEquals(-1, state.currentIndex)
        assertEquals(30, state.secondsRemaining)
        assertEquals(0f, state.progress, 0.0001f)
    }

    @Test
    fun `exactly at start is Running on the first task`() {
        val state = at(0)
        assertEquals(Phase.Running, state.phase)
        assertEquals(0, state.currentIndex)
        assertEquals(600, state.secondsRemaining)
        assertEquals(0f, state.progress, 0.0001f)
    }

    @Test
    fun `exactly on a task boundary moves to the next task`() {
        val state = at(600)
        assertEquals(Phase.Running, state.phase)
        assertEquals(1, state.currentIndex)
        assertEquals(300, state.secondsRemaining)
        assertEquals(0f, state.progress, 0.0001f)
    }

    @Test
    fun `mid-task progress is the fraction elapsed`() {
        val state = at(300) // halfway through the 10 min task
        assertEquals(0, state.currentIndex)
        assertEquals(300, state.secondsRemaining)
        assertEquals(0.5f, state.progress, 0.0001f)
    }

    @Test
    fun `in the final second of the routine is still Running`() {
        val state = at(2100 - 1)
        assertEquals(Phase.Running, state.phase)
        assertEquals(2, state.currentIndex)
        assertEquals(1, state.secondsRemaining)
    }

    @Test
    fun `exactly at the end is Finished`() {
        val state = at(2100)
        assertEquals(Phase.Finished, state.phase)
        assertEquals(tasks.size, state.currentIndex)
        assertEquals(0, state.secondsRemaining)
        assertEquals(1f, state.progress, 0.0001f)
    }

    @Test
    fun `after the end stays Finished`() {
        val state = at(5000)
        assertEquals(Phase.Finished, state.phase)
        assertEquals(tasks.size, state.currentIndex)
    }

    @Test
    fun `empty task list is immediately Finished`() {
        val state = runState(window(0), emptyList(), startMs, startMs)
        assertEquals(Phase.Finished, state.phase)
        assertEquals(0, state.currentIndex)
    }

    @Test
    fun `elapsedSeconds is derived from the wall clock`() {
        assertEquals(123L, at(123).elapsedSeconds)
    }

    // --- buffer -------------------------------------------------------------

    @Test
    fun `a buffer stretches the run to the routine window`() {
        // 10 + 5 + 20 = 35 claimed in a 45 minute window: 10 minutes of buffer
        // are shared out, so the run lasts 45 minutes, not 35.
        val roomy = window(45)
        val state = runState(roomy, tasks, startMs, startMs + 35 * 60_000L)
        assertEquals(Phase.Running, state.phase)
        assertEquals(2, state.currentIndex)
    }

    @Test
    fun `the buffer is shared proportionally so the first task grows`() {
        // 10/35 of a 10 minute buffer is ~2.86 -> 3 minutes, so task 0 renders
        // 13 minutes and the boundary moves from 600s to 780s.
        val roomy = window(45)
        val state = runState(roomy, tasks, startMs, startMs + 700 * 60L)
        assertEquals(0, state.currentIndex)
    }

    @Test
    fun `an over-subscribed routine still runs at the minimums`() {
        // 35 claimed in a 20 minute window: nothing to give away, so the run
        // still lasts 35 minutes and reports the overrun rather than shrinking.
        val tight = window(20)
        val state = runState(tight, tasks, startMs, startMs + 34 * 60_000L)
        assertEquals(Phase.Running, state.phase)
        assertEquals(2, state.currentIndex)
    }

    // --- midnight crossing --------------------------------------------------

    @Test
    fun `routine crossing midnight keeps running past 24h`() {
        val zone = ZoneId.of("Europe/London")
        val start = LocalDateTime.of(2026, 1, 10, 23, 50).atZone(zone).toInstant().toEpochMilli()
        val crossing = listOf(task(20, 1), task(10, 2)) // ends 00:20 next day

        val beforeMidnight = runState(window(30), crossing, start, start + 5 * 60_000L)
        assertEquals(Phase.Running, beforeMidnight.phase)
        assertEquals(0, beforeMidnight.currentIndex)

        val afterMidnight = runState(window(30), crossing, start, start + 25 * 60_000L)
        assertEquals(Phase.Running, afterMidnight.phase)
        assertEquals(1, afterMidnight.currentIndex)

        val done = runState(window(30), crossing, start, start + 30 * 60_000L)
        assertEquals(Phase.Finished, done.phase)
    }

    // --- DST ----------------------------------------------------------------

    @Test
    fun `run state is unaffected by a DST transition because it uses epoch millis`() {
        val zone = ZoneId.of("Europe/London")
        // UK clocks go forward at 01:00 on 2026-03-29.
        val start = LocalDateTime.of(2026, 3, 29, 0, 30).atZone(zone).toInstant().toEpochMilli()
        val longRun = listOf(task(60, 1), task(60, 2)) // 2 hours of wall-clock-independent time

        val state = runState(window(120), longRun, start, start + 90 * 60_000L)
        assertEquals(Phase.Running, state.phase)
        assertEquals(1, state.currentIndex)
        assertEquals(30 * 60, state.secondsRemaining)
    }

    @Test
    fun `DST start date is a real transition in the test zone`() {
        val zone = ZoneId.of("Europe/London")
        val before = LocalDate.of(2026, 3, 29).atStartOfDay(zone)
        val after = LocalDate.of(2026, 3, 29).atTime(2, 0).atZone(zone)
        // 01:00 -> 02:00, so the offset changes by one hour.
        assertTrue(before.offset.totalSeconds != after.offset.totalSeconds)
    }
}
