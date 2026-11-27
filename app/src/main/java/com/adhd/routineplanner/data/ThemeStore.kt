package com.adhd.routineplanner.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.adhd.routineplanner.ui.theme.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the user's chosen [AppTheme] in the app's shared preferences DataStore.
 *
 * The theme id is persisted as a string so themes can be added, renamed or
 * retired without a database migration: an unknown id falls back to
 * [AppTheme.DEFAULT], which is the lake blue the app shipped with.
 */
@Singleton
class ThemeStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val theme: Flow<AppTheme> = dataStore.data
        .map { prefs -> AppTheme.fromId(prefs[KEY_THEME]) }
        // Every unrelated settings change re-emits the whole preference map;
        // only a genuine theme change should ripple out to the activity.
        .distinctUntilChanged()

    suspend fun setTheme(theme: AppTheme) {
        dataStore.edit { prefs ->
            prefs[KEY_THEME] = theme.id
        }
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("app_theme")
    }
}
