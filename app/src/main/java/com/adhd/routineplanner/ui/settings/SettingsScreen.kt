package com.adhd.routineplanner.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adhd.routineplanner.alarm.AlarmTone
import com.adhd.routineplanner.alarm.AlarmToneOption
import com.adhd.routineplanner.data.AlarmSettings
import com.adhd.routineplanner.domain.IconKeys
import com.adhd.routineplanner.domain.StockTask
import com.adhd.routineplanner.domain.TimeParts
import com.adhd.routineplanner.ui.plan.DurationWheels
import com.adhd.routineplanner.ui.theme.AdhdRoutineAppTheme
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.SelectionOutline
import com.adhd.routineplanner.ui.theme.StockTaskColors
import com.adhd.routineplanner.ui.theme.Surface
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary
import com.adhd.routineplanner.viewmodel.SettingsViewModel
import kotlin.math.roundToInt

/**
 * Settings (spec §15.12).
 *
 * A plain list of settings, the first of which is the stock task library. Stock
 * tasks are the reusable pieces a routine is built from — shower, get dressed,
 * breakfast — and they appear as one-tap presets in the add-task dialog of the
 * routine creation and editing screens.
 *
 * The user can add, edit and delete as many as they like; the list is theirs,
 * so deleting everything is allowed and is not undone by a re-seed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val stockTasks by viewModel.stockTasks.collectAsStateWithLifecycle()
    val alarmSettings by viewModel.alarmSettings.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<StockTask?>(null) }
    var adding by remember { mutableStateOf(false) }
    var pickingTone by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = "Alarm",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "How the app gets your attention when a task starts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
            }

            item {
                AlarmSettingsCard(
                    settings = alarmSettings,
                    onPickTone = { pickingTone = true },
                    onVolumeChange = viewModel::setAlarmVolume,
                    onVibrateChange = viewModel::setAlarmVibrate,
                    onDurationChange = viewModel::setAlarmDurationSeconds,
                    onAlarmStrengthChange = viewModel::setAlarmVibrationStrength,
                    onTaskStrengthChange = viewModel::setTaskVibrationStrength,
                    onTonePreviewChange = viewModel::setTonePreviewEnabled,
                )
            }

            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Accountability",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Extra friction so you pick up your phone and see the routine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
            }

            item {
                TakeoverSettingsCard(
                    enabled = alarmSettings.takeoverEnabled,
                    onEnabledChange = viewModel::setTakeoverEnabled,
                )
            }

            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Stock tasks",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Reusable tasks offered when you add a task to a routine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
            }

            if (stockTasks.isEmpty()) {
                item {
                    Text(
                        text = "No stock tasks. Add one below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(stockTasks, key = { it.id }) { task ->
                    StockTaskRow(
                        task = task,
                        onClick = { editing = task },
                        onDelete = { viewModel.delete(task.id) },
                    )
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surface)
                        .clickable { adding = true }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Add stock task",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                    )
                }
            }
        }
    }

    if (pickingTone) {
        AlarmToneDialog(
            selectedUri = alarmSettings.toneUri,
            previewEnabled = alarmSettings.tonePreviewEnabled,
            onConfirm = { option ->
                viewModel.setAlarmTone(option.uri, option.title)
                pickingTone = false
            },
            onDismiss = { pickingTone = false },
        )
    }

    if (adding) {
        StockTaskDialog(
            initial = null,
            onConfirm = {
                viewModel.save(it)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }

    val current = editing
    if (current != null) {
        StockTaskDialog(
            initial = current,
            onConfirm = {
                viewModel.save(it)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/**
 * The alarm preferences card: tone, volume, vibration and ring duration.
 *
 * All four are app-wide. The tone row opens a picker; the two sliders write
 * straight through to the store on every change, which is cheap because
 * DataStore batches writes and the alarm only reads the value when it fires.
 */
@Composable
private fun AlarmSettingsCard(
    settings: AlarmSettings,
    onPickTone: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onVibrateChange: (Boolean) -> Unit,
    onDurationChange: (Int) -> Unit,
    onAlarmStrengthChange: (Float) -> Unit,
    onTaskStrengthChange: (Float) -> Unit,
    onTonePreviewChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Surface)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        // --- Tone ----------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onPickTone)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Tone",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                )
                Text(
                    text = settings.toneName?.takeIf { it.isNotBlank() }
                        ?: AlarmTone.DEFAULT_TITLE,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Text(
                text = "Change",
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
            )
        }

        Spacer(Modifier.height(8.dp))

        // --- Tone preview --------------------------------------------
        // Plays a short, quiet sample of each tone as it is tapped in the
        // picker, so the user can hear what they are choosing. Off for anyone
        // who finds it distracting.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Preview tones",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                )
                Text(
                    text = "Play a quiet sample when you tap a tone in the picker.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Switch(
                checked = settings.tonePreviewEnabled,
                onCheckedChange = onTonePreviewChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Background,
                    checkedTrackColor = TextPrimary,
                ),
            )
        }

        Spacer(Modifier.height(12.dp))

        // --- Volume --------------------------------------------------
        Text(
            text = "Volume · ${(settings.volume * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
        )
        Slider(
            value = settings.volume,
            onValueChange = onVolumeChange,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = TextPrimary,
                activeTrackColor = TextPrimary,
                inactiveTrackColor = TextSecondary.copy(alpha = 0.3f),
            ),
        )

        Spacer(Modifier.height(4.dp))

        // --- Duration ------------------------------------------------
        Text(
            text = "Ring for ${settings.durationSeconds}s",
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
        )
        Slider(
            value = settings.durationSeconds.toFloat(),
            onValueChange = { onDurationChange(it.roundToInt()) },
            valueRange = AlarmSettings.MIN_DURATION_SECONDS.toFloat()..
                AlarmSettings.MAX_DURATION_SECONDS.toFloat(),
            steps = AlarmSettings.MAX_DURATION_SECONDS -
                AlarmSettings.MIN_DURATION_SECONDS - 1,
            colors = SliderDefaults.colors(
                thumbColor = TextPrimary,
                activeTrackColor = TextPrimary,
                inactiveTrackColor = TextSecondary.copy(alpha = 0.3f),
            ),
        )

        Spacer(Modifier.height(4.dp))

        // --- Vibrate -------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Vibrate",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                )
                Text(
                    text = "Buzz alongside the tone",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Switch(
                checked = settings.vibrate,
                onCheckedChange = onVibrateChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Background,
                    checkedTrackColor = TextPrimary,
                ),
            )
        }

        Spacer(Modifier.height(12.dp))

        // --- Alarm vibration strength --------------------------------
        // Only meaningful when vibration is on, so it is dimmed otherwise.
        val strengthAlpha = if (settings.vibrate) 1f else 0.4f
        Text(
            text = "Alarm vibration · ${(settings.alarmVibrationStrength * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary.copy(alpha = strengthAlpha),
        )
        Text(
            text = "How hard the phone buzzes for a task alarm. 100% is a solid, " +
                "unbroken buzz at full power.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary.copy(alpha = strengthAlpha),
        )
        Slider(
            value = settings.alarmVibrationStrength,
            onValueChange = onAlarmStrengthChange,
            enabled = settings.vibrate,
            valueRange = AlarmSettings.MIN_VIBRATION_STRENGTH..
                AlarmSettings.MAX_VIBRATION_STRENGTH,
            colors = SliderDefaults.colors(
                thumbColor = TextPrimary,
                activeTrackColor = TextPrimary,
                inactiveTrackColor = TextSecondary.copy(alpha = 0.3f),
            ),
        )

        Spacer(Modifier.height(4.dp))

        // --- Task / notification vibration strength ------------------
        Text(
            text = "Task vibration · ${(settings.taskVibrationStrength * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
        )
        Text(
            text = "How hard the phone buzzes when a task changes or a run starts.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Slider(
            value = settings.taskVibrationStrength,
            onValueChange = onTaskStrengthChange,
            valueRange = AlarmSettings.MIN_VIBRATION_STRENGTH..
                AlarmSettings.MAX_VIBRATION_STRENGTH,
            colors = SliderDefaults.colors(
                thumbColor = TextPrimary,
                activeTrackColor = TextPrimary,
                inactiveTrackColor = TextSecondary.copy(alpha = 0.3f),
            ),
        )
    }
}

/**
 * The accountability card: a single switch for the lock-screen takeover
 * (plans/accountability-lockscreen.md).
 *
 * When on, the ongoing run notification carries a full-screen intent, so
 * turning the screen on during a run shows the routine over the lock screen
 * instead of the normal lock screen. The user can always swipe it away; it is
 * friction and a reminder, not a lock.
 */
@Composable
private fun TakeoverSettingsCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Surface)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Take over the lock screen",
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
            Text(
                text = "While a routine runs, turning the screen on shows the " +
                    "routine instead of your lock screen. Swipe it away to use " +
                    "your phone as normal.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Background,
                checkedTrackColor = TextPrimary,
            ),
        )
    }
}

/**
 * Picks an alarm tone from the device's list.
 *
 * The list is read once when the dialog opens, on a background thread, because
 * [AlarmTone.available] runs a `RingtoneManager` cursor. Until it arrives the
 * dialog shows a short "Loading" line rather than an empty list.
 *
 * Tapping a row *selects* it and closes the dialog, as before. When
 * [previewEnabled] is on, tapping also plays a short, quiet sample of the tone
 * first (via [AlarmTone.preview]) so the user hears what they picked. The
 * sample is stopped when the dialog closes, so nothing keeps playing
 * afterwards.
 */
@Composable
private fun AlarmToneDialog(
    selectedUri: String?,
    previewEnabled: Boolean,
    onConfirm: (AlarmToneOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var options by remember { mutableStateOf<List<AlarmToneOption>?>(null) }
    // The currently-playing preview, so a second tap stops the first sample
    // rather than layering two tones on top of each other.
    var previewPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        options = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            AlarmTone.available(context)
        }
    }

    // Stop the preview when the dialog leaves the composition (confirm or
    // dismiss), so a sample never outlives the picker.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            AlarmTone.stop(previewPlayer)
            previewPlayer = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = {
            Text(
                text = "Alarm tone",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
        },
        text = {
            val list = options
            if (list == null) {
                Text(
                    text = "Loading tones…",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    list.forEach { option ->
                        val selected = option.uri.ifBlank { null } == selectedUri
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) Background else Color.Transparent)
                                .clickable {
                                    if (previewEnabled) {
                                        // Replace any sample already playing.
                                        AlarmTone.stop(previewPlayer)
                                        previewPlayer = AlarmTone.preview(context, option.uri)
                                    }
                                    onConfirm(option)
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = option.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) TextPrimary else TextSecondary,
                                fontWeight = if (selected) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Text(
                                    text = "Selected",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextPrimary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TextPrimary)
            }
        },
    )
}

/** One stock task: its icon, name, duration and a delete button. */
@Composable
private fun StockTaskRow(
    task: StockTask,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Surface)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(task.color).copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = StockTaskIcons.iconFor(task.icon),
                contentDescription = null,
                tint = Color(task.color),
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = task.name,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = buildString {
                    append("${task.minutes} min")
                    if (task.alarmEnabled) append(" · alarm")
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Delete ${task.name}",
                tint = TextSecondary,
            )
        }
    }
}

/**
 * Add/edit dialog for a stock task: name, icon, colour, duration and alarm.
 *
 * @param initial the task to edit, or `null` to create a new one.
 */
@Composable
private fun StockTaskDialog(
    initial: StockTask?,
    onConfirm: (StockTask) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var icon by remember { mutableStateOf(initial?.icon ?: IconKeys.TASK) }
    var color by remember {
        mutableStateOf(initial?.color ?: StockTaskColors.first().toArgb())
    }
    var alarmEnabled by remember { mutableStateOf(initial?.alarmEnabled ?: false) }
    var parts by remember {
        mutableStateOf(TimeParts.fromMinutes(initial?.minutes ?: StockTask.DEFAULT_MINUTES))
    }

    val minutes = parts.totalMinutes.coerceIn(1, MAX_STOCK_MINUTES)
    val nameValid = name.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = {
            Text(
                text = if (initial == null) "New stock task" else "Edit stock task",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                // --- Name ------------------------------------------------
                Text(
                    text = "Name",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Background, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (name.isEmpty()) {
                        Text(
                            text = "e.g. Shower",
                            style = TextStyle(color = TextSecondary, fontSize = 16.sp),
                        )
                    }
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        cursorBrush = SolidColor(TextPrimary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(16.dp))

                // --- Icon ------------------------------------------------
                Text(
                    text = "Icon",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                IconKeys.ALL.chunked(5).forEach { rowKeys ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowKeys.forEach { key ->
                            val selected = key == icon
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) TextPrimary else Background)
                                    .clickable {
                                        haptics.performHapticFeedback(
                                            HapticFeedbackType.TextHandleMove,
                                        )
                                        icon = key
                                    }
                                    .padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    imageVector = StockTaskIcons.iconFor(key),
                                    contentDescription = StockTaskIcons.labelFor(key),
                                    tint = if (selected) Background else TextSecondary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = StockTaskIcons.labelFor(key),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) Background else TextSecondary,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(8.dp))

                // --- Colour ----------------------------------------------
                Text(
                    text = "Colour",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                StockTaskColors.chunked(6).forEach { rowColors ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowColors.forEach { stock ->
                            val argb = stock.toArgb()
                            val selected = argb == color
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .background(stock, CircleShape)
                                    .border(
                                        width = if (selected) 2.dp else 0.dp,
                                        color = if (selected) SelectionOutline else Color.Transparent,
                                        shape = CircleShape,
                                    )
                                    .clickable {
                                        haptics.performHapticFeedback(
                                            HapticFeedbackType.TextHandleMove,
                                        )
                                        color = argb
                                    },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(8.dp))

                // --- Duration --------------------------------------------
                Text(
                    text = "Default duration",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                DurationWheels(
                    parts = parts,
                    maxMinutes = MAX_STOCK_MINUTES,
                    onPartsChange = { parts = it },
                )

                Spacer(Modifier.height(16.dp))

                // --- Alarm -----------------------------------------------
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Alarm",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary,
                        )
                        Text(
                            text = "Ring when this task starts",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                    Switch(
                        checked = alarmEnabled,
                        onCheckedChange = { alarmEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Background,
                            checkedTrackColor = TextPrimary,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onConfirm(
                        StockTask(
                            id = initial?.id ?: 0,
                            name = name.trim(),
                            minutes = minutes,
                            color = color,
                            icon = icon,
                            alarmEnabled = alarmEnabled,
                            position = initial?.position ?: 0,
                        ),
                    )
                },
                enabled = nameValid,
            ) {
                Text(
                    text = if (initial == null) "Add" else "Save",
                    color = if (nameValid) TextPrimary else TextSecondary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
    )
}

/** Longest default duration a stock task may have. */
private const val MAX_STOCK_MINUTES: Int = 240

@Preview(name = "Settings", showBackground = true, backgroundColor = 0xFF0E0F12)
@Composable
private fun SettingsPreview() {
    AdhdRoutineAppTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StockTaskRow(
                task = StockTask(
                    id = 1,
                    name = "Shower",
                    minutes = 10,
                    color = 0xFF4F8EF7.toInt(),
                    icon = IconKeys.SHOWER,
                ),
                onClick = {},
                onDelete = {},
            )
            StockTaskRow(
                task = StockTask(
                    id = 2,
                    name = "Wake up",
                    minutes = 1,
                    color = 0xFFF2C94C.toInt(),
                    icon = IconKeys.ALARM,
                    alarmEnabled = true,
                ),
                onClick = {},
                onDelete = {},
            )
        }
    }
}
