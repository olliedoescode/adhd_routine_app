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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
 * Name, start time, end time (both Material 3 time pickers), active days (seven
 * toggles) and an enabled switch. The start and end times define the routine's
 * fixed window; the tasks are stretched to fill it, so the window length is the
 * number the user is really choosing.
 *
 * Alarms are per task now, so there is no routine-level alarm mode here — the
 * bell on each task in the plan screen decides what wakes you up.
 *
 * The sheet is stateless with respect to persistence: it edits a local copy of
 * [routine] and reports the result through [onSave]. The caller writes it via
 * the repository, which reschedules alarms.
 *
 * @param routine the routine to edit.
 * @param onSave called with the edited routine when the user confirms.
 * @param onDismiss called when the sheet closes without saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineSettingsSheet(
    routine: Routine,
    onSave: (Routine) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Surface,
    ) {
        RoutineSettingsContent(
            initial = routine,
            onSave = onSave,
            onCancel = onDismiss,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutineSettingsContent(
    initial: Routine,
    onSave: (Routine) -> Unit,
    onCancel: () -> Unit,
) {
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

    val nameValid = name.trim().isNotEmpty()
    // The window length is what the user is really choosing, so show it live.
    val capacity = initial.copy(
        startMinuteOfDay = startMinute,
        endMinuteOfDay = endMinute,
    ).capacityMinutes

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

        // --- Window: start and end ----------------------------------------
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

        Spacer(Modifier.height(16.dp))

        // --- Date ---------------------------------------------------------
        // A date makes the routine a one-off. Picking a weekday below clears it
        // again, so the two ways of scheduling can never both be set.
        Text("Date", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
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
                    text = oneOffDate?.let { RoutineDates.oneOffLabel(it) } ?: "Repeats weekly",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                )
                Text(
                    text = if (oneOffDate == null) {
                        "Runs on the days selected below"
                    } else {
                        "One-off: runs once, then turns itself off"
                    },
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

        // --- Active days --------------------------------------------------
        Text("Active days", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
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
            Switch(
                checked = enabled,
                onCheckedChange = { enabled = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Background,
                    checkedTrackColor = TextPrimary,
                ),
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
            Switch(
                checked = useBuffer,
                onCheckedChange = { useBuffer = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Background,
                    checkedTrackColor = TextPrimary,
                ),
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
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            startMinuteOfDay = startMinute,
                            endMinuteOfDay = endMinute,
                            // A one-off has no weekly days; a recurring routine
                            // has no date. The two are mutually exclusive.
                            daysOfWeek = if (oneOffDate != null) DaysOfWeek.NONE else days,
                            scheduledDate = RoutineDates.store(oneOffDate),
                            enabled = enabled,
                            useBuffer = useBuffer,
                        ),
                    )
                },
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
        val seed = if (target == TimeField.Start) startMinute else endMinute
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
                    text = if (target == TimeField.Start) "Start time" else "End time",
                    color = TextPrimary,
                )
            },
            text = {
                Box(contentAlignment = Alignment.Center) {
                    TimePicker(state = pickerState)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val picked = pickerState.hour * 60 + pickerState.minute
                    if (target == TimeField.Start) startMinute = picked else endMinute = picked
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
}

/** Which time picker, if any, is open. */
private enum class TimeField { None, Start, End }

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

@Preview(name = "Routine settings", showBackground = true, backgroundColor = 0xFF0E0F12)
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
                onSave = {},
                onCancel = {},
            )
        }
    }
}
