package com.adhd.routineplanner.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhd.routineplanner.domain.Schedule
import com.adhd.routineplanner.ui.theme.Accent
import com.adhd.routineplanner.ui.theme.GridLine
import com.adhd.routineplanner.ui.theme.Surface
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A draggable 24-hour clock face.
 *
 * The user drags a hand around the dial and the time follows the finger. This
 * replaces the old ±15-minute step buttons, which could only ever land on
 * quarter hours and took a dozen taps to cross a morning.
 *
 * ## How the angle maps to a time
 *
 * Midnight is at the top and the dial runs clockwise, so one full turn is 24
 * hours. The angle is converted straight to a minute of day:
 *
 * ```
 * minuteOfDay = angle / 2π * 1440
 * ```
 *
 * ## Precision
 *
 * The dial is *always* minute-accurate — the readout in the middle shows the
 * exact minute under the finger. What changes is the **snap**:
 *
 *  - Dragging near the rim snaps to 5-minute steps, which is easy to hit with a
 *    thumb and covers the common "quarter past" style choices.
 *  - Dragging closer to the centre snaps to 1-minute steps, for when the user
 *    genuinely wants 07:23.
 *
 * The snap radius is a fraction of the dial radius, so it behaves the same at
 * any size. A haptic tick fires whenever the snapped minute changes, so the
 * user can feel the steps without looking.
 *
 * @param minuteOfDay the currently selected minute of day, `0..1439`.
 * @param onMinuteChange called with the new minute of day as the user drags.
 * @param modifier applied to the whole dial column.
 * @param label a small caption under the dial, e.g. "Start".
 * @param accent the colour of the hand and the selected arc.
 */
@Composable
fun ClockDial(
    minuteOfDay: Int,
    onMinuteChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    accent: Color = Accent,
) {
    val haptics = LocalHapticFeedback.current
    val textMeasurer = rememberTextMeasurer()

    // The gesture callbacks are captured once by pointerInput, so read the
    // latest values through rememberUpdatedState to avoid a stale lambda.
    val currentMinute by rememberUpdatedState(minuteOfDay)
    val currentOnChange by rememberUpdatedState(onMinuteChange)

    // Last snapped minute, so a haptic tick only fires on an actual change.
    var lastTick by remember { mutableIntStateOf(minuteOfDay) }

    /** Converts a touch position to a snapped minute of day. */
    fun minuteAt(position: Offset, width: Int, height: Int): Int {
        val centre = Offset(width / 2f, height / 2f)
        val dx = position.x - centre.x
        val dy = position.y - centre.y

        // atan2 with y inverted so 12 o'clock is zero and the dial runs
        // clockwise. Adding 2π keeps the result positive.
        var angle = atan2(dx, -dy)
        if (angle < 0f) angle += (2 * PI).toFloat()

        val rawMinute = angle / (2 * PI).toFloat() * Schedule.MINUTES_PER_DAY

        // Near the rim snap to 5 minutes; near the centre snap to 1 minute.
        val radius = minOf(width, height) / 2f
        val distance = hypot(dx, dy)
        val nearRim = distance > radius * RIM_SNAP_FRACTION
        val step = if (nearRim) RIM_SNAP_MINUTES else FINE_SNAP_MINUTES

        val snapped = (rawMinute / step).roundToInt() * step
        return ((snapped % Schedule.MINUTES_PER_DAY) + Schedule.MINUTES_PER_DAY) %
            Schedule.MINUTES_PER_DAY
    }

    /** Applies a new minute, ticking the haptics when it changes. */
    fun apply(minute: Int) {
        if (minute != lastTick) {
            lastTick = minute
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        currentOnChange(minute)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .semantics {
                    contentDescription =
                        "Clock dial, currently ${formatDialTime(minuteOfDay)}"
                }
                .pointerInput(Unit) {
                    detectTapGestures { position ->
                        apply(minuteAt(position, size.width, size.height))
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { position ->
                            apply(minuteAt(position, size.width, size.height))
                        },
                    ) { change, _ ->
                        change.consume()
                        apply(minuteAt(change.position, size.width, size.height))
                    }
                },
        ) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                drawDial(
                    minuteOfDay = currentMinute,
                    accent = accent,
                    textMeasurer = textMeasurer,
                )
            }
        }

        if (label != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
    }
}

/** Fraction of the radius beyond which the dial snaps to 5-minute steps. */
private const val RIM_SNAP_FRACTION = 0.55f

/** Snap step used near the rim of the dial. */
private const val RIM_SNAP_MINUTES = 5

/** Snap step used near the centre of the dial. */
private const val FINE_SNAP_MINUTES = 1

/** Draws the face, the hour ticks, the numerals, the selected arc and the hand. */
private fun DrawScope.drawDial(
    minuteOfDay: Int,
    accent: Color,
    textMeasurer: TextMeasurer,
) {
    val radius = size.minDimension / 2f
    val centre = Offset(size.width / 2f, size.height / 2f)
    val rim = radius * 0.94f

    // Face.
    drawCircle(color = Surface, radius = rim, center = centre)

    // Hour ticks. Midnight is at the top; every hour is 15 degrees.
    for (hour in 0 until 24) {
        val angle = hour / 24f * 2f * PI.toFloat()
        val major = hour % 6 == 0
        val outer = rim
        val inner = rim - if (major) radius * 0.10f else radius * 0.05f
        drawLine(
            color = if (major) TextSecondary else GridLine,
            start = pointOnCircle(centre, inner, angle),
            end = pointOnCircle(centre, outer, angle),
            strokeWidth = if (major) 2.5f else 1.5f,
            cap = StrokeCap.Round,
        )
    }

    // Numerals at 00, 06, 12 and 18 — enough to orient without clutter.
    listOf(0, 6, 12, 18).forEach { hour ->
        val angle = hour / 24f * 2f * PI.toFloat()
        val label = hour.toString().padStart(2, '0')
        val layout = textMeasurer.measure(
            text = label,
            style = TextStyle(
                color = TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        val anchor = pointOnCircle(centre, rim * 0.74f, angle)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                anchor.x - layout.size.width / 2f,
                anchor.y - layout.size.height / 2f,
            ),
        )
    }

    // The selected arc, from midnight round to the current minute.
    val sweep = minuteOfDay / Schedule.MINUTES_PER_DAY.toFloat() * 360f
    if (sweep > 0f) {
        drawArc(
            color = accent.copy(alpha = 0.22f),
            startAngle = -90f,
            sweepAngle = sweep,
            useCenter = true,
            topLeft = Offset(centre.x - rim, centre.y - rim),
            size = Size(rim * 2f, rim * 2f),
        )
    }

    // The hand.
    val handAngle = minuteOfDay / Schedule.MINUTES_PER_DAY.toFloat() * 2f * PI.toFloat()
    drawLine(
        color = accent,
        start = centre,
        end = pointOnCircle(centre, rim * 0.86f, handAngle),
        strokeWidth = 4f,
        cap = StrokeCap.Round,
    )

    // The knob on the rim, so the grab point is obvious.
    val knob = pointOnCircle(centre, rim * 0.86f, handAngle)
    drawCircle(color = accent, radius = radius * 0.075f, center = knob)
    drawCircle(color = Surface, radius = radius * 0.032f, center = knob)

    // The hub.
    drawCircle(color = accent, radius = radius * 0.045f, center = centre)

    // The readout, drawn on a pill so it stays legible over the arc.
    val readout = formatDialTime(minuteOfDay)
    val layout = textMeasurer.measure(
        text = readout,
        style = TextStyle(
            color = TextPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
    val pillWidth = layout.size.width + 28f
    val pillHeight = layout.size.height + 14f
    val pillTopLeft = Offset(
        centre.x - pillWidth / 2f,
        centre.y + radius * 0.30f,
    )
    drawRoundRect(
        color = Surface,
        topLeft = pillTopLeft,
        size = Size(pillWidth, pillHeight),
        cornerRadius = CornerRadius(pillHeight / 2f),
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            centre.x - layout.size.width / 2f,
            pillTopLeft.y + (pillHeight - layout.size.height) / 2f,
        ),
    )
}

/** A point on a circle, with zero radians at 12 o'clock and clockwise positive. */
private fun pointOnCircle(centre: Offset, radius: Float, angle: Float): Offset =
    Offset(
        x = centre.x + radius * sin(angle),
        y = centre.y - radius * cos(angle),
    )

/** Formats a minute of day as `HH:mm`. */
internal fun formatDialTime(minuteOfDay: Int): String {
    val wrapped = ((minuteOfDay % Schedule.MINUTES_PER_DAY) + Schedule.MINUTES_PER_DAY) %
        Schedule.MINUTES_PER_DAY
    val hour = wrapped / 60
    val minute = wrapped % 60
    return "%02d:%02d".format(hour, minute)
}

/**
 * A compact dial for the full editor, where the dial shares the screen with the
 * rest of the form. Same behaviour as [ClockDial], just inset so it does not
 * touch the screen edges.
 */
@Composable
fun CompactClockDial(
    minuteOfDay: Int,
    onMinuteChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Accent,
) {
    ClockDial(
        minuteOfDay = minuteOfDay,
        onMinuteChange = onMinuteChange,
        modifier = modifier.padding(horizontal = 24.dp),
        accent = accent,
    )
}
