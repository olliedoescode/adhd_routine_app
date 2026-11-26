package com.adhd.routineplanner.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adhd.routineplanner.data.AlarmSettings
import com.adhd.routineplanner.data.AlarmSettingsStore
import com.adhd.routineplanner.data.StockTaskRepository
import com.adhd.routineplanner.domain.StockTask
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the settings screen (spec §15.12).
 *
 * Right now the only setting is the stock task library: the reusable tasks the
 * add-task picker offers. The user can add, edit and delete as many as they
 * like, and the list is fully theirs — deleting every stock task is allowed and
 * is not undone by a re-seed.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: StockTaskRepository,
    private val alarmSettingsStore: AlarmSettingsStore,
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

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }
}
