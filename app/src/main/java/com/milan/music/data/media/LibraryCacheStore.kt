package com.milan.music.data.media

import android.content.Context
import android.util.Log
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
    val albumArtist: String = "",
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
    albumArtist = albumArtist,
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
    albumArtist = albumArtist,
)

private val Context.libraryCacheStore: DataStore<Preferences> by
    preferencesDataStore(name = "milan_library_cache")

/**
 * Persists the last scanned library so app launches do not hit MediaStore.
 *
 * A full MediaStore query costs hundreds of milliseconds on large libraries and
 * makes the first frame wait; showing the cached snapshot immediately and only
 * rescanning on an explicit user action keeps launches instant.
 *
 * ## Where this stops scaling
 *
 * The rows live as one JSON string inside a Preferences DataStore. That is the right
 * shape for a cache written once per scan and read once per launch, and it keeps the
 * build free of Room and KSP. The cost is whole-value granularity: every [save]
 * re-encodes every row (~1 MB at 5 000 tracks) and DataStore rewrites the file in
 * full. At this app's write rate — one commit per scan — that is not worth a second
 * storage engine. If the library ever reaches five figures, move the payload to a
 * plain file beside the watermark and leave only the stamp in DataStore; the
 * [Snapshot] boundary means that change stays inside this class.
 */
class LibraryCacheStore(private val context: Context) {

    private val tracksKey = stringPreferencesKey("tracks")
    private val generationKey = longPreferencesKey("index_generation")
    private val countKey = longPreferencesKey("index_count")
    private val newestKey = longPreferencesKey("index_newest")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(CachedTrack.serializer())

    /** Rows and watermark exactly as they stood in a single DataStore read. */
    data class Snapshot(val tracks: List<Track>, val stamp: IndexStamp?)

    suspend fun load(): List<Track> = withContext(Dispatchers.Default) {
        decode(preferences()[tracksKey])
    }

    /**
     * Rows and watermark from one read, so the "rescan or not" decision cannot straddle
     * a concurrent [save].
     *
     * The two used to be separate calls, and a [save] landing between them pairs the
     * rows of one generation with the watermark of another: `rows = old, stamp = new`
     * makes the next launch skip a scan it needed, and `rows = new, stamp = old` costs
     * a redundant full scan. Neither corrupts the cache, but only a single read makes
     * the comparison mean what the caller assumes.
     */
    suspend fun snapshot(): Snapshot = withContext(Dispatchers.Default) {
        val stored = preferences()
        Snapshot(tracks = decode(stored[tracksKey]), stamp = stored.stamp())
    }

    private fun decode(raw: String?): List<Track> {
        if (raw == null) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }
            .onFailure { Log.w(TAG, "cached library payload unreadable; falling back to a scan", it) }
            .getOrDefault(emptyList())
            .map { it.toTrack() }
    }

    /**
     * Watermark stored alongside the snapshot, or null when the cache predates it.
     *
     * Null is not an error: it makes the first launch after an upgrade take the
     * full-scan path once, which then rewrites the cache with a stamp.
     */
    private fun Preferences.stamp(): IndexStamp? {
        if (!contains(generationKey) && !contains(countKey)) return null
        return IndexStamp(
            generation = this[generationKey],
            count = this[countKey] ?: -1L,
            newestModified = this[newestKey] ?: -1L,
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

    private companion object {
        const val TAG = "LibraryCache"
    }
}
