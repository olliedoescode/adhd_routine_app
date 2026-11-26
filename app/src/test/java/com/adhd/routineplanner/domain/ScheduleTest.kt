package com.adhd.routineplanner.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleTest {

    private fun task(minutes: Int, id: Long = 0) = PlacedTask(id = id, minutes = minutes)

    /** A routine window of exactly [capacity] minutes starting at 07:00. */
    private fun window(capacity: Int) = Routine(
        startMinuteOfDay = 7 * 60,
        endMinuteOfDay = 7 * 60 + capacity,
    )

    // --- startMinutes -------------------------------------------------------

    @Test
    fun `startMinutes for no tasks is a single zero`() {
        assertArrayEquals(intArrayOf(0), Schedule.startMinutes(window(60), emptyList()))
    }

    @Test
    fun `startMinutes for one task is zero then the capacity`() {
        // A lone task absorbs the whole window.
        assertArrayEquals(intArrayOf(0, 60), Schedule.startMinutes(window(60), listOf(task(10))))
    }

    @Test
    fun `startMinutes stretches tasks to fill the window`() {
        // Minimums 10 + 5 + 20 + 1 = 36, window 36 -> no buffer, plain prefix sums.
        val tasks = listOf(task(10), task(5), task(20), task(1))
        assertArrayEquals(intArrayOf(0, 10, 15, 35, 36), Schedule.startMinutes(window(36), tasks))
    }

    @Test
    fun `startMinutes distributes buffer proportionally`() {
        // Minimums 10 + 10 = 20, window 30 -> buffer 10, split 5/5.
        val tasks = listOf(task(10), task(10))
        assertArrayEquals(intArrayOf(0, 15, 30), Schedule.startMinutes(window(30), tasks))
    }

    @Test
    fun `startMinutes gives a longer task more of the buffer`() {
        // Minimums 10 + 30 = 40, window 60 -> buffer 20, split 5/15.
        val tasks = listOf(task(10), task(30))
        assertArrayEquals(intArrayOf(0, 15, 60), Schedule.startMinutes(window(60), tasks))
    }

    @Test
    fun `startMinutes renders at minimum when over-subscribed`() {
        // Minimums 20 + 20 = 40 in a 30-minute window: no shrinking.
        val tasks = listOf(task(20), task(20))
        assertArrayEquals(intArrayOf(0, 20, 40), Schedule.startMinutes(window(30), tasks))
    }

    // --- minimumStartMinutes ------------------------------------------------

    @Test
    fun `minimumStartMinutes ignores the buffer`() {
        val tasks = listOf(task(10), task(5), task(20), task(1))
        assertArrayEquals(
            intArrayOf(0, 10, 15, 35, 36),
            Schedule.minimumStartMinutes(tasks),
        )
    }

    // --- totalMinutes -------------------------------------------------------

    @Test
    fun `totalMinutes of empty list is zero`() {
        assertEquals(0, Schedule.totalMinutes(emptyList()))
    }

    @Test
    fun `totalMinutes sums minimum durations`() {
        assertEquals(36, Schedule.totalMinutes(listOf(task(10), task(5), task(20), task(1))))
    }

    // --- renderedMinutes ----------------------------------------------------

    @Test
    fun `renderedMinutes equals the capacity when there is buffer`() {
        assertEquals(60, Schedule.renderedMinutes(window(60), listOf(task(10), task(20))))
    }

    @Test
    fun `renderedMinutes exceeds the capacity when over-subscribed`() {
        assertEquals(40, Schedule.renderedMinutes(window(30), listOf(task(20), task(20))))
    }

    // --- taskAtMinute -------------------------------------------------------

    @Test
    fun `taskAtMinute returns null for no tasks`() {
        assertNull(Schedule.taskAtMinute(IntArray(1), 0, 0))
    }

    @Test
    fun `taskAtMinute maps boundaries to the correct task`() {
        // Minimums 10 + 5 + 20 = 35 in a 35-minute window: no buffer.
        val tasks = listOf(task(10), task(5), task(20))
        val starts = Schedule.startMinutes(window(35), tasks)
        assertEquals(0, Schedule.taskAtMinute(starts, tasks.size, 0))
        assertEquals(0, Schedule.taskAtMinute(starts, tasks.size, 9))
        assertEquals(1, Schedule.taskAtMinute(starts, tasks.size, 10))
        assertEquals(1, Schedule.taskAtMinute(starts, tasks.size, 14))
        assertEquals(2, Schedule.taskAtMinute(starts, tasks.size, 15))
        assertEquals(2, Schedule.taskAtMinute(starts, tasks.size, 34))
    }

    @Test
    fun `taskAtMinute returns null at and past the end`() {
        val tasks = listOf(task(10), task(5))
        val starts = Schedule.startMinutes(window(15), tasks)
        assertNull(Schedule.taskAtMinute(starts, tasks.size, 15))
        assertNull(Schedule.taskAtMinute(starts, tasks.size, 100))
    }

    @Test
    fun `taskAtMinute returns null for negative minutes`() {
        val tasks = listOf(task(10))
        val starts = Schedule.startMinutes(window(10), tasks)
        assertNull(Schedule.taskAtMinute(starts, tasks.size, -1))
    }

    @Test
    fun `taskAtMinute covers every minute of the window exactly once`() {
        val tasks = listOf(task(3), task(7), task(2))
        val starts = Schedule.startMinutes(window(20), tasks)
        // Buffer 8 spread proportionally, so the rendered total is the capacity.
        assertEquals(20, starts.last())
        for (m in 0 until 20) {
            val index = Schedule.taskAtMinute(starts, tasks.size, m)
            assertEquals("minute $m should belong to a task", true, index != null)
        }
    }

    // --- colorOfMinute ------------------------------------------------------

    @Test
    fun `colorOfMinute maps every minute to its task index`() {
        val tasks = listOf(task(2), task(3), task(1))
        assertArrayEquals(intArrayOf(0, 0, 1, 1, 1, 2), Schedule.colorOfMinute(tasks, 6))
    }

    @Test
    fun `colorOfMinute of empty list is empty`() {
        assertArrayEquals(IntArray(0), Schedule.colorOfMinute(emptyList(), 0))
    }

    // --- endMinuteOfDay -----------------------------------------------------

    @Test
    fun `endMinuteOfDay is the declared routine end`() {
        val routine = Routine(startMinuteOfDay = 7 * 60, endMinuteOfDay = 7 * 60 + 30)
        assertEquals(7 * 60 + 30, Schedule.endMinuteOfDay(routine))
    }

    @Test
    fun `endMinuteOfDay wraps across midnight`() {
        // 23:50 -> 00:10 next day -> 10
        val routine = Routine(startMinuteOfDay = 23 * 60 + 50, endMinuteOfDay = 24 * 60 + 10)
        assertEquals(10, Schedule.endMinuteOfDay(routine))
    }

    @Test
    fun `endMinuteOfDay of a full day wraps to zero`() {
        val routine = Routine(startMinuteOfDay = 0, endMinuteOfDay = 24 * 60)
        assertEquals(0, Schedule.endMinuteOfDay(routine))
    }

    // --- clockLabel ---------------------------------------------------------

    @Test
    fun `clockLabel formats the first row`() {
        assertEquals("7:00", Schedule.clockLabel(7 * 60, row = 0, cols = 10))
    }

    @Test
    fun `clockLabel advances by cols per row`() {
        assertEquals("7:10", Schedule.clockLabel(7 * 60, row = 1, cols = 10))
        assertEquals("7:20", Schedule.clockLabel(7 * 60, row = 2, cols = 10))
    }

    @Test
    fun `clockLabel wraps at 24h`() {
        // 23:50 start, row 1 at 10 cols -> 00:00
        assertEquals("0:00", Schedule.clockLabel(23 * 60 + 50, row = 1, cols = 10))
    }

    @Test
    fun `clockLabel pads minutes`() {
        assertEquals("7:05", Schedule.clockLabel(7 * 60 + 5, row = 0, cols = 5))
    }

    // --- formatMinuteOfDay --------------------------------------------------

    @Test
    fun `formatMinuteOfDay formats and wraps`() {
        assertEquals("7:05", Schedule.formatMinuteOfDay(7 * 60 + 5))
        assertEquals("0:00", Schedule.formatMinuteOfDay(24 * 60))
        assertEquals("23:59", Schedule.formatMinuteOfDay(-1))
    }
}
