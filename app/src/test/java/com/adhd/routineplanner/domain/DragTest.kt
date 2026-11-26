package com.adhd.routineplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DragTest {

    private fun task(id: Long, minutes: Int, position: Int = id.toInt()) =
        PlacedTask(id = id, minutes = minutes, position = position)

    // A: 0..9, B: 10..19, C: 20..24, D: 25..39
    private val tasks = listOf(
        task(1, 10),
        task(2, 10),
        task(3, 5),
        task(4, 15),
    )

    /**
     * A window exactly as long as the tasks claim, so the buffer is zero and
     * the rendered boundaries match the raw cumulative sums above.
     */
    private val routine = Routine(
        name = "Test",
        startMinuteOfDay = 7 * 60,
        endMinuteOfDay = 7 * 60 + 40,
    )

    // --- moveInsertionIndex ------------------------------------------------

    @Test
    fun `move insertion index excludes the dragged task`() {
        // Dragging A (index 0) to minute 12 (inside B) should land at index 0
        // of the remaining list [B, C, D], i.e. before B.
        assertEquals(0, Drag.moveInsertionIndex(tasks, draggedIndex = 0, minute = 12))
    }

    @Test
    fun `move insertion index to the empty tail appends`() {
        // Remaining list is [A, B, C] = 25 minutes; minute 30 is past the end.
        assertEquals(3, Drag.moveInsertionIndex(tasks, draggedIndex = 3, minute = 30))
    }

    @Test
    fun `move insertion index out of range falls back to plain insertion`() {
        assertEquals(
            Reorder.insertionIndex(tasks, 12),
            Drag.moveInsertionIndex(tasks, draggedIndex = 99, minute = 12),
        )
    }

    // --- moveTo ------------------------------------------------------------

    @Test
    fun `moveTo first task to the end`() {
        val result = Drag.moveTo(tasks, fromIndex = 0, minute = 100)
        assertEquals(listOf(2L, 3L, 4L, 1L), result.map { it.id })
    }

    @Test
    fun `moveTo last task to the front`() {
        val result = Drag.moveTo(tasks, fromIndex = 3, minute = 0)
        assertEquals(listOf(4L, 1L, 2L, 3L), result.map { it.id })
    }

    @Test
    fun `moveTo same slot is a no-op`() {
        // Dropping A back onto its own span keeps the order.
        val result = Drag.moveTo(tasks, fromIndex = 0, minute = 5)
        assertEquals(tasks.map { it.id }, result.map { it.id })
    }

    @Test
    fun `moveTo one slot forward swaps neighbours`() {
        // Move A (index 0) to minute 20. Remaining is [B(10), C(5), D(15)], so
        // minute 20 is the start of C in that list: insertion index 1, giving
        // [B, A, C, D].
        val result = Drag.moveTo(tasks, fromIndex = 0, minute = 20)
        assertEquals(listOf(2L, 1L, 3L, 4L), result.map { it.id })
    }

    @Test
    fun `moveTo out of range returns input`() {
        assertEquals(tasks, Drag.moveTo(tasks, fromIndex = 9, minute = 0))
    }

    // --- insertAt ----------------------------------------------------------

    @Test
    fun `insertAt front`() {
        val new = task(9, 5)
        val result = Drag.insertAt(tasks, new, minute = 0)
        assertEquals(listOf(9L, 1L, 2L, 3L, 4L), result.map { it.id })
    }

    @Test
    fun `insertAt middle`() {
        val new = task(9, 5)
        // Minute 12 is inside B (10..19), so insert before B.
        val result = Drag.insertAt(tasks, new, minute = 12)
        assertEquals(listOf(1L, 9L, 2L, 3L, 4L), result.map { it.id })
    }

    @Test
    fun `insertAt empty tail appends`() {
        val new = task(9, 5)
        val result = Drag.insertAt(tasks, new, minute = 999)
        assertEquals(listOf(1L, 2L, 3L, 4L, 9L), result.map { it.id })
    }

    @Test
    fun `insertAt into empty list`() {
        val new = task(9, 5)
        assertEquals(listOf(new), Drag.insertAt(emptyList(), new, minute = 0))
    }

    // --- boundaryMinute ----------------------------------------------------

    @Test
    fun `boundary minute snaps to the start of the hovered task`() {
        assertEquals(10, Drag.boundaryMinute(routine, tasks, minute = 12))
    }

    @Test
    fun `boundary minute in the tail is the total length`() {
        assertEquals(40, Drag.boundaryMinute(routine, tasks, minute = 100))
    }

    @Test
    fun `boundary minute at the very start is zero`() {
        assertEquals(0, Drag.boundaryMinute(routine, tasks, minute = 0))
    }

    @Test
    fun `boundary minute includes the buffer share of earlier tasks`() {
        // 10 + 10 + 5 + 15 = 40 claimed in a 60 minute window: 20 minutes of
        // buffer. Task 0 gets 10 + 5 = 15, so the first boundary is at 15.
        val roomy = Routine(
            name = "Roomy",
            startMinuteOfDay = 7 * 60,
            endMinuteOfDay = 7 * 60 + 60,
        )
        assertEquals(15, Drag.boundaryMinute(roomy, tasks, minute = 12))
    }

    // --- nearestInsertionMinute -------------------------------------------

    @Test
    fun `nearest insertion minute snaps inside a task to its nearest boundary`() {
        assertEquals(10, Drag.nearestInsertionMinute(tasks, minute = 12))
        assertEquals(20, Drag.nearestInsertionMinute(tasks, minute = 18))
    }

    @Test
    fun `nearest insertion minute clamps into the empty tail`() {
        assertEquals(40, Drag.nearestInsertionMinute(tasks, minute = 100))
    }

    @Test
    fun `nearest insertion minute for an empty grid is zero`() {
        assertEquals(0, Drag.nearestInsertionMinute(emptyList(), minute = 25))
    }

    // --- boundariesOf ------------------------------------------------------

    @Test
    fun `boundaries are the cumulative starts plus the total`() {
        // A: 10, B: 10, C: 5, D: 15 -> 0, 10, 20, 25, 40
        assertEquals(listOf(0, 10, 20, 25, 40), Drag.boundariesOf(tasks))
    }

    @Test
    fun `boundaries of an empty list is just zero`() {
        assertEquals(listOf(0), Drag.boundariesOf(emptyList()))
    }

    // --- resolveSlot -------------------------------------------------------

    @Test
    fun `resolveSlot with no held slot snaps to the nearest boundary`() {
        // Minute 12 is nearest boundary 10 -> slot 1.
        assertEquals(1, Drag.resolveSlot(tasks, minute = 12, currentSlot = null))
        // Minute 18 is nearest boundary 20 -> slot 2.
        assertEquals(2, Drag.resolveSlot(tasks, minute = 18, currentSlot = null))
    }

    @Test
    fun `resolveSlot holds the current slot until the midpoint is crossed`() {
        // Held at slot 1 (boundary 10). Midpoint to slot 2 (boundary 20) is 15.
        // With hysteresis 2, the slot only flips past 17.
        assertEquals(1, Drag.resolveSlot(tasks, minute = 16, currentSlot = 1, hysteresis = 2))
        assertEquals(2, Drag.resolveSlot(tasks, minute = 18, currentSlot = 1, hysteresis = 2))
    }

    @Test
    fun `resolveSlot moves backwards past the midpoint`() {
        // Held at slot 2 (boundary 20). Midpoint to slot 1 (boundary 10) is 15.
        // With hysteresis 2, the slot only flips below 13.
        assertEquals(2, Drag.resolveSlot(tasks, minute = 14, currentSlot = 2, hysteresis = 2))
        assertEquals(1, Drag.resolveSlot(tasks, minute = 12, currentSlot = 2, hysteresis = 2))
    }

    @Test
    fun `resolveSlot clamps into the empty tail`() {
        assertEquals(4, Drag.resolveSlot(tasks, minute = 999, currentSlot = null))
    }

    @Test
    fun `resolveSlot for an empty list is zero`() {
        assertEquals(0, Drag.resolveSlot(emptyList(), minute = 25, currentSlot = null))
    }

    @Test
    fun `resolveSlot only advances one slot per call`() {
        // Held at 0, finger jumps far past several boundaries. One call moves
        // at most one slot, so the ghost slides rather than teleports.
        assertEquals(1, Drag.resolveSlot(tasks, minute = 39, currentSlot = 0, hysteresis = 0))
    }
}
