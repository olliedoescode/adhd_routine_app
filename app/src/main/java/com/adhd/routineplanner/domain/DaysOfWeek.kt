package com.adhd.routineplanner.domain

/**
 * Bitmask helpers for `Routine.daysOfWeek` (spec section 4).
 *
 * Mon=1, Tue=2, Wed=4, Thu=8, Fri=16, Sat=32, Sun=64. The bit index is the
 * ISO day-of-week minus one, so `java.time.DayOfWeek.value` (1..7) maps
 * directly via `1 shl (value - 1)`.
 */
object DaysOfWeek {

    const val MONDAY: Int = 1 shl 0
    const val TUESDAY: Int = 1 shl 1
    const val WEDNESDAY: Int = 1 shl 2
    const val THURSDAY: Int = 1 shl 3
    const val FRIDAY: Int = 1 shl 4
    const val SATURDAY: Int = 1 shl 5
    const val SUNDAY: Int = 1 shl 6

    const val NONE: Int = 0
    const val ALL: Int = 0b111_1111

    /** Short labels in bit order, Monday first. */
    val SHORT_LABELS: List<String> = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** Single-letter labels in bit order, Monday first. */
    val LETTER_LABELS: List<String> = listOf("M", "T", "W", "T", "F", "S", "S")

    /** Bit for an ISO day-of-week value (1 = Monday ... 7 = Sunday). */
    fun bitFor(isoDayOfWeek: Int): Int {
        require(isoDayOfWeek in 1..7) { "isoDayOfWeek must be 1..7, was $isoDayOfWeek" }
        return 1 shl (isoDayOfWeek - 1)
    }

    /**
     * True if [mask] includes [isoDayOfWeek] (1 = Monday ... 7 = Sunday).
     */
    fun isActiveOn(mask: Int, isoDayOfWeek: Int): Boolean =
        (mask and bitFor(isoDayOfWeek)) != 0

    /**
     * The seven bits of [mask] as booleans, Monday first. Index 0 is Monday,
     * index 6 is Sunday.
     */
    fun toDisplayChips(mask: Int): List<Boolean> =
        (0 until 7).map { i -> (mask and (1 shl i)) != 0 }

    /** Builds a mask from seven booleans, Monday first. */
    fun fromChips(chips: List<Boolean>): Int {
        var mask = 0
        chips.forEachIndexed { i, active ->
            if (active && i in 0..6) mask = mask or (1 shl i)
        }
        return mask
    }

    /** Toggles a single day in [mask], returning the new mask. */
    fun toggle(mask: Int, isoDayOfWeek: Int): Int = mask xor bitFor(isoDayOfWeek)
}
