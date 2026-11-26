package com.adhd.routineplanner.ui.plan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.adhd.routineplanner.domain.Grid
import com.adhd.routineplanner.domain.Routine
import com.adhd.routineplanner.domain.Schedule
import com.adhd.routineplanner.ui.theme.AdhdRoutineAppTheme
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.GridLine
import com.adhd.routineplanner.ui.theme.SelectionOutline
import com.adhd.routineplanner.ui.theme.Surface
import com.adhd.routineplanner.ui.theme.TextSecondary
import com.adhd.routineplanner.viewmodel.DRAG_GHOST_ID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Width of the left clock-label column (spec §14.1). */
val GutterWidth: Dp = 44.dp

/** Cell border thickness (spec §14.4). */
private val CellBorderWidth: Dp = 0.5.dp

/** Corner radius of a task block's true start and end (spec §14.4). */
private val BlockCornerRadius: Dp = 4.dp

/** Extra faint rows drawn after the last task so a drop at the end is possible. */
private const val TAIL_ROWS: Int = 2

/** Fill for cells that hold no task. */
private val EmptyCellFill = Color(0xFF121418)

/**
 * A drag preview: the block being dragged, drawn at its prospective position
 * (spec §14.2 layer 8).
 *
 * @param task the task being dragged (or the template's preview task).
 * @param insertionMinute minute index where the block would be inserted.
 * @param originMinute start minute of the dragged task in the committed list,
 *   or `null` for a tray drag (nothing to leave a placeholder behind).
 * @param overTray true while the finger is over the tray, meaning "remove";
 *   the preview is then drawn as a faint dashed outline only.
 */
data class DragPreview(
    val task: GridTask,
    val insertionMinute: Int,
    val originMinute: Int? = null,
    val overTray: Boolean = false,
)

/**
 * The routine grid: one Canvas, nine draw layers, in the exact order from
 * spec §14.2.
 *
 * The composable is sized to `rows * cellPx` so it can live inside a vertical
 * scroll container (spec §14.6). Only rows intersecting the viewport are drawn.
 *
 * @param tasks ordered task list; start minutes are derived, never stored.
 * @param cols one of [Grid.ZOOM_STEPS]; 1 cell = 1 minute.
 * @param cellPx square cell edge in pixels.
 * @param routineStartMinuteOfDay minute-of-day of the routine's first minute.
 * @param capacityMinutes the routine window length, used to distribute the
 *   buffer across tasks so the grid is a complete partition.
 * @param selectedId id of the selected task, or `null`.
 * @param endByMinute optional "end by" minute; cells past it are tinted red.
 * @param preview optional drag preview (Phase 6).
 * @param visibleTopPx first visible pixel offset within the canvas.
 * @param visibleBottomPx last visible pixel offset within the canvas.
 * @param runOverlay optional run-mode layer (Phase 8); drawn last.
 */
@Composable
fun GridCanvas(
    tasks: List<GridTask>,
    cols: Int,
    cellPx: Float,
    routineStartMinuteOfDay: Int,
    capacityMinutes: Int,
    modifier: Modifier = Modifier,
    selectedId: Long? = null,
    endByMinute: Int? = null,
    preview: DragPreview? = null,
    visibleTopPx: Float = 0f,
    visibleBottomPx: Float = Float.MAX_VALUE,
    useBuffer: Boolean = true,
    runOverlay: (DrawScope) -> Unit = {},
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val placed = remember(tasks) { tasks.map { it.toPlacedTask() } }
    // Only the window length and the buffer flag matter for the layout maths,
    // so a throwaway routine carries them into the domain helpers.
    val window = remember(capacityMinutes, useBuffer) {
        Routine(
            startMinuteOfDay = 0,
            endMinuteOfDay = capacityMinutes,
            useBuffer = useBuffer,
        )
    }
    // The grid always shows the whole routine window, even when it holds no
    // tasks: an empty 1-hour routine should still draw a 1-hour grid, not just
    // the tail rows. When the tasks overrun the window we grow to fit them, so
    // the overrun stays visible. With buffers off the rendered total is just the
    // minimums, so `maxOf` keeps the full window visible and the spare time
    // shows as empty cells.
    val totalMinutes = remember(placed, window, capacityMinutes) {
        maxOf(capacityMinutes, Schedule.renderedMinutes(window, placed))
    }
    val starts = remember(placed, window) {
        Schedule.startMinutes(window, placed)
    }
    val contentRows = remember(totalMinutes, cols) { Grid.rowsFor(totalMinutes, cols) }
    val rows = contentRows + TAIL_ROWS

    val borderPx = with(density) { CellBorderWidth.toPx() }
    val cornerPx = with(density) { BlockCornerRadius.toPx() }
    val gutterPx = with(density) { GutterWidth.toPx() }

    val gutterLabelStyle = remember {
        TextStyle(color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
    val blockLabelStyle = remember {
        TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(with(density) { (rows * cellPx).toDp() }),
    ) {
        val gridWidth = size.width - gutterPx
        if (gridWidth <= 0f || cellPx <= 0f || cols <= 0) return@Canvas

        // Only draw rows that intersect the viewport (spec §14.6).
        val firstRow = max(0, (visibleTopPx / cellPx).toInt() - 1)
        val lastRow = min(rows - 1, ceil(visibleBottomPx / cellPx).toInt() + 1)

        // --- Layer 1: background -------------------------------------------
        drawRect(color = Background, size = size)

        // --- Layer 2: dedicated time gutter, separate from the task grid ----
        drawRect(
            color = Surface,
            topLeft = Offset.Zero,
            size = Size(gutterPx, size.height),
        )
        drawLine(
            color = GridLine,
            start = Offset(gutterPx, 0f),
            end = Offset(gutterPx, size.height),
            strokeWidth = 1.dp.toPx(),
        )

        // --- Layer 3: empty-cell fill with thin borders ---------------------
        drawEmptyCells(firstRow, lastRow, cols, cellPx, gutterPx, borderPx)

        // --- Layer 3: task cell fills --------------------------------------
        drawTaskFills(tasks, starts, firstRow, lastRow, cols, cellPx, gutterPx, cornerPx)

        // --- Layer 4: task outlines and names ------------------------------
        drawTaskLabels(
            tasks = tasks,
            starts = starts,
            firstRow = firstRow,
            lastRow = lastRow,
            cols = cols,
            cellPx = cellPx,
            gutterPx = gutterPx,
            textMeasurer = textMeasurer,
            style = blockLabelStyle,
        )

        // --- Layer 5: guide lines every 5 minutes --------------------------
        drawGuideLines(firstRow, lastRow, cols, cellPx, gutterPx)

        // --- Layer 6: overflow tint past endByMinute -----------------------
        if (endByMinute != null && endByMinute < totalMinutes) {
            drawOverflowTint(
                fromMinute = endByMinute,
                toMinute = totalMinutes,
                firstRow = firstRow,
                lastRow = lastRow,
                cols = cols,
                cellPx = cellPx,
                gutterPx = gutterPx,
            )
        }

        // --- Layer 7: selection outline ------------------------------------
        val selectedIndex = tasks.indexOfFirst { it.id == selectedId }
        if (selectedIndex >= 0) {
            drawSelectionOutline(
                start = starts[selectedIndex],
                minutes = tasks[selectedIndex].minutes,
                firstRow = firstRow,
                lastRow = lastRow,
                cols = cols,
                cellPx = cellPx,
                gutterPx = gutterPx,
                cornerPx = cornerPx,
            )
        }

        // --- Layer 8: drag preview and insertion marker --------------------
        if (preview != null) {
            drawDragPreview(preview, cols, cellPx, gutterPx, cornerPx)
        }

        // --- Layer 9: run mode overlay (Phase 8) ---------------------------
        runOverlay(this)

        // --- Gutter clock labels (spec §14.2) ------------------------------
        drawGutterLabels(
            firstRow = firstRow,
            lastRow = lastRow,
            cols = cols,
            cellPx = cellPx,
            routineStartMinuteOfDay = routineStartMinuteOfDay,
            textMeasurer = textMeasurer,
            style = gutterLabelStyle,
        )
    }
}

// ---------------------------------------------------------------------------
// Layer 2 — empty cells
// ---------------------------------------------------------------------------

private fun DrawScope.drawEmptyCells(
    firstRow: Int,
    lastRow: Int,
    cols: Int,
    cellPx: Float,
    gutterPx: Float,
    borderPx: Float,
) {
    for (row in firstRow..lastRow) {
        val top = row * cellPx
        for (col in 0 until cols) {
            val left = gutterPx + col * cellPx
            drawRect(
                color = EmptyCellFill,
                topLeft = Offset(left, top),
                size = Size(cellPx, cellPx),
            )
            drawRect(
                color = GridLine,
                topLeft = Offset(left, top),
                size = Size(cellPx, cellPx),
                style = Stroke(width = borderPx),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Layer 3 — task fills, one rounded rect per row segment (spec §14.4)
// ---------------------------------------------------------------------------

private fun DrawScope.drawTaskFills(
    tasks: List<GridTask>,
    starts: IntArray,
    firstRow: Int,
    lastRow: Int,
    cols: Int,
    cellPx: Float,
    gutterPx: Float,
    cornerPx: Float,
) {
    for (i in tasks.indices) {
        if (tasks[i].id == DRAG_GHOST_ID) continue
        val start = starts[i]
        val end = starts[i + 1]
        val color = Color(tasks[i].color)
        forEachSegment(start, end, cols) { segStart, segEnd, isFirst, isLast ->
            val row = segStart / cols
            if (row < firstRow || row > lastRow) return@forEachSegment
            val colStart = segStart % cols
            val colEnd = (segEnd - 1) % cols
            drawSegment(
                color = color,
                left = gutterPx + colStart * cellPx,
                top = row * cellPx,
                width = (colEnd - colStart + 1) * cellPx,
                height = cellPx,
                cornerPx = cornerPx,
                roundStart = isFirst,
                roundEnd = isLast,
            )
        }
    }
}

/**
 * Draws one row segment of a block. Corners are rounded only at the true start
 * and true end of the task; edges where the block wraps are square
 * (spec §14.4).
 */
private fun DrawScope.drawSegment(
    color: Color,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    cornerPx: Float,
    roundStart: Boolean,
    roundEnd: Boolean,
) {
    when {
        roundStart && roundEnd -> drawRoundRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = CornerRadius(cornerPx, cornerPx),
        )

        !roundStart && !roundEnd -> drawRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size(width, height),
        )

        else -> drawPath(
            path = segmentPath(left, top, width, height, cornerPx, roundStart),
            color = color,
        )
    }
}

/** Path for a segment rounded on exactly one end. */
private fun segmentPath(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    r: Float,
    roundStart: Boolean,
): Path {
    val right = left + width
    val bottom = top + height
    return Path().apply {
        if (roundStart) {
            moveTo(left + r, top)
            lineTo(right, top)
            lineTo(right, bottom)
            lineTo(left + r, bottom)
            arcTo(Rect(left, bottom - 2 * r, left + 2 * r, bottom), 90f, 90f, false)
            lineTo(left, top + r)
            arcTo(Rect(left, top, left + 2 * r, top + 2 * r), 180f, 90f, false)
        } else {
            moveTo(left, top)
            lineTo(right - r, top)
            arcTo(Rect(right - 2 * r, top, right, top + 2 * r), 270f, 90f, false)
            lineTo(right, bottom - r)
            arcTo(Rect(right - 2 * r, bottom - 2 * r, right, bottom), 0f, 90f, false)
            lineTo(left, bottom)
        }
        close()
    }
}

// ---------------------------------------------------------------------------
// Layer 4 — task names
// ---------------------------------------------------------------------------

private fun DrawScope.drawTaskLabels(
    tasks: List<GridTask>,
    starts: IntArray,
    firstRow: Int,
    lastRow: Int,
    cols: Int,
    cellPx: Float,
    gutterPx: Float,
    textMeasurer: TextMeasurer,
    style: TextStyle,
) {
    for (i in tasks.indices) {
        val task = tasks[i]
        if (task.id == DRAG_GHOST_ID) continue
        // 1-2 minute tasks get no label but stay selectable (spec §14.4).
        if (task.minutes <= 2) continue
        val start = starts[i]
        val end = starts[i + 1]
        val labelColor = labelColorFor(Color(task.color))

        // The name goes in the first segment, unless that segment is only the
        // last cell of a row — then use the next row's segment (spec §14.4).
        var labelSegmentStart = start
        val firstColIndex = start % cols
        val firstSegmentCells = min(end, (start / cols + 1) * cols) - start
        if (firstColIndex == cols - 1 && firstSegmentCells == 1 && end > start + 1) {
            labelSegmentStart = start + 1
        }
        val labelRow = labelSegmentStart / cols
        if (labelRow < firstRow || labelRow > lastRow) continue

        val segEnd = min(end, (labelRow + 1) * cols)
        val colStart = labelSegmentStart % cols
        val colEnd = (segEnd - 1) % cols
        val left = gutterPx + colStart * cellPx
        val top = labelRow * cellPx
        val width = (colEnd - colStart + 1) * cellPx

        val padding = cellPx * 0.12f
        val available = width - padding * 2
        if (available <= 0f) continue

        val layout = textMeasurer.measure(
            text = task.name,
            style = style.copy(color = labelColor),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = available.toInt().coerceAtLeast(1)),
        )
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(left + padding, top + (cellPx - layout.size.height) / 2f),
        )
    }
}

// ---------------------------------------------------------------------------
// Layer 5 — guide lines every 5 minutes
// ---------------------------------------------------------------------------

private fun DrawScope.drawGuideLines(
    firstRow: Int,
    lastRow: Int,
    cols: Int,
    cellPx: Float,
    gutterPx: Float,
) {
    val guide = GridLine.copy(alpha = 0.55f)
    for (row in firstRow..lastRow) {
        val top = row * cellPx
        for (col in 0 until cols) {
            if ((row * cols + col) % 5 != 0) continue
            val left = gutterPx + col * cellPx
            drawLine(
                color = guide,
                start = Offset(left, top),
                end = Offset(left, top + cellPx),
                strokeWidth = 1f,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Layer 6 — overflow tint
// ---------------------------------------------------------------------------

private fun DrawScope.drawOverflowTint(
    fromMinute: Int,
    toMinute: Int,
    firstRow: Int,
    lastRow: Int,
    cols: Int,
    cellPx: Float,
    gutterPx: Float,
) {
    val tint = Color(0xFFFF3B30).copy(alpha = 0.22f)
    for (minute in fromMinute until toMinute) {
        val row = minute / cols
        if (row < firstRow || row > lastRow) continue
        val col = minute % cols
        drawRect(
            color = tint,
            topLeft = Offset(gutterPx + col * cellPx, row * cellPx),
            size = Size(cellPx, cellPx),
        )
    }
}

// ---------------------------------------------------------------------------
// Layer 7 — selection outline
// ---------------------------------------------------------------------------

private fun DrawScope.drawSelectionOutline(
    start: Int,
    minutes: Int,
    firstRow: Int,
    lastRow: Int,
    cols: Int,
    cellPx: Float,
    gutterPx: Float,
    cornerPx: Float,
) {
    val stroke = 2.dp.toPx()
    val inset = stroke / 2f
    forEachSegment(start, start + minutes, cols) { segStart, segEnd, isFirst, isLast ->
        val row = segStart / cols
        if (row < firstRow || row > lastRow) return@forEachSegment
        val colStart = segStart % cols
        val colEnd = (segEnd - 1) % cols
        val left = gutterPx + colStart * cellPx
        val top = row * cellPx
        val width = (colEnd - colStart + 1) * cellPx
        val radius = if (isFirst || isLast) cornerPx else 0f
        drawRoundRect(
            color = SelectionOutline,
            topLeft = Offset(left + inset, top + inset),
            size = Size(width - stroke, cellPx - stroke),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = stroke),
        )
    }
}

// ---------------------------------------------------------------------------
// Layer 8 — drag preview and insertion marker
// ---------------------------------------------------------------------------

private fun DrawScope.drawDragPreview(
    preview: DragPreview,
    cols: Int,
    cellPx: Float,
    gutterPx: Float,
    cornerPx: Float,
) {
    // Faint dashed placeholder at the dragged task's original position
    // (spec §15.5). Tray drags have no origin, so nothing is left behind.
    val origin = preview.originMinute
    if (origin != null) {
        drawDashedPlaceholder(
            start = origin,
            minutes = preview.task.minutes,
            cols = cols,
            cellPx = cellPx,
            gutterPx = gutterPx,
            cornerPx = cornerPx,
        )
    }

    // Over the tray the drop means "remove": show only the dashed outline of
    // the dragged block at the hovered minute, no solid fill.
    val start = preview.insertionMinute
    val end = start + preview.task.minutes
    if (preview.overTray) {
        drawDashedPlaceholder(start, preview.task.minutes, cols, cellPx, gutterPx, cornerPx)
        return
    }

    forEachSegment(start, end, cols) { segStart, segEnd, isFirst, isLast ->
        val row = segStart / cols
        val colStart = segStart % cols
        val colEnd = (segEnd - 1) % cols
        drawSegment(
            color = Color(preview.task.color).copy(alpha = 0.55f),
            left = gutterPx + colStart * cellPx,
            top = row * cellPx,
            width = (colEnd - colStart + 1) * cellPx,
            height = cellPx,
            cornerPx = cornerPx,
            roundStart = isFirst,
            roundEnd = isLast,
        )
    }
    // Insertion marker: a bright vertical line at the drop boundary.
    val x = gutterPx + (start % cols) * cellPx
    val y = (start / cols) * cellPx
    drawLine(
        color = SelectionOutline,
        start = Offset(x, y),
        end = Offset(x, y + cellPx),
        strokeWidth = 3.dp.toPx(),
    )
}

/**
 * A faint dashed outline covering `[start, start + minutes)`, one per row
 * segment, used for the drag origin placeholder and the "remove" preview
 * (spec §15.5).
 */
private fun DrawScope.drawDashedPlaceholder(
    start: Int,
    minutes: Int,
    cols: Int,
    cellPx: Float,
    gutterPx: Float,
    cornerPx: Float,
) {
    val dashColor = SelectionOutline.copy(alpha = 0.35f)
    val stroke = 1.5.dp.toPx()
    val dash = 4.dp.toPx()
    val gap = 3.dp.toPx()
    forEachSegment(start, start + minutes, cols) { segStart, segEnd, isFirst, isLast ->
        val row = segStart / cols
        val colStart = segStart % cols
        val colEnd = (segEnd - 1) % cols
        val left = gutterPx + colStart * cellPx
        val top = row * cellPx
        val right = gutterPx + (colEnd + 1) * cellPx
        val bottom = top + cellPx
        val radius = if (isFirst || isLast) cornerPx else 0f
        drawDashedRect(left, top, right, bottom, radius, dashColor, stroke, dash, gap)
    }
}

/** Draws a dashed rounded rectangle outline. */
private fun DrawScope.drawDashedRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    radius: Float,
    color: Color,
    stroke: Float,
    dash: Float,
    gap: Float,
) {
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                radiusX = radius,
                radiusY = radius,
            ),
        )
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap)),
        ),
    )
}

// ---------------------------------------------------------------------------
// Gutter clock labels
// ---------------------------------------------------------------------------

private fun DrawScope.drawGutterLabels(
    firstRow: Int,
    lastRow: Int,
    cols: Int,
    cellPx: Float,
    routineStartMinuteOfDay: Int,
    textMeasurer: TextMeasurer,
    style: TextStyle,
) {
    for (row in firstRow..lastRow) {
        val label = Schedule.clockLabel(routineStartMinuteOfDay, row, cols)
        val layout = textMeasurer.measure(text = label, style = style, maxLines = 1)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(4.dp.toPx(), row * cellPx + (cellPx - layout.size.height) / 2f),
        )
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Splits `[start, end)` into per-row segments, calling [block] with the
 * segment's minute range and whether it is the task's true first/last segment.
 */
private inline fun forEachSegment(
    start: Int,
    end: Int,
    cols: Int,
    block: (segStart: Int, segEnd: Int, isFirst: Boolean, isLast: Boolean) -> Unit,
) {
    if (end <= start || cols <= 0) return
    var segStart = start
    while (segStart < end) {
        val rowEnd = (segStart / cols + 1) * cols
        val segEnd = min(end, rowEnd)
        block(segStart, segEnd, segStart == start, segEnd == end)
        segStart = segEnd
    }
}

/**
 * White or near-black label, chosen from the block colour's luminance so custom
 * colours stay readable (spec §14.4).
 */
private fun labelColorFor(background: Color): Color =
    if (background.luminance() > 0.55f) Color(0xFF101216) else Color(0xFFF7F8FA)

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

private fun previewTasks(): List<GridTask> = listOf(
    GridTask(id = 1, minutes = 10, position = 0, name = "Shower", color = 0xFF4F8EF7.toInt()),
    GridTask(id = 2, minutes = 15, position = 1, name = "Breakfast", color = 0xFFF2994A.toInt()),
    GridTask(id = 3, minutes = 5, position = 2, name = "Get dressed", color = 0xFF34C7A1.toInt()),
    GridTask(id = 4, minutes = 10, position = 3, name = "Meditate", color = 0xFF9B6BF2.toInt()),
    GridTask(id = 5, minutes = 5, position = 4, name = "Stretch", color = 0xFFF2C94C.toInt()),
    GridTask(id = 6, minutes = 15, position = 5, name = "Free time", color = 0xFF8E9AAF.toInt()),
)

@Preview(name = "Grid 10 cols", showBackground = true, backgroundColor = 0xFF0E0F12, widthDp = 400, heightDp = 320)
@Composable
fun GridCanvasPreview10() {
    AdhdRoutineAppTheme {
        GridCanvas(
            tasks = previewTasks(),
            cols = 10,
            cellPx = 30.dp.value * 3f,
            routineStartMinuteOfDay = 7 * 60,
            capacityMinutes = 60,
            modifier = Modifier
                .background(Background)
                .padding(8.dp),
        )
    }
}

@Preview(name = "Grid 5 cols", showBackground = true, backgroundColor = 0xFF0E0F12, widthDp = 400, heightDp = 420)
@Composable
fun GridCanvasPreview5() {
    AdhdRoutineAppTheme {
        GridCanvas(
            tasks = previewTasks(),
            cols = 5,
            cellPx = 60.dp.value * 3f,
            routineStartMinuteOfDay = 7 * 60,
            capacityMinutes = 60,
            modifier = Modifier
                .background(Background)
                .padding(8.dp),
        )
    }
}

@Preview(name = "Grid with selection", showBackground = true, backgroundColor = 0xFF0E0F12, widthDp = 400, heightDp = 320)
@Composable
fun GridCanvasPreviewSelected() {
    AdhdRoutineAppTheme {
        GridCanvas(
            tasks = previewTasks(),
            cols = 10,
            cellPx = 30.dp.value * 3f,
            routineStartMinuteOfDay = 7 * 60,
            capacityMinutes = 60,
            selectedId = 2,
            modifier = Modifier
                .background(Background)
                .padding(8.dp),
        )
    }
}
