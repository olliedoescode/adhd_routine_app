package com.adhd.routineplanner.ui.plan.archive

// ARCHIVED with the edit-mode grid: continuous drag-resize was driven by the
// grid's resize strip. The timeline changes duration from the task editor.

import com.adhd.routineplanner.domain.Durations
import kotlin.math.abs

/**
 * Continuous resize tracking for the resize strip (spec §15.4).
 *
 * The strip is not a "one swipe = one step" control. It accumulates the total
 * distance the finger has travelled since the gesture began and maps that to a
 * position on the [Durations.LADDER]. Because it tracks *accumulated* distance
 * rather than per-event deltas, the user can drag down, change their mind
 * mid-gesture and drag back up, and the duration follows smoothly in both
 * directions without lifting a finger.
 *
 * The class is deliberately mutable and Android-free so it can be unit-tested
 * on the JVM.
 *
 * @param startMinutes the duration when the gesture began; snapped to the
 *   ladder so the first step is predictable.
 * @param pixelsPerStep how far the finger must travel to move one ladder
 *   position. Smaller means more sensitive.
 */
class ResizeGesture(
    startMinutes: Int,
    private val pixelsPerStep: Float,
) {
    /** The duration when the gesture began, snapped to the ladder. */
    val startMinutes: Int = Durations.snap(startMinutes)

    /** Ladder index at the start of the gesture. */
    private val startIndex: Int = Durations.indexOf(this.startMinutes)

    /** Total vertical distance travelled since the gesture began, in pixels. */
    private var accumulatedPx: Float = 0f

    /** The most recent duration produced by [dragBy]. */
    var currentMinutes: Int = this.startMinutes
        private set

    /**
     * Feed a drag delta in pixels. Positive [dy] means the finger moved down,
     * which lengthens the task.
     *
     * @return the new duration, or `null` if it did not change.
     */
    fun dragBy(dy: Float): Int? {
        accumulatedPx += dy
        val steps = (accumulatedPx / pixelsPerStep).toInt()
        val target = (startIndex + steps).coerceIn(0, Durations.LADDER.lastIndex)
        val next = Durations.LADDER[target]
        if (next == currentMinutes) return null
        currentMinutes = next
        return next
    }

    /**
     * True when the finger has travelled far enough to be considered a real
     * drag rather than a tap. Used to suppress the haptic on a stray touch.
     */
    fun hasMoved(): Boolean = abs(accumulatedPx) >= pixelsPerStep / 2f

    /** Reset to a new starting duration, e.g. when the selection changes. */
    fun reset(startMinutes: Int) {
        accumulatedPx = 0f
        currentMinutes = Durations.snap(startMinutes)
    }
}
