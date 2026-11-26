package com.adhd.routineplanner.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adhd.routineplanner.ui.theme.CountdownRed
import com.adhd.routineplanner.ui.theme.Surface
import com.adhd.routineplanner.ui.theme.TextSecondary

/**
 * The bottom bar of the plan screen (spec §15.3).
 *
 * Adding a task now lives in the top bar, where it is always visible. This bar
 * remains as the drag-to-remove target: while a placed task is dragged over it
 * the bar turns red to signal "release to remove" (spec §15.5). When nothing is
 * being dragged it shows a quiet hint pointing at the top-bar add button.
 *
 * @param removeActive true while a placed task hovers the bar.
 */
@Composable
fun TaskTray(
    removeActive: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (removeActive) CountdownRed.copy(alpha = 0.25f) else Surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (removeActive) {
                    "Release to remove"
                } else {
                    "Drag a task here to remove it"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (removeActive) CountdownRed else TextSecondary,
            )
        }
    }
}
