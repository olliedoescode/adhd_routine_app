package com.adhd.routineplanner.ui.theme

import androidx.compose.ui.graphics.Color

// The loon palette.
//
// The launcher mark is a great northern diver: near-black head and neck, a pale
// breast, one crimson eye, sitting on slate-blue water. Every theme in
// [Palette.kt] is a re-reading of that bird — lake blue, ember black-and-red,
// soft umber brown, and three daylight variants — so the app keeps one identity
// while letting the user pick the light it sits in.
//
// Token names are unchanged so every call site keeps working. Each token is now
// a *getter* into [AppColors.current]: reading a token inside composition
// subscribes the reader to the palette snapshot, so switching theme repaints
// the whole app with no per-screen plumbing. Writing an equal palette is a
// snapshot no-op, so a steady theme costs nothing.

/** Deep lake water at dusk — the page background. */
val Background: Color get() = AppColors.current.background

/** The surface cards and sheets sit on: one step up from the water. */
val Surface: Color get() = AppColors.current.surface

/** Hairlines, dividers and outlines. */
val GridLine: Color get() = AppColors.current.gridLine

/** Loon breast white, softened so it does not glare on a dark page. */
val TextPrimary: Color get() = AppColors.current.textPrimary

/** Slate — the bird's back and bill, used for secondary copy. */
val TextSecondary: Color get() = AppColors.current.textSecondary

/** The loon's eye: the one urgent colour, for alarms and destructive actions. */
val CountdownRed: Color get() = AppColors.current.countdownRed

/** A slightly lifted surface for grouped sections that should read as "on top". */
val SurfaceElevated: Color get() = AppColors.current.surfaceElevated

/** A recessed surface for wells, inputs and the area behind grouped content. */
val SurfaceSunken: Color get() = AppColors.current.surfaceSunken

/**
 * The last-minute warning colour: a muted reed-gold that sits warmly against
 * the page without competing with the red.
 */
val CountdownAmber: Color get() = AppColors.current.countdownAmber

/** The brightest line the app uses, for selection rings. */
val SelectionOutline: Color get() = AppColors.current.selectionOutline

/**
 * The accent: pale water caught on the loon's back. Used for primary actions,
 * selected chips and highlights. Deliberately calm — the red is for urgency.
 */
val Accent: Color get() = AppColors.current.accent

/** A low-alpha wash of [Accent] for badges and soft highlights. */
val AccentSoft: Color get() = AppColors.current.accentSoft

/**
 * Stock task colours tuned for the active page: lake, moss, clay, heather,
 * reed, pine, slate, rose. Muted rather than neon, and none of them is a pure
 * red — that stays reserved for the countdown.
 */
val StockTaskColors: List<Color> get() = AppColors.current.stockTaskColors

/**
 * Blends [color] toward the background by [factor] (0 = unchanged, 1 = fully
 * background). Used for future minutes in run mode and washed backgrounds.
 *
 * Reads the *current* background, so a washed colour always fades back into the
 * page it is actually drawn on, in either a dark or a light theme.
 */
fun washed(color: Color, factor: Float = 0.7f): Color = androidx.compose.ui.graphics.lerp(
    color,
    Background,
    factor,
)

/**
 * The tints used to give each routine card its own colour without a schema
 * change. A routine's tint is derived deterministically from its id (see
 * [routineTint]), so the same routine always gets the same colour and the list
 * reads as a calm, varied set of cards rather than a grey stack.
 */
val RoutineTints: List<Color> get() = AppColors.current.routineTints

/**
 * A stable tint for a routine, chosen from [RoutineTints] by id. Negative or
 * very large ids are folded into range so this is always safe to call.
 */
fun routineTint(routineId: Long): Color = AppColors.current.tintFor(routineId)
