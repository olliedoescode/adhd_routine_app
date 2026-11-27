package com.adhd.routineplanner.ui.plan

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.adhd.routineplanner.domain.Schedule
import com.adhd.routineplanner.ui.common.ReorderState
import com.adhd.routineplanner.ui.common.reorderableRow
import com.adhd.routineplanner.ui.common.rememberReorderState
import com.adhd.routineplanner.ui.theme.AdhdRoutineAppTheme
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.CountdownRed
import com.adhd.routineplanner.ui.theme.SelectionOutline
import com.adhd.routineplanner.ui.theme.Surface
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary
import com.adhd.routineplanner.ui.theme.washed

/**
 * The vertical timeline editor (proposal §4.2) — the default way to read and
 * reorder a routine.
 *
 * Each task is a coloured block on a continuous vertical rail. The block's
 * height is proportional to its rendered length, so a 20-minute task is visibly
 * taller than a 5-minute one and the routine reads like a day planner rather
 * than a spreadsheet. Clock times sit in a gutter on the left at each task
 * boundary, so the user can see exactly when each step starts.
 *
 * When the routine has spare time, a dashed "buffer" segment is drawn at the
 * end so the user can see the window is not full. When the tasks overrun the
 * window, a red "over" segment is drawn instead.
 *
 * **Reordering** is a live drag: the held block follows the finger and the
 * other blocks slide out of the way, then the move is committed once on
 * release. See [ReorderState] for why this replaced the old swap-on-threshold
 * approach.
 *
 * **Selection** is a plain tap. Tapping a block toggles it in [selectedIds];
 * when anything is selected the caller shows a bulk-delete bar. Tapping a block
 * while nothing is selected opens the editor, so the two gestures never fight.
 *
 * @param tasks the routine's tasks, in order.
 * @param routineStartMinuteOfDay the routine's start, used to show clock times.
 * @param onMove called with the dragged row's index and its new index.
 * @param onTap called with the tapped task, to open the editor.
 * @param selectedIds the ids currently highlighted for bulk actions.
 * @param onToggleSelect called with a task id when a block is tapped.
 * @param capacityMinutes the routine window length; used to draw the buffer or
 *   overrun tail. Pass 0 to hide the tail.
 * @param zoom multiplier on the minutes-to-height scale. 1 is the default size;
 *   larger values make long tasks read taller, smaller values fit more of the
 *   routine on screen. Driven by the same Zoom in/out controls as the run grid.
 */
@Composable
fun TaskListEditor(
    tasks: List<GridTask>,
    routineStartMinuteOfDay: Int,
    onMove: (from: Int, to: Int) -> Unit,
    onTap: (GridTask) -> Unit,
    modifier: Modifier = Modifier,
    capacityMinutes: Int = 0,
    selectedIds: Set<Long> = emptySet(),
    onToggleSelect: (Long) -> Unit = {},
    zoom: Float = 1f,
) {
    if (tasks.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No tasks yet",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
        return
    }

    // Absolute start of each task, so the rail can show a clock time rather than
    // a bare offset. The routine may cross midnight, so wrap into the day.
    val starts = remember(tasks, routineStartMinuteOfDay) {
        val offsets = Schedule.minimumStartMinutes(tasks.map { it.toPlacedTask() })
        IntArray(tasks.size) { index ->
            ((routineStartMinuteOfDay + offsets[index]) % (24 * 60) + 24 * 60) % (24 * 60)
        }
    }

    val claimed = tasks.sumOf { it.minutes }
    val spare = (capacityMinutes - claimed).coerceAtLeast(0)
    val overrun = (claimed - capacityMinutes).coerceAtLeast(0)

    // Pixels-per-minute so block heights are proportional but never so small a
    // short task becomes untappable. At the default zoom, 2.2dp/min gives a
    // 5-minute task an 11dp block, lifted to the 44dp minimum below. `zoom`
    // scales that so the overflow-menu Zoom in/out controls resize this view
    // too, not just the grid.
    val pxPerMinute = 2.2f * zoom.coerceIn(0.5f, 2f)
    val minBlockHeight = 44.dp
    val density = LocalDensity.current

    // Each row's real height, so the drag engine measures travel in rows rather
    // than in a fixed unit. Tall blocks therefore need a proportionally longer
    // drag before they swap.
    fun heightFor(index: Int): Float {
        val minutes = tasks.getOrNull(index)?.minutes ?: 0
        val dp = (minutes * pxPerMinute).dp.coerceAtLeast(minBlockHeight)
        return with(density) { dp.toPx() }
    }

    val reorder = rememberReorderState(
        itemCount = { tasks.size },
        rowHeightPx = { index -> heightFor(index) },
        onMove = onMove,
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(tasks, key = { _, task -> task.id }) { index, task ->
            val isDragging = reorder.draggingIndex == index
            val isSelected = task.id in selectedIds
            val blockHeight = (task.minutes * pxPerMinute).dp
                .coerceAtLeast(minBlockHeight)

            // The dragged row follows the finger; the others slide by the
            // dragged row's height to open a gap at the target slot.
            val shiftPx = if (isDragging) reorder.dragOffset else reorder.shiftFor(index)
            val animatedShift by animateDpAsState(
                targetValue = with(density) { shiftPx.toDp() },
                animationSpec = tween(durationMillis = if (isDragging) 0 else 140),
                label = "rowShift",
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(blockHeight)
                    .offset(y = animatedShift)
                    .zIndex(if (isDragging) 1f else 0f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when {
                            isDragging -> Surface
                            isSelected -> washed(Color(task.color), factor = 0.35f)
                            else -> Background
                        },
                    )
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                width = 2.dp,
                                color = SelectionOutline,
                                shape = RoundedCornerShape(10.dp),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .reorderableRow(
                        state = reorder,
                        index = index,
                        onTap = {
                            if (selectedIds.isEmpty()) onTap(task) else onToggleSelect(task.id)
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // --- Clock gutter -----------------------------------------
                Column(
                    modifier = Modifier.width(52.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        text = Schedule.clockLabel(starts[index], 0, 1),
                        // Clock times are reference, not emphasis. The colour
                        // step (secondary) plus the light label weight keeps the
                        // task blocks the loud thing in this column.
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                    )
                }
                Spacer(Modifier.width(10.dp))

                // --- The task block ---------------------------------------
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp))
                        // A wash of the task colour so the block reads as a
                        // solid span of time, not a thin bar.
                        .background(washed(Color(task.color), factor = 0.55f))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Solid colour spine on the left edge of the block.
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(task.color)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = task.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextPrimary,
                            )
                            if (task.alarmEnabled) {
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Filled.NotificationsActive,
                                    contentDescription = "Alarm on",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                        Text(
                            text = "${task.minutes} min",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Selected",
                            tint = SelectionOutline,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Icon(
                        imageVector = Icons.Filled.DragHandle,
                        contentDescription = "Reorder ${task.name}",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // --- Tail: buffer or overrun --------------------------------------
        if (capacityMinutes > 0 && spare > 0) {
            item(key = "buffer-tail") {
                TimelineTail(
                    label = "$spare min spare",
                    detail = "Spare time is shared across tasks",
                    color = TextSecondary,
                    dashed = true,
                )
            }
        } else if (capacityMinutes > 0 && overrun > 0) {
            item(key = "overrun-tail") {
                TimelineTail(
                    label = "$overrun min over",
                    detail = "Shorten a task or extend the window",
                    color = CountdownRed,
                    dashed = false,
                )
            }
        }
    }
}

/**
 * The tail segment under the last task: a dashed "spare" strip or a solid red
 * "over" strip, so the timeline shows whether the window is full at a glance.
 */
@Composable
private fun TimelineTail(
    label: String,
    detail: String,
    color: Color,
    dashed: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Surface.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A short marker in the gutter column, aligned with the task times.
        Box(
            modifier = Modifier
                .width(52.dp)
                .height(2.dp)
                .background(color.copy(alpha = if (dashed) 0.4f else 0.8f)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

@Preview(name = "Task timeline editor", showBackground = true, backgroundColor = 0xFF0A1116)
@Composable
private fun TaskListEditorPreview() {
    AdhdRoutineAppTheme {
        TaskListEditor(
            tasks = listOf(
                GridTask(id = 1, minutes = 10, position = 0, name = "Shower", color = 0xFF4F8EF7.toInt()),
                GridTask(
                    id = 2,
                    minutes = 5,
                    position = 1,
                    name = "Get dressed",
                    color = 0xFF34C7A1.toInt(),
                    alarmEnabled = true,
                ),
                GridTask(id = 3, minutes = 15, position = 2, name = "Breakfast", color = 0xFFF2994A.toInt()),
            ),
            routineStartMinuteOfDay = 7 * 60,
            onMove = { _, _ -> },
            onTap = {},
            capacityMinutes = 60,
            selectedIds = setOf(2L),
        )
    }
}
