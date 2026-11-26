package com.adhd.routineplanner.ui.theme

import androidx.compose.ui.graphics.Color

// Visual tokens from spec section 15.11.
val Background = Color(0xFF0E0F12)
val Surface = Color(0xFF16181D)
val GridLine = Color(0xFF2A2D33)
val TextPrimary = Color(0xFFE8EAED)
val TextSecondary = Color(0xFF9AA0A6)
val CountdownRed = Color(0xFFFF3B30)

/**
 * Warning colour for the last minute of a task. The countdown only turns red
 * in the final ten seconds, so a routine does not look alarming throughout.
 */
val CountdownAmber = Color(0xFFFFB020)
val SelectionOutline = Color(0xFFFFFFFF)

/**
 * Stock task colours tuned for the dark background. Pure red is deliberately
 * avoided so it stays reserved for the countdown.
 */
val StockTaskColors = listOf(
    Color(0xFF4F8EF7),
    Color(0xFF34C7A1),
    Color(0xFFF2994A),
    Color(0xFF9B6BF2),
    Color(0xFFF2C94C),
    Color(0xFF6FCF97),
    Color(0xFF56CCF2),
    Color(0xFFF28CB1),
    Color(0xFFBB8F6A),
    Color(0xFF8E9AAF),
    Color(0xFFA3D65C),
    Color(0xFFF26D6D),
)

/**
 * Blends [color] toward the background by [factor] (0 = unchanged, 1 = fully
 * background). Used for future minutes in run mode and washed backgrounds.
 */
fun washed(color: Color, factor: Float = 0.7f): Color = androidx.compose.ui.graphics.lerp(
    color,
    Background,
    factor,
)
