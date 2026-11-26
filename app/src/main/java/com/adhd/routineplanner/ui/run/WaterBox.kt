package com.adhd.routineplanner.ui.run

import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhd.routineplanner.ui.theme.washed
import kotlin.math.PI
import kotlin.math.sin

/** Height of the header water box (spec section 15.8). */
private val WaterBoxHeight = 110.dp

/** Corner radius of the water box (spec section 15.8). */
private val WaterBoxCorner = 16.dp

/** One full wave cycle (spec section 8.5: about 3 s). */
private const val WAVE_PERIOD_MS = 3000

/** Wave amplitude as a fraction of the box height. */
private const val WAVE_AMPLITUDE = 0.035f

/** Number of wave crests across the width. */
private const val WAVE_CRESTS = 2.5f

/**
 * The run header water box (spec sections 8.5, 15.8).
 *
 * A rounded rect with a washed task-colour background and a bright water body
 * rising from the bottom by [progress]. The surface is a sine wave driven by an
 * infinite phase animation. The task name sits centred in white with a subtle
 * shadow; the caller places the countdown below it.
 *
 * The wave phase is read inside the draw scope so only this Canvas redraws each
 * frame, not the whole screen (spec section 18).
 *
 * @param name the current task's name (or a status label).
 * @param color the task's ARGB colour.
 * @param progress fraction of the task elapsed, 0..1.
 * @param nameAlignment where the name sits inside the box. The run header puts
 *   the countdown along the bottom edge, so it passes [Alignment.TopCenter] to
 *   keep the two from overlapping.
 */
@Composable
fun WaterBox(
    name: String,
    color: Int,
    progress: Float,
    modifier: Modifier = Modifier,
    nameAlignment: Alignment = Alignment.Center,
) {
    val taskColor = Color(color)
    val transition = rememberInfiniteTransition(label = "water")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = WAVE_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavePhase",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(WaterBoxHeight)
            .clip(RoundedCornerShape(WaterBoxCorner)),
        contentAlignment = nameAlignment,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val corner = WaterBoxCorner.toPx()
            val radius = CornerRadius(corner, corner)

            // Washed background.
            drawRoundRect(
                color = washed(taskColor, 0.7f),
                size = size,
                cornerRadius = radius,
            )

            // Clip the water to the rounded rect so it never spills the corners.
            val clip = Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        rect = androidx.compose.ui.geometry.Rect(Offset.Zero, size),
                        cornerRadius = radius,
                    ),
                )
            }
            clipPath(clip) {
                val level = size.height * (1f - progress.coerceIn(0f, 1f))
                val amplitude = size.height * WAVE_AMPLITUDE
                val water = Path().apply {
                    moveTo(0f, level)
                    var x = 0f
                    val step = size.width / 48f
                    while (x <= size.width) {
                        val t = x / size.width
                        val y = level + sin(t * WAVE_CRESTS * 2f * PI.toFloat() + phase) * amplitude
                        lineTo(x, y)
                        x += step
                    }
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(path = water, color = taskColor)
            }
        }

        Text(
            text = name,
            style = TextStyle(
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.55f),
                    offset = Offset(0f, 2f),
                    blurRadius = 4f,
                ),
            ),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}
