package com.adhd.routineplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class GridTest {

    // --- rowsFor ------------------------------------------------------------

    @Test
    fun `rowsFor rounds up partial rows`() {
        assertEquals(1, Grid.rowsFor(10, 10))
        assertEquals(2, Grid.rowsFor(11, 10))
        assertEquals(3, Grid.rowsFor(25, 10))
    }

    @Test
    fun `rowsFor of zero minutes is zero`() {
        assertEquals(0, Grid.rowsFor(0, 10))
    }

    @Test
    fun `rowsFor handles each zoom step`() {
        assertEquals(1, Grid.rowsFor(20, 20))
        assertEquals(2, Grid.rowsFor(20, 15))
        assertEquals(2, Grid.rowsFor(20, 10))
        assertEquals(4, Grid.rowsFor(20, 5))
    }

    // --- cellAt -------------------------------------------------------------

    @Test
    fun `cellAt maps the origin to minute zero`() {
        assertEquals(0, Grid.cellAt(Point(0f, 0f), cellPx = 30f, cols = 10))
    }

    @Test
    fun `cellAt maps columns within the first row`() {
        assertEquals(3, Grid.cellAt(Point(95f, 5f), cellPx = 30f, cols = 10))
    }

    @Test
    fun `cellAt maps rows`() {
        // row 2, col 4 -> 2 * 10 + 4 = 24
        assertEquals(24, Grid.cellAt(Point(4 * 30f + 1f, 2 * 30f + 1f), cellPx = 30f, cols = 10))
    }

    @Test
    fun `cellAt clamps negative coordinates to zero`() {
        assertEquals(0, Grid.cellAt(Point(-10f, -10f), cellPx = 30f, cols = 10))
    }

    @Test
    fun `cellAt respects the column count`() {
        // row 1, col 0 at 5 columns -> 5
        assertEquals(5, Grid.cellAt(Point(1f, 60f + 1f), cellPx = 60f, cols = 5))
    }

    // --- nextZoomStep -------------------------------------------------------

    @Test
    fun `nextZoomStep steps in at 1_15`() {
        assertEquals(1, Grid.nextZoomStep(current = 0, accumulatedScale = 1.15f))
    }

    @Test
    fun `nextZoomStep does not step in at 1_14`() {
        assertEquals(0, Grid.nextZoomStep(current = 0, accumulatedScale = 1.14f))
    }

    @Test
    fun `nextZoomStep steps out at 0_85`() {
        assertEquals(0, Grid.nextZoomStep(current = 1, accumulatedScale = 0.85f))
    }

    @Test
    fun `nextZoomStep does not step out at 0_86`() {
        assertEquals(1, Grid.nextZoomStep(current = 1, accumulatedScale = 0.86f))
    }

    @Test
    fun `nextZoomStep clamps at the widest and narrowest steps`() {
        assertEquals(0, Grid.nextZoomStep(current = 0, accumulatedScale = 0.5f))
        assertEquals(Grid.ZOOM_STEPS.lastIndex, Grid.nextZoomStep(current = 3, accumulatedScale = 2f))
    }

    @Test
    fun `zoom steps are the specified column counts`() {
        assertEquals(listOf(20, 15, 10, 5), Grid.ZOOM_STEPS.toList())
    }

    // --- consumePinch -------------------------------------------------------

    @Test
    fun `consumePinch below the threshold takes no step`() {
        val result = Grid.consumePinch(current = 0, accumulatedScale = 1.1f)
        assertEquals(0, result.step)
        assertEquals(0, result.steps)
        assertEquals(1.1f, result.remainingScale, 0.0001f)
    }

    @Test
    fun `consumePinch takes one step at the threshold`() {
        val result = Grid.consumePinch(current = 0, accumulatedScale = 1.15f)
        assertEquals(1, result.step)
        assertEquals(1, result.steps)
        // 1.15 / 1.15 == 1.0 leftover.
        assertEquals(1f, result.remainingScale, 0.0001f)
    }

    @Test
    fun `consumePinch crosses several levels in one call`() {
        // 1.15^3 is enough for three steps.
        val scale = 1.15f * 1.15f * 1.15f
        val result = Grid.consumePinch(current = 0, accumulatedScale = scale)
        assertEquals(3, result.step)
        assertEquals(3, result.steps)
        assertEquals(1f, result.remainingScale, 0.0001f)
    }

    @Test
    fun `consumePinch steps out several levels`() {
        val scale = 0.85f * 0.85f
        val result = Grid.consumePinch(current = 3, accumulatedScale = scale)
        assertEquals(1, result.step)
        assertEquals(2, result.steps)
    }

    @Test
    fun `consumePinch clamps at the widest step`() {
        val result = Grid.consumePinch(current = 0, accumulatedScale = 0.5f)
        assertEquals(0, result.step)
        assertEquals(0, result.steps)
    }

    @Test
    fun `consumePinch clamps at the narrowest step`() {
        val result = Grid.consumePinch(current = 3, accumulatedScale = 2f)
        assertEquals(3, result.step)
        assertEquals(0, result.steps)
    }

    @Test
    fun `consumePinch leftover continues the next event`() {
        // First event: 1.15 * 1.15 -> two steps, leftover 1.0.
        val first = Grid.consumePinch(current = 0, accumulatedScale = 1.15f * 1.15f)
        assertEquals(2, first.step)
        // Second event adds a little more; the leftover carries over.
        val second = Grid.consumePinch(
            current = first.step,
            accumulatedScale = first.remainingScale * 1.15f,
        )
        assertEquals(3, second.step)
    }
}
