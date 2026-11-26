package com.adhd.routineplanner.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes a fresh render to every placed [RunWidget].
 *
 * The widget can also refresh itself on the periodic update period declared in
 * its info XML, but that is coarse (minutes). This updater is called at the
 * moments the readout actually changes — run start, task boundary, finish and
 * stop — so the lock-screen card flips to the new task promptly.
 *
 * Updates are fire-and-forget on an app-scoped coroutine: a widget refresh must
 * never block the alarm receiver or the run view model.
 */
@Singleton
class RunWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // IO, not Default: rendering the widget reads DataStore and Room, which are
    // blocking I/O rather than CPU work.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Re-renders every placed widget from the current run session. */
    fun refresh() {
        scope.launch {
            runCatching { RunWidget().updateAll(context) }
        }
    }
}
