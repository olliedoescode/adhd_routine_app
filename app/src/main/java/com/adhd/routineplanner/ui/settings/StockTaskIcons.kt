package com.adhd.routineplanner.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.FreeBreakfast
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import com.adhd.routineplanner.domain.IconKeys

/**
 * Maps a stock task's stored [IconKeys] string to a Material icon.
 *
 * Keeping the mapping here rather than in the entity means the icon set can
 * change without a database migration, and an unknown key from a future version
 * falls back to a generic task icon instead of crashing.
 */
object StockTaskIcons {

    fun iconFor(key: String): ImageVector = when (key) {
        IconKeys.ALARM -> Icons.Filled.Alarm
        IconKeys.SHOWER -> Icons.Filled.WaterDrop
        IconKeys.DRESS -> Icons.Filled.Checkroom
        IconKeys.BREAKFAST -> Icons.Filled.FreeBreakfast
        IconKeys.BRUSH_TEETH -> Icons.Filled.CleaningServices
        IconKeys.MEDICATION -> Icons.Filled.Medication
        IconKeys.WATER -> Icons.Filled.LocalDrink
        IconKeys.MEDITATE -> Icons.Filled.SelfImprovement
        IconKeys.STRETCH -> Icons.Filled.Bedtime
        else -> Icons.Filled.TaskAlt
    }

    /** A human label for the icon picker. */
    fun labelFor(key: String): String = when (key) {
        IconKeys.ALARM -> "Alarm"
        IconKeys.SHOWER -> "Shower"
        IconKeys.DRESS -> "Clothes"
        IconKeys.BREAKFAST -> "Breakfast"
        IconKeys.BRUSH_TEETH -> "Teeth"
        IconKeys.MEDICATION -> "Medicine"
        IconKeys.WATER -> "Drink"
        IconKeys.MEDITATE -> "Meditate"
        IconKeys.STRETCH -> "Rest"
        else -> "Task"
    }
}
