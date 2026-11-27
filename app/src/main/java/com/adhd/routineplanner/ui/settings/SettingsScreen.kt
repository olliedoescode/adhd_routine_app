package com.adhd.routineplanner.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.adhd.routineplanner.alarm.Vibration
import com.adhd.routineplanner.data.AlarmSettings
import com.adhd.routineplanner.data.VibrationPattern
import com.adhd.routineplanner.domain.IconKeys
import com.adhd.routineplanner.domain.StockTask
import com.adhd.routineplanner.domain.StockTaskCatalog
import com.adhd.routineplanner.domain.TimeParts
import com.adhd.routineplanner.ui.common.AppSwitch
import com.adhd.routineplanner.ui.common.AppTopBar
import com.adhd.routineplanner.ui.common.OptionChips
import com.adhd.routineplanner.ui.common.StockTaskChip
import com.adhd.routineplanner.ui.common.SettingRow
import com.adhd.routineplanner.ui.common.SettingsCard
import com.adhd.routineplanner.ui.common.SettingsDivider
import com.adhd.routineplanner.ui.common.SettingsSectionHeader
import com.adhd.routineplanner.ui.common.nearestIndex
import com.adhd.routineplanner.ui.common.toChip
import com.adhd.routineplanner.ui.plan.DurationWheels
import com.adhd.routineplanner.ui.routines.AlarmSetupCard
import com.adhd.routineplanner.ui.theme.Accent
import com.adhd.routineplanner.ui.theme.AdhdRoutineAppTheme
import com.adhd.routineplanner.ui.theme.AppTheme
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.GridLine
import com.adhd.routineplanner.ui.theme.SelectionOutline
import com.adhd.routineplanner.ui.theme.StockTaskColors
import com.adhd.routineplanner.ui.theme.Surface
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary
import com.adhd.routineplanner.viewmodel.SettingsViewModel

/**
 * Settings (spec §15.12).
 *
 * One page of sections, in the order a morning actually happens:
 *
 *  1. **Appearance** — which palette the app sits in (see [AppTheme]).
 *  2. **Alarm** — the tone, how loud it is and how long it rings.
 *  3. **Vibration** — whether it buzzes, the rhythm, and how hard.
 *  4. **Lock screen** — the takeover switch (plans/accountability-lockscreen.md).
 *  5. **Alarm setup** — the permissions that decide whether an alarm fires.
 *  6. **Stock tasks** — the reusable pieces a routine is built from.
 *
 * Alarm setup used to be a separate screen behind a menu item, which split the
 * alarm story across two pages: the things that decide whether an alarm fires
 * lived apart from the things that decide how it sounds. They are both here now.
 *
 * Numbers are kept out of the UI. Volume, ring length and buzz strength are
 * stored as continuous values but offered as three or four plain words, because
 * nobody can hear the difference between 68% and 72%, and a percentage read-out
 * makes a calm page look like a mixing desk.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val stockTasks by viewModel.stockTasks.collectAsStateWithLifecycle()
    val alarmSettings by viewModel.alarmSettings.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<StockTask?>(null) }
    var adding by remember { mutableStateOf(false) }
    var pickingTone by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Background,
        topBar = {
            AppTopBar(
                title = "Settings",
                subtitle = "Sound, alarms and tasks",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // --- Appearance ------------------------------------------------
            item {
                Column {
                    SettingsSectionHeader(
                        title = "Appearance",
                        subtitle = "The light the app sits in.",
                    )
                    Spacer(Modifier.height(10.dp))
                    AppearanceSectionCard(
                        selected = theme,
                        onSelect = viewModel::setTheme,
                    )
                }
            }

            // --- Alarm -----------------------------------------------------
            item {
                Column {
                    SettingsSectionHeader(
                        title = "Alarm",
                        subtitle = "What a task alarm sounds like.",
                    )
                    Spacer(Modifier.height(10.dp))
                    AlarmSectionCard(
                        settings = alarmSettings,
                        onPickTone = { pickingTone = true },
                        onTonePreviewChange = viewModel::setTonePreviewEnabled,
                        onVolumeChange = viewModel::setAlarmVolume,
                        onDurationChange = viewModel::setAlarmDurationSeconds,
                    )
                }
            }

            // --- Vibration -------------------------------------------------
            item {
                Column {
                    SettingsSectionHeader(
                        title = "Vibration",
                        subtitle = "How the phone buzzes, and how hard.",
                    )
                    Spacer(Modifier.height(10.dp))
                    VibrationSectionCard(
                        settings = alarmSettings,
                        onVibrateChange = viewModel::setAlarmVibrate,
                        onPatternChange = viewModel::setVibrationPattern,
                        onAlarmStrengthChange = viewModel::setAlarmVibrationStrength,
                        onTaskStrengthChange = viewModel::setTaskVibrationStrength,
                    )
                }
            }

            // --- Lock screen -----------------------------------------------
            item {
                Column {
                    SettingsSectionHeader(
                        title = "Lock screen",
                        subtitle = "Extra friction so you actually see the routine.",
                    )
                    Spacer(Modifier.height(10.dp))
                    SettingsCard {
                        SettingRow(
                            title = "Take over the lock screen",
                            subtitle = "While a routine runs, turning the screen on shows " +
                                "the routine instead of your lock screen. Swipe it away " +
                                "to use your phone as normal.",
                            trailing = {
                                SettingsSwitch(
                                    checked = alarmSettings.takeoverEnabled,
                                    onCheckedChange = viewModel::setTakeoverEnabled,
                                )
                            },
                        )
                    }
                }
            }

            // --- Alarm setup (permissions) ---------------------------------
            item {
                Column {
                    SettingsSectionHeader(
                        title = "Alarm setup",
                        subtitle = "The handful of permissions an alarm needs to fire on time.",
                    )
                    Spacer(Modifier.height(10.dp))
                    AlarmSetupCard()
                }
            }

            // --- Stock tasks -----------------------------------------------
            item {
                Column {
                    SettingsSectionHeader(
                        title = "Stock tasks",
                        subtitle = "Reusable tasks offered when you add a task to a routine.",
                    )
                    Spacer(Modifier.height(10.dp))
                    SettingsCard {
                        StockTaskGrid(
                            stockTasks = stockTasks,
                            onEdit = { editing = it },
                            onDelete = { viewModel.delete(it) },
                        )
                        SettingsDivider()
                        AddStockTaskRow(onClick = { adding = true })
                    }
                }
            }
        }
    }

    if (pickingTone) {
        AlarmToneDialog(
            selectedUri = alarmSettings.toneUri,
            previewEnabled = alarmSettings.tonePreviewEnabled,
            onConfirm = { option ->
                // Selecting a tone keeps the dialog open so the user can
                // audition several; the preview plays on each tap.
                viewModel.setAlarmTone(option.uri, option.title)
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

// --- value maps ------------------------------------------------------------
//
// Each stored setting is continuous, each control is three or four words. The
// chip label list and the value list are kept side by side so a mapping can be
// read in one glance, and `nearestIndex` decides which chip a stored value
// corresponds to. An existing 0.8f volume lands on "Balanced", not on nothing.

private val LOUDNESS_LABELS = listOf("Quiet", "Balanced", "Loud")
private val LOUDNESS_VALUES = listOf(0.35f, 0.7f, 1f)

private val RING_LABELS = listOf("Short", "Medium", "Long", "Max")
private val RING_VALUES = listOf(5, 10, 20, 30)
private val RING_VALUES_F = RING_VALUES.map { it.toFloat() }

private val BUZZ_LABELS = listOf("Gentle", "Medium", "Strong")
private val BUZZ_VALUES = listOf(0.35f, 0.65f, 1f)

/**
 * The appearance picker: one tile per [AppTheme], in two columns.
 *
 * Each tile is drawn in *its own* palette rather than the active one, so the
 * choice is a preview rather than a swatch — a light theme's tile is genuinely
 * pale even while the app is dark. The selected tile carries an accent ring and
 * a tick, which stay legible whichever way the user is moving.
 */
@Composable
private fun AppearanceSectionCard(
    selected: AppTheme,
    onSelect: (AppTheme) -> Unit,
) {
    SettingsCard {
        Column(
            modifier = Modifier.padding(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppTheme.entries.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { theme ->
                        ThemeTile(
                            theme = theme,
                            selected = theme == selected,
                            onSelect = { onSelect(theme) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keeps a lone final tile the same width as the rest.
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * One theme preview: a miniature page (accent rule, two lines of "text", the
 * red eye) drawn in that theme's own colours, with its name underneath.
 */
@Composable
private fun ThemeTile(
    theme: AppTheme,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = theme.palette
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(palette.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) palette.accent else palette.gridLine,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onSelect)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.background)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(palette.accent),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.85f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(palette.textPrimary),
                )
                Box(
                    Modifier
                        .fillMaxWidth(0.55f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(palette.textSecondary),
                )
            }
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(palette.countdownRed),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = theme.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.textPrimary,
                )
                Text(
                    text = theme.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = palette.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * A switch styled for the active palette.
 *
 * Kept as a thin local alias so the settings page reads the same as before;
 * the colours themselves come from [AppSwitch], which every other switch in the
 * app now uses too, so "on" looks identical wherever it appears.
 */
@Composable
private fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    AppSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
    )
}

/** Tone, tone preview, loudness and ring length. */
@Composable
private fun AlarmSectionCard(
    settings: AlarmSettings,
    onPickTone: () -> Unit,
    onTonePreviewChange: (Boolean) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onDurationChange: (Int) -> Unit,
) {
    SettingsCard {
        SettingRow(
            title = "Tone",
            subtitle = settings.toneName?.takeIf { it.isNotBlank() }
                ?: AlarmTone.DEFAULT_TITLE,
            onClick = onPickTone,
            trailing = {
                Text(
                    text = "Change",
                    style = MaterialTheme.typography.labelLarge,
                    color = Accent,
                )
            },
        )
        SettingsDivider()
        SettingRow(
            title = "Preview tones",
            subtitle = "Hear a quiet sample as you browse the list.",
            trailing = {
                SettingsSwitch(
                    checked = settings.tonePreviewEnabled,
                    onCheckedChange = onTonePreviewChange,
                )
            },
        )
        SettingsDivider()
        OptionChips(
            title = "Loudness",
            subtitle = "Quiet is a nudge. Loud is a fire alarm.",
            options = LOUDNESS_LABELS,
            selectedIndex = nearestIndex(settings.volume, LOUDNESS_VALUES),
            onSelect = { onVolumeChange(LOUDNESS_VALUES[it]) },
        )
        SettingsDivider()
        OptionChips(
            title = "Ring for",
            subtitle = "How long it keeps going before it gives up.",
            options = RING_LABELS,
            selectedIndex = nearestIndex(settings.durationSeconds.toFloat(), RING_VALUES_F),
            onSelect = { onDurationChange(RING_VALUES[it]) },
        )
    }
}

/** Vibrate switch, pattern and the two strengths. */
@Composable
private fun VibrationSectionCard(
    settings: AlarmSettings,
    onVibrateChange: (Boolean) -> Unit,
    onPatternChange: (VibrationPattern) -> Unit,
    onAlarmStrengthChange: (Float) -> Unit,
    onTaskStrengthChange: (Float) -> Unit,
) {
    val context = LocalContext.current
    val patterns = VibrationPattern.entries
    SettingsCard {
        SettingRow(
            title = "Vibrate",
            subtitle = "Buzz alongside the tone.",
            trailing = {
                SettingsSwitch(
                    checked = settings.vibrate,
                    onCheckedChange = onVibrateChange,
                )
            },
        )
        SettingsDivider()
        OptionChips(
            title = "Pattern",
            subtitle = "The rhythm of the buzz.",
            options = patterns.map { vibrationPatternLabel(it) },
            selectedIndex = patterns.indexOf(settings.vibrationPattern),
            enabled = settings.vibrate,
            onSelect = { index ->
                val pattern = patterns[index]
                onPatternChange(pattern)
                // Buzz a one-off sample at the strength the pattern will
                // actually run at, so the choice is felt rather than read.
                Vibration.preview(
                    context = context,
                    strength = settings.alarmVibrationStrength,
                    pattern = pattern,
                )
            },
        )
        SettingsDivider()
        OptionChips(
            title = "Alarm buzz",
            subtitle = "How hard it buzzes for a task alarm.",
            options = BUZZ_LABELS,
            selectedIndex = nearestIndex(settings.alarmVibrationStrength, BUZZ_VALUES),
            enabled = settings.vibrate,
            onSelect = { onAlarmStrengthChange(BUZZ_VALUES[it]) },
        )
        SettingsDivider()
        OptionChips(
            title = "Task buzz",
            subtitle = "How hard it buzzes when a task changes or a run starts.",
            options = BUZZ_LABELS,
            selectedIndex = nearestIndex(settings.taskVibrationStrength, BUZZ_VALUES),
            onSelect = { onTaskStrengthChange(BUZZ_VALUES[it]) },
        )
    }
}

/** Human-readable label for a [VibrationPattern] chip. */
private fun vibrationPatternLabel(pattern: VibrationPattern): String = when (pattern) {
    VibrationPattern.Solid -> "Solid"
    VibrationPattern.Pulse -> "Pulse"
    VibrationPattern.Heartbeat -> "Heartbeat"
    VibrationPattern.Escalating -> "Escalating"
}

/**
 * Picks an alarm tone from the device's list.
 *
 * The list is read once when the dialog opens, on a background thread, because
 * [AlarmTone.available] runs a `RingtoneManager` cursor. Until it arrives the
 * dialog shows a short "Loading" line rather than an empty list.
 *
 * Tapping a row *selects* it and keeps the dialog open, so the user can
 * audition several tones before settling on one. When [previewEnabled] is on,
 * tapping plays a short, quiet sample of the tone (via [AlarmTone.preview]),
 * capped at three seconds. The sample is stopped when the dialog closes, so
 * nothing keeps playing afterwards.
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
    // Bumped on every tap so the auto-stop coroutine below restarts its timer
    // for the newest sample instead of cutting a later one short.
    var previewToken by remember { mutableStateOf(0) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        options = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            AlarmTone.available(context)
        }
    }

    // Cap every preview at three seconds. Keyed on the token so each new tap
    // cancels the previous timer and starts a fresh one for the new sample.
    androidx.compose.runtime.LaunchedEffect(previewToken) {
        if (previewToken == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(AlarmTone.PREVIEW_MAX_MS)
        AlarmTone.stop(previewPlayer)
        previewPlayer = null
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
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) Background else Color.Transparent)
                                .clickable {
                                    // Preview on tap, but do *not* close the
                                    // dialog: the user needs to hear several
                                    // tones to compare them, and a picker that
                                    // vanishes on the first tap makes that
                                    // impossible. The row is marked "Selected"
                                    // and the dialog is closed with "Close".
                                    if (previewEnabled) {
                                        // Replace any sample already playing.
                                        AlarmTone.stop(previewPlayer)
                                        previewPlayer = AlarmTone.preview(context, option.uri)
                                        // Restart the three-second cap for this sample.
                                        previewToken++
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
                                // Selection is carried by weight *and* colour,
                                // but both tones stay on the light side of the
                                // ladder so the list does not shout.
                                fontWeight = if (selected) {
                                    FontWeight.Medium
                                } else {
                                    FontWeight.Light
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Text(
                                    text = "Selected",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Accent,
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

/**
 * The stock tasks as a wrapping flow of chips: the same chip the create flow's
 * task step uses, so the two screens read as one app rather than two lists.
 *
 * The built-in tasks come first and cannot be removed — they are part of the app,
 * not the user's data. Their own tasks follow and carry a small cross. Tapping any
 * chip opens the editor.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StockTaskGrid(
    stockTasks: List<StockTask>,
    onEdit: (StockTask) -> Unit,
    onDelete: (StockTask) -> Unit,
) {
    if (stockTasks.isEmpty()) {
        Text(
            text = "No stock tasks yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 8.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            stockTasks.forEach { task ->
                val remove: (() -> Unit)? =
                    if (StockTaskCatalog.isBuiltIn(task)) null else { { onDelete(task) } }
                StockTaskChip(
                    chip = task.toChip(),
                    onClick = { onEdit(task) },
                    onDelete = remove,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Tap a task to change it. Only the ones you add yourself can be removed.",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
    }
}

/** The "Add stock task" row at the foot of the stock task card. */
@Composable
private fun AddStockTaskRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Add stock task",
            style = MaterialTheme.typography.bodyLarge,
            color = Accent,
        )
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

    // A built-in task is identified by its name, so renaming one would leave the
    // catalogue entry behind and look like the task had been duplicated. Its
    // name is therefore fixed; duration, colour, icon and alarm stay editable.
    val builtIn = initial != null && StockTaskCatalog.isBuiltIn(initial)

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
                        .background(Background, RoundedCornerShape(12.dp))
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
                        onValueChange = { if (!builtIn) name = it },
                        enabled = !builtIn,
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
                if (builtIn) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Built-in tasks keep their name. You can still change " +
                            "the duration, colour, icon and alarm.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
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
                // Four per row: the labels sit in narrow cells, so five
                // would start truncating words like "Medicine".
                IconKeys.ALL.chunked(4).forEach { rowKeys ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowKeys.forEach { key ->
                            val selected = key == icon
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) Accent else Background)
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
                                        color = if (selected) {
                                            SelectionOutline
                                        } else {
                                            Color.Transparent
                                        },
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
                    SettingsSwitch(
                        checked = alarmEnabled,
                        onCheckedChange = { alarmEnabled = it },
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
                    color = if (nameValid) Accent else TextSecondary,
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

@Preview(name = "Settings", showBackground = true, backgroundColor = 0xFF0A1116)
@Composable
private fun SettingsPreview() {
    AdhdRoutineAppTheme {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                SettingsSectionHeader(
                    title = "Alarm",
                    subtitle = "What a task alarm sounds like.",
                )
                Spacer(Modifier.height(10.dp))
                SettingsCard {
                    SettingRow(
                        title = "Tone",
                        subtitle = "Loon (default)",
                        onClick = {},
                        trailing = {
                            Text(
                                text = "Change",
                                style = MaterialTheme.typography.labelLarge,
                                color = Accent,
                            )
                        },
                    )
                    SettingsDivider()
                    OptionChips(
                        title = "Loudness",
                        subtitle = "Quiet is a nudge. Loud is a fire alarm.",
                        options = LOUDNESS_LABELS,
                        selectedIndex = 1,
                        onSelect = {},
                    )
                }
            }
            Column {
                SettingsSectionHeader(
                    title = "Stock tasks",
                    subtitle = "Reusable tasks offered when you add a task to a routine.",
                )
                Spacer(Modifier.height(10.dp))
                SettingsCard {
                    StockTaskGrid(
                        stockTasks = listOf(
                            StockTask(
                                id = 1,
                                name = "Wake up",
                                minutes = 1,
                                color = 0xFFC9B37E.toInt(),
                                icon = IconKeys.ALARM,
                                alarmEnabled = true,
                            ),
                            StockTask(
                                id = 2,
                                name = "Shower",
                                minutes = 10,
                                color = 0xFF8FA9CF.toInt(),
                                icon = IconKeys.SHOWER,
                            ),
                            StockTask(
                                id = 3,
                                name = "Take vitamins",
                                minutes = 2,
                                color = 0xFF87BFA6.toInt(),
                                icon = IconKeys.TASK,
                            ),
                        ),
                        onEdit = {},
                        onDelete = {},
                    )
                    SettingsDivider()
                    AddStockTaskRow(onClick = {})
                }
            }
        }
    }
}
