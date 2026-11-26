package com.adhd.routineplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ReorderTest {

    private fun task(minutes: Int, id: Long) = PlacedTask(id = id, minutes = minutes)

    private val tasks = listOf(
        task(10, 1), // minutes 0..9
        task(5, 2),  // minutes 10..14
        task(20, 3), // minutes 15..34
    )

    // --- insertionIndex -----------------------------------------------------

    @Test
    fun `insertionIndex at the first task`() {
        assertEquals(0, Reorder.insertionIndex(tasks, 0))
    }

    @Test
    fun `insertionIndex inside the first task`() {
        assertEquals(0, Reorder.insertionIndex(tasks, 5))
    }

    @Test
    fun `insertionIndex at an exact boundary picks the later task`() {
        // minute 10 is the first minute of task 2
        assertEquals(1, Reorder.insertionIndex(tasks, 10))
        // minute 15 is the first minute of task 3
        assertEquals(2, Reorder.insertionIndex(tasks, 15))
    }

    @Test
    fun `insertionIndex inside the last task`() {
        assertEquals(2, Reorder.insertionIndex(tasks, 34))
    }

    @Test
    fun `insertionIndex in the empty tail appends`() {
        assertEquals(3, Reorder.insertionIndex(tasks, 35))
        assertEquals(3, Reorder.insertionIndex(tasks, 1000))
    }

    @Test
    fun `insertionIndex on an empty list is zero`() {
        assertEquals(0, Reorder.insertionIndex(emptyList(), 0))
    }

    // --- move ---------------------------------------------------------------

    @Test
    fun `move forward shifts the intervening tasks back`() {
        val moved = Reorder.move(tasks, fromIndex = 0, toIndex = 2)
        assertEquals(listOf(2L, 3L, 1L), moved.map { it.id })
    }

    @Test
    fun `move backward shifts the intervening tasks forward`() {
        val moved = Reorder.move(tasks, fromIndex = 2, toIndex = 0)
        assertEquals(listOf(3L, 1L, 2L), moved.map { it.id })
    }

    @Test
    fun `move to the same index is a no-op`() {
        assertEquals(tasks, Reorder.move(tasks, 1, 1))
    }

    @Test
    fun `move clamps out-of-range indices`() {
        assertEquals(listOf(2L, 3L, 1L), Reorder.move(tasks, 0, 99).map { it.id })
        assertEquals(listOf(3L, 1L, 2L), Reorder.move(tasks, 99, 0).map { it.id })
    }

    @Test
    fun `move on an empty list returns it unchanged`() {
        assertEquals(emptyList<PlacedTask>(), Reorder.move(emptyList(), 0, 1))
    }

    // --- resize -------------------------------------------------------------

    @Test
    fun `resize changes the duration`() {
        val resized = Reorder.resize(tasks, index = 1, minutes = 12)
        assertEquals(12, resized[1].minutes)
    }

    @Test
    fun `resize clamps to one minute`() {
        val resized = Reorder.resize(tasks, index = 1, minutes = 0)
        assertEquals(1, resized[1].minutes)
        val negative = Reorder.resize(tasks, index = 1, minutes = -50)
        assertEquals(1, negative[1].minutes)
    }

    @Test
    fun `resize with an out-of-range index is a no-op`() {
        assertEquals(tasks, Reorder.resize(tasks, index = 9, minutes = 5))
    }

    // --- remove -------------------------------------------------------------

    @Test
    fun `remove deletes the task at the index`() {
        assertEquals(listOf(1L, 3L), Reorder.remove(tasks, 1).map { it.id })
    }

    @Test
    fun `remove with an out-of-range index is a no-op`() {
        assertEquals(tasks, Reorder.remove(tasks, 9))
    }

    // --- renumber -----------------------------------------------------------

    @Test
    fun `renumber reassigns positions to 0 to n-1`() {
        val unnumbered = listOf(
            task(10, 1).copy(position = 5),
            task(5, 2).copy(position = 5),
            task(20, 3).copy(position = 5),
        )
        assertEquals(listOf(0, 1, 2), Reorder.renumber(unnumbered).map { it.position })
    }

    @Test
    fun `renumber leaves already-correct positions untouched`() {
        val numbered = listOf(
            task(10, 1).copy(position = 0),
            task(5, 2).copy(position = 1),
        )
        assertEquals(numbered, Reorder.renumber(numbered))
    }
}
