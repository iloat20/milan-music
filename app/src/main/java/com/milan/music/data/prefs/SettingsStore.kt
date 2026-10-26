package com.milan.music.data.prefs

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.milan.music.data.model.SortMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by
    preferencesDataStore(
        name = "milan_settings",
        // Same reasoning as UserDataStore: the read fallback only hides the damage,
        // while every later edit would keep throwing on the corrupt file.
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    )

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
        try {
            context.settingsDataStore.edit { preferences -> preferences[sortModeKey] = mode.name }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed preference write must not crash the sort menu. The UI keeps the
            // old order; the next launch falls back to the default.
            Log.w(TAG, "sort mode write failed", e)
        }
    }

    private companion object {
        const val TAG = "Settings"
    }
}
