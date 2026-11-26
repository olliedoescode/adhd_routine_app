package com.adhd.routineplanner.data

import com.adhd.routineplanner.domain.DaysOfWeek
import com.adhd.routineplanner.domain.PlacedTask
import com.adhd.routineplanner.domain.Routine

/**
 * First-run seed data (spec sections 7.4, 10).
 *
 * Tasks are self-contained: each one carries its own name and colour, so there
 * is no template table to seed. The sample routine is a fixed 07:00–08:00
 * window; the tasks claim 55 of those 60 minutes, so the remaining 5 minutes
 * show up as buffer spread across the blocks.
 */
object SeedData {

    // Stock colours, duplicated here as ARGB ints so the data layer does not
    // depend on Compose. Must stay in sync with StockTaskColors in Color.kt.
    private const val BLUE = 0xFF4F8EF7.toInt()
    private const val TEAL = 0xFF34C7A1.toInt()
    private const val ORANGE = 0xFFF2994A.toInt()
    private const val PURPLE = 0xFF9B6BF2.toInt()
    private const val YELLOW = 0xFFF2C94C.toInt()

    /**
     * A sample routine so the grid has something to draw on first launch.
     * The first task has an alarm so the wake-up flow is exercised on day one.
     */
    fun sampleRoutine(): Pair<Routine, List<PlacedTask>> {
        val routine = Routine(
            name = "Morning",
            startMinuteOfDay = 7 * 60,
            endMinuteOfDay = 8 * 60,
            daysOfWeek = DaysOfWeek.MONDAY or DaysOfWeek.TUESDAY or DaysOfWeek.WEDNESDAY or
                DaysOfWeek.THURSDAY or DaysOfWeek.FRIDAY,
            enabled = true,
        )
        val tasks = listOf(
            PlacedTask(
                minutes = 10,
                position = 0,
                name = "Shower",
                color = BLUE,
                alarmEnabled = true,
            ),
            PlacedTask(
                minutes = 5,
                position = 1,
                name = "Get dressed",
                color = TEAL,
            ),
            PlacedTask(
                minutes = 15,
                position = 2,
                name = "Breakfast",
                color = ORANGE,
            ),
            PlacedTask(
                minutes = 10,
                position = 3,
                name = "Meditate",
                color = PURPLE,
            ),
            PlacedTask(
                minutes = 5,
                position = 4,
                name = "Stretch",
                color = YELLOW,
            ),
        )
        return routine to tasks
    }
}
