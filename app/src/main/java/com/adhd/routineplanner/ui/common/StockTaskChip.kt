package com.adhd.routineplanner.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adhd.routineplanner.domain.StockTask
import com.adhd.routineplanner.ui.settings.StockTaskIcons
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.SurfaceElevated
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary

/** A stock task rendered as a chip, with the data needed to add it. */
data class StockChip(
    val name: String,
    val minutes: Int,
    val color: Int,
    val icon: String,
    val stock: StockTask,
)

/** Builds the chip for [task]. */
fun StockTask.toChip(): StockChip = StockChip(
    name = name,
    minutes = minutes,
    color = color,
    icon = icon,
    stock = this,
)

/**
 * One stock-task chip: a rounded, colour-tinted pill with an icon, the task name
 * and its duration. Chips size to their content so several fit per row and the
 * flow stays tidy rather than becoming a rigid grid.
 *
 * Shared by the create flow's task step and the settings page so both show the
 * same thing; only the gesture map differs.
 *
 * @param picked whether the task is already in the routine being built. A picked
 *   chip is drawn filled and shows a tick.
 * @param onDoubleClick an optional secondary action. Used by the create flow so
 *   a single tap adds the task straight away while a double tap opens the
 *   duration picker.
 * @param onDelete when non-null, a small cross is drawn inside the chip. Used for
 *   the user's own stock tasks, which they are allowed to remove.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StockTaskChip(
    chip: StockChip,
    modifier: Modifier = Modifier,
    picked: Boolean = false,
    onClick: () -> Unit = {},
    onDoubleClick: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val tint = Color(chip.color)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (picked) tint else SurfaceElevated)
            .border(
                width = 1.dp,
                color = if (picked) tint else tint.copy(alpha = 0.35f),
                shape = RoundedCornerShape(50),
            )
            .combinedClickable(onClick = onClick, onDoubleClick = onDoubleClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = StockTaskIcons.iconFor(chip.icon),
            contentDescription = null,
            tint = if (picked) Background else tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = chip.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (picked) Background else TextPrimary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (picked) "✓" else "${chip.minutes}m",
            style = MaterialTheme.typography.labelSmall,
            color = if (picked) Background.copy(alpha = 0.8f) else TextSecondary,
        )
        if (onDelete != null) {
            Spacer(Modifier.width(5.dp))
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.22f))
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Delete ${chip.name}",
                    tint = tint,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}
