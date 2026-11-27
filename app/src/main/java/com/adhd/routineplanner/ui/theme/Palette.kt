package com.adhd.routineplanner.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * The colours of one theme.
 *
 * Every screen used to import a handful of top-level `val`s from `Color.kt`;
 * those are still the names call sites use, but they now resolve through
 * [AppColors] to whichever palette is active, so a theme change repaints the
 * whole app without touching a single screen.
 *
 * The token set is deliberately small. The app is built out of a background, a
 * couple of surfaces, two text tones, one accent and one urgent red — anything
 * else is derived (see [accentSoft], [washed]).
 */
data class AppPalette(
    /** Whether this is a dark palette. Drives which Material scheme is built. */
    val dark: Boolean,
    /** The page background. */
    val background: Color,
    /** The surface cards and sheets sit on. */
    val surface: Color,
    /** Hairlines, dividers and outlines. */
    val gridLine: Color,
    /** Primary text. */
    val textPrimary: Color,
    /** Secondary copy: subtitles, captions, inactive controls. */
    val textSecondary: Color,
    /** The urgent colour: countdowns and destructive actions. */
    val countdownRed: Color,
    /** A lifted surface for grouped sections that should read as "on top". */
    val surfaceElevated: Color,
    /** A recessed surface for wells and inputs. */
    val surfaceSunken: Color,
    /** The "running out of time" warning tone. */
    val countdownAmber: Color,
    /** The brightest line the app uses, for selection rings. */
    val selectionOutline: Color,
    /** Primary actions, selected chips and highlights. */
    val accent: Color,
    /** The colours a task can be given. */
    val stockTaskColors: List<Color>,
    /** The tints routine cards draw from, by routine id. */
    val routineTints: List<Color>,
) {
    /** A 20% wash of [accent], for badges and soft highlights. */
    val accentSoft: Color get() = accent.copy(alpha = 0.20f)

    /**
     * A stable tint for a routine, chosen from [routineTints] by id. Negative or
     * very large ids are folded into range so this is always safe to call.
     */
    fun tintFor(routineId: Long): Color {
        val size = routineTints.size
        if (size == 0) return accent
        val index = (((routineId % size) + size) % size).toInt()
        return routineTints[index]
    }

    /**
     * The Material colour scheme for this palette.
     *
     * Built from the same tokens the screens already use, so a Material widget
     * (a dialog, a menu, a bottom sheet) cannot drift away from the hand-drawn
     * surfaces around it — the old scheme did exactly that, with purple-grey
     * `surfaceContainer` tones on a blue-black page.
     */
    fun colorScheme(): ColorScheme {
        val scheme = if (dark) darkColorScheme() else lightColorScheme()
        return scheme.copy(
            primary = accent,
            onPrimary = background,
            primaryContainer = surface,
            onPrimaryContainer = textPrimary,
            inversePrimary = accent,
            secondary = stockTaskColors.getOrElse(1) { accent },
            onSecondary = background,
            secondaryContainer = surfaceElevated,
            onSecondaryContainer = textPrimary,
            tertiary = stockTaskColors.getOrElse(2) { accent },
            onTertiary = background,
            tertiaryContainer = surfaceElevated,
            onTertiaryContainer = textPrimary,
            background = background,
            onBackground = textPrimary,
            surface = surface,
            onSurface = textPrimary,
            surfaceVariant = gridLine,
            onSurfaceVariant = textSecondary,
            surfaceTint = accent,
            inverseSurface = textPrimary,
            inverseOnSurface = background,
            error = countdownRed,
            onError = textPrimary,
            errorContainer = surfaceElevated,
            onErrorContainer = countdownRed,
            outline = gridLine,
            outlineVariant = gridLine,
            scrim = Color.Black,
            surfaceBright = if (dark) surfaceElevated else surface,
            surfaceDim = if (dark) background else surfaceSunken,
            surfaceContainerLowest = if (dark) surfaceSunken else surface,
            surfaceContainerLow = surface,
            surfaceContainer = surface,
            surfaceContainerHigh = surfaceElevated,
            surfaceContainerHighest = surfaceElevated,
        )
    }
}

/** Every palette the app ships. */
object Palettes {

    /**
     * **Lake** — the original. A great northern diver at dusk: deep blue-black
     * water, mist-white text, slate rules, and the loon's pale back as the
     * accent.
     */
    val lake = AppPalette(
        dark = true,
        background = Color(0xFF0A1116),
        surface = Color(0xFF121C22),
        gridLine = Color(0xFF23323A),
        textPrimary = Color(0xFFEAF1F3),
        textSecondary = Color(0xFF8FA3AC),
        countdownRed = Color(0xFFF05A45),
        surfaceElevated = Color(0xFF17242B),
        surfaceSunken = Color(0xFF070D11),
        countdownAmber = Color(0xFFE9B36B),
        selectionOutline = Color(0xFFF4F9FA),
        accent = Color(0xFF7FB3C8),
        stockTaskColors = listOf(
            Color(0xFF7FB3C8), // lake
            Color(0xFF87BFA6), // moss
            Color(0xFFD9906B), // clay
            Color(0xFFA79BD9), // heather
            Color(0xFFC9B37E), // reed
            Color(0xFF6FA98F), // pine
            Color(0xFF8FA9CF), // slate blue
            Color(0xFFD092A6), // loon rose
            Color(0xFFB4A48F), // stone
            Color(0xFF9AA9B6), // mist
            Color(0xFF9DBE72), // lichen
            Color(0xFFD97C6B), // soft eye red
        ),
        routineTints = listOf(
            Color(0xFF7FB3C8),
            Color(0xFF87BFA6),
            Color(0xFFD9906B),
            Color(0xFFA79BD9),
            Color(0xFF8FA9CF),
            Color(0xFFD092A6),
            Color(0xFF9DBE72),
            Color(0xFFB4A48F),
        ),
    )

    /**
     * **Ember** — warm black and red. The lake at night with a fire on the
     * shore: near-black with a brown undertone, warm off-white text, and an
     * ember-red accent that is warm rather than alarming.
     */
    val ember = AppPalette(
        dark = true,
        background = Color(0xFF141010),
        surface = Color(0xFF1D1717),
        gridLine = Color(0xFF352A29),
        textPrimary = Color(0xFFF6EAE4),
        textSecondary = Color(0xFFAF918A),
        countdownRed = Color(0xFFFF4A38),
        surfaceElevated = Color(0xFF261C1B),
        surfaceSunken = Color(0xFF0C0909),
        countdownAmber = Color(0xFFF0A45E),
        selectionOutline = Color(0xFFFDF3EE),
        accent = Color(0xFFDE6A57),
        stockTaskColors = listOf(
            Color(0xFFE0776A), // terracotta
            Color(0xFFE2A45E), // ember gold
            Color(0xFFC76A76), // rose brick
            Color(0xFFB98C6E), // clay
            Color(0xFFCFB08A), // sand
            Color(0xFFA8776B), // cocoa
            Color(0xFFD97757), // ember
            Color(0xFFE8C07A), // honey
            Color(0xFF9E8478), // taupe
            Color(0xFFB24B4B), // deep red
            Color(0xFFC98F62), // biscuit
            Color(0xFFD8A4A0), // blush
        ),
        routineTints = listOf(
            Color(0xFFE0776A),
            Color(0xFFE2A45E),
            Color(0xFFC76A76),
            Color(0xFFB98C6E),
            Color(0xFFCFB08A),
            Color(0xFFA8776B),
            Color(0xFFD97757),
            Color(0xFFE8C07A),
        ),
    )

    /**
     * **Umber** — dark soft brown. Peat, bark and coffee: the same calm darkness
     * as Lake with the cold blue swapped for warmth. Cream text, tan accent.
     */
    val umber = AppPalette(
        dark = true,
        background = Color(0xFF17130F),
        surface = Color(0xFF211B16),
        gridLine = Color(0xFF3A2F26),
        textPrimary = Color(0xFFF2E8DB),
        textSecondary = Color(0xFFB1A18E),
        countdownRed = Color(0xFFE2604A),
        surfaceElevated = Color(0xFF2A221B),
        surfaceSunken = Color(0xFF100D0A),
        countdownAmber = Color(0xFFE0B276),
        selectionOutline = Color(0xFFFBF3E7),
        accent = Color(0xFFCFA06B),
        stockTaskColors = listOf(
            Color(0xFFC0A080), // oat
            Color(0xFFA98B6B), // bark
            Color(0xFF9C8468), // khaki
            Color(0xFFB79B7A), // biscuit
            Color(0xFF8E7A5E), // peat
            Color(0xFFCBB38C), // sand
            Color(0xFFA8907A), // fawn
            Color(0xFFB08163), // clay
            Color(0xFFA3A06B), // dried grass
            Color(0xFFC49A6C), // amber
            Color(0xFFC08E8E), // rosewood
            Color(0xFF96866E), // stone
        ),
        routineTints = listOf(
            Color(0xFFC0A080),
            Color(0xFFA98B6B),
            Color(0xFFB79B7A),
            Color(0xFFCBB38C),
            Color(0xFFB08163),
            Color(0xFFA3A06B),
            Color(0xFFC49A6C),
            Color(0xFFC08E8E),
        ),
    )

    /**
     * **Dawn** — light and cool. The lake at first light: pale mist background,
     * white cards, deep ink text and a dark teal accent so buttons stay legible
     * on a bright page.
     */
    val dawn = AppPalette(
        dark = false,
        background = Color(0xFFEEF2F4),
        surface = Color(0xFFFFFFFF),
        gridLine = Color(0xFFD2DDE2),
        textPrimary = Color(0xFF16232A),
        textSecondary = Color(0xFF5B7280),
        countdownRed = Color(0xFFD6432E),
        surfaceElevated = Color(0xFFF8FBFC),
        surfaceSunken = Color(0xFFE2EAEE),
        countdownAmber = Color(0xFFB07B27),
        selectionOutline = Color(0xFF16232A),
        accent = Color(0xFF2F6E86),
        stockTaskColors = listOf(
            Color(0xFF2F6E86), // lake
            Color(0xFF3C7A5E), // moss
            Color(0xFFA85E36), // clay
            Color(0xFF6B5C9E), // heather
            Color(0xFF8A6A24), // reed
            Color(0xFF4A7D66), // pine
            Color(0xFF4A6B94), // slate blue
            Color(0xFFA85167), // loon rose
            Color(0xFF7C6A55), // stone
            Color(0xFF4E6572), // mist
            Color(0xFF5E7F37), // lichen
            Color(0xFFB0483A), // soft eye red
        ),
        routineTints = listOf(
            Color(0xFF2F6E86),
            Color(0xFF3C7A5E),
            Color(0xFFA85E36),
            Color(0xFF6B5C9E),
            Color(0xFF4A6B94),
            Color(0xFFA85167),
            Color(0xFF5E7F37),
            Color(0xFF7C6A55),
        ),
    )

    /**
     * **Parchment** — light and warm. Old paper beside the lake: cream
     * background, warm white cards, dark brown ink and an ochre accent.
     */
    val parchment = AppPalette(
        dark = false,
        background = Color(0xFFF6F0E6),
        surface = Color(0xFFFFFBF3),
        gridLine = Color(0xFFE0D5C4),
        textPrimary = Color(0xFF2A211A),
        textSecondary = Color(0xFF7A6A58),
        countdownRed = Color(0xFFC2402C),
        surfaceElevated = Color(0xFFFDF7EC),
        surfaceSunken = Color(0xFFEDE4D4),
        countdownAmber = Color(0xFFA9762A),
        selectionOutline = Color(0xFF2A211A),
        accent = Color(0xFF8E6031),
        stockTaskColors = listOf(
            Color(0xFF8E6031), // ochre
            Color(0xFF6E7A3A), // olive
            Color(0xFFA8503A), // clay
            Color(0xFF7B5E8A), // heather
            Color(0xFF96762F), // reed
            Color(0xFF4F7A5A), // pine
            Color(0xFF5A6E92), // slate
            Color(0xFF9A5566), // rose
            Color(0xFF7A6450), // bark
            Color(0xFF6E6252), // stone
            Color(0xFF6F8036), // lichen
            Color(0xFFB25C3C), // ember
        ),
        routineTints = listOf(
            Color(0xFF8E6031),
            Color(0xFF4F7A5A),
            Color(0xFFA8503A),
            Color(0xFF7B5E8A),
            Color(0xFF5A6E92),
            Color(0xFF9A5566),
            Color(0xFF6F8036),
            Color(0xFF7A6450),
        ),
    )

    /**
     * **Reed** — light and green. A morning in the reeds: soft sage page, near
     * white cards, deep green ink and a forest accent.
     */
    val reed = AppPalette(
        dark = false,
        background = Color(0xFFF0F4EE),
        surface = Color(0xFFFBFDF9),
        gridLine = Color(0xFFD5DED1),
        textPrimary = Color(0xFF1D2A22),
        textSecondary = Color(0xFF5F7266),
        countdownRed = Color(0xFFCE4530),
        surfaceElevated = Color(0xFFF7FAF4),
        surfaceSunken = Color(0xFFE6EDE3),
        countdownAmber = Color(0xFFA87C2A),
        selectionOutline = Color(0xFF1D2A22),
        accent = Color(0xFF3E7355),
        stockTaskColors = listOf(
            Color(0xFF3E7355), // pine
            Color(0xFF4A6E8A), // slate blue
            Color(0xFF8A6A32), // reed
            Color(0xFF7A5A82), // heather
            Color(0xFF9A5A48), // clay
            Color(0xFF5A7A62), // moss
            Color(0xFF3F6E7C), // lake
            Color(0xFF8C6D45), // bark
            Color(0xFF5C6E55), // sage
            Color(0xFF6B6A8A), // violet slate
            Color(0xFF7C8A3A), // lichen
            Color(0xFFA85A4A), // ember
        ),
        routineTints = listOf(
            Color(0xFF3E7355),
            Color(0xFF4A6E8A),
            Color(0xFF9A5A48),
            Color(0xFF7A5A82),
            Color(0xFF8A6A32),
            Color(0xFF5C6E55),
            Color(0xFF7C8A3A),
            Color(0xFF3F6E7C),
        ),
    )

    /**
     * **Plum** — dark and violet. A quiet evening room: aubergine walls, warm
     * white ink and a lilac accent that reads as "lamp light", not neon.
     */
    val plum = AppPalette(
        dark = true,
        background = Color(0xFF140F18),
        surface = Color(0xFF1D1624),
        gridLine = Color(0xFF33283D),
        textPrimary = Color(0xFFF1EAF5),
        textSecondary = Color(0xFFA99BB4),
        countdownRed = Color(0xFFE0574B),
        surfaceElevated = Color(0xFF241B2C),
        surfaceSunken = Color(0xFF100C14),
        countdownAmber = Color(0xFFD99A4E),
        selectionOutline = Color(0xFFF1EAF5),
        accent = Color(0xFFB79BE0),
        stockTaskColors = listOf(
            Color(0xFFB79BE0), // lilac
            Color(0xFF7E9BC4), // periwinkle
            Color(0xFFC98A9E), // rose
            Color(0xFF8FBFA8), // mint
            Color(0xFFD9A45F), // ochre
            Color(0xFF9C8ACF), // violet
            Color(0xFF6FA8A0), // teal
            Color(0xFFC1A07A), // sand
            Color(0xFFA98AC4), // orchid
            Color(0xFF8290B8), // slate
            Color(0xFFB9C46A), // olive
            Color(0xFFD97F6B), // coral
        ),
        routineTints = listOf(
            Color(0xFFB79BE0),
            Color(0xFF7E9BC4),
            Color(0xFFC98A9E),
            Color(0xFF8FBFA8),
            Color(0xFFD9A45F),
            Color(0xFF9C8ACF),
            Color(0xFF6FA8A0),
            Color(0xFFA98AC4),
        ),
    )

    /**
     * **Fern** — dark and green. Under a canopy: deep moss shadow, pale sage
     * text and a fresh leaf accent. The dark companion to [reed].
     */
    val fern = AppPalette(
        dark = true,
        background = Color(0xFF0B1410),
        surface = Color(0xFF121D17),
        gridLine = Color(0xFF23342B),
        textPrimary = Color(0xFFE9F1EB),
        textSecondary = Color(0xFF9BAEA1),
        countdownRed = Color(0xFFDE6A54),
        surfaceElevated = Color(0xFF17241C),
        surfaceSunken = Color(0xFF08100C),
        countdownAmber = Color(0xFFC9A055),
        selectionOutline = Color(0xFFE9F1EB),
        accent = Color(0xFF7FB58A),
        stockTaskColors = listOf(
            Color(0xFF7FB58A), // leaf
            Color(0xFF6F9BC0), // river
            Color(0xFFB9885A), // bark
            Color(0xFFA383B0), // heather
            Color(0xFFC97F6B), // clay
            Color(0xFF5FA890), // jade
            Color(0xFF8AA86F), // sorrel
            Color(0xFF6FA0A8), // pool
            Color(0xFFC4B06A), // straw
            Color(0xFF8E86B0), // iris
            Color(0xFF5F8F7A), // fern
            Color(0xFFD99A5F), // amber
        ),
        routineTints = listOf(
            Color(0xFF7FB58A),
            Color(0xFF6F9BC0),
            Color(0xFFC97F6B),
            Color(0xFFA383B0),
            Color(0xFFB9885A),
            Color(0xFF6FA0A8),
            Color(0xFF8AA86F),
            Color(0xFF5FA890),
        ),
    )

    /**
     * **Harbour** — light and cool. Early morning on a stone quay: blue-grey
     * page, white cards, dark slate ink and a sea-blue accent.
     */
    val harbour = AppPalette(
        dark = false,
        background = Color(0xFFF1F5F9),
        surface = Color(0xFFFCFDFE),
        gridLine = Color(0xFFD7E0E8),
        textPrimary = Color(0xFF1A2530),
        textSecondary = Color(0xFF5A6A79),
        countdownRed = Color(0xFFC8452F),
        surfaceElevated = Color(0xFFF7FAFD),
        surfaceSunken = Color(0xFFE6EDF3),
        countdownAmber = Color(0xFFA87A2A),
        selectionOutline = Color(0xFF1A2530),
        accent = Color(0xFF2F6B96),
        stockTaskColors = listOf(
            Color(0xFF2F6B96), // harbour blue
            Color(0xFF3F7A6E), // sea green
            Color(0xFF8A6A32), // brass
            Color(0xFF7A5A82), // heather
            Color(0xFFA1553F), // brick
            Color(0xFF4A6E7A), // slate teal
            Color(0xFF5C6E8A), // flint
            Color(0xFF8C6D45), // rope
            Color(0xFF5A7A62), // weed
            Color(0xFF6B6A8A), // dusk
            Color(0xFF7C8A3A), // lichen
            Color(0xFFA85A4A), // buoy
        ),
        routineTints = listOf(
            Color(0xFF2F6B96),
            Color(0xFF3F7A6E),
            Color(0xFFA1553F),
            Color(0xFF7A5A82),
            Color(0xFF8A6A32),
            Color(0xFF4A6E7A),
            Color(0xFF6B6A8A),
            Color(0xFF3E7A62),
        ),
    )

    /**
     * **Blush** — light and warm. Linen and rosewood: a soft pink-cream page,
     * white cards, dark cocoa ink and a muted rose accent. Warm without being
     * sweet.
     */
    val blush = AppPalette(
        dark = false,
        background = Color(0xFFFAF2F0),
        surface = Color(0xFFFFFBFA),
        gridLine = Color(0xFFEBDCD7),
        textPrimary = Color(0xFF2B1F1C),
        textSecondary = Color(0xFF7A645E),
        countdownRed = Color(0xFFBE4230),
        surfaceElevated = Color(0xFFFFF8F6),
        surfaceSunken = Color(0xFFF4E6E2),
        countdownAmber = Color(0xFFA87A2E),
        selectionOutline = Color(0xFF2B1F1C),
        accent = Color(0xFFB25263),
        stockTaskColors = listOf(
            Color(0xFFB25263), // rosewood
            Color(0xFF8A6A7A), // mauve
            Color(0xFFA8643F), // terracotta
            Color(0xFF5F7A6A), // sage
            Color(0xFF7A5A82), // plum
            Color(0xFF9A5566), // wine
            Color(0xFF8C6D45), // cork
            Color(0xFF6B6A8A), // iris
            Color(0xFFA85A4A), // rust
            Color(0xFF5C6E55), // moss
            Color(0xFF7C8A3A), // olive
            Color(0xFF3F6E7C), // petrol
        ),
        routineTints = listOf(
            Color(0xFFB25263),
            Color(0xFFA8643F),
            Color(0xFF8A6A7A),
            Color(0xFF5F7A6A),
            Color(0xFF7A5A82),
            Color(0xFF9A5566),
            Color(0xFF8C6D45),
            Color(0xFF3F6E7C),
        ),
    )
}

/**
 * The theme the app is currently painting with.
 *
 * Snapshot-backed on purpose: the accessors in `Color.kt` read
 * [AppColors.current], so writing it re-runs every composable that draws a
 * colour, which is exactly what "switch theme" has to do. Nothing outside the
 * theme wrapper writes it.
 */
object AppColors {
    var current: AppPalette by mutableStateOf(Palettes.lake)
        internal set
}

/**
 * The themes offered in Settings.
 *
 * Five dark and five light, all drawn from the loon's world and the places
 * around it: the lake at dusk, fire on the shore, peat, the cold deep, a violet
 * evening, the forest floor, and five ways morning can look.
 * [id] is what gets persisted, so renaming a constant never resets the user's
 * choice.
 */
enum class AppTheme(
    val id: String,
    val label: String,
    val description: String,
    val palette: AppPalette,
) {
    Lake("lake", "Lake", "Cool blue · dark", Palettes.lake),
    Ember("ember", "Ember", "Black and red · dark", Palettes.ember),
    Umber("umber", "Umber", "Soft brown · dark", Palettes.umber),
    Plum("plum", "Plum", "Violet evening · dark", Palettes.plum),
    Fern("fern", "Fern", "Deep green · dark", Palettes.fern),
    Dawn("dawn", "Dawn", "Mist and teal · light", Palettes.dawn),
    Parchment("parchment", "Parchment", "Warm cream · light", Palettes.parchment),
    Reed("reed", "Reed", "Soft sage · light", Palettes.reed),
    Harbour("harbour", "Harbour", "Cool slate · light", Palettes.harbour),
    Blush("blush", "Blush", "Linen and rose · light", Palettes.blush),
    ;

    /** True for the dark themes. */
    val isDark: Boolean get() = palette.dark

    companion object {
        /** The theme a fresh install uses, and the fallback for a bad id. */
        val DEFAULT: AppTheme = Lake

        /** Maps a stored id back to a theme, defaulting to [DEFAULT]. */
        fun fromId(id: String?): AppTheme = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
