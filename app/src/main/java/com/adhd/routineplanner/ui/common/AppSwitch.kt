package com.adhd.routineplanner.ui.common

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.adhd.routineplanner.ui.theme.Accent
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.GridLine
import com.adhd.routineplanner.ui.theme.TextSecondary

/**
 * The app's one on/off switch.
 *
 * Every switch in the app uses this, so "on" always looks the same: an accent
 * track with a dark thumb punched out of it, and an "off" switch that is a quiet
 * outline rather than a grey blob. Previously each screen had its own copy of
 * [SwitchDefaults.colors], and they had drifted — the routine cards used the
 * routine's tint as the track, the wizard and the settings sheet used white, and
 * only the settings page had the accent. Same control, four looks.
 *
 * Colours resolve from the active palette, so a switch follows the theme like
 * everything else.
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Background,
            checkedTrackColor = Accent,
            uncheckedThumbColor = TextSecondary,
            uncheckedTrackColor = GridLine,
            uncheckedBorderColor = GridLine,
        ),
    )
}
