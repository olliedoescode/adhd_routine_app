package com.adhd.routineplanner.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp

/**
 * The app's corner-radius scale.
 *
 * Larger and softer than Material's defaults, so surfaces read as warm, rounded
 * objects rather than sharp rectangles. Small is for chips and inputs, medium
 * for cards and grouped sections, large for sheets, dialogs and the run card.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/**
 * Applies the chosen [AppTheme].
 *
 * The palette is published into [AppColors] *before* the content is composed, so
 * every descendant that reads a colour token (including the `DrawScope` helpers
 * that cannot see a `CompositionLocal`) draws with the new colours on the very
 * first frame after a switch. Because the holder is snapshot state, switching
 * theme invalidates exactly the composables that read a token — no screen needs
 * to know that themes exist.
 *
 * Typography is Onest throughout (see [Typography]) and shapes use the warm,
 * rounded [AppShapes] scale.
 */
@Composable
fun AdhdRoutineAppTheme(
    theme: AppTheme = AppTheme.DEFAULT,
    content: @Composable () -> Unit,
) {
    val palette = theme.palette

    // Published here rather than in a SideEffect so this pass already sees the
    // new values; guarded so a repeat composition does not churn every reader.
    if (AppColors.current != palette) {
        AppColors.current = palette
    }

    val colorScheme = remember(palette) { palette.colorScheme() }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content,
    )
}
