package com.adhd.routineplanner.domain

import kotlin.math.ceil

/**
 * Plain 2D point used instead of Compose's `Offset` so `domain/` stays
 * Android-free (spec section 5).
 */
data class Point(val x: Float, val y: Float)

/**
 * Grid geometry and zoom maths (spec sections 14.1, 14.3).
 *
 * 1 cell = 1 minute. A routine of T minutes has T cells in reading order, so
 * `row = minute / cols` and `col = minute % cols`.
 */
object Grid {

    /** Column steps for pinch and menu zoom, spaced in intervals of five. */
    val ZOOM_STEPS: IntArray = intArrayOf(20, 15, 10, 5)

    /** Default column count each time a routine is opened. */
    const val DEFAULT_COLS: Int = 10

    /** Pinch scale above which we step towards fewer columns. */
    const val ZOOM_IN_THRESHOLD: Float = 1.15f

    /** Pinch scale below which we step back towards more columns. */
    const val ZOOM_OUT_THRESHOLD: Float = 0.85f

    /** Number of rows needed to show [totalMinutes] at [cols] columns. */
    fun rowsFor(totalMinutes: Int, cols: Int): Int {
        if (totalMinutes <= 0 || cols <= 0) return 0
        return ceil(totalMinutes.toDouble() / cols.toDouble()).toInt()
    }

    /**
     * Minute index under a touch at [pos], given a square [cellPx] and [cols].
     *
     * Formula from spec section 7.3:
     * `(pos.y / cellPx).toInt() * cols + (pos.x / cellPx).toInt()`.
     * Negative coordinates clamp to 0 so a touch just outside the grid still
     * maps to the nearest cell.
     */
    fun cellAt(pos: Point, cellPx: Float, cols: Int): Int {
        if (cellPx <= 0f || cols <= 0) return 0
        val row = (pos.y / cellPx).toInt().coerceAtLeast(0)
        val col = (pos.x / cellPx).toInt().coerceAtLeast(0)
        return row * cols + col
    }

    /**
     * Next zoom step given the current step index and the accumulated pinch
     * scale (spec section 14.3). Crossing 1.15 steps towards fewer columns
     * (index + 1); crossing 0.85 steps back (index - 1). The result is clamped
     * to the valid range of [ZOOM_STEPS].
     */
    fun nextZoomStep(current: Int, accumulatedScale: Float): Int {
        val clampedCurrent = current.coerceIn(0, ZOOM_STEPS.lastIndex)
        return when {
            accumulatedScale >= ZOOM_IN_THRESHOLD -> (clampedCurrent + 1).coerceAtMost(ZOOM_STEPS.lastIndex)
            accumulatedScale <= ZOOM_OUT_THRESHOLD -> (clampedCurrent - 1).coerceAtLeast(0)
            else -> clampedCurrent
        }
    }

    /** Result of consuming a pinch scale: the new step index and leftover scale. */
    data class PinchResult(val step: Int, val remainingScale: Float, val steps: Int)

    /**
     * Consumes an accumulated pinch scale, stepping as many levels as it
     * contains. Unlike [nextZoomStep], which only ever moves one level, this
     * lets a single continuous spread or pinch cross several zoom levels
     * without lifting the fingers (spec section 14.3).
     *
     * Each [ZOOM_IN_THRESHOLD] of scale above 1 (or [ZOOM_OUT_THRESHOLD] below
     * 1) is consumed as one step, and the leftover scale is returned so the
     * next gesture event continues from where this one stopped. Stepping stops
     * at the ends of [ZOOM_STEPS].
     *
     * @param current the current step index.
     * @param accumulatedScale the total scale accumulated so far.
     */
    fun consumePinch(current: Int, accumulatedScale: Float): PinchResult {
        var step = current.coerceIn(0, ZOOM_STEPS.lastIndex)
        var scale = accumulatedScale
        var steps = 0
        while (scale >= ZOOM_IN_THRESHOLD && step < ZOOM_STEPS.lastIndex) {
            step++
            scale /= ZOOM_IN_THRESHOLD
            steps++
        }
        while (scale <= ZOOM_OUT_THRESHOLD && step > 0) {
            step--
            scale /= ZOOM_OUT_THRESHOLD
            steps++
        }
        return PinchResult(step = step, remainingScale = scale, steps = steps)
    }
}
