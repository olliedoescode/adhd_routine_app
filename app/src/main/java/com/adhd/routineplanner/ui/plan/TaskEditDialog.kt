package com.adhd.routineplanner.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.adhd.routineplanner.domain.IconKeys
import com.adhd.routineplanner.domain.PlacedTask
import com.adhd.routineplanner.domain.StockTask
import com.adhd.routineplanner.domain.TimeParts
import com.adhd.routineplanner.ui.common.StockTaskChip
import com.adhd.routineplanner.ui.common.toChip
import com.adhd.routineplanner.ui.settings.StockTaskIcons
import com.adhd.routineplanner.ui.theme.AdhdRoutineAppTheme
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.GridLine
import com.adhd.routineplanner.ui.theme.SelectionOutline
import com.adhd.routineplanner.ui.theme.StockTaskColors
import com.adhd.routineplanner.ui.theme.Surface
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/** Height of one row in the time wheels. */
private val WheelRowHeight = 36.dp

/**
 * How many rows are visible above and below the selected one. One row either
 * side keeps the wheel compact enough to fit inside the dialog without
 * overlapping the title or the colour swatches.
 */
private const val WheelVisibleRows = 1

/** Total height of a wheel: the centre row plus [WheelVisibleRows] either side. */
private val WheelHeight = WheelRowHeight * (WheelVisibleRows * 2 + 1)

/**
 * Popup editor for a placed task: rename it and set its duration with a
 * Samsung clock-app style scrollable hours/minutes/seconds picker.
 *
 * The name is a plain text field; tapping it focuses it and raises the keyboard.
 * Each wheel is a snapping [LazyColumn] — dragging a column up or down scrolls
 * through its values, and the row resting under the centre highlight is the
 * selected value. The wheels feed a [TimeParts] value that is converted back to
 * whole minutes on confirm.
 *
 * @param task the task being edited (supplies the current name, colour and duration).
 * @param onRename called with the new name when the user confirms a change.
 * @param onColorChange called with the new ARGB colour when the user picks one.
 * @param onDurationChange called with the new duration in whole minutes.
 * @param onDelete called when the delete button is tapped.
 * @param onDismiss called when the dialog is dismissed without confirming.
 * @param maxMinutes the longest duration this task may be given without
 *   over-subscribing the routine, given the other tasks' minimums. The wheels
 *   are clamped to this, exactly as in [TaskCreateDialog].
 * @param onAlarmChange called with the new alarm flag when the user confirms,
 *   so the alarm state can be seen and changed here just as when creating.
 */
@Composable
fun TaskEditDialog(
    task: GridTask,
    onRename: (String) -> Unit,
    onColorChange: (Int) -> Unit,
    onDurationChange: (Int) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    maxMinutes: Int = Int.MAX_VALUE,
    onAlarmChange: (Boolean) -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    var name by remember(task.id) { mutableStateOf(task.name) }
    var color by remember(task.id) { mutableStateOf(task.color) }
    var alarmEnabled by remember(task.id) { mutableStateOf(task.alarmEnabled) }
    var parts by remember(task.id) { mutableStateOf(TimeParts.fromMinutes(task.minutes)) }
    val nameFocus = remember { FocusRequester() }

    // Keep the chosen duration inside what the routine can still hold. The
    // wheels can only offer values up to `maxMinutes`, so this only bites when
    // the user lowers the limit by editing another task first.
    val clampedMinutes = parts.totalMinutes.coerceIn(1, maxMinutes.coerceAtLeast(1))

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Edit task",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete task",
                        tint = TextSecondary,
                    )
                }
            }
        },
        text = {
            // The dialog's text slot has a bounded height; make the content
            // scrollable so a tall wheel can never overflow and draw over the
            // title or the colour swatches.
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                // --- Editable name ---------------------------------------
                Text(
                    text = "Name",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Background, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .clickable { nameFocus.requestFocus() },
                ) {
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        cursorBrush = SolidColor(TextPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(nameFocus),
                    )
                }

                Spacer(Modifier.height(16.dp))

                // --- Colour swatches -------------------------------------
                Text(
                    text = "Colour",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                // Two rows of six so the swatches stay finger-sized.
                StockTaskColors.chunked(6).forEach { rowColors ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowColors.forEach { stock ->
                            val argb = stock.toArgb()
                            val isSelected = argb == color
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .background(stock, CircleShape)
                                    .border(
                                        width = if (isSelected) 2.dp else 0.dp,
                                        color = if (isSelected) SelectionOutline else Color.Transparent,
                                        shape = CircleShape,
                                    )
                                    .clickable {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        color = argb
                                    },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(8.dp))

                // --- Duration wheels -------------------------------------
                Text(
                    text = "Duration",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                DurationWheels(
                    parts = parts,
                    maxMinutes = maxMinutes,
                    onPartsChange = { parts = it },
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (clampedMinutes >= maxMinutes) {
                        "Routine is full at $maxMinutes min"
                    } else {
                        "Up to $maxMinutes min available"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )

                Spacer(Modifier.height(16.dp))

                // --- Alarm -----------------------------------------------
                AlarmToggle(
                    enabled = alarmEnabled,
                    onToggle = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        alarmEnabled = !alarmEnabled
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onRename(name)
                    onColorChange(color)
                    onDurationChange(clampedMinutes)
                    onAlarmChange(alarmEnabled)
                    onDismiss()
                },
            ) {
                Text("Save", color = TextPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
    )
}

/**
 * Popup editor for a brand-new task: name, colour, duration and whether it
 * raises an alarm.
 *
 * This is the same shape as [TaskEditDialog] minus the delete and reorder
 * actions, because a task that does not exist yet cannot be deleted or moved.
 * It reuses the same clock-face [TimeWheel]s, so creating and editing a task
 * feel identical.
 *
 * @param maxMinutes the longest duration the routine can still absorb, given
 *   the other tasks' minimums. The wheels are clamped to this so a new task
 *   cannot over-subscribe the routine.
 * @param stockTasks the user's reusable tasks, offered as one-tap presets that
 *   fill in the name, colour, duration, alarm and icon. Empty hides the row.
 * @param onCreate called with the name, duration in whole minutes, ARGB colour,
 *   alarm flag and stock-task icon key when the user confirms.
 * @param onDismiss called when the dialog is dismissed without confirming.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskCreateDialog(
    maxMinutes: Int,
    onCreate: (name: String, minutes: Int, color: Int, alarmEnabled: Boolean, icon: String) -> Unit,
    onDismiss: () -> Unit,
    stockTasks: List<StockTask> = emptyList(),
) {
    val haptics = LocalHapticFeedback.current
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(StockTaskColors.first().toArgb()) }
    var alarmEnabled by remember { mutableStateOf(false) }
    // The icon key the new task will carry. Starts as the generic task icon and
    // is replaced when the user taps a stock task, so a task created from
    // "Shower" keeps the shower icon all the way through the run.
    var icon by remember { mutableStateOf(IconKeys.TASK) }
    var parts by remember { mutableStateOf(TimeParts.fromMinutes(DEFAULT_NEW_TASK_MINUTES)) }
    val nameFocus = remember { FocusRequester() }

    // Keep the chosen duration inside what the routine can still hold. The
    // wheels can only offer values up to `maxMinutes`, so this only bites when
    // the user lowers the limit by editing another task first.
    val clampedMinutes = parts.totalMinutes.coerceIn(1, maxMinutes.coerceAtLeast(1))

    // Full-screen, on the app background, exactly like the creation flow's
    // task dialog. The stock chips are a formatted block at the top; the
    // hand-typed form sits underneath, so "pick a preset" and "make your own"
    // read as one continuous sheet instead of a cramped alert.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Background)
                .padding(horizontal = 20.dp),
        ) {
            // --- Header: title + close --------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "New task",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = TextPrimary,
                    )
                }
            }

            // --- Scrollable body --------------------------------------
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                // --- Stock tasks -----------------------------------------
                // One tap fills in everything below, so a routine made of the
                // usual steps takes seconds rather than a dialog each. A picked
                // chip stays highlighted so the user can see what seeded the form.
                if (stockTasks.isNotEmpty()) {
                    Text(
                        text = "Your stock tasks",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Several built-ins can arrive without a database id
                        // (id = 0); the name disambiguates them, so it is the
                        // key that matters here.
                        stockTasks.forEach { stock ->
                            val chip = stock.toChip()
                            StockTaskChip(
                                chip = chip,
                                picked = stock.name.trim()
                                    .equals(name.trim(), ignoreCase = true),
                                onClick = {
                                    haptics.performHapticFeedback(
                                        HapticFeedbackType.TextHandleMove,
                                    )
                                    name = stock.name
                                    color = stock.color
                                    alarmEnabled = stock.alarmEnabled
                                    // Keep the stock task's icon so it survives
                                    // into the run screen and notification.
                                    icon = stock.icon
                                    parts = TimeParts.fromMinutes(
                                        stock.minutes
                                            .coerceIn(1, maxMinutes.coerceAtLeast(1)),
                                    )
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "Or make your own",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                // --- Name ------------------------------------------------
                Text(
                    text = "Name",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Surface, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .clickable { nameFocus.requestFocus() },
                ) {
                    if (name.isEmpty()) {
                        Text(
                            text = "What are you doing?",
                            style = TextStyle(color = TextSecondary, fontSize = 16.sp),
                        )
                    }
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        cursorBrush = SolidColor(TextPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(nameFocus),
                    )
                }

                Spacer(Modifier.height(16.dp))

                // --- Colour ----------------------------------------------
                Text(
                    text = "Colour",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                StockTaskColors.chunked(6).forEach { rowColors ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowColors.forEach { stock ->
                            val argb = stock.toArgb()
                            val isSelected = argb == color
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .background(stock, CircleShape)
                                    .border(
                                        width = if (isSelected) 2.dp else 0.dp,
                                        color = if (isSelected) SelectionOutline else Color.Transparent,
                                        shape = CircleShape,
                                    )
                                    .clickable {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        color = argb
                                    },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(8.dp))

                // --- Duration --------------------------------------------
                Text(
                    text = "Duration",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                DurationWheels(
                    parts = parts,
                    maxMinutes = maxMinutes,
                    onPartsChange = { parts = it },
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (clampedMinutes >= maxMinutes) {
                        "Routine is full at $maxMinutes min"
                    } else {
                        "Up to $maxMinutes min available"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )

                Spacer(Modifier.height(16.dp))

                // --- Alarm -----------------------------------------------
                AlarmToggle(
                    enabled = alarmEnabled,
                    onToggle = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        alarmEnabled = !alarmEnabled
                    },
                )

                Spacer(Modifier.height(16.dp))
            }

            // --- Footer: cancel / add ---------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel", color = TextSecondary)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(TextPrimary)
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onCreate(
                                name.trim().ifEmpty { PlacedTask.DEFAULT_NAME },
                                clampedMinutes,
                                color,
                                alarmEnabled,
                                icon,
                            )
                            onDismiss()
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Add task",
                        style = MaterialTheme.typography.titleMedium,
                        color = Background,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** Default duration offered when creating a task. */
private const val DEFAULT_NEW_TASK_MINUTES: Int = 10

/**
 * The hours/minutes wheels shared by the create and edit dialogs.
 *
 * ## Bounding the total, not just each field
 *
 * Each wheel's upper bound is derived from the *other* field so the combination
 * can never exceed [maxMinutes]: with 90 minutes free you cannot pick 1h 59m.
 *
 * ## Why the bounds are decoupled
 *
 * The hours ceiling depends only on the cap, and the minutes ceiling depends
 * only on the chosen hours. Crucially, the hours wheel's offered list does *not*
 * depend on the minutes value — so scrolling the minutes wheel can never change
 * the hours wheel's list and yank it back to a different row. (The old version
 * derived the hours ceiling from the minutes, which is what made "set the
 * minute" disturb the hour segment.) Changing the hours may still clamp the
 * minutes, which is the one direction that is genuinely required.
 *
 * ## No seconds
 *
 * Durations are whole minutes everywhere in the app, so the picker offers only
 * hours and minutes — there is no seconds segment.
 */
@Composable
internal fun DurationWheels(
    parts: TimeParts,
    maxMinutes: Int,
    onPartsChange: (TimeParts) -> Unit,
) {
    val cap = maxMinutes.coerceAtLeast(1)

    // The most the hours wheel may show: the whole hours that fit in the cap.
    // Deliberately independent of the minutes value, so changing minutes never
    // rebuilds this wheel's list.
    val maxHours = (cap / 60).coerceIn(0, TimeParts.MAX_HOURS)
    // The most the minutes wheel may show: whatever is left after the hours,
    // capped at 59. This depends on hours only, so changing hours may clamp the
    // minutes (correct), but changing minutes never touches the hours wheel.
    val maxMinutesWheel = (cap - parts.hours * 60).coerceIn(0, 59)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimeWheel(
            values = TimeParts.HOUR_VALUES,
            selected = parts.hours,
            label = "h",
            // Keep minutes untouched; only the hours field is bounded.
            onSelected = { onPartsChange(parts.copy(hours = it).clamped()) },
            modifier = Modifier.weight(1f),
            maxValue = maxHours,
        )
        WheelSeparator()
        TimeWheel(
            values = TimeParts.SIXTY_VALUES,
            selected = parts.minutes,
            label = "m",
            onSelected = { onPartsChange(parts.copy(minutes = it).clamped()) },
            modifier = Modifier.weight(1f),
            maxValue = maxMinutesWheel,
        )
    }
}

/**
 * The alarm on/off row shared by the create and edit dialogs, so a task's
 * alarm state can be seen and changed in both places.
 */
@Composable
internal fun AlarmToggle(enabled: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (enabled) "🔔" else "🔕",
            fontSize = 18.sp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Alarm at the start of this task",
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The colon-like gap between two wheels. */
@Composable
private fun WheelSeparator() {
    Text(
        text = ":",
        color = TextSecondary,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 2.dp),
    )
}

/**
 * A [NestedScrollConnection] that calls [onRelease] once the user stops
 * scrolling — either by lifting their finger or by a fling finishing.
 *
 * Compose has no "scroll ended" callback, so this watches the nested-scroll
 * stream: a drag ends with a `Fling`/`Dispose` event, and a fling ends with a
 * zero-delta `Fling` event. Both are treated as "settle now".
 */
@Composable
private fun rememberSnapOnRelease(onRelease: () -> Unit): NestedScrollConnection {
    val current by rememberUpdatedState(onRelease)
    return remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // A drag ends with a zero-delta UserInput event; a fling ends
                // with a zero-delta Fling event. Either means "settle now".
                if (available.y == 0f && consumed.y == 0f) {
                    current()
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                current()
                return Velocity.Zero
            }
        }
    }
}

/**
 * A single snapping wheel. The selected value rests under the fixed centre
 * highlight; scrolling the list changes the selection.
 *
 * Geometry: the viewport is exactly [WheelHeight] (`2 * WheelVisibleRows + 1`
 * rows) tall and the list is padded with [WheelVisibleRows] empty rows at each
 * end. `scrollToItem(target + WheelVisibleRows)` therefore lands the target row
 * in the middle of the viewport, which is also the middle of the highlight band,
 * so "scroll to the selection" and "read the centred row" agree exactly.
 *
 * Snapping is done by hand rather than with `rememberSnapFlingBehavior`: the
 * built-in behaviour snaps to the *top* of the viewport, which is one row above
 * the highlight, so every fling would nudge the selection up by one and the
 * "keep aligned to `selected`" effect would fight it forever. Instead a drag
 * settles on the nearest row boundary and a fling animates to the nearest row
 * boundary, both measured from the centre.
 *
 * The selection is reported only once the wheel has come to rest, so a drag
 * cannot feed values back into `selected` mid-gesture and yank the list around.
 */
@Composable
private fun TimeWheel(
    values: List<Int>,
    selected: Int,
    label: String,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    maxValue: Int = Int.MAX_VALUE,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val rowPx = with(density) { WheelRowHeight.roundToPx() }
    // Only offer values the routine can still hold. Filtering the list (rather
    // than clamping after the fact) means the wheel physically cannot be
    // scrolled past the available window — there is simply nothing beyond it.
    val offered = remember(values, maxValue) {
        values.filter { it <= maxValue }.ifEmpty { listOf(values.first()) }
    }
    val selectedIndex = offered.indexOf(selected).coerceAtLeast(0)
    val listState = rememberLazyListState(
        // The first visible item is the top row of the viewport, which is
        // [WheelVisibleRows] rows above the centred one.
        initialFirstVisibleItemIndex = (selectedIndex - WheelVisibleRows).coerceAtLeast(0),
    )
    val scope = rememberCoroutineScope()

    // The value currently under the highlight band. This is tracked live from
    // the scroll position so the bold/centred styling follows the finger
    // immediately, instead of lagging until the wheel settles and `selected`
    // catches up.
    var centredValue by remember { mutableStateOf(selected) }

    /** Scroll offset that puts the row at [listIndex] in the centre. */
    fun centreOffset(listIndex: Int): Int = -WheelVisibleRows * rowPx

    /** The list index currently resting nearest the viewport centre. */
    fun centredIndex(): Int {
        val info = listState.layoutInfo
        val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
        return info.visibleItemsInfo.minByOrNull { item ->
            kotlin.math.abs((item.offset + item.size / 2) - center)
        }?.index ?: listState.firstVisibleItemIndex
    }

    /** The offered value at a given list index (accounting for the top padding). */
    fun valueAt(listIndex: Int): Int =
        offered[(listIndex - WheelVisibleRows).coerceIn(0, offered.lastIndex)]

    // Track the centred value live as the list scrolls, so the highlight never
    // lags behind the finger.
    LaunchedEffect(listState, offered) {
        snapshotFlow { centredIndex() }
            .collect { index ->
                val value = valueAt(index)
                if (value != centredValue) centredValue = value
            }
    }

    /**
     * Snap to the nearest row boundary, measured from the centre, then report
     * the value that landed under the highlight.
     *
     * The stock `rememberSnapFlingBehavior` snaps to the viewport *top*, which
     * is one row above the highlight band, so it would always leave the wheel
     * misaligned by a row. This settles on the row nearest the centre instead,
     * and animates there so a fling still feels like a fling.
     *
     * Reporting here — rather than from a live `snapshotFlow` — is what stops
     * the runaway scroll: the wheel only ever tells the parent about a value
     * once it has stopped moving, so `selected` cannot change mid-gesture and
     * yank the list back.
     */
    fun settle() {
        val target = centredIndex()
        scope.launch {
            listState.animateScrollToItem(
                index = target,
                scrollOffset = centreOffset(target),
            )
            val value = valueAt(target)
            if (value != selected) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSelected(value)
            }
        }
    }

    /**
     * Jump straight to [value] and report it at once.
     *
     * Tapping a row used to rely on the settle coroutine, which could still be
     * animating from a previous drag when the tap landed — so a quick tap after
     * a drag was sometimes swallowed. Reporting synchronously here, and
     * cancelling any in-flight settle first, makes a tap always register.
     */
    fun jumpTo(value: Int) {
        val index = offered.indexOf(value)
        if (index < 0) return
        val listIndex = index + WheelVisibleRows
        scope.launch {
            listState.scrollToItem(index = listIndex, scrollOffset = centreOffset(listIndex))
        }
        centredValue = value
        if (value != selected) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onSelected(value)
        }
    }

    // Keep the wheel aligned to `selected` when it changes from *outside* the
    // wheel (initial value, or a clamp). If the centred row already equals
    // `selected` this does nothing, so it can never fight the user's drag.
    LaunchedEffect(selected, offered) {
        val valueIndex = offered.indexOf(selected)
        if (valueIndex < 0) return@LaunchedEffect
        val listIndex = valueIndex + WheelVisibleRows
        if (centredIndex() != listIndex) {
            listState.scrollToItem(index = listIndex, scrollOffset = centreOffset(listIndex))
        }
        centredValue = selected
    }

    Box(
        modifier = modifier.height(WheelHeight),
        contentAlignment = Alignment.Center,
    ) {
        // Centre highlight band.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(WheelRowHeight)
                .background(GridLine.copy(alpha = 0.35f), RoundedCornerShape(8.dp)),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                // Settle on the nearest row once the finger lifts or a fling
                // ends. `settle` measures from the centre, unlike the stock
                // snap behaviour which measures from the top.
                .nestedScroll(rememberSnapOnRelease(::settle)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(WheelVisibleRows) {
                Spacer(Modifier.height(WheelRowHeight))
            }
            items(offered) { value ->
                // Highlight the row under the band, tracked live, so the bold
                // styling follows the scroll without waiting for a settle.
                val isCentred = value == centredValue
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(WheelRowHeight)
                        .clickable { jumpTo(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = value.toString(),
                        color = if (isCentred) TextPrimary else TextSecondary,
                        fontSize = if (isCentred) 22.sp else 16.sp,
                        fontWeight = if (isCentred) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            items(WheelVisibleRows) {
                Spacer(Modifier.height(WheelRowHeight))
            }
        }
        // Unit label pinned to the right of the centre row.
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 6.dp),
        )
    }
}

@Preview(name = "Task edit dialog", showBackground = true, backgroundColor = 0xFF0A1116)
@Composable
private fun TaskEditDialogPreview() {
    AdhdRoutineAppTheme {
        TaskEditDialog(
            task = GridTask(
                id = 2,
                minutes = 15,
                position = 1,
                name = "Breakfast",
                color = 0xFFF2994A.toInt(),
            ),
            onRename = {},
            onColorChange = {},
            onDurationChange = {},
            onDelete = {},
            onDismiss = {},
        )
    }
}
