package com.adhd.routineplanner.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.adhd.routineplanner.alarm.AlarmManagerScheduler
import com.adhd.routineplanner.alarm.AlarmScheduler
import com.adhd.routineplanner.data.db.AppDatabase
import com.adhd.routineplanner.data.db.PlacedTaskDao
import com.adhd.routineplanner.data.db.RoutineDao
import com.adhd.routineplanner.data.db.StockTaskDao
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            // Real migrations keep user routines across schema changes.
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
            )
            .build()

    @Provides
    fun provideRoutineDao(db: AppDatabase): RoutineDao = db.routineDao()

    @Provides
    fun providePlacedTaskDao(db: AppDatabase): PlacedTaskDao = db.placedTaskDao()

    @Provides
    fun provideStockTaskDao(db: AppDatabase): StockTaskDao = db.stockTaskDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.dataStore

    /**
     * Application-lifetime coroutine scope for work that must survive the
     * screen that started it — e.g. clearing the run session after the user
     * confirms "Stop run" and the run screen is popped immediately.
     *
     * A [SupervisorJob] keeps one failed child from cancelling the rest.
     */
    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AlarmModule {

    /** Real AlarmManager-backed scheduler (spec section 9). */
    @Binds
    @Singleton
    abstract fun bindAlarmScheduler(impl: AlarmManagerScheduler): AlarmScheduler
}
