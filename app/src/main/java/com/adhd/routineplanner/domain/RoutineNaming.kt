package com.adhd.routineplanner.domain

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Suggests a name for a new routine from the tasks the user picked, the time it
 * starts and the days it runs.
 *
 * ## Why a heuristic and not a model
 *
 * Naming a routine is a classification problem with a handful of answers
 * ("Morning", "Evening", "Weekday mornings"). That is a lookup table, not a
 * language task, so this stays a pure function: no network, no API key, no
 * latency, no failure mode, and — most importantly — it can be asserted in a
 * plain JUnit test. The whole of `domain/` is built that way and this fits.
 *
 * ## How a name is built
 *
 * Three independent signals are combined:
 *
 *  1. **The tasks.** Each task name is split into words and scored against
 *     [GROUPS]. The highest-scoring group wins. Distinctive words ("shower",
 *     "bed", "gym") are worth [STRONG_WEIGHT] so they beat incidental ones
 *     ("coffee", "check"), which are worth [WEAK_WEIGHT].
 *  2. **The time of day.** Used as the flavour when no task keyword matches, so
 *     an unrecognised routine at 07:00 still becomes "Morning Routine" rather
 *     than "New routine".
 *  3. **The day pattern.** Turns the flavour into a phrase: "Weekday Mornings",
 *     "Weekend Mornings", "Wednesday Morning", "Morning Routine".
 *
 * ## Synonyms
 *
 * There is deliberately no stemming or thesaurus. Variants are written out as
 * separate entries in [GROUPS] ("shower", "showering", "showers"), which is
 * cheaper, more predictable and easier to review than a stemmer.
 *
 * ## Word boundaries
 *
 * Matching is on whole words, not raw substrings, so "run" does not match
 * "brunch" and "tea" does not match "teach". That is what makes it safe to
 * include short, high-value words like "run", "tea" and "bed".
 */
object RoutineNaming {

    /** Weight of a word that strongly implies a group, e.g. "shower". */
    private const val STRONG_WEIGHT = 3

    /** Weight of a word that merely hints at a group, e.g. "coffee". */
    private const val WEAK_WEIGHT = 1

    /**
     * The keyword table.
     *
     * Each group maps a display flavour to its words. Words are lowercase and
     * matched against whole words of the task name. A word prefixed with `!` is
     * a strong signal and scores [STRONG_WEIGHT]; everything else scores
     * [WEAK_WEIGHT].
     *
     * ## The one rule for adding a word
     *
     * A word belongs in a group only if hearing it makes you think of that time
     * of day. "Shower" does — almost nobody showers at 9pm. "Water" does not —
     * you drink water all day. A word that is merely *related* to a routine
     * ("routine", "chores", "relax") is noise: it fires on every routine and so
     * distinguishes nothing.
     *
     * ## Never list a word twice
     *
     * A word in two groups scores for both, so the two cancel out and the
     * routine falls back to the time of day — the opposite of what was wanted.
     * Brushing your teeth is the classic case: it happens morning *and* night,
     * so it is in neither group and the other tasks decide.
     *
     * Keep this list readable: it is the whole "intelligence" of the namer, and
     * adding a word here is the only maintenance it should ever need.
     */
    private val GROUPS: List<Pair<String, List<String>>> = listOf(
        // Waking, washing and eating breakfast. The words here are the ones
        // that only really happen first thing.
        "Morning" to listOf(
            "!wake", "!waking", "!woke", "!wakeup", "!getup", "!alarm",
            "!shower", "!showering", "!showers", "!breakfast",
            "!dressed", "!dress", "!meds", "!medication",
            "coffee", "espresso", "juice", "smoothie", "toast", "cereal",
            "eggs", "oatmeal", "porridge", "yoghurt", "yogurt", "granola",
            "vitamins", "sunlight", "daylight", "curtains", "blinds",
            "shave", "shaving", "makeup", "hair", "morning",
        ),
        // Desk work and study. "Read" and "plan" are deliberately absent: they
        // happen at any hour, so they would fire on evening routines too.
        "Work" to listOf(
            "!work", "!standup", "!meeting", "!meetings", "!emails", "!email",
            "!inbox", "!deadline", "!deepwork", "!study", "!studying",
            "!revision", "!revise", "!homework", "!lecture", "!lectures",
            "!class", "!classes", "!office", "!commute", "!desk",
            "laptop", "computer", "slack", "calendar", "tickets", "report",
            "reports", "writing", "notes", "research", "admin", "paperwork",
            "bills", "budget", "zoom", "sync", "sprint", "project",
            "uni", "university", "college", "school", "lesson", "lessons",
        ),
        // Moving your body. "Walk" and "steps" are weak because a walk can be
        // a commute or an errand; "stretch" is absent because it is equally a
        // wind-down.
        "Exercise" to listOf(
            "!gym", "!workout", "!workouts", "!run", "!running", "!jog",
            "!jogging", "!yoga", "!swim", "!swimming", "!cycle", "!cycling",
            "!weights", "!lift", "!lifting", "!hiit", "!cardio", "!pilates",
            "!pressups", "!pushups", "!squats", "!plank", "!rowing",
            "walk", "walking", "steps", "exercise", "training", "climbing",
            "football", "tennis", "sport", "gymnasium",
        ),
        // Cooking and clearing up after the day. "Shop" and "unpack" are gone:
        // shopping happens whenever, and "unpack" is as likely a suitcase.
        "Evening" to listOf(
            "!dinner", "!cook", "!cooking", "!dishes", "!washingup",
            "!tidy", "!tidying", "!clean", "!cleaning", "!laundry",
            "!hoover", "!vacuum", "!ironing", "!iron",
            "supper", "meal", "meals", "prep", "prepping", "kitchen",
            "bin", "bins", "rubbish", "trash", "recycling", "dust",
            "dusting", "wipe", "wiping", "sweep", "sweeping", "mop",
            "chores", "chore", "evening",
        ),
        // Getting into bed. Only words that mean "the day is over" live here;
        // vague ones like "relax" and "quiet" were removed because they fire on
        // any calm routine at any hour.
        "Night" to listOf(
            "!bed", "!bedtime", "!sleep", "!sleeping", "!asleep",
            "!lightsout", "!pyjamas", "!pajamas", "!pjs", "!night",
            "!nighttime", "!goodnight",
            "pillow", "duvet", "blanket", "floss", "flossing", "mouthwash",
        ),
        // Deliberately winding down before bed. "Tea" and "stretch" were
        // removed: tea is drunk all day and stretching is exercise.
        "Wind-down" to listOf(
            "!meditate", "!meditation", "!breathe", "!breathing",
            "!gratitude", "!journal", "!journaling", "!reflect",
            "!reflection", "!diary",
            "mindful", "mindfulness", "candle", "candles", "podcast",
            "audiobook", "herbal", "chamomile", "winddown", "unwind",
            "unwinding", "book", "novel", "kindle",
        ),
    )

    /**
     * Exposes the keyword table for tests.
     *
     * The table is the whole of the namer's "intelligence", so the tests assert
     * properties of it directly — no word in two groups, no word twice in one
     * group — rather than only checking the names it produces. That catches a
     * bad edit at the point it is made instead of months later.
     */
    internal fun debugGroups(): List<Pair<String, List<String>>> = GROUPS

    /** The time-of-day buckets, used when no task keyword matches. */
    private val TIME_BUCKETS: List<Pair<String, IntRange>> = listOf(
        "Morning" to (4 * 60 until 12 * 60),
        "Afternoon" to (12 * 60 until 17 * 60),
        "Evening" to (17 * 60 until 21 * 60),
        "Night" to (21 * 60 until 24 * 60),
    )

    /** The bucket for the small hours, which wrap past midnight. */
    private const val NIGHT_BUCKET = "Night"

    /** Monday to Friday, the "weekday" pattern. */
    private const val WEEKDAYS: Int =
        DaysOfWeek.MONDAY or DaysOfWeek.TUESDAY or DaysOfWeek.WEDNESDAY or
            DaysOfWeek.THURSDAY or DaysOfWeek.FRIDAY

    /** Saturday and Sunday, the "weekend" pattern. */
    private const val WEEKEND: Int = DaysOfWeek.SATURDAY or DaysOfWeek.SUNDAY

    /**
     * Suggests a name for a routine.
     *
     * @param taskNames the names of the tasks the user picked, in order.
     * @param startMinuteOfDay when the routine starts, `0..1439`.
     * @param days the weekly day mask, or [DaysOfWeek.NONE] for a one-off.
     * @param oneOffDate the single date for a one-off routine, or `null`.
     * @param existingNames names already in use, so the result can be made
     *   unique by appending `#2`, `#3`, and so on.
     * @param locale used for weekday names; defaults to the device locale.
     */
    fun suggest(
        taskNames: List<String>,
        startMinuteOfDay: Int,
        days: Int,
        oneOffDate: LocalDate?,
        existingNames: List<String> = emptyList(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val flavour = flavourFor(taskNames, startMinuteOfDay)
        val base = compose(flavour, days, oneOffDate, locale)
        return makeUnique(base, existingNames)
    }

    /**
     * Picks the flavour word: the best-scoring task group, or the time-of-day
     * bucket when no task keyword matches.
     */
    internal fun flavourFor(taskNames: List<String>, startMinuteOfDay: Int): String {
        val words = taskNames.flatMap { wordsOf(it) }
        if (words.isNotEmpty()) {
            var bestGroup: String? = null
            var bestScore = 0
            GROUPS.forEach { (group, keywords) ->
                val score = scoreGroup(words, keywords)
                // Strictly greater, so the earlier group wins a tie. The table
                // is ordered most-specific-first, which makes that the right
                // tie-break.
                if (score > bestScore) {
                    bestScore = score
                    bestGroup = group
                }
            }
            if (bestGroup != null) return bestGroup!!
        }
        return timeBucketFor(startMinuteOfDay)
    }

    /** Sums the weights of every keyword that appears in [words]. */
    private fun scoreGroup(words: List<String>, keywords: List<String>): Int =
        keywords.sumOf { keyword ->
            val strong = keyword.startsWith("!")
            val word = if (strong) keyword.substring(1) else keyword
            if (word in words) {
                if (strong) STRONG_WEIGHT else WEAK_WEIGHT
            } else {
                0
            }
        }

    /**
     * Splits a task name into lowercase words.
     *
     * Splitting on anything that is not a letter or digit is what makes
     * whole-word matching work: "Get dressed!" becomes `["get", "dressed"]`,
     * and "brunch" never yields "run".
     */
    internal fun wordsOf(name: String): List<String> =
        name.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }

    /** The time-of-day bucket for a start minute. */
    internal fun timeBucketFor(startMinuteOfDay: Int): String {
        val minute = ((startMinuteOfDay % Schedule.MINUTES_PER_DAY) +
            Schedule.MINUTES_PER_DAY) % Schedule.MINUTES_PER_DAY
        TIME_BUCKETS.forEach { (bucket, range) ->
            if (minute in range) return bucket
        }
        // 00:00–03:59 is still night, not a new morning.
        return NIGHT_BUCKET
    }

    /**
     * Turns a flavour and a day pattern into a title-cased name.
     *
     * The day pattern decides the shape of the phrase:
     *  - a one-off date → "Wednesday Morning"
     *  - every day → "Morning Routine"
     *  - Mon–Fri → "Weekday Mornings"
     *  - Sat–Sun → "Weekend Mornings"
     *  - a single weekday → "Wednesday Morning"
     *  - anything else → "Mornings on Mon, Wed and Fri"
     */
    internal fun compose(
        flavour: String,
        days: Int,
        oneOffDate: LocalDate?,
        locale: Locale = Locale.getDefault(),
    ): String {
        if (oneOffDate != null) {
            return "${weekdayName(oneOffDate, locale)} $flavour"
        }
        val mask = days and DaysOfWeek.ALL
        return when {
            mask == DaysOfWeek.NONE -> "$flavour Routine"
            mask == DaysOfWeek.ALL -> "$flavour Routine"
            mask == WEEKDAYS -> "Weekday ${plural(flavour)}"
            mask == WEEKEND -> "Weekend ${plural(flavour)}"
            isSingleDay(mask) -> "${weekdayNameForBit(mask, locale)} $flavour"
            else -> "${plural(flavour)} on ${describeDays(mask)}"
        }
    }

    /** True when exactly one day bit is set. */
    private fun isSingleDay(mask: Int): Boolean =
        mask != 0 && (mask and (mask - 1)) == 0

    /** The weekday name for a single-bit mask. */
    private fun weekdayNameForBit(mask: Int, locale: Locale): String {
        val index = Integer.numberOfTrailingZeros(mask)
        // Walk forward to a date that falls on the requested weekday, then ask
        // that date for its name, so the locale's own weekday names are used
        // rather than a hardcoded English list.
        val today = LocalDate.now()
        val target = generateSequence(today) { it.plusDays(1) }
            .first { it.dayOfWeek.value == index + 1 }
        return weekdayName(target, locale)
    }

    /** The full weekday name for a date, e.g. "Wednesday". */
    private fun weekdayName(date: LocalDate, locale: Locale): String =
        date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)

    /** Naive pluralisation, which is all a flavour word needs. */
    private fun plural(word: String): String = when {
        word.endsWith("s") -> word
        word.endsWith("y") -> word.dropLast(1) + "ies"
        else -> word + "s"
    }

    /** Turns a day mask into "Mon, Wed and Fri". */
    private fun describeDays(mask: Int): String {
        val names = DaysOfWeek.SHORT_LABELS.filterIndexed { index, _ ->
            (mask and (1 shl index)) != 0
        }
        return when (names.size) {
            0 -> "no days"
            1 -> names.first()
            2 -> "${names[0]} and ${names[1]}"
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
    }

    /**
     * Appends `#2`, `#3`, … until the name is not already taken.
     *
     * Matching is on the base name, so "Morning Routine" and "Morning Routine
     * #2" both count as taken and the next free one is "#3".
     */
    internal fun makeUnique(base: String, existingNames: List<String>): String {
        val taken = existingNames.map { it.trim() }.toSet()
        if (base !in taken) return base
        var suffix = 2
        while ("$base #$suffix" in taken) suffix += 1
        return "$base #$suffix"
    }
}
