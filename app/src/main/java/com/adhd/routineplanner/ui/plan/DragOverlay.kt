package com.adhd.routineplanner.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** How far above the finger the ghost floats so the finger does not hide it. */
private val GhostLift: androidx.compose.ui.unit.Dp = 40.dp

/** Ghost scale (spec §15.5). */
private const val GhostScale: Float = 1.05f

/**
 * The floating ghost shown while a task or tray chip is dragged (spec §15.5).
 *
 * Draws the task name and minutes on a rounded, elevated chip in the task
 * colour, offset about 40dp above the finger. Positioned with an absolute
 * [IntOffset] inside a top-level overlay so it can leave the grid bounds.
 *
 * @param task the task being dragged.
 * @param fingerPosition the current pointer position in the overlay's
 *   coordinate space.
 * @param overTray true while the finger is over the tray; the ghost tints red
 *   to signal "remove".
 */
@Composable
fun DragGhost(
    task: GridTask,
    fingerPosition: Offset,
    overTray: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val liftPx = with(density) { GhostLift.toPx() }
    val color = Color(task.color)
    val labelColor = if (color.luminance() > 0.55f) Color(0xFF101216) else Color(0xFFF7F8FA)
    val tint = if (overTray) Color(0xFFFF3B30) else color

    Box(
        modifier = modifier
            .offset {
                // Lift only vertically so the ghost floats above the finger;
                // horizontally it stays centred on the pointer.
                IntOffset(
                    x = fingerPosition.x.roundToInt(),
                    y = (fingerPosition.y - liftPx).roundToInt(),
                )
            }
            .graphicsLayer {
                scaleX = GhostScale
                scaleY = GhostScale
            }
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(tint)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = task.name,
                style = MaterialTheme.typography.labelLarge,
                color = labelColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${task.minutes} min",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor.copy(alpha = 0.8f),
                maxLines = 1,
            )
        }
    }
}
