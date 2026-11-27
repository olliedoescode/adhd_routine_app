package com.adhd.routineplanner.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The active run session: which routine is running and when it started
 * (spec section 16).
 *
 * `(routineId, startMs, pausedAtMs)` is the whole state. Everything else —
 * elapsed time, the current task, progress — is derived from the wall clock by
 * [com.adhd.routineplanner.domain.runState], so a killed process resumes
 * correctly and a clock change self-corrects on the next tick.
 *
 * A manual Run stores `System.currentTimeMillis()`; a scheduled run stores
 * today's occurrence of the routine start time.
 *
 * ## Pause
 *
 * Pausing does not add a second clock. [pausedAtMs] records *when* the pause
 * began, and while it is non-null the run state is evaluated at that instant
 * instead of "now", so the countdown freezes where it was. Resuming shifts
 * [startMs] forward by the paused span, which restores the original elapsed
 * time with no other bookkeeping. A paused run therefore survives a process
 * death exactly like a running one.
 */
data class RunSession(
    val routineId: Long,
    val startMs: Long,
    /** Wall-clock instant the run was paused at, or `null` while running. */
    val pausedAtMs: Long? = null,
) {
    /** True while the run's clock is stopped. */
    val isPaused: Boolean get() = pausedAtMs != null
}

/**
 * Persists the single active [RunSession] in DataStore (spec section 16).
 *
 * There is at most one active run at a time, so the session is stored as a few
 * scalar keys rather than a serialised object. A missing or cleared session is
 * represented by `null`.
 */
@Singleton
class RunSessionStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    /** Emits the active session, or `null` when no run is in progress. */
    val session: Flow<RunSession?> = dataStore.data.map { prefs ->
        val routineId = prefs[KEY_ROUTINE_ID] ?: return@map null
        val startMs = prefs[KEY_START_MS] ?: return@map null
        RunSession(
            routineId = routineId,
            startMs = startMs,
            pausedAtMs = prefs[KEY_PAUSED_AT],
        )
    }

    /**
     * Emits `true` once the user has unlocked the phone at least once during the
     * current run.
     *
     * This is the discriminator between the two "an alarm fired" states:
     *
     *  - **Not present** (the phone was locked when the alarm fired): after the
     *    user stops the alarm they must be shown the lock-screen takeover, so
     *    they consciously acknowledge the routine.
     *  - **Present** (the user unlocked the phone *during* this run, so they are
     *    already demonstrably on their phone): a later task alarm must **not**
     *    drag them to the takeover again — they have already acknowledged being
     *    on the phone.
     *
     * It is reset to `false` whenever a run starts, so every run begins in the
     * "not present" state and the first alarm (or screen-on) always takes over.
     */
    val userPresent: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_USER_PRESENT] ?: false
    }

    /** Starts (or replaces) the active run, resetting the presence flag. */
    suspend fun start(routineId: Long, startMs: Long) {
        dataStore.edit { prefs ->
            prefs[KEY_ROUTINE_ID] = routineId
            prefs[KEY_START_MS] = startMs
            // A fresh run is never paused.
            prefs.remove(KEY_PAUSED_AT)
            // A fresh run always begins "not present": the first alarm or
            // screen-on must take over the lock screen.
            prefs[KEY_USER_PRESENT] = false
        }
    }

    /**
     * Stops the run's clock at [nowMs].
     *
     * Idempotent: a second pause keeps the first timestamp, so the span added
     * back on resume is exactly the time the user spent paused.
     */
    suspend fun pause(nowMs: Long) {
        dataStore.edit { prefs ->
            if (prefs[KEY_ROUTINE_ID] == null) return@edit
            if (prefs[KEY_PAUSED_AT] != null) return@edit
            prefs[KEY_PAUSED_AT] = nowMs
        }
    }

    /**
     * Restarts the run's clock at [nowMs], shifting the session start forward by
     * however long the pause lasted.
     *
     * Returns the shifted start time, or `null` when the run was not paused, so
     * the caller can re-arm the remaining task alarms against the new start.
     */
    suspend fun resume(nowMs: Long): Long? {
        var shifted: Long? = null
        dataStore.edit { prefs ->
            val pausedAt = prefs[KEY_PAUSED_AT] ?: return@edit
            val startMs = prefs[KEY_START_MS] ?: return@edit
            val span = (nowMs - pausedAt).coerceAtLeast(0L)
            val newStart = startMs + span
            prefs[KEY_START_MS] = newStart
            prefs.remove(KEY_PAUSED_AT)
            shifted = newStart
        }
        return shifted
    }

    /** Records that the user has unlocked the phone during the current run. */
    suspend fun markUserPresent() {
        dataStore.edit { prefs -> prefs[KEY_USER_PRESENT] = true }
    }

    /** Clears the active run, e.g. when the user confirms Exit. */
    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_ROUTINE_ID)
            prefs.remove(KEY_START_MS)
            prefs.remove(KEY_PAUSED_AT)
            prefs.remove(KEY_USER_PRESENT)
        }
    }

    private companion object {
        val KEY_ROUTINE_ID = longPreferencesKey("run_routine_id")
        val KEY_START_MS = longPreferencesKey("run_start_ms")
        val KEY_PAUSED_AT = longPreferencesKey("run_paused_at")
        val KEY_USER_PRESENT = booleanPreferencesKey("run_user_present")
    }
}
