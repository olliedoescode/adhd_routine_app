package com.adhd.routineplanner.ui.run

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adhd.routineplanner.domain.Grid
import com.adhd.routineplanner.domain.Phase
import com.adhd.routineplanner.domain.TaskDescription
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.CountdownAmber
import com.adhd.routineplanner.ui.theme.CountdownRed
import com.adhd.routineplanner.ui.theme.Surface
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary
import com.adhd.routineplanner.viewmodel.RunUiState
import com.adhd.routineplanner.viewmodel.RunViewModel
import kotlin.math.roundToInt

/** Height of the header block (spec section 15.8). */
private val HeaderHeight = 110.dp

/**
 * Duration of the task-boundary water drain/refill (spec section 15.8).
 * Kept inside the 150–200ms band every animation except the run cursor uses
 * (spec §15.11, plan 10.5).
 */
private const val BOUNDARY_MS = 200

/**
 * Run mode (spec sections 8, 15.8).
 *
 * Layout: a fixed header at the top holding the water box and the red
 * countdown, then the grid, vertically centred on the cursor row and kept
 * there by auto-scroll. The screen is kept awake while it is visible.
 *
 * The "End routine" button and the system back gesture both open a confirm
 * dialog; confirming stops the run and cancels the routine's remaining alarms.
 */
@Composable
fun RunScreen(
    onBack: () -> Unit,
    viewModel: RunViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showExitDialog by remember { mutableStateOf(false) }

    KeepScreenOn()
    BackHandler { showExitDialog = true }

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        if (uiState.loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Loading…", color = TextSecondary)
            }
        } else {
            RunContent(
                state = uiState,
                onExit = { showExitDialog = true },
            )
        }

        if (showExitDialog) {
            ExitDialog(
                onDismiss = { showExitDialog = false },
                onConfirm = {
                    showExitDialog = false
                    viewModel.stop()
                    onBack()
                },
            )
        }
    }
}

/** The header + grid body, split out so previews can drive it directly. */
@Composable
private fun RunContent(
    state: RunUiState,
    onExit: () -> Unit,
) {
    val runState = state.state
    val cols = Grid.DEFAULT_COLS
    val startMinuteOfDay = state.routine?.startMinuteOfDay ?: 0

    // Water level: the current task's progress. Before start it is empty, once
    // finished it is full. The level animates over BOUNDARY_MS so a task change
    // drains and refills rather than snapping (spec section 15.8).
    val targetLevel = when (runState.phase) {
        Phase.BeforeStart -> 0f
        Phase.Finished -> 1f
        Phase.Running -> runState.progress
    }
    val waterLevel by animateFloatAsState(
        targetValue = targetLevel,
        animationSpec = tween(durationMillis = BOUNDARY_MS),
        label = "waterLevel",
    )

    // Haptic tick when the current task changes (spec section 15.8).
    val haptics = LocalHapticFeedback.current
    var lastIndex by remember { mutableStateOf(runState.currentIndex) }
    LaunchedEffect(runState.currentIndex) {
        if (runState.currentIndex != lastIndex) {
            lastIndex = runState.currentIndex
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // The activity is edge-to-edge, so the header would otherwise sit
            // under the status bar. Consume the system bars here so the water
            // box and countdown clear the status bar and the gesture nav bar.
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        RunHeader(
            state = state,
            waterLevel = waterLevel,
        )

        AutoScrollingGrid(
            state = state,
            cols = cols,
            startMinuteOfDay = startMinuteOfDay,
            modifier = Modifier.weight(1f),
        )

        EndRoutineButton(onExit = onExit)
    }
}

/**
 * The single, explicit "End routine" action at the bottom of the run screen
 * (spec section 15.8).
 *
 * This is the only on-screen way out: the header used to carry a second, small
 * X that did the same thing, which just made the screen look like it had two
 * competing exits. The system back gesture still opens the same confirm dialog,
 * so ending a run is never a single accidental tap.
 *
 * The button is deliberately *not* a flat grey slab. It is a full-width,
 * 60dp-tall pill with a visible outline and a stop glyph, so it reads as a
 * distinct control rather than as part of the background. The previous version
 * used `Surface` as its container colour, which is only a shade lighter than
 * the page — it looked like a disabled strip and was easy to miss or mis-tap.
 */
@Composable
private fun EndRoutineButton(onExit: () -> Unit) {
    Button(
        onClick = onExit,
        modifier = Modifier
            .fillMaxWidth()
            // 20dp side margins keep the pill clear of Samsung's edge-swipe
            // back gesture, and the extra bottom padding lifts it off the
            // gesture nav bar.
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp)
            .height(60.dp)
            .border(
                width = 1.dp,
                color = CountdownRed.copy(alpha = 0.55f),
                shape = RoundedCornerShape(30.dp),
            ),
        shape = RoundedCornerShape(30.dp),
        colors = ButtonDefaults.buttonColors(
            // A faint red wash rather than flat grey: it reads as "this ends
            // the run" without shouting, and the white label keeps contrast.
            containerColor = CountdownRed.copy(alpha = 0.14f),
            contentColor = TextPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Stop,
            contentDescription = null,
            tint = CountdownRed,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "End routine",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Fixed header: the water box with the current task name and the countdown
 * (spec section 15.8).
 *
 * The header deliberately carries no exit control. Ending the run is a single,
 * clearly-labelled pill at the bottom of the screen, so there is exactly one
 * place to look for it and no small target competing with the countdown.
 */
@Composable
private fun RunHeader(
    state: RunUiState,
    waterLevel: Float,
) {
    val runState = state.state
    val current = state.currentTask

    val label = when (runState.phase) {
        Phase.BeforeStart -> state.nextTask?.name ?: "Ready"
        Phase.Finished -> "Done"
        Phase.Running -> current?.name ?: "Done"
    }
    val color = when (runState.phase) {
        Phase.BeforeStart -> state.nextTask?.color ?: 0xFF8E9AAF.toInt()
        Phase.Finished -> current?.color ?: 0xFF8E9AAF.toInt()
        Phase.Running -> current?.color ?: 0xFF8E9AAF.toInt()
    }

    // The countdown is only alarming when time is nearly up. Above a minute it
    // stays in the calm primary text colour; the last minute turns amber, and
    // the last ten seconds go red (spec §8.6).
    val countdownColor = when {
        runState.phase != Phase.Running -> TextPrimary
        runState.secondsRemaining <= 10 -> CountdownRed
        runState.secondsRemaining <= 60 -> CountdownAmber
        else -> TextPrimary
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HeaderHeight)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Crossfade(
            targetState = label,
            animationSpec = tween(durationMillis = BOUNDARY_MS),
            label = "taskName",
        ) { name ->
            WaterBox(
                name = name,
                color = color,
                progress = waterLevel,
                modifier = Modifier.fillMaxSize(),
                // The countdown owns the bottom edge, so the name sits at the
                // top and the two never overlap.
                nameAlignment = Alignment.TopCenter,
            )
        }

        // Countdown sits along the bottom edge of the water box.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            when (runState.phase) {
                Phase.BeforeStart -> Countdown(
                    secondsRemaining = runState.secondsRemaining,
                    prefix = "",
                    color = TextPrimary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Phase.Running -> Countdown(
                    secondsRemaining = runState.secondsRemaining,
                    color = countdownColor,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Phase.Finished -> Text(
                    text = "Done",
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
    }
}

/**
 * The grid, scrolled so the cursor row stays vertically centred (spec section
 * 8.2). The scroll offset follows the current row with a short animation so the
 * grid glides rather than jumping at a row boundary.
 */
@Composable
private fun AutoScrollingGrid(
    state: RunUiState,
    cols: Int,
    startMinuteOfDay: Int,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val runState = state.state
    val totalMinutes = state.totalMinutes

    // Cell size in px, matching RunGrid: (screen width - 16dp padding - gutter)
    // divided by the column count. Used to convert the cursor row to a scroll
    // offset without waiting for layout.
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val cellPx = with(density) {
        (screenWidthDp.dp - 16.dp - 44.dp).toPx() / cols
    }

    // Row containing the cursor. Before start it is row 0; finished it is the
    // last row of the routine.
    val cursorRow = when (runState.phase) {
        Phase.BeforeStart -> 0
        Phase.Finished -> (Grid.rowsFor(totalMinutes, cols) - 1).coerceAtLeast(0)
        Phase.Running -> (runState.elapsedSeconds / 60L / cols).toInt()
    }

    // Spoken summary of the run, since the grid is a Canvas (spec §18). The
    // length quoted is the rendered one, so it matches the countdown.
    val runDescription = remember(
        runState.phase,
        runState.currentIndex,
        state.currentTask,
        state.currentTaskMinutes,
    ) {
        val current = state.currentTask
        val length = TaskDescription.length(state.currentTaskMinutes)
        when {
            current == null -> "Run finished."
            runState.phase == Phase.BeforeStart -> "Starting soon: ${current.name}, $length."
            else -> buildString {
                append("Now: ${current.name}, $length. ")
                append("${TaskDescription.length((runState.secondsRemaining / 60).toInt())} remaining.")
            }
        }
    }

    // Centre the cursor row: scroll so that row's top is half a viewport down.
    LaunchedEffect(cursorRow, cols, cellPx) {
        val viewport = scrollState.viewportSize
        if (viewport > 0 && cellPx > 0f) {
            val target = (cursorRow * cellPx - viewport / 2f + cellPx / 2f)
                .roundToInt()
                .coerceIn(0, scrollState.maxValue)
            scrollState.animateScrollTo(target)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 24dp of horizontal padding keeps Samsung's edge-swipe back
            // gesture off the grid (spec §17, plan 10.3).
            .padding(horizontal = 24.dp)
            .semantics { contentDescription = runDescription }
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        RunGrid(
            tasks = state.tasks,
            state = runState,
            cols = cols,
            startMinuteOfDay = startMinuteOfDay,
            totalMinutes = totalMinutes,
            renderedMinutes = state.renderedMinutes,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * Confirm dialog shown by the End routine button and the back gesture
 * (spec section 15.8).
 *
 * The previous version used Material's default `AlertDialog` button row: two
 * small text buttons crammed side by side at the bottom-right, with the
 * destructive action first and the safe action second. That is easy to
 * mis-tap and reads as an afterthought. This version stacks two full-width,
 * 56dp-tall buttons so each is a large, unambiguous target:
 *
 *  - "Keep going" is the primary, filled button at the top, because the
 *    common case is that the back gesture was an accident.
 *  - "End routine" is a quieter outlined button below it, so ending the run
 *    takes a deliberate second tap on a clearly-labelled control.
 *
 * The dialog is dismissed by tapping outside or the back gesture, both of
 * which map to "keep going".
 */
@Composable
private fun ExitDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "End this routine?",
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        // Both buttons are rendered in the text slot so they can be stacked
        // full-width; the built-in confirm/dismiss row is left empty.
        confirmButton = {},
        dismissButton = {},
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Your run will stop and any alarms still set for this " +
                        "routine will be cancelled. You can start it again any time.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TextPrimary,
                        contentColor = Background,
                    ),
                ) {
                    Text(
                        text = "Keep going",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .border(
                            width = 1.dp,
                            color = CountdownRed.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(28.dp),
                        ),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = CountdownRed,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = null,
                        tint = CountdownRed,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "End routine",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
    )
}

/** Keeps the screen awake while the run screen is visible (spec section 8.2). */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
