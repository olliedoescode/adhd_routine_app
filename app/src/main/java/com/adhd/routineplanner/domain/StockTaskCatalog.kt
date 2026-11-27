package com.adhd.routineplanner.domain

/**
 * The built-in stock tasks: the set every install starts with, and the set the
 * pickers show even when the database has not caught up.
 *
 * ## Why this is separate from the database
 *
 * Seeding only ever ran once, guarded by "is the stock task table empty". That
 * meant an install which had been seeded by an older build kept its short list
 * for ever, while the create flow went on offering extra suggestions synthesised
 * from [RoutineCategory] — so the settings page and the task picker disagreed
 * about what "the stock tasks" were, and the settings page looked like it was
 * missing half of them.
 *
 * The catalogue is now the single source of truth. [com.adhd.routineplanner.data.StockTaskRepository]
 * merges it into what the database holds, so both surfaces show the same list and
 * an old install picks the newer tasks up without a migration.
 *
 * ## Identity
 *
 * A task is "built-in" if its name is in this catalogue (case-insensitive,
 * trimmed). That is deliberately name-based rather than a column: it needs no
 * schema change, and a task the user typed that happens to share a built-in name
 * behaves like the built-in, which is what they meant.
 *
 * ## Icons
 *
 * Icons are part of the catalogue for the same reason as names: they say what a
 * built-in *is*. Because the stored row wins during the merge, an icon fixed
 * here would never reach an install that was already seeded — so the icons this
 * catalogue used to give these tasks are recorded in [retiredIcons], and
 * [upgradedIcon] swaps them for the current choice while leaving a key the user
 * picked deliberately alone.
 *
 * Colours are ARGB ints, duplicated from the loon stock palette so the domain
 * layer does not depend on Compose. They must stay in sync with
 * [com.adhd.routineplanner.ui.theme.StockTaskColors].
 */
object StockTaskCatalog {

    private val LAKE = 0xFF7FB3C8.toInt()
    private val MOSS = 0xFF87BFA6.toInt()
    private val CLAY = 0xFFD9906B.toInt()
    private val HEATHER = 0xFFA79BD9.toInt()
    private val REED = 0xFFC9B37E.toInt()
    private val PINE = 0xFF6FA98F.toInt()
    private val SLATE = 0xFF8FA9CF.toInt()
    private val ROSE = 0xFFD092A6.toInt()
    private val STONE = 0xFFB4A48F.toInt()
    private val MIST = 0xFF9AA9B6.toInt()
    private val LICHEN = 0xFF9DBE72.toInt()
    private val EYE_RED = 0xFFD97C6B.toInt()

    /**
     * Every built-in task, in display order.
     *
     * The order tells a small story: the morning beats first (wake up, meds,
     * water, wash, teeth, clothes, breakfast), then the everyday extras, then
     * the work, evening, night and exercise pieces. That roughly matches the
     * order a person meets them in the pickers.
     *
     * Each icon is chosen for the task rather than for the category it belongs
     * to: a workout is a dumbbell, cooking is a dinner plate, reading is a book,
     * washing up is soap. Generic check marks are the last resort, not the
     * default for anything with a recognisable picture.
     */
    val builtIns: List<StockTask> = listOf(
        // --- morning ------------------------------------------------------
        StockTask(
            name = "Wake up",
            minutes = 1,
            color = REED,
            icon = IconKeys.ALARM,
            alarmEnabled = true,
            position = 0,
        ),
        StockTask(
            name = "Meds",
            minutes = 2,
            color = ROSE,
            icon = IconKeys.MEDICATION,
            position = 1,
        ),
        StockTask(
            name = "Water",
            minutes = 1,
            color = LAKE,
            icon = IconKeys.WATER,
            position = 2,
        ),
        StockTask(
            name = "Shower",
            minutes = 10,
            color = SLATE,
            icon = IconKeys.SHOWER,
            position = 3,
        ),
        StockTask(
            name = "Brush teeth",
            minutes = 3,
            color = MIST,
            icon = IconKeys.BRUSH_TEETH,
            position = 4,
        ),
        StockTask(
            name = "Get dressed",
            minutes = 5,
            color = MOSS,
            icon = IconKeys.DRESS,
            position = 5,
        ),
        StockTask(
            name = "Breakfast",
            minutes = 15,
            color = CLAY,
            icon = IconKeys.BREAKFAST,
            position = 6,
        ),
        // --- everyday extras ----------------------------------------------
        StockTask(
            name = "Stretch",
            minutes = 5,
            color = LICHEN,
            icon = IconKeys.STRETCH,
            position = 7,
        ),
        StockTask(
            name = "Tidy up",
            minutes = 10,
            color = STONE,
            icon = IconKeys.TIDY,
            position = 8,
        ),
        StockTask(
            name = "Meditate",
            minutes = 10,
            color = HEATHER,
            icon = IconKeys.MEDITATE,
            position = 9,
        ),
        // --- night ---------------------------------------------------------
        StockTask(
            name = "Wash face",
            minutes = 5,
            color = LAKE,
            icon = IconKeys.WASH_FACE,
            position = 10,
        ),
        StockTask(
            name = "Pyjamas",
            minutes = 3,
            color = HEATHER,
            icon = IconKeys.DRESS,
            position = 11,
        ),
        StockTask(
            name = "Read",
            minutes = 20,
            color = REED,
            icon = IconKeys.READ,
            position = 12,
        ),
        StockTask(
            name = "Lights out",
            minutes = 1,
            color = PINE,
            icon = IconKeys.NIGHT,
            position = 13,
        ),
        // --- work ----------------------------------------------------------
        StockTask(
            name = "Check emails",
            minutes = 10,
            color = SLATE,
            icon = IconKeys.EMAIL,
            position = 14,
        ),
        StockTask(
            name = "Plan the day",
            minutes = 5,
            color = MOSS,
            icon = IconKeys.PLAN,
            position = 15,
        ),
        StockTask(
            name = "Deep work",
            minutes = 45,
            color = PINE,
            icon = IconKeys.WORK,
            position = 16,
        ),
        StockTask(
            name = "Standup",
            minutes = 15,
            color = CLAY,
            icon = IconKeys.MEETING,
            position = 17,
        ),
        StockTask(
            name = "Break",
            minutes = 10,
            color = STONE,
            icon = IconKeys.BREAK,
            position = 18,
        ),
        // --- evening -------------------------------------------------------
        StockTask(
            name = "Cook dinner",
            minutes = 30,
            color = CLAY,
            icon = IconKeys.COOK,
            position = 19,
        ),
        StockTask(
            name = "Wash up",
            minutes = 10,
            color = MIST,
            icon = IconKeys.WASH_UP,
            position = 20,
        ),
        StockTask(
            name = "Laundry",
            minutes = 10,
            color = SLATE,
            icon = IconKeys.LAUNDRY,
            position = 21,
        ),
        // --- exercise ------------------------------------------------------
        StockTask(
            name = "Warm up",
            minutes = 5,
            color = REED,
            icon = IconKeys.WARM_UP,
            position = 22,
        ),
        StockTask(
            name = "Workout",
            minutes = 30,
            color = EYE_RED,
            icon = IconKeys.WORKOUT,
            position = 23,
        ),
    )

    /** Lower-cased, trimmed names, so matching is forgiving of stray spaces. */
    private val builtInNames: Set<String> =
        builtIns.map { it.name.trim().lowercase() }.toSet()

    /**
     * The icons earlier builds gave these tasks, keyed by lower-cased name.
     *
     * This exists purely to repair seeds. A built-in's stored row beats the
     * catalogue during the merge (so a user's edited duration and colour
     * survive), which also means a stored icon beats the catalogue — forever.
     * Listing the old default here lets [upgradedIcon] spot "this is just the
     * seed we shipped" and move it to the current glyph.
     *
     * Only old *defaults* go here. A key the user picked in Settings is not in
     * any of these sets and is therefore preserved.
     */
    private val retiredIcons: Map<String, Set<String>> = mapOf(
        // The moon used to be the catch-all for anything physical or restful.
        "stretch" to setOf(IconKeys.STRETCH),
        "warm up" to setOf(IconKeys.STRETCH),
        "workout" to setOf(IconKeys.STRETCH),
        // The lotus was standing in for reading, and the moon for bedtime.
        "read" to setOf(IconKeys.MEDITATE),
        "lights out" to setOf(IconKeys.ALARM),
        // Washing your face looked like pouring a drink.
        "wash face" to setOf(IconKeys.WATER),
        // Everything below was the generic check mark.
        "tidy up" to setOf(IconKeys.TASK),
        "check emails" to setOf(IconKeys.TASK),
        "plan the day" to setOf(IconKeys.TASK),
        "deep work" to setOf(IconKeys.TASK),
        "standup" to setOf(IconKeys.TASK),
        "break" to setOf(IconKeys.TASK),
        "cook dinner" to setOf(IconKeys.TASK),
        "wash up" to setOf(IconKeys.TASK),
        "laundry" to setOf(IconKeys.TASK),
    )

    /** Whether [task] is one of the built-ins (and so cannot be deleted). */
    fun isBuiltIn(task: StockTask): Boolean = isBuiltIn(task.name)

    /** Whether a task called [name] is one of the built-ins. */
    fun isBuiltIn(name: String): Boolean = name.trim().lowercase() in builtInNames

    /**
     * [storedIcon], unless it is an icon this app has retired for [name], in
     * which case the catalogue's current icon is used instead.
     */
    fun upgradedIcon(name: String?, storedIcon: String, catalogueIcon: String): String {
        val retired = retiredIcons[name?.trim()?.lowercase()] ?: return storedIcon
        return if (storedIcon in retired) catalogueIcon else storedIcon
    }

    /**
     * The icon a task should draw with, given its name and stored key.
     *
     * Tasks already placed in a routine keep the icon key they were created
     * with, so a routine built before an icon was fixed would go on drawing the
     * old glyph on the run screen. Built-in names are stable, so the UI can ask
     * this at draw time instead of rewriting the user's rows.
     */
    fun resolveIcon(name: String?, icon: String?): String {
        val key = icon ?: return IconKeys.TASK
        val builtIn = builtIns.firstOrNull {
            it.name.equals(name?.trim(), ignoreCase = true)
        } ?: return key
        return upgradedIcon(name, key, builtIn.icon)
    }
}
