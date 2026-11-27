package com.adhd.routineplanner.data

import com.adhd.routineplanner.alarm.AlarmScheduler
import com.adhd.routineplanner.data.db.PlacedTaskDao
import com.adhd.routineplanner.data.db.RoutineDao
import com.adhd.routineplanner.data.model.PlacedTaskEntity
import com.adhd.routineplanner.data.model.RoutineEntity
import com.adhd.routineplanner.data.model.toDomain
import com.adhd.routineplanner.data.model.toEntity
import com.adhd.routineplanner.domain.PlacedTask
import com.adhd.routineplanner.domain.Reorder
import com.adhd.routineplanner.domain.Routine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry point to persisted data (spec section 6).
 *
 * The UI observes Flows; every committed edit goes through here, which writes
 * to Room in one transaction and then reschedules alarms. Previews during a
 * drag or resize never reach this class.
 *
 * Tasks are self-contained: they carry their own name and colour, so there is
 * no template table to join against.
 */
@Singleton
class RoutineRepository @Inject constructor(
    private val routineDao: RoutineDao,
    private val placedTaskDao: PlacedTaskDao,
    private val alarmScheduler: AlarmScheduler,
) {

    // --- Routines -----------------------------------------------------------

    fun observeRoutines(): Flow<List<Routine>> =
        routineDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeRoutine(id: Long): Flow<Routine?> =
        routineDao.observeById(id).map { it?.toDomain() }

    suspend fun getRoutine(id: Long): Routine? = routineDao.getById(id)?.toDomain()

    /**
     * Persists a routine, inserting it when new and updating it in place
     * otherwise.
     *
     * Existing routines go through `update`, never `insert … OR REPLACE`.
     * SQLite implements REPLACE as DELETE + INSERT, and `placed_tasks` has a
     * CASCADE foreign key back to the routine, so a replace would delete every
     * task on the routine. That was the bug that made editing a routine's
     * settings (start/end time, day, name) wipe its tasks.
     */
    suspend fun saveRoutine(routine: Routine): Long {
        val savedId = if (routine.id == 0L) {
            routineDao.insert(routine.toEntity())
        } else {
            routineDao.update(routine.toEntity())
            routine.id
        }
        alarmScheduler.rescheduleRoutine(savedId)
        return savedId
    }

    suspend fun deleteRoutine(id: Long) {
        alarmScheduler.cancel(id)
        routineDao.deleteById(id)
    }

    suspend fun setRoutineEnabled(id: Long, enabled: Boolean) {
        routineDao.setEnabled(id, enabled)
        alarmScheduler.rescheduleRoutine(id)
    }

    // --- Placed tasks -------------------------------------------------------

    fun observeTasks(routineId: Long): Flow<List<PlacedTask>> =
        placedTaskDao.observeForRoutine(routineId).map { list -> list.map { it.toDomain() } }

    /**
     * Every placed task grouped by routine id, for the routine list's duration
     * and end-time summaries (spec §15.2). One query rather than one per card.
     */
    fun observeAllTasksByRoutine(): Flow<Map<Long, List<PlacedTask>>> =
        placedTaskDao.observeAll().map { list ->
            list.map { it.toDomain() }.groupBy { it.routineId }
        }

    suspend fun getTasks(routineId: Long): List<PlacedTask> =
        placedTaskDao.getForRoutine(routineId).map { it.toDomain() }

    /**
     * Persists a new task list for a routine: renumbers `position` 0..n-1,
     * replaces the rows in one transaction, then reschedules alarms
     * (spec sections 6, 14.7).
     *
     * An empty list is ignored. [PlacedTaskDao.replaceAll] deletes the routine's
     * rows before inserting, so writing an empty list would silently wipe every
     * task. Deleting the last task goes through [PlanViewModel.deleteTask] /
     * `deleteSelectedTasks`, which call this with the remaining list — a
     * deliberate empty write is not a thing any caller needs.
     */
    suspend fun commitTasks(routineId: Long, tasks: List<PlacedTask>) {
        if (tasks.isEmpty()) return
        val renumbered = Reorder.renumber(tasks)
        val entities = renumbered.map { it.copy(routineId = routineId).toEntity() }
        placedTaskDao.replaceAll(routineId, entities)
        alarmScheduler.rescheduleRoutine(routineId)
    }

    // --- Seeding ------------------------------------------------------------

    /** True when the database has no routines yet, i.e. a fresh install. */
    suspend fun isRoutineTableEmpty(): Boolean = routineDao.getAll().isEmpty()

    suspend fun insertRoutineWithTasks(routine: Routine, tasks: List<PlacedTask>): Long {
        // Same insert-or-update rule as [saveRoutine], so reusing this for an
        // existing routine cannot cascade-delete the tasks it is about to
        // rewrite.
        val routineId = saveRoutine(routine)
        val entities = Reorder.renumber(tasks).map { it.copy(routineId = routineId).toEntity() }
        placedTaskDao.replaceAll(routineId, entities)
        alarmScheduler.rescheduleRoutine(routineId)
        return routineId
    }

    /** Convenience for tests and seeding: all placed tasks as entities. */
    suspend fun getAllPlacedTasks(): List<PlacedTaskEntity> = placedTaskDao.getAll()

    /** Convenience for tests: all routines as entities. */
    suspend fun getAllRoutines(): List<RoutineEntity> = routineDao.getEnabled()
}
