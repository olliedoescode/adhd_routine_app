package com.adhd.routineplanner.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.adhd.routineplanner.domain.IconKeys
import com.adhd.routineplanner.domain.PlacedTask

/**
 * A task placed on a routine's timeline. Copied from spec section 4.
 *
 * [name] and [color] are stored on the task itself: there is no template
 * library, so each task owns its label and colour. [position] is the order
 * within the routine and is renumbered 0..n-1 on every committed edit.
 *
 * Deleting a routine cascades to its placed tasks.
 */
@Entity(
    tableName = "placed_tasks",
    foreignKeys = [
        ForeignKey(
            entity = RoutineEntity::class,
            parentColumns = ["id"],
            childColumns = ["routineId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("routineId"),
    ],
)
data class PlacedTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineId: Long,
    val minutes: Int,
    val position: Int,
    val name: String,
    val color: Int,
    /**
     * Whether this task raises an alarm when it starts. Added in schema v5.
     *
     * Before v5 the domain type carried this flag but the entity dropped it, so
     * the toggle in the task editor was silently lost on save. Defaults to `0`
     * so existing tasks stay silent, matching how they behaved before.
     */
    val alarmEnabled: Boolean = false,
    /**
     * The stock-task icon key this task was created from. Added in schema v6.
     *
     * Before v6 the icon was dropped when a stock task became a placed task, so
     * the run screen and notification had nothing to show. Defaults to the
     * generic task key so existing tasks keep a sensible icon.
     */
    val icon: String = IconKeys.TASK,
)

/** Maps the persisted entity to the Android-free domain type. */
fun PlacedTaskEntity.toDomain(): PlacedTask = PlacedTask(
    id = id,
    routineId = routineId,
    minutes = minutes,
    position = position,
    name = name,
    color = color,
    alarmEnabled = alarmEnabled,
    icon = icon,
)

/** Maps the domain type back to the persisted entity. */
fun PlacedTask.toEntity(): PlacedTaskEntity = PlacedTaskEntity(
    id = id,
    routineId = routineId,
    minutes = minutes,
    position = position,
    name = name,
    color = color,
    alarmEnabled = alarmEnabled,
    icon = icon,
)
