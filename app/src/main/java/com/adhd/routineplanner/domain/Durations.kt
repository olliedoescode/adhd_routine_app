package com.adhd.routineplanner.domain

/**
 * The duration ladder used by the resize strip (spec §15.4).
 *
 * Durations snap to these values so a routine is always made of round numbers
 * rather than arbitrary minute counts. The ladder is deliberately coarse at the
 * top end: nobody wants to drag through 47 minutes to reach 50.
 */
object Durations {

    /** Every duration a task may have, in ascending order. */
    val LADDER: IntArray = intArrayOf(5, 10, 15, 20, 25, 30, 45, 60, 90, 120)

    /** The smallest allowed duration. */
    val MIN: Int get() = LADDER.first()

    /** The largest allowed duration. */
    val MAX: Int get() = LADDER.last()

    /**
     * The ladder value nearest to [minutes].
     *
     * Ties go to the larger value, so 7 snaps to 5 and 8 snaps to 10. Values
     * outside the ladder clamp to [MIN] or [MAX].
     */
    fun snap(minutes: Int): Int {
        if (minutes <= MIN) return MIN
        if (minutes >= MAX) return MAX
        var best = LADDER[0]
        var bestDistance = Int.MAX_VALUE
        for (value in LADDER) {
            val distance = kotlin.math.abs(value - minutes)
            // `<=` makes ties prefer the larger value, since LADDER ascends.
            if (distance <= bestDistance) {
                best = value
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * The next ladder value above [minutes], or [MAX] if already there.
     */
    fun next(minutes: Int): Int {
        for (value in LADDER) {
            if (value > minutes) return value
        }
        return MAX
    }

    /**
     * The next ladder value below [minutes], or [MIN] if already there.
     */
    fun previous(minutes: Int): Int {
        for (index in LADDER.indices.reversed()) {
            if (LADDER[index] < minutes) return LADDER[index]
        }
        return MIN
    }

    /** Index of [minutes] on the ladder, snapping first. */
    fun indexOf(minutes: Int): Int {
        val snapped = snap(minutes)
        val index = LADDER.indexOf(snapped)
        return if (index >= 0) index else 0
    }

    /**
     * Ladder value [steps] positions away from [minutes], clamped to the ends.
     * Negative [steps] moves down the ladder.
     */
    fun shift(minutes: Int, steps: Int): Int {
        val target = (indexOf(minutes) + steps).coerceIn(0, LADDER.lastIndex)
        return LADDER[target]
    }
}
