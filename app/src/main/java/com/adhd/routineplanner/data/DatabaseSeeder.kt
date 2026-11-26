package com.adhd.routineplanner.data

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seeds a sample routine and the default stock tasks on first run (spec
 * section 7.4).
 *
 * Both seeds are guarded by "is the table empty" rather than a DataStore flag,
 * so a user who deletes everything is not re-seeded on the next launch.
 */
@Singleton
class DatabaseSeeder @Inject constructor(
    private val repository: RoutineRepository,
    private val stockTaskRepository: StockTaskRepository,
) {
    suspend fun seedIfEmpty() {
        stockTaskRepository.seedDefaultsIfEmpty()
        if (!repository.isRoutineTableEmpty()) return
        val (routine, tasks) = SeedData.sampleRoutine()
        repository.insertRoutineWithTasks(routine, tasks)
    }
}
