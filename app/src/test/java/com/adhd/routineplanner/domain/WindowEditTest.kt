package com.adhd.routineplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers every branch of the [WindowEdit] state machine.
 *
 * The regression this guards against: editing a routine's start or end time
 * used to wipe the routine's tasks. These tests pin down that a window edit
 * never removes a task, and that a window which still holds the tasks leaves
 * them completely untouched.
 */
class WindowEditTest {

    private fun routine(start: Int, end: Int) = Routine(
        id = 1L,
        name = "Morning",
        startMinuteOfDay = start,
        endMinuteOfDay = end,
    )

    private fun tasks(vararg minutes: Int) = minutes.mapIndexed { index, m ->
        PlacedTask(id = index + 1L, routineId = 1L, minutes = m, position = index)
    }

    // --- State 0: no tasks ---------------------------------------------------

    @Test
    fun `no tasks reports NoTasks and persists the routine`() {
        val outcome = WindowEdit.evaluate(
            previous = routine(7 * 60, 8 * 60),
            updated = routine(7 * 60, 9 * 60),
            tasks = emptyList(),
        )
        assertEquals(WindowEdit.State.NoTasks, outcome.state)
        assertTrue(outcome.shouldPersist)
        assertFalse(outcome.tasksChanged)
        assertTrue(outcome.tasks.isEmpty())
    }

    // --- State 1: unchanged window -------------------------------------------

    @Test
    fun `same window reports Unchanged and writes nothing`() {
        val existing = tasks(10, 20)
        val outcome = WindowEdit.evaluate(
            previous = routine(7 * 60, 8 * 60),
            updated = routine(7 * 60, 8 * 60),
            tasks = existing,
        )
        assertEquals(WindowEdit.State.Unchanged, outcome.state)
        assertFalse(outcome.shouldPersist)
        assertFalse(outcome.tasksChanged)
        assertSame(existing, outcome.tasks)
    }

    @Test
    fun `same capacity reached by a different start is still Unchanged`() {
        // 07:00-08:00 and 09:00-10:00 are both 60 minutes: the tasks are laid
        // out relative to the start, so nothing about them needs rewriting.
        val existing = tasks(10, 20)
        val outcome = WindowEdit.evaluate(
            previous = routine(7 * 60, 8 * 60),
            updated = routine(9 * 60, 10 * 60),
            tasks = existing,
        )
        assertEquals(WindowEdit.State.Unchanged, outcome.state)
        assertSame(existing, outcome.tasks)
    }

    // --- State 2: window grows / still fits ----------------------------------

    @Test
    fun `longer window leaves tasks untouched`() {
        val existing = tasks(10, 20, 30)
        val outcome = WindowEdit.evaluate(
            previous = routine(7 * 60, 8 * 60),
            updated = routine(7 * 60, 9 * 60),
            tasks = existing,
        )
        assertEquals(WindowEdit.State.Fits, outcome.state)
        assertTrue(outcome.shouldPersist)
        assertFalse(outcome.tasksChanged)
        assertSame(existing, outcome.tasks)
        assertEquals(60, outcome.claimed)
        assertEquals(120, outcome.capacity)
    }

    @Test
    fun `shorter window that still holds every minimum leaves tasks untouched`() {
        // 60 minutes of tasks in a 90-minute window shrinking to 70 minutes:
        // still enough for every task at its minimum, so nothing is rewritten.
        val existing = tasks(10, 20, 30)
        val outcome = WindowEdit.evaluate(
            previous = routine(7 * 60, 8 * 60 + 30),
            updated = routine(7 * 60, 8 * 60 + 10),
            tasks = existing,
        )
        assertEquals(WindowEdit.State.Fits, outcome.state)
        assertFalse(outcome.tasksChanged)
        assertSame(existing, outcome.tasks)
    }

    @Test
    fun `window exactly equal to the claimed minutes leaves tasks untouched`() {
        val existing = tasks(10, 20, 30)
        val outcome = WindowEdit.evaluate(
            previous = routine(7 * 60, 9 * 60),
            updated = routine(7 * 60, 8 * 60),
            tasks = existing,
        )
        assertEquals(WindowEdit.State.Fits, outcome.state)
        assertSame(existing, outcome.tasks)
    }

    // --- State 3: shrink -----------------------------------------------------

    @Test
    fun `shorter window shortens tasks but keeps every one`() {
        val existing = tasks(20, 20, 20)
        val outcome = WindowEdit.evaluate(
            previous = routine(7 * 60, 8 * 60),
            updated = routine(7 * 60, 7 * 60 + 30),
            tasks = existing,
        )
        assertEquals(WindowEdit.State.Shrink, outcome.state)
        assertTrue(outcome.shouldPersist)
        assertTrue(outcome.tasksChanged)
        assertEquals(3, outcome.tasks.size)
        assertEquals(30, outcome.tasks.sumOf { it.minutes })
        // Identity is preserved: same ids, same order, same names.
        assertEquals(existing.map { it.id }, outcome.tasks.map { it.id })
        assertEquals(existing.map { it.position }, outcome.tasks.map { it.position })
    }

    @Test
    fun `shrink never drops a task and never goes below one minute`() {
        val existing = tasks(50, 40, 30, 20, 10)
        val outcome = WindowEdit.evaluate(
            previous = routine(7 * 60, 9 * 60),
            updated = routine(7 * 60, 7 * 60 + 5),
            tasks = existing,
        )
        assertEquals(WindowEdit.State.Shrink, outcome.state)
        assertEquals(5, outcome.tasks.size)
        assertTrue(outcome.tasks.all { it.minutes >= TimeWindow.MIN_TASK_MINUTES })
        assertEquals(5, outcome.tasks.sumOf { it.minutes })
    }

    @Test
    fun `shrink preserves task names colours and alarms`() {
        val existing = listOf(
            PlacedTask(id = 1L, minutes = 30, name = "Shower", color = 0xFF112233.toInt(), alarmEnabled = true),
            PlacedTask(id = 2L, minutes = 30, name = "Coffee", color = 0xFF445566.toInt(), alarmEnabled = false),
        )
        val outcome = WindowEdit.evaluate(
            previous = routine(7 * 60, 8 * 60),
            updated = routine(7 * 60, 7 * 60 + 20),
            tasks = existing,
        )
        assertEquals(WindowEdit.State.Shrink, outcome.state)
        assertEquals(listOf("Shower", "Coffee"), outcome.tasks.map { it.name })
        assertEquals(listOf(0xFF112233.toInt(), 0xFF445566.toInt()), outcome.tasks.map { it.color })
        assertEquals(listOf(true, false), outcome.tasks.map { it.alarmEnabled })
    }

    // --- State 4: refuse -----------------------------------------------------

    @Test
    fun `window shorter than the task count is refused and writes nothing`() {
        val existing = tasks(10, 10, 10, 10)
        val outcome = WindowEdit.evaluate(
            previous = routine(7 * 60, 8 * 60),
            updated = routine(7 * 60, 7 * 60 + 3),
            tasks = existing,
        )
        assertEquals(WindowEdit.State.Refuse, outcome.state)
        assertFalse(outcome.shouldPersist)
        assertFalse(outcome.tasksChanged)
        assertSame(existing, outcome.tasks)
    }

    @Test
    fun `window exactly the task count is allowed`() {
        val existing = tasks(10, 10, 10)
        val outcome = WindowEdit.evaluate(
            previous = routine(7 * 60, 8 * 60),
            updated = routine(7 * 60, 7 * 60 + 3),
            tasks = existing,
        )
        assertEquals(WindowEdit.State.Shrink, outcome.state)
        assertEquals(listOf(1, 1, 1), outcome.tasks.map { it.minutes })
    }

    // --- Wrapping windows ----------------------------------------------------

    @Test
    fun `window crossing midnight is measured correctly`() {
        val existing = tasks(30, 30)
        val outcome = WindowEdit.evaluate(
            previous = routine(23 * 60, 23 * 60 + 30),
            updated = routine(23 * 60, 30),
            tasks = existing,
        )
        // 23:00 -> 00:30 is 90 minutes, which holds 60 minutes of tasks.
        assertEquals(90, outcome.capacity)
        assertEquals(WindowEdit.State.Fits, outcome.state)
        assertSame(existing, outcome.tasks)
    }

    // --- The regression itself -----------------------------------------------

    @Test
    fun `no window edit ever removes a task`() {
        val existing = tasks(15, 15, 15, 15)
        val windows = listOf(
            routine(7 * 60, 8 * 60),
            routine(7 * 60, 9 * 60),
            routine(7 * 60, 7 * 60 + 61),
            routine(7 * 60, 7 * 60 + 60),
            routine(7 * 60, 7 * 60 + 59),
            routine(7 * 60, 7 * 60 + 4),
            routine(7 * 60, 7 * 60 + 3),
            routine(6 * 60, 8 * 60),
        )
        windows.forEach { updated ->
            val outcome = WindowEdit.evaluate(routine(7 * 60, 8 * 60), updated, existing)
            assertEquals(
                "task count changed for window ${updated.startMinuteOfDay}..${updated.endMinuteOfDay}",
                existing.size,
                outcome.tasks.size,
            )
        }
    }
}
