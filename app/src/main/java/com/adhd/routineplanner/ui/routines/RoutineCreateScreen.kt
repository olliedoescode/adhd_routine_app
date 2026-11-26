package com.adhd.routineplanner.ui.routines

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhd.routineplanner.domain.DaysOfWeek
import com.adhd.routineplanner.domain.IconKeys
import com.adhd.routineplanner.domain.PlacedTask
import com.adhd.routineplanner.domain.Routine
import com.adhd.routineplanner.domain.RoutineDates
import com.adhd.routineplanner.domain.StockTask
import com.adhd.routineplanner.domain.TimeParts
import com.adhd.routineplanner.ui.plan.AlarmToggle
import com.adhd.routineplanner.ui.plan.DurationWheels
import com.adhd.routineplanner.ui.settings.StockTaskIcons
import com.adhd.routineplanner.ui.theme.AdhdRoutineAppTheme
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.SelectionOutline
import com.adhd.routineplanner.ui.theme.StockTaskColors
import com.adhd.routineplanner.ui.theme.Surface
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** A task staged in the creation screen, before the routine is saved. */
data class DraftTask(
    val name: String,
    val minutes: Int,
    val color: Int,
    val alarmEnabled: Boolean = false,
)

/**
 * Full-page routine creation (spec §15.6).
 *
 * The screen is laid out top to bottom in the order the user thinks about a
 * routine:
 *
 * 1. **Name.**
 * 2. **When** — start and end time, then the date. The date defaults to
 *    tomorrow and the calendar button on its right turns the routine into a
 *    one-off that runs on that single day and then disables itself.
 * 3. **Repeat** — the MTWTFSS selector. Tapping any day switches the routine
 *    from one-off to weekly recurring; with no day highlighted it stays a
 *    one-off. The two are mutually exclusive, so picking a day clears the date
 *    and picking a date clears the days.
 * 4. **Buffer** — whether spare window time is spread across the tasks.
 * 5. **Tasks** — the staged list, reorderable by long-press and drag, with an
 *    add row that offers the user's stock tasks as shortcuts.
 *
 * Nothing is written until the user taps Create, so backing out leaves no
 * half-built routine behind.
 *
 * @param stockTasks the user's reusable tasks, offered in the add-task picker.
 * @param onSave called with the routine and its staged tasks when the user
 *   confirms. The caller inserts both in one transaction.
 * @param onBack called when the user leaves without saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineCreateScreen(
    stockTasks: List<StockTask>,
    onSave: (Routine, List<DraftTask>) -> Unit,
    onBack: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var name by remember { mutableStateOf("New routine") }
    var startMinute by remember { mutableIntStateOf(DEFAULT_START_MINUTE) }
    var endMinute by remember { mutableIntStateOf(DEFAULT_START_MINUTE + DEFAULT_LENGTH_MINUTES) }
    // A new routine starts as a one-off dated tomorrow. Tapping a weekday in
    // the repeat row clears this and makes it recurring.
    var oneOffDate by remember { mutableStateOf<LocalDate?>(RoutineDates.tomorrow()) }
    var days by remember { mutableIntStateOf(DaysOfWeek.NONE) }
    var useBuffer by remember { mutableStateOf(true) }
    var pickerTarget by remember { mutableStateOf(CreateTimeField.None) }
    var datePickerOpen by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableIntStateOf(-1) }
    var addingTask by remember { mutableStateOf(false) }

    val tasks = remember { mutableStateListOf<DraftTask>() }

    val nameValid = name.trim().isNotEmpty()
    val capacity = Routine(
        startMinuteOfDay = startMinute,
        endMinuteOfDay = endMinute,
    ).capacityMinutes
    val claimed = tasks.sumOf { it.minutes }
    val remaining = (capacity - claimed).coerceAtLeast(0)

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("New routine", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            onSave(
                                Routine(
                                    name = name.trim(),
                                    startMinuteOfDay = startMinute,
                                    endMinuteOfDay = endMinute,
                                    daysOfWeek = if (oneOffDate != null) DaysOfWeek.NONE else days,
                                    enabled = true,
                                    useBuffer = useBuffer,
                                    scheduledDate = RoutineDates.store(oneOffDate),
                                ),
                                tasks.toList(),
                            )
                        },
                        enabled = nameValid,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Create routine",
                            tint = if (nameValid) TextPrimary else TextSecondary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            // --- Name -----------------------------------------------------
            SectionLabel("Name")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface, RoundedCornerShape(8.dp))
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

            Spacer(Modifier.height(20.dp))

            // --- When: start and end --------------------------------------
            SectionLabel("When")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Start",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    CreateTimeFieldBox(
                        label = formatCreateMinuteOfDay(startMinute),
                        onClick = { pickerTarget = CreateTimeField.Start },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "End",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    CreateTimeFieldBox(
                        label = formatCreateMinuteOfDay(endMinute),
                        onClick = { pickerTarget = CreateTimeField.End },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "$capacity min window · $claimed claimed · $remaining spare",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )

            Spacer(Modifier.height(16.dp))

            // --- Date: the one-off switch ---------------------------------
            // The date row is always visible. When a date is set the routine is
            // a one-off; the calendar icon on the right opens the picker, and
            // the "Recurring" chip clears the date again.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Surface)
                    .clickable { datePickerOpen = true }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Date",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = oneOffDate?.let { RoutineDates.oneOffLabel(it) }
                            ?: "Repeats weekly",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                IconButton(onClick = { datePickerOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.CalendarMonth,
                        contentDescription = "Pick a date for a one-off routine",
                        tint = TextPrimary,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (oneOffDate != null) {
                    "One-off: runs once on this date, then turns itself off."
                } else {
                    "Repeats every week on the days below."
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )

            Spacer(Modifier.height(20.dp))

            // --- Repeat: the weekly day selector --------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("Repeat every week", modifier = Modifier.weight(1f))
                if (oneOffDate != null) {
                    Text(
                        text = "One-off",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
            }
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
                            .background(if (active) TextPrimary else Surface)
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                // Tapping any day makes the routine recurring,
                                // so the one-off date is dropped.
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
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (oneOffDate != null) {
                    "Tap a day to make this a weekly routine instead."
                } else if (days == DaysOfWeek.NONE) {
                    "No days selected — this will be a one-off."
                } else {
                    "Runs on the highlighted days."
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )

            Spacer(Modifier.height(20.dp))

            // --- Buffer ---------------------------------------------------
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

            Spacer(Modifier.height(20.dp))

            // --- Tasks ----------------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("Tasks", modifier = Modifier.weight(1f))
                Text(
                    text = "Long-press to reorder",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
            Spacer(Modifier.height(8.dp))

            if (tasks.isEmpty()) {
                Text(
                    text = "No tasks yet. Add one below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                ReorderableTaskList(
                    tasks = tasks,
                    onTap = { index -> editingIndex = index },
                    onRemove = { index ->
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        tasks.removeAt(index)
                    },
                    onMove = { from, to ->
                        if (from != to) {
                            val moved = tasks.removeAt(from)
                            tasks.add(to, moved)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Surface)
                    .clickable { addingTask = true }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Add task",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                )
            }
        }
    }

    // --- Window pickers ---------------------------------------------------
    val target = pickerTarget
    if (target != CreateTimeField.None) {
        val seed = if (target == CreateTimeField.Start) startMinute else endMinute
        val pickerState = rememberTimePickerState(
            initialHour = seed / 60,
            initialMinute = seed % 60,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { pickerTarget = CreateTimeField.None },
            containerColor = Surface,
            title = {
                Text(
                    text = if (target == CreateTimeField.Start) "Start time" else "End time",
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
                    if (target == CreateTimeField.Start) startMinute = picked else endMinute = picked
                    pickerTarget = CreateTimeField.None
                }) {
                    Text("OK", color = TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { pickerTarget = CreateTimeField.None }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
        )
    }

    // --- One-off date picker ----------------------------------------------
    if (datePickerOpen) {
        val initialMillis = (oneOffDate ?: RoutineDates.tomorrow())
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { datePickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = dateState.selectedDateMillis
                    if (millis != null) {
                        oneOffDate = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                        // A one-off has no weekly days.
                        days = DaysOfWeek.NONE
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

    // --- Per-task duration editor ----------------------------------------
    val editIndex = editingIndex
    if (editIndex in tasks.indices) {
        val draft = tasks[editIndex]
        val maxForEdit = (capacity - (claimed - draft.minutes)).coerceAtLeast(MIN_TASK_MINUTES)
        DraftTaskDialog(
            initial = draft,
            maxMinutes = maxForEdit,
            stockTasks = stockTasks,
            onConfirm = { updated ->
                tasks[editIndex] = updated
                editingIndex = -1
            },
            onDismiss = { editingIndex = -1 },
        )
    }

    // --- New task editor --------------------------------------------------
    if (addingTask) {
        val maxForNew = (capacity - claimed).coerceAtLeast(MIN_TASK_MINUTES)
        DraftTaskDialog(
            initial = null,
            maxMinutes = maxForNew,
            stockTasks = stockTasks,
            onConfirm = { created ->
                tasks.add(created)
                addingTask = false
            },
            onDismiss = { addingTask = false },
        )
    }
}

/** A small uppercase section heading. */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

/**
 * A vertical list of staged tasks that can be reordered by long-pressing a row
 * and dragging it up or down.
 *
 * The list is a plain [Column] rather than a `LazyColumn` because the screen is
 * already inside a vertical scroll container, and a routine is short enough
 * that laying every row out is cheap. Dragging tracks the finger's vertical
 * travel and swaps the dragged row with its neighbour once the finger has
 * moved past half a row, which keeps the reorder predictable without needing
 * per-row offsets.
 */
@Composable
private fun ReorderableTaskList(
    tasks: List<DraftTask>,
    onTap: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val rowHeight = 56.dp
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }

    Column(Modifier.fillMaxWidth()) {
        tasks.forEachIndexed { index, task ->
            val isDragging = index == draggingIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isDragging) Surface else Background)
                    .pointerInput(tasks.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingIndex = index
                                dragOffset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                // Swap once the finger has travelled past half
                                // a row, then reset so the next swap needs the
                                // same travel again.
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
                    .clickable { onTap(index) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(Color(task.color), CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = task.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${task.minutes} min",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                IconButton(onClick = { onRemove(index) }) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Remove ${task.name}",
                        tint = TextSecondary,
                    )
                }
            }
        }
    }
}

/**
 * Editor for one staged task: name, colour, duration and alarm. Used both to add
 * a new task and to edit an existing draft, so the two paths cannot drift apart.
 *
 * This deliberately mirrors the plan screen's `TaskEditDialog` / `TaskCreateDialog`:
 * the same colour swatches with a selection ring, the same clock-face
 * [DurationWheels] clamped to the window, and the same [AlarmToggle]. Building a
 * routine therefore feels identical to editing one afterwards.
 *
 * When creating a new task the dialog also offers the user's stock tasks as
 * one-tap presets: tapping one fills in its name, colour, duration and alarm,
 * which the user can then tweak before adding.
 *
 * @param initial the draft to edit, or `null` to create a new one.
 * @param maxMinutes the longest duration this task may be given without
 *   over-subscribing the routine window. The wheels are clamped to this.
 * @param stockTasks the user's reusable tasks, shown as presets when adding.
 * @param onConfirm called with the finished draft.
 * @param onDismiss called when the dialog closes without confirming.
 */
@Composable
private fun DraftTaskDialog(
    initial: DraftTask?,
    maxMinutes: Int,
    stockTasks: List<StockTask>,
    onConfirm: (DraftTask) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var color by remember {
        mutableStateOf(initial?.color ?: StockTaskColors.first().toArgb())
    }
    var alarmEnabled by remember { mutableStateOf(initial?.alarmEnabled ?: false) }
    var parts by remember {
        mutableStateOf(TimeParts.fromMinutes(initial?.minutes ?: DEFAULT_TASK_MINUTES))
    }

    // Keep the chosen duration inside what the routine can still hold. The
    // wheels can only offer values up to `maxMinutes`, so this only bites when
    // the limit shrinks while the dialog is open.
    val clampedMinutes = parts.totalMinutes.coerceIn(1, maxMinutes.coerceAtLeast(1))

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(
                text = if (initial == null) "New task" else "Edit task",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                // --- Stock task presets (new tasks only) ------------------
                if (initial == null && stockTasks.isNotEmpty()) {
                    Text(
                        text = "Stock tasks",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        stockTasks.forEach { stock ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Background)
                                    .clickable {
                                        haptics.performHapticFeedback(
                                            HapticFeedbackType.TextHandleMove,
                                        )
                                        name = stock.name
                                        color = stock.color
                                        alarmEnabled = stock.alarmEnabled
                                        parts = TimeParts.fromMinutes(
                                            stock.minutes.coerceIn(1, maxMinutes.coerceAtLeast(1)),
                                        )
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = StockTaskIcons.iconFor(stock.icon),
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stock.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "${stock.minutes} min",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
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
                        .background(Background, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
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
                        modifier = Modifier.fillMaxWidth(),
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
                                        haptics.performHapticFeedback(
                                            HapticFeedbackType.TextHandleMove,
                                        )
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
                    onConfirm(
                        DraftTask(
                            name = name.trim().ifEmpty { PlacedTask.DEFAULT_NAME },
                            minutes = clampedMinutes,
                            color = color,
                            alarmEnabled = alarmEnabled,
                        ),
                    )
                },
            ) {
                Text(if (initial == null) "Add" else "Save", color = TextPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
    )
}

/** A tappable time value that opens a picker. */
@Composable
private fun CreateTimeFieldBox(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Surface)
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

/** Which time picker, if any, is open on the creation screen. */
private enum class CreateTimeField { None, Start, End }

/** Formats minutes-since-midnight as `H:MM` (24h). */
private fun formatCreateMinuteOfDay(minuteOfDay: Int): String {
    val wrapped = ((minuteOfDay % (24 * 60)) + (24 * 60)) % (24 * 60)
    return "%d:%02d".format(wrapped / 60, wrapped % 60)
}

/** Default start of a new routine: 07:00. */
private const val DEFAULT_START_MINUTE: Int = 7 * 60

/** Default window length of a new routine, in minutes. */
private const val DEFAULT_LENGTH_MINUTES: Int = 60

/** Default duration offered for a staged task. */
private const val DEFAULT_TASK_MINUTES: Int = 10

/** Shortest duration a staged task may have. */
private const val MIN_TASK_MINUTES: Int = 1

@Preview(name = "Routine create screen", showBackground = true, backgroundColor = 0xFF0E0F12)
@Composable
private fun RoutineCreatePreview() {
    AdhdRoutineAppTheme {
        RoutineCreateScreen(
            stockTasks = listOf(
                StockTask(
                    name = "Shower",
                    minutes = 10,
                    color = 0xFF4F8EF7.toInt(),
                    icon = IconKeys.SHOWER,
                ),
                StockTask(
                    name = "Breakfast",
                    minutes = 15,
                    color = 0xFFF2994A.toInt(),
                    icon = IconKeys.BREAKFAST,
                ),
            ),
            onSave = { _, _ -> },
            onBack = {},
        )
    }
}
