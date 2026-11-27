package com.adhd.routineplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * Tests for the routine-name heuristic.
 *
 * The namer is a pure function, so every branch can be asserted directly. The
 * tests are grouped by the three signals the name is built from — tasks, time
 * of day, day pattern — plus the dedup rule and the word-boundary behaviour
 * that keeps short keywords safe.
 */
class RoutineNamingTest {

    private val locale = Locale.UK

    private fun suggest(
        tasks: List<String>,
        start: Int = 7 * 60,
        days: Int = DaysOfWeek.ALL,
        date: LocalDate? = null,
        existing: List<String> = emptyList(),
    ): String = RoutineNaming.suggest(
        taskNames = tasks,
        startMinuteOfDay = start,
        days = days,
        oneOffDate = date,
        existingNames = existing,
        locale = locale,
    )

    // --- Axis 1: the tasks ------------------------------------------------

    @Test
    fun `morning tasks are named Morning`() {
        val name = suggest(listOf("Wake up", "Shower", "Get dressed", "Breakfast"))
        assertEquals("Morning Routine", name)
    }

    @Test
    fun `evening chores are named Evening`() {
        val name = suggest(listOf("Cook dinner", "Wash up", "Tidy up"), start = 18 * 60)
        assertEquals("Evening Routine", name)
    }

    @Test
    fun `bedtime tasks are named Night`() {
        val name = suggest(listOf("Brush teeth", "Pyjamas", "Lights out"), start = 22 * 60)
        assertEquals("Night Routine", name)
    }

    @Test
    fun `exercise tasks are named Exercise`() {
        val name = suggest(listOf("Gym", "Workout", "Stretch"), start = 17 * 60)
        assertEquals("Exercise Routine", name)
    }

    @Test
    fun `work tasks are named Work`() {
        val name = suggest(listOf("Check emails", "Standup", "Deep work"), start = 9 * 60)
        assertEquals("Work Routine", name)
    }

    @Test
    fun `a strong signal beats several weak ones`() {
        // "coffee" and "check" are weak; "shower" is strong. Morning must win
        // even though Work has more matching words.
        val name = suggest(listOf("Coffee", "Check phone", "Shower"), start = 9 * 60)
        assertEquals("Morning Routine", name)
    }

    @Test
    fun `ambiguous tasks do not drag a routine to the wrong time`() {
        // Brushing your teeth happens morning and night, so it is in neither
        // group. A bedtime routine must still be Night on the strength of the
        // unambiguous tasks around it.
        val name = suggest(listOf("Brush teeth", "Pyjamas", "Lights out"), start = 22 * 60)
        assertEquals("Night Routine", name)
    }

    @Test
    fun `a morning routine is not stolen by its toothbrushing`() {
        val name = suggest(
            listOf("Wake up", "Brush teeth", "Shower", "Breakfast"),
            start = 7 * 60,
        )
        assertEquals("Morning Routine", name)
    }

    @Test
    fun `a walk alone does not make a routine exercise`() {
        // "walk" is a weak Exercise word, so a stroll with no other exercise
        // signal falls back to the time of day rather than claiming Exercise.
        val name = suggest(listOf("Walk home"), start = 18 * 60)
        assertEquals("Evening Routine", name)
    }

    @Test
    fun `reading is not treated as work`() {
        // "read" was removed from Work because it happens at any hour. A
        // bedtime reading routine should be Night, not Work.
        val name = suggest(listOf("Read a book", "Bed"), start = 22 * 60)
        assertEquals("Night Routine", name)
    }

    @Test
    fun `a weak exercise word loses to a strong evening one`() {
        // "walk" (weak, Exercise) against "cook" and "dinner" (strong,
        // Evening): the evening wins, which is what a person would say.
        val name = suggest(listOf("Walk home", "Cook dinner"), start = 18 * 60)
        assertEquals("Evening Routine", name)
    }

    @Test
    fun `no keyword appears in two groups`() {
        // A word in two groups scores for both, so they cancel out and the
        // routine silently falls back to the time of day. This guards the table
        // against that class of bug.
        val seen = mutableMapOf<String, String>()
        val duplicates = mutableListOf<String>()
        RoutineNaming.debugGroups().forEach { (group, keywords) ->
            keywords.forEach { keyword ->
                val word = keyword.removePrefix("!")
                val previous = seen.put(word, group)
                if (previous != null && previous != group) {
                    duplicates += "$word ($previous and $group)"
                }
            }
        }
        assertTrue("duplicated keywords: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `no group lists the same word twice`() {
        RoutineNaming.debugGroups().forEach { (group, keywords) ->
            val words = keywords.map { it.removePrefix("!") }
            assertEquals("$group has a duplicate", words.size, words.toSet().size)
        }
    }

    // --- Axis 2: the time of day ------------------------------------------

    @Test
    fun `unrecognised tasks fall back to the time of day`() {
        assertEquals("Morning Routine", suggest(listOf("Faff about"), start = 7 * 60))
        assertEquals("Afternoon Routine", suggest(listOf("Faff about"), start = 14 * 60))
        assertEquals("Evening Routine", suggest(listOf("Faff about"), start = 19 * 60))
        assertEquals("Night Routine", suggest(listOf("Faff about"), start = 23 * 60))
    }

    @Test
    fun `the small hours are still night`() {
        assertEquals("Night Routine", suggest(listOf("Faff about"), start = 2 * 60))
        assertEquals("Night Routine", suggest(listOf("Faff about"), start = 0))
    }

    @Test
    fun `no tasks at all still gets a sensible name`() {
        assertEquals("Morning Routine", suggest(emptyList(), start = 8 * 60))
    }

    @Test
    fun `bucket boundaries are inclusive at the start`() {
        assertEquals("Morning", RoutineNaming.timeBucketFor(4 * 60))
        assertEquals("Afternoon", RoutineNaming.timeBucketFor(12 * 60))
        assertEquals("Evening", RoutineNaming.timeBucketFor(17 * 60))
        assertEquals("Night", RoutineNaming.timeBucketFor(21 * 60))
    }

    // --- Axis 3: the day pattern ------------------------------------------

    @Test
    fun `weekdays are named Weekday`() {
        val weekdays = DaysOfWeek.MONDAY or DaysOfWeek.TUESDAY or
            DaysOfWeek.WEDNESDAY or DaysOfWeek.THURSDAY or DaysOfWeek.FRIDAY
        val name = suggest(listOf("Shower", "Breakfast"), days = weekdays)
        assertEquals("Weekday Mornings", name)
    }

    @Test
    fun `weekends are named Weekend`() {
        val weekend = DaysOfWeek.SATURDAY or DaysOfWeek.SUNDAY
        val name = suggest(listOf("Shower", "Breakfast"), days = weekend)
        assertEquals("Weekend Mornings", name)
    }

    @Test
    fun `a single weekday is named after that day`() {
        val name = suggest(listOf("Shower", "Breakfast"), days = DaysOfWeek.WEDNESDAY)
        assertEquals("Wednesday Morning", name)
    }

    @Test
    fun `a one-off date is named after its weekday`() {
        // 2026-11-25 is a Wednesday.
        val name = suggest(
            listOf("Shower", "Breakfast"),
            days = DaysOfWeek.NONE,
            date = LocalDate.of(2026, 11, 25),
        )
        assertEquals("Wednesday Morning", name)
    }

    @Test
    fun `an odd day combination lists the days`() {
        val days = DaysOfWeek.MONDAY or DaysOfWeek.WEDNESDAY or DaysOfWeek.FRIDAY
        val name = suggest(listOf("Shower", "Breakfast"), days = days)
        assertEquals("Mornings on Mon, Wed and Fri", name)
    }

    @Test
    fun `no days picked is treated as a plain routine`() {
        val name = suggest(listOf("Shower", "Breakfast"), days = DaysOfWeek.NONE, date = null)
        assertEquals("Morning Routine", name)
    }

    // --- Word boundaries --------------------------------------------------

    @Test
    fun `run does not match brunch`() {
        val words = RoutineNaming.wordsOf("Brunch with friends")
        assertFalse("run" in words)
        assertTrue("brunch" in words)
    }

    @Test
    fun `tea does not match teach`() {
        val words = RoutineNaming.wordsOf("Teach the kids")
        assertFalse("tea" in words)
    }

    @Test
    fun `punctuation is stripped`() {
        val words = RoutineNaming.wordsOf("Get dressed! (quickly)")
        assertEquals(listOf("get", "dressed", "quickly"), words)
    }

    @Test
    fun `matching is case insensitive`() {
        val name = suggest(listOf("SHOWER", "Breakfast"))
        assertEquals("Morning Routine", name)
    }

    // --- Dedup ------------------------------------------------------------

    @Test
    fun `a free name is returned unchanged`() {
        val name = suggest(listOf("Shower"), existing = listOf("Evening Routine"))
        assertEquals("Morning Routine", name)
    }

    @Test
    fun `a taken name gets a 2 suffix`() {
        val name = suggest(listOf("Shower"), existing = listOf("Morning Routine"))
        assertEquals("Morning Routine #2", name)
    }

    @Test
    fun `the suffix keeps counting past 2`() {
        val name = suggest(
            listOf("Shower"),
            existing = listOf("Morning Routine", "Morning Routine #2"),
        )
        assertEquals("Morning Routine #3", name)
    }

    @Test
    fun `dedup ignores surrounding whitespace`() {
        val name = suggest(listOf("Shower"), existing = listOf("  Morning Routine  "))
        assertEquals("Morning Routine #2", name)
    }

    // --- Composition ------------------------------------------------------

    @Test
    fun `pluralisation handles words ending in y`() {
        // "Study" is a Work keyword; the plural must be "Studies".
        val name = RoutineNaming.compose(
            flavour = "Study",
            days = DaysOfWeek.MONDAY or DaysOfWeek.TUESDAY or DaysOfWeek.WEDNESDAY or
                DaysOfWeek.THURSDAY or DaysOfWeek.FRIDAY,
            oneOffDate = null,
            locale = locale,
        )
        assertEquals("Weekday Studies", name)
    }

    @Test
    fun `every day is a plain routine`() {
        val name = RoutineNaming.compose(
            flavour = "Morning",
            days = DaysOfWeek.ALL,
            oneOffDate = null,
            locale = locale,
        )
        assertEquals("Morning Routine", name)
    }

    @Test
    fun `a full weekday morning routine reads naturally`() {
        val weekdays = DaysOfWeek.MONDAY or DaysOfWeek.TUESDAY or
            DaysOfWeek.WEDNESDAY or DaysOfWeek.THURSDAY or DaysOfWeek.FRIDAY
        val name = suggest(
            tasks = listOf("Wake up", "Shower", "Get dressed", "Breakfast", "Meds"),
            start = 7 * 60,
            days = weekdays,
        )
        assertEquals("Weekday Mornings", name)
    }

    @Test
    fun `a weekend evening routine reads naturally`() {
        val weekend = DaysOfWeek.SATURDAY or DaysOfWeek.SUNDAY
        val name = suggest(
            tasks = listOf("Cook dinner", "Wash up", "Tidy up"),
            start = 18 * 60,
            days = weekend,
        )
        assertEquals("Weekend Evenings", name)
    }
}
