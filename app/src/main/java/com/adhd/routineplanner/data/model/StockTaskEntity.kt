package com.adhd.routineplanner.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.adhd.routineplanner.domain.StockTask

/**
 * A reusable task the user can drop into any routine. Added in schema v5.
 *
 * Stock tasks are the "standard" things a routine is made of — shower, get
 * dressed, breakfast — so the user does not retype them every time. They live
 * in their own table rather than being hard-coded, so the user can add, edit
 * and delete as many as they like from the settings screen.
 *
 * [icon] is a stable string key (for example `"shower"`) that the UI maps to a
 * Material icon; storing the key rather than a resource id keeps the row
 * portable and lets the icon set change without a migration.
 */
@Entity(tableName = "stock_tasks")
data class StockTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val minutes: Int,
    val color: Int,
    val icon: String,
    val alarmEnabled: Boolean = false,
    /** Sort order in the settings list and the add-task picker. */
    val position: Int = 0,
)

/** Maps the persisted entity to the Android-free domain type. */
fun StockTaskEntity.toDomain(): StockTask = StockTask(
    id = id,
    name = name,
    minutes = minutes,
    color = color,
    icon = icon,
    alarmEnabled = alarmEnabled,
    position = position,
)

/** Maps the domain type back to the persisted entity. */
fun StockTask.toEntity(): StockTaskEntity = StockTaskEntity(
    id = id,
    name = name,
    minutes = minutes,
    color = color,
    icon = icon,
    alarmEnabled = alarmEnabled,
    position = position,
)
