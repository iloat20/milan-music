package com.milan.music.data.media

import android.content.Context
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.milan.music.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException

@Serializable
private data class CachedTrack(
    val id: Long = 0L,
    val uri: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: Long = 0L,
    val duration: Long = 0L,
    val sizeBytes: Long = 0L,
    val year: Int = 0,
    val trackNumber: Int = 0,
    val dateAdded: Long = 0L,
    val dateModified: Long = 0L,
)

private fun CachedTrack.toTrack(): Track = Track(
    id = id,
    uri = uri.toUri(),
    title = title,
    artist = artist,
    album = album,
    albumId = albumId,
    duration = duration,
    sizeBytes = sizeBytes,
    year = year,
    trackNumber = trackNumber,
    dateAdded = dateAdded,
    dateModified = dateModified,
)

private fun Track.toCached(): CachedTrack = CachedTrack(
    id = id,
    uri = uri.toString(),
    title = title,
    artist = artist,
    album = album,
    albumId = albumId,
    duration = duration,
    sizeBytes = sizeBytes,
    year = year,
    trackNumber = trackNumber,
    dateAdded = dateAdded,
    dateModified = dateModified,
)

private val Context.libraryCacheStore: DataStore<Preferences> by
    preferencesDataStore(name = "milan_library_cache")

/**
 * Persists the last scanned library so app launches do not hit MediaStore.
 *
 * A full MediaStore query costs hundreds of milliseconds on large libraries and
 * makes the first frame wait; showing the cached snapshot immediately and only
 * rescanning on an explicit user action keeps launches instant.
 */
class LibraryCacheStore(private val context: Context) {

    private val tracksKey = stringPreferencesKey("tracks")
    private val generationKey = longPreferencesKey("index_generation")
    private val countKey = longPreferencesKey("index_count")
    private val newestKey = longPreferencesKey("index_newest")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(CachedTrack.serializer())

    suspend fun load(): List<Track> = withContext(Dispatchers.Default) {
        val raw = preferences()[tracksKey] ?: return@withContext emptyList()
        runCatching { json.decodeFromString(serializer, raw) }
            .getOrDefault(emptyList())
            .map { it.toTrack() }
    }

    /**
     * Watermark stored alongside the snapshot, or null when the cache predates it.
     *
     * Null is not an error: it makes the first launch after an upgrade take the
     * full-scan path once, which then rewrites the cache with a stamp.
     */
    suspend fun loadStamp(): IndexStamp? = withContext(Dispatchers.Default) {
        val preferences = preferences()
        if (!preferences.contains(generationKey) && !preferences.contains(countKey)) {
            return@withContext null
        }
        IndexStamp(
            generation = preferences[generationKey],
            count = preferences[countKey] ?: -1L,
            newestModified = preferences[newestKey] ?: -1L,
        )
    }

    /**
     * Writes the snapshot and the watermark in one commit so they can never
     * disagree — a stale stamp next to fresh rows would make the next launch skip
     * a scan it actually needed.
     */
    suspend fun save(tracks: List<Track>, stamp: IndexStamp) = withContext(Dispatchers.Default) {
        val payload = json.encodeToString(serializer, tracks.map { it.toCached() })
        context.libraryCacheStore.edit { preferences ->
            preferences[tracksKey] = payload
            val generation = stamp.generation
            if (generation != null) {
                preferences[generationKey] = generation
            } else {
                preferences.remove(generationKey)
            }
            preferences[countKey] = stamp.count
            preferences[newestKey] = stamp.newestModified
        }
    }

    private suspend fun preferences(): Preferences = context.libraryCacheStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .first()
}
