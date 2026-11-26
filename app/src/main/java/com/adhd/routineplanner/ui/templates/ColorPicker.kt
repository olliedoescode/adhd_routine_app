package com.adhd.routineplanner.ui.templates

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adhd.routineplanner.ui.theme.AdhdRoutineAppTheme
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.SelectionOutline
import com.adhd.routineplanner.ui.theme.StockTaskColors
import com.adhd.routineplanner.ui.theme.TextSecondary

/**
 * Colour chooser for the template editor (spec §15.6).
 *
 * A row of the twelve stock swatches, then a custom hue slider and a brightness
 * slider with a live preview. The selected colour is reported as an ARGB int so
 * it can be stored on the template without the data layer depending on Compose.
 *
 * @param color the currently selected ARGB colour.
 * @param onColorChange called with the new ARGB colour whenever it changes.
 */
@Composable
fun ColorPicker(
    color: Int,
    onColorChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val selected = Color(color)

    // Derive the sliders' positions from the current colour so an external
    // change (e.g. tapping a stock swatch) moves the sliders too.
    val hsv = hsvOf(selected)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Colour",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(8.dp))

        // Two rows of six stock swatches, finger-sized (spec §15.6).
        StockTaskColors.chunked(6).forEach { rowColors ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowColors.forEach { stock ->
                    val argb = stock.toArgb()
                    val isSelected = argb == color
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .background(stock, CircleShape)
                            .border(
                                width = if (isSelected) 2.dp else 0.dp,
                                color = if (isSelected) SelectionOutline else Color.Transparent,
                                shape = CircleShape,
                            )
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onColorChange(argb)
                            },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(8.dp))

        // --- Custom hue slider -------------------------------------------
        Text(
            text = "Hue",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
        Slider(
            value = hsv.hue,
            onValueChange = { hue ->
                onColorChange(hsv.copy(hue = hue).toColor().toArgb())
            },
            valueRange = 0f..360f,
            colors = SliderDefaults.colors(
                thumbColor = selected,
                activeTrackColor = selected,
                inactiveTrackColor = Background,
            ),
        )

        // --- Custom brightness slider ------------------------------------
        Text(
            text = "Brightness",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
        Slider(
            value = hsv.value,
            onValueChange = { value ->
                onColorChange(hsv.copy(value = value).toColor().toArgb())
            },
            valueRange = 0.2f..1f,
            colors = SliderDefaults.colors(
                thumbColor = selected,
                activeTrackColor = selected,
                inactiveTrackColor = Background,
            ),
        )

        // --- Live preview -------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Preview",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(width = 72.dp, height = 28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(selected)
                    .border(1.dp, SelectionOutline.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
            )
        }
    }
}

/** HSV triple with Compose-free maths so the sliders stay simple. */
private data class Hsv(val hue: Float, val saturation: Float, val value: Float) {
    fun toColor(): Color = Color.hsv(hue, saturation, value)
}

/**
 * Reads [color] into an HSV triple. A grey (saturation 0) or very dark colour
 * is nudged to a usable saturation/brightness so the hue slider has a visible
 * effect rather than producing black.
 */
private fun hsvOf(color: Color): Hsv {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    val saturation = if (hsv[1] < 0.05f) 0.6f else hsv[1]
    val value = if (hsv[2] < 0.2f) 0.6f else hsv[2]
    return Hsv(hue = hsv[0], saturation = saturation, value = value)
}

@Preview(name = "Colour picker", showBackground = true, backgroundColor = 0xFF0E0F12)
@Composable
private fun ColorPickerPreview() {
    AdhdRoutineAppTheme {
        ColorPicker(
            color = 0xFF4F8EF7.toInt(),
            onColorChange = {},
            modifier = Modifier
                .background(Background)
                .padding(16.dp),
        )
    }
}
