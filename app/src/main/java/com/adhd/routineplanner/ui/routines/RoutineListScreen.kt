package com.adhd.routineplanner.ui.routines

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adhd.routineplanner.R
import com.adhd.routineplanner.domain.DaysOfWeek
import com.adhd.routineplanner.domain.Routine
import com.adhd.routineplanner.domain.Schedule
import com.adhd.routineplanner.ui.common.AppSnackbarHost
import com.adhd.routineplanner.ui.common.AppSwitch
import com.adhd.routineplanner.ui.common.AppTopBar
import com.adhd.routineplanner.ui.theme.AdhdRoutineAppTheme
import com.adhd.routineplanner.ui.theme.Accent
import com.adhd.routineplanner.ui.theme.AccentSoft
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.CountdownRed
import com.adhd.routineplanner.ui.theme.Surface
import com.adhd.routineplanner.ui.theme.SurfaceElevated
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary
import com.adhd.routineplanner.ui.theme.routineTint
import com.adhd.routineplanner.viewmodel.RoutineCardUi
import com.adhd.routineplanner.viewmodel.RoutineListViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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
@Composable
fun RoutineListScreen(
    onOpenRoutine: (Long) -> Unit,
    onCreateRoutine: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRun: (Long) -> Unit,
    viewModel: RoutineListViewModel = hiltViewModel(),
) {
    val routines by viewModel.routines.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    val activeRunId by viewModel.activeRunId.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
            // Auto-dismiss: with an action Material defaults to Indefinite, so
            // the undo bar used to sit over the list until the user closed it.
            duration = SnackbarDuration.Short,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> viewModel.undoDelete()
            SnackbarResult.Dismissed -> viewModel.dismissUndo()
        }
    }

    Scaffold(
        containerColor = Background,
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            if (selecting) {
                SelectionTopBar(
                    count = selectedIds.size,
                    onClose = { selectedIds = emptySet() },
                    onDelete = { confirmDelete = true },
                )
            } else {
                AppTopBar(
                    title = greeting(),
                    subtitle = todayLabel(),
                    actions = {
                        // One entry, straight to Settings. Alarm setup lives
                        // there as a section now, so the old two-item menu was
                        // one tap of ceremony for no extra choice.
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Settings",
                                tint = TextPrimary,
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Coming back to the app mid-run should offer the run in one tap.
            // The ongoing notification can do it, but a bar right on the home
            // screen makes it impossible to miss (and to forget a run is live).
            activeRunId?.let { runId ->
                RunInProgressBanner(
                    routineName = routines
                        .firstOrNull { it.routine.id == runId }
                        ?.routine?.name,
                    onClick = { onOpenRun(runId) },
                )
            }
            if (routines.isEmpty()) {
                EmptyRoutines(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    onCreateRoutine = onCreateRoutine,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(routines, key = { it.routine.id }) { card ->
                        val id = card.routine.id
                        // Deletion is done through selection mode (long-press to
                        // select, then the bin in the top bar), so the card itself
                        // is a plain, non-swipeable surface. Swiping used to delete
                        // on a single gesture, which was too easy to trigger by
                        // accident while scrolling.
                        RoutineCard(
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
                            // Only untimed routines show this button; it starts the
                            // run immediately from the list, because an untimed
                            // routine has no start alarm to wait for.
                            onRun = { viewModel.startRun(id) },
                        )
                    }
                }
            }
        }
    }

    // A floating create button keeps "make a new routine" one thumb-reach away
    // once the list is long. It hides in selection mode, where the top bar owns
    // the actions, and when the list is empty — the empty state already offers
    // a single "Create your first routine" button, so showing this one too would
    // give the user two identical calls to action.
    if (!selecting && routines.isNotEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.BottomEnd,
        ) {
            Button(
                onClick = onCreateRoutine,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Background,
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "New routine",
                    style = MaterialTheme.typography.labelLarge,
                )
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
 * A slim bar above the list while a run is live.
 *
 * The ongoing notification is the canonical way back into a run, but a phone
 * picked up mid-routine lands on this page; putting the run one tap away here
 * means the user never has to hunt for it (or wonder whether the run is still
 * going).
 */
@Composable
private fun RunInProgressBanner(
    routineName: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AccentSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (routineName != null) {
                    "$routineName is running"
                } else {
                    "A routine is running"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Tap to open the run page.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

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
    AppTopBar(
        title = "$count selected",
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
    )
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
    onRun: () -> Unit,
) {
    val routine = card.routine
    val haptics = LocalHapticFeedback.current
    // Each routine gets its own warm hue, derived from its id so it stays
    // stable without a schema change. It now appears only on the Run button and
    // the "starts in" line, so a disabled routine simply drops to the secondary
    // text colour rather than being faded.
    val tint = routineTint(routine.id)
    val shape = RoundedCornerShape(20.dp)

    M3Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .then(
                // A selected card gets a bright outline so the whole selection
                // is obvious at a glance. It is drawn after the surface so it
                // is not clipped away.
                if (selected) Modifier.border(2.dp, TextPrimary, shape) else Modifier,
            ),
        shape = shape,
        color = Surface,
        tonalElevation = 2.dp,
        shadowElevation = if (selected) 0.dp else 3.dp,
    ) {
        // One calm block of content. The routine's colour shows up in its Run
        // button and weekday letters, not in a rail down the side of the card.
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Name on the left, enable switch on the right. There is
                // deliberately no per-card overflow menu: deletion is a swipe,
                // and editing happens by opening the routine.
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
                        color = if (routine.enabled) TextPrimary else TextSecondary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    AppSwitch(
                        checked = routine.enabled,
                        onCheckedChange = onToggleEnabled,
                        // The switch is not the point in selection mode;
                        // disable it so a tap on the card toggles selection.
                        enabled = !selecting,
                    )
                }

                Spacer(Modifier.height(12.dp))

                // When it runs: the weekly letters for a recurring routine, the
                // date for a one-off, or "Anytime" for a routine with no
                // temporal connection at all.
                val oneOff = card.oneOffLabel
                if (routine.isUntimed) {
                    // Meta, not emphasis: these lines sit under the card title
                    // and only need to be readable.
                    Text(
                        text = "Anytime — no set time",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                } else if (oneOff != null) {
                    Text(
                        text = oneOff,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                } else {
                    WeekdayLetters(
                        mask = routine.daysOfWeek,
                        enabled = routine.enabled,
                        accent = tint,
                    )
                }

                // A countdown for a routine that starts within the next day, so
                // the next thing coming up is obvious without opening anything.
                // It is tinted with the routine's own hue to tie it to the card.
                val startsIn = card.startsInLabel
                if (startsIn != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.NotificationsActive,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = startsIn,
                            // The routine's own tint already marks this line as
                            // "this card, soon" — it does not need weight too.
                            style = MaterialTheme.typography.bodyMedium,
                            color = tint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                if (routine.isUntimed) {
                    // An untimed routine has no start or end, so showing them
                    // would just print "00:00" twice. Only the length means
                    // anything here — and it is the routine's own length, not
                    // the tasks' minimum sum, because the buffer stretches the
                    // tasks to fill it. The sum is the floor in the odd case
                    // where the tasks claim more than the stored length.
                    val lengthMinutes = maxOf(routine.capacityMinutes, card.totalMinutes)
                    TimeStat(
                        label = "Length",
                        value = if (lengthMinutes == 0) "—" else "$lengthMinutes min",
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(14.dp))

                    // The Run button, for this routine only: an untimed routine
                    // is never started by an alarm, so it needs a way to begin
                    // from the list. It follows the routine's own hue and is
                    // disabled while the routine is switched off, in the same
                    // way its alarms would be, and while it holds no tasks —
                    // there is nothing to run, so the button would only ever
                    // drop the user into an empty run screen.
                    Button(
                        onClick = onRun,
                        enabled = !selecting && routine.enabled && card.totalMinutes > 0,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tint,
                            contentColor = Background,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Run now",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                } else {
                    // Start, end and duration, laid out as three soft chips.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
        }
    }
}


/** A small caption above a value, used for the start/end/length row. */
@Composable
private fun TimeStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceElevated.copy(alpha = 0.7f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            // A value, not a heading: one step up from the light label above it
            // is enough of a hierarchy inside a 12dp chip.
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The welcome shown when there are no routines yet.
 *
 * The new loon artwork is transparent, so it can sit directly on the page as a
 * quiet scene: an adult in flight above, a pair resting on the water below. The
 * artwork is tinted with the theme's primary text colour and faded back, which
 * keeps the line work visible on both the dark and light palettes instead of
 * letting a black silhouette disappear into a dark page. The birds follow the
 * theme the user picked, so the empty page always belongs to the app they chose.
 */
@Composable
private fun EmptyRoutines(modifier: Modifier = Modifier, onCreateRoutine: () -> Unit) {
    Box(modifier = modifier.clipToBounds()) {
        // The birds are decoration, not content: drawn first, behind the words,
        // and faded enough that the title and button always carry the page.
        val ink = TextPrimary

        Image(
            painter = painterResource(R.drawable.loon_flying),
            contentDescription = null,
            colorFilter = ColorFilter.tint(ink, BlendMode.SrcIn),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.78f)
                .aspectRatio(3f / 2f)
                .offset(x = 32.dp, y = (-8).dp)
                .rotate(-8f)
                .alpha(0.10f),
        )

        Image(
            painter = painterResource(R.drawable.loon_pair_on_water),
            contentDescription = null,
            colorFilter = ColorFilter.tint(ink, BlendMode.SrcIn),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .aspectRatio(3f / 2f)
                .offset(y = 36.dp)
                .alpha(0.13f),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Nothing planned yet",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Build a routine and it will show up here, ready to run.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onCreateRoutine,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Background,
                ),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Create your first routine",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** "Good morning" / "Good afternoon" / "Good evening", by the hour. */
private fun greeting(now: java.time.LocalTime = java.time.LocalTime.now()): String =
    when (now.hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Late night"
    }

/** Today's date, e.g. "Thursday 27 November". */
private fun todayLabel(today: LocalDate = LocalDate.now()): String =
    today.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))

/**
 * The seven weekday letters, Monday first.
 *
 * Active days are bright with a small dot above the letter; inactive days are
 * greyed out with no dot. The letters sit close together with no circles
 * around them, so the row reads as a compact word rather than seven buttons.
 */
@Composable
private fun WeekdayLetters(
    mask: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = TextPrimary,
) {
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
                        .background(if (lit) accent else Color.Transparent),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = DaysOfWeek.LETTER_LABELS[index],
                    style = MaterialTheme.typography.labelLarge,
                    color = if (lit) TextPrimary else TextSecondary.copy(alpha = 0.45f),
                    // The dot above the letter is what says "this day is on";
                    // the letter only needs to be a shade firmer than the
                    // switched-off ones, not bold seven times per card.
                    fontWeight = if (lit) FontWeight.Medium else FontWeight.Light,
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

@Preview(name = "Routine list", showBackground = true, backgroundColor = 0xFF0A1116)
@Composable
fun RoutineListPreview() {
    AdhdRoutineAppTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RoutineCard(
                card = RoutineCardUi(
                    routine = Routine(id = 1, name = "Morning", startMinuteOfDay = 7 * 60),
                    totalMinutes = 55,
                    endMinuteOfDay = 7 * 60 + 55,
                    startsInLabel = "Starts in 2 hours 15 mins",
                ),
                selected = false,
                selecting = false,
                onClick = {},
                onLongClick = {},
                onToggleEnabled = {},
                onRun = {},
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
                onRun = {},
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
                onRun = {},
            )
            // The untimed variant: no days, no start, just the length and the
            // Run now button.
            RoutineCard(
                card = RoutineCardUi(
                    routine = Routine(
                        id = 4,
                        name = "Reset the flat",
                        startMinuteOfDay = 0,
                        endMinuteOfDay = 45,
                        isUntimed = true,
                        allowPause = true,
                    ),
                    totalMinutes = 45,
                    endMinuteOfDay = 45,
                ),
                selected = false,
                selecting = false,
                onClick = {},
                onLongClick = {},
                onToggleEnabled = {},
                onRun = {},
            )
        }
    }
}
