package com.adhd.routineplanner.viewmodel

import com.adhd.routineplanner.domain.Routine
import com.adhd.routineplanner.ui.plan.GridTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the derived fields on [PlanUiState] (spec §16).
 *
 * These are pure functions over an immutable snapshot, so they run on the JVM
 * with no Robolectric or Android dependencies.
 */
class PlanUiStateTest {

    /** A 07:00–08:00 window, i.e. 60 minutes of capacity. */
    private val routine = Routine(
        id = 1,
        name = "Morning",
        startMinuteOfDay = 7 * 60,
        endMinuteOfDay = 8 * 60,
    )

    private val tasks = listOf(
        GridTask(1, 10, 0, "Shower", 0xFF4F8EF7.toInt()),
        GridTask(2, 15, 1, "Breakfast", 0xFFF2994A.toInt()),
        GridTask(3, 5, 2, "Get dressed", 0xFF34C7A1.toInt()),
    )

    private fun state(
        preview: PlanPreview = PlanPreview(),
        selectedId: Long? = null,
    ) = PlanUiState(
        routine = routine,
        tasks = tasks,
        preview = preview,
        selectedId = selectedId,
        loading = false,
    )

    @Test
    fun `totalMinutes sums the stored durations`() {
        assertEquals(30, state().totalMinutes)
    }

    @Test
    fun `capacityMinutes is the routine window`() {
        assertEquals(60, state().capacityMinutes)
    }

    @Test
    fun `bufferMinutes is capacity minus the claimed time`() {
        // 60 - 30
        assertEquals(30, state().bufferMinutes)
    }

    @Test
    fun `overrunMinutes is zero when the tasks fit`() {
        assertEquals(0, state().overrunMinutes)
    }

    @Test
    fun `overrunMinutes reports the shortfall when the tasks do not fit`() {
        val tight = PlanUiState(
            routine = Routine(startMinuteOfDay = 7 * 60, endMinuteOfDay = 7 * 60 + 20),
            tasks = tasks,
            loading = false,
        )
        assertEquals(20, tight.capacityMinutes)
        assertEquals(30, tight.totalMinutes)
        assertEquals(10, tight.overrunMinutes)
        assertEquals(0, tight.bufferMinutes)
    }

    @Test
    fun `displayTasks is the stored list when no preview is pending`() {
        assertEquals(tasks, state().displayTasks)
    }

    @Test
    fun `displayTasks folds in a pending resize`() {
        val preview = PlanPreview(resizeTaskId = 2, resizeMinutes = 25)
        val display = state(preview).displayTasks
        assertEquals(25, display.first { it.id == 2L }.minutes)
        // Other tasks are untouched.
        assertEquals(10, display.first { it.id == 1L }.minutes)
        assertEquals(5, display.first { it.id == 3L }.minutes)
    }

    @Test
    fun `totalMinutes reflects a pending resize`() {
        val preview = PlanPreview(resizeTaskId = 2, resizeMinutes = 25)
        // 10 + 25 + 5
        assertEquals(40, state(preview).totalMinutes)
    }

    @Test
    fun `displayTasks ignores a preview with no minutes`() {
        val preview = PlanPreview(resizeTaskId = 2, resizeMinutes = null)
        assertEquals(tasks, state(preview).displayTasks)
    }

    @Test
    fun `endMinuteOfDay is the declared routine end, not the task sum`() {
        // The window is authoritative: 08:00 regardless of the 30 claimed minutes.
        assertEquals(8 * 60, state().endMinuteOfDay)
    }

    @Test
    fun `endMinuteOfDay does not move when a resize is pending`() {
        val preview = PlanPreview(resizeTaskId = 2, resizeMinutes = 25)
        assertEquals(8 * 60, state(preview).endMinuteOfDay)
    }

    @Test
    fun `maxDurationFor leaves room for the other tasks`() {
        // Capacity 60, others claim 15 + 5 = 20, so task 1 may grow to 40.
        assertEquals(40, state().maxDurationFor(taskId = 1))
    }

    @Test
    fun `maxDurationFor a new task is the remaining capacity`() {
        // Capacity 60, everything claims 30, so a new task may be 30.
        assertEquals(30, state().maxDurationFor(taskId = null))
    }

    @Test
    fun `selectedTask returns the task matching selectedId`() {
        assertEquals("Breakfast", state(selectedId = 2).selectedTask?.name)
    }

    @Test
    fun `selectedTask is null when nothing is selected`() {
        assertNull(state().selectedTask)
    }

    @Test
    fun `selectedTask is null for an unknown id`() {
        assertNull(state(selectedId = 99).selectedTask)
    }

    @Test
    fun `empty preview reports isEmpty`() {
        assertTrue(PlanPreview().isEmpty)
    }

    @Test
    fun `a resize preview is not empty`() {
        assertTrue(!PlanPreview(resizeTaskId = 1, resizeMinutes = 5).isEmpty)
    }

    @Test
    fun `an empty routine has zero total and the declared end`() {
        val empty = PlanUiState(routine = routine, loading = false)
        assertEquals(0, empty.totalMinutes)
        assertEquals(60, empty.capacityMinutes)
        assertEquals(60, empty.bufferMinutes)
        assertEquals(8 * 60, empty.endMinuteOfDay)
    }
}
