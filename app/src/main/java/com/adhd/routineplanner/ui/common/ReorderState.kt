package com.adhd.routineplanner.ui.common

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Shared drag-to-reorder engine used by both the plan screen's timeline editor
 * and the routine-creation task list.
 *
 * The previous implementations swapped a row with its neighbour as soon as the
 * finger travelled half a fixed row height, then reset the offset. That had
 * three problems:
 *
 *  - the dragged row never moved under the finger, so the gesture felt dead;
 *  - the threshold used a constant row height, so tall rows swapped too early
 *    and short rows too late;
 *  - the swap fired from inside `onDrag`, which re-keyed the list mid-gesture
 *    and could drop the gesture entirely ("sometimes it doesn't work").
 *
 * This engine instead keeps the list order fixed while the finger is down and
 * only reports a *target index*. The caller draws the dragged row translated by
 * [dragOffset] and lets the other rows animate out of the way, then commits the
 * move once on release. That is what makes the drag feel continuous.
 *
 * @param itemCount the number of rows currently in the list.
 * @param rowHeightPx a function returning each row's height in pixels, so the
 *   engine can compute how far the finger has travelled in *rows* rather than
 *   in a fixed unit.
 * @param onMove called once on release with the row's original index and its
 *   final index. Never called when the row did not change position.
 */
@Stable
class ReorderState(
    private val itemCount: () -> Int,
    private val rowHeightPx: (index: Int) -> Float,
    private val onMove: (from: Int, to: Int) -> Unit,
) {
    /** The row currently held, or -1 when nothing is being dragged. */
    var draggingIndex by mutableIntStateOf(-1)
        private set

    /** Vertical travel of the finger since the drag began, in pixels. */
    var dragOffset by mutableFloatStateOf(0f)
        private set

    /** Where the dragged row would land if released now. */
    var targetIndex by mutableIntStateOf(-1)
        private set

    val isDragging: Boolean get() = draggingIndex >= 0

    /**
     * The vertical shift, in pixels, that a row at [index] should be drawn with
     * so the list opens a gap for the dragged row. Rows between the dragged
     * row and the target slide by exactly the dragged row's height.
     */
    fun shiftFor(index: Int): Float {
        val from = draggingIndex
        val to = targetIndex
        if (from < 0 || to < 0 || from == to) return 0f
        val draggedHeight = rowHeightPx(from)
        return when {
            from < to && index in IntRange(from + 1, to) -> -draggedHeight
            to < from && index in IntRange(to, from - 1) -> draggedHeight
            else -> 0f
        }
    }

    /** Begin a drag on [index]. Called from `onDragStart`. */
    fun start(index: Int) {
        draggingIndex = index
        targetIndex = index
        dragOffset = 0f
    }

    /** Accumulate finger travel and recompute the target slot. */
    fun drag(deltaY: Float) {
        val from = draggingIndex
        if (from < 0) return
        dragOffset += deltaY
        targetIndex = resolveTarget(from, dragOffset)
    }

    /** Finish the drag, committing the move if the slot changed. */
    fun end() {
        val from = draggingIndex
        val to = targetIndex
        reset()
        if (from >= 0 && to >= 0 && from != to) onMove(from, to)
    }

    /** Abandon the drag without moving anything. */
    fun cancel() = reset()

    private fun reset() {
        draggingIndex = -1
        targetIndex = -1
        dragOffset = 0f
    }

    /**
     * Walk outward from the dragged row, consuming the finger's travel against
     * each neighbour's real height. This is what makes a tall row need a
     * proportionally longer drag before it swaps.
     */
    private fun resolveTarget(from: Int, offset: Float): Int {
        val count = itemCount()
        if (count <= 1) return from
        var remaining = offset
        var target = from
        if (remaining > 0f) {
            while (target < count - 1) {
                val nextHeight = rowHeightPx(target + 1)
                if (remaining < nextHeight / 2f) break
                remaining -= nextHeight
                target++
            }
        } else if (remaining < 0f) {
            while (target > 0) {
                val prevHeight = rowHeightPx(target - 1)
                if (-remaining < prevHeight / 2f) break
                remaining += prevHeight
                target--
            }
        }
        return target
    }
}

/** Remembers a [ReorderState] for the given list size and row-height lookup. */
@Composable
fun rememberReorderState(
    itemCount: () -> Int,
    rowHeightPx: (index: Int) -> Float,
    onMove: (from: Int, to: Int) -> Unit,
): ReorderState {
    // The lambdas are captured once; they read the latest values through the
    // caller's own state, so the engine never needs to be recreated.
    val state = remember { ReorderState(itemCount, rowHeightPx, onMove) }
    return state
}

/**
 * Attaches the long-press-drag gesture for one row to [this] modifier.
 *
 * A separate tap detector runs alongside it so a plain tap still selects the
 * row: the drag detector only claims the pointer after the long-press timeout,
 * so a quick tap falls through to [onTap].
 *
 * @param state the shared reorder engine.
 * @param index this row's index.
 * @param onTap invoked on a plain tap (selection).
 */
@Composable
fun Modifier.reorderableRow(
    state: ReorderState,
    index: Int,
    onTap: () -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    return this
        .pointerInput(index, state) {
            detectTapGestures(onTap = { onTap() })
        }
        .pointerInput(index, state) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    state.start(index)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onDrag = { change, amount ->
                    change.consume()
                    state.drag(amount.y)
                },
                onDragEnd = { state.end() },
                onDragCancel = { state.cancel() },
            )
        }
}

