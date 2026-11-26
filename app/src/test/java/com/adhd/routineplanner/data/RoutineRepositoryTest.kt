package com.adhd.routineplanner.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.adhd.routineplanner.alarm.AlarmScheduler
import com.adhd.routineplanner.data.db.AppDatabase
import com.adhd.routineplanner.domain.PlacedTask
import com.adhd.routineplanner.domain.Routine
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 2.7 — repository behaviour against a real (in-memory) Room database.
 *
 * Runs on the JVM via Robolectric so it stays fast and needs no device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoutineRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RoutineRepository
    private lateinit var alarmScheduler: RecordingAlarmScheduler

    /** Records reschedule calls so we can assert the repository notifies alarms. */
    private class RecordingAlarmScheduler : AlarmScheduler {
        val rescheduled = mutableListOf<Long>()
        val cancelled = mutableListOf<Long>()

        override fun schedule(routine: Routine, tasks: List<PlacedTask>) = Unit

        override fun cancel(routineId: Long) {
            cancelled += routineId
        }

        override suspend fun rescheduleRoutine(routineId: Long) {
            rescheduled += routineId
        }

        override suspend fun rescheduleAll() = Unit

        override fun scheduleTestAlarm(minutes: Int) = Unit
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        alarmScheduler = RecordingAlarmScheduler()
        repository = RoutineRepository(
            routineDao = db.routineDao(),
            placedTaskDao = db.placedTaskDao(),
            alarmScheduler = alarmScheduler,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun task(
        minutes: Int,
        position: Int = 0,
        name: String = "Shower",
        color: Int = 0xFF4F8EF7.toInt(),
        alarmEnabled: Boolean = false,
    ) = PlacedTask(
        minutes = minutes,
        position = position,
        name = name,
        color = color,
        alarmEnabled = alarmEnabled,
    )

    private suspend fun seedRoutine(): Long =
        repository.saveRoutine(
            Routine(
                name = "Morning",
                startMinuteOfDay = 7 * 60,
                endMinuteOfDay = 8 * 60,
            ),
        )

    @Test
    fun `saveRoutine assigns an id and reschedules alarms`() = runTest {
        val id = seedRoutine()

        assertTrue("expected a generated row id", id > 0L)
        assertEquals(listOf(id), alarmScheduler.rescheduled)
        assertEquals("Morning", repository.getRoutine(id)?.name)
    }

    @Test
    fun `saveRoutine persists the end time`() = runTest {
        val id = seedRoutine()

        val stored = repository.getRoutine(id)
        assertEquals(7 * 60, stored?.startMinuteOfDay)
        assertEquals(8 * 60, stored?.endMinuteOfDay)
        assertEquals(60, stored?.capacityMinutes)
    }

    @Test
    fun `commitTasks renumbers position from zero`() = runTest {
        val routineId = seedRoutine()

        // Deliberately out of order and with duplicate/garbage positions.
        repository.commitTasks(
            routineId,
            listOf(
                task(minutes = 15, position = 7),
                task(minutes = 10, position = 7),
                task(minutes = 5, position = 99),
            ),
        )

        val stored = repository.getTasks(routineId)
        assertEquals(3, stored.size)
        assertEquals(listOf(0, 1, 2), stored.map { it.position })
        assertEquals(listOf(15, 10, 5), stored.map { it.minutes })
    }

    @Test
    fun `commitTasks round-trips name, colour and alarm flag`() = runTest {
        val routineId = seedRoutine()

        repository.commitTasks(
            routineId,
            listOf(
                task(minutes = 10, name = "Shower", color = 0xFF34C7A1.toInt(), alarmEnabled = true),
                task(minutes = 5, name = "Stretch", color = 0xFFF2C94C.toInt()),
            ),
        )

        val stored = repository.getTasks(routineId)
        assertEquals(listOf("Shower", "Stretch"), stored.map { it.name })
        assertEquals(listOf(0xFF34C7A1.toInt(), 0xFFF2C94C.toInt()), stored.map { it.color })
        assertEquals(listOf(true, false), stored.map { it.alarmEnabled })
    }

    @Test
    fun `commitTasks replaces the previous list rather than appending`() = runTest {
        val routineId = seedRoutine()

        repository.commitTasks(
            routineId,
            listOf(task(minutes = 15), task(minutes = 10)),
        )
        repository.commitTasks(routineId, listOf(task(minutes = 5)))

        val stored = repository.getTasks(routineId)
        assertEquals(1, stored.size)
        assertEquals(5, stored.single().minutes)
        assertEquals(0, stored.single().position)
    }

    @Test
    fun `commitTasks reschedules alarms for the routine`() = runTest {
        val routineId = seedRoutine()
        alarmScheduler.rescheduled.clear()

        repository.commitTasks(routineId, listOf(task(minutes = 5)))

        assertEquals(listOf(routineId), alarmScheduler.rescheduled)
    }

    @Test
    fun `deleting a routine cascades to its placed tasks`() = runTest {
        val routineId = seedRoutine()
        repository.commitTasks(
            routineId,
            listOf(task(minutes = 15), task(minutes = 10)),
        )
        assertEquals(2, repository.getAllPlacedTasks().size)

        repository.deleteRoutine(routineId)

        assertNull(repository.getRoutine(routineId))
        assertTrue(
            "placed tasks should be removed by the cascade",
            repository.getAllPlacedTasks().isEmpty(),
        )
        assertEquals(listOf(routineId), alarmScheduler.cancelled)
    }

    @Test
    fun `insertRoutineWithTasks stores the routine and its tasks`() = runTest {
        val routineId = repository.insertRoutineWithTasks(
            Routine(
                name = "Evening",
                startMinuteOfDay = 20 * 60,
                endMinuteOfDay = 21 * 60,
            ),
            listOf(
                task(minutes = 10, name = "Read"),
                task(minutes = 20, name = "Journal"),
            ),
        )

        assertNotNull(repository.getRoutine(routineId))
        val stored = repository.getTasks(routineId)
        assertEquals(listOf(10, 20), stored.map { it.minutes })
        assertEquals(listOf(0, 1), stored.map { it.position })
        assertEquals(listOf("Read", "Journal"), stored.map { it.name })
    }

    @Test
    fun `insertRoutineWithTasks keeps the order the creation sheet arranged`() = runTest {
        // The creation sheet stages tasks in a mutable list the user can
        // reorder, then hands them over in display order. Positions must follow
        // that order, not the order the tasks were first added.
        val routineId = repository.insertRoutineWithTasks(
            Routine(
                name = "Morning",
                startMinuteOfDay = 7 * 60,
                endMinuteOfDay = 8 * 60,
            ),
            listOf(
                task(minutes = 5, name = "Stretch"),
                task(minutes = 10, name = "Shower"),
                task(minutes = 15, name = "Breakfast"),
            ),
        )

        val stored = repository.getTasks(routineId)
        assertEquals(listOf("Stretch", "Shower", "Breakfast"), stored.map { it.name })
        assertEquals(listOf(0, 1, 2), stored.map { it.position })
    }

    @Test
    fun `isRoutineTableEmpty reflects the routine count`() = runTest {
        assertTrue(repository.isRoutineTableEmpty())
        seedRoutine()
        assertFalse(repository.isRoutineTableEmpty())
    }

    @Test
    fun `setRoutineEnabled persists the flag and reschedules`() = runTest {
        val routineId = seedRoutine()
        alarmScheduler.rescheduled.clear()

        repository.setRoutineEnabled(routineId, false)

        assertEquals(false, repository.getRoutine(routineId)?.enabled)
        assertEquals(listOf(routineId), alarmScheduler.rescheduled)
    }
}
