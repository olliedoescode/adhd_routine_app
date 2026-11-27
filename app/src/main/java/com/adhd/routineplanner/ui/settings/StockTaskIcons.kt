package com.adhd.routineplanner.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DinnerDining
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.FreeBreakfast
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.LocalLaundryService
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Shower
import androidx.compose.material.icons.filled.Soap
import androidx.compose.material.icons.filled.SportsGymnastics
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Wash
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.adhd.routineplanner.domain.IconKeys

/**
 * Maps a stock task's stored [IconKeys] string to a Material icon.
 *
 * Keeping the mapping here rather than in the entity means the icon set can
 * change without a database migration, and an unknown key from a future version
 * falls back to a generic task icon instead of crashing.
 *
 * The glyphs are chosen to say what the task *is*, not what it was grouped
 * with: a workout is a dumbbell rather than a moon, washing up is a soap bar,
 * reading is a book rather than a meditation lotus. Meditation keeps the lotus,
 * because that is the one place it is literally right.
 */
object StockTaskIcons {

    fun iconFor(key: String): ImageVector = when (key) {
        IconKeys.ALARM -> Icons.Filled.Alarm
        IconKeys.NIGHT -> Icons.Filled.NightsStay
        IconKeys.MEDICATION -> Icons.Filled.Medication
        IconKeys.WATER -> Icons.Filled.LocalDrink
        IconKeys.SHOWER -> Icons.Filled.Shower
        IconKeys.BRUSH_TEETH -> Toothbrush
        IconKeys.DRESS -> Icons.Filled.Checkroom
        IconKeys.BREAKFAST -> Icons.Filled.FreeBreakfast
        IconKeys.MEDITATE -> Icons.Filled.SelfImprovement
        IconKeys.READ -> Icons.Filled.MenuBook
        IconKeys.STRETCH -> Icons.Filled.SportsGymnastics
        IconKeys.WARM_UP -> Icons.Filled.Accessibility
        IconKeys.WORKOUT -> Icons.Filled.FitnessCenter
        IconKeys.WASH_FACE -> Icons.Filled.Wash
        IconKeys.TIDY -> Icons.Filled.CleaningServices
        IconKeys.LAUNDRY -> Icons.Filled.LocalLaundryService
        IconKeys.WASH_UP -> Icons.Filled.Soap
        IconKeys.COOK -> Icons.Filled.DinnerDining
        IconKeys.BREAK -> Icons.Filled.LocalCafe
        IconKeys.EMAIL -> Icons.Filled.MarkEmailRead
        IconKeys.PLAN -> Icons.Filled.EditNote
        IconKeys.WORK -> Icons.Filled.Work
        IconKeys.MEETING -> Icons.Filled.Groups
        else -> Icons.Filled.TaskAlt
    }

    /** A human label for the icon picker. Kept short: the cells are narrow. */
    fun labelFor(key: String): String = when (key) {
        IconKeys.ALARM -> "Alarm"
        IconKeys.NIGHT -> "Night"
        IconKeys.MEDICATION -> "Medicine"
        IconKeys.WATER -> "Drink"
        IconKeys.SHOWER -> "Shower"
        IconKeys.BRUSH_TEETH -> "Teeth"
        IconKeys.DRESS -> "Clothes"
        IconKeys.BREAKFAST -> "Breakfast"
        IconKeys.MEDITATE -> "Meditate"
        IconKeys.READ -> "Read"
        IconKeys.STRETCH -> "Stretch"
        IconKeys.WARM_UP -> "Warm up"
        IconKeys.WORKOUT -> "Workout"
        IconKeys.WASH_FACE -> "Wash"
        IconKeys.TIDY -> "Tidy"
        IconKeys.LAUNDRY -> "Laundry"
        IconKeys.WASH_UP -> "Dishes"
        IconKeys.COOK -> "Cook"
        IconKeys.BREAK -> "Break"
        IconKeys.EMAIL -> "Email"
        IconKeys.PLAN -> "Plan"
        IconKeys.WORK -> "Work"
        IconKeys.MEETING -> "Meeting"
        else -> "Task"
    }

    /**
     * A toothbrush, for [IconKeys.BRUSH_TEETH].
     *
     * Material has no toothbrush glyph, and the obvious stand-in — [Icons.Filled
     * .Brush] — is a *paintbrush*, which is why this task used to look like
     * "paint something". The path is the same one the notification's
     * `ic_task_brush_teeth` drawable uses, so the picker, the chips and the
     * ongoing notification all agree on what brushing your teeth looks like.
     *
     * Between the two faces of the app: the Compose side has no resource lookup
     * in this mapping (that is the point of it), so the path is inlined here
     * rather than loaded from the drawable.
     */
    private val Toothbrush: ImageVector = ImageVector.Builder(
        name = "Toothbrush",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(TOOTHBRUSH_PATH).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()

    private const val TOOTHBRUSH_PATH: String =
        "M19.5,3.5c-1.1,0 -2,0.9 -2,2 0,0.4 0.1,0.7 0.3,1L9.6,14.7c-0.3,-0.2 " +
            "-0.6,-0.3 -1,-0.3 -1.1,0 -2,0.9 -2,2 0,0.4 0.1,0.7 0.3,1l-3.4,3.4 " +
            "1.4,1.4 3.4,-3.4c0.3,0.2 0.6,0.3 1,0.3 1.1,0 2,-0.9 2,-2 " +
            "0,-0.4 -0.1,-0.7 -0.3,-1l8.2,-8.2c0.3,0.2 0.6,0.3 1,0.3 " +
            "1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2z"
}
