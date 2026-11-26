package com.adhd.routineplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for the resize duration ladder (spec §15.4). */
class DurationsTest {

    @Test
    fun `ladder is ascending and starts at 5`() {
        assertEquals(5, Durations.LADDER.first())
        assertEquals(120, Durations.LADDER.last())
        val sorted = Durations.LADDER.toList().sorted()
        assertEquals(sorted, Durations.LADDER.toList())
    }

    @Test
    fun `ladder contains the requested round values`() {
        val ladder = Durations.LADDER.toList()
        listOf(5, 10, 15, 20, 30, 60).forEach { value ->
            assert(ladder.contains(value)) { "ladder is missing $value" }
        }
    }

    // --- snap ---------------------------------------------------------------

    @Test
    fun `snap leaves exact ladder values alone`() {
        Durations.LADDER.forEach { value ->
            assertEquals(value, Durations.snap(value))
        }
    }

    @Test
    fun `snap rounds to the nearest value`() {
        assertEquals(5, Durations.snap(4))
        assertEquals(5, Durations.snap(6))
        assertEquals(10, Durations.snap(9))
        assertEquals(10, Durations.snap(11))
        assertEquals(20, Durations.snap(19))
        assertEquals(30, Durations.snap(31))
    }

    @Test
    fun `snap breaks ties towards the larger value`() {
        // 7 is equidistant from 5 and 10.
        assertEquals(10, Durations.snap(7))
        // 12 is equidistant from 10 and 15.
        assertEquals(15, Durations.snap(12))
    }

    @Test
    fun `snap clamps below and above the ladder`() {
        assertEquals(5, Durations.snap(0))
        assertEquals(5, Durations.snap(-20))
        assertEquals(120, Durations.snap(500))
    }

    // --- next / previous ----------------------------------------------------

    @Test
    fun `next steps up the ladder`() {
        assertEquals(10, Durations.next(5))
        assertEquals(15, Durations.next(10))
        assertEquals(30, Durations.next(25))
        assertEquals(60, Durations.next(45))
    }

    @Test
    fun `next clamps at the top`() {
        assertEquals(120, Durations.next(120))
        assertEquals(120, Durations.next(999))
    }

    @Test
    fun `previous steps down the ladder`() {
        assertEquals(5, Durations.previous(10))
        assertEquals(10, Durations.previous(15))
        assertEquals(25, Durations.previous(30))
        assertEquals(45, Durations.previous(60))
    }

    @Test
    fun `previous clamps at the bottom`() {
        assertEquals(5, Durations.previous(5))
        assertEquals(5, Durations.previous(0))
    }

    // --- shift --------------------------------------------------------------

    @Test
    fun `shift moves several positions at once`() {
        assertEquals(15, Durations.shift(5, 2))
        assertEquals(5, Durations.shift(15, -2))
    }

    @Test
    fun `shift clamps at both ends`() {
        assertEquals(5, Durations.shift(5, -10))
        assertEquals(120, Durations.shift(120, 10))
    }

    @Test
    fun `shift of zero snaps without moving`() {
        assertEquals(10, Durations.shift(9, 0))
    }

    // --- indexOf ------------------------------------------------------------

    @Test
    fun `indexOf returns the ladder position`() {
        assertEquals(0, Durations.indexOf(5))
        assertEquals(1, Durations.indexOf(10))
        assertEquals(Durations.LADDER.lastIndex, Durations.indexOf(120))
    }

    @Test
    fun `indexOf snaps an off-ladder value first`() {
        assertEquals(Durations.indexOf(10), Durations.indexOf(11))
    }
}
