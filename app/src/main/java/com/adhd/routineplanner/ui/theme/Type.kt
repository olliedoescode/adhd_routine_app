package com.adhd.routineplanner.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.adhd.routineplanner.R

/**
 * The app's single typeface: **Onest** (SIL OFL, see `licenses/Onest-OFL.txt`).
 *
 * Onest ships as one variable font (`res/font/onest.ttf`) with a `wght` axis, so
 * every weight is the same file — we just ask for a different variation. That
 * keeps the APK small and means headings and body share one warm, coherent
 * voice rather than pairing two faces.
 */
@OptIn(ExperimentalTextApi::class)
private fun onest(weight: FontWeight): Font = Font(
    resId = R.font.onest,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/**
 * The weights the app is allowed to ask for.
 *
 * Every entry is the same variable file pinned to a different point on its
 * `wght` axis (the `fvar` table declares 100–900). The list has to include the
 * *lighter* end as well as the heavy end: Compose resolves a requested weight to
 * the nearest declared [Font], so with only 400–700 registered a request for
 * [FontWeight.Light] silently snapped back up to 400 and small text could never
 * actually get lighter.
 *
 * 200 is declared as well because that is where the quiet end of the small type
 * lives — captions and eyebrows sit there so the headings are the only loud
 * thing on a screen. If text ever reads too faint on a light theme, fix the
 * colour (`textSecondary`), not the weight.
 */
val OnestFontFamily: FontFamily = FontFamily(
    onest(FontWeight.ExtraLight),
    onest(FontWeight.Light),
    onest(FontWeight.Normal),
    onest(FontWeight.Medium),
    onest(FontWeight.SemiBold),
    onest(FontWeight.Bold),
)

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
 * Full type scale, all in Onest.
 *
 * Sizes and line-heights are tuned for a warm, readable feel: generous line
 * heights and slightly tighter tracking on the big display/headline styles.
 * Every Material style is defined so nothing silently falls back to Roboto.
 *
 * The weight ladder is deliberately *wide*, because a single mid-weight
 * everything is what makes a page feel shouty. Three zones:
 *
 *  - 600/700 — headings and task names. The only loud thing on a screen.
 *  - 300 — running copy. Present, but clearly behind the headings.
 *  - 200 — captions, timings, eyebrows, meta. Reference information.
 *
 * The gap between zones is what does the work. Small text set at 400 next to
 * bold headings reads as a second layer of emphasis; at 200 it steps back and
 * the page stops shouting. Tracking is opened up on the small end so light text
 * keeps its legibility, and call sites should leave [TextStyle.fontWeight]
 * alone rather than adding weight back on top of these choices.
 */
val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = OnestFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 52.sp,
        lineHeight = 58.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = OnestFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        lineHeight = 50.sp,
        letterSpacing = (-0.5).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = OnestFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = OnestFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = OnestFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = OnestFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = OnestFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = OnestFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = OnestFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = OnestFontFamily,
        fontWeight = FontWeight.Light,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = OnestFontFamily,
        fontWeight = FontWeight.Light,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.15.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = OnestFontFamily,
        fontWeight = FontWeight.ExtraLight,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.35.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = OnestFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = OnestFontFamily,
        fontWeight = FontWeight.Light,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.45.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = OnestFontFamily,
        fontWeight = FontWeight.Light,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.65.sp,
    ),
)
