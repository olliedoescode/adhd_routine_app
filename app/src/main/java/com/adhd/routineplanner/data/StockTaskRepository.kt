package com.adhd.routineplanner.data

import com.adhd.routineplanner.data.db.StockTaskDao
import com.adhd.routineplanner.data.model.toDomain
import com.adhd.routineplanner.data.model.toEntity
import com.adhd.routineplanner.domain.StockTask
import com.adhd.routineplanner.domain.StockTaskCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The stock tasks offered when building a routine: the app's built-in catalogue
 * plus whatever the user has added of their own.
 *
 * On first use the table is seeded with the whole built-in catalogue so the
 * pickers are useful straight away. Seeding is guarded by `dao.count()` so a user
 * who deletes their own tasks is not re-seeded on the next launch.
 *
 * ## Reading is a merge, not a query
 *
 * [observeAll] returns the built-ins followed by the user's own tasks, even when
 * the database is missing some of the built-ins. Seeding used to run only once,
 * which left installs from an older build with a short list while the create flow
 * went on suggesting the newer tasks — the two screens disagreed. Merging keeps
 * every surface showing the same thing without a migration, and a built-in the
 * user has edited keeps its edited duration because the stored row wins.
 */
@Singleton
class StockTaskRepository @Inject constructor(
    private val dao: StockTaskDao,
) {

    fun observeAll(): Flow<List<StockTask>> =
        dao.observeAll().map { rows -> merge(rows.map { it.toDomain() }) }

    suspend fun getAll(): List<StockTask> = merge(dao.getAll().map { it.toDomain() })

    suspend fun save(task: StockTask): Long = dao.upsert(task.toEntity())

    /**
     * Deletes a stock task the user made.
     *
     * Built-ins are part of the app rather than the user's data, so they are
     * refused here as well as being hidden in the UI: the settings screen is not
     * the only possible caller.
     */
    suspend fun deleteCustom(task: StockTask) {
        if (StockTaskCatalog.isBuiltIn(task)) return
        dao.deleteById(task.id)
    }

    /** Inserts the built-in defaults if the table has never been populated. */
    suspend fun seedDefaultsIfEmpty() {
        if (dao.count() > 0) return
        dao.upsertAll(defaults().map { it.toEntity() })
    }

    /**
     * Blends the built-in catalogue with the rows in the database.
     *
     * Built-ins come first, in catalogue order. A stored row with the same name
     * replaces the catalogue entry, so an edited duration or colour is respected.
     * Anything else the user owns follows, in stored order.
     *
     * The one field that is *not* simply copied from the stored row is the icon.
     * Seeding runs once, so an install from an older build holds rows whose icons
     * were the generic defaults of the day — a moon for a workout, a paintbrush
     * for brushing teeth. [StockTaskCatalog.upgradedIcon] swaps only those known
     * bad defaults for the current glyph and leaves a deliberately chosen icon
     * alone, which means the fix reaches existing installs without a migration.
     */
    private fun merge(userTasks: List<StockTask>): List<StockTask> {
        val byName = userTasks.associateBy { it.name.trim().lowercase() }
        val builtIns = StockTaskCatalog.builtIns.map { builtIn ->
            byName[builtIn.name.trim().lowercase()]?.let { stored ->
                stored.copy(
                    icon = StockTaskCatalog.upgradedIcon(
                        name = stored.name,
                        storedIcon = stored.icon,
                        catalogueIcon = builtIn.icon,
                    ),
                )
            } ?: builtIn
        }
        val custom = userTasks.filter { !StockTaskCatalog.isBuiltIn(it.name) }
        return builtIns + custom
    }

    companion object {
        /**
         * The stock tasks a new install starts with: the whole built-in
         * catalogue ([StockTaskCatalog.builtIns]).
         *
         * "Wake up" is modelled as an alarm task with no duration to speak of,
         * which is why it is first and short: it is the thing that gets you out
         * of bed, not a task you do. The rest cover the common beats of a day —
         * meds, water, shower, teeth, clothes, breakfast, then work, evening,
         * night and exercise pieces. The create flow's category suggestions
         * ([com.adhd.routineplanner.domain.RoutineCategory]) match these by name,
         * so keeping the names stable keeps the tailored suggestions working.
         */
        fun defaults(): List<StockTask> = StockTaskCatalog.builtIns
    }
}
