package com.adhd.routineplanner.ui.run

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adhd.routineplanner.domain.Phase
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.TextSecondary
import com.adhd.routineplanner.viewmodel.RunUiState
import com.adhd.routineplanner.viewmodel.RunViewModel
import kotlinx.coroutines.delay

/**
 * How long the "Routine finished" state is shown before the run screen closes
 * itself. Long enough to read the message and register that the routine is
 * over, short enough that the phone is not left sitting on a dead screen.
 */
private const val FINISHED_AUTO_CLOSE_MS = 4_000L

/**
 * Run mode (spec sections 8, 15.8).
 *
 * Layout: a fixed header at the top holding the water box and the red
 * countdown, then the grid, vertically centred on the cursor row and kept
 * there by auto-scroll. The screen is kept awake while it is visible.
 *
 * The "End routine" button and the system back gesture both open a confirm
 * dialog; confirming stops the run and cancels the routine's remaining alarms.
 * Both the card and the End routine control live in the shared [RunBody], so
 * the lock-screen takeover shows exactly the same page.
 */
@Composable
fun RunScreen(
    onBack: () -> Unit,
    viewModel: RunViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    KeepScreenOn()

    // The back gesture is the same two-step exit as the End routine pill: it
    // opens the confirm dialog owned by [RunBody] rather than ending the run
    // outright. [RunBody] owns that dialog, so the back gesture simply asks it
    // to show it via this shared flag.
    var showExitDialog by remember { mutableStateOf(false) }
    BackHandler { showExitDialog = true }

    // Once the routine finishes, show the "Routine finished" state for a few
    // seconds and then close the screen on its own. This bypasses the exit
    // dialog on purpose: the run is already over, so there is nothing to
    // confirm. `stop()` is called first so the session, alarms and the
    // ongoing notification are all torn down before the screen pops.
    val finished = !uiState.loading && uiState.state.phase == Phase.Finished
    LaunchedEffect(finished) {
        if (finished) {
            delay(FINISHED_AUTO_CLOSE_MS)
            viewModel.stop()
            onBack()
        }
    }

    // If the routine is deleted while this screen is showing, there is nothing
    // left to run. [com.adhd.routineplanner.alarm.RunStopper] has already
    // cleared the session, cancelled the alarms and dismissed the ongoing
    // notification, so closing here is the last piece: leave the screen instead
    // of sitting on a run whose routine no longer exists.
    val routineGone = !uiState.loading && uiState.routine == null
    LaunchedEffect(routineGone) {
        if (routineGone) {
            viewModel.stop()
            onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        if (uiState.loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Loading…", color = TextSecondary)
            }
        } else {
            RunContent(
                state = uiState,
                exitRequested = showExitDialog,
                onExitRequestHandled = { showExitDialog = false },
                onExit = {
                    viewModel.stop()
                    onBack()
                },
                onTogglePause = viewModel::togglePause,
            )
        }
    }
}

/**
 * The run body: the Now/Next card fills the screen, with the single End routine
 * pill pinned to the bottom.
 *
 * The card, its fill animation and the End routine control all live in the
 * shared [RunBody], which the lock-screen takeover renders too, so the two
 * surfaces are the same page. This wrapper only adds the run-screen-specific
 * haptic tick when the current task changes.
 */
@Composable
private fun RunContent(
    state: RunUiState,
    exitRequested: Boolean,
    onExitRequestHandled: () -> Unit,
    onExit: () -> Unit,
    onTogglePause: () -> Unit,
) {
    val runState = state.state

    // Haptic tick when the current task changes (spec section 15.8).
    val haptics = LocalHapticFeedback.current
    var lastIndex by remember { mutableStateOf(runState.currentIndex) }
    LaunchedEffect(runState.currentIndex) {
        if (runState.currentIndex != lastIndex) {
            lastIndex = runState.currentIndex
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    RunBody(
        state = state,
        onExit = onExit,
        exitRequested = exitRequested,
        onExitRequestHandled = onExitRequestHandled,
        // Pause lives on the run body so the takeover gets the same control;
        // `RunBody` itself decides whether to show it from the routine setting.
        onTogglePause = onTogglePause,
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
