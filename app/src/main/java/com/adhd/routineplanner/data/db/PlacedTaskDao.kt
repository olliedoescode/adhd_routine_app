package com.adhd.routineplanner.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.adhd.routineplanner.data.model.PlacedTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlacedTaskDao {

    @Query("SELECT * FROM placed_tasks WHERE routineId = :routineId ORDER BY position ASC")
    fun observeForRoutine(routineId: Long): Flow<List<PlacedTaskEntity>>

    /** Every placed task, ordered by routine then position; used for list summaries. */
    @Query("SELECT * FROM placed_tasks ORDER BY routineId ASC, position ASC")
    fun observeAll(): Flow<List<PlacedTaskEntity>>

    @Query("SELECT * FROM placed_tasks WHERE routineId = :routineId ORDER BY position ASC")
    suspend fun getForRoutine(routineId: Long): List<PlacedTaskEntity>

    @Query("SELECT * FROM placed_tasks ORDER BY routineId ASC, position ASC")
    suspend fun getAll(): List<PlacedTaskEntity>

    @Insert
    suspend fun insertAll(tasks: List<PlacedTaskEntity>)

    @Query("DELETE FROM placed_tasks WHERE routineId = :routineId")
    suspend fun deleteForRoutine(routineId: Long)

    /**
     * Replaces the whole task list for a routine in a single transaction, so
     * the grid never observes a half-written list (spec section 6).
     */
    @Transaction
    suspend fun replaceAll(routineId: Long, tasks: List<PlacedTaskEntity>) {
        deleteForRoutine(routineId)
        if (tasks.isNotEmpty()) {
            insertAll(tasks.map { it.copy(routineId = routineId) })
        }
    }
}
