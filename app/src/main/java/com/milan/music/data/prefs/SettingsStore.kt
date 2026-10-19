package com.milan.music.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.milan.music.data.model.SortMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "milan_settings")

class SettingsStore(private val context: Context) {

    private val sortModeKey = stringPreferencesKey("sort_mode")

    val sortMode: Flow<SortMode> = context.settingsDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences ->
            SortMode.entries.firstOrNull { it.name == preferences[sortModeKey] } ?: SortMode.TITLE
        }

    suspend fun setSortMode(mode: SortMode) {
        context.settingsDataStore.edit { preferences -> preferences[sortModeKey] = mode.name }
    }
}
