package com.adhd.routineplanner.ui.run

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.adhd.routineplanner.ui.theme.CountdownRed
import com.adhd.routineplanner.ui.theme.CountdownTextStyle

/**
 * The red `-M:SS` countdown (spec sections 8.6, 15.8).
 *
 * Uses the countdown font family (DSEG when bundled, tabular monospace until
 * then) so the digits do not jitter as they change. Minutes are not capped at
 * 99; a long routine shows `-123:45` rather than wrapping.
 *
 * @param secondsRemaining seconds left in the current task (or until start).
 * @param prefix the leading sign text; `-` while counting down, empty when done.
 */
@Composable
fun Countdown(
    secondsRemaining: Int,
    modifier: Modifier = Modifier,
    prefix: String = "-",
    color: Color = CountdownRed,
) {
    val safe = secondsRemaining.coerceAtLeast(0)
    val minutes = safe / 60
    val seconds = safe % 60
    Text(
        text = "$prefix$minutes:${seconds.toString().padStart(2, '0')}",
        style = CountdownTextStyle.copy(color = color),
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}
