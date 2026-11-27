package com.adhd.routineplanner.domain

/**
 * The broad "kind" of routine a user is building, used to tailor the stock-task
 * suggestions shown in the create flow's task step.
 *
 * ## Why this exists
 *
 * The stock-task picker used to show the same flat list of every stock task no
 * matter what the user was building. That is fine when there are five tasks, but
 * as the list grows it becomes a wall of unrelated options ("Shower" next to
 * "Standup" next to "Dishes"). This type narrows the suggestions to the handful
 * that actually fit the routine being built, while still letting the user reach
 * everything else.
 *
 * ## How a category is chosen
 *
 * Two signals are combined, in this order:
 *
 *  1. **The tasks already picked.** If the user has added "Standup" or "Emails",
 *     the routine is clearly a work routine regardless of the clock. This is the
 *     strongest signal because it reflects intent directly.
 *  2. **The start time.** With no tasks picked yet, the clock decides: a routine
 *     starting at 07:00 is a morning routine, one at 22:00 is a night routine.
 *
 * The keyword matching reuses [RoutineNaming]'s word splitting so the two
 * classifiers agree on what a word is, but the category table is separate: naming
 * cares about *flavour* ("Morning", "Evening"), whereas this cares about *intent*
 * ("Work", "Exercise"), and the two do not map one-to-one.
 *
 * ## Wide windows, not narrow ones
 *
 * The time windows below are deliberately generous. A "morning" routine is
 * anything that starts before noon, not just 06:00–09:00, because people build
 * routines at all sorts of times and a too-narrow window would hide the very
 * suggestions they wanted. The categories overlap on purpose; the first match
 * wins, and the order is chosen so the more specific intent (work, exercise)
 * beats the vaguer time-of-day buckets.
 */
object RoutineCategory {

    /** The kinds of routine the picker knows how to tailor for. */
    enum class Kind {
        /** Waking up, washing, breakfast, meds. */
        Morning,

        /** Desk work, study, meetings, email. */
        Work,

        /** Cooking, tidying, winding down after the day. */
        Evening,

        /** Getting ready for bed, sleep. */
        Night,

        /** Moving your body: gym, run, yoga. */
        Exercise,

        /** Anything that does not match a specific category. */
        Generic,
    }

    /**
     * A curated suggestion: the stock-task name to look for, plus a fallback
     * duration and icon used when the user has no stock task with that name.
     *
     * The fallback lets the picker offer a useful suggestion even before the user
     * has customised their stock tasks, so a fresh install still gets a sensible
     * morning set.
     */
    data class Suggestion(
        val name: String,
        val minutes: Int,
        val icon: String,
    )

    /**
     * Keyword table for classifying by task name. A word here means "this task
     * implies this kind of routine". Matching is whole-word, case-insensitive.
     *
     * Order matters: the first kind whose keywords match wins, so the more
     * specific intents (work, exercise) are listed before the time-of-day ones.
     */
    private val TASK_KEYWORDS: List<Pair<Kind, Set<String>>> = listOf(
        Kind.Work to setOf(
            "work", "standup", "meeting", "meetings", "email", "emails", "inbox",
            "deadline", "deepwork", "study", "studying", "revision", "revise",
            "homework", "lecture", "lectures", "class", "classes", "office",
            "commute", "desk", "laptop", "computer", "slack", "calendar",
            "tickets", "report", "reports", "writing", "notes", "research",
            "admin", "paperwork", "bills", "budget", "zoom", "sync", "sprint",
            "project", "uni", "university", "college", "school", "lesson",
            "lessons",
        ),
        Kind.Exercise to setOf(
            "gym", "workout", "workouts", "run", "running", "jog", "jogging",
            "yoga", "swim", "swimming", "cycle", "cycling", "weights", "lift",
            "lifting", "hiit", "cardio", "pilates", "pressups", "pushups",
            "squats", "plank", "rowing", "walk", "walking", "steps", "exercise",
            "training", "climbing", "football", "tennis", "sport", "gymnasium",
        ),
        Kind.Night to setOf(
            "bed", "bedtime", "sleep", "sleeping", "asleep", "lightsout",
            "pyjamas", "pajamas", "pjs", "night", "nighttime", "goodnight",
            "pillow", "duvet", "blanket", "floss", "flossing", "mouthwash",
        ),
        Kind.Evening to setOf(
            "dinner", "cook", "cooking", "dishes", "washingup", "tidy", "tidying",
            "clean", "cleaning", "laundry", "hoover", "vacuum", "ironing", "iron",
            "supper", "meal", "meals", "prep", "prepping", "kitchen", "bin",
            "bins", "rubbish", "trash", "recycling", "dust", "dusting", "wipe",
            "wiping", "sweep", "sweeping", "mop", "chores", "chore", "evening",
        ),
        Kind.Morning to setOf(
            "wake", "waking", "woke", "wakeup", "getup", "alarm", "shower",
            "showering", "showers", "breakfast", "dressed", "dress", "meds",
            "medication", "coffee", "espresso", "juice", "smoothie", "toast",
            "cereal", "eggs", "oatmeal", "porridge", "yoghurt", "yogurt",
            "granola", "vitamins", "sunlight", "daylight", "curtains", "blinds",
            "shave", "shaving", "makeup", "hair", "morning",
        ),
    )

    /**
     * The curated suggestions for each kind, in display order.
     *
     * These are the tasks that most often belong to that kind of routine. They
     * are matched against the user's stock tasks by name (case-insensitive); any
     * suggestion the user does not have is offered as a fallback so the picker is
     * never empty. The user's own stock tasks that are *not* in this list are
     * still shown, just under an "Everything else" heading.
     */
    private val SUGGESTIONS: Map<Kind, List<Suggestion>> = mapOf(
        Kind.Morning to listOf(
            Suggestion("Wake up", 1, IconKeys.ALARM),
            Suggestion("Meds", 2, IconKeys.MEDICATION),
            Suggestion("Shower", 10, IconKeys.SHOWER),
            Suggestion("Brush teeth", 3, IconKeys.BRUSH_TEETH),
            Suggestion("Get dressed", 5, IconKeys.DRESS),
            Suggestion("Breakfast", 15, IconKeys.BREAKFAST),
            Suggestion("Water", 1, IconKeys.WATER),
            Suggestion("Stretch", 5, IconKeys.STRETCH),
        ),
        Kind.Work to listOf(
            Suggestion("Check emails", 10, IconKeys.EMAIL),
            Suggestion("Plan the day", 5, IconKeys.PLAN),
            Suggestion("Deep work", 45, IconKeys.WORK),
            Suggestion("Standup", 15, IconKeys.MEETING),
            Suggestion("Break", 10, IconKeys.BREAK),
            Suggestion("Water", 1, IconKeys.WATER),
        ),
        Kind.Evening to listOf(
            Suggestion("Cook dinner", 30, IconKeys.COOK),
            Suggestion("Wash up", 10, IconKeys.WASH_UP),
            Suggestion("Tidy up", 10, IconKeys.TIDY),
            Suggestion("Laundry", 10, IconKeys.LAUNDRY),
            Suggestion("Water", 1, IconKeys.WATER),
        ),
        Kind.Night to listOf(
            Suggestion("Brush teeth", 3, IconKeys.BRUSH_TEETH),
            Suggestion("Wash face", 5, IconKeys.WASH_FACE),
            Suggestion("Pyjamas", 3, IconKeys.DRESS),
            Suggestion("Read", 20, IconKeys.READ),
            Suggestion("Meditate", 10, IconKeys.MEDITATE),
            Suggestion("Lights out", 1, IconKeys.NIGHT),
        ),
        Kind.Exercise to listOf(
            Suggestion("Warm up", 5, IconKeys.WARM_UP),
            Suggestion("Workout", 30, IconKeys.WORKOUT),
            Suggestion("Stretch", 5, IconKeys.STRETCH),
            Suggestion("Shower", 10, IconKeys.SHOWER),
            Suggestion("Water", 1, IconKeys.WATER),
        ),
        Kind.Generic to listOf(
            Suggestion("Water", 1, IconKeys.WATER),
            Suggestion("Stretch", 5, IconKeys.STRETCH),
            Suggestion("Tidy up", 10, IconKeys.TIDY),
        ),
    )

    /**
     * Classifies a routine from the tasks picked so far and its start time.
     *
     * @param taskNames the names of the tasks already staged, in order.
     * @param startMinuteOfDay when the routine starts, `0..1439`.
     */
    fun classify(taskNames: List<String>, startMinuteOfDay: Int): Kind {
        // 1. Intent from the tasks wins over the clock.
        val words = taskNames.flatMap { RoutineNaming.wordsOf(it) }.toSet()
        if (words.isNotEmpty()) {
            TASK_KEYWORDS.forEach { (kind, keywords) ->
                if (words.any { it in keywords }) return kind
            }
        }
        // 2. Otherwise fall back to the time of day, with wide windows.
        return kindForTime(startMinuteOfDay)
    }

    /**
     * The time-of-day category for a start minute, using deliberately wide
     * windows so a routine planned for "the morning" is not hidden just because
     * it starts at 11:00.
     */
    fun kindForTime(startMinuteOfDay: Int): Kind {
        val minute = ((startMinuteOfDay % Schedule.MINUTES_PER_DAY) +
            Schedule.MINUTES_PER_DAY) % Schedule.MINUTES_PER_DAY
        return when {
            // 04:00–11:59 — anything before noon reads as "morning".
            minute in 4 * 60 until 12 * 60 -> Kind.Morning
            // 12:00–16:59 — early afternoon, treat as work-ish.
            minute in 12 * 60 until 17 * 60 -> Kind.Work
            // 17:00–20:59 — after work, cooking and tidying.
            minute in 17 * 60 until 21 * 60 -> Kind.Evening
            // 21:00–03:59 — winding down and bed.
            else -> Kind.Night
        }
    }

    /** The curated suggestions for [kind], in display order. */
    fun suggestionsFor(kind: Kind): List<Suggestion> = SUGGESTIONS[kind].orEmpty()

    /** A short, human label for a category, used as a heading in the picker. */
    fun labelFor(kind: Kind): String = when (kind) {
        Kind.Morning -> "Morning"
        Kind.Work -> "Work & focus"
        Kind.Evening -> "Evening"
        Kind.Night -> "Night"
        Kind.Exercise -> "Exercise"
        Kind.Generic -> "Suggested"
    }
}
