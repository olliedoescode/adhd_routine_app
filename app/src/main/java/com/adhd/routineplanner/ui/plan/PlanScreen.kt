package com.adhd.routineplanner.ui.plan

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adhd.routineplanner.domain.Grid
import com.adhd.routineplanner.domain.Point
import com.adhd.routineplanner.domain.Routine
import com.adhd.routineplanner.domain.Schedule
import com.adhd.routineplanner.domain.TaskDescription
import com.adhd.routineplanner.ui.routines.RoutineSettingsSheet
import com.adhd.routineplanner.ui.theme.AdhdRoutineAppTheme
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.Surface
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary
import com.adhd.routineplanner.viewmodel.PlanUiState
import com.adhd.routineplanner.viewmodel.PlanViewModel
import kotlin.math.roundToInt

/** Duration of the cell-size animation when zooming (spec §14.3). */
private const val ZOOM_ANIMATION_MS: Int = 200

/** Auto-scroll starts when the finger is this close to the grid's top/bottom. */
private val AutoScrollEdge = 72.dp

/**
 * Horizontal breathing room around the grid so Samsung's edge-swipe back
 * gesture does not fight the grid (spec §17, plan 10.3).
 */
private val GridEdgePadding = 16.dp

/**
 * Shared drag geometry, written by the grid and read by the tray drag.
 *
 * The grid knows its own bounds, cell size and scroll offset; the tray only
 * knows finger deltas. This holder lets a tray drag map an absolute root-space
 * finger position to a minute on the grid, so drag-in lands where the user
 * drops it (spec §15.5).
 */
private class DragGeometry {
    /** Grid bounds in root coordinates. */
    var gridBounds: Rect = Rect.Zero

    /** Current cell edge in pixels. */
    var cellPx: Float = 0f

    /** Current column count. */
    var cols: Int = Grid.DEFAULT_COLS

    /** Current vertical scroll offset of the grid. */
    var scrollOffset: Int = 0

    /** Gutter width in pixels. */
    var gutterPx: Float = 0f

    /** Maps a root-space position to a minute, or null when outside the grid. */
    fun minuteAt(root: Offset): Int? {
        if (cellPx <= 0f || cols <= 0) return null
        if (!gridBounds.contains(root)) return null
        val localX = root.x - gridBounds.left
        val localY = root.y - gridBounds.top + scrollOffset
        if (localX < gutterPx) return null
        return Grid.cellAt(Point(localX - gutterPx, localY), cellPx, cols)
    }

    /** True when [root] is inside the grid bounds. */
    fun contains(root: Offset): Boolean = gridBounds.contains(root)
}

/**
 * The plan screen (spec §15.3): top bar, scrolling grid, resize strip, tray.
 *
 * Phase 4 adds pinch zoom and zoom buttons. Phase 5 adds tap-to-select and the
 * resize strip. Phase 6 adds long-press drag and drop, tray drag-in, delete and
 * auto-scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    onBack: () -> Unit,
    onRun: (Long) -> Unit,
    viewModel: PlanViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val stockTasks by viewModel.stockTasks.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }

    // New-task dialog visibility.
    var creatingTask by remember { mutableStateOf(false) }
    // Routine settings sheet visibility.
    var settingsOpen by remember { mutableStateOf(false) }
    // Whether the tasks are shown as a reorderable list instead of the grid.
    // The list is the easier way to read and reorder a routine; the grid is the
    // better way to see how it fills its window, so both are offered.
    var listView by remember { mutableStateOf(false) }

    // Shared drag geometry and the finger position in root coordinates.
    val geometry = remember { DragGeometry() }
    var fingerPosition by remember { mutableStateOf(Offset.Zero) }
    // Root-space origin of the content Box, so the ghost (positioned in the
    // Box's local space) lines up with the root-space finger position.
    var contentOrigin by remember { mutableStateOf(Offset.Zero) }

    // Offer undo whenever a new snapshot appears (spec §16).
    LaunchedEffect(state.undo) {
        val snapshot = state.undo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "${snapshot.label} applied",
            actionLabel = "Undo",
            withDismissAction = true,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> viewModel.undoLast()
            SnackbarResult.Dismissed -> viewModel.dismissUndo()
        }
    }

    Scaffold(
        containerColor = Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier.clickable(
                            enabled = state.routine != null && !state.runActive,
                            onClick = { settingsOpen = true },
                        ),
                    ) {
                        Text(
                            text = state.routine?.name ?: "Routine",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                        )
                        Text(
                            text = summaryLine(state),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary,
                        )
                    }
                },
                actions = {
                    // Add-task lives in the top bar so it is always visible and
                    // easy to reach, rather than tucked into the bottom tray.
                    val canAdd = !state.runActive &&
                        state.overrunMinutes == 0 &&
                        state.totalMinutes < state.capacityMinutes
                    IconButton(
                        onClick = { creatingTask = true },
                        enabled = canAdd,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add task",
                            tint = if (canAdd) TextPrimary else TextSecondary,
                        )
                    }
                    IconButton(
                        onClick = {
                            // Start the run for real first (session + ongoing
                            // notification + takeover + vibration), then open
                            // the run screen. This makes the manual button a
                            // full test of the scheduled-alarm path.
                            viewModel.startRunNow()
                            state.routine?.let { onRun(it.id) }
                        },
                        enabled = state.tasks.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Run routine",
                            tint = if (state.tasks.isNotEmpty()) TextPrimary else TextSecondary,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More options",
                                tint = TextPrimary,
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Routine settings") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Settings, contentDescription = null)
                                },
                                enabled = state.routine != null && !state.runActive,
                                onClick = {
                                    menuOpen = false
                                    settingsOpen = true
                                },
                            )
                            // Switch between the grid and the reorderable list.
                            // The list is the easier way to reorder and read a
                            // routine; the grid shows how it fills its window.
                            DropdownMenuItem(
                                text = {
                                    Text(if (listView) "Grid view" else "List view")
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (listView) {
                                            Icons.Filled.GridView
                                        } else {
                                            Icons.Filled.ViewList
                                        },
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    listView = !listView
                                },
                            )
                            // Every gesture needs a button equivalent (spec §18).
                            DropdownMenuItem(
                                text = { Text("Zoom in") },
                                leadingIcon = {
                                    Icon(Icons.Filled.ZoomIn, contentDescription = null)
                                },
                                enabled = state.cols != Grid.ZOOM_STEPS.last(),
                                onClick = {
                                    menuOpen = false
                                    viewModel.zoomIn()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Zoom out") },
                                leadingIcon = {
                                    Icon(Icons.Filled.ZoomOut, contentDescription = null)
                                },
                                enabled = state.cols != Grid.ZOOM_STEPS.first(),
                                onClick = {
                                    menuOpen = false
                                    viewModel.zoomOut()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface),
            )
        },
        bottomBar = {
            // The tray is hidden while a run is active: editing is disabled so
            // the running timeline cannot change underneath the user
            // (spec section 8.7).
            if (!state.runActive) {
                TaskTray(
                    removeActive = state.preview.overTray && state.preview.dragTaskId != null,
                )
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (state.runActive) {
                RunLockBanner()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .onGloballyPositioned { contentOrigin = it.boundsInRoot().topLeft },
            ) {
            if (state.loading) {
                Text(
                    text = "Loading…",
                    color = TextSecondary,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else if (listView && !state.runActive) {
                // List view: each task's core information, reorderable by
                // long-press and drag. Tapping a row opens the same editor the
                // grid uses.
                TaskListEditor(
                    tasks = state.tasks,
                    routineStartMinuteOfDay = state.routine?.startMinuteOfDay ?: 0,
                    onMove = { from, to ->
                        // A move by one slot is the same operation the grid's
                        // drag performs, so it goes through the same undo path.
                        if (to > from) viewModel.moveTask(state.tasks[from].id, 1)
                        else viewModel.moveTask(state.tasks[from].id, -1)
                    },
                    onTap = { task -> viewModel.select(task.id) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ScrollingGrid(
                    state = state,
                    geometry = geometry,
                    onFingerPosition = { fingerPosition = it },
                    onSelect = viewModel::select,
                    onPinchStep = viewModel::consumePinch,
                    onDragStart = viewModel::startTaskDrag,
                    onDragMove = viewModel::dragMove,
                    onDragEnd = viewModel::dropDrag,
                    onDragCancel = viewModel::cancelDrag,
                    modifier = Modifier.fillMaxSize(),
                )
                // Empty state: a routine with no tasks (plan 10.6).
                if (state.tasks.isEmpty() && !state.runActive) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "No tasks yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Tap + above to add the first thing you need to do.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            // Floating ghost, drawn above everything (spec §15.5). The finger
            // is tracked in root space; convert to this Box's local space.
            val ghost = state.dragGhostTask
            if (ghost != null) {
                DragGhost(
                    task = ghost,
                    fingerPosition = fingerPosition - contentOrigin,
                    overTray = state.preview.overTray,
                )
            }

            // Tapping a task opens the popup editor: rename it and set its
            // duration with the scrollable h/m/s picker. Suppressed while a run
            // is active (spec section 8.7).
            val selected = state.selectedTask
            if (selected != null && !state.runActive) {
                TaskEditDialog(
                    task = selected,
                    maxMinutes = state.maxDurationFor(taskId = selected.id),
                    onRename = { name -> viewModel.renameTask(selected.id, name) },
                    onColorChange = { color -> viewModel.setTaskColor(selected.id, color) },
                    onDurationChange = { minutes ->
                        viewModel.setTaskDuration(selected.id, minutes)
                    },
                    onDelete = {
                        viewModel.deleteTask(selected.id)
                        viewModel.clearSelection()
                    },
                    onDismiss = viewModel::clearSelection,
                    onAlarmChange = { enabled -> viewModel.setTaskAlarm(selected.id, enabled) },
                )
            }
            }
        }

        // New-task dialog. The duration wheels are clamped to whatever the
        // routine can still absorb, so a new task can never over-subscribe it.
        if (creatingTask) {
            TaskCreateDialog(
                maxMinutes = state.maxDurationFor(taskId = null),
                onCreate = { name, minutes, color, alarmEnabled ->
                    viewModel.createTask(name, minutes, color, alarmEnabled)
                },
                onDismiss = { creatingTask = false },
                stockTasks = stockTasks,
            )
        }

        // Routine settings bottom sheet (spec §15.7). Opened from the title or
        // the overflow menu.
        val routine = state.routine
        if (settingsOpen && routine != null) {
            RoutineSettingsSheet(
                routine = routine,
                onSave = { updated ->
                    viewModel.saveRoutine(updated)
                    settingsOpen = false
                },
                onDismiss = { settingsOpen = false },
            )
        }
    }
}

/** Banner shown on the plan screen while this routine is running (spec §8.7). */
@Composable
private fun RunLockBanner() {
    Text(
        text = "This routine is running — editing is locked until the run ends.",
        color = TextSecondary,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * The grid inside a vertical scroll container (spec §14.6). The Canvas is sized
 * to `rows * cellPx`; only visible rows are drawn.
 *
 * Pinch zoom steps through [Grid.ZOOM_STEPS] and keeps the focal minute
 * stationary by adjusting the scroll offset (spec §14.3, plan 4.2). Long-press
 * starts a task drag (spec §17); a second finger cancels it.
 */
@Composable
private fun ScrollingGrid(
    state: PlanUiState,
    geometry: DragGeometry,
    onFingerPosition: (Offset) -> Unit,
    onSelect: (Long?) -> Unit,
    onPinchStep: (Float) -> Float?,
    onDragStart: (Long) -> Unit,
    onDragMove: (minute: Int?, overTray: Boolean) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

    val gutterDp = GutterWidth

    // Measure the width actually given to the grid rather than assuming the
    // whole screen width. The grid sits inside the Scaffold's content padding
    // and this composable's own [GridEdgePadding], so using `screenWidthDp`
    // made the cells too wide and pushed the last column off the right edge.
    var measuredWidthPx by remember { mutableIntStateOf(0) }
    val measuredWidthDp = with(density) { measuredWidthPx.toDp() }

    // Animate the cell size so a zoom step reflows smoothly (plan 4.3).
    val targetCellDp = remember(measuredWidthDp, state.cols) {
        val available = (measuredWidthDp - gutterDp).value
        (available / state.cols).coerceAtLeast(1f).dp
    }
    val cellDp by animateDpAsState(
        targetValue = targetCellDp,
        animationSpec = tween(durationMillis = ZOOM_ANIMATION_MS),
        label = "cellSize",
    )
    val cellPx = with(density) { cellDp.toPx() }

    val scrollState = rememberScrollState()
    val visibleTop = scrollState.value.toFloat()
    val visibleBottom = visibleTop + with(density) { configuration.screenHeightDp.dp.toPx() }

    // Accumulated pinch scale for the duration of this gesture. It remains
    // cumulative so repeated spread/pinch movements can cross several zoom steps
    // without lifting the fingers.
    var accumulatedScale by remember { mutableFloatStateOf(1f) }
    // True while a pinch is in flight, so a stray tap does not select.
    var pinching by remember { mutableStateOf(false) }
    // True while a long-press task drag is in flight. Shared between the drag
    // detector and the pinch detector so a second finger cancels the drag.
    var draggingNow by remember { mutableStateOf(false) }
    // Fresh values for the pinch detector, which is keyed on Unit so it is not
    // recreated mid-gesture. Anything it reads that can change must come through
    // a rememberUpdatedState holder, or it would capture a stale value.
    val colsNow by rememberUpdatedState(state.cols)
    val cellPxNow by rememberUpdatedState(cellPx)
    val onPinchStepNow by rememberUpdatedState(onPinchStep)
    val onDragCancelNow by rememberUpdatedState(onDragCancel)
    // Cumulative root-space position for grid drags. Using drag deltas avoids
    // stale position values inside detectDragGesturesAfterLongPress.
    var dragRoot by remember { mutableStateOf(Offset.Zero) }
    // Last minute the finger crossed, so a haptic tick fires once per cell
    // rather than once per pointer event (spec §18).
    var lastDragMinute by remember { mutableStateOf<Int?>(null) }

    // Remember the cell size so a zoom step can keep the focal minute still.
    var previousCellPx by remember { mutableFloatStateOf(cellPx) }
    var focalMinute by remember { mutableFloatStateOf(0f) }

    val gutterPx = with(density) { gutterDp.toPx() }
    val autoScrollPx = with(density) { AutoScrollEdge.toPx() }

    // Keep the shared geometry current for tray drags.
    geometry.cellPx = cellPx
    geometry.cols = state.cols
    geometry.scrollOffset = scrollState.value
    geometry.gutterPx = gutterPx

    // One spoken summary of the whole grid, since the blocks are drawn on a
    // Canvas and have no semantics of their own (spec §18).
    val gridDescription = remember(state.tasks, state.routine) {
        val routine = state.routine
        val routineStart = routine?.startMinuteOfDay ?: 0
        if (state.tasks.isEmpty() || routine == null) {
            "Empty routine. Tap Add task to add one."
        } else {
            val placed = state.tasks.map { it.toPlacedTask() }
            val starts = Schedule.startMinutes(routine, placed)
            val renderedPerTask = IntArray(placed.size) { i -> starts[i + 1] - starts[i] }
            buildString {
                append("Routine grid, ${state.tasks.size} tasks, ")
                append(TaskDescription.length(Schedule.totalMinutes(placed)))
                append(". ")
                state.tasks.forEachIndexed { index, task ->
                    if (index > 0) append("; ")
                    append(
                        TaskDescription.describeAtOffset(
                            name = task.name,
                            routineStartMinuteOfDay = routineStart,
                            startOffset = starts[index],
                            minutes = renderedPerTask[index],
                        ),
                    )
                }
            }
        }
    }

    // After a zoom step, restore the scroll offset so the focal minute stays
    // under the same screen position (plan 4.2).
    LaunchedEffect(cellPx) {
        val before = previousCellPx
        previousCellPx = cellPx
        if (before <= 0f || before == cellPx) return@LaunchedEffect
        val target = (focalMinute * cellPx).roundToInt().coerceAtLeast(0)
        scrollState.scrollTo(target)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // A little horizontal padding keeps Samsung's edge-swipe back
            // gesture off the grid (spec §17, plan 10.3).
            .padding(horizontal = GridEdgePadding)
            .semantics { contentDescription = gridDescription }
            .onGloballyPositioned {
                geometry.gridBounds = it.boundsInRoot()
                measuredWidthPx = it.size.width
            }
            .verticalScroll(scrollState)
            // Keyed on Unit so the detector is NOT recreated when the column
            // count changes mid-gesture. Recreating it would cancel the in-flight
            // pinch, which is why a continuous spread only ever stepped once.
            .pointerInput(Unit) {
                // `state` is captured at composition time and is stale here, so
                // the drag-cancel check uses a local flag written by the drag
                // detector instead of reading state.preview.
                detectTransformGestures { centroid, _, zoom, _ ->
                    if (zoom == 1f) return@detectTransformGestures
                    // A second finger during a drag cancels it (spec §17).
                    if (draggingNow) {
                        onDragCancelNow()
                        draggingNow = false
                        return@detectTransformGestures
                    }
                    pinching = true
                    val localY = centroid.y + scrollState.value
                    focalMinute = (localY / cellPxNow).coerceAtLeast(0f)
                    // Accumulate the raw pinch scale and consume as many steps as
                    // it contains, so a single continuous spread/pinch can cross
                    // several zoom levels without lifting the fingers.
                    accumulatedScale *= zoom
                    val remaining = onPinchStepNow(accumulatedScale)
                    if (remaining != null) {
                        accumulatedScale = remaining
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } else if (
                        (zoom > 1f && colsNow == Grid.ZOOM_STEPS.last()) ||
                        (zoom < 1f && colsNow == Grid.ZOOM_STEPS.first())
                    ) {
                        // At a zoom limit, discard further movement in that same
                        // direction so reversing immediately becomes usable.
                        accumulatedScale = 1f
                    }
                }
            }
            .pointerInput(state.tasks, cellPx, state.cols) {
                detectTapGestures { offset ->
                    if (pinching) {
                        pinching = false
                        return@detectTapGestures
                    }
                    if (offset.x < gutterPx) return@detectTapGestures
                    // offset is viewport-local because the grid lives inside a
                    // verticalScroll, so add the scroll offset to reach content space.
                    val minute = Grid.cellAt(
                        pos = Point(offset.x - gutterPx, offset.y + scrollState.value),
                        cellPx = cellPx,
                        cols = state.cols,
                    )
                    val routine = state.routine ?: return@detectTapGestures
                    val starts = Schedule.startMinutes(routine, state.tasks.map { it.toPlacedTask() })
                    val index = Schedule.taskAtMinute(starts, state.tasks.size, minute)
                    onSelect(index?.let { state.tasks[it].id })
                }
            }
            .pointerInput(state.tasks, cellPx, state.cols) {
                // `state` is captured at composition time, so it is stale inside
                // this long-lived gesture lambda. Track whether a drag actually
                // started with a local flag instead of reading state.preview.
                var dragging = false
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        dragging = false
                        lastDragMinute = null
                        if (offset.x < gutterPx) return@detectDragGesturesAfterLongPress
                        dragRoot = geometry.gridBounds.topLeft + offset
                        // offset is viewport-local because the grid lives inside a
                        // verticalScroll, so add the scroll offset to reach content
                        // space. Without this the hit-test misses once scrolled and
                        // the drag never starts.
                        val minute = Grid.cellAt(
                            pos = Point(offset.x - gutterPx, offset.y + scrollState.value),
                            cellPx = cellPx,
                            cols = state.cols,
                        )
                        val routine = state.routine
                            ?: return@detectDragGesturesAfterLongPress
                        val starts =
                            Schedule.startMinutes(routine, state.tasks.map { it.toPlacedTask() })
                        val index = Schedule.taskAtMinute(starts, state.tasks.size, minute)
                        if (index != null) {
                            dragging = true
                            draggingNow = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDragStart(state.tasks[index].id)
                        }
                    },
                    onDragEnd = {
                        if (dragging) {
                            // Confirm on drop (spec §18).
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDragEnd()
                        }
                        dragging = false
                        draggingNow = false
                    },
                    onDragCancel = {
                        if (dragging) onDragCancel()
                        dragging = false
                        draggingNow = false
                    },
                ) { change, dragAmount ->
                    if (!dragging) return@detectDragGesturesAfterLongPress
                    change.consume()
                    // Track the finger in root space for the ghost and hit-test.
                    dragRoot += dragAmount
                    val root = dragRoot
                    onFingerPosition(root)
                    val minute = geometry.minuteAt(root)
                    // Below the grid means the finger is over the tray, which
                    // turns the drop into a delete (spec §15.5).
                    val overTray = root.y > geometry.gridBounds.bottom
                    // Tick once per cell crossed (spec §18).
                    if (minute != null && minute != lastDragMinute) {
                        lastDragMinute = minute
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    onDragMove(minute, overTray)
                    if (!overTray) {
                        autoScroll(root, geometry.gridBounds, scrollState, autoScrollPx, cellPx)
                    }
                }
            },
    ) {
        GridCanvas(
            tasks = state.displayTasks,
            cols = state.cols,
            cellPx = cellPx,
            routineStartMinuteOfDay = state.routine?.startMinuteOfDay ?: 0,
            capacityMinutes = state.capacityMinutes,
            selectedId = state.selectedId,
            preview = dragPreviewFor(state),
            visibleTopPx = visibleTop,
            visibleBottomPx = visibleBottom,
            useBuffer = state.routine?.useBuffer ?: true,
        )
    }
}

/**
 * Builds the [DragPreview] for the grid from the current UI state.
 *
 * The task list is live-reordered while dragging (the ghost is clipped into its
 * slot and the other blocks shift aside), so no dashed "origin" placeholder is
 * drawn — the gap has already closed. Only the moving block and its insertion
 * marker are shown.
 */
private fun dragPreviewFor(state: PlanUiState): DragPreview? {
    val ghost = state.dragGhostTask ?: return null
    val minute = state.preview.dragInsertionMinute ?: return null
    return DragPreview(
        task = ghost,
        insertionMinute = minute,
        originMinute = null,
        overTray = state.preview.overTray,
    )
}

/**
 * Scrolls the grid when the finger nears the top or bottom edge (spec §15.5).
 * Runs on the drag callback, so it needs no coroutine.
 */
private fun autoScroll(
    fingerRoot: Offset,
    gridBounds: Rect,
    scrollState: ScrollState,
    edgePx: Float,
    cellPx: Float,
) {
    val localY = fingerRoot.y - gridBounds.top
    val viewport = scrollState.viewportSize.toFloat()
    if (viewport <= 0f) return
    val step = (cellPx / 2f).roundToInt().toFloat()
    when {
        localY < edgePx -> scrollState.dispatchRawDelta(-step)
        localY > viewport - edgePx -> scrollState.dispatchRawDelta(step)
    }
}

/** "7:00 – 8:10 · 70 min · 5 min buffer" under the routine name. */
private fun summaryLine(state: PlanUiState): String {
    val routine = state.routine ?: return ""
    if (state.tasks.isEmpty()) return "Empty routine"
    val start = Schedule.formatMinuteOfDay(routine.startMinuteOfDay)
    val end = Schedule.formatMinuteOfDay(routine.endMinuteOfDay)
    val base = "$start – $end · ${state.totalMinutes} min"
    return when {
        state.overrunMinutes > 0 -> "$base · ${state.overrunMinutes} min over"
        state.bufferMinutes > 0 -> "$base · ${state.bufferMinutes} min buffer"
        else -> base
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

private fun previewState(): PlanUiState {
    val tasks = listOf(
        GridTask(1, 10, 0, "Shower", 0xFF4F8EF7.toInt(), alarmEnabled = true),
        GridTask(2, 15, 1, "Breakfast", 0xFFF2994A.toInt()),
        GridTask(3, 5, 2, "Get dressed", 0xFF34C7A1.toInt()),
        GridTask(4, 10, 3, "Meditate", 0xFF9B6BF2.toInt()),
    )
    return PlanUiState(
        routine = Routine(
            id = 1,
            name = "Morning",
            startMinuteOfDay = 7 * 60,
            endMinuteOfDay = 8 * 60,
        ),
        tasks = tasks,
        cols = Grid.DEFAULT_COLS,
        loading = false,
    )
}

@Preview(name = "Plan screen", showBackground = true, backgroundColor = 0xFF0E0F12, heightDp = 780)
@Composable
fun PlanScreenPreview() {
    AdhdRoutineAppTheme {
        val state = previewState()
        Scaffold(
            containerColor = Background,
            topBar = {
                Column(Modifier.padding(16.dp)) {
                    Text(state.routine?.name ?: "", color = TextPrimary)
                    Text(summaryLine(state), color = TextSecondary)
                }
            },
            bottomBar = {
                TaskTray(removeActive = false)
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                ScrollingGrid(
                    state = state,
                    geometry = remember { DragGeometry() },
                    onFingerPosition = {},
                    onSelect = {},
                    onPinchStep = { null },
                    onDragStart = {},
                    onDragMove = { _, _ -> },
                    onDragEnd = {},
                    onDragCancel = {},
                )
            }
        }
    }
}

@Preview(
    name = "Plan screen with task editor",
    showBackground = true,
    backgroundColor = 0xFF0E0F12,
    heightDp = 780,
)
@Composable
fun PlanScreenResizePreview() {
    AdhdRoutineAppTheme {
        val base = previewState()
        val state = base.copy(selectedId = 2)
        val selected = state.selectedTask
        Scaffold(
            containerColor = Background,
            topBar = {
                Column(Modifier.padding(16.dp)) {
                    Text(state.routine?.name ?: "", color = TextPrimary)
                    Text(summaryLine(state), color = TextSecondary)
                }
            },
            bottomBar = {
                TaskTray(removeActive = false)
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                ScrollingGrid(
                    state = state,
                    geometry = remember { DragGeometry() },
                    onFingerPosition = {},
                    onSelect = {},
                    onPinchStep = { null },
                    onDragStart = {},
                    onDragMove = { _, _ -> },
                    onDragEnd = {},
                    onDragCancel = {},
                    modifier = Modifier.fillMaxSize(),
                )
                if (selected != null) {
                    TaskEditDialog(
                        task = selected,
                        onRename = {},
                        onColorChange = {},
                        onDurationChange = {},
                        onDelete = {},
                        onDismiss = {},
                    )
                }
            }
        }
    }
}

@Preview(name = "Grid only", showBackground = true, backgroundColor = 0xFF0E0F12, widthDp = 400, heightDp = 300)
@Composable
fun GridCanvasPreview() {
    AdhdRoutineAppTheme {
        val state = previewState()
        val density = LocalDensity.current
        val cellPx = with(density) { 30.dp.toPx() }
        GridCanvas(
            tasks = state.tasks,
            cols = Grid.DEFAULT_COLS,
            cellPx = cellPx,
            routineStartMinuteOfDay = 7 * 60,
            capacityMinutes = state.capacityMinutes,
        )
    }
}
