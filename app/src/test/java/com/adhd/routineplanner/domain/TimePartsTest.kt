package com.adhd.routineplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TimePartsTest {

    @Test
    fun `fromMinutes splits whole hours and minutes`() {
        val parts = TimeParts.fromMinutes(75)
        assertEquals(1, parts.hours)
        assertEquals(15, parts.minutes)
        assertEquals(0, parts.seconds)
    }

    @Test
    fun `fromMinutes of a sub-hour value has zero hours`() {
        val parts = TimeParts.fromMinutes(45)
        assertEquals(0, parts.hours)
        assertEquals(45, parts.minutes)
    }

    @Test
    fun `fromMinutes clamps negatives to zero`() {
        val parts = TimeParts.fromMinutes(-10)
        assertEquals(0, parts.hours)
        assertEquals(0, parts.minutes)
        assertEquals(0, parts.seconds)
    }

    @Test
    fun `totalMinutes folds hours minutes and seconds`() {
        val parts = TimeParts(hours = 1, minutes = 2, seconds = 3)
        // 1h 2m 3s = 3723s -> rounds up to 63 minutes.
        assertEquals(63, parts.totalMinutes)
    }

    @Test
    fun `totalMinutes rounds a stray second up`() {
        val parts = TimeParts(hours = 0, minutes = 1, seconds = 1)
        assertEquals(2, parts.totalMinutes)
    }

    @Test
    fun `totalMinutes of zero is zero`() {
        assertEquals(0, TimeParts().totalMinutes)
    }

    @Test
    fun `fromSeconds splits exactly`() {
        val parts = TimeParts.fromSeconds(3723)
        assertEquals(1, parts.hours)
        assertEquals(2, parts.minutes)
        assertEquals(3, parts.seconds)
    }

    @Test
    fun `clamped bounds each field`() {
        val parts = TimeParts(hours = 99, minutes = 99, seconds = 99).clamped()
        assertEquals(TimeParts.MAX_HOURS, parts.hours)
        assertEquals(59, parts.minutes)
        assertEquals(59, parts.seconds)
    }

    @Test
    fun `clamped rejects negative fields`() {
        val parts = TimeParts(hours = -1, minutes = -1, seconds = -1).clamped()
        assertEquals(0, parts.hours)
        assertEquals(0, parts.minutes)
        assertEquals(0, parts.seconds)
    }

    @Test
    fun `round trip minutes to parts and back is stable`() {
        for (minutes in listOf(1, 5, 30, 59, 60, 61, 90, 120, 1440)) {
            assertEquals(minutes, TimeParts.fromMinutes(minutes).totalMinutes)
        }
    }

    @Test
    fun `hour wheel covers zero through max`() {
        assertEquals(0, TimeParts.HOUR_VALUES.first())
        assertEquals(TimeParts.MAX_HOURS, TimeParts.HOUR_VALUES.last())
        assertEquals(TimeParts.MAX_HOURS + 1, TimeParts.HOUR_VALUES.size)
    }

    @Test
    fun `sixty wheel covers zero through fifty nine`() {
        assertEquals(0, TimeParts.SIXTY_VALUES.first())
        assertEquals(59, TimeParts.SIXTY_VALUES.last())
        assertEquals(60, TimeParts.SIXTY_VALUES.size)
    }
}
