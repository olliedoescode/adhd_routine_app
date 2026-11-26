package com.adhd.routineplanner.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Countdown font family (spec section 2, 15.8).
 *
 * The intended face is DSEG7 Classic (SIL OFL), which gives the seven-segment
 * digital look. The TTF is not bundled yet, so this falls back to a monospace
 * family with tabular numerals, which prevents digit jitter in the countdown.
 *
 * TODO(phase-8): drop `DSEG7Classic-Regular.ttf` into `app/src/main/res/font/`
 *  as `dseg7.ttf` and replace this with `FontFamily(Font(R.font.dseg7))`.
 */
val CountdownFontFamily: FontFamily = FontFamily.Monospace

/** Tabular numerals keep the countdown digits from shifting as they change. */
const val CountdownFontFeatureSettings: String = "tnum"

/** Style for the red `-M:SS` countdown (spec section 8.6, 15.8). */
val CountdownTextStyle = TextStyle(
    fontFamily = CountdownFontFamily,
    fontFeatureSettings = CountdownFontFeatureSettings,
    fontWeight = FontWeight.Bold,
    fontSize = 32.sp,
    lineHeight = 36.sp,
)

/**
 * Base typography. The DSEG seven-segment font for the countdown is added when
 * run mode is built; until then the countdown uses tabular numerals.
 */
val Typography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)
