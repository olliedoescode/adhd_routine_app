package com.adhd.routineplanner.domain

/**
 * A task placed on a routine's timeline, as seen by the pure domain layer.
 *
 * This deliberately mirrors the fields of the Room `PlacedTask` entity
 * (spec section 4) but lives in `domain/` so the scheduling, reordering and
 * run-state logic stays plain Kotlin with no Android or Room imports. The
 * repository maps the entity to this type at the boundary.
 *
 * [minutes] is the task's **minimum** duration — the time it genuinely needs.
 * The routine window is usually longer than the sum of these minimums, and the
 * spare time is distributed across the tasks by [Buffer]. The rendered length
 * is therefore `minutes + buffer share`, and the start minute is derived from
 * the rendered lengths of the earlier tasks. Neither is stored.
 *
 * [name] and [color] live on the task itself. There is no template library:
 * every task is created by the user, on demand, and owns its own label and
 * colour. [color] is an ARGB int so this type stays free of Compose.
 *
 * [alarmEnabled] is per task, not per routine: any subset of the tasks may wake
 * the user, and it is not tied to the first one.
 */
data class PlacedTask(
    val id: Long = 0,
    val routineId: Long = 0,
    val minutes: Int,
    val position: Int = 0,
    val name: String = DEFAULT_NAME,
    val color: Int = DEFAULT_COLOR,
    val alarmEnabled: Boolean = false,
) {
    companion object {
        /** Fallback label for a task whose name was somehow left blank. */
        const val DEFAULT_NAME: String = "Task"

        /** Neutral grey, matching the last stock swatch. */
        const val DEFAULT_COLOR: Int = 0xFF8E9AAF.toInt()
    }
}
