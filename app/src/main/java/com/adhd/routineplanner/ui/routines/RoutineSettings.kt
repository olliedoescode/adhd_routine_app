package com.adhd.routineplanner.ui.routines

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhd.routineplanner.domain.DaysOfWeek
import com.adhd.routineplanner.domain.Routine
import com.adhd.routineplanner.domain.RoutineDates
import com.adhd.routineplanner.domain.TimeWindow
import com.adhd.routineplanner.ui.common.AppSwitch
import com.adhd.routineplanner.ui.theme.AdhdRoutineAppTheme
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.Surface
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Routine settings bottom sheet (spec §15.7).
 *
 * Name, start time, end time (both the stock Material 3 [TimePicker]), active
 * days (seven toggles) and an enabled switch. The start and end times define the
 * routine's fixed window; the tasks are stretched to fill it, so the window
 * length is the number the user is really choosing.
 *
 * Changing the window never deletes a task. Task start and end times are not
 * stored — they are derived from the window, the task order and each task's
 * duration — so moving the window automatically moves every task with it.
 *
 * Alarms are per task now, so there is no routine-level alarm mode here — the
 * bell on each task in the plan screen decides what wakes you up.
 *
 * The sheet is stateless with respect to persistence: it edits a local copy of
 * [routine] and reports the result through [onSave]. The caller writes it via
 * the repository, which reschedules alarms.
 *
 * @param routine the routine to edit.
 * @param taskMinutes the durations of the routine's tasks, used to decide
 *   whether a shorter window can still hold them. Defaults to empty.
 * @param onSave called with the edited routine when the user confirms.
 * @param onDismiss called when the sheet closes without saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineSettingsSheet(
    routine: Routine,
    onSave: (Routine) -> Unit,
    onDismiss: () -> Unit,
    taskMinutes: List<Int> = emptyList(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Surface,
    ) {
        RoutineSettingsContent(
            initial = routine,
            taskMinutes = taskMinutes,
            onSave = onSave,
            onCancel = onDismiss,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutineSettingsContent(
    initial: Routine,
    taskMinutes: List<Int>,
    onSave: (Routine) -> Unit,
    onCancel: () -> Unit,
) {
    val taskCount = taskMinutes.size
    val haptics = LocalHapticFeedback.current
    var name by remember { mutableStateOf(initial.name) }
    var startMinute by remember { mutableIntStateOf(initial.startMinuteOfDay) }
    var endMinute by remember { mutableIntStateOf(initial.endMinuteOfDay) }
    var days by remember { mutableIntStateOf(initial.daysOfWeek) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var useBuffer by remember { mutableStateOf(initial.useBuffer) }
    var pickerTarget by remember { mutableStateOf(TimeField.None) }
    // Null means the routine repeats weekly; a date makes it a one-off that
    // runs on that single day and then disables itself.
    var oneOffDate by remember { mutableStateOf<LocalDate?>(RoutineDates.dateOf(initial)) }
    var datePickerOpen by remember { mutableStateOf(false) }
    // Untimed routines keep their length in [untimedMinutes]; the timed fields
    // below are simply ignored while [untimed] is true.
    var untimed by remember { mutableStateOf(initial.isUntimed) }
    var untimedMinutes by remember { mutableIntStateOf(initial.untimedDurationMinutes) }
    var allowPause by remember { mutableStateOf(initial.allowPause) }

    // Set when a shorter window would not fit the existing tasks. Holds the
    // pending (start, end) so the user can confirm the trim. Tasks are never
    // deleted by a time change.
    var pendingShrink by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // Same idea for an untimed routine, whose "window" is a single length.
    var pendingUntimedShrink by remember { mutableStateOf<Int?>(null) }
    // Set when the window cannot hold every task at all (each needs >= 1 min).
    var tooManyTasks by remember { mutableStateOf(false) }

    val nameValid = name.trim().isNotEmpty()
    // An untimed routine has no clock window: its "end" field stores the total
    // length, so the same capacity number drives both modes.
    val capacity = if (untimed) untimedMinutes else TimeWindow.capacity(startMinute, endMinute)

    /**
     * Applies a new start time. The end is pushed forward so it is never before
     * the start, and a window too short for the tasks is refused.
     */
    fun applyStart(picked: Int) {
        val newEnd = TimeWindow.endAfterStartMoved(
            start = picked,
            currentEnd = endMinute,
            taskMinutes = taskMinutes,
        )
        val newCapacity = TimeWindow.capacity(picked, newEnd)
        if (!TimeWindow.canHoldTasks(newCapacity, taskCount)) {
            tooManyTasks = true
            return
        }
        startMinute = picked
        endMinute = newEnd
    }

    /**
     * Applies a new end time, asking for confirmation before trimming tasks if
     * the window would be shorter than they need.
     */
    fun applyEnd(picked: Int) {
        val newCapacity = TimeWindow.capacity(startMinute, picked)
        if (!TimeWindow.canHoldTasks(newCapacity, taskCount)) {
            tooManyTasks = true
            return
        }
        if (newCapacity < taskMinutes.sum()) {
            pendingShrink = startMinute to picked
        } else {
            endMinute = picked
        }
    }

    /**
     * Writes the edited routine back. [windowStart] and [windowEnd] are the timed
     * clock window; [length] is the total for an untimed routine. Only the fields
     * that belong to the current mode are read, so callers may pass the stored
     * values for the unused mode.
     *
     * Set in one place so the Save button and both shrink confirmations can
     * never drift apart — in particular so the untimed flag is always written.
     */
    fun saveRoutine(windowStart: Int, windowEnd: Int, length: Int) {
        onSave(
            initial.copy(
                name = name.trim(),
                startMinuteOfDay = if (untimed) 0 else windowStart,
                endMinuteOfDay = if (untimed) length.coerceAtLeast(1) else windowEnd,
                // A one-off has no weekly days; a recurring routine has no date.
                // An untimed routine has neither.
                daysOfWeek = if (untimed || oneOffDate != null) DaysOfWeek.NONE else days,
                scheduledDate = if (untimed) null else RoutineDates.store(oneOffDate),
                enabled = enabled,
                useBuffer = useBuffer,
                allowPause = allowPause,
                isUntimed = untimed,
            ),
        )
    }

    /**
     * Explains what a shorter window costs, in the user's terms: how many tasks
     * get trimmed and by how many minutes in total, that spare time (the buffer)
     * is always used up before any task is touched, and that the trim is spread
     * so long tasks give up the most while very short ones barely move.
     *
     * @param capacity the window length the user is about to save.
     */
    fun shrinkExplanation(capacity: Int): String {
        val needed = taskMinutes.sum()
        val trimmed = TimeWindow.shrinkToFit(taskMinutes, capacity)
        val shorterCount = taskMinutes.indices.count { trimmed[it] < taskMinutes[it] }
        val removed = needed - trimmed.sum()
        val cost = if (shorterCount == 1) {
            "1 task will be shortened by $removed min."
        } else {
            "$shorterCount of your ${taskMinutes.size} tasks will be shortened by " +
                "$removed min in total."
        }
        return "Any spare time in the window goes first. This window is $capacity min " +
            "but your tasks need $needed min, so $cost Long tasks lose the most; " +
            "quick ones like brushing teeth barely change."
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(
            text = "Routine settings",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(16.dp))

        // --- Name ---------------------------------------------------------
        Text("Name", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Background, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
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
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(16.dp))

        // --- Anytime (untimed) --------------------------------------------
        // An untimed routine drops the clock entirely: no start time, no day,
        // no date — just a length you start from the Run button on its card.
        // Toggling preserves the current length in either direction.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Anytime routine",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                )
                Text(
                    text = if (untimed) {
                        "No start time — run it whenever you like"
                    } else {
                        "Has a fixed start time and days"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            AppSwitch(
                checked = untimed,
                onCheckedChange = { on ->
                    if (on) {
                        // Same clamp the creation wizard and the picker use, so a
                        // routine can never store a length outside 1..1439.
                        untimedMinutes = capacity.coerceIn(1, 24 * 60 - 1)
                    } else {
                        endMinute = (startMinute + untimedMinutes) % (24 * 60)
                    }
                    untimed = on
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        // --- Window: start and end (timed) or total length (untimed) ------
        if (untimed) {
            Text(
                "Length",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(4.dp))
            TimeFieldBox(
                label = formatLength(untimedMinutes),
                onClick = { pickerTarget = TimeField.Length },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "$capacity min total · no start time",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Start time",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    TimeFieldBox(
                        label = formatMinuteOfDay(startMinute),
                        onClick = { pickerTarget = TimeField.Start },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "End time",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    TimeFieldBox(
                        label = formatMinuteOfDay(endMinute),
                        onClick = { pickerTarget = TimeField.End },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "$capacity min window",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }

        Spacer(Modifier.height(16.dp))

        // --- Which day ----------------------------------------------------
        // Timed routines only: an untimed routine has no day at all.
        if (!untimed) {
            // Two mutually exclusive modes, worded the same way as the creation
            // flow: "Just one day" (a date) or "Days of the week" (weekly). A date
            // makes the routine a one-off; picking a weekday below clears it again,
            // so the two ways of scheduling can never both be set.
            Text("Which day", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Background, RoundedCornerShape(8.dp))
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Just one day",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary,
                    )
                    Text(
                        text = oneOffDate?.let { RoutineDates.oneOffLabel(it) }
                            ?: "Pick a date",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                IconButton(onClick = { datePickerOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.CalendarMonth,
                        contentDescription = "Pick a one-off date",
                        tint = TextPrimary,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // --- Days of the week -----------------------------------------
            Text(
                text = "Days of the week",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (oneOffDate == null) {
                    "Repeats every week on the days you pick."
                } else {
                    "Tap a day to switch to a weekly routine."
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DaysOfWeek.LETTER_LABELS.forEachIndexed { index, label ->
                    val bit = 1 shl index
                    val active = oneOffDate == null && (days and bit) != 0
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(CircleShape)
                            .background(if (active) TextPrimary else Background)
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                // Choosing a weekday makes the routine recurring.
                                oneOffDate = null
                                days = days xor bit
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = if (active) Background else TextSecondary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // --- Enabled ------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Enabled",
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            AppSwitch(
                checked = enabled,
                onCheckedChange = { enabled = it },
            )
        }

        Spacer(Modifier.height(16.dp))

        // --- Buffer -------------------------------------------------------
        // When on, spare window time is spread across the tasks so they stretch
        // to fill the routine. When off, every task keeps its minimum and the
        // leftover time shows as empty grid cells at the end.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Use buffer",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                )
                Text(
                    text = if (useBuffer) {
                        "Spare time is shared out across tasks"
                    } else {
                        "Tasks keep their minimum; spare time stays empty"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            AppSwitch(
                checked = useBuffer,
                onCheckedChange = { useBuffer = it },
            )
        }

        Spacer(Modifier.height(16.dp))

        // --- Pause --------------------------------------------------------
        // Pausing freezes the countdown and the in-run alarms; without it the
        // run can only be finished early. It is per routine so a workout can
        // allow it while a wake-up routine cannot.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Allow pausing",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                )
                Text(
                    text = if (allowPause) {
                        "Pause and Resume show on the run screen"
                    } else {
                        "Once it starts, the countdown cannot be stopped"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            AppSwitch(
                checked = allowPause,
                onCheckedChange = { allowPause = it },
            )
        }

        Spacer(Modifier.height(24.dp))

        // --- Actions ------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = TextSecondary)
            }
            TextButton(
                onClick = { saveRoutine(startMinute, endMinute, untimedMinutes) },
                enabled = nameValid,
            ) {
                Text(
                    text = "Save",
                    color = if (nameValid) TextPrimary else TextSecondary,
                )
            }
        }
    }

    if (datePickerOpen) {
        val seedMillis = (oneOffDate ?: RoutineDates.tomorrow())
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = seedMillis)
        DatePickerDialog(
            onDismissRequest = { datePickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { millis ->
                        oneOffDate = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                    }
                    datePickerOpen = false
                }) {
                    Text("OK", color = TextPrimary)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        // Clearing the date returns the routine to weekly.
                        oneOffDate = null
                        datePickerOpen = false
                    }) {
                        Text("Recurring", color = TextSecondary)
                    }
                    TextButton(onClick = { datePickerOpen = false }) {
                        Text("Cancel", color = TextSecondary)
                    }
                }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    val target = pickerTarget
    if (target != TimeField.None) {
        val seed = when (target) {
            TimeField.Start -> startMinute
            TimeField.End -> endMinute
            TimeField.Length -> untimedMinutes
            TimeField.None -> 0
        }
        // The stock Material 3 picker holds its own draft state and only reports
        // a value on OK, so a window the routine cannot hold is refused after
        // the user has finished choosing rather than mid-gesture.
        val pickerState = rememberTimePickerState(
            initialHour = seed / 60,
            initialMinute = seed % 60,
            is24Hour = true,
        )
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pickerTarget = TimeField.None },
            containerColor = Surface,
            title = {
                Text(
                    text = when (target) {
                        TimeField.Start -> "Start time"
                        TimeField.End -> "End time"
                        else -> "Length"
                    },
                    color = TextPrimary,
                )
            },
            text = {
                TimePicker(state = pickerState)
            },
            confirmButton = {
                TextButton(onClick = {
                    val picked = pickerState.hour * 60 + pickerState.minute
                    when (target) {
                        TimeField.Start -> applyStart(picked)
                        TimeField.End -> applyEnd(picked)
                        // The same rules as a timed window: refuse anything
                        // that cannot give every task a minute, and confirm
                        // anything that would have to trim them.
                        TimeField.Length -> when {
                            !TimeWindow.canHoldTasks(picked, taskCount) ->
                                tooManyTasks = true
                            picked < taskMinutes.sum() ->
                                pendingUntimedShrink = picked
                            else -> untimedMinutes = picked.coerceAtLeast(1)
                        }
                        TimeField.None -> Unit
                    }
                    pickerTarget = TimeField.None
                }) {
                    Text("OK", color = TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { pickerTarget = TimeField.None }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
        )
    }

    // --- Shorter-window confirmation --------------------------------------
    val shrink = pendingShrink
    if (shrink != null) {
        val (newStart, newEnd) = shrink
        val newCapacity = TimeWindow.capacity(newStart, newEnd)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingShrink = null },
            containerColor = Surface,
            title = { Text("Tasks will be shortened", color = TextPrimary) },
            text = {
                Text(
                    text = shrinkExplanation(newCapacity),
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // Persist the shorter window; the view model trims the
                    // tasks with the same TimeWindow.shrinkToFit rule spelled
                    // out in the dialog.
                    pendingShrink = null
                    saveRoutine(newStart, newEnd, untimedMinutes)
                }) {
                    Text("Shorten tasks", color = TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingShrink = null }) {
                    Text("Keep as is", color = TextSecondary)
                }
            },
        )
    }

    // --- Shorter-length confirmation (untimed) ----------------------------
    val untimedShrink = pendingUntimedShrink
    if (untimedShrink != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingUntimedShrink = null },
            containerColor = Surface,
            title = { Text("Tasks will be shortened", color = TextPrimary) },
            text = {
                Text(
                    text = shrinkExplanation(untimedShrink),
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingUntimedShrink = null
                    untimedMinutes = untimedShrink
                    saveRoutine(startMinute, endMinute, untimedShrink)
                }) {
                    Text("Shorten tasks", color = TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUntimedShrink = null }) {
                    Text("Keep as is", color = TextSecondary)
                }
            },
        )
    }

    // --- Too many tasks ---------------------------------------------------
    if (tooManyTasks) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { tooManyTasks = false },
            containerColor = Surface,
            title = { Text("Too many tasks", color = TextPrimary) },
            text = {
                Text(
                    text = "There are $taskCount tasks and each needs at " +
                        "least one minute, so this window is too short. Make it " +
                        "longer.",
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = { tooManyTasks = false }) {
                    Text("OK", color = TextPrimary)
                }
            },
        )
    }
}

/** Which time picker, if any, is open. */
private enum class TimeField { None, Start, End, Length }

/** A tappable time value that opens a picker. */
@Composable
private fun TimeFieldBox(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
    }
}

/** Formats minutes-since-midnight as `H:MM` (24h). */
private fun formatMinuteOfDay(minuteOfDay: Int): String {
    val wrapped = ((minuteOfDay % (24 * 60)) + (24 * 60)) % (24 * 60)
    return "%d:%02d".format(wrapped / 60, wrapped % 60)
}

/** Formats a duration in minutes as `45 min` or `1 h 30 min`. */
private fun formatLength(totalMinutes: Int): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0 -> "$minutes min"
        minutes == 0 -> "$hours h"
        else -> "$hours h $minutes min"
    }
}

@Preview(name = "Routine settings", showBackground = true, backgroundColor = 0xFF0A1116)
@Composable
private fun RoutineSettingsPreview() {
    AdhdRoutineAppTheme {
        Column(Modifier.background(Surface).padding(20.dp)) {
            RoutineSettingsContent(
                initial = Routine(
                    id = 1,
                    name = "Morning",
                    startMinuteOfDay = 7 * 60,
                    endMinuteOfDay = 8 * 60,
                    daysOfWeek = DaysOfWeek.MONDAY or DaysOfWeek.TUESDAY or DaysOfWeek.WEDNESDAY,
                ),
                taskMinutes = listOf(10, 15, 20),
                onSave = {},
                onCancel = {},
            )
        }
    }
}
