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
 * `(routineId, startMs)` is the whole state. Everything else — elapsed time,
 * the current task, progress — is derived from the wall clock by
 * [com.adhd.routineplanner.domain.runState], so a killed process resumes
 * correctly and a clock change self-corrects on the next tick.
 *
 * A manual Run stores `System.currentTimeMillis()`; a scheduled run stores
 * today's occurrence of the routine start time.
 */
data class RunSession(
    val routineId: Long,
    val startMs: Long,
)

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
        RunSession(routineId = routineId, startMs = startMs)
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
            // A fresh run always begins "not present": the first alarm or
            // screen-on must take over the lock screen.
            prefs[KEY_USER_PRESENT] = false
        }
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
            prefs.remove(KEY_USER_PRESENT)
        }
    }

    private companion object {
        val KEY_ROUTINE_ID = longPreferencesKey("run_routine_id")
        val KEY_START_MS = longPreferencesKey("run_start_ms")
        val KEY_USER_PRESENT = booleanPreferencesKey("run_user_present")
    }
}
