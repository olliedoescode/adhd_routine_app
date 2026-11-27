package com.adhd.routineplanner.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adhd.routineplanner.ui.theme.Accent
import com.adhd.routineplanner.ui.theme.SurfaceElevated
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary

/**
 * The app's undo snackbar, styled as part of the page rather than a bright
 * system bar.
 *
 * Material's default snackbar uses `inverseSurface`, which on this dark theme
 * is a near-white slab — loud against the lake background and, worse, undo
 * snackbars carry an action so they sit there until dismissed. Rendering on
 * [SurfaceElevated] with [Accent] for the action keeps it quiet, and the inset
 * lifts it clear of the screen edges so it reads as a floating note.
 *
 * Callers are expected to pass `SnackbarDuration.Short` to
 * [SnackbarHostState.showSnackbar]: the bar should be a brief offer to undo,
 * not a permanent footer. Dismissing it (swipe, timeout or the X) leaves the
 * change applied, which is the safe default.
 */
@Composable
fun AppSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    ) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = SurfaceElevated,
            contentColor = TextPrimary,
            actionContentColor = Accent,
            dismissActionContentColor = TextSecondary,
        )
    }
}
