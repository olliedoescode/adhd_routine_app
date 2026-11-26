package com.adhd.routineplanner.data

import com.adhd.routineplanner.data.db.StockTaskDao
import com.adhd.routineplanner.data.model.StockTaskEntity
import com.adhd.routineplanner.data.model.toDomain
import com.adhd.routineplanner.data.model.toEntity
import com.adhd.routineplanner.domain.IconKeys
import com.adhd.routineplanner.domain.StockTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's reusable stock tasks.
 *
 * On first use the table is seeded with a handful of sensible defaults (wake
 * up, shower, get dressed, breakfast, brush teeth) so the add-task picker is
 * useful straight away. Seeding is guarded by [count] so a user who deletes
 * every stock task does not get them back on the next launch.
 */
@Singleton
class StockTaskRepository @Inject constructor(
    private val dao: StockTaskDao,
) {

    fun observeAll(): Flow<List<StockTask>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun getAll(): List<StockTask> = dao.getAll().map { it.toDomain() }

    suspend fun save(task: StockTask): Long = dao.upsert(task.toEntity())

    suspend fun delete(id: Long) = dao.deleteById(id)

    /** Inserts the built-in defaults if the table has never been populated. */
    suspend fun seedDefaultsIfEmpty() {
        if (dao.count() > 0) return
        dao.upsertAll(defaults().map { it.toEntity() })
    }

    companion object {
        /**
         * The stock tasks a new install starts with. "Wake up" is modelled as an
         * alarm task with no duration to speak of, which is why it is first and
         * short: it is the thing that gets you out of bed, not a task you do.
         */
        fun defaults(): List<StockTask> = listOf(
            StockTask(
                name = "Wake up",
                minutes = 1,
                color = 0xFFF2C94C.toInt(),
                icon = IconKeys.ALARM,
                alarmEnabled = true,
                position = 0,
            ),
            StockTask(
                name = "Shower",
                minutes = 10,
                color = 0xFF4F8EF7.toInt(),
                icon = IconKeys.SHOWER,
                position = 1,
            ),
            StockTask(
                name = "Get dressed",
                minutes = 5,
                color = 0xFF34C7A1.toInt(),
                icon = IconKeys.DRESS,
                position = 2,
            ),
            StockTask(
                name = "Breakfast",
                minutes = 15,
                color = 0xFFF2994A.toInt(),
                icon = IconKeys.BREAKFAST,
                position = 3,
            ),
            StockTask(
                name = "Brush teeth",
                minutes = 3,
                color = 0xFF56CCF2.toInt(),
                icon = IconKeys.BRUSH_TEETH,
                position = 4,
            ),
        )
    }
}
