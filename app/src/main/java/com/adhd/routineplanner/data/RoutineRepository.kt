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

    suspend fun saveRoutine(routine: Routine): Long {
        val id = routineDao.upsert(routine.toEntity())
        val savedId = if (routine.id == 0L) id else routine.id
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
     */
    suspend fun commitTasks(routineId: Long, tasks: List<PlacedTask>) {
        val renumbered = Reorder.renumber(tasks)
        val entities = renumbered.map { it.copy(routineId = routineId).toEntity() }
        placedTaskDao.replaceAll(routineId, entities)
        alarmScheduler.rescheduleRoutine(routineId)
    }

    // --- Seeding ------------------------------------------------------------

    /** True when the database has no routines yet, i.e. a fresh install. */
    suspend fun isRoutineTableEmpty(): Boolean = routineDao.getAll().isEmpty()

    suspend fun insertRoutineWithTasks(routine: Routine, tasks: List<PlacedTask>): Long {
        val id = routineDao.upsert(routine.toEntity())
        val routineId = if (routine.id == 0L) id else routine.id
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
