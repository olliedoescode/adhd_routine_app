package com.adhd.routineplanner.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.adhd.routineplanner.domain.Routine

/**
 * A routine: a fixed time window plus an ordered list of placed tasks.
 * Copied from spec section 4.
 *
 * [startMinuteOfDay] and [endMinuteOfDay] are minutes since midnight, which
 * avoids a `LocalTime` type converter. Together they define the window that the
 * tasks are stretched to fill. [daysOfWeek] is a bitmask, Mon=1 ... Sun=64.
 *
 * Alarms are per task now (`PlacedTaskEntity.alarmEnabled`), so the old
 * routine-level `alarmMode` column is gone.
 */
@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val daysOfWeek: Int,
    val enabled: Boolean = true,
    /**
     * Whether spare window time is spread across the tasks as buffer. Added in
     * schema v4; defaults to `true` so routines created before the toggle keep
     * their stretched layout.
     */
    val useBuffer: Boolean = true,
    /**
     * The single calendar date this routine runs on, as an ISO `yyyy-MM-dd`
     * string, or `null` for a weekly recurring routine. Added in schema v5.
     *
     * A routine is either a one-off or a weekly recurrence, never both. When
     * this is non-null the routine runs once on that date and then disables
     * itself; when it is `null` the routine repeats on [daysOfWeek].
     */
    val scheduledDate: String? = null,
)

/** Maps the persisted entity to the Android-free domain type. */
fun RoutineEntity.toDomain(): Routine = Routine(
    id = id,
    name = name,
    startMinuteOfDay = startMinuteOfDay,
    endMinuteOfDay = endMinuteOfDay,
    daysOfWeek = daysOfWeek,
    enabled = enabled,
    useBuffer = useBuffer,
    scheduledDate = scheduledDate,
)

/** Maps the domain type back to the persisted entity. */
fun Routine.toEntity(): RoutineEntity = RoutineEntity(
    id = id,
    name = name,
    startMinuteOfDay = startMinuteOfDay,
    endMinuteOfDay = endMinuteOfDay,
    daysOfWeek = daysOfWeek,
    enabled = enabled,
    useBuffer = useBuffer,
    scheduledDate = scheduledDate,
)
