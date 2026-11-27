package com.adhd.routineplanner.domain

/**
 * A reusable task the user can drop into any routine.
 *
 * Stock tasks are the "standard" pieces a routine is built from — shower, get
 * dressed, breakfast — so the user does not retype them every time. The built-in
 * set lives in [StockTaskCatalog] and is always offered; the settings screen lets
 * the user tune those (duration, colour, icon, alarm) and add as many tasks of
 * their own as they like, including removing them again. A built-in's name is
 * fixed because that is how it is recognised.
 *
 * [icon] is a stable string key (for example [IconKeys.SHOWER]) that the UI
 * maps to a Material icon. Keeping it a string rather than an Android resource
 * id keeps this type free of Android imports, matching the rest of `domain/`.
 */
data class StockTask(
    val id: Long = 0,
    val name: String = "",
    val minutes: Int = DEFAULT_MINUTES,
    val color: Int = DEFAULT_COLOR,
    val icon: String = IconKeys.TASK,
    val alarmEnabled: Boolean = false,
    val position: Int = 0,
) {
    companion object {
        const val DEFAULT_MINUTES: Int = 10

        /** Same neutral grey as `PlacedTask.DEFAULT_COLOR` (0xFF8E9AAF). */
        const val DEFAULT_COLOR: Int = -7435601
    }
}

/**
 * The icon keys a stock task can use. The UI resolves these to Material icons;
 * anything unrecognised falls back to [TASK].
 *
 * Keys are stable strings, not resource ids, for two reasons: they survive a
 * theme or icon-set change, and they are persisted on every `StockTask` and
 * `PlacedTask` — so a key's *meaning* must not drift. When a glyph turns out to
 * be wrong for a task, change the glyph behind the key (see
 * `ui/settings/StockTaskIcons.kt`), and only add a new key when a task genuinely
 * needs a different picture from its neighbours. The one place this was broken
 * is recorded in [StockTaskCatalog.upgradedIcon], which repairs the seeds that
 * shipped with the old choices.
 */
object IconKeys {
    const val ALARM = "alarm"
    const val NIGHT = "night"
    const val MEDICATION = "medication"
    const val WATER = "water"
    const val SHOWER = "shower"
    const val BRUSH_TEETH = "brush_teeth"
    const val DRESS = "dress"
    const val BREAKFAST = "breakfast"
    const val STRETCH = "stretch"
    const val WARM_UP = "warm_up"
    const val WORKOUT = "workout"
    const val MEDITATE = "meditate"
    const val WASH_FACE = "wash_face"
    const val READ = "read"
    const val TIDY = "tidy"
    const val LAUNDRY = "laundry"
    const val WASH_UP = "wash_up"
    const val COOK = "cook"
    const val BREAK = "break"
    const val EMAIL = "email"
    const val PLAN = "plan"
    const val WORK = "work"
    const val MEETING = "meeting"
    const val TASK = "task"

    /**
     * Every key the picker offers, in display order.
     *
     * Grouped the way a day happens (wake, bathroom, clothes, food, quiet,
     * home, desk, evening, exercise) so the picker is browsable rather than a
     * alphabet soup. Length is a multiple of four so the grid in Settings fills
     * its rows evenly.
     */
    val ALL: List<String> = listOf(
        ALARM,
        NIGHT,
        MEDICATION,
        WATER,
        SHOWER,
        BRUSH_TEETH,
        DRESS,
        BREAKFAST,
        MEDITATE,
        READ,
        STRETCH,
        WARM_UP,
        WORKOUT,
        WASH_FACE,
        TIDY,
        LAUNDRY,
        WASH_UP,
        COOK,
        BREAK,
        EMAIL,
        PLAN,
        WORK,
        MEETING,
        TASK,
    )
}
