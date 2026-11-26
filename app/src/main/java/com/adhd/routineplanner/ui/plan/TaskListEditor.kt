package com.adhd.routineplanner.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adhd.routineplanner.domain.Schedule
import com.adhd.routineplanner.ui.theme.AdhdRoutineAppTheme
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.Surface
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary

/**
 * A list view of a routine's tasks, as an alternative to the grid (spec §15.3).
 *
 * The grid is the right tool for seeing how a routine fills its window, but it
 * is a poor way to reorder or read a long routine. This list shows each task's
 * core information — its colour, name, start time, duration and whether it
 * alarms — and lets the user reorder by long-pressing a row and dragging it.
 *
 * Reordering is expressed as a single [onMove] callback with the from/to
 * indices, so the caller can reuse the same move logic the grid uses and the
 * change goes through the usual undo snapshot.
 *
 * @param tasks the routine's tasks, in order.
 * @param routineStartMinuteOfDay the routine's start, used to show each task's
 *   clock time.
 * @param onMove called with the dragged row's index and its new index.
 * @param onTap called with the tapped task, to open the editor.
 */
@Composable
fun TaskListEditor(
    tasks: List<GridTask>,
    routineStartMinuteOfDay: Int,
    onMove: (from: Int, to: Int) -> Unit,
    onTap: (GridTask) -> Unit,
    modifier: Modifier = Modifier,
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

    // Absolute start of each task, so the row can show a clock time rather than
    // a bare offset. The routine may cross midnight, so wrap into the day.
    val starts = remember(tasks, routineStartMinuteOfDay) {
        val offsets = Schedule.minimumStartMinutes(tasks.map { it.toPlacedTask() })
        IntArray(tasks.size) { index ->
            ((routineStartMinuteOfDay + offsets[index]) % (24 * 60) + 24 * 60) % (24 * 60)
        }
    }

    val rowHeight = 64.dp
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
    val haptics = LocalHapticFeedback.current
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(tasks, key = { _, task -> task.id }) { index, task ->
            val isDragging = index == draggingIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isDragging) Surface else Background)
                    .pointerInput(tasks.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingIndex = index
                                dragOffset = 0f
                                haptics.performHapticFeedback(
                                    HapticFeedbackType.LongPress,
                                )
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                // Swap once the finger passes half a row, then
                                // reset so the next swap needs the same travel.
                                if (dragOffset > rowHeightPx / 2f && index < tasks.lastIndex) {
                                    onMove(index, index + 1)
                                    dragOffset -= rowHeightPx
                                } else if (dragOffset < -rowHeightPx / 2f && index > 0) {
                                    onMove(index, index - 1)
                                    dragOffset += rowHeightPx
                                }
                            },
                            onDragEnd = {
                                draggingIndex = -1
                                dragOffset = 0f
                            },
                            onDragCancel = {
                                draggingIndex = -1
                                dragOffset = 0f
                            },
                        )
                    }
                    .clickable { onTap(task) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Colour bar, so the list reads the same as the grid.
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(36.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(task.color)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = task.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium,
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
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${Schedule.clockLabel(starts[index], 0, 1)} · ${task.minutes} min",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
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
}

@Preview(name = "Task list editor", showBackground = true, backgroundColor = 0xFF0E0F12)
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
        )
    }
}
