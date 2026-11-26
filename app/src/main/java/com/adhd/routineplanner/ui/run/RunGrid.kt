package com.adhd.routineplanner.ui.run

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhd.routineplanner.domain.Grid
import com.adhd.routineplanner.domain.Phase
import com.adhd.routineplanner.domain.RunState
import com.adhd.routineplanner.domain.Schedule
import com.adhd.routineplanner.ui.plan.GridTask
import com.adhd.routineplanner.ui.theme.SelectionOutline
import com.adhd.routineplanner.ui.theme.TextSecondary
import com.adhd.routineplanner.ui.theme.washed
import kotlin.math.floor

/** Gap between cells, in dp, so the grid reads as separate minutes. */
private val CellGap = 1.dp

/** Corner radius of a filled minute cell. */
private val CellCorner = 2.dp

/** Width of the thin cursor line. */
private val CursorWidth = 2.dp

/** Width of the left clock gutter. */
private val GutterWidth = 44.dp

/**
 * The run-mode grid (spec sections 8.3, 8.4, 15.8).
 *
 * Draws the routine's minutes in reading order with `cols` columns:
 *
 * - minutes already elapsed are drawn in the task's full colour;
 * - minutes still to come are washed toward the background;
 * - the current minute is washed, with the fraction already elapsed filled in
 *   full colour up to the cursor;
 * - a thin vertical cursor line sweeps across the current minute, moving one
 *   cell per minute and wrapping to the next row.
 *
 * The cursor position is animated with a one-second linear tween so it glides
 * rather than jumping, and it is read inside the draw scope so only this
 * Canvas redraws each second (spec section 18).
 *
 * @param tasks the routine's tasks in order.
 * @param state the derived run state (elapsed time, current index, phase).
 * @param cols number of columns; the plan screen's zoom level is reused.
 * @param startMinuteOfDay the routine's start time, for the gutter clock labels.
 * @param totalMinutes the rendered routine length, buffer included. Passed in
 *   rather than summed from the tasks so the grid matches the run clock.
 * @param renderedMinutes each task's rendered length, buffer included, in the
 *   same order as [tasks]. Sums to [totalMinutes].
 * @param modifier layout modifier; the height is derived from the row count.
 */
@Composable
fun RunGrid(
    tasks: List<GridTask>,
    state: RunState,
    cols: Int,
    startMinuteOfDay: Int,
    totalMinutes: Int,
    renderedMinutes: List<Int>,
    modifier: Modifier = Modifier,
) {
    val safeCols = cols.coerceAtLeast(1)
    // Only the routine's own minutes are drawn: no trailing empty rows, so the
    // grid never shows time beyond the end of the routine.
    val rows = Grid.rowsFor(totalMinutes, safeCols)
    val textMeasurer = rememberTextMeasurer()

    // Fractional minute position of the cursor, 0..totalMinutes. Before the
    // start it sits at 0; once finished it rests at the end of the last minute.
    val targetMinute = when (state.phase) {
        Phase.BeforeStart -> 0f
        Phase.Finished -> totalMinutes.toFloat()
        Phase.Running -> (state.elapsedSeconds / 60f).coerceIn(0f, totalMinutes.toFloat())
    }
    val cursorMinute by animateFloatAsState(
        targetValue = targetMinute,
        animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
        label = "cursorMinute",
    )

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val gutterPx = GutterWidth
        val gridWidth = maxWidth - gutterPx
        val cellPx = gridWidth / safeCols
        val height = cellPx * rows

        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            val gutter = GutterWidth.toPx()
            val cell = (size.width - gutter) / safeCols
            val gap = CellGap.toPx()
            val corner = CornerRadius(CellCorner.toPx(), CellCorner.toPx())

            drawRunCells(
                tasks = tasks,
                renderedMinutes = renderedMinutes,
                state = state,
                cols = safeCols,
                rows = rows,
                cell = cell,
                gap = gap,
                corner = corner,
                gutter = gutter,
                cursorMinute = cursorMinute,
                totalMinutes = totalMinutes,
            )
            drawRunGutter(
                startMinuteOfDay = startMinuteOfDay,
                cols = safeCols,
                rows = rows,
                cell = cell,
                gutter = gutter,
                textMeasurer = textMeasurer,
            )
            drawCursor(
                cursorMinute = cursorMinute,
                totalMinutes = totalMinutes,
                cols = safeCols,
                cell = cell,
                gutter = gutter,
                phase = state.phase,
            )
        }
    }
}

/** Draws every minute cell, coloured by elapsed / current / future. */
private fun DrawScope.drawRunCells(
    tasks: List<GridTask>,
    renderedMinutes: List<Int>,
    state: RunState,
    cols: Int,
    rows: Int,
    cell: Float,
    gap: Float,
    corner: CornerRadius,
    gutter: Float,
    cursorMinute: Float,
    totalMinutes: Int,
) {
    val elapsedMinutes = when (state.phase) {
        Phase.BeforeStart -> 0f
        Phase.Finished -> totalMinutes.toFloat()
        Phase.Running -> cursorMinute
    }

    var minute = 0
    tasks.forEachIndexed { index, task ->
        val taskColor = Color(task.color)
        val washedColor = washed(taskColor, 0.7f)
        // The buffer share is drawn in the task's own colour, so the block the
        // user sees is exactly as long as the countdown says it is.
        repeat(renderedMinutes.getOrElse(index) { task.minutes }) {
            val row = minute / cols
            val col = minute % cols
            if (row < rows) {
                val left = gutter + col * cell + gap / 2f
                val top = row * cell + gap / 2f
                val side = cell - gap
                val size = Size(side, side)

                // Fully elapsed minutes are full colour; everything from the
                // current minute onward starts washed and is topped up below.
                val fill = if (minute + 1 <= floor(elapsedMinutes)) taskColor else washedColor
                drawRoundRect(
                    color = fill,
                    topLeft = Offset(left, top),
                    size = size,
                    cornerRadius = corner,
                )

                // Current minute: fill the elapsed fraction in full colour.
                if (minute < elapsedMinutes && minute + 1 > elapsedMinutes) {
                    val fraction = (elapsedMinutes - minute).coerceIn(0f, 1f)
                    drawRoundRect(
                        color = taskColor,
                        topLeft = Offset(left, top),
                        size = Size(side * fraction, side),
                        cornerRadius = corner,
                    )
                }
            }
            minute++
        }
    }
}

/** Draws the `H:MM` clock label at the start of each row. */
private fun DrawScope.drawRunGutter(
    startMinuteOfDay: Int,
    cols: Int,
    rows: Int,
    cell: Float,
    gutter: Float,
    textMeasurer: TextMeasurer,
) {
    val style = TextStyle(
        color = TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
    )
    for (row in 0 until rows) {
        val label = Schedule.clockLabel(startMinuteOfDay, row, cols)
        val layout = textMeasurer.measure(label, style)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                x = (gutter - layout.size.width) / 2f,
                y = row * cell + (cell - layout.size.height) / 2f,
            ),
        )
    }
}

/** Draws the thin vertical cursor line at [cursorMinute]. */
private fun DrawScope.drawCursor(
    cursorMinute: Float,
    totalMinutes: Int,
    cols: Int,
    cell: Float,
    gutter: Float,
    phase: Phase,
) {
    if (phase == Phase.Finished || totalMinutes <= 0) return
    val clamped = cursorMinute.coerceIn(0f, totalMinutes.toFloat())
    val row = (clamped / cols).toInt()
    val col = clamped - row * cols
    val x = gutter + col * cell
    val width = CursorWidth.toPx()
    drawRect(
        color = SelectionOutline,
        topLeft = Offset(x - width / 2f, row * cell),
        size = Size(width, cell),
    )
}

