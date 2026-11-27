package com.adhd.routineplanner.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary

/**
 * The app's one top bar.
 *
 * Every screen used to build its own [TopAppBar] with slightly different
 * colours and type. This keeps the chrome consistent: the bar sits on the page
 * background, the title uses the Onest display weight, and an optional
 * [subtitle] gives a screen a second, quieter line (a date, a summary, a
 * count).
 *
 * @param title the screen title, always shown.
 * @param subtitle an optional quieter second line under the title.
 * @param navigationIcon the leading icon, usually a back arrow.
 * @param actions the trailing actions, usually icon buttons.
 * @param containerColor the bar background; defaults to the page background.
 * @param onTitleClick makes the title tappable, e.g. to open routine settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    containerColor: Color = Background,
    onTitleClick: (() -> Unit)? = null,
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Column(
                modifier = if (onTitleClick != null) {
                    Modifier.clickable(onClick = onTitleClick)
                } else {
                    Modifier
                },
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = containerColor),
    )
}
