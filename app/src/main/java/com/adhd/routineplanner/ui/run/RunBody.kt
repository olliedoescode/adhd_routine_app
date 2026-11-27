package com.adhd.routineplanner.ui.run

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhd.routineplanner.domain.Phase
import com.adhd.routineplanner.ui.theme.Accent
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.CountdownRed
import com.adhd.routineplanner.ui.theme.Surface
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary
import com.adhd.routineplanner.viewmodel.RunUiState

/**
 * Duration of the card's slow background fill. Long and linear so the colour
 * creeps up the card over the course of a task rather than snapping each
 * second — the "slowly fill up" effect the run card is meant to have.
 */
internal const val FILL_MS = 1_500

/**
 * The shared run body: the Now/Next card, filling the available space, with the
 * slow fill-up animation driven from the live run state, and the single "End
 * routine" pill pinned underneath it.
 *
 * This is the single source of truth for what a running routine looks like. The
 * in-app run screen ([RunScreen]) and the lock-screen takeover
 * ([com.adhd.routineplanner.alarm.RunTakeoverActivity]) both render *this*
 * composable, so the two are guaranteed to be the same page — same card, same
 * fill animation, same corner grid, and the same End routine control — rather
 * than two copies that drift apart.
 *
 * The End routine pill and its confirm dialog live here, not in [RunScreen], so
 * the takeover gets them for free. The caller only supplies [onExit], which is
 * invoked once the user has confirmed they want to end the run; what "ending"
 * means (stopping the session, cancelling the notification, closing the screen)
 * is the caller's business.
 *
 * When the routine allows it, a Pause/Resume pill is shown next to the End
 * routine pill. Pausing is per-routine: the setting lives on the routine, so a
 * routine you want to be able to interrupt gets the control and one you want to
 * push straight through does not. The pill is only offered while a task is
 * actually running — there is nothing to pause before the start or once the
 * routine has finished.
 *
 * @param state the live run snapshot.
 * @param modifier layout modifier for the whole body.
 * @param onExit invoked after the user confirms "End routine" in the dialog.
 * @param onTogglePause toggles pause/resume. `null` hides the pause control
 *   entirely; callers pass it only where they can service it (the in-app run
 *   screen and the takeover both can).
 * @param exitRequested when true, the confirm dialog is shown even though the
 *   pill was not tapped. The run screen uses this to route the system back
 *   gesture through the same two-step exit; the takeover leaves it false.
 * @param onExitRequestHandled called once the dialog has been dismissed, so the
 *   caller can reset its [exitRequested] flag.
 * @param cardModifier extra modifier applied to the card only (not the End
 *   routine pill). The takeover uses this to make tapping/swiping the card
 *   dismiss the overlay without swallowing taps on the End routine button.
 */
@Composable
fun RunBody(
    state: RunUiState,
    modifier: Modifier = Modifier,
    onExit: () -> Unit,
    exitRequested: Boolean = false,
    onExitRequestHandled: () -> Unit = {},
    cardModifier: Modifier = Modifier,
    onTogglePause: (() -> Unit)? = null,
) {
    val runState = state.state

    // Pause is offered only when the routine says so *and* there is a running
    // task whose clock can be stopped. Before the start and after the finish
    // both buttons would be a no-op, so the control simply is not there.
    val showPause = onTogglePause != null &&
        state.routine?.allowPause == true &&
        runState.phase == Phase.Running

    // Water level: the current task's progress. Before start it is empty, once
    // finished it is full. The card background slowly fills from the bottom as
    // the task progresses, so the level is animated with a long, linear tween
    // rather than a short boundary tween — it should creep up smoothly over the
    // whole task, not snap on each tick. A task change still drains/refills
    // (the target jumps), but the motion stays gentle.
    val targetLevel = when (runState.phase) {
        Phase.BeforeStart -> 0f
        Phase.Finished -> 1f
        Phase.Running -> runState.progress
    }
    val waterLevel by animateFloatAsState(
        targetValue = targetLevel,
        animationSpec = tween(durationMillis = FILL_MS, easing = LinearEasing),
        label = "waterLevel",
    )

    // The confirm dialog is owned here so both surfaces get the same two-step
    // exit: the pill never ends the run on a single tap. An external
    // [exitRequested] (the run screen's back gesture) also opens it.
    var showExitDialog by remember { mutableStateOf(false) }
    LaunchedEffect(exitRequested) {
        if (exitRequested) showExitDialog = true
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The activity is edge-to-edge, so the card would otherwise sit
                // under the status bar. Consume the system bars here so the card
                // and the control below it clear the status bar and gesture nav.
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            RunNowNextCard(
                state = state,
                waterLevel = waterLevel,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .then(cardModifier),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 20dp side margins keep both pills clear of Samsung's
                    // edge-swipe back gesture, and the extra bottom padding
                    // lifts them off the gesture nav bar.
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (showPause) {
                    PauseButton(
                        paused = state.isPaused,
                        onToggle = onTogglePause,
                        modifier = Modifier.weight(1f),
                    )
                }
                EndRoutineButton(
                    onExit = { showExitDialog = true },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (showExitDialog) {
            ExitDialog(
                onDismiss = {
                    showExitDialog = false
                    onExitRequestHandled()
                },
                onConfirm = {
                    showExitDialog = false
                    onExitRequestHandled()
                    onExit()
                },
            )
        }
    }
}

/**
 * The single, explicit "End routine" action at the bottom of the run body
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
private fun EndRoutineButton(onExit: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onExit,
        modifier = modifier
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
            maxLines = 1,
        )
    }
}

/**
 * The Pause/Resume pill, shown only for routines whose `allowPause` is set.
 *
 * It sits beside the End routine pill and is deliberately the calmer of the
 * two: cool loon blue rather than the red of the exit. While paused the icon
 * and label flip to Resume, which is the only affordance needed — the countdown
 * on the card is already frozen, and the notification says "Paused".
 */
@Composable
private fun PauseButton(
    paused: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onToggle,
        modifier = modifier
            .height(60.dp)
            .border(
                width = 1.dp,
                color = Accent.copy(alpha = 0.55f),
                shape = RoundedCornerShape(30.dp),
            ),
        shape = RoundedCornerShape(30.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Accent.copy(alpha = 0.16f),
            contentColor = TextPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        Icon(
            imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (paused) "Resume" else "Pause",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
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
        shape = RoundedCornerShape(28.dp),
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
