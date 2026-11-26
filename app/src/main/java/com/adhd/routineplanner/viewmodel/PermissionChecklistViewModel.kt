package com.adhd.routineplanner.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adhd.routineplanner.alarm.AlarmPermissionStatus
import com.adhd.routineplanner.alarm.AlarmPermissions
import com.adhd.routineplanner.alarm.AlarmScheduler
import com.adhd.routineplanner.alarm.NotificationChannels
import com.adhd.routineplanner.domain.AlarmSchedule
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the first-run permission checklist (spec section 15.10).
 *
 * Holds the current [AlarmPermissionStatus] and re-checks it on resume. The
 * Test alarm button schedules a one-off alarm one minute ahead so alarm
 * reliability can be proven on the device before run mode is built.
 */
@HiltViewModel
class PermissionChecklistViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduler: AlarmScheduler,
) : ViewModel() {

    private val _status = MutableStateFlow(AlarmPermissions.status(context))
    val status: StateFlow<AlarmPermissionStatus> = _status.asStateFlow()

    /**
     * Epoch millis at which the pending test alarm will fire, or `null` when no
     * test is pending. Exposed as a time rather than a boolean so the UI can
     * show "fires at 14:32" and clear itself once the moment passes.
     */
    private val _testAlarmAt = MutableStateFlow<Long?>(null)
    val testAlarmAt: StateFlow<Long?> = _testAlarmAt.asStateFlow()

    /**
     * `false` when the last test alarm could only be registered inexactly
     * because exact-alarm permission is missing. The UI uses this to warn that
     * the test may arrive late instead of implying on-time delivery.
     */
    private val _testAlarmExact = MutableStateFlow(true)
    val testAlarmExact: StateFlow<Boolean> = _testAlarmExact.asStateFlow()

    init {
        // Make sure the alarm channel exists before any notification is posted.
        NotificationChannels.ensureCreated(context)
    }

    /** Re-reads every permission; call from `ON_RESUME`. */
    fun refresh() {
        _status.value = AlarmPermissions.status(context)
    }

    /** Schedules a test alarm one minute ahead (spec section 15.10). */
    fun scheduleTestAlarm() {
        val triggerAt = AlarmSchedule.testAlarmAt(System.currentTimeMillis(), minutes = 1)
        _testAlarmExact.value = scheduler.scheduleTestAlarm(minutes = 1)
        _testAlarmAt.value = triggerAt
        // Clear the "scheduled" label a little after the alarm should have
        // fired, so a stale message does not linger if the user stays put.
        viewModelScope.launch {
            delay(triggerAt - System.currentTimeMillis() + 5_000L)
            if (_testAlarmAt.value == triggerAt) _testAlarmAt.value = null
        }
    }
}
