package com.adhd.routineplanner.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adhd.routineplanner.ui.theme.Accent
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.GridLine
import com.adhd.routineplanner.ui.theme.Surface
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary

/**
 * The shared building blocks of the settings page.
 *
 * Settings used to be a pile of one-off rows in three different corner radii,
 * which read as unrelated scraps rather than one page. These four pieces give
 * every section the same rhythm: a small heading, one rounded card, hairline
 * dividers between rows, plain-language labels, and a short row of word-based
 * chips instead of sliders and percentages.
 */

/** A section heading: a short title with an optional one-line explanation. */
@Composable
fun SettingsSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

/** One rounded card that holds a section's rows. */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Surface)
            .padding(horizontal = 16.dp, vertical = 2.dp),
        content = content,
    )
}

/** A hairline between two rows inside a [SettingsCard]. */
@Composable
fun SettingsDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(GridLine.copy(alpha = 0.7f)),
    )
}

/**
 * A titled row: label, optional explanation and an optional trailing control
 * (a switch, a "Change" label, a value). Tappable rows turn the whole row into
 * the touch target.
 */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary.copy(alpha = alpha),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary.copy(alpha = alpha),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/**
 * A titled row of word chips — "Quiet / Balanced / Loud" rather than a slider
 * showing a percentage. The caller maps the selected index back to whatever
 * value the store holds.
 */
@Composable
fun OptionChips(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else 0.45f
    Column(modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary.copy(alpha = alpha),
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary.copy(alpha = alpha),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEachIndexed { index, label ->
                FilterChip(
                    selected = index == selectedIndex,
                    onClick = { if (enabled) onSelect(index) },
                    enabled = enabled,
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Background.copy(alpha = 0.55f),
                        labelColor = TextSecondary,
                        selectedContainerColor = Accent,
                        selectedLabelColor = Background,
                    ),
                )
            }
        }
    }
}

/**
 * Picks the option closest to [value].
 *
 * Settings are stored as continuous numbers (0.8f volume, 20s ring, 0.6f buzz),
 * but the UI offers three or four words. Mapping by nearest value means an
 * existing setting always lands on the chip that describes it, and picking a
 * chip writes a clean, round value back.
 */
fun nearestIndex(value: Float, values: List<Float>): Int {
    var best = 0
    var bestDelta = Float.MAX_VALUE
    values.forEachIndexed { index, candidate ->
        val delta = kotlin.math.abs(candidate - value)
        if (delta < bestDelta) {
            bestDelta = delta
            best = index
        }
    }
    return best
}
