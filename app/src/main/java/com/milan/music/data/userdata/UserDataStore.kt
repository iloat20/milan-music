package com.milan.music.data.userdata

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

@Serializable
data class UserData(
    val favorites: List<Long> = emptyList(),
)

private val Context.userDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "milan_user_data")

/**
 * Stores the "liked" track ids. The volume is small (hundreds at most), so a
 * serialized document is simpler and safer than a relational database, and it
 * avoids coupling the build to KSP.
 */
class UserDataStore(private val context: Context) {

    private val payloadKey = stringPreferencesKey("payload")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val data: Flow<UserData> = context.userDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences -> decode(preferences[payloadKey]) }

    private fun decode(raw: String?): UserData =
        raw?.let {
            runCatching { json.decodeFromString(UserData.serializer(), it) }.getOrNull()
        } ?: UserData()

    private suspend fun mutate(transform: (UserData) -> UserData) {
        context.userDataStore.edit { preferences ->
            val current = decode(preferences[payloadKey])
            preferences[payloadKey] =
                json.encodeToString(UserData.serializer(), transform(current))
        }
    }

    suspend fun toggleFavorite(trackId: Long) = mutate { userData ->
        userData.copy(
            favorites = if (trackId in userData.favorites) {
                userData.favorites - trackId
            } else {
                userData.favorites + trackId
            },
        )
    }
}
