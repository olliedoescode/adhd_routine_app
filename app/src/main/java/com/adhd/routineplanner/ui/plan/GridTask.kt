package com.adhd.routineplanner.ui.plan

import com.adhd.routineplanner.domain.IconKeys
import com.adhd.routineplanner.domain.PlacedTask

/**
 * The presentation view of a placed task.
 *
 * Tasks own their own name and colour now (there are no templates), so this is
 * a straight projection of [PlacedTask] with the fields the grid needs. It
 * exists so the drawing code does not have to import the domain type and so
 * previews can build fake tasks cheaply.
 */
data class GridTask(
    val id: Long,
    val minutes: Int,
    val position: Int,
    val name: String,
    /** ARGB colour int. */
    val color: Int,
    val alarmEnabled: Boolean = false,
    /** Stock-task icon key, so the run screen can show the task's icon. */
    val icon: String = IconKeys.TASK,
) {
    /** The pure-domain view of this task, for [com.adhd.routineplanner.domain.Schedule]. */
    fun toPlacedTask(): PlacedTask = PlacedTask(
        id = id,
        minutes = minutes,
        position = position,
        name = name,
        color = color,
        alarmEnabled = alarmEnabled,
        icon = icon,
    )
}

/** Projects placed tasks into grid tasks, preserving order. */
fun List<PlacedTask>.toGridTasks(): List<GridTask> = map { task ->
    GridTask(
        id = task.id,
        minutes = task.minutes,
        position = task.position,
        name = task.name,
        color = task.color,
        alarmEnabled = task.alarmEnabled,
        icon = task.icon,
    )
}
