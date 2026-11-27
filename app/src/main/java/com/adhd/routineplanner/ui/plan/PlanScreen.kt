package com.adhd.routineplanner.ui.plan

import com.adhd.routineplanner.ui.common.AppSnackbarHost
import com.adhd.routineplanner.ui.common.AppTopBar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adhd.routineplanner.domain.Grid
import com.adhd.routineplanner.domain.Routine
import com.adhd.routineplanner.domain.Schedule
import com.adhd.routineplanner.ui.routines.RoutineSettingsSheet
import com.adhd.routineplanner.ui.theme.Accent
import com.adhd.routineplanner.ui.theme.AdhdRoutineAppTheme
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.CountdownRed
import com.adhd.routineplanner.ui.theme.Surface
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary
import com.adhd.routineplanner.viewmodel.PlanUiState
import com.adhd.routineplanner.viewmodel.PlanViewModel

/**
 * The plan screen (spec §15.3): top bar, reorderable task timeline, and the
 * routine settings sheet.
 *
 * The timeline is the only editor now. The old minute-cell grid, its pinch/drag
 * gesture stack and the resize strip were retired — they asked the user to aim
 * at individual minutes on a canvas, which was the fiddliest part of the app.
 * The code is archived under `ui/plan/archive` for reference. The run page has
 * its own, read-only grid (`ui/run/RunGrid.kt`) which is unaffected.
 *
 * Editing a task (name, colour, duration, alarm) happens in [TaskEditDialog];
 * adding one happens in [TaskCreateDialog]. Both are triggered from the
 * timeline, so selection is a plain tap: tapping a block toggles it for a bulk
 * delete, and tapping a block with nothing selected opens the editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    onBack: () -> Unit,
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

    // Offer undo whenever a new snapshot appears (spec §16).
    LaunchedEffect(state.undo) {
        val snapshot = state.undo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "${snapshot.label} applied",
            actionLabel = "Undo",
            withDismissAction = true,
            // Auto-dismiss instead of the Material default of Indefinite, so
            // the undo offer never becomes a permanent bar over the timeline.
            duration = SnackbarDuration.Short,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> viewModel.undoLast()
            SnackbarResult.Dismissed -> viewModel.dismissUndo()
        }
    }

    Scaffold(
        containerColor = Background,
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                title = state.routine?.name ?: "Routine",
                subtitle = summaryLine(state),
                // Tapping the title opens routine settings, as before.
                onTitleClick = if (state.routine != null && !state.runActive) {
                    { settingsOpen = true }
                } else {
                    null
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
                    // Add-task is a floating button over the list (see below),
                    // not a top-bar action: it sits within thumb reach next to
                    // the tasks it adds.
                    IconButton(
                        onClick = {
                            // Start the run for real (session + ongoing
                            // notification + in-run alarms + vibration). The
                            // takeover is launched by RunStarter itself, so we
                            // deliberately do *not* also navigate to the run
                            // screen here: that pushed an identical Run page
                            // underneath the takeover, so dismissing the
                            // overlay dropped the user onto a duplicate of the
                            // same screen. The run screen is still reachable
                            // from the ongoing notification and the routine
                            // list.
                            viewModel.startRunNow()
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
                            // Every gesture needs a button equivalent (spec §18).
                            // Zoom resizes the timeline blocks, so "zoom in" means
                            // "show fewer minutes, larger" whichever view is up.
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
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (state.runActive) {
                RunLockBanner()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (state.loading) {
                    Text(
                        text = "Loading…",
                        color = TextSecondary,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    // Timeline view: each task as a proportional coloured block
                    // on a vertical rail, reorderable by long-press and drag.
                    // Tapping a block opens the same editor; once anything is
                    // highlighted, tapping toggles it for a bulk delete instead.
                    Column(Modifier.fillMaxSize()) {
                        TaskListEditor(
                            tasks = state.tasks,
                            routineStartMinuteOfDay = state.routine?.startMinuteOfDay ?: 0,
                            onMove = { from, to ->
                                // A move by one slot goes through the view
                                // model's single-step move, so it shares the
                                // undo path with every other reorder.
                                if (to > from) viewModel.moveTask(state.tasks[from].id, 1)
                                else viewModel.moveTask(state.tasks[from].id, -1)
                            },
                            onTap = { task -> viewModel.select(task.id) },
                            modifier = Modifier.weight(1f),
                            capacityMinutes = state.capacityMinutes,
                            selectedIds = state.multiSelectedIds,
                            onToggleSelect = viewModel::toggleMultiSelect,
                            // Map the same column setting the zoom menu drives
                            // onto the timeline's height scale, so Zoom in/out
                            // now works here and not only on the old grid.
                            zoom = Grid.DEFAULT_COLS.toFloat() / state.cols.toFloat(),
                        )
                        if (state.multiSelectedIds.isNotEmpty()) {
                            MultiSelectBar(
                                count = state.multiSelectedIds.size,
                                onCancel = viewModel::clearMultiSelect,
                                onDelete = viewModel::deleteSelectedTasks,
                            )
                        }
                    }
                }

                // Add-task button: the same pill the routines page uses for
                // "+ New routine", so the two pages teach one gesture. It sits
                // bottom-right, thumb-reachable, and is hidden while a run is
                // active (editing is locked) and while a multi-selection is
                // showing (the delete bar owns that space).
                val canAdd = !state.runActive &&
                    state.overrunMinutes == 0 &&
                    state.totalMinutes < state.capacityMinutes &&
                    state.multiSelectedIds.isEmpty()
                if (canAdd) {
                    Button(
                        onClick = { creatingTask = true },
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Background,
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(20.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "New task",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                // Tapping a task opens the popup editor: rename it and set its
                // duration with the scrollable h/m/s picker. Suppressed while a
                // run is active (spec section 8.7).
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
                onCreate = { name, minutes, color, alarmEnabled, icon ->
                    viewModel.createTask(name, minutes, color, alarmEnabled, icon)
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
                    // One atomic call: the routine row and any task shortening
                    // are decided by the WindowEdit state machine and written
                    // together, so a window edit can never wipe the tasks.
                    viewModel.applyWindowEdit(updated)
                    settingsOpen = false
                },
                onDismiss = { settingsOpen = false },
                taskMinutes = state.tasks.map { it.minutes },
            )
        }
    }
}

/**
 * The bulk-action bar shown under the timeline while tasks are highlighted.
 * Kept out of the top bar so it sits next to the list it acts on. Mirrors the
 * routines page: a count, a cancel, and a red bin that deletes the selection.
 */
@Composable
private fun MultiSelectBar(
    count: Int,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Cancel selection",
                tint = TextSecondary,
            )
        }
        Text(
            text = if (count == 1) "1 task selected" else "$count tasks selected",
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Delete selected tasks",
                tint = CountdownRed,
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

@Preview(name = "Plan screen", showBackground = true, backgroundColor = 0xFF0A1116, heightDp = 780)
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
        ) { padding ->
            Box(Modifier.padding(padding)) {
                TaskListEditor(
                    tasks = state.tasks,
                    routineStartMinuteOfDay = state.routine?.startMinuteOfDay ?: 0,
                    onMove = { _, _ -> },
                    onTap = {},
                    capacityMinutes = state.capacityMinutes,
                )
            }
        }
    }
}
