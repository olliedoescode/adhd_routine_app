package com.adhd.routineplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DaysOfWeekTest {

    @Test
    fun `bit values match the spec bitmask`() {
        assertEquals(1, DaysOfWeek.MONDAY)
        assertEquals(2, DaysOfWeek.TUESDAY)
        assertEquals(4, DaysOfWeek.WEDNESDAY)
        assertEquals(8, DaysOfWeek.THURSDAY)
        assertEquals(16, DaysOfWeek.FRIDAY)
        assertEquals(32, DaysOfWeek.SATURDAY)
        assertEquals(64, DaysOfWeek.SUNDAY)
        assertEquals(127, DaysOfWeek.ALL)
    }

    @Test
    fun `isActiveOn reads the right bit`() {
        val weekdays = DaysOfWeek.MONDAY or DaysOfWeek.WEDNESDAY or DaysOfWeek.FRIDAY
        assertTrue(DaysOfWeek.isActiveOn(weekdays, 1))  // Monday
        assertFalse(DaysOfWeek.isActiveOn(weekdays, 2)) // Tuesday
        assertTrue(DaysOfWeek.isActiveOn(weekdays, 3))  // Wednesday
        assertFalse(DaysOfWeek.isActiveOn(weekdays, 7)) // Sunday
    }

    @Test
    fun `toDisplayChips returns seven booleans Monday first`() {
        val mask = DaysOfWeek.MONDAY or DaysOfWeek.SUNDAY
        assertEquals(
            listOf(true, false, false, false, false, false, true),
            DaysOfWeek.toDisplayChips(mask),
        )
    }

    @Test
    fun `fromChips round-trips toDisplayChips`() {
        val mask = DaysOfWeek.TUESDAY or DaysOfWeek.THURSDAY or DaysOfWeek.SATURDAY
        assertEquals(mask, DaysOfWeek.fromChips(DaysOfWeek.toDisplayChips(mask)))
    }

    @Test
    fun `fromChips of all false is NONE`() {
        assertEquals(DaysOfWeek.NONE, DaysOfWeek.fromChips(List(7) { false }))
    }

    @Test
    fun `fromChips of all true is ALL`() {
        assertEquals(DaysOfWeek.ALL, DaysOfWeek.fromChips(List(7) { true }))
    }

    @Test
    fun `toggle flips a single day`() {
        val toggled = DaysOfWeek.toggle(DaysOfWeek.NONE, 1)
        assertEquals(DaysOfWeek.MONDAY, toggled)
        assertEquals(DaysOfWeek.NONE, DaysOfWeek.toggle(toggled, 1))
    }

    @Test
    fun `bitFor maps ISO day numbers`() {
        assertEquals(DaysOfWeek.MONDAY, DaysOfWeek.bitFor(1))
        assertEquals(DaysOfWeek.SUNDAY, DaysOfWeek.bitFor(7))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bitFor rejects out-of-range days`() {
        DaysOfWeek.bitFor(8)
    }
}
