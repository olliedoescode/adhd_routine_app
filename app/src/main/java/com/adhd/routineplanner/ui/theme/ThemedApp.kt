package com.adhd.routineplanner.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.adhd.routineplanner.data.ThemeStore

/**
 * Wraps [content] in the theme the user picked in Settings.
 *
 * For activities that are not [com.adhd.routineplanner.MainActivity] — the alarm
 * screen and the lock-screen takeover. MainActivity tracks the theme itself so it
 * can also repaint the system bars the moment the picker changes; these two are
 * short-lived overlays launched from an alarm, so they simply read the stored
 * value.
 *
 * Nothing is drawn until the stored theme has arrived, so a light palette never
 * flashes dark while the alarm screen opens. The wait is a single frozen
 * DataStore read — microseconds — and the window background covers it.
 */
@Composable
fun AppThemeFromStore(
    themeStore: ThemeStore,
    content: @Composable () -> Unit,
) {
    val stored by produceState<AppTheme?>(initialValue = null) {
        themeStore.theme.collect { value = it }
    }
    val theme = stored ?: return
    AdhdRoutineAppTheme(theme = theme, content = content)
}
