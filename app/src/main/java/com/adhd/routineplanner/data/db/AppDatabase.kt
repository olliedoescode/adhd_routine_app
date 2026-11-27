package com.adhd.routineplanner.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adhd.routineplanner.data.model.PlacedTaskEntity
import com.adhd.routineplanner.data.model.RoutineEntity
import com.adhd.routineplanner.data.model.StockTaskEntity

/**
 * The app's single Room database. Schemas are exported to `app/schemas` via the
 * `room.schemaLocation` KSP argument in `app/build.gradle.kts`.
 *
 * Version 2 adds `RoutineEntity.alarmMode` (spec section 9).
 *
 * Version 3 makes two structural changes:
 *
 * 1. **The template library is gone.** A task owns its own `name` and `color`
 *    instead of pointing at a `task_templates` row, so the migration copies
 *    each task's template name and colour onto the task before dropping the
 *    table.
 * 2. **A routine is a fixed window.** `routines.endMinuteOfDay` is added, and
 *    the per-routine `alarmMode` is dropped because alarms are now per task.
 *
 * Existing routines get an end time equal to their start plus the sum of their
 * tasks' durations, so nothing changes visually on first launch after the
 * upgrade. No user data is lost.
 *
 * Version 4 adds `RoutineEntity.useBuffer`, the per-routine switch that decides
 * whether spare window time is spread across the tasks as buffer. It defaults to
 * `1` (on) so every existing routine keeps its stretched layout.
 *
 * Version 5 adds three things:
 *
 * 1. `RoutineEntity.scheduledDate`, which turns a routine into a one-off that
 *    runs on a single date and then disables itself. `NULL` means weekly
 *    recurring, so every existing routine keeps repeating.
 * 2. `PlacedTaskEntity.alarmEnabled`. The domain type always had this flag but
 *    the entity dropped it, so the task editor's alarm toggle was lost on save.
 *    It defaults to `0`, matching the silent behaviour existing tasks had.
 * 3. The `stock_tasks` table: user-editable reusable tasks that the add-task
 *    picker offers. The table starts empty and is seeded by
 *    `StockTaskRepository.seedDefaultsIfEmpty()`.
 *
 * Version 6 adds `PlacedTaskEntity.icon`, the stock-task icon key the task was
 * created from. Before v6 the icon was dropped when a stock task was placed into
 * a routine, so the run screen and the ongoing notification had nothing to show.
 * It defaults to `'task'`, the generic key, so existing tasks keep a sensible
 * icon rather than a blank one.
 *
 * Version 7 adds `RoutineEntity.isUntimed` and `RoutineEntity.allowPause`:
 * routines with no start time, day or date that are simply run on demand, and
 * the per-routine switch that decides whether a run may be paused. Both default
 * to `0`, so every existing routine stays a scheduled routine with no pause
 * control, exactly as before.
 */
@Database(
    entities = [
        RoutineEntity::class,
        PlacedTaskEntity::class,
        StockTaskEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun routineDao(): RoutineDao
    abstract fun placedTaskDao(): PlacedTaskDao
    abstract fun stockTaskDao(): StockTaskDao

    companion object {
        const val NAME: String = "routine-planner.db"

        /** v1 -> v2: add the alarm granularity column with its default. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE routines ADD COLUMN alarmMode INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * v2 -> v3: fold the template library into the tasks, and give each
         * routine an explicit end time.
         *
         * SQLite cannot drop a column or a foreign key in place, so both tables
         * are rebuilt. `placed_tasks` is copied across while joining the old
         * template for its name and colour; `routines` is copied across with an
         * end time derived from the sum of its tasks, so an existing routine
         * keeps exactly the length it had before.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Order matters: migratePlacedTasks reads the old routines.alarmMode
                // column, so it must run before migrateRoutines rebuilds (and thus
                // drops) the routines table. Doing it the other way round throws
                // "no such column: r.alarmMode" on any device upgrading from v2.
                migratePlacedTasks(db)
                migrateRoutines(db)
                db.execSQL("DROP TABLE IF EXISTS task_templates")
            }
        }

        /**
         * v3 -> v4: add the per-routine buffer switch.
         *
         * A plain additive column, so no table rebuild is needed. Existing
         * routines default to `1` (buffers on), which is exactly how they were
         * laid out before this version, so nothing shifts on upgrade.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE routines ADD COLUMN useBuffer INTEGER NOT NULL DEFAULT 1",
                )
            }
        }

        /**
         * v4 -> v5: one-off routine dates, persisted task alarms, stock tasks.
         *
         * All three changes are additive, so no table is rebuilt:
         *
         * - `routines.scheduledDate` is nullable, and `NULL` means "recurring
         *   weekly", which is exactly what every existing routine is.
         * - `placed_tasks.alarmEnabled` defaults to `0`, so existing tasks stay
         *   silent rather than suddenly alarming.
         * - `stock_tasks` is a brand-new table, created empty. The defaults are
         *   inserted by the repository so the seed logic lives in one place.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routines ADD COLUMN scheduledDate TEXT")
                db.execSQL(
                    "ALTER TABLE placed_tasks ADD COLUMN alarmEnabled INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS stock_tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        minutes INTEGER NOT NULL,
                        color INTEGER NOT NULL,
                        icon TEXT NOT NULL,
                        alarmEnabled INTEGER NOT NULL,
                        position INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v5 -> v6: persist the stock-task icon on each placed task.
         *
         * A plain additive column with a default, so no table rebuild is needed.
         * Existing tasks get the generic `'task'` key, which is what the UI
         * already fell back to when no icon was known.
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE placed_tasks ADD COLUMN icon TEXT NOT NULL DEFAULT 'task'",
                )
            }
        }

        /**
         * v6 -> v7: on-demand routines and the per-routine pause switch.
         *
         * Both are plain additive columns with a `0` default, which is the
         * existing behaviour: a routine is scheduled and cannot be paused. No
         * table rebuild, and no existing routine changes meaning.
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE routines ADD COLUMN isUntimed INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE routines ADD COLUMN allowPause INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /** Rebuilds `routines` with `endMinuteOfDay` and without `alarmMode`. */
        private fun migrateRoutines(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS routines_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    startMinuteOfDay INTEGER NOT NULL,
                    endMinuteOfDay INTEGER NOT NULL,
                    daysOfWeek INTEGER NOT NULL,
                    enabled INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            // The end time is the start plus the sum of the routine's task
            // durations, which is exactly where the old derived timeline ended.
            // A routine with no tasks gets a one-hour window.
            db.execSQL(
                """
                INSERT INTO routines_new
                    (id, name, startMinuteOfDay, endMinuteOfDay, daysOfWeek, enabled)
                SELECT
                    r.id,
                    r.name,
                    r.startMinuteOfDay,
                    r.startMinuteOfDay + COALESCE(
                        (SELECT SUM(t.minutes) FROM placed_tasks t WHERE t.routineId = r.id),
                        60
                    ),
                    r.daysOfWeek,
                    r.enabled
                FROM routines r
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE routines")
            db.execSQL("ALTER TABLE routines_new RENAME TO routines")
        }

        /** Rebuilds `placed_tasks` with inline name/colour and no template FK. */
        private fun migratePlacedTasks(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS placed_tasks_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    routineId INTEGER NOT NULL,
                    minutes INTEGER NOT NULL,
                    position INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    color INTEGER NOT NULL,
                    FOREIGN KEY(routineId) REFERENCES routines(id)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            // Carry the template's name and colour onto the task. Tasks whose
            // template has vanished fall back to the domain defaults.
            //
            // Note: the target schema has no per-task alarm column, so nothing
            // alarm-related is copied here. The old routines.alarmMode is only
            // read by migrateRoutines' end-time calculation, not by this copy.
            db.execSQL(
                """
                INSERT INTO placed_tasks_new
                    (id, routineId, minutes, position, name, color)
                SELECT
                    t.id,
                    t.routineId,
                    t.minutes,
                    t.position,
                    COALESCE(NULLIF(tpl.name, ''), 'Task'),
                    COALESCE(tpl.color, $DEFAULT_COLOR)
                FROM placed_tasks t
                LEFT JOIN task_templates tpl ON tpl.id = t.templateId
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE placed_tasks")
            db.execSQL("ALTER TABLE placed_tasks_new RENAME TO placed_tasks")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_placed_tasks_routineId " +
                    "ON placed_tasks (routineId)",
            )
        }

        /** Same neutral grey as `PlacedTask.DEFAULT_COLOR` (0xFF8E9AAF). */
        private const val DEFAULT_COLOR: Int = -7435601
    }
}
