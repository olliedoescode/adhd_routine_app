package com.adhd.routineplanner.ui.routines

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adhd.routineplanner.domain.DaysOfWeek
import com.adhd.routineplanner.domain.Routine
import com.adhd.routineplanner.domain.Schedule
import com.adhd.routineplanner.ui.theme.AdhdRoutineAppTheme
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.CountdownRed
import com.adhd.routineplanner.ui.theme.Surface
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary
import com.adhd.routineplanner.viewmodel.RoutineCardUi
import com.adhd.routineplanner.viewmodel.RoutineListViewModel

/**
 * Routine list (spec §15.2).
 *
 * The whole page is black: the top bar shares the page background rather than
 * sitting on a lighter surface. The bar holds the "Routines" title, a settings
 * entry and the new-routine button, so there is no floating action button.
 *
 * Each card shows the routine's name, its enable switch, when it runs (weekly
 * day letters for a recurring routine, or the date for a one-off) and its
 * start, end and duration. Tapping a card opens the plan. Swiping deletes, with
 * an undo snackbar as the safety net.
 *
 * Long-pressing a card enters selection mode: the card highlights, further
 * taps toggle other cards, and a bin appears in the top bar to delete the whole
 * selection at once. Back or the close button leaves selection mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineListScreen(
    onOpenRoutine: (Long) -> Unit,
    onCreateRoutine: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPermissions: () -> Unit,
    viewModel: RoutineListViewModel = hiltViewModel(),
) {
    val routines by viewModel.routines.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }

    // Ids of the routines selected for batch deletion. Empty means selection
    // mode is off, so the top bar shows the normal actions.
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selecting = selectedIds.isNotEmpty()

    // Drop ids that no longer exist (e.g. deleted elsewhere) so the count and
    // the bin action never refer to a routine that is already gone.
    LaunchedEffect(routines) {
        if (selectedIds.isNotEmpty()) {
            val live = routines.map { it.routine.id }.toSet()
            val pruned = selectedIds intersect live
            if (pruned != selectedIds) selectedIds = pruned
        }
    }

    // Back leaves selection mode before it leaves the screen.
    BackHandler(enabled = selecting) { selectedIds = emptySet() }

    // Offer undo whenever a routine is deleted.
    LaunchedEffect(deleted) {
        val pending = deleted ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "${pending.routine.name} deleted",
            actionLabel = "Undo",
            withDismissAction = true,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> viewModel.undoDelete()
            SnackbarResult.Dismissed -> viewModel.dismissUndo()
        }
    }

    Scaffold(
        containerColor = Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selecting) {
                SelectionTopBar(
                    count = selectedIds.size,
                    onClose = { selectedIds = emptySet() },
                    onDelete = { confirmDelete = true },
                )
            } else {
                TopAppBar(
                    title = { Text("Routines", color = TextPrimary) },
                    actions = {
                        // New routine sits to the left of the settings icon.
                        IconButton(onClick = onCreateRoutine) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "New routine",
                                tint = TextPrimary,
                            )
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Settings,
                                    contentDescription = "Settings",
                                    tint = TextPrimary,
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Settings, contentDescription = null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onOpenSettings()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Alarm setup") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.NotificationsActive,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onOpenPermissions()
                                    },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
                )
            }
        },
    ) { innerPadding ->
        if (routines.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text("No routines yet", color = TextSecondary)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(routines, key = { it.routine.id }) { card ->
                    val id = card.routine.id
                    SwipeableRoutineCard(
                        card = card,
                        selected = id in selectedIds,
                        selecting = selecting,
                        onClick = {
                            if (selecting) {
                                selectedIds = selectedIds.toggle(id)
                            } else {
                                onOpenRoutine(id)
                            }
                        },
                        onLongClick = {
                            selectedIds = selectedIds.toggle(id)
                        },
                        onToggleEnabled = { enabled ->
                            viewModel.setEnabled(id, enabled)
                        },
                        onDelete = { viewModel.delete(card.routine) },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        val chosen = routines.filter { it.routine.id in selectedIds }.map { it.routine }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Surface,
            title = { Text("Delete ${chosen.size} routine(s)?", color = TextPrimary) },
            text = {
                Text(
                    text = "This cannot be undone.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        selectedIds = emptySet()
                        viewModel.deleteMany(chosen)
                    },
                ) {
                    Text("Delete", color = CountdownRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Cancel", color = TextPrimary)
                }
            },
        )
    }
}

/** Adds or removes [id] from the selection. */
private fun Set<Long>.toggle(id: Long): Set<Long> =
    if (id in this) this - id else this + id

/**
 * The top bar shown while routines are selected: a close button, the count and
 * the bin that deletes the whole selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        title = { Text("$count selected", color = TextPrimary) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Cancel selection",
                    tint = TextPrimary,
                )
            }
        },
        actions = {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete selected routines",
                    tint = CountdownRed,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
    )
}

/**
 * A routine card wrapped in a swipe-to-delete container. Swiping either way
 * deletes; the undo snackbar is the safety net.
 *
 * While [selecting] the swipe is disabled so a horizontal drag cannot delete a
 * routine the user is trying to select; taps toggle selection instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableRoutineCard(
    card: RoutineCardUi,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled && !selecting) {
                onDelete()
                true
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        // Swiping is meaningless in selection mode; the bin in the top bar is
        // the way to delete then.
        enableDismissFromStartToEnd = !selecting,
        enableDismissFromEndToStart = !selecting,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CountdownRed.copy(alpha = 0.85f))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text("Delete", color = TextPrimary, fontWeight = FontWeight.SemiBold)
            }
        },
    ) {
        RoutineCard(
            card = card,
            selected = selected,
            selecting = selecting,
            onClick = onClick,
            onLongClick = onLongClick,
            onToggleEnabled = onToggleEnabled,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoutineCard(
    card: RoutineCardUi,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    val routine = card.routine
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Surface.copy(alpha = 0.9f) else Surface)
            // A selected card gets a bright outline so the whole selection is
            // obvious at a glance.
            .then(
                if (selected) {
                    Modifier.border(2.dp, TextPrimary, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(16.dp),
    ) {
        // Name on the left, enable switch on the right. There is deliberately
        // no per-card overflow menu: deletion is a swipe, and editing happens
        // by opening the routine.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) TextPrimary else TextSecondary.copy(alpha = 0.4f),
                    modifier = Modifier.padding(end = 10.dp).size(20.dp),
                )
            }
            Text(
                text = routine.name,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = routine.enabled,
                onCheckedChange = onToggleEnabled,
                // The switch is not the point in selection mode; disable it so
                // a tap on the card toggles selection instead.
                enabled = !selecting,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Background,
                    checkedTrackColor = TextPrimary,
                ),
            )
        }

        Spacer(Modifier.height(10.dp))

        // When it runs: the weekly letters for a recurring routine, or the
        // date for a one-off.
        val oneOff = card.oneOffLabel
        if (oneOff != null) {
            Text(
                text = oneOff,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                fontWeight = FontWeight.Medium,
            )
        } else {
            WeekdayLetters(mask = routine.daysOfWeek, enabled = routine.enabled)
        }

        Spacer(Modifier.height(12.dp))

        // Start, end and duration, laid out as three aligned columns.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TimeStat(
                label = "Start",
                value = Schedule.clockLabel(routine.startMinuteOfDay, 0, 1),
                modifier = Modifier.weight(1f),
            )
            TimeStat(
                label = "End",
                value = Schedule.clockLabel(card.endMinuteOfDay, 0, 1),
                modifier = Modifier.weight(1f),
            )
            TimeStat(
                label = "Length",
                value = if (card.totalMinutes == 0) "—" else "${card.totalMinutes} min",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** A small caption above a value, used for the start/end/length row. */
@Composable
private fun TimeStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * The seven weekday letters, Monday first.
 *
 * Active days are bright with a small dot above the letter; inactive days are
 * greyed out with no dot. The letters sit close together with no circles
 * around them, so the row reads as a compact word rather than seven buttons.
 */
@Composable
private fun WeekdayLetters(mask: Int, enabled: Boolean, modifier: Modifier = Modifier) {
    val chips = DaysOfWeek.toDisplayChips(mask)
    Row(
        modifier = modifier.semantics {
            contentDescription = "Active days: ${daySummary(mask)}"
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        chips.forEachIndexed { index, active ->
            // A disabled routine greys everything out, so the card reads as
            // "this is off" at a glance.
            val lit = active && enabled
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // The dot only appears above an active day. An invisible dot
                // keeps the letters on the same baseline whether or not it is
                // shown.
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(if (lit) TextPrimary else androidx.compose.ui.graphics.Color.Transparent),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = DaysOfWeek.LETTER_LABELS[index],
                    style = MaterialTheme.typography.labelLarge,
                    color = if (lit) TextPrimary else TextSecondary.copy(alpha = 0.45f),
                    fontWeight = if (lit) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/** "Mon–Fri" for a contiguous run, otherwise comma-separated short labels. */
private fun daySummary(mask: Int): String {
    if (mask == DaysOfWeek.ALL) return "Every day"
    if (mask == DaysOfWeek.NONE) return "No days"
    val chips = DaysOfWeek.toDisplayChips(mask)
    val active = chips.indices.filter { chips[it] }
    if (active.isEmpty()) return "No days"
    val contiguous = active.last() - active.first() == active.size - 1
    return if (contiguous) {
        "${DaysOfWeek.SHORT_LABELS[active.first()]}–${DaysOfWeek.SHORT_LABELS[active.last()]}"
    } else {
        active.joinToString(", ") { DaysOfWeek.SHORT_LABELS[it] }
    }
}

@Preview(name = "Routine list", showBackground = true, backgroundColor = 0xFF0E0F12)
@Composable
fun RoutineListPreview() {
    AdhdRoutineAppTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RoutineCard(
                card = RoutineCardUi(
                    routine = Routine(id = 1, name = "Morning", startMinuteOfDay = 7 * 60),
                    totalMinutes = 55,
                    endMinuteOfDay = 7 * 60 + 55,
                ),
                selected = false,
                selecting = false,
                onClick = {},
                onLongClick = {},
                onToggleEnabled = {},
            )
            RoutineCard(
                card = RoutineCardUi(
                    routine = Routine(
                        id = 2,
                        name = "Evening wind-down",
                        startMinuteOfDay = 21 * 60 + 30,
                        daysOfWeek = DaysOfWeek.MONDAY or DaysOfWeek.WEDNESDAY or DaysOfWeek.FRIDAY,
                    ),
                    totalMinutes = 30,
                    endMinuteOfDay = 22 * 60,
                ),
                selected = true,
                selecting = true,
                onClick = {},
                onLongClick = {},
                onToggleEnabled = {},
            )
            RoutineCard(
                card = RoutineCardUi(
                    routine = Routine(
                        id = 3,
                        name = "Dentist prep",
                        startMinuteOfDay = 8 * 60,
                        scheduledDate = "2026-11-25",
                    ),
                    totalMinutes = 20,
                    endMinuteOfDay = 8 * 60 + 20,
                    oneOffLabel = "Wed 25 Nov",
                ),
                selected = false,
                selecting = false,
                onClick = {},
                onLongClick = {},
                onToggleEnabled = {},
            )
        }
    }
}
