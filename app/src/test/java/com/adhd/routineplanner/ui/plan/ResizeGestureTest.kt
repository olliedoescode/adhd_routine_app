package com.adhd.routineplanner.ui.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for continuous resize tracking (spec §15.4).
 *
 * The key behaviour is that the duration follows accumulated finger travel, so
 * reversing direction mid-gesture moves back down the ladder immediately.
 */
class ResizeGestureTest {

    private fun gesture(start: Int = 10, step: Float = 100f) =
        ResizeGesture(startMinutes = start, pixelsPerStep = step)

    @Test
    fun `starts at the snapped duration`() {
        assertEquals(10, gesture(start = 10).currentMinutes)
        // 11 snaps to 10.
        assertEquals(10, gesture(start = 11).currentMinutes)
        // 12 snaps to 15 (tie goes to the larger value).
        assertEquals(15, gesture(start = 12).currentMinutes)
    }

    @Test
    fun `a small drag does not change the duration`() {
        val g = gesture()
        assertNull(g.dragBy(40f))
        assertEquals(10, g.currentMinutes)
    }

    @Test
    fun `one full step down the screen lengthens by one ladder position`() {
        val g = gesture(start = 10, step = 100f)
        assertEquals(15, g.dragBy(100f))
    }

    @Test
    fun `two steps lengthen by two ladder positions`() {
        val g = gesture(start = 10, step = 100f)
        assertEquals(15, g.dragBy(100f))
        assertEquals(20, g.dragBy(100f))
    }

    @Test
    fun `dragging up shortens the task`() {
        val g = gesture(start = 20, step = 100f)
        assertEquals(15, g.dragBy(-100f))
        assertEquals(10, g.dragBy(-100f))
    }

    @Test
    fun `reversing mid-gesture moves back immediately`() {
        val g = gesture(start = 10, step = 100f)
        // Down two steps: 10 -> 15 -> 20.
        assertEquals(15, g.dragBy(100f))
        assertEquals(20, g.dragBy(100f))
        // Back up one step: 20 -> 15.
        assertEquals(15, g.dragBy(-100f))
        // And back to where we started.
        assertEquals(10, g.dragBy(-100f))
    }

    @Test
    fun `accumulated distance is not reset by direction changes`() {
        val g = gesture(start = 10, step = 100f)
        // 60 down then 60 up nets zero, so no change overall.
        assertNull(g.dragBy(60f))
        assertNull(g.dragBy(-60f))
        assertEquals(10, g.currentMinutes)
    }

    @Test
    fun `many small deltas accumulate into a step`() {
        val g = gesture(start = 10, step = 100f)
        assertNull(g.dragBy(30f))
        assertNull(g.dragBy(30f))
        assertNull(g.dragBy(30f))
        // 90px so far, still short of 100.
        assertEquals(10, g.currentMinutes)
        assertEquals(15, g.dragBy(30f))
    }

    @Test
    fun `clamps at the smallest duration`() {
        val g = gesture(start = 5, step = 100f)
        assertNull(g.dragBy(-500f))
        assertEquals(5, g.currentMinutes)
    }

    @Test
    fun `clamps at the largest duration`() {
        val g = gesture(start = 120, step = 100f)
        assertNull(g.dragBy(500f))
        assertEquals(120, g.currentMinutes)
    }

    @Test
    fun `a long drag walks the whole ladder`() {
        val g = gesture(start = 5, step = 100f)
        val seen = mutableListOf(5)
        repeat(20) {
            g.dragBy(100f)
            if (seen.last() != g.currentMinutes) seen.add(g.currentMinutes)
        }
        assertEquals(listOf(5, 10, 15, 20, 25, 30, 45, 60, 90, 120), seen)
    }

    @Test
    fun `hasMoved is false for a tap`() {
        val g = gesture(step = 100f)
        g.dragBy(10f)
        assertFalse(g.hasMoved())
    }

    @Test
    fun `hasMoved is true past half a step`() {
        val g = gesture(step = 100f)
        g.dragBy(60f)
        assertTrue(g.hasMoved())
    }

    @Test
    fun `reset clears the accumulator`() {
        val g = gesture(start = 10, step = 100f)
        g.dragBy(100f)
        assertEquals(15, g.currentMinutes)
        g.reset(30)
        assertEquals(30, g.currentMinutes)
        // The accumulator is back to zero, so a fresh step is needed.
        assertNull(g.dragBy(40f))
        assertEquals(45, g.dragBy(60f))
    }
}
