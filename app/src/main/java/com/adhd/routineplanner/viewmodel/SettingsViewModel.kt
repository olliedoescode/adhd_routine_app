package com.adhd.routineplanner.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adhd.routineplanner.data.AlarmSettings
import com.adhd.routineplanner.data.AlarmSettingsStore
import com.adhd.routineplanner.data.StockTaskRepository
import com.adhd.routineplanner.data.ThemeStore
import com.adhd.routineplanner.data.VibrationPattern
import com.adhd.routineplanner.domain.StockTask
import com.adhd.routineplanner.domain.StockTaskCatalog
import com.adhd.routineplanner.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the settings screen (spec §15.12).
 *
 * The stock task library is the reusable set the add-task picker offers: the
 * app's built-ins ([com.adhd.routineplanner.domain.StockTaskCatalog]) followed by
 * whatever the user has added. Built-ins can be retuned but not deleted or
 * renamed — they are part of the app — while the user's own tasks can be edited
 * and removed freely. Deleting those is not undone by a re-seed.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: StockTaskRepository,
    private val alarmSettingsStore: AlarmSettingsStore,
    private val themeStore: ThemeStore,
) : ViewModel() {

    val stockTasks: StateFlow<List<StockTask>> = repository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * The app-wide alarm preferences. Defaults are emitted immediately so the
     * settings screen never has to handle a "not loaded yet" state.
     */
    val alarmSettings: StateFlow<AlarmSettings> = alarmSettingsStore.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AlarmSettings(),
        )

    /**
     * The theme currently selected in the appearance picker. Seeded with the
     * default so the picker draws a tick immediately; the stored value arrives
     * on the first emission from [ThemeStore].
     */
    val theme: StateFlow<AppTheme> = themeStore.theme
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AppTheme.DEFAULT,
        )

    /** Switches the whole app to [theme] and remembers it across launches. */
    fun setTheme(theme: AppTheme) {
        viewModelScope.launch { themeStore.setTheme(theme) }
    }

    /** Stores the chosen tone; a blank uri resets to the system default. */
    fun setAlarmTone(uri: String?, name: String?) {
        viewModelScope.launch {
            alarmSettingsStore.setTone(uri?.takeIf { it.isNotBlank() }, name)
        }
    }

    fun setAlarmVolume(volume: Float) {
        viewModelScope.launch { alarmSettingsStore.setVolume(volume) }
    }

    fun setAlarmVibrate(enabled: Boolean) {
        viewModelScope.launch { alarmSettingsStore.setVibrate(enabled) }
    }

    /** The rhythm of the alarm's buzz (Solid / Pulse / Heartbeat / Escalating). */
    fun setVibrationPattern(pattern: VibrationPattern) {
        viewModelScope.launch { alarmSettingsStore.setVibrationPattern(pattern) }
    }

    /** How hard a task alarm buzzes, 0f..1f. */
    fun setAlarmVibrationStrength(strength: Float) {
        viewModelScope.launch { alarmSettingsStore.setAlarmVibrationStrength(strength) }
    }

    /** How hard a task boundary / run start buzzes, 0f..1f. */
    fun setTaskVibrationStrength(strength: Float) {
        viewModelScope.launch { alarmSettingsStore.setTaskVibrationStrength(strength) }
    }

    fun setAlarmDurationSeconds(seconds: Int) {
        viewModelScope.launch { alarmSettingsStore.setDurationSeconds(seconds) }
    }

    /** Turns the quiet tone preview in the tone picker on or off. */
    fun setTonePreviewEnabled(enabled: Boolean) {
        viewModelScope.launch { alarmSettingsStore.setTonePreviewEnabled(enabled) }
    }

    /**
     * Turns the lock-screen takeover on or off
     * (plans/accountability-lockscreen.md). When on, turning the screen on
     * during a run shows the routine over the lock screen.
     */
    fun setTakeoverEnabled(enabled: Boolean) {
        viewModelScope.launch { alarmSettingsStore.setTakeoverEnabled(enabled) }
    }

    /**
     * Saves a stock task. A new task is appended to the end of the list; an
     * existing one keeps its position.
     */
    fun save(task: StockTask) {
        viewModelScope.launch {
            val position = if (task.id == 0L) {
                (stockTasks.value.maxOfOrNull { it.position } ?: -1) + 1
            } else {
                task.position
            }
            repository.save(task.copy(position = position))
        }
    }

    /**
     * Deletes a stock task the user made. The built-in tasks are part of the app
     * rather than the user's data, so they are refused here too — the UI hides
     * their delete affordance, but the guard belongs where the write happens.
     */
    fun delete(task: StockTask) {
        if (StockTaskCatalog.isBuiltIn(task)) return
        viewModelScope.launch { repository.deleteCustom(task) }
    }
}
