package com.adhd.routineplanner.domain

/**
 * A reusable task the user can drop into any routine.
 *
 * Stock tasks are the "standard" pieces a routine is built from — shower, get
 * dressed, breakfast — so the user does not retype them every time. They are
 * fully user-editable: the defaults below are only a starting point, and the
 * settings screen lets the user add, edit and delete as many as they like.
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
 */
object IconKeys {
    const val ALARM = "alarm"
    const val SHOWER = "shower"
    const val DRESS = "dress"
    const val BREAKFAST = "breakfast"
    const val MEDITATE = "meditate"
    const val STRETCH = "stretch"
    const val BRUSH_TEETH = "brush_teeth"
    const val MEDICATION = "medication"
    const val WATER = "water"
    const val TASK = "task"

    /** Every key the picker offers, in display order. */
    val ALL: List<String> = listOf(
        ALARM,
        SHOWER,
        DRESS,
        BREAKFAST,
        BRUSH_TEETH,
        MEDICATION,
        WATER,
        MEDITATE,
        STRETCH,
        TASK,
    )
}
