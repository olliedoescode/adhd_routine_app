package com.adhd.routineplanner.ui.run

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhd.routineplanner.domain.Phase
import com.adhd.routineplanner.domain.Schedule
import com.adhd.routineplanner.domain.StockTaskCatalog
import com.adhd.routineplanner.ui.theme.CountdownAmber
import com.adhd.routineplanner.ui.theme.CountdownRed
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary
import com.adhd.routineplanner.ui.theme.washed
import com.adhd.routineplanner.ui.settings.StockTaskIcons
import com.adhd.routineplanner.viewmodel.RunUiState
import kotlin.math.PI
import kotlin.math.sin

/**
 * The "Now / Next" run card (proposal §3.2).
 *
 * The whole screen is one giant card for the task that is running right now:
 * its colour washes the background, its name is the largest thing on screen,
 * and the countdown sits directly under it. A slim "Next: …" line at the bottom
 * keeps the following step visible without competing for attention.
 *
 * The full minute grid is gone from the main area — it was the wrong thing to
 * stare at while trying to do a task. Instead a compact **corner mini-grid**
 * lives in the bottom-right: a block of square minute cells that shows where the
 * run is in its window at a glance, labelled only with the routine's start time
 * (top), its end time (bottom) and the time left in the current task (centre).
 * That is the three pieces of time information a person actually needs mid-run;
 * every individual minute number was noise.
 *
 * @param state the live run snapshot.
 * @param waterLevel the animated fill level for the card background (0..1).
 */
@Composable
fun RunNowNextCard(
    state: RunUiState,
    waterLevel: Float,
    modifier: Modifier = Modifier,
) {
    val runState = state.state
    val current = state.currentTask
    val next = state.nextTask

    val taskColor = when (runState.phase) {
        Phase.BeforeStart -> next?.color ?: DEFAULT_COLOR
        Phase.Finished -> current?.color ?: DEFAULT_COLOR
        Phase.Running -> current?.color ?: DEFAULT_COLOR
    }
    val color = Color(taskColor)

    val title = when (runState.phase) {
        Phase.BeforeStart -> next?.name ?: "Ready"
        Phase.Finished -> "Done"
        Phase.Running -> current?.name ?: "Done"
    }

    // The icon of the task the card is about: the one running, or the one about
    // to start before the run begins. Once finished there is no task, so the
    // badge is hidden. This is the same icon the notification uses, so a stock
    // task keeps its identity from the picker all the way through the run.
    //
    // Placed tasks freeze the icon they were created with, so a routine built
    // before the icons were repaired still carries the old default for its
    // task name — a moon on a workout. Resolving through the catalogue at draw
    // time upgrades those without touching the stored row.
    val iconKey = when (runState.phase) {
        Phase.BeforeStart -> next?.let { StockTaskCatalog.resolveIcon(it.name, it.icon) }
        Phase.Finished -> null
        Phase.Running -> current?.let { StockTaskCatalog.resolveIcon(it.name, it.icon) }
    }

    val countdownColor = when {
        runState.phase != Phase.Running -> TextPrimary
        runState.secondsRemaining <= 10 -> CountdownRed
        runState.secondsRemaining <= 60 -> CountdownAmber
        else -> TextPrimary
    }

    // The card's "empty" (unfilled) colour: a deep wash of the task colour,
    // dark enough that the white name and countdown stay legible. The card
    // slowly fills from the bottom with the task's full colour as the task
    // progresses, so a glance shows how far through the current task you are.
    val emptyColor = washed(color, factor = 0.82f)
    val fillColor = color

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
            .background(emptyColor),
    ) {
        // --- Slow fill-up background (wavy) -------------------------------
        // A full-colour body anchored to the bottom whose surface tracks the
        // task's progress (0..1). The top edge is a gently moving sine wave, so
        // the fill reads as liquid creeping up the card rather than a flat bar
        // snapping into place. It is drawn behind the text so the name and
        // countdown stay readable, and the level animates over a long, linear
        // tween so it rises smoothly over the whole task.
        //
        // The wave phase is read inside the draw scope, so only this Canvas
        // redraws each frame — not the whole card (spec section 18).
        //
        // Seamless loop: the phase runs 0..2π and *every* sine term uses an
        // integer multiple of it, so at the loop boundary (2π → 0) each term
        // lands on the same value it started from. The old code multiplied the
        // second wave by 1.5, which is not a whole cycle, so the surface jumped
        // every loop — the harsh "cut" the user saw. Integer multipliers remove
        // that entirely, so the motion is continuous and calm.
        val waveTransition = rememberInfiniteTransition(label = "cardWave")
        val wavePhase by waveTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = CARD_WAVE_PERIOD_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "cardWavePhase",
        )
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(28.dp)),
        ) {
            val level = size.height * (1f - waterLevel.coerceIn(0f, 1f))
            // Two overlapping waves give the surface a gentle, organic ripple
            // rather than a single mechanical sine. Both use whole-number phase
            // multipliers so the loop is seamless (see the note above).
            val amplitude = size.height * CARD_WAVE_AMPLITUDE
            val water = Path().apply {
                moveTo(0f, level)
                val step = size.width / 64f
                var x = 0f
                while (x <= size.width) {
                    val t = x / size.width
                    val y = level +
                        sin(t * CARD_WAVE_CRESTS * 2f * PI.toFloat() + wavePhase) * amplitude +
                        sin(t * CARD_WAVE_CRESTS * 4f * PI.toFloat() - wavePhase * 2f) *
                        amplitude * 0.35f
                    lineTo(x, y)
                    x += step
                }
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(path = water, color = fillColor)
        }
        // --- The giant task card body -------------------------------------
        // Horizontally centred, and biased slightly above the vertical middle
        // so the eye lands on the task name rather than the dead centre.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 20.dp)
                .padding(bottom = 96.dp), // clear the bottom-right mini-grid
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (iconKey != null) {
                // A soft badge so the icon reads against the washed card colour
                // without competing with the task name.
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(TextPrimary.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = StockTaskIcons.iconFor(iconKey),
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
            }

            Text(
                text = when (runState.phase) {
                    Phase.BeforeStart -> "UP NEXT"
                    Phase.Finished -> "FINISHED"
                    Phase.Running -> "NOW"
                },
                // An eyebrow, not a heading: the size and tracking already say
                // "label", so it does not need weight on top of that.
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))

            Crossfade(
                targetState = title,
                animationSpec = tween(durationMillis = BOUNDARY_MS),
                label = "nowNextTitle",
            ) { name ->
                Text(
                    text = name,
                    color = TextPrimary,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(20.dp))

            when (runState.phase) {
                Phase.BeforeStart -> Countdown(
                    secondsRemaining = runState.secondsRemaining,
                    prefix = "Starts in ",
                    color = TextPrimary,
                )
                Phase.Running -> Countdown(
                    secondsRemaining = runState.secondsRemaining,
                    color = countdownColor,
                )
                Phase.Finished -> Text(
                    text = "Nice work.",
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // --- Corner mini-grid (bottom-right) ------------------------------
        // The grid sits in a rounded panel in the card's *unfilled* colour, so
        // the little grid reads as a window cut into the card. No coloured
        // outline: the bright ring competed with the cells.
        MiniGrid(
            state = state,
            emptyColor = emptyColor,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp)
                .width(MINI_GRID_WIDTH)
                .height(MINI_GRID_HEIGHT),
        )

        // --- Next / Last line (bottom-left) -------------------------------
        // Always shown while a run is in progress. When there is no following
        // task the current one is the last, so it says so instead of vanishing.
        //
        // The line sits on a rounded pill in the card's *unfilled* colour, the
        // same tone as the mini-grid panel, so it reads as a deliberate chip
        // rather than loose text floating over the fill. That also keeps it
        // legible once the water rises past it: the pill stays the empty colour
        // while the water behind it is the full task colour.
        if (runState.phase != Phase.Finished) {
            val following = next
            val label = when {
                following != null -> "Next: ${following.name}"
                runState.phase == Phase.Running -> "Last task"
                else -> null
            }
            if (label != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 20.dp, bottom = 20.dp)
                        .padding(end = MINI_GRID_WIDTH + 28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(emptyColor)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(
                                Color(following?.color ?: current?.color ?: DEFAULT_COLOR),
                            ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The compact corner grid: a real grid of **square** minute cells for the whole
 * routine window, laid out top-to-bottom in reading order.
 *
 * This is the same per-minute colouring the full run grid uses — elapsed
 * minutes in the task's full colour, the current minute filled up to the
 * fraction of the minute that has passed, future minutes washed toward the
 * surface — so the corner reads as a faithful miniature of the run rather than
 * a single progress bar.
 *
 * Cells are always square: the column count is chosen from the routine length
 * and the available width, and the cell edge is then derived from that width,
 * so a cell is never stretched into a rectangle.
 *
 * Only three labels are drawn — the routine start at the top, the routine end
 * at the bottom, and the current task's remaining time in a badge — because
 * those are the only numbers that matter mid-run.
 */
@Composable
private fun MiniGrid(
    state: RunUiState,
    emptyColor: Color,
    modifier: Modifier = Modifier,
) {
    val runState = state.state
    val totalMinutes = state.totalMinutes.coerceAtLeast(1)
    // The clock the grid is labelled with is the *real* run start, not the
    // routine's scheduled start: a test run started from the plan screen begins
    // now, so a 7:30 routine tested at 9pm must read 9:00 → 9:25, not 7:30.
    val startMinuteOfDay = state.runStartMinuteOfDay
    val endMinuteOfDay = state.runEndMinuteOfDay

    // Fractional minute position of the cursor, 0..totalMinutes.
    val targetMinute = when (runState.phase) {
        Phase.BeforeStart -> 0f
        Phase.Finished -> totalMinutes.toFloat()
        Phase.Running -> (runState.elapsedSeconds / 60f).coerceIn(0f, totalMinutes.toFloat())
    }
    val cursorMinute by animateFloatAsState(
        targetValue = targetMinute,
        animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
        label = "miniGridCursor",
    )

    // Each minute's colour, in task order, so the grid can paint per cell.
    val minuteColors = remember(state.tasks, state.renderedMinutes) {
        val colors = IntArray(totalMinutes)
        var minute = 0
        state.tasks.forEachIndexed { index, task ->
            val length = state.renderedMinutes.getOrNull(index) ?: task.minutes
            repeat(length) {
                if (minute < colors.size) colors[minute] = task.color
                minute++
            }
        }
        colors
    }

    // The panel: a rounded box in the card's *unfilled* colour, so the grid
    // reads as a window cut into the card. There is deliberately no coloured
    // outline around it — the bright ring competed with the cells and made the
    // corner look boxed-in rather than part of the card.
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(emptyColor)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        // Start time label.
        Text(
            text = Schedule.formatMinuteOfDay(startMinuteOfDay),
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(4.dp))

        // The grid itself.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Pick the column count that keeps cells square for this many
                // minutes in the available box, then derive the square edge.
                val cols = miniGridCols(totalMinutes, size.width, size.height)
                val rows = (totalMinutes + cols - 1) / cols
                val gap = 1.dp.toPx()
                // Square edge: the smaller of the width- and height-constrained
                // sizes, so cells are never stretched.
                val cellW = (size.width - gap * (cols - 1)) / cols
                val cellH = (size.height - gap * (rows - 1)) / rows
                val side = minOf(cellW, cellH)
                val corner = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())

                // Centre the square block of cells within the box.
                val gridW = side * cols + gap * (cols - 1)
                val gridH = side * rows + gap * (rows - 1)
                val originX = (size.width - gridW) / 2f
                val originY = (size.height - gridH) / 2f

                // Cells are laid out in reading order — left to right, then
                // down — so the grid visibly fills from the top-left as the
                // routine progresses. Elapsed minutes are solid in their task
                // colour; minutes still to come are a dark, near-empty track
                // tinted faintly with their task colour, so the boundary
                // between "done" and "to do" is obvious at a glance.
                val cursorWhole = cursorMinute.toInt()
                for (minute in 0 until totalMinutes) {
                    val row = minute / cols
                    val col = minute % cols
                    val left = originX + col * (side + gap)
                    val top = originY + row * (side + gap)
                    val cellColor = Color(minuteColors.getOrElse(minute) { DEFAULT_COLOR })

                    when {
                        minute < cursorWhole -> {
                            // Fully elapsed: solid task colour.
                            drawRoundRect(
                                color = cellColor,
                                topLeft = Offset(left, top),
                                size = Size(side, side),
                                cornerRadius = corner,
                            )
                        }
                        minute == cursorWhole -> {
                            // Current minute: empty track with a partial fill
                            // that grows left-to-right across the cell.
                            drawRoundRect(
                                color = washed(cellColor, factor = 0.88f),
                                topLeft = Offset(left, top),
                                size = Size(side, side),
                                cornerRadius = corner,
                            )
                            val fraction = (cursorMinute - minute).coerceIn(0f, 1f)
                            if (fraction > 0f) {
                                drawRoundRect(
                                    color = cellColor,
                                    topLeft = Offset(left, top),
                                    size = Size(side * fraction, side),
                                    cornerRadius = corner,
                                )
                            }
                        }
                        else -> {
                            // Still to come: dark track, faint task tint.
                            drawRoundRect(
                                color = washed(cellColor, factor = 0.88f),
                                topLeft = Offset(left, top),
                                size = Size(side, side),
                                cornerRadius = corner,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        // End time label.
        Text(
            text = Schedule.formatMinuteOfDay(endMinuteOfDay),
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
        }
    }
}

/**
 * Chooses the column count for the corner grid so its cells stay square.
 *
 * Tries each candidate column count from [MINI_GRID_MIN_COLS] upward and keeps
 * the one whose resulting square cell is largest while still fitting the box.
 * A routine of a few minutes gets few columns; a long routine gets more, so the
 * block stays roughly square overall.
 */
private fun miniGridCols(totalMinutes: Int, widthPx: Float, heightPx: Float): Int {
    if (totalMinutes <= 0 || widthPx <= 0f || heightPx <= 0f) return MINI_GRID_MIN_COLS
    var bestCols = MINI_GRID_MIN_COLS
    var bestSide = 0f
    for (cols in MINI_GRID_MIN_COLS..MINI_GRID_MAX_COLS) {
        val rows = (totalMinutes + cols - 1) / cols
        val side = minOf(widthPx / cols, heightPx / rows)
        if (side > bestSide) {
            bestSide = side
            bestCols = cols
        }
    }
    return bestCols
}

/** Width of the corner mini-grid box. */
private val MINI_GRID_WIDTH = 112.dp

/** Height of the corner mini-grid box. */
private val MINI_GRID_HEIGHT = 150.dp

/** Fewest columns the corner grid will use. */
private const val MINI_GRID_MIN_COLS: Int = 3

/** Most columns the corner grid will use. */
private const val MINI_GRID_MAX_COLS: Int = 8

/** Neutral fallback colour, matching [com.adhd.routineplanner.domain.PlacedTask]. */
private val DEFAULT_COLOR: Int = 0xFF8E9AAF.toInt()

/** Duration of the task-boundary crossfade, matching the run screen. */
private const val BOUNDARY_MS = 200

/**
 * One full cycle of the card's fill wave, in milliseconds.
 *
 * Deliberately slow (a full cycle every ~9 seconds) so the surface drifts
 * gently rather than rippling frantically. The old 3.2s cycle read as busy and
 * "sporadic"; a slow drift is what makes it feel like liquid settling.
 */
private const val CARD_WAVE_PERIOD_MS = 9_000

/**
 * Amplitude of the card's fill wave, as a fraction of the card height.
 *
 * Small on purpose: the surface should look like a calm waterline, not a
 * choppy sea. The old 0.018 was too tall and made the fill edge wobble
 * distractingly.
 */
private const val CARD_WAVE_AMPLITUDE = 0.010f

/**
 * Number of wave crests across the card width.
 *
 * Fewer, wider crests read as a slow swell; many narrow crests read as noise.
 */
private const val CARD_WAVE_CRESTS = 1.4f
