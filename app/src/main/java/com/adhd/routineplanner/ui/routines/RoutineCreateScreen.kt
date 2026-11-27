package com.adhd.routineplanner.ui.routines

import com.adhd.routineplanner.ui.common.AppTopBar

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.adhd.routineplanner.domain.DaysOfWeek
import com.adhd.routineplanner.domain.IconKeys
import com.adhd.routineplanner.domain.PlacedTask
import com.adhd.routineplanner.domain.Routine
import com.adhd.routineplanner.domain.RoutineCategory
import com.adhd.routineplanner.domain.RoutineDates
import com.adhd.routineplanner.domain.RoutineNaming
import com.adhd.routineplanner.domain.StockTask
import com.adhd.routineplanner.domain.TimeParts
import com.adhd.routineplanner.domain.TimeWindow
import com.adhd.routineplanner.ui.common.AppSwitch
import com.adhd.routineplanner.ui.common.StockChip
import com.adhd.routineplanner.ui.common.StockTaskChip
import com.adhd.routineplanner.ui.common.reorderableRow
import com.adhd.routineplanner.ui.common.rememberReorderState
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
import com.adhd.routineplanner.ui.theme.washed
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** A task staged in the creation screen, before the routine is saved. */
data class DraftTask(
    val name: String,
    val minutes: Int,
    val color: Int,
    val alarmEnabled: Boolean = false,
    /**
     * The stock-task icon key this draft came from, or [IconKeys.TASK] for a
     * task the user typed by hand. Carried through to the saved task so the run
     * screen and notification can show it.
     */
    val icon: String = IconKeys.TASK,
)

/**
 * Full-page routine creation (spec §15.6).
 *
 * The flow is four guided steps followed by the full editor:
 *
 * 1. **When does it start?** — the clock dial.
 * 2. **When should it end?** — the clock dial.
 * 3. **Which day?** — a single date, or the days of the week.
 * 4. **Pick tasks** — a dedicated page of stock-task rows, so the user builds
 *    the routine from their own reusable pieces before seeing the full form.
 *
 * The editor then opens with everything adjustable:
 *
 * 1. **Name** — pre-filled from [RoutineNaming] the moment the task step is
 *    left, and freely editable. Once the user types, the suggestion stops
 *    updating so their words are never overwritten.
 * 2. **When** — start and end time.
 * 3. **Which day** — the same two modes as step 3.
 * 4. **Buffer** — whether spare window time is spread across the tasks.
 * 5. **Tasks** — the staged list, reorderable by long-press and drag.
 *
 * Nothing is written until the user taps Create, so backing out leaves no
 * half-built routine behind.
 *
 * @param stockTasks the user's reusable tasks, offered in the task picker.
 * @param existingNames names of routines that already exist, used to make the
 *   suggested name unique (`"Morning Routine #2"`).
 * @param onSave called with the routine and its staged tasks when the user
 *   confirms. The caller inserts both in one transaction.
 * @param onBack called when the user leaves without saving.
 * @param saving true while the routine is being written to the database. The
 *   screen covers itself with a progress overlay so a slow insert cannot look
 *   like a dropped tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineCreateScreen(
    stockTasks: List<StockTask>,
    existingNames: List<String> = emptyList(),
    onSave: (Routine, List<DraftTask>) -> Unit,
    onBack: () -> Unit,
    saving: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current

    // The creation flow is split into steps so the first thing a user sees is
    // one big, obvious question at a time (proposal §4.4):
    //   1. When does it start?   2. When does it end?
    //   3. Which day(s)?         4. Pick the tasks.
    // Then the full editor opens with everything adjustable.
    var step by remember { mutableIntStateOf(0) }

    // Seed the start time from the clock with a smart default: in the small
    // hours or late at night the routine is planned for the next morning, so
    // the date rolls forward too. The end defaults to one hour later.
    val smartDefault = remember {
        val now = java.time.LocalTime.now()
        TimeWindow.smartDefaultStart(now.hour * 60 + now.minute)
    }
    val suggestedStart = smartDefault.first
    // The name starts empty and is filled in from RoutineNaming once the user
    // leaves the task step, so the suggestion can see the tasks they picked.
    var name by remember { mutableStateOf("") }

    // Set once the user types in the name field. From then on the suggestion
    // stops updating, so their words are never overwritten.
    var nameEdited by remember { mutableStateOf(false) }
    var startMinute by remember { mutableIntStateOf(suggestedStart) }
    var endMinute by remember {
        mutableIntStateOf(TimeWindow.endForStart(suggestedStart, emptyList()))
    }
    // A new routine starts as a one-off. The date is today, or tomorrow when
    // the smart default rolled forward (late at night). Tapping a weekday in
    // the repeat row clears this and makes it recurring.
    var oneOffDate by remember {
        mutableStateOf<LocalDate?>(
            if (smartDefault.second) RoutineDates.tomorrow() else LocalDate.now(),
        )
    }
    var days by remember { mutableIntStateOf(DaysOfWeek.NONE) }
    var useBuffer by remember { mutableStateOf(true) }
    var pickerTarget by remember { mutableStateOf(CreateTimeField.None) }
    var datePickerOpen by remember { mutableStateOf(false) }

    // Which repeat mode is selected. `oneOffDate` alone cannot answer that
    // while the date picker is open (the date is only written on OK), so the
    // row looked inert between the tap and the confirm — most visible after
    // switching from "No set time". This latches the choice the moment the row
    // is pressed and follows the date whenever a real date is picked.
    var oneOffMode by remember { mutableStateOf(oneOffDate != null) }

    // A routine can opt out of the clock entirely: no start time, no day, no
    // date — just a length to work through whenever the user taps Run on its
    // card. `untimed` is the switch, `untimedMinutes` the length; the length is
    // stored as the window end with a 00:00 start, which is the shape the rest
    // of the app already understands.
    var untimed by remember { mutableStateOf(false) }
    var untimedMinutes by remember { mutableIntStateOf(UNTIMED_DEFAULT_MINUTES) }

    // The length picker in the fine-tune editor. Untimed routines edit a length
    // where timed ones edit a start and an end.
    var lengthPickerOpen by remember { mutableStateOf(false) }

    // Whether the run screen offers Pause/Resume for this routine. Off by
    // default: for most routines a pause button is exactly what lets a run
    // stall, and pausing is an escape hatch to opt into rather than something
    // to notice and undo. The toggle sits next to the buffer switch.
    var allowPause by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableIntStateOf(-1) }
    var addingTask by remember { mutableStateOf(false) }

    // The stock task the user tapped in step 4, waiting for them to confirm how
    // long it will take. Non-null opens the duration-confirm dialog. This is the
    // "tap a stock task, then confirm the time" flow: a tap never adds a task
    // straight away, it always asks first.
    var pendingStock by remember { mutableStateOf<StockTask?>(null) }

    // Indices of staged tasks highlighted for a bulk delete. Empty means the
    // list is in "tap opens the editor" mode; non-empty means "tap toggles".
    val selectedIndices = remember { mutableStateListOf<Int>() }

    // Set when the user tries to add a task but the window is full. Drives the
    // "adjust the end time first" prompt.
    var fullPrompt by remember { mutableStateOf(false) }

    // Set when a shorter window would not fit the existing tasks. Holds the
    // pending (start, end) so the user can confirm the trim.
    var pendingShrink by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val tasks = remember { mutableStateListOf<DraftTask>() }

    val nameValid = name.trim().isNotEmpty()

    // The guided flow has different pages in each mode: a timed routine asks
    // day → start → end → tasks, an untimed one asks day/untimed → length →
    // tasks. `step` stays the canonical number the editor uses (>= 4), and
    // `pages` maps a step to its position in the flow. That is what makes Back
    // trustworthy: the previous page is whichever step came before *this* one
    // in the mode the user is actually in.
    val pages = if (untimed) UNTIMED_PAGES else TIMED_PAGES
    val lastPage = pages.last()
    val pageCount = pages.size
    val pageIndex = if (step < 4) pages.indexOf(step).coerceAtLeast(0) else pageCount

    // An untimed routine is a window from midnight to its length, so every
    // existing capacity / claim / spare calculation keeps working untouched.
    val windowStart = if (untimed) 0 else startMinute
    val windowEnd = if (untimed) untimedMinutes else endMinute

    val capacity = TimeWindow.capacity(windowStart, windowEnd)
    val claimed = tasks.sumOf { it.minutes }
    val remaining = (capacity - claimed).coerceAtLeast(0)

    /**
     * Applies a new start time, honouring the editing rules:
     *  - the end is pushed forward so it is never before the start;
     *  - if tasks exist, the end is set to cover their total length;
     *  - if the resulting window cannot hold every task (each needs >= 1 min),
     *    the change is refused with a "too many tasks" message.
     */
    fun applyStart(picked: Int) {
        val newEnd = TimeWindow.endAfterStartMoved(
            start = picked,
            currentEnd = endMinute,
            taskMinutes = tasks.map { it.minutes },
        )
        val newCapacity = TimeWindow.capacity(picked, newEnd)
        if (!TimeWindow.canHoldTasks(newCapacity, tasks.size)) {
            fullPrompt = true
            return
        }
        startMinute = picked
        endMinute = newEnd
    }

    /**
     * Applies a new end time. If the window would be too short for the tasks,
     * asks the user to confirm trimming them rather than deleting anything.
     */
    fun applyEnd(picked: Int) {
        val newCapacity = TimeWindow.capacity(startMinute, picked)
        if (!TimeWindow.canHoldTasks(newCapacity, tasks.size)) {
            fullPrompt = true
            return
        }
        if (newCapacity < claimed) {
            pendingShrink = startMinute to picked
        } else {
            endMinute = picked
        }
    }

    /**
     * Fills the suggested name from everything that is known about the routine.
     *
     * Never overwrites a name the user typed: `nameEdited` is the latch for
     * that. An untimed routine has no day or date to embed, so the suggestion
     * uses the smart default time only as a flavour hint ("Morning Routine"),
     * never as a scheduled start.
     */
    fun suggestName() {
        if (nameEdited) return
        name = RoutineNaming.suggest(
            taskNames = tasks.map { it.name },
            startMinuteOfDay = if (untimed) suggestedStart else startMinute,
            days = if (untimed || oneOffDate != null) DaysOfWeek.NONE else days,
            oneOffDate = if (untimed) null else oneOffDate,
            existingNames = existingNames,
        )
    }

    /**
     * Moves to the next page of the wizard, or into the editor from the last
     * page. Leaving the task page is when the name suggestion gets filled in,
     * because only then are the tasks known.
     */
    fun goForward() {
        if (step == lastPage) suggestName()
        val index = pages.indexOf(step)
        step = if (index >= 0 && index < pages.lastIndex) pages[index + 1] else 4
    }

    /**
     * Moves one page back — including out of the editor, which returns to the
     * task page rather than throwing the routine away. Only backing out of the
     * first page leaves the screen entirely.
     *
     * The previous page is looked up in `pages`, not by decrementing `step`,
     * because an untimed routine has no start/end pages: from its length page,
     * Back must land on the day/untimed step rather than on a clock question
     * that does not exist in this mode.
     */
    fun goBack() {
        if (step >= 4) {
            step = lastPage
            return
        }
        val index = pages.indexOf(step)
        if (index > 0) step = pages[index - 1] else onBack()
    }

    /**
     * Snapshots everything the wizard has collected into a [Routine].
     *
     * An untimed routine stores its length as the window end, clamped up to what
     * the tasks already need so the plan is never saved over-subscribed by
     * construction. Kept in one place so the footer button and any other caller
     * cannot disagree about what gets saved.
     */
    fun buildRoutine(): Routine = Routine(
        name = name.trim(),
        startMinuteOfDay = windowStart,
        endMinuteOfDay = if (untimed) {
            untimedMinutes.coerceAtLeast(claimed).coerceAtLeast(MIN_TASK_MINUTES)
        } else {
            endMinute
        },
        daysOfWeek = if (untimed || oneOffDate != null) DaysOfWeek.NONE else days,
        enabled = true,
        useBuffer = useBuffer,
        scheduledDate = if (untimed) null else RoutineDates.store(oneOffDate),
        isUntimed = untimed,
        allowPause = allowPause,
    )

    /** Hands the built routine and its staged tasks to the caller. */
    fun save() {
        if (!nameValid) return
        onSave(buildRoutine(), tasks.toList())
    }

    // The system back gesture follows the same rule as the top-bar arrow and
    // the footer's Back button: one page back inside the wizard, out of the
    // screen only from the first page. Disabled there so the NavHost's own
    // back handling takes over.
    BackHandler(enabled = step > 0) { goBack() }

    // Keep the suggested name in step with the day pattern while the user is on
    // the fine-tune step. The name is first filled in when the task step is
    // left, but the day/date can still be changed afterwards — and the
    // suggestion embeds the day ("Morning Routine on weekdays", "… on Monday"),
    // so it would otherwise go stale. This only runs while the user has not
    // typed their own name (`nameEdited` is false); the moment they type, the
    // suggestion stops touching it. It also waits until the task step has been
    // passed (step >= 4) so it never fights the initial fill-in.
    LaunchedEffect(step, days, oneOffDate, startMinute, untimed, nameEdited) {
        if (step >= 4) suggestName()
    }

    Scaffold(
        containerColor = Background,
        // The fine-tune editor saves from a footer button, matching the Next
        // button every guided step uses: one primary action, in the same place,
        // reachable with a thumb. The top bar keeps just Back, so no page offers
        // two competing ways forward.
        bottomBar = {
            if (step >= 4) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Background)
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (nameValid) TextPrimary else Surface)
                            .clickable(enabled = nameValid, onClick = { save() })
                            .padding(vertical = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Create routine",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (nameValid) Background else TextSecondary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        },
        topBar = {
            AppTopBar(
                title = "New routine",
                // The count reflects the pages this mode actually has, so an
                // untimed routine reads "Step 2 of 3" rather than skipping a
                // number it will never show.
                subtitle = if (step < 4) {
                    "Step ${pageIndex + 1} of $pageCount"
                } else {
                    "Fine-tune the plan"
                },
                navigationIcon = {
                    // One page back inside the wizard; only the first page
                    // leaves the screen. Same rule as the footer's Back button
                    // and the system gesture.
                    IconButton(onClick = { goBack() }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (step < 4) {
            // --- Guided intro steps --------------------------------------
            // One big question per screen, so the user is never faced with the
            // whole form at once. Each step advances on its own.
            CreateStepScaffold(
                step = step,
                innerPadding = innerPadding,
                hasTasks = tasks.isNotEmpty(),
                onNext = { goForward() },
            ) {
                when (step) {
                    0 -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        BigRepeatStep(
                            oneOffDate = oneOffDate,
                            days = days,
                            oneOffSelected = oneOffMode,
                            onPickDate = {
                                // Choosing a day means the routine is timed
                                // again, so the "No set time" switch turns
                                // itself off rather than staying lit over a
                                // schedule it no longer describes.
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                untimed = false
                                // Light the row straight away; the date picker
                                // only writes `oneOffDate` when it confirms.
                                oneOffMode = true
                                datePickerOpen = true
                            },
                            onToggleDay = { bit ->
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                untimed = false
                                oneOffMode = false
                                oneOffDate = null
                                days = days xor bit
                            },
                            onClearDate = {
                                oneOffMode = false
                                oneOffDate = null
                            },
                        )
                        Spacer(Modifier.height(16.dp))
                        UntimedToggle(
                            untimed = untimed,
                            onToggle = { value ->
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                untimed = value
                                // The two modes are exclusive: a timed routine
                                // needs a day, an untimed one has none, so the
                                // choice is dropped with the mode rather than
                                // lingering invisibly.
                                if (value) {
                                    oneOffMode = false
                                    oneOffDate = null
                                    days = DaysOfWeek.NONE
                                }
                            },
                        )
                    }
                    1 -> if (untimed) {
                        BigDurationStep(
                            minutes = untimedMinutes,
                            taskMinutes = claimed,
                            onChange = { untimedMinutes = it },
                        )
                    } else {
                        BigTimeStep(
                            title = "When does it start?",
                            subtitle = "Pick the time you want to begin.",
                            minuteOfDay = startMinute,
                            onChange = { picked ->
                                // Setting the start defaults the end to one hour
                                // later (or to cover existing tasks).
                                startMinute = picked
                                endMinute = TimeWindow.endForStart(
                                    picked,
                                    tasks.map { it.minutes },
                                )
                            },
                            // "That's in …" belongs on the start step and follows
                            // the picker live: as the user scrolls the time,
                            // startMinute changes and this recomputes on the next
                            // frame.
                            footnote = timeUntilStart(startMinute, oneOffDate),
                        )
                    }
                    2 -> BigTimeStep(
                        title = "When should it end?",
                        subtitle = "That gives you a ${formatDuration(capacity)} window.",
                        minuteOfDay = endMinute,
                        onChange = { picked -> applyEnd(picked) },
                    )
                    else -> BigTaskPickStep(
                        stockTasks = stockTasks,
                        tasks = tasks,
                        capacity = capacity,
                        startMinute = startMinute,
                        onQuickAddStock = { stock ->
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            // A single tap adds the task straight away using the
                            // duration saved on the stock task, clamped to the
                            // room left in the window. Tapping a task that is
                            // already in the routine is a no-op, since its chip
                            // already reads as picked.
                            when {
                                remaining < MIN_TASK_MINUTES -> fullPrompt = true
                                tasks.any { it.name.equals(stock.name, ignoreCase = true) } -> Unit
                                else -> tasks.add(
                                    DraftTask(
                                        name = stock.name,
                                        minutes = stock.minutes
                                            .coerceIn(MIN_TASK_MINUTES, remaining),
                                        color = stock.color,
                                        alarmEnabled = stock.alarmEnabled,
                                        icon = stock.icon,
                                    ),
                                )
                            }
                        },
                        onPickStock = { stock ->
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            // A double tap opens the duration-confirm dialog so
                            // the user can set how long the task takes before it
                            // joins the routine.
                            if (remaining < MIN_TASK_MINUTES) {
                                fullPrompt = true
                            } else {
                                pendingStock = stock
                            }
                        },
                        onRemoveTask = { index ->
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (index in tasks.indices) tasks.removeAt(index)
                        },
                        onMoveTask = { from, to ->
                            if (from != to && from in tasks.indices && to in tasks.indices) {
                                val moved = tasks.removeAt(from)
                                tasks.add(to, moved)
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onAddOwn = {
                            if (remaining < MIN_TASK_MINUTES) {
                                fullPrompt = true
                            } else {
                                addingTask = true
                            }
                        },
                    )
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            // --- Name -----------------------------------------------------
            // Pre-filled from RoutineNaming when the task step was left, and
            // freely editable. Typing sets `nameEdited`, which stops the
            // suggestion from ever overwriting the user's own words.
            SectionLabel("Name")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (name.isEmpty()) {
                    Text(
                        text = "Name this routine",
                        style = TextStyle(color = TextSecondary, fontSize = 16.sp),
                    )
                }
                BasicTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameEdited = true
                    },
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
            // An untimed routine has no clock at all, so it edits a single
            // length instead of a start/end pair. Underneath it is still a
            // window (00:00 → length), which is what keeps capacity, buffer and
            // the grid identical in both modes.
            if (untimed) {
                SectionLabel("Length")
                Spacer(Modifier.height(8.dp))
                CreateTimeFieldBox(
                    label = formatDuration(untimedMinutes),
                    onClick = { lengthPickerOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${formatDuration(capacity)} long · " +
                        "${formatDuration(claimed)} claimed · " +
                        "${formatDuration(remaining)} spare",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "No start time or day — start it from the Run button " +
                        "on the routine card.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            } else {
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
                    text = "${formatDuration(capacity)} window · " +
                        "${formatDuration(claimed)} claimed · " +
                        "${formatDuration(remaining)} spare",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                // When the start is under a day away, say how long that is —
                // the same "That's in …" line the end-time step shows, so the
                // timing is clear while fine-tuning too. Null (and hidden) when
                // it is further out or already in the past.
                timeUntilStart(startMinute, oneOffDate)?.let { until ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = until,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Tap a time to change it. The end always stays after the start.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }

            Spacer(Modifier.height(16.dp))

            // Untimed routines have no day at all, so the day pickers are
            // skipped rather than shown disabled.
            if (!untimed) {
            // --- Which day ------------------------------------------------
            // Two mutually exclusive modes, labelled in plain language rather
            // than as "Date" and "Repeat every week". A non-null date means
            // one-off; a non-empty day mask means weekly. The mode is derived
            // from the data, so the two can never both be set.
            SectionLabel("Which day")
            Spacer(Modifier.height(8.dp))

            // Mode 1: a single date.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (oneOffDate != null) TextPrimary else Surface)
                    .clickable { datePickerOpen = true }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.CalendarMonth,
                    contentDescription = null,
                    tint = if (oneOffDate != null) Background else TextPrimary,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Just one day",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (oneOffDate != null) Background else TextPrimary,
                    )
                    Text(
                        text = oneOffDate?.let { RoutineDates.oneOffLabel(it) }
                            ?: "Pick a date",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (oneOffDate != null) Background else TextSecondary,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (oneOffDate != null) {
                    "Runs once on this date, then turns itself off."
                } else {
                    "A one-off routine runs on a single date."
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )

            Spacer(Modifier.height(12.dp))

            // Mode 2: days of the week.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Surface)
                    .clickable {
                        // Tapping the card body switches to weekly without
                        // changing which days are selected.
                        if (oneOffDate != null) {
                            oneOffDate = null
                            oneOffMode = false
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "Days of the week",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (oneOffDate == null) TextPrimary else TextSecondary,
                )
                Text(
                    text = "Repeats every week on the days you pick.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
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
                                    haptics.performHapticFeedback(
                                        HapticFeedbackType.TextHandleMove,
                                    )
                                    // Tapping any day makes the routine
                                    // recurring, so the one-off date is dropped.
                                    oneOffMode = false
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
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        oneOffDate != null -> "Tap a day to switch to weekly."
                        days == DaysOfWeek.NONE -> "No days picked yet."
                        days == DaysOfWeek.ALL -> "Every day."
                        else -> "Weekly on ${describeDays(days)}."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
            } // if (!untimed)

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
                AppSwitch(
                    checked = useBuffer,
                    onCheckedChange = { useBuffer = it },
                )
            }

            Spacer(Modifier.height(20.dp))

            // --- Pause ----------------------------------------------------
            // Per-routine, because whether an interruption is a lifeline or an
            // escape hatch depends on the routine: a wind-down is fine to
            // pause, a ten-minute tidy is not.
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
                            "The run screen shows Pause and Resume"
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

            Spacer(Modifier.height(20.dp))

            // --- Tasks ----------------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("Tasks", modifier = Modifier.weight(1f))
                Text(
                    text = if (selectedIndices.isEmpty()) {
                        "Long-press to reorder"
                    } else {
                        "${selectedIndices.size} selected"
                    },
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
                        selectedIndices.clear()
                    },
                    onMove = { from, to ->
                        if (from != to) {
                            val moved = tasks.removeAt(from)
                            tasks.add(to, moved)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedIndices.clear()
                        }
                    },
                    selectedIndices = selectedIndices.toSet(),
                    onToggleSelect = { index ->
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (!selectedIndices.remove(index)) selectedIndices.add(index)
                    },
                )
            }

            // Bulk-delete bar, shown only while something is highlighted.
            if (selectedIndices.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surface)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${selectedIndices.size} selected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { selectedIndices.clear() }) {
                        Text("Cancel", color = TextSecondary)
                    }
                    TextButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            // Remove from the highest index down so earlier
                            // removals do not shift the indices still to go.
                            selectedIndices.sortedDescending().forEach { index ->
                                if (index in tasks.indices) tasks.removeAt(index)
                            }
                            selectedIndices.clear()
                        },
                    ) {
                        Text("Delete", color = TextPrimary)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Surface)
                    .clickable {
                        // If the window is already full, don't open the task
                        // editor — send the user to adjust the end time first.
                        if (remaining < MIN_TASK_MINUTES) {
                            fullPrompt = true
                        } else {
                            addingTask = true
                        }
                    }
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

    // --- Save progress overlay ---------------------------------------------
    // Writing the routine and its tasks is a database round-trip. On a cold
    // start it can take a moment, and the tap used to feel ignored. Covering the
    // screen acknowledges the tap, blocks a second (duplicate) submit, and
    // explains the wait. Not dismissible: the only sensible response is to wait.
    if (saving) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Background.copy(alpha = 0.86f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = TextPrimary)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Saving routine…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                    )
                }
            }
        }
    }

    // --- Window pickers ---------------------------------------------------
    // The same Material clock face as the intro steps, so the gesture is
    // identical wherever the user changes a time. The picker edits a local
    // copy and only commits on OK, so dragging past a refused window does not
    // fight the user.
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
                TimePicker(state = pickerState)
            },
            confirmButton = {
                TextButton(onClick = {
                    val picked = pickerState.hour * 60 + pickerState.minute
                    if (target == CreateTimeField.Start) {
                        applyStart(picked)
                    } else {
                        applyEnd(picked)
                    }
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
                    // Falls back to the suggested date (today/tomorrow) so OK
                    // always commits a date even if the picker reported no
                    // explicit selection.
                    val millis = dateState.selectedDateMillis ?: initialMillis
                    // Picking a date is picking a schedule, so an untimed
                    // routine becomes timed again.
                    untimed = false
                    oneOffMode = true
                    oneOffDate = Instant.ofEpochMilli(millis)
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                    // A one-off has no weekly days.
                    days = DaysOfWeek.NONE
                    datePickerOpen = false
                }) {
                    Text("OK", color = TextPrimary)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        // Clearing the date returns the routine to weekly.
                        oneOffMode = false
                        oneOffDate = null
                        datePickerOpen = false
                    }) {
                        Text("Recurring", color = TextSecondary)
                    }
                    TextButton(onClick = {
                        // Cancelling gives up the one-off choice unless a date
                        // was already set before the picker opened.
                        oneOffMode = oneOffDate != null
                        datePickerOpen = false
                    }) {
                        Text("Cancel", color = TextSecondary)
                    }
                }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    // --- Untimed length picker --------------------------------------------
    // Only an untimed routine has a length separate from a clock window; the
    // dialog clamps to a full day and never below what the tasks already claim.
    if (lengthPickerOpen) {
        LengthDialog(
            initialMinutes = untimedMinutes,
            claimedMinutes = claimed,
            onConfirm = { picked ->
                untimedMinutes = picked
                lengthPickerOpen = false
            },
            onDismiss = { lengthPickerOpen = false },
        )
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

    // --- Stock task duration confirm --------------------------------------
    // Double-tapping a stock task in step 4 opens this instead of adding it at
    // its saved length (a single tap does that). The user confirms — and can
    // tweak — how long the task takes, then it joins the routine. The wheels are
    // clamped to the room left in the window.
    val pending = pendingStock
    if (pending != null) {
        val maxForStock = (capacity - claimed).coerceAtLeast(MIN_TASK_MINUTES)
        StockDurationDialog(
            stock = pending,
            maxMinutes = maxForStock,
            onConfirm = { minutes ->
                tasks.add(
                    DraftTask(
                        name = pending.name,
                        minutes = minutes,
                        color = pending.color,
                        alarmEnabled = pending.alarmEnabled,
                        // Keep the stock task's icon so it survives into the run.
                        icon = pending.icon,
                    ),
                )
                pendingStock = null
            },
            onDismiss = { pendingStock = null },
        )
    }

    // --- "Window is full" prompt ------------------------------------------
    // Shown when the user tries to add a task with no room left, or when a
    // time change would leave no room for the tasks. The action jumps them to
    // the end-time field so they can extend the window.
    if (fullPrompt) {
        AlertDialog(
            onDismissRequest = { fullPrompt = false },
            containerColor = Surface,
            title = { Text("No room left", color = TextPrimary) },
            text = {
                Text(
                    text = if (tasks.size > capacity) {
                        "There are more tasks than minutes in this routine. " +
                            "Each task needs at least one minute, so make the " +
                            "window longer before adding more."
                    } else {
                        "Your tasks already fill this window. Adjust the end " +
                            "time to make room for another task."
                    },
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    fullPrompt = false
                    // Act as if the user tapped the end-time field.
                    pickerTarget = CreateTimeField.End
                }) {
                    Text("Adjust end time", color = TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { fullPrompt = false }) {
                    Text("Not now", color = TextSecondary)
                }
            },
        )
    }

    // --- Shorter-window confirmation --------------------------------------
    // The user picked a window shorter than the tasks need. Rather than
    // deleting tasks, we ask, then trim each task proportionally.
    val shrink = pendingShrink
    if (shrink != null) {
        val (newStart, newEnd) = shrink
        val newCapacity = TimeWindow.capacity(newStart, newEnd)
        AlertDialog(
            onDismissRequest = { pendingShrink = null },
            containerColor = Surface,
            title = { Text("Tasks take up too much time", color = TextPrimary) },
            text = {
                Text(
                    text = "This window is $newCapacity min but your tasks need " +
                        "$claimed min. Continue and each task will be shortened " +
                        "to fit?",
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = TimeWindow.shrinkToFit(
                        tasks.map { it.minutes },
                        newCapacity,
                    )
                    trimmed.forEachIndexed { index, minutes ->
                        tasks[index] = tasks[index].copy(minutes = minutes)
                    }
                    startMinute = newStart
                    endMinute = newEnd
                    pendingShrink = null
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
}

/**
 * The shell for the guided intro steps: the step content and a single big
 * button.
 *
 * The button label adapts to where the user is:
 *  - steps 1–3 say "Next";
 *  - step 4 says "Start adding tasks" until the user has added at least one
 *    task, then switches to "Finish" so the last action reads as the end of the
 *    flow rather than the start of it.
 *
 * There is deliberately no Back button here: the top bar already has a back
 * arrow and the system gesture does the same thing, so a second Back at the
 * bottom only competed with the one control that matters.
 *
 * The step content is given the remaining height and is responsible for its own
 * scrolling, so a long task list can never push the title or the button off the
 * screen.
 */
@Composable
private fun CreateStepScaffold(
    step: Int,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    hasTasks: Boolean,
    onNext: () -> Unit,
    content: @Composable () -> Unit,
) {
    // The step label lives in the AppTopBar, so this scaffold deliberately does
    // not repeat it — doing so showed "Step X of 4" twice on every guided step.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        // The content takes all the space between the top bar and the button and
        // scrolls internally, so the footer stays put.
        Box(Modifier.weight(1f)) {
            content()
        }
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(TextPrimary)
                .clickable(onClick = onNext)
                .padding(vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when {
                    step != 3 -> "Next"
                    hasTasks -> "Finish"
                    else -> "Start adding tasks"
                },
                style = MaterialTheme.typography.titleMedium,
                color = Background,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * A single big time question, answered with the standard Material clock face.
 *
 * This is the same circular dial people already know from the stock Android
 * clock app: tap the hour, then tap the minute. It replaced the custom 24-hour
 * dial, which was hard to aim at a specific time with.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BigTimeStep(
    title: String,
    subtitle: String,
    minuteOfDay: Int,
    onChange: (Int) -> Unit,
    footnote: String? = null,
) {
    val state = rememberTimePickerState(
        initialHour = minuteOfDay / 60,
        initialMinute = minuteOfDay % 60,
        is24Hour = true,
    )
    // Report user changes back so the parent's start/end state stays in sync.
    // Skip the very first emission: it merely echoes the initial value and
    // would otherwise re-run the parent's start/end logic on entry.
    var reported by remember { mutableStateOf(false) }
    LaunchedEffect(state.hour, state.minute) {
        if (reported) {
            onChange(state.hour * 60 + state.minute)
        } else {
            reported = true
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(24.dp))
        TimePicker(state = state)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Tap the hour, then the minute.",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
        if (footnote != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = footnote,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
    }
}

/**
 * The third intro step: which day does this routine run on?
 *
 * The old wording ("When does it repeat?") asked the user to think about
 * recurrence before they had decided whether they even wanted one. This asks
 * the concrete question instead — *which day* — and offers the two answers as
 * two clearly labelled modes:
 *
 *  - **Just one day** — a single date, shown as "Fri 28 Nov".
 *  - **Days of the week** — the MTWTFSS row, which repeats weekly.
 *
 * The mode is derived from the data rather than stored separately: a non-null
 * date means one-off, a non-empty day mask means weekly. That keeps the two
 * mutually exclusive by construction, which is what the domain already
 * requires.
 */
@Composable
private fun BigRepeatStep(
    oneOffDate: LocalDate?,
    days: Int,
    oneOffSelected: Boolean,
    onPickDate: () -> Unit,
    onToggleDay: (Int) -> Unit,
    onClearDate: () -> Unit,
) {
    // Highlighting follows the *choice* rather than the stored date: the row
    // lights up as soon as it is tapped, even while the date picker is open.
    val weekly = !oneOffSelected

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Which day?",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Run it once on a single date, or every week on the days " +
                "you choose.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(24.dp))

        // --- Mode 1: a single date -------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(if (!weekly) TextPrimary else Surface)
                .clickable(onClick = onPickDate)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.CalendarMonth,
                contentDescription = null,
                tint = if (!weekly) Background else TextPrimary,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Just one day",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (!weekly) Background else TextPrimary,
                )
                Text(
                    text = oneOffDate?.let { RoutineDates.oneOffLabel(it) }
                        ?: "Pick a date",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!weekly) Background else TextSecondary,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // --- Mode 2: days of the week ----------------------------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(if (weekly) Surface else Surface.copy(alpha = 0.5f))
                .clickable { if (!weekly) onClearDate() }
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text(
                text = "Days of the week",
                style = MaterialTheme.typography.bodyLarge,
                color = if (weekly) TextPrimary else TextSecondary,
            )
            Text(
                text = if (weekly) {
                    "Repeats every week on the days you pick."
                } else {
                    "Tap a day to switch to a weekly routine."
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DaysOfWeek.LETTER_LABELS.forEachIndexed { index, label ->
                    val bit = 1 shl index
                    val active = weekly && (days and bit) != 0
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(CircleShape)
                            .background(if (active) TextPrimary else Background)
                            .clickable { onToggleDay(bit) },
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
            Spacer(Modifier.height(10.dp))
            Text(
                text = when {
                    !weekly -> "Currently a one-off."
                    days == DaysOfWeek.NONE -> "No days picked yet."
                    days == DaysOfWeek.ALL -> "Every day."
                    else -> "Weekly on ${describeDays(days)}."
                },
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
    }
}

/**
 * The "no set time" switch, offered on the first step next to the two day
 * modes.
 *
 * Ticking it turns the routine into a checklist with a length instead of a
 * schedule: the two clock questions drop out of the flow, the day choice is
 * cleared, and the routine card grows a Run button. It is styled like the day
 * modes above it because it is the same kind of decision — how this routine is
 * anchored in time — and the answer is "not at all".
 */
@Composable
private fun UntimedToggle(untimed: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (untimed) TextPrimary else Surface.copy(alpha = 0.5f))
            .clickable { onToggle(!untimed) }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.CalendarMonth,
            contentDescription = null,
            tint = if (untimed) Background else TextSecondary,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "No set time",
                style = MaterialTheme.typography.bodyLarge,
                color = if (untimed) Background else TextPrimary,
            )
            Text(
                text = if (untimed) {
                    "Anytime — just a length. Start it with the Run button."
                } else {
                    "Skip the clock: no start time or day, just a length you run when you want."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (untimed) Background else TextSecondary,
            )
        }
    }
}

/**
 * The length question for an untimed routine: no clock, just how long the whole
 * thing should take.
 *
 * The wheels are the same [DurationWheels] the task editors use, so a routine
 * length feels like every other duration in the app. The hint underneath names
 * the length and what the staged tasks already need, because a length below
 * that renders the tasks at their minimums with an honest overrun rather than
 * quietly shortening them.
 */
@Composable
private fun BigDurationStep(
    minutes: Int,
    taskMinutes: Int,
    onChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "How long is it?",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "No start time — the routine is ready whenever you are.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(24.dp))
        DurationWheels(
            parts = TimeParts.fromMinutes(minutes),
            maxMinutes = UNTIMED_MAX_MINUTES,
            onPartsChange = { onChange(it.totalMinutes.coerceAtLeast(MIN_TASK_MINUTES)) },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (taskMinutes > 0) {
                "${formatDuration(minutes)} long · ${formatDuration(taskMinutes)} of tasks"
            } else {
                "${formatDuration(minutes)} long"
            },
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
    }
}

/**
 * The fourth intro step: pick the tasks the routine is built from.
 *
 * ## Layout
 *
 * The title and the "… left in the window" line are pinned at the top; only the body
 * below them scrolls. That fixes the old bug where the heading scrolled away
 * once the list grew past the screen, leaving the user staring at a bare list
 * with no idea what screen they were on.
 *
 * ## Picking
 *
 * Stock tasks are shown as a wrapping flow of varied-width chips — several per
 * row, sized to their label — so a long list stays compact instead of becoming
 * a tall column of identical full-width rows. A **single tap** adds the task
 * using the duration it already has; a **double tap** opens a duration-confirm
 * dialog (handled by the caller via [onPickStock]) so the user can set how long
 * it takes first.
 *
 * ## Tailoring
 *
 * The chips are grouped by [RoutineCategory], chosen from the start time and the
 * tasks already picked. The category's curated suggestions come first, then any
 * of the user's own stock tasks that were not already suggested. This is what
 * makes a morning routine offer "Meds / Shower / Breakfast" while a work routine
 * offers "Emails / Deep work".
 *
 * ## Reordering
 *
 * The staged tasks are listed above the picker and can be reordered by
 * long-pressing and dragging, so the routine's running order can be set before
 * the user ever reaches the full editor.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BigTaskPickStep(
    stockTasks: List<StockTask>,
    tasks: List<DraftTask>,
    capacity: Int,
    startMinute: Int,
    onQuickAddStock: (StockTask) -> Unit,
    onPickStock: (StockTask) -> Unit,
    onRemoveTask: (Int) -> Unit,
    onMoveTask: (from: Int, to: Int) -> Unit,
    onAddOwn: () -> Unit,
) {
    val claimed = tasks.sumOf { it.minutes }
    val remaining = (capacity - claimed).coerceAtLeast(0)

    // Classify from what the user has picked so far, falling back to the clock.
    val category = RoutineCategory.classify(tasks.map { it.name }, startMinute)
    val suggestions = RoutineCategory.suggestionsFor(category)

    // Match each curated suggestion to one of the user's stock tasks by name
    // (case-insensitive). A suggestion with no matching stock task is offered as
    // a fallback chip so the picker is never empty on a fresh install.
    val stockByName = stockTasks.associateBy { it.name.trim().lowercase() }
    val suggestedChips = suggestions.map { suggestion ->
        val match = stockByName[suggestion.name.trim().lowercase()]
        if (match != null) {
            StockChip(
                name = match.name,
                minutes = match.minutes,
                color = match.color,
                icon = match.icon,
                stock = match,
            )
        } else {
            StockChip(
                name = suggestion.name,
                minutes = suggestion.minutes,
                color = StockTaskColors.first().toArgb(),
                icon = suggestion.icon,
                stock = StockTask(
                    name = suggestion.name,
                    minutes = suggestion.minutes,
                    color = StockTaskColors.first().toArgb(),
                    icon = suggestion.icon,
                ),
            )
        }
    }
    // The user's own stock tasks that were not already suggested, so nothing is
    // hidden — they just sit under a quieter heading.
    val suggestedNames = suggestedChips.map { it.name.trim().lowercase() }.toSet()
    val otherChips = stockTasks
        .filter { it.name.trim().lowercase() !in suggestedNames }
        .map { stock ->
            StockChip(
                name = stock.name,
                minutes = stock.minutes,
                color = stock.color,
                icon = stock.icon,
                stock = stock,
            )
        }

    Column(Modifier.fillMaxWidth()) {
        // --- Pinned header -------------------------------------------
        Text(
            text = "Pick your tasks",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Tap a task to add it. Double-tap one to set how long it takes. " +
                "${formatDuration(remaining)} left in the window.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(12.dp))

        // --- Scrollable body -----------------------------------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // Staged tasks so far, reorderable.
            if (tasks.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("In this routine", modifier = Modifier.weight(1f))
                    Text(
                        text = "Long-press to reorder",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                ReorderableTaskList(
                    tasks = tasks,
                    onTap = {},
                    onRemove = onRemoveTask,
                    onMove = onMoveTask,
                )
                Spacer(Modifier.height(20.dp))
            }

            // Category-tailored suggestions.
            SectionLabel("${RoutineCategory.labelFor(category)} tasks")
            Spacer(Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestedChips.forEach { chip ->
                    StockTaskChip(
                        chip = chip,
                        picked = tasks.any { it.name == chip.name },
                        onClick = { onQuickAddStock(chip.stock) },
                        onDoubleClick = { onPickStock(chip.stock) },
                    )
                }
            }

            // Everything else the user has, so nothing is unreachable.
            if (otherChips.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                SectionLabel("Everything else")
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    otherChips.forEach { chip ->
                        StockTaskChip(
                            chip = chip,
                            picked = tasks.any { it.name == chip.name },
                            onClick = { onQuickAddStock(chip.stock) },
                            onDoubleClick = { onPickStock(chip.stock) },
                        )
                    }
                }
            }

        }

        // --- Add a task that is not in the stock list -----------------
        // Deliberately outside the scrolling picker: with a long chip list this
        // control used to sit below the fold, so making a custom task meant
        // scrolling first. Pinned here it is always one tap away.
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Surface)
                .clickable(onClick = onAddOwn)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Add your own",
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
        }
    }
}

/** Turns a day mask into "Mon, Wed and Fri". */
private fun describeDays(mask: Int): String {
    val names = DaysOfWeek.SHORT_LABELS.filterIndexed { index, _ ->
        (mask and (1 shl index)) != 0
    }
    return when (names.size) {
        0 -> "no days"
        1 -> names.first()
        2 -> "${names[0]} and ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
}

/**
 * A small uppercase section eyebrow ("TASKS", "WHEN").
 *
 * Secondary colour and the label styles' own tracking are the whole emphasis
 * here; adding weight on top of an 11sp cap-height label is what made these read
 * as a second set of headings.
 */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        modifier = modifier,
    )
}

/**
 * A vertical list of staged tasks that can be reordered by long-pressing a row
 * and dragging it up or down.
 *
 * The list is a plain [Column] rather than a `LazyColumn` because the screen is
 * already inside a vertical scroll container, and a routine is short enough
 * that laying every row out is cheap.
 *
 * Reordering uses the shared [ReorderState] engine: the held row follows the
 * finger and the others slide out of the way, then the move is committed once
 * on release. This replaced the old swap-on-half-row approach, which never
 * moved the dragged row and could drop the gesture mid-drag.
 *
 * Tapping a row toggles it in [selectedIndices]; when anything is selected the
 * caller shows a bulk-delete bar. Tapping with nothing selected opens the
 * editor, so the two gestures never fight.
 */
@Composable
private fun ReorderableTaskList(
    tasks: List<DraftTask>,
    onTap: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    selectedIndices: Set<Int> = emptySet(),
    onToggleSelect: (Int) -> Unit = {},
) {
    val rowHeight = 56.dp
    val density = LocalDensity.current
    val rowHeightPx = with(density) { rowHeight.toPx() }

    val reorder = rememberReorderState(
        itemCount = { tasks.size },
        rowHeightPx = { rowHeightPx },
        onMove = onMove,
    )

    Column(Modifier.fillMaxWidth()) {
        tasks.forEachIndexed { index, task ->
            val isDragging = reorder.draggingIndex == index
            val isSelected = index in selectedIndices

            val shiftPx = if (isDragging) reorder.dragOffset else reorder.shiftFor(index)
            val animatedShift by animateDpAsState(
                targetValue = with(density) { shiftPx.toDp() },
                animationSpec = tween(durationMillis = if (isDragging) 0 else 140),
                label = "draftRowShift",
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .offset(y = animatedShift)
                    .zIndex(if (isDragging) 1f else 0f)
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
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
                                shape = RoundedCornerShape(14.dp),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .reorderableRow(
                        state = reorder,
                        index = index,
                        onTap = {
                            if (selectedIndices.isEmpty()) onTap(index) else onToggleSelect(index)
                        },
                    )
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
                if (isSelected) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Selected",
                        tint = SelectionOutline,
                        modifier = Modifier.size(20.dp),
                    )
                }
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
 * It is a full-screen sheet rather than a small dialog: the wheels need room to
 * breathe, and the app's own background colour is used so it reads as part of
 * the flow instead of a floating box. The stock-task presets that used to live
 * here were removed — the picker behind this sheet already lists every stock
 * task, so repeating them was redundant.
 *
 * @param initial the draft to edit, or `null` to create a new one.
 * @param maxMinutes the longest duration this task may be given without
 *   over-subscribing the routine window. The wheels are clamped to this.
 * @param stockTasks the user's reusable tasks (kept for signature parity with
 *   the plan screen; no longer shown as presets here).
 * @param onConfirm called with the finished draft.
 * @param onDismiss called when the sheet closes without confirming.
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
    // the limit shrinks while the sheet is open.
    val clampedMinutes = parts.totalMinutes.coerceIn(1, maxMinutes.coerceAtLeast(1))

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
                    text = if (initial == null) "New task" else "Edit task",
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

                Spacer(Modifier.height(16.dp))
            }

            // --- Footer: cancel / confirm -----------------------------
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
                            onConfirm(
                                DraftTask(
                                    name = name.trim().ifEmpty { PlacedTask.DEFAULT_NAME },
                                    minutes = clampedMinutes,
                                    color = color,
                                    alarmEnabled = alarmEnabled,
                                    // Editing keeps the original icon; a brand-new
                                    // hand-typed task falls back to the generic one.
                                    icon = initial?.icon ?: IconKeys.TASK,
                                ),
                            )
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (initial == null) "Add task" else "Save",
                        style = MaterialTheme.typography.titleMedium,
                        color = Background,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * The confirm step shown when the user double-taps a stock task in step 4.
 *
 * A single tap already adds the task at its saved length; a double tap means the
 * user wants to decide how long it takes first, so this asks. The duration is
 * pre-filled from the stock task's own default and edited with the same
 * [DurationWheels] used everywhere else, so setting the time is one familiar
 * gesture. The wheels are clamped to the room left in the window, so the user
 * cannot over-fill the routine here.
 *
 * @param stock the stock task being added.
 * @param maxMinutes the most this task may take without over-filling the window.
 * @param onConfirm called with the chosen duration in whole minutes.
 * @param onDismiss called when the user backs out without adding.
 */
@Composable
private fun StockDurationDialog(
    stock: StockTask,
    maxMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val cap = maxMinutes.coerceAtLeast(1)
    var parts by remember(stock.name) {
        mutableStateOf(TimeParts.fromMinutes(stock.minutes.coerceIn(1, cap)))
    }
    val minutes = parts.totalMinutes.coerceIn(1, cap)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = StockTaskIcons.iconFor(stock.icon),
                    contentDescription = null,
                    tint = Color(stock.color),
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stock.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = "How long will this take?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(12.dp))
                DurationWheels(
                    parts = parts,
                    maxMinutes = cap,
                    onPartsChange = { parts = it },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (minutes >= cap) {
                        "Routine is full at $cap min"
                    } else {
                        "Up to $cap min available"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onConfirm(minutes)
                },
            ) {
                Text("Add task", color = TextPrimary)
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
 * The length editor for an untimed routine.
 *
 * Uses the same [DurationWheels] as every other duration in the app, so a
 * routine's length is set with a gesture the user already knows. The tasks'
 * claimed minutes are shown underneath: shortening the routine below what the
 * tasks need is allowed (the plan then reports an honest overrun) but it should
 * never happen by surprise.
 */
@Composable
private fun LengthDialog(
    initialMinutes: Int,
    claimedMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var parts by remember { mutableStateOf(TimeParts.fromMinutes(initialMinutes)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(
                text = "How long is it?",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = "No start time — this is the total length.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(12.dp))
                DurationWheels(
                    parts = parts,
                    maxMinutes = UNTIMED_MAX_MINUTES,
                    onPartsChange = { parts = it },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (claimedMinutes > 0) {
                        "${formatDuration(claimedMinutes)} of tasks"
                    } else {
                        "No tasks yet"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(parts.totalMinutes.coerceAtLeast(MIN_TASK_MINUTES))
                },
            ) {
                Text("OK", color = TextPrimary)
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

/**
 * Formats a whole number of minutes as a human phrase, e.g. "45 minutes",
 * "1 hour", "1 hour 15 minutes", "2 hours". Used for both the "That's in …"
 * line and the window summaries so they read consistently.
 */
private fun formatDuration(totalMinutes: Int): String {
    val safe = totalMinutes.coerceAtLeast(0)
    val hours = safe / 60
    val minutes = safe % 60
    return when {
        hours > 0 && minutes > 0 -> "$hours hour${plural(hours)} $minutes minute${plural(minutes)}"
        hours > 0 -> "$hours hour${plural(hours)}"
        else -> "$minutes minute${plural(minutes)}"
    }
}

/**
 * A short "how long until it starts" line for the start-time step, e.g.
 * "That's in 2 hours 15 minutes." or "That's in 40 minutes."
 *
 * The gap is measured from now to the routine's start on its scheduled day. It
 * is only meaningful when the start is less than 24 hours away, so anything
 * further out (or already in the past) returns `null` and the caller shows
 * nothing rather than a misleading figure.
 *
 * @param startMinuteOfDay the routine's start, minutes since midnight.
 * @param oneOffDate the single date the routine runs on, or `null` for weekly.
 */
private fun timeUntilStart(
    startMinuteOfDay: Int,
    oneOffDate: LocalDate?,
): String? {
    val now = java.time.LocalDateTime.now()
    val today = now.toLocalDate()
    // For a weekly routine the next run is today if the start is still ahead,
    // otherwise tomorrow. For a one-off it is the chosen date.
    val startDate = oneOffDate ?: run {
        val startToday = today.atStartOfDay().plusMinutes(startMinuteOfDay.toLong())
        if (startToday.isAfter(now)) today else today.plusDays(1)
    }
    val start = startDate.atStartOfDay().plusMinutes(startMinuteOfDay.toLong())
    val minutesUntil = java.time.Duration.between(now, start).toMinutes()
    // Only show it when it is in the future and under a day away.
    if (minutesUntil <= 0 || minutesUntil >= 24 * 60) return null
    return "That's in ${formatDuration(minutesUntil.toInt())}."
}

/** "s" for anything but 1, so "1 hour" / "2 hours". */
private fun plural(value: Int): String = if (value == 1) "" else "s"

/** Default duration offered for a staged task. */
private const val DEFAULT_TASK_MINUTES: Int = 10

/** Shortest duration a staged task may have. */
private const val MIN_TASK_MINUTES: Int = 1

/** Default length offered for a new untimed routine. */
private const val UNTIMED_DEFAULT_MINUTES: Int = 45

/**
 * Longest an untimed routine may be. Just under a full day, matching the
 * `1..1439` range [com.adhd.routineplanner.domain.Routine.untimedDurationMinutes]
 * clamps to, so the picker can never offer a length that would be rewritten on
 * save.
 */
private const val UNTIMED_MAX_MINUTES: Int = 24 * 60 - 1

/**
 * The wizard's pages, as `step` numbers (`4` is the fine-tune editor).
 *
 * A timed routine asks start → end → day → tasks; an untimed one drops the two
 * clock questions and asks length → tasks. Keeping one canonical numbering and
 * listing the pages per mode is what lets Back land on the page the user
 * actually saw, rather than on a step that does not exist in this mode.
 */
private val TIMED_PAGES: List<Int> = listOf(0, 1, 2, 3)
private val UNTIMED_PAGES: List<Int> = listOf(0, 1, 3)

@Preview(name = "Routine create screen", showBackground = true, backgroundColor = 0xFF0A1116)
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
