package com.adhd.routineplanner.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.adhd.routineplanner.data.model.StockTaskEntity
import kotlinx.coroutines.flow.Flow

/** Reads and writes the user's reusable stock tasks. */
@Dao
interface StockTaskDao {

    @Query("SELECT * FROM stock_tasks ORDER BY position ASC, name ASC")
    fun observeAll(): Flow<List<StockTaskEntity>>

    @Query("SELECT * FROM stock_tasks ORDER BY position ASC, name ASC")
    suspend fun getAll(): List<StockTaskEntity>

    @Query("SELECT COUNT(*) FROM stock_tasks")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: StockTaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tasks: List<StockTaskEntity>)

    @Update
    suspend fun update(task: StockTaskEntity)

    @Query("DELETE FROM stock_tasks WHERE id = :id")
    suspend fun deleteById(id: Long)
}
