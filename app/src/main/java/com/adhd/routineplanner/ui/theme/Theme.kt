package com.adhd.routineplanner.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = StockTaskColors[0],
    onPrimary = Background,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = GridLine,
    onSurfaceVariant = TextSecondary,
    error = CountdownRed,
    onError = TextPrimary,
)

/**
 * The app is dark-only by design (spec section 10), so there is no light scheme
 * and no dynamic colour.
 */
@Composable
fun AdhdRoutineAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography,
        content = content,
    )
}
